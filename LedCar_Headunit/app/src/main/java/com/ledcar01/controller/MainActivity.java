package com.ledcar01.controller;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements BleDeviceManager.Listener {

    private static final int[] DEFAULT_COLORS = {
            Color.parseColor("#E24B4A"),
            Color.parseColor("#639922"),
            Color.parseColor("#378ADD"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#EF9F27"),
            Color.parseColor("#D4537E"),
            Color.parseColor("#7F77DD"),
            Color.parseColor("#5DCAA5"),
            Color.parseColor("#D85A30"),
            Color.parseColor("#185FA5"),
    };

    private static final long RETRY_INTERVAL_MS = 8000;

    private BleDeviceManager bleManager;
    private SavedColorStore store;
    private UpdateInstaller updateInstaller;

    private TextView tvDeviceSummary;
    private TextView scanStatusText;
    private Button btnConnect;
    private Button btnPower;
    private Button btnSettings;
    private FrameLayout zoneToggleContainer;
    private MarchingAntsView zoneMarchingBorder;
    private ShimmerDrawable scanShimmer;
    private Button btnZoneRgb;
    private Button btnZoneDmx;
    private Button btnMode;
    private LinearLayout colorCard;
    private LinearLayout bottomBar;
    private HsvColorWheelView colorWheel;
    private TextView tvRgbReadout;
    private LinearLayout defaultColorsRow;
    private Button btnSaveColor;
    private HorizontalScrollView savedEggsScroll;
    private LinearLayout savedEggsRow;
    private SeekBar seekBrightness;
    private TextView tvBrightnessValue;
    private SeekBar seekSpeed;
    private TextView tvSpeedValue;
    private ImageView imgFootwellLight;
    private ImageView imgNeonStripLight;
    private AmbientLightingController ambientController;

    private boolean rgbActive = true;
    private boolean dmxActive = true;
    private int currentR = 255;
    private int currentG = 80;
    private int currentB = 80;
    // Each zone remembers its own color independently, so switching the
    // active zone (RGB <-> DMX) restores that zone's own color instead of
    // showing whatever was last edited for the other zone.
    private int rgbR = 255, rgbG = 80, rgbB = 80;
    private int dmxR = 255, dmxG = 80, dmxB = 80;
    private int currentBrightness = 80;
    // Same idea as the per-zone color memory above, but for brightness.
    private int rgbBrightness = 80;
    private int dmxBrightness = 80;
    private int currentSpeed = 50;
    private boolean poweredOn = true;
    private ObjectAnimator powerPulseAnimator;

    private static final long SEND_THROTTLE_MS = 100;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final Throttler colorThrottle = new Throttler(SEND_THROTTLE_MS);
    private final Throttler brightnessThrottle = new Throttler(SEND_THROTTLE_MS);
    private final Throttler speedThrottle = new Throttler(SEND_THROTTLE_MS);

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private boolean firstFailureToastShown = false;
    private boolean ambientLit = false;
    private boolean isScanning = false;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (bleManager.getConnectedCount() == 0) {
                if (!firstFailureToastShown) {
                    Toast.makeText(MainActivity.this, R.string.reconnecting, Toast.LENGTH_SHORT).show();
                    firstFailureToastShown = true;
                }
                requestPermissionsThenScan();
            }
            retryHandler.postDelayed(this, RETRY_INTERVAL_MS);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
                boolean allGranted = !grants.containsValue(false);
                if (allGranted) {
                    proceedToScan();
                } else {
                    Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bleManager.isBluetoothEnabled()) {
                    bleManager.startScan();
                } else {
                    Toast.makeText(this, R.string.bluetooth_disabled, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bleManager = BleDeviceManager.getInstance(this);
        store = new SavedColorStore(this);
        // App defaults to powered-on: seed the manager's state-replay cache so
        // every device that connects (now or later) receives power-on automatically.
        bleManager.broadcastCommand(Car01Protocol.powerOn());

        restoreLastState();
        bindViews();
        buildDefaultSwatches();
        rebuildSavedEggs();
        wireControls();
        applyZoneUi();
        updateRgbReadout();
        setConnectedControlsEnabled(bleManager.getConnectedCount() > 0);

        checkForUpdate(); // fire-and-forget; silent unless a newer release is found
    }

    /* ====================================================== */
    /*  In-app update check (GitHub Releases)                 */
    /* ====================================================== */

    /**
     * Checks FuzzBC/ledcar_vw_cc's latest GitHub release against this build's
     * versionCode. Only surfaces a dialog when a strictly newer version is
     * found; stays silent on "up to date" or network/parse errors (logged
     * only) so it never nags on a flaky connection.
     */
    private void checkForUpdate() {
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(String tagName, int versionCode, String apkUrl, String releaseNotes) {
                // Matches app/build.gradle's own versionName formula
                // (versionMajor + "." + versionCode, zero-padded to 3
                // digits) so what's shown here is the same number the user
                // would see in the system app-info screen after installing
                // it - not the raw GitHub tag, which is just an internal
                // build counter.
                String displayVersion = BuildConfig.VERSION_MAJOR + "." + String.format(Locale.US, "%03d", versionCode);
                showUpdateAvailableDialog(displayVersion, apkUrl, tagName, releaseNotes);
            }

            @Override
            public void onUpToDate() {
                // nothing to do
            }

            @Override
            public void onError(String message) {
                android.util.Log.w("UpdateChecker", "update check failed: " + message);
            }
        });
    }

    private void showUpdateAvailableDialog(String displayVersion, String apkUrl, String tagName, String releaseNotes) {
        new UpdateAvailableDialog(this, displayVersion, releaseNotes,
                () -> startUpdateDownload(apkUrl, displayVersion, tagName)).show();
    }

    /**
     * Kicks off the APK download behind a live progress dialog (percent,
     * size, speed, Cancel). On API 26+, installing from a downloaded file
     * requires the user to have granted "install unknown apps" for this app
     * first - if not granted, sends them straight to that settings screen
     * instead of downloading (they can just tap Update again after).
     */
    private void startUpdateDownload(String apkUrl, String displayVersion, String tagName) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"install unknown apps\" for this app, then check for updates again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }

        if (updateInstaller == null) updateInstaller = new UpdateInstaller(this);
        UpdateProgressDialog progressDialog = new UpdateProgressDialog(this, displayVersion, () -> {
            updateInstaller.cancel();
            Toast.makeText(this, "Update cancelled", Toast.LENGTH_SHORT).show();
        });
        progressDialog.show();

        updateInstaller.download(apkUrl, tagName, new UpdateInstaller.ProgressListener() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speedBps) {
                if (percent < 0) {
                    progressDialog.setStatus(downloaded + " B downloaded...");
                } else {
                    progressDialog.setProgress(percent, downloaded, total, speedBps);
                }
            }

            @Override
            public void onComplete() {
                progressDialog.dismiss();
            }

            @Override
            public void onFailed(String reason) {
                progressDialog.dismiss();
                Toast.makeText(MainActivity.this, "Update download failed: " + reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bleManager.setListener(this);
        if (bleManager.getConnectedCount() == 0) {
            requestPermissionsThenScan();
        }
        retryHandler.removeCallbacks(retryRunnable);
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bleManager.setListener(null);
        retryHandler.removeCallbacks(retryRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Deliberately not touching bleManager here: it is an Application-scoped
        // singleton and must survive Activity recreation (rotation) — only the
        // process ending should end the connections.
        debounceHandler.removeCallbacksAndMessages(null);
    }

    private void restoreLastState() {
        rgbActive = store.getRgbActive();
        dmxActive = store.getDmxActive();
        SavedColorStore.ColorState last = store.getLastColorState();
        currentR = last.r;
        currentG = last.g;
        currentB = last.b;
        currentBrightness = last.brightness;
        currentSpeed = store.getLastSpeed();

        SavedColorStore.ColorState savedRgb = store.getZoneColor(Car01Protocol.Zone.RGB, currentR, currentG, currentB);
        rgbR = savedRgb.r;
        rgbG = savedRgb.g;
        rgbB = savedRgb.b;
        SavedColorStore.ColorState savedDmx = store.getZoneColor(Car01Protocol.Zone.DMX, currentR, currentG, currentB);
        dmxR = savedDmx.r;
        dmxG = savedDmx.g;
        dmxB = savedDmx.b;
        rgbBrightness = dmxBrightness = currentBrightness;
    }

    private void bindViews() {
        tvDeviceSummary = findViewById(R.id.tvDeviceSummary);
        scanStatusText = findViewById(R.id.scanStatusText);
        btnConnect = findViewById(R.id.btnConnect);
        btnPower = findViewById(R.id.btnPower);
        btnSettings = findViewById(R.id.btnSettings);
        zoneToggleContainer = findViewById(R.id.zoneToggleContainer);
        // Hard-clip everything inside to the pill's true rounded silhouette -
        // without this, a button's own background or the border glow's blur
        // can show a sliver of color in the square corner wedges outside the
        // rounded drawables but still inside this container's rectangular bounds.
        zoneToggleContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        zoneToggleContainer.setClipToOutline(true);
        zoneMarchingBorder = findViewById(R.id.zoneMarchingBorder);
        btnZoneRgb = findViewById(R.id.btnZoneRgb);
        btnZoneDmx = findViewById(R.id.btnZoneDmx);
        btnMode = findViewById(R.id.btnMode);
        colorCard = findViewById(R.id.colorCard);
        bottomBar = findViewById(R.id.bottomBar);
        colorWheel = findViewById(R.id.colorWheel);
        tvRgbReadout = findViewById(R.id.tvRgbReadout);
        defaultColorsRow = findViewById(R.id.defaultColorsRow);
        btnSaveColor = findViewById(R.id.btnSaveColor);
        savedEggsScroll = findViewById(R.id.savedEggsScroll);
        savedEggsRow = findViewById(R.id.savedEggsRow);
        seekBrightness = findViewById(R.id.seekBrightness);
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue);
        seekSpeed = findViewById(R.id.seekSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        imgFootwellLight = findViewById(R.id.imgFootwellLight);
        imgNeonStripLight = findViewById(R.id.imgNeonStripLight);
        ambientController = new AmbientLightingController(imgFootwellLight, imgNeonStripLight);

        ImageView imgBaseInterior = findViewById(R.id.imgBaseInterior);
        final int ambientCornerRadiusPx = dp(12);
        ViewOutlineProvider ambientCornerClip = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ambientCornerRadiusPx);
            }
        };
        for (ImageView ambientLayer : new ImageView[]{imgBaseInterior, imgFootwellLight, imgNeonStripLight}) {
            ambientLayer.setOutlineProvider(ambientCornerClip);
            ambientLayer.setClipToOutline(true);
        }

        seekBrightness.setProgress(currentBrightness);
        tvBrightnessValue.setText(currentBrightness + "%");
        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + "%");
        colorWheel.setColor(currentR, currentG, currentB);
        refreshAmbientColors();
        refreshAmbientBrightness();
    }

    private void buildDefaultSwatches() {
        int size = dp(30);
        int margin = dp(7);
        for (int color : DEFAULT_COLORS) {
            View swatch = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.border));
            swatch.setBackground(bg);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(margin);
            swatch.setLayoutParams(params);
            swatch.setOnClickListener(v -> applyColor(Color.red(color), Color.green(color), Color.blue(color)));
            defaultColorsRow.addView(swatch);
        }
    }

    /**
     * Rebuilds the saved-eggs row from scratch. Called after every add/remove
     * so each egg's tap/long-press listener always closes over the correct,
     * up-to-date index - simpler than patching a single view in place.
     */
    private void rebuildSavedEggs() {
        savedEggsRow.removeAllViews();
        List<SavedColorStore.SavedEgg> eggs = store.getEggs();
        int eggWidth = dp(20);
        int eggHeight = dp(35);
        int margin = dp(10);
        for (int i = 0; i < eggs.size(); i++) {
            SavedColorStore.SavedEgg egg = eggs.get(i);
            int index = i;

            // Pill shape (matches the app's own button/zone-selector language)
            // split by a diagonal instead of a straight line - DMX toward the
            // left, RGB toward the right. One tap target for the whole swatch.
            DiagonalSwatchView eggView = new DiagonalSwatchView(this);
            eggView.setColors(Color.rgb(egg.dmx.r, egg.dmx.g, egg.dmx.b), Color.rgb(egg.rgb.r, egg.rgb.g, egg.rgb.b));

            eggView.setOnClickListener(v -> applySavedEgg(egg));
            eggView.setOnLongClickListener(v -> {
                showEggActionDialog(index);
                return true;
            });

            LinearLayout.LayoutParams eggParams = new LinearLayout.LayoutParams(eggWidth, eggHeight);
            eggParams.setMarginEnd(margin);
            eggView.setLayoutParams(eggParams);
            savedEggsRow.addView(eggView);
        }
    }

    /**
     * A saved egg restores both zones at once - DMX color+brightness and RGB
     * color+brightness - as a single tap, instead of the old per-half
     * behavior that only touched one zone. Both zones are activated so the
     * zone pill and ambient preview immediately reflect the full restore.
     */
    private void applySavedEgg(SavedColorStore.SavedEgg egg) {
        if (bleManager.getConnectedCount() == 0) {
            Toast.makeText(this, "Connect a device to change color", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!poweredOn) {
            Toast.makeText(this, "Turn the power on to change color", Toast.LENGTH_SHORT).show();
            return;
        }
        rgbActive = true;
        dmxActive = true;
        store.setActiveZones(rgbActive, dmxActive);
        // Restoring a saved egg leaves whatever effect was previewing on either zone.
        ambientController.stopFootwellEffectPreview();
        ambientController.stopNeonStripEffectPreview();

        rgbR = egg.rgb.r;
        rgbG = egg.rgb.g;
        rgbB = egg.rgb.b;
        rgbBrightness = egg.rgb.brightness;
        dmxR = egg.dmx.r;
        dmxG = egg.dmx.g;
        dmxB = egg.dmx.b;
        dmxBrightness = egg.dmx.brightness;
        store.setZoneColor(Car01Protocol.Zone.RGB, rgbR, rgbG, rgbB);
        store.setZoneColor(Car01Protocol.Zone.DMX, dmxR, dmxG, dmxB);

        currentR = dmxR;
        currentG = dmxG;
        currentB = dmxB;
        currentBrightness = dmxBrightness;
        colorWheel.setColor(currentR, currentG, currentB);
        updateRgbReadout();
        seekBrightness.setProgress(currentBrightness);
        tvBrightnessValue.setText(currentBrightness + "%");
        applyZoneUi();
        store.setLastColor(currentR, currentG, currentB);
        store.setLastBrightness(currentBrightness);
        refreshAmbientColors();
        refreshAmbientBrightness();

        bleManager.broadcastCommand(Car01Protocol.setColor(dmxR, dmxG, dmxB, Car01Protocol.Zone.DMX));
        bleManager.broadcastCommand(Car01Protocol.setBrightness(dmxBrightness, Car01Protocol.Zone.DMX));
        bleManager.broadcastCommand(Car01Protocol.setColor(rgbR, rgbG, rgbB, Car01Protocol.Zone.RGB));
        bleManager.broadcastCommand(Car01Protocol.setBrightness(rgbBrightness, Car01Protocol.Zone.RGB));
    }

    /** Long-press on a saved egg: replace it with the current live color/brightness, or remove it. */
    private void showEggActionDialog(int index) {
        new EggActionDialog(this, new EggActionDialog.Callback() {
            @Override
            public void onUpdate() {
                SavedColorStore.ColorState dmxColor = new SavedColorStore.ColorState(dmxR, dmxG, dmxB, dmxBrightness);
                SavedColorStore.ColorState rgbColor = new SavedColorStore.ColorState(rgbR, rgbG, rgbB, rgbBrightness);
                store.updateEgg(index, dmxColor, rgbColor);
                rebuildSavedEggs();
            }

            @Override
            public void onRemove() {
                store.removeEgg(index);
                rebuildSavedEggs();
            }
        }).show();
    }

    /** Prevents saving the exact same DMX+RGB combination as an already-saved egg. */
    private boolean isDuplicateEgg(SavedColorStore.ColorState dmx, SavedColorStore.ColorState rgb) {
        for (SavedColorStore.SavedEgg egg : store.getEggs()) {
            if (colorStateEquals(egg.dmx, dmx) && colorStateEquals(egg.rgb, rgb)) {
                return true;
            }
        }
        return false;
    }

    private boolean colorStateEquals(SavedColorStore.ColorState a, SavedColorStore.ColorState b) {
        return a.r == b.r && a.g == b.g && a.b == b.b && a.brightness == b.brightness;
    }

    private void wireControls() {
        btnConnect.setOnClickListener(v -> {
            // Pressing Scan locks everything down the same way a fresh, never-
            // connected launch does - even if a device was already connected -
            // so a rescan never leaves controls half-usable while it runs.
            isScanning = true;
            setConnectedControlsEnabled(false);
            requestPermissionsThenScan();
        });

        btnPower.setOnClickListener(v -> {
            poweredOn = !poweredOn;
            bleManager.broadcastCommand(poweredOn ? Car01Protocol.powerOn() : Car01Protocol.powerOff());
            updatePowerVisualState();
            updateControlsLockState();
        });

        btnSettings.setOnClickListener(v -> new SettingsDialog(this, store, new SettingsDialog.Callback() {
            @Override
            public void onWelcomeMode(boolean on) {
                bleManager.broadcastCommand(Car01Protocol.setWelcomeMode(on));
            }

            @Override
            public void onConfigConfirmed(int pixelCount, int colorOrderId) {
                bleManager.broadcastCommand(Car01Protocol.setConfigSpi(pixelCount, colorOrderId));
            }
        }).show());

        btnZoneRgb.setOnClickListener(v -> selectSingleZone(Car01Protocol.Zone.RGB));
        btnZoneDmx.setOnClickListener(v -> selectSingleZone(Car01Protocol.Zone.DMX));
        btnZoneRgb.setOnLongClickListener(v -> {
            selectBothZones();
            return true;
        });
        btnZoneDmx.setOnLongClickListener(v -> {
            selectBothZones();
            return true;
        });

        btnMode.setOnClickListener(v -> new ModePickerDialog(this,
                store.getLastModeId(Car01Protocol.Zone.RGB), store.getLastModeId(Car01Protocol.Zone.DMX),
                new ModePickerDialog.OnModeSelectedListener() {
            @Override
            public void onModeSelected(int id, String name, Car01Protocol.Zone zone) {
                activateZone(zone);
                bleManager.broadcastCommand(Car01Protocol.setMode(id, zone));
                store.setLastModeId(zone, id);
                // Ambient preview mimics the selected effect instead of a flat
                // tint, so picking a mode reads the same way on-screen as it
                // will on the real strip.
                List<Integer> colors = EffectAdapter.colorsForName(name);
                boolean jump = name.toLowerCase(Locale.US).contains("jump");
                if (zone == Car01Protocol.Zone.RGB) {
                    ambientController.previewFootwellEffect(colors, jump);
                } else {
                    ambientController.previewNeonStripEffect(colors, jump);
                }
                // A mode is now active - Speed becomes usable.
                updateControlsLockState();
            }

            @Override
            public void onStaticColorSelected(Car01Protocol.Zone zone) {
                activateZone(zone);
                // Cancelling a mode resends the static color AND brightness for
                // both zones (not just the one being viewed) - the lamp was
                // running a mode on both, so both need to be pulled back to a
                // flat color or one zone is left mid-effect.
                bleManager.broadcastCommand(Car01Protocol.setColor(rgbR, rgbG, rgbB, Car01Protocol.Zone.RGB));
                bleManager.broadcastCommand(Car01Protocol.setColor(dmxR, dmxG, dmxB, Car01Protocol.Zone.DMX));
                bleManager.broadcastCommand(Car01Protocol.setBrightness(rgbBrightness, Car01Protocol.Zone.RGB));
                bleManager.broadcastCommand(Car01Protocol.setBrightness(dmxBrightness, Car01Protocol.Zone.DMX));
                // Cancelling a mode clears the selection dot on both tabs, not
                // just the one being viewed - neither zone is running an
                // effect anymore once this dialog closes.
                store.setLastModeId(Car01Protocol.Zone.RGB, -1);
                store.setLastModeId(Car01Protocol.Zone.DMX, -1);
                // Stop whichever preview was running and drop back to the flat
                // static color for both zones, matching the dot-clear above.
                ambientController.stopFootwellEffectPreview();
                ambientController.stopNeonStripEffectPreview();
                refreshAmbientColors();
                // No mode active on either zone anymore - Speed goes back to disabled.
                updateControlsLockState();
            }
        }).show());

        colorWheel.setOnColorChangeListener((r, g, b, fromUser) -> {
            currentR = r;
            currentG = g;
            currentB = b;
            rememberColorForActiveZones();
            updateRgbReadout();
            applyZoneUi();
            if (fromUser) {
                scheduleColorSend();
            }
        });

        colorWheel.setOnColorLongPressListener((r, g, b) -> new RgbSliderDialog(this, r, g, b, (rr, gg, bb) ->
                applyColor(rr, gg, bb)).show());

        // Once the finger lifts, drop any trailing throttled send still queued
        // from the last drag update instead of letting it fire ~100ms later -
        // the live sends during the drag already got the device close enough,
        // and a send that lands after release just reads as pointless spam.
        colorWheel.setOnTouchEndListener(colorThrottle::cancelPending);

        tvRgbReadout.setOnClickListener(v -> new RgbSliderDialog(this, currentR, currentG, currentB, (r, g, b) ->
                applyColor(r, g, b)).show());

        btnSaveColor.setOnClickListener(v -> {
            // Appends a new egg using each zone's own remembered live color -
            // the wheel/zone state itself is the "next empty egg to modify";
            // Save just freezes its current values as a permanent entry.
            SavedColorStore.ColorState dmxColor = new SavedColorStore.ColorState(dmxR, dmxG, dmxB, dmxBrightness);
            SavedColorStore.ColorState rgbColor = new SavedColorStore.ColorState(rgbR, rgbG, rgbB, rgbBrightness);
            if (isDuplicateEgg(dmxColor, rgbColor)) {
                Toast.makeText(this, "This color is already saved", Toast.LENGTH_SHORT).show();
                return;
            }
            store.addEgg(dmxColor, rgbColor);
            rebuildSavedEggs();
            // New egg lands at the right end of the row - scroll it into view.
            savedEggsScroll.post(() -> savedEggsScroll.fullScroll(View.FOCUS_RIGHT));
            Toast.makeText(this, "Color saved", Toast.LENGTH_SHORT).show();
        });

        seekBrightness.setOnSeekBarChangeListener(simpleDebouncedListener(percent -> {
            currentBrightness = percent;
            rememberBrightnessForActiveZones();
            tvBrightnessValue.setText(percent + "%");
            store.setLastBrightness(percent);
            brightnessThrottle.request(() -> broadcastBrightnessForActiveZones(percent));
        }));

        seekSpeed.setOnSeekBarChangeListener(simpleDebouncedListener(percent -> {
            currentSpeed = percent;
            tvSpeedValue.setText(percent + "%");
            store.setLastSpeed(percent);
            speedThrottle.request(() -> broadcastForActiveZones(zone -> Car01Protocol.setSpeed(percent, zone)));
        }));
    }

    /**
     * Tapping a zone button selects only that zone, deselecting the other,
     * and restores that zone's own remembered color and brightness to the
     * wheel/readout/slider - a pure local UI sync (no broadcast) so switching
     * back and forth between RGB and DMX never loses either zone's last
     * values.
     */
    private void selectSingleZone(Car01Protocol.Zone zone) {
        rgbActive = zone == Car01Protocol.Zone.RGB;
        dmxActive = zone == Car01Protocol.Zone.DMX;
        store.setActiveZones(rgbActive, dmxActive);
        if (zone == Car01Protocol.Zone.RGB) {
            currentR = rgbR;
            currentG = rgbG;
            currentB = rgbB;
            currentBrightness = rgbBrightness;
        } else {
            currentR = dmxR;
            currentG = dmxG;
            currentB = dmxB;
            currentBrightness = dmxBrightness;
        }
        colorWheel.setColor(currentR, currentG, currentB);
        updateRgbReadout();
        seekBrightness.setProgress(currentBrightness);
        tvBrightnessValue.setText(currentBrightness + "%");
        applyZoneUi();
    }

    /** Long-pressing either zone button selects both at once. */
    private void selectBothZones() {
        rgbActive = true;
        dmxActive = true;
        store.setActiveZones(rgbActive, dmxActive);
        applyZoneUi();
    }

    private void activateZone(Car01Protocol.Zone zone) {
        if (zone == Car01Protocol.Zone.RGB) {
            rgbActive = true;
        } else {
            dmxActive = true;
        }
        store.setActiveZones(rgbActive, dmxActive);
        applyZoneUi();
    }

    /**
     * Zone selector: btnZoneRgb always shows RGB's own live color, btnZoneDmx
     * always shows DMX's own live color - both halves stay colored and track
     * whichever zone's color is currently being edited (both update together
     * when both zones are active). Selection is shown separately by
     * zoneMarchingBorder, a continuously animated dashed border shaped to the
     * selected half (rounded on the pill's true outer end, square on the
     * inner seam) or the whole pill, sliding smoothly between left/right via
     * translationX.
     */
    private static final int ZONE_PILL_WIDTH_DP = 180;
    private static final int ZONE_PILL_HEIGHT_DP = 40;
    private static final long ZONE_SLIDE_DURATION_MS = 220;
    /**
     * How far the "both zones" effect's glow is allowed to bleed past the
     * pill's true edge - zoneToggleContainer reserves this much padding
     * around the pill (see activity_main.xml) specifically so there's real
     * canvas room for it. Single-zone dash mode ignores this and hugs the
     * pill exactly as before. Kept a few dp under that XML padding (12dp) so
     * the glow's own blur fades out before reaching the container's hard
     * clip edge, instead of getting cut off right at its softest, faintest
     * point.
     */
    private static final int ZONE_GLOW_MARGIN_DP = 8;

    private enum ZonePillHalf { LEFT, RIGHT, FULL }

    private void applyZoneUi() {
        btnZoneRgb.setBackground(buildHalfFill(Color.rgb(rgbR, rgbG, rgbB), ZonePillHalf.LEFT));
        btnZoneDmx.setBackground(buildHalfFill(Color.rgb(dmxR, dmxG, dmxB), ZonePillHalf.RIGHT));

        boolean both = rgbActive && dmxActive;
        ZonePillHalf half = both ? ZonePillHalf.FULL : (rgbActive ? ZonePillHalf.LEFT : ZonePillHalf.RIGHT);
        // DMX-only marches the opposite way around the pill from RGB, so the two zones read as distinct.
        zoneMarchingBorder.setReversed(half == ZonePillHalf.RIGHT);
        // Both zones active: bounce-scanner effect instead of dashes meeting at the seam.
        zoneMarchingBorder.setZoneColors(Color.rgb(rgbR, rgbG, rgbB), Color.rgb(dmxR, dmxG, dmxB));
        zoneMarchingBorder.setBreathingMode(both);

        int fullWidth = dp(ZONE_PILL_WIDTH_DP);
        int height = dp(ZONE_PILL_HEIGHT_DP);
        int halfWidth = fullWidth / 2;
        int borderWidth = both ? fullWidth : halfWidth;
        float targetTranslationX = half == ZonePillHalf.RIGHT ? halfWidth : 0f;

        // Size the border view a bit bigger than the pill itself in "both"
        // mode and pull it back with negative margins so it's still centered
        // on the same spot - the extra canvas around the crisp band is
        // exactly where the "both zones" effect's outward glow gets to
        // spread. Single-zone dash mode keeps glowMargin at 0 and hugs the
        // pill exactly as before.
        int glowMargin = both ? dp(ZONE_GLOW_MARGIN_DP) : 0;
        int targetWidth = borderWidth + glowMargin * 2;
        int targetHeight = height + glowMargin * 2;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) zoneMarchingBorder.getLayoutParams();
        boolean widthChanged = params.width != targetWidth;
        params.width = targetWidth;
        params.height = targetHeight;
        params.setMarginStart(-glowMargin);
        params.topMargin = -glowMargin;
        zoneMarchingBorder.setLayoutParams(params);
        zoneMarchingBorder.setCornerRadii(cornerRadiiForHalf(half));

        zoneMarchingBorder.animate().cancel();
        if (widthChanged) {
            // Width only changes when entering/leaving "both" - jump instantly
            // rather than sliding a resize, which would look like a stretch.
            zoneMarchingBorder.setTranslationX(targetTranslationX);
        } else {
            zoneMarchingBorder.animate().translationX(targetTranslationX).setDuration(ZONE_SLIDE_DURATION_MS).start();
        }
    }

    /** Per-corner radii (GradientDrawable/Path format) for a half: rounded on the pill's true outer end, square on the inner seam. */
    private float[] cornerRadiiForHalf(ZonePillHalf half) {
        float r = dp(ZONE_PILL_HEIGHT_DP) / 2f;
        switch (half) {
            case LEFT:
                return new float[]{r, r, 0, 0, 0, 0, r, r};
            case RIGHT:
                return new float[]{0, 0, r, r, r, r, 0, 0};
            default:
                return new float[]{r, r, r, r, r, r, r, r};
        }
    }

    private Drawable buildHalfFill(int color, ZonePillHalf half) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadii(cornerRadiiForHalf(half));
        d.setColor(color);
        return d;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * While off, everything except the power button dims, and the power
     * button itself glows red + gently pulses to draw the eye back to it.
     * While on, the layout is fully visible and the button shows a green
     * glow ring too - a calmer, non-pulsing effect so it doesn't keep
     * demanding attention the way the off state does.
     */
    private void updatePowerVisualState() {
        int dangerColor = ContextCompat.getColor(this, R.color.danger);
        int successColor = ContextCompat.getColor(this, R.color.success);

        if (poweredOn) {
            stopPowerPulse();
            btnPower.setBackground(buildOvalGlow(successColor));
            setDimmed(false);
        } else {
            btnPower.setBackground(buildOvalGlow(dangerColor));
            startPowerPulse();
            setDimmed(true);
        }
    }

    private void setDimmed(boolean dimmed) {
        float alpha = dimmed ? 0.35f : 1f;
        zoneToggleContainer.setAlpha(alpha);
        colorCard.setAlpha(alpha);
        bottomBar.setAlpha(alpha);
    }

    private void startPowerPulse() {
        if (powerPulseAnimator != null && powerPulseAnimator.isRunning()) {
            return;
        }
        powerPulseAnimator = ObjectAnimator.ofFloat(btnPower, "alpha", 1f, 0.55f);
        powerPulseAnimator.setDuration(900);
        powerPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        powerPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        powerPulseAnimator.start();
    }

    private void stopPowerPulse() {
        if (powerPulseAnimator != null) {
            powerPulseAnimator.cancel();
            powerPulseAnimator = null;
        }
        btnPower.setAlpha(1f);
    }

    private Drawable buildOvalGlow(int color) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(dp(5), withAlpha(color, 80));

        GradientDrawable middle = new GradientDrawable();
        middle.setShape(GradientDrawable.OVAL);
        middle.setColor(Color.TRANSPARENT);
        middle.setStroke(dp(3), withAlpha(color, 170));

        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(color);

        // Bolt icon as its own layer instead of button text - a real vector
        // shape renders identically on every device, unlike the old Unicode
        // power-symbol glyph which depended on the system font.
        Drawable bolt = ContextCompat.getDrawable(this, R.drawable.ic_bolt);

        LayerDrawable layered = new LayerDrawable(new Drawable[]{outer, middle, core, bolt});
        layered.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        layered.setLayerInset(2, dp(6), dp(6), dp(6), dp(6));
        layered.setLayerInset(3, dp(17), dp(17), dp(17), dp(17));
        return layered;
    }

    private interface ZoneCommandBuilder {
        byte[] build(Car01Protocol.Zone zone);
    }

    private void broadcastForActiveZones(ZoneCommandBuilder builder) {
        if (rgbActive) {
            bleManager.broadcastCommand(builder.build(Car01Protocol.Zone.RGB));
        }
        if (dmxActive) {
            bleManager.broadcastCommand(builder.build(Car01Protocol.Zone.DMX));
        }
    }

    /**
     * When both zones are active and getting the same value (color wheel /
     * brightness slider drive both at once), send the single "LED tab sync"
     * packet instead of one command per zone - fewer writes, and matches how
     * the vendor app itself behaves in this situation.
     */
    private void broadcastColorForActiveZones(int r, int g, int b) {
        if (rgbActive && dmxActive) {
            bleManager.broadcastCommand(Car01Protocol.setColorBoth(r, g, b));
        } else {
            broadcastForActiveZones(zone -> Car01Protocol.setColor(r, g, b, zone));
        }
    }

    private void broadcastBrightnessForActiveZones(int percent) {
        if (rgbActive && dmxActive) {
            bleManager.broadcastCommand(Car01Protocol.setBrightnessBoth(percent));
        } else {
            broadcastForActiveZones(zone -> Car01Protocol.setBrightness(percent, zone));
        }
    }

    private void applyColor(int r, int g, int b) {
        if (bleManager.getConnectedCount() == 0) {
            // Color can only be changed while a device is connected.
            Toast.makeText(this, "Connect a device to change color", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!poweredOn) {
            Toast.makeText(this, "Turn the power on to change color", Toast.LENGTH_SHORT).show();
            return;
        }
        currentR = r;
        currentG = g;
        currentB = b;
        rememberColorForActiveZones();
        colorWheel.setColor(r, g, b);
        updateRgbReadout();
        applyZoneUi();
        store.setLastColor(r, g, b);
        broadcastColorForActiveZones(r, g, b);
    }

    /** Copies the current live color into whichever zone(s) are active right now. */
    private void rememberColorForActiveZones() {
        boolean connected = bleManager.getConnectedCount() > 0;
        if (rgbActive) {
            rgbR = currentR;
            rgbG = currentG;
            rgbB = currentB;
            if (connected) {
                store.setZoneColor(Car01Protocol.Zone.RGB, rgbR, rgbG, rgbB);
            }
            // Manually picking a color leaves whatever effect was previewing.
            ambientController.stopFootwellEffectPreview();
        }
        if (dmxActive) {
            dmxR = currentR;
            dmxG = currentG;
            dmxB = currentB;
            if (connected) {
                store.setZoneColor(Car01Protocol.Zone.DMX, dmxR, dmxG, dmxB);
            }
            ambientController.stopNeonStripEffectPreview();
        }
        refreshAmbientColors();
    }

    /** Footwell/door-handle/storage glow follows RGB zone; dash neon strip follows DMX zone. */
    private void refreshAmbientColors() {
        ambientController.setFootwellGlowColor(Color.rgb(rgbR, rgbG, rgbB));
        ambientController.setNeonStripColor(Color.rgb(dmxR, dmxG, dmxB));
    }

    /** Copies the current brightness into whichever zone(s) are active right now. */
    private void rememberBrightnessForActiveZones() {
        if (rgbActive) {
            rgbBrightness = currentBrightness;
        }
        if (dmxActive) {
            dmxBrightness = currentBrightness;
        }
        refreshAmbientBrightness();
    }

    /** Footwell glow intensity follows RGB zone brightness; neon strip follows DMX zone brightness. */
    private void refreshAmbientBrightness() {
        ambientController.setFootwellGlowBrightness(rgbBrightness);
        ambientController.setNeonStripBrightness(dmxBrightness);
    }

    private void scheduleColorSend() {
        store.setLastColor(currentR, currentG, currentB);
        colorThrottle.request(() -> broadcastColorForActiveZones(currentR, currentG, currentB));
    }

    /**
     * Rate-limits outgoing sends to at most one per interval while updates
     * keep arriving (e.g. a finger dragging the color wheel), instead of a
     * plain debounce that would never fire until the drag paused. The first
     * request in a quiet period fires immediately; subsequent ones within the
     * interval coalesce into a single trailing send carrying the latest value.
     */
    private class Throttler {
        private final long intervalMs;
        private final Runnable scheduledRunnable = this::runScheduled;
        private long lastRunAt = 0L;
        private Runnable pendingAction;
        private boolean scheduled = false;

        Throttler(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        void request(Runnable action) {
            pendingAction = action;
            long elapsed = SystemClock.uptimeMillis() - lastRunAt;
            if (elapsed >= intervalMs) {
                runNow();
            } else if (!scheduled) {
                scheduled = true;
                debounceHandler.postDelayed(scheduledRunnable, intervalMs - elapsed);
            }
        }

        private void runScheduled() {
            scheduled = false;
            runNow();
        }

        private void runNow() {
            lastRunAt = SystemClock.uptimeMillis();
            Runnable action = pendingAction;
            pendingAction = null;
            if (action != null) {
                action.run();
            }
        }

        /** Drops any trailing send still waiting to fire, without running it. */
        void cancelPending() {
            if (scheduled) {
                debounceHandler.removeCallbacks(scheduledRunnable);
                scheduled = false;
                pendingAction = null;
            }
        }
    }

    private void updateRgbReadout() {
        tvRgbReadout.setText(String.format(Locale.US, "R %d  G %d  B %d", currentR, currentG, currentB));
    }

    private void setBrightnessValue(int percent, boolean sendImmediately) {
        currentBrightness = percent;
        rememberBrightnessForActiveZones();
        seekBrightness.setProgress(percent);
        tvBrightnessValue.setText(percent + "%");
        store.setLastBrightness(percent);
        if (sendImmediately) {
            broadcastBrightnessForActiveZones(percent);
        }
    }

    private interface PercentCallback {
        void onChanged(int percent);
    }

    private SeekBar.OnSeekBarChangeListener simpleDebouncedListener(PercentCallback callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    callback.onChanged(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private void requestPermissionsThenScan() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(android.Manifest.permission.BLUETOOTH_SCAN);
            needed.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> missing = new ArrayList<>();
        for (String permission : needed) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            proceedToScan();
        } else {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void proceedToScan() {
        if (!bleManager.isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        bleManager.startScan();
    }

    private void setConnectedControlsEnabled(boolean enabled) {
        // Power itself stays tappable whenever a device is connected,
        // regardless of on/off state - otherwise there'd be no way to turn
        // it back on. Everything else additionally requires poweredOn (see
        // updateControlsLockState) since editing color/mode/brightness on a
        // powered-off strip has no visible effect and just confuses things.
        btnPower.setEnabled(enabled);
        btnSettings.setEnabled(enabled);
        updateUtilityButtonColors(enabled);
        if (!enabled) {
            // No device connected yet: show a plain disabled button, but leave
            // poweredOn untouched — the app defaults to powered-on, and this
            // state gets applied for real as soon as a device connects.
            stopPowerPulse();
            btnPower.setBackgroundResource(R.drawable.bg_power_button);
            btnPower.setAlpha(0.4f);
            setDimmed(false);
        } else {
            updatePowerVisualState();
        }
        updateControlsLockState();
    }

    /**
     * Color/mode/brightness/speed controls are only usable while connected
     * AND powered on - while blocked, Scan (btnConnect) is deliberately left
     * as the only active control, since it's the way out of that state.
     */
    private void updateControlsLockState() {
        boolean unlocked = bleManager.getConnectedCount() > 0 && poweredOn && !isScanning;
        seekBrightness.setEnabled(unlocked);
        // Speed only means anything while an animated effect is actually running -
        // a static color has no speed to adjust, so leave the slider disabled
        // until at least one zone has a mode selected.
        boolean anyModeActive = store.getLastModeId(Car01Protocol.Zone.RGB) != -1
                || store.getLastModeId(Car01Protocol.Zone.DMX) != -1;
        seekSpeed.setEnabled(unlocked && anyModeActive);
        colorWheel.setEnabled(unlocked);
        btnMode.setEnabled(unlocked);
        btnSaveColor.setEnabled(unlocked);
        tvRgbReadout.setEnabled(unlocked);
        // The marching-ants selection stroke only makes sense while the zone
        // pill is actually usable - hide it rather than just dimming it, so a
        // powered-off or disconnected strip doesn't look like it's still live.
        zoneMarchingBorder.setVisibility(unlocked ? View.VISIBLE : View.INVISIBLE);
        // Ambient preview mirrors the real strip: only glows while connected and
        // powered on, easing between states (power toggle, connect/disconnect)
        // instead of snapping the preview instantly.
        if (unlocked) {
            refreshAmbientColors();
            refreshAmbientBrightness();
            if (!ambientLit) {
                ambientController.fadeIn();
            }
        } else if (ambientLit) {
            ambientController.fadeOut();
        } else {
            ambientController.turnOffAmbientLights();
        }
        ambientLit = unlocked;
    }

    /**
     * Settings/Scan/Mode/Save show their colorful outline only once a device
     * is connected; while disconnected they stay the plain dark neutral pill
     * so the colored state reads as "the app is actually live" rather than
     * just decoration.
     */
    private void updateUtilityButtonColors(boolean connected) {
        if (connected) {
            btnSettings.setBackgroundResource(R.drawable.bg_outline_settings);
            btnSettings.setTextColor(ContextCompat.getColor(this, R.color.btn_settings_color));
            btnConnect.setBackgroundResource(R.drawable.bg_outline_scan);
            btnConnect.setTextColor(ContextCompat.getColor(this, R.color.btn_scan_color));
            btnMode.setBackgroundResource(R.drawable.bg_outline_mode);
            btnMode.setTextColor(ContextCompat.getColor(this, R.color.btn_mode_color));
            btnSaveColor.setBackgroundResource(R.drawable.bg_outline_save);
            btnSaveColor.setTextColor(ContextCompat.getColor(this, R.color.btn_save_color));
        } else {
            int darkText = ContextCompat.getColor(this, R.color.text_primary);
            btnSettings.setBackgroundResource(R.drawable.bg_pill_button);
            btnSettings.setTextColor(darkText);
            btnConnect.setBackgroundResource(R.drawable.bg_pill_button);
            btnConnect.setTextColor(darkText);
            btnMode.setBackgroundResource(R.drawable.bg_pill_button);
            btnMode.setTextColor(darkText);
            btnSaveColor.setBackgroundResource(R.drawable.bg_pill_button);
            btnSaveColor.setTextColor(darkText);
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /** Shimmer sweep on the Scan button for as long as a scan is running - works over either its dark or blue outline state. */
    private void startScanShimmer() {
        if (scanShimmer == null) {
            int base = ContextCompat.getColor(this, R.color.btn_scan_color);
            int highlight = (base & 0x00FFFFFF) | 0x99000000;
            scanShimmer = new ShimmerDrawable(highlight, dp(14));
            btnConnect.setForeground(scanShimmer);
        }
        scanShimmer.start();
    }

    private void stopScanShimmer() {
        if (scanShimmer != null) {
            scanShimmer.stop();
            btnConnect.setForeground(null);
            scanShimmer = null;
        }
    }

    // BleDeviceManager.Listener

    /**
     * BleDeviceManager reports plenty of verbose, per-device status strings
     * (connecting, service discovery, GATT errors...); the UI only wants a
     * terse summary of the three states that actually matter here.
     */
    @Override
    public void onStatus(String status) {
        String s = status.toLowerCase(Locale.US);
        String shown;
        if (s.contains("scanning")) {
            shown = "Scanning…";
            // Locks everything down the same way a fresh, never-connected launch
            // does, for as long as the scan runs - covers the auto-retry path too,
            // not just a manual tap (which already locks immediately on click).
            isScanning = true;
            setConnectedControlsEnabled(false);
            startScanShimmer();
        } else {
            // Scan just ended (found, not found, or any other terminal status) -
            // unlock and let the real connection count decide the resulting state.
            isScanning = false;
            stopScanShimmer();
            if (s.contains("no ledcar devices found")) {
                shown = "No devices found";
            } else if (s.contains("connected") && !s.contains("disconnected")) {
                int count = bleManager.getConnectedCount();
                shown = count + (count == 1 ? " device found" : " devices found");
            } else {
                shown = "";
            }
            setConnectedControlsEnabled(bleManager.getConnectedCount() > 0);
        }
        scanStatusText.setText(shown);
    }

    @Override
    public void onDevicesChanged(List<BleConnection> connections) {
        int connectedCount = 0;
        for (BleConnection c : connections) {
            if (c.getState() == BleConnection.State.CONNECTED) {
                connectedCount++;
            }
        }
        boolean anyConnected = connectedCount > 0;
        tvDeviceSummary.setText(anyConnected ? bleManager.summaryText() : getString(R.string.no_device));
        setConnectedControlsEnabled(anyConnected);
        if (anyConnected) {
            firstFailureToastShown = false;
        }
    }
}
