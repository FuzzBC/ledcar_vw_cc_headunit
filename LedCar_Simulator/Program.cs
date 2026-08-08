using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Foundation;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace LedCar_Simulator;

/// <summary>
/// Emulates a LEDCAR-01 BLE peripheral on this PC's Bluetooth radio so the
/// Android controller app can connect to a real device during development.
/// Advertises the same service/characteristic as the real strip (0xFFE0 /
/// 0xFFE1) and decodes every 9-byte command frame it receives.
/// </summary>
internal static class Program
{
    private static readonly Guid ServiceUuid = Guid.Parse("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static readonly Guid CharacteristicUuid = Guid.Parse("0000ffe1-0000-1000-8000-00805f9b34fb");

    // "RGB Color" tab mode ids (0x7E header) - from the vendor app's car_mode resource.
    private static readonly Dictionary<int, string> CarModeNames = new()
    {
        { 135, "Tricolor jump" }, { 136, "Seven-color jump" }, { 137, "Tricolor gradient" },
        { 138, "Seven-color gradient" }, { 139, "Red gradient" }, { 140, "Green gradient" },
        { 141, "Blue gradient" }, { 142, "Yellow gradient" }, { 143, "Cyan gradient" },
        { 144, "Purple gradient" }, { 145, "White gradient" }, { 146, "Red-green gradient" },
        { 147, "Red-blue gradient" }, { 148, "Green-blue gradient" }, { 149, "Seven-color flash" },
        { 150, "Red flash" }, { 151, "Green flash" }, { 152, "Blue flash" }, { 153, "Yellow flash" },
        { 154, "Cyan flash" }, { 155, "Purple flash" }, { 156, "White flash" }, { 157, "Seven-color breath" },
    };

    // "DMX zone" tab mode ids (0x7B header) - from the vendor app's dmx_model resource.
    private static readonly Dictionary<int, string> DmxModeNames = new()
    {
        { 255, "Auto (cycle through effects)" },
        { 1, "Forward Dreaming" },
        { 2, "Backward Dreaming" },
        { 3, "Forward 7 Colors" },
        { 4, "Backward 7 Colors" },
        { 5, "Forward RD/GN/BU" },
        { 6, "Backward RD/GN/BU" },
        { 7, "Forward YE/CN/VT" },
        { 8, "Backward YE/CN/VT" },
        { 9, "Forward 6 Colors RD" },
        { 10, "Backward 6 Colors RD" },
        { 11, "Forward 6 Colors GN" },
        { 12, "Backward 6 Colors GN" },
        { 13, "Forward 6 Colors BU" },
        { 14, "Backward 6 Colors BU" },
        { 15, "Forward 6 Colors CN" },
        { 16, "Backward 6 Colors CN" },
        { 17, "Forward 6 Colors YE" },
        { 18, "Backward 6 Colors YE" },
        { 19, "Forward 6 Colors VT" },
        { 20, "Backward 6 Colors VT" },
        { 21, "Forward 6 Colors WH" },
        { 22, "Backward 6 Colors WH" },
        { 23, "Forward Trailing 7 Colors" },
        { 24, "Backward Trailing 7 Colors" },
        { 25, "Forward Trailing RD" },
        { 26, "Backward Trailing RD" },
        { 27, "Forward Trailing GN" },
        { 28, "Backward Trailing GN" },
        { 29, "Forward Trailing BU" },
        { 30, "Backward Trailing BU" },
        { 31, "Forward Trailing YE" },
        { 32, "Backward Trailing YE" },
        { 33, "Forward Trailing CN" },
        { 34, "Backward Trailing CN" },
        { 35, "Forward Trailing VT" },
        { 36, "Backward Trailing VT" },
        { 37, "Forward Trailing WH" },
        { 38, "Backward Trailing WH" },
        { 39, "Forward Streaming 7 Colors" },
        { 40, "Backward Streaming 7 Colors" },
        { 41, "Forward Streaming RD/GN/BU" },
        { 42, "Backward Streaming RD/GN/BU" },
        { 43, "Forward Streaming YE/CN/VT" },
        { 44, "Backward Streaming YE/CN/VT" },
        { 45, "Forward Streaming RD/GN" },
        { 46, "Backward Streaming RD/GN" },
        { 47, "Forward Streaming GN/BU" },
        { 48, "Backward Streaming GN/BU" },
        { 49, "Forward Streaming YE/BU" },
        { 50, "Backward Streaming YE/BU" },
        { 51, "Forward Streaming YE/CN" },
        { 52, "Backward Streaming YE/CN" },
        { 53, "Forward Streaming CN/VT" },
        { 54, "Backward Streaming CN/VT" },
        { 55, "Forward Streaming BK/WH" },
        { 56, "Backward Streaming BK/WH" },
        { 57, "Open Curtain 7 Colors" },
        { 58, "Close Curtain 7 Colors" },
        { 59, "Open Curtain RD/GN/BU" },
        { 60, "Close Curtain RD/GN/BU" },
        { 61, "Open Curtain YE/CN/VT" },
        { 62, "Close Curtain YE/CN/VT" },
        { 63, "Forward Follow Spot 7 Colors" },
        { 64, "Backward Follow Spot 7 Colors" },
        { 65, "Forward Follow Spot RD/GN/BU" },
        { 66, "Backward Follow Spot RD/GN/BU" },
        { 67, "Forward Follow Spot YE/CN/VT" },
        { 68, "Backward Follow Spot YE/CN/VT" },
        { 69, "Forward Flutter 7 Colors" },
        { 70, "Backward Flutter 7 Colors" },
        { 71, "Forward Flutter RD/GN/BU" },
        { 72, "Backward Flutter RD/GN/BU" },
        { 73, "Forward Flutter YE/CN/VT" },
        { 74, "Backward Flutter YE/CN/VT" },
        { 75, "Hop 7 Colors" },
        { 76, "Hop RD/GN/BU" },
        { 77, "Hop RD/GN/BU" },
        { 78, "Strobe 7 Colors" },
        { 79, "Strobe RD/GN/BU" },
        { 80, "Strobe YE/CN/VT" },
        { 81, "Gradual 7 Colors" },
        { 82, "Gradual RD/E" },
        { 83, "Gradual RD/VT" },
        { 84, "Gradual GN/CN" },
        { 85, "Gradual GN/YE" },
        { 86, "Gradual BU/VT" },
        { 87, "Close Curtain RD" },
        { 88, "Close Curtain GN" },
        { 89, "Close Curtain BU" },
        { 90, "Close Curtain YE" },
        { 91, "Close Curtain CN" },
        { 92, "Close Curtain VT" },
        { 93, "Close Curtain WH" },
        { 94, "Open Curtain RD" },
        { 95, "Open Curtain GN" },
        { 96, "Open Curtain BU" },
        { 97, "Open Curtain YE" },
        { 98, "Open Curtain CN" },
        { 99, "Open Curtain VT" },
        { 100, "Open Curtain WH" },
        { 101, "Horse Race RD" },
        { 102, "Horse Race GN" },
        { 103, "Horse Race BU" },
        { 104, "Horse Race YE" },
        { 105, "Horse Race CN" },
        { 106, "Horse Race VT" },
        { 107, "Horse Race WH" },
        { 108, "Forward Run RD" },
        { 109, "Backward Run RD" },
        { 110, "Forward Run GN" },
        { 111, "Backward Run GN" },
        { 112, "Forward Run BU" },
        { 113, "Backward Run BU" },
        { 114, "Forward Run YE" },
        { 115, "Backward Run YE" },
        { 116, "Forward Run CN" },
        { 117, "Backward Run CN" },
        { 118, "Forward Run VT" },
        { 119, "Backward Run VT" },
        { 120, "Forward Run WH" },
        { 121, "Backward Run WH" },
        { 122, "Forward Run 7 Colors" },
        { 123, "Backward Run 7 Colors" },
        { 124, "Forward Run RD/GN/BU" },
        { 125, "Backward Run RD/GN/BU" },
        { 126, "Forward Run YE/CN/VT" },
        { 127, "Backward Run YE/CN/VT" },
        { 128, "Forward Run BU/YE/VT" },
        { 129, "Forward Run GN/BU/YE" },
        { 130, "Backward Run BU/YE/VT" },
        { 131, "Forward Run RD/ON/WH" },
        { 132, "Backward Run RD/ON/WH" },
        { 133, "Forward Run GN ON RD" },
        { 134, "Backward Run GN ON RD" },
        { 135, "Forward Run BU ON GN" },
        { 136, "Backward Run BU ON GN" },
        { 137, "Forward Run YE ON BU" },
        { 138, "Backward Run YE ON BU" },
        { 139, "Forward Run CN ON YE" },
        { 140, "Backward Run CN ON YE" },
        { 141, "Forward Run VT ON CN" },
        { 142, "Backward Run VT ON CN" },
        { 143, "Forward Run WH ON VT" },
        { 144, "Backward Run WH ON VT" },
        { 145, "Forward Run WH ON RD" },
        { 146, "Backward Run WH ON RD" },
        { 147, "Forward Run 7 COLOR ON RD" },
        { 148, "Backward Run 7 COLOR ON RD" },
        { 149, "Forward Run 7 COLOR ON GN" },
        { 150, "Backward Run 7 COLOR ON GN" },
        { 151, "Forward Run 7 COLOR ON BU" },
        { 152, "Backward Run 7 COLOR ON BU" },
        { 153, "Forward Run 7 COLOR ON YE" },
        { 154, "Backward Run 7 COLOR ON YE" },
        { 155, "Forward Run 7 COLOR ON CN" },
        { 156, "Backward Run 7 COLOR ON CN" },
        { 157, "Forward Run 7 COLOR ON VT" },
        { 158, "Backward Run 7 COLOR ON VT" },
        { 159, "Forward Run 7 COLOR ON WH" },
        { 160, "Backward Run 7 COLOR ON WH" },
        { 161, "Forward Flow WH RD WH" },
        { 162, "Backward Flow WH RD WH" },
        { 163, "Forward Flow WH GN WH" },
        { 164, "Backward Flow WH GN WH" },
        { 165, "Forward Flow WH BU WH" },
        { 166, "Backward Flow WH BU WH" },
        { 167, "Forward Flow WH YE WH" },
        { 168, "Backward Flow WH YE WH" },
        { 169, "Forward Flow WH CN WH" },
        { 170, "Backward Flow WH CN WH" },
        { 171, "Forward Flow WH VT WH" },
        { 172, "Backward Flow WH VT WH" },
        { 173, "Forward Flow RD WH RD" },
        { 174, "Backward Flow RD WH RD" },
        { 175, "Forward Flow GN WH GN" },
        { 176, "Backward Flow GN WH GN" },
        { 177, "Forward Flow BU WH BU" },
        { 178, "Backward Flow BU WH BU" },
        { 179, "Forward Flow YE WH YE" },
        { 180, "Backward Flow YE WH YE" },
        { 181, "Forward Flow CN WH CN" },
        { 182, "Backward Flow CN WH CN" },
        { 183, "Forward Flow VT WH VT" },
        { 184, "Backward Flow VT WH VT" },
        { 185, "Forward Run GN ON BU" },
        { 186, "Backward Run GN ON BU" },
        { 187, "Forward Run GN ON RD" },
        { 188, "Backward Run GN ON RD" },
        { 189, "Forward Run RD ON BU" },
        { 190, "Backward Run RD ON BU" },
        { 191, "Forward Run CN ON YE" },
        { 192, "Backward Run CN ON YE" },
        { 193, "Forward Run YE ON VT" },
        { 194, "Backward Run YE ON VT" },
        { 195, "Forward Run WH ON YE" },
        { 196, "Backward Run WH ON YE" },
        { 197, "Forward Run YE ON WH" },
        { 198, "Backward Run YE ON WH" },
        { 199, "Forward Swab 7 COLORS" },
        { 200, "Backward Swab 7 COLORS" },
        { 201, "Forward Swab RD GN BU" },
        { 202, "Backward Swab RD GN BU" },
        { 203, "Forward Swab YE CN VT" },
        { 204, "Backward Swab YE CN VT" },
        { 205, "Open Curtain Swab 7 COLORS" },
        { 206, "Close Curtain Swab 7 COLORS" },
        { 207, "Open Curtain Swab R G B" },
        { 208, "Close Curtain Swab R G B" },
        { 209, "Open Curtain Swab Y C P" },
        { 210, "Close Curtain Swab Y C P" },
    };

    private static readonly Dictionary<int, string> ColorOrders = new()
    {
        { 1, "RGB" }, { 2, "RBG" }, { 3, "GRB" }, { 4, "GBR" }, { 5, "BRG" }, { 6, "BGR" },
    };

    private static bool _power;
    private static byte _r = 255, _g = 80, _b = 80;
    private static int _brightness = 80;
    private static int _speed = 50;
    private static string _mode = "-";
    private static string _direction = "forward";
    private static bool _music;

    private static readonly object ConsoleLock = new();
    private const int MaxAdvertisingRetries = 10;
    private static int _advertisingRetries;

    private static async Task Main()
    {
        Console.WriteLine("LEDCAR-01 BLE simulator");
        Console.WriteLine("========================");

        var serviceResult = await GattServiceProvider.CreateAsync(ServiceUuid);
        if (serviceResult.Error != BluetoothError.Success)
        {
            Console.WriteLine($"Failed to create GATT service: {serviceResult.Error}");
            return;
        }

        GattServiceProvider provider = serviceResult.ServiceProvider;

        var characteristicParameters = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Write
                                        | GattCharacteristicProperties.WriteWithoutResponse
                                        | GattCharacteristicProperties.Notify,
            WriteProtectionLevel = GattProtectionLevel.Plain,
            UserDescription = "LEDCAR01 command channel",
        };

        var characteristicResult = await provider.Service.CreateCharacteristicAsync(CharacteristicUuid, characteristicParameters);
        if (characteristicResult.Error != BluetoothError.Success)
        {
            Console.WriteLine($"Failed to create GATT characteristic: {characteristicResult.Error}");
            return;
        }

        GattLocalCharacteristic characteristic = characteristicResult.Characteristic;
        characteristic.WriteRequested += OnWriteRequested;
        characteristic.ReadRequested += OnReadRequested;
        characteristic.SubscribedClientsChanged += (sender, _) =>
            Log($"Notification subscribers: {sender.SubscribedClients.Count} (a subscribe means a central connected and enabled notifications)");

        var advertisingParameters = new GattServiceProviderAdvertisingParameters
        {
            IsDiscoverable = true,
            IsConnectable = true,
        };

        provider.AdvertisementStatusChanged += (sender, args) =>
        {
            Log($"Advertising status: {args.Status}");
            // Some Windows/Bluetooth-driver combinations report Started and then
            // immediately Abort the actual radio advertisement. Retry a bounded
            // number of times rather than leaving the server silently dead.
            if (args.Status == GattServiceProviderAdvertisementStatus.Aborted
                && _advertisingRetries < MaxAdvertisingRetries)
            {
                _advertisingRetries++;
                Log($"Advertising aborted by the OS/driver - retry {_advertisingRetries}/{MaxAdvertisingRetries} in 1s...");
                _ = Task.Delay(1000).ContinueWith(_ =>
                {
                    try
                    {
                        provider.StartAdvertising(advertisingParameters);
                    }
                    catch (Exception ex)
                    {
                        Log($"Retry failed to call StartAdvertising: {ex.Message}");
                    }
                });
            }
            else if (args.Status == GattServiceProviderAdvertisementStatus.Aborted)
            {
                Log("Gave up retrying. Your Bluetooth adapter/driver may not sustain BLE peripheral (GATT server) advertising.");
                Log("Try: disabling Windows 'Nearby sharing'/'Swift Pair', updating the Bluetooth driver, or using a USB BLE dongle.");
            }
        };

        provider.StartAdvertising(advertisingParameters);

        Log("Advertising service 0xFFE0 / characteristic 0xFFE1 on this PC's Bluetooth radio.");
        Log("Note: Windows advertises this PC's own Bluetooth name, not \"LEDCAR-01-...\".");
        Log("The Android app should scan by the 0xFFE0 service UUID rather than requiring that name prefix.");
        Log("Waiting for the phone to connect. Press Ctrl+C to quit.");
        Console.WriteLine();
        PrintState();

        await Task.Delay(Timeout.Infinite);
    }

    private static async void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        Log("WriteRequested event fired.");
        Deferral deferral = args.GetDeferral();
        try
        {
            GattWriteRequest? request = await args.GetRequestAsync();
            if (request == null)
            {
                Log("GetRequestAsync() returned null (request was likely superseded or the central disconnected mid-write).");
                return;
            }

            byte[] data = new byte[request.Value.Length];
            using (DataReader reader = DataReader.FromBuffer(request.Value))
            {
                reader.ReadBytes(data);
            }

            Decode(data);

            if (request.Option == GattWriteOption.WriteWithResponse)
            {
                request.Respond();
            }
        }
        catch (Exception ex)
        {
            Log($"Exception while handling write: {ex}");
        }
        finally
        {
            deferral.Complete();
        }
    }

    private static void OnReadRequested(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        Log("ReadRequested event fired (this characteristic has no readable value configured).");
    }

    private static void Decode(byte[] packet)
    {
        string hex = BitConverter.ToString(packet).Replace("-", " ");

        if (packet.Length == 9 && packet[0] == 0x2A && packet[8] == 0xAF)
        {
            Log($"RX [{hex}]  ->  AUTH/PASSWORD handshake (setPassword, sent automatically on connect - no response required)");
            return;
        }

        if (packet.Length == 9 && packet[0] == 0x7E && packet[8] == 0xEF)
        {
            if (packet[2] == 0x05 && packet[3] == 0x03)
            {
                _r = packet[4]; _g = packet[5]; _b = packet[6];
                Log($"RX [{hex}]  ->  COLOR (plain RGB tab) r={_r} g={_g} b={_b} (setBleRgb)");
                PrintState();
            }
            else if (packet[2] == 0x04)
            {
                _power = packet[3] == 1;
                Log($"RX [{hex}]  ->  {(_power ? "POWER ON" : "POWER OFF")}  — RGB tab (carturnOn/Off)");
                PrintState();
            }
            else if (packet[2] == 0x01)
            {
                _brightness = packet[3];
                Log($"RX [{hex}]  ->  BRIGHTNESS (plain RGB tab) {_brightness}% (setBrightness)");
                PrintState();
            }
            else if (packet[2] == 0x12)
            {
                string welcome = packet[3] == 0 ? "enabled" : "disabled";
                Log($"RX [{hex}]  ->  WELCOME mode {welcome} (setAuxiliary)");
            }
            else if (packet[2] == 0x03)
            {
                CarModeNames.TryGetValue(packet[3], out string? carModeName);
                _mode = carModeName ?? $"unknown ({packet[3]})";
                Log($"RX [{hex}]  ->  MODE (RGB Color tab) id={packet[3]} ({_mode}) (setRgbMode)");
                PrintState();
            }
            else if (packet[2] == 0x02)
            {
                _speed = packet[3];
                Log($"RX [{hex}]  ->  SPEED (RGB Color tab) {_speed}% (setSpeed)");
                PrintState();
            }
            else
            {
                Log($"RX [{hex}]  (0x7E-family frame, opcode 0x{packet[2]:X2}/0x{packet[3]:X2} not yet mapped)");
            }
            return;
        }

        if (packet.Length != 9 || packet[0] != 0x7B || packet[8] != 0xBF)
        {
            Log($"RX [{hex}]  (unrecognised frame, expected 9 bytes 0x7B..0xBF, 0x7E..0xEF, or 0x2A..0xAF)");
            return;
        }

        int opcode = packet[2];
        string meaning;
        switch (opcode)
        {
            case 0x04:
                string powerTab;
                (_power, powerTab) = packet[3] switch
                {
                    7 => (true, "LED tab"),
                    6 => (false, "LED tab"),
                    1 => (true, "DMX zone"),
                    0 => (false, "DMX zone"),
                    _ => (packet[3] != 0, $"unknown byte3=0x{packet[3]:X2}"),
                };
                meaning = (_power ? "POWER ON" : "POWER OFF") + $"  — {powerTab}";
                break;
            case 0x07:
                _r = packet[3];
                _g = packet[4];
                _b = packet[5];
                string colorTab = packet[1] switch
                {
                    0x01 => "LED tab (sync)",
                    0x00 => "DMX zone",
                    _ => $"mode byte 0x{packet[1]:X2}",
                };
                meaning = $"COLOR r={_r} g={_g} b={_b}  — {colorTab}";
                break;
            case 0x01:
                _brightness = packet[4];
                string zone = packet[5] switch
                {
                    0x02 => "LED tab (sync)",
                    0x01 => "music-reactive",
                    0x00 => "DMX zone",
                    _ => $"flag 0x{packet[5]:X2}",
                };
                meaning = $"BRIGHTNESS {_brightness}%  — {zone}";
                break;
            case 0x03:
                DmxModeNames.TryGetValue(packet[3], out string? dmxName);
                _mode = dmxName ?? $"unknown ({packet[3]})";
                meaning = $"MODE (DMX zone) id={packet[3]} ({_mode})";
                break;
            case 0x02:
                _speed = packet[3];
                meaning = $"SPEED (DMX zone) {_speed}%";
                break;
            case 0x0D:
                _direction = packet[3] == 0 ? "forward" : "reverse";
                meaning = $"DIRECTION {_direction}";
                break;
            case 0x0B:
                _music = packet[3] == 1;
                meaning = $"MUSIC MODE {(_music ? "on" : "off")}";
                break;
            case 0x05 when packet[3] == 0x04:
                ColorOrders.TryGetValue(packet[6], out string? colorOrder);
                meaning = $"SPI CONFIG  pixels={packet[5]}  colorOrder={colorOrder ?? $"unknown ({packet[6]})"}  b={packet[4]} (setConfigSPI)";
                break;
            case 0x05 when packet[3] == 0x05:
                meaning = $"CAR01 CONFIG  i={packet[1]} i2={packet[4]} i3={packet[5]} i4={packet[6]} (setConfigCAR01, likely banner pixel dimensions)";
                break;
            default:
                meaning = $"UNKNOWN opcode 0x{opcode:X2}";
                break;
        }

        Log($"RX [{hex}]  ->  {meaning}");
        PrintState();
    }

    private static void PrintState()
    {
        Console.WriteLine($"  lamp: {(_power ? "ON " : "OFF")}  color=({_r},{_g},{_b})  brightness={_brightness}%  mode={_mode}  speed={_speed}%  dir={_direction}  music={(_music ? "on" : "off")}");
    }

    private static void Log(string message)
    {
        lock (ConsoleLock)
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] {message}");
        }
    }
}
