package com.ansub.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

// Frosted glass without live blur (Android 11 can't): the wallpaper is blurred once, small, and
// each glass shape draws the part of it that lies underneath, scaled up, plus a tint.
final class Glass {
    private static Bitmap blurred;
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private Glass() {}

    // A tenth of the size, then three passes of a radius-2 box blur: about 128x72 for a 720p screen.
    static Bitmap blur(Bitmap src) {
        int w = Math.max(1, src.getWidth() / 10), h = Math.max(1, src.getHeight() / 10);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        if (small.getConfig() != Bitmap.Config.ARGB_8888 || !small.isMutable()) {
            Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
            if (copy != small) small.recycle();
            small = copy;
        }
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 2, true);
            box(tmp, px, w, h, 2, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = in[horizontal ? l * w + k : k * w + l];
                    a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
                }
                out[horizontal ? l * w + i : i * w + l] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }

    // The blurred wallpaper every glass shape draws from. The last one set wins.
    static void setBlurred(Bitmap b) {
        Bitmap old = blurred;
        blurred = b;
        for (View v : hosts.toArray(new View[0])) v.invalidate();
        if (old != null && old != b) old.recycle();
    }

    static Bitmap blurred() {
        return blurred;
    }

    // Paints the blurred wallpaper under host, clipped to a rounded rect, then the tint on top.
    static final class Backdrop extends Drawable {
        private final View host;
        private final float radius;
        private final int tint;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private final Rect src = new Rect();
        private final int[] at = new int[2];

        Backdrop(View host, float radiusPx, int tint) {
            this.host = host;
            this.radius = radiusPx;
            this.tint = tint;
            tintPaint.setColor(tint);
            hosts.add(host);
        }

        @Override
        public void draw(Canvas c) {
            box.set(getBounds());
            clip.reset();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);
            Bitmap b = blurred;
            if (b != null && !b.isRecycled()) {
                View root = host.getRootView();
                int sw = Math.max(1, root.getWidth()), sh = Math.max(1, root.getHeight());
                host.getLocationOnScreen(at);
                float sx = (float) b.getWidth() / sw, sy = (float) b.getHeight() / sh;
                src.set(Math.round(at[0] * sx), Math.round(at[1] * sy),
                        Math.round((at[0] + host.getWidth()) * sx), Math.round((at[1] + host.getHeight()) * sy));
                c.save();
                c.clipPath(clip);
                c.drawBitmap(b, src, getBounds(), paint);
                c.restore();
            }
            c.drawPath(clip, tintPaint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); tintPaint.setAlpha(a * (tint >>> 24) / 255); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
