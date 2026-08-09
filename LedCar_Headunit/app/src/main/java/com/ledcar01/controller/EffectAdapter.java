package com.ledcar01.controller;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RecyclerView adapter for the effects list. Each row's swatch is a live
 * animated {@link EffectPreviewView} playing that effect's real pattern
 * (see {@link EffectVisual}), not a static gradient rectangle - only the
 * ~10-15 rows actually bound/visible at once ever animate, since
 * {@link #onViewRecycled} stops a row's animation the moment RecyclerView
 * recycles it off-screen.
 */
public class EffectAdapter extends RecyclerView.Adapter<EffectAdapter.ViewHolder> {

    public interface OnEffectClickListener {
        void onEffectClick(int id, String name);
    }

    public static final class EffectItem {
        public final int id;
        public final String name;

        public EffectItem(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final int[] RAINBOW = {
            Color.parseColor("#E24B4A"), Color.parseColor("#EF9F27"), Color.parseColor("#639922"),
            Color.parseColor("#5DCAA5"), Color.parseColor("#378ADD"), Color.parseColor("#7F77DD"),
    };

    private static final Map<String, Integer> KEYWORD_COLORS = new LinkedHashMap<>();

    static {
        KEYWORD_COLORS.put("RD", Color.parseColor("#E24B4A"));
        KEYWORD_COLORS.put("RED", Color.parseColor("#E24B4A"));
        KEYWORD_COLORS.put("GN", Color.parseColor("#639922"));
        KEYWORD_COLORS.put("GREEN", Color.parseColor("#639922"));
        KEYWORD_COLORS.put("BU", Color.parseColor("#378ADD"));
        KEYWORD_COLORS.put("BLUE", Color.parseColor("#378ADD"));
        KEYWORD_COLORS.put("YE", Color.parseColor("#EF9F27"));
        KEYWORD_COLORS.put("YELLOW", Color.parseColor("#EF9F27"));
        KEYWORD_COLORS.put("CN", Color.parseColor("#5DCAA5"));
        KEYWORD_COLORS.put("CYAN", Color.parseColor("#5DCAA5"));
        KEYWORD_COLORS.put("VT", Color.parseColor("#7F77DD"));
        KEYWORD_COLORS.put("PURPLE", Color.parseColor("#7F77DD"));
        KEYWORD_COLORS.put("VIOLET", Color.parseColor("#7F77DD"));
        KEYWORD_COLORS.put("WH", Color.parseColor("#F5F5F2"));
        KEYWORD_COLORS.put("WHITE", Color.parseColor("#F5F5F2"));
    }

    private final List<EffectItem> items;
    private final OnEffectClickListener listener;
    private int selectedId;

    public EffectAdapter(List<EffectItem> items, int selectedId, OnEffectClickListener listener) {
        this.items = items;
        this.selectedId = selectedId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_effect, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EffectItem item = items.get(position);
        holder.name.setText(item.name);
        holder.preview.setEffect(EffectVisual.forEffect(holder.preview.getContext(), item.id, item.name));
        holder.dot.setVisibility(item.id == selectedId ? View.VISIBLE : View.INVISIBLE);
        holder.itemView.setOnClickListener(v -> {
            selectedId = item.id;
            notifyDataSetChanged();
            listener.onEffectClick(item.id, item.name);
        });
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        // Belt-and-suspenders alongside EffectPreviewView's own
        // onDetachedFromWindow stop - RecyclerView detaches recycled views
        // from the window anyway, but making the intent explicit here means
        // this doesn't silently break if that recycling strategy ever changes.
        holder.preview.setEffect(null);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Same color-keyword extraction used historically for the list row swatches - kept for the ambient preview, which mimics a selected effect with a static gradient rather than a live animation. */
    public static List<Integer> colorsForName(String name) {
        String upper = name.toUpperCase(Locale.US);
        if (upper.contains("7 COLOR") || upper.contains("SEVEN-COLOR") || upper.contains("SEVEN COLOR")
                || upper.contains("TRICOLOR") || upper.contains("6 COLORS")) {
            List<Integer> rainbow = new ArrayList<>();
            for (int c : RAINBOW) {
                rainbow.add(c);
            }
            return rainbow;
        }
        List<Integer> colors = new ArrayList<>();
        for (String token : upper.split("[^A-Z]+")) {
            Integer c = KEYWORD_COLORS.get(token);
            if (c != null && !colors.contains(c)) {
                colors.add(c);
            }
        }
        if (colors.isEmpty()) {
            colors.add(Color.parseColor("#7F77DD"));
        }
        return colors;
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final EffectPreviewView preview;
        final TextView name;
        final View dot;

        ViewHolder(View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.effectPreview);
            name = itemView.findViewById(R.id.effectName);
            dot = itemView.findViewById(R.id.effectSelectedDot);
        }
    }
}
