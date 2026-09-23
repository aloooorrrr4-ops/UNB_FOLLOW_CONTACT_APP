package com.unb.imageeditor;

import android.graphics.Bitmap;
import android.graphics.RectF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lightweight offline text-region detector.
 *
 * It does not read the text content. It detects dense character-like edge
 * groups so Arabic/Latin/numeric text can be selected and cropped without a
 * network OCR service.
 */
public final class LocalTextRegionDetector {

    private static final int MAX_DIMENSION = 1200;
    private static final int CELL = 6;

    public List<RectF> detect(Bitmap source) {
        List<RectF> empty = new ArrayList<>();
        if (source == null || source.getWidth() < 8 || source.getHeight() < 8) return empty;

        float scale = Math.min(1f,
                MAX_DIMENSION / (float) Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));

        Bitmap work = scale < 0.999f
                ? Bitmap.createScaledBitmap(source, w, h, true)
                : source;

        int[] pixels = new int[w * h];
        work.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] gray = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }

        int cols = (w + CELL - 1) / CELL;
        int rows = (h + CELL - 1) / CELL;
        int[] edgeCounts = new int[cols * rows];

        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int gx = Math.abs(gray[row + x + 1] - gray[row + x - 1]);
                int gy = Math.abs(gray[row + w + x] - gray[row - w + x]);
                if (gx + gy < 48) continue;
                int cx = x / CELL;
                int cy = y / CELL;
                edgeCounts[cy * cols + cx]++;
            }
        }

        boolean[] active = new boolean[cols * rows];
        for (int i = 0; i < active.length; i++) {
            active[i] = edgeCounts[i] >= 3;
        }

        // Character strokes are separated by small gaps. Grow horizontally
        // more than vertically so neighboring glyphs form words/lines.
        boolean[] grown = new boolean[active.length];
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                if (!active[cy * cols + cx]) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = cy + dy;
                    if (yy < 0 || yy >= rows) continue;
                    for (int dx = -3; dx <= 3; dx++) {
                        int xx = cx + dx;
                        if (xx < 0 || xx >= cols) continue;
                        grown[yy * cols + xx] = true;
                    }
                }
            }
        }

        boolean[] seen = new boolean[grown.length];
        List<RectF> boxes = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int start = 0; start < grown.length; start++) {
            if (!grown[start] || seen[start]) continue;

            seen[start] = true;
            queue.add(start);

            int minX = cols, minY = rows, maxX = 0, maxY = 0;
            int cellCount = 0;
            int edgeCount = 0;

            while (!queue.isEmpty()) {
                int idx = queue.removeFirst();
                int cy = idx / cols;
                int cx = idx % cols;

                minX = Math.min(minX, cx);
                minY = Math.min(minY, cy);
                maxX = Math.max(maxX, cx);
                maxY = Math.max(maxY, cy);
                cellCount++;
                edgeCount += edgeCounts[idx];

                for (int dy = -1; dy <= 1; dy++) {
                    int yy = cy + dy;
                    if (yy < 0 || yy >= rows) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = cx + dx;
                        if (xx < 0 || xx >= cols) continue;
                        int ni = yy * cols + xx;
                        if (!grown[ni] || seen[ni]) continue;
                        seen[ni] = true;
                        queue.addLast(ni);
                    }
                }
            }

            int left = minX * CELL;
            int top = minY * CELL;
            int right = Math.min(w, (maxX + 1) * CELL);
            int bottom = Math.min(h, (maxY + 1) * CELL);
            int bw = right - left;
            int bh = bottom - top;

            if (cellCount < 2 || edgeCount < 8) continue;
            if (bw < 12 || bh < 6) continue;
            if (bh > h * 0.35f || bw > w * 0.96f && bh > h * 0.25f) continue;
            if (bw * bh > w * h * 0.42f) continue;

            float inv = 1f / scale;
            float padX = Math.max(2f, 4f * inv);
            float padY = Math.max(2f, 3f * inv);

            RectF box = new RectF(
                    Math.max(0f, left * inv - padX),
                    Math.max(0f, top * inv - padY),
                    Math.min(source.getWidth(), right * inv + padX),
                    Math.min(source.getHeight(), bottom * inv + padY)
            );
            boxes.add(box);
        }

        mergeNearbyLines(boxes, source.getWidth(), source.getHeight());
        boxes.sort(Comparator
                .comparingDouble((RectF r) -> r.top)
                .thenComparingDouble(r -> r.left));

        return boxes;
    }

    private void mergeNearbyLines(List<RectF> boxes, int imageW, int imageH) {
        boolean changed = true;
        while (changed) {
            changed = false;

            outer:
            for (int i = 0; i < boxes.size(); i++) {
                RectF a = boxes.get(i);
                for (int j = i + 1; j < boxes.size(); j++) {
                    RectF b = boxes.get(j);

                    float overlapTop = Math.max(a.top, b.top);
                    float overlapBottom = Math.min(a.bottom, b.bottom);
                    float verticalOverlap = Math.max(0f, overlapBottom - overlapTop);
                    float minHeight = Math.max(1f, Math.min(a.height(), b.height()));
                    float gap = Math.max(0f, Math.max(a.left, b.left) - Math.min(a.right, b.right));

                    boolean sameLine = verticalOverlap / minHeight > 0.35f &&
                            gap < Math.max(a.height(), b.height()) * 3.0f;

                    if (!sameLine) continue;

                    RectF merged = new RectF(
                            Math.max(0f, Math.min(a.left, b.left)),
                            Math.max(0f, Math.min(a.top, b.top)),
                            Math.min(imageW, Math.max(a.right, b.right)),
                            Math.min(imageH, Math.max(a.bottom, b.bottom))
                    );
                    boxes.set(i, merged);
                    boxes.remove(j);
                    changed = true;
                    break outer;
                }
            }
        }
    }
}
