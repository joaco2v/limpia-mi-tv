package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * grid_categories: a title above the first row of each category in the grid. It only adds room above those rows and
 * draws text there, so the tiles keep their positions and Up/Down still move exactly one row.
 */
final class CategoryDecoration extends RecyclerView.ItemDecoration {

    private final AppAdapter adapter;
    private final int columns;
    private final int room;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    CategoryDecoration(Context ctx, AppAdapter adapter, int columns, float density) {
        this.adapter = adapter;
        this.columns = columns;
        this.room = Math.round(52 * density);
        text.setColor(0xB3FFFFFF);
        text.setTextSize(22 * density);
        text.setLetterSpacing(0.04f);
        try {
            text.setTypeface(ctx.getResources().getFont(R.font.outfit_medium));
        } catch (RuntimeException ignored) {
            // The system font is fine if the bundled one cannot be read.
        }
    }

    private String titleFor(int position) {
        if (position == RecyclerView.NO_POSITION || position >= adapter.appCount()) return null;
        return adapter.categoryTitleForRow(position / columns);
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        // Every cell of the row gets the same room, so the row stays level.
        if (titleFor(parent.getChildAdapterPosition(view)) != null) out.top = room;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || pos % columns != 0) continue;
            String title = titleFor(pos);
            if (title == null) continue;
            View card = child.findViewById(R.id.card);
            float x = child.getLeft() + child.getTranslationX() + (card != null ? card.getLeft() : 0);
            float y = child.getTop() + child.getTranslationY() - room * 0.30f;
            text.setAlpha(Math.round(0xB3 * child.getAlpha()));
            c.drawText(title, x, y, text);
        }
    }
}
