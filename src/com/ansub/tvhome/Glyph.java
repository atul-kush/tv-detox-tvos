package com.ansub.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

// White icons drawn in code on a 24-unit grid, so the app needs no image resources.
// setTint recolours them (dark on a focused white tile).
final class Glyph extends Drawable {
    private static final int WIFI = 0, ETHERNET = 1, OFFLINE = 2, GEAR = 3, ACCOUNT = 4, BLUETOOTH = 5,
            AUDIO = 6, HDMI = 7, PLAY = 8, PLUS = 9, PICTURE = 10, REFRESH = 11, CHECK = 12, EMPTY = 13, CLOCK = 14, DOWNLOAD = 15;
    private static final int DIM = 0x47;

    static Drawable wifi(int level) { return new Glyph(WIFI, Math.max(1, Math.min(3, level))); }
    static Drawable ethernet() { return new Glyph(ETHERNET, 0); }
    static Drawable offline() { return new Glyph(OFFLINE, 0); }
    static Drawable gear() { return new Glyph(GEAR, 0); }
    static Drawable account() { return new Glyph(ACCOUNT, 0); }
    static Drawable bluetooth() { return new Glyph(BLUETOOTH, 0); }
    static Drawable audio() { return new Glyph(AUDIO, 0); }
    static Drawable hdmi() { return new Glyph(HDMI, 0); }
    static Drawable play() { return new Glyph(PLAY, 0); }
    static Drawable plus() { return new Glyph(PLUS, 0); }
    static Drawable picture() { return new Glyph(PICTURE, 0); }
    static Drawable refresh() { return new Glyph(REFRESH, 0); }
    static Drawable clock() { return new Glyph(CLOCK, 0); }
    static Drawable download() { return new Glyph(DOWNLOAD, 0); }
    // A ticked or empty round box, for choices that can be on or off.
    static Drawable check(boolean on) { return new Glyph(on ? CHECK : EMPTY, 0); }

    private final int kind, level;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int color = 0xFFFFFFFF, alpha = 255;

    private Glyph(int kind, int level) {
        this.kind = kind;
        this.level = level;
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        c.save();
        c.translate(getBounds().left + (getBounds().width() - 24 * s) / 2, getBounds().top + (getBounds().height() - 24 * s) / 2);
        c.scale(s, s);
        switch (kind) {
            case WIFI: wifiFan(c, level); break;
            case OFFLINE:
                wifiFan(c, 0);
                stroke(c, 2f, 255);
                c.drawLine(3, 2, 21, 19.5f, line);
                break;
            case ETHERNET:
                stroke(c, 2f, 255);
                oval.set(4, 5, 20, 16);
                c.drawRoundRect(oval, 1.5f, 1.5f, line);
                c.drawLine(8, 19.5f, 16, 19.5f, line);
                c.drawLine(12, 16, 12, 19.5f, line);
                break;
            case GEAR: drawFilled(c, gearPath()); break;
            case ACCOUNT: drawFilled(c, accountPath()); break;
            case BLUETOOTH:
                stroke(c, 2.2f, 255);
                path.reset();
                path.moveTo(7, 7.5f); path.lineTo(17, 16.5f); path.lineTo(12, 21); path.lineTo(12, 3);
                path.lineTo(17, 7.5f); path.lineTo(7, 16.5f);
                c.drawPath(path, line);
                break;
            case AUDIO:
                path.reset();
                path.moveTo(4, 9.5f); path.lineTo(7.5f, 9.5f); path.lineTo(12, 5.5f); path.lineTo(12, 18.5f);
                path.lineTo(7.5f, 14.5f); path.lineTo(4, 14.5f); path.close();
                drawFilled(c, path);
                stroke(c, 2f, 255);
                arc(c, 12, 12, 4.4f, -45, 90);
                arc(c, 12, 12, 7.8f, -50, 100);
                break;
            case HDMI:
                stroke(c, 2f, 255);
                path.reset();
                path.moveTo(3, 9); path.lineTo(21, 9); path.lineTo(21, 13); path.lineTo(18.5f, 16);
                path.lineTo(5.5f, 16); path.lineTo(3, 13); path.close();
                c.drawPath(path, line);
                c.drawLine(8, 12.5f, 16, 12.5f, line);
                break;
            case PLAY:
                path.reset();
                path.moveTo(8, 5.5f); path.lineTo(19, 12); path.lineTo(8, 18.5f); path.close();
                drawFilled(c, path);
                break;
            case PICTURE:
                // A framed landscape: rounded frame, a sun and two hills.
                stroke(c, 2f, 255);
                oval.set(3, 5, 21, 19);
                c.drawRoundRect(oval, 2.5f, 2.5f, line);
                c.drawCircle(15.5f, 9.5f, 1.6f, line);
                path.reset();
                path.moveTo(4, 17.5f); path.lineTo(9.5f, 11.5f); path.lineTo(13.5f, 15.5f); path.lineTo(15.5f, 13.5f);
                path.lineTo(20, 17.5f);
                c.drawPath(path, line);
                break;
            case REFRESH:
                // A clockwise circle, open at the top, ending in an arrowhead that points on round
                // the circle (like "arrow.clockwise"): "show another".
                stroke(c, 2.2f, 255);
                arc(c, 12, 12, 7.5f, -60, 300);
                // The arc ends at 240° (about 10 o'clock); the head sits there, aimed along the clockwise tangent.
                path.reset();
                path.moveTo(6.6f, 3.7f); path.lineTo(10.4f, 4.25f); path.lineTo(9.0f, 7.8f);
                c.drawPath(path, line);
                break;
            case CHECK:
                // A filled disc with the tick cut out of it, so it reads on white and on glass.
                fill.setColor(withAlpha(255));
                Paint knock = new Paint(line);
                knock.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                knock.setStrokeWidth(2.4f);
                path.reset();
                path.moveTo(7.6f, 12.2f); path.lineTo(10.7f, 15.2f); path.lineTo(16.4f, 9.2f);
                int layer = c.saveLayer(0, 0, 24, 24, null);
                c.drawCircle(12, 12, 9, fill);
                c.drawPath(path, knock);
                c.restoreToCount(layer);
                break;
            case CLOCK:
                stroke(c, 2f, 255);
                c.drawCircle(12, 12, 8.5f, line);
                c.drawLine(12, 12, 12, 7, line);
                c.drawLine(12, 12, 15.5f, 14, line);
                break;
            case DOWNLOAD:
                // An arrow down into a tray: "bring some in".
                stroke(c, 2.2f, 255);
                c.drawLine(12, 4, 12, 15, line);
                path.reset();
                path.moveTo(7.5f, 10.5f); path.lineTo(12, 15); path.lineTo(16.5f, 10.5f);
                c.drawPath(path, line);
                path.reset();
                path.moveTo(5, 15.5f); path.lineTo(5, 19); path.lineTo(19, 19); path.lineTo(19, 15.5f);
                c.drawPath(path, line);
                break;
            case EMPTY:
                stroke(c, 1.8f, 0x99);
                c.drawCircle(12, 12, 8.6f, line);
                break;
            default:
                stroke(c, 2f, 255);
                c.drawLine(12, 5, 12, 19, line);
                c.drawLine(5, 12, 19, 12, line);
        }
        c.restore();
    }

    // Apple-style fan from one centre: a filled dot and two bands, equal thickness, equal gaps.
    // Bands beyond the signal level are dim; level 0 draws everything dim.
    private void wifiFan(Canvas c, int lit) {
        path.reset();
        path.moveTo(12, 19.5f);
        oval.set(12 - 3.5f, 19.5f - 3.5f, 12 + 3.5f, 19.5f + 3.5f);
        path.arcTo(oval, -135, 90);
        path.close();
        fill.setColor(withAlpha(lit >= 1 ? 255 : DIM));
        c.drawPath(path, fill);
        stroke(c, 1f, lit >= 1 ? 255 : DIM);
        c.drawPath(path, line);
        stroke(c, 3f, lit >= 2 ? 255 : DIM);
        arc(c, 12, 19.5f, 8.1f, -135, 90);
        stroke(c, 3f, lit >= 3 ? 255 : DIM);
        arc(c, 12, 19.5f, 13.7f, -135, 90);
    }

    // Eight square teeth around a ring with a round hole: a gear, not a sun.
    private static Path gearPath() {
        Path p = new Path();
        p.setFillType(Path.FillType.EVEN_ODD);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            pt(p, i == 0, a - Math.toRadians(13), 7.6);
            pt(p, false, a - Math.toRadians(9), 10.5);
            pt(p, false, a + Math.toRadians(9), 10.5);
            pt(p, false, a + Math.toRadians(13), 7.6);
        }
        p.close();
        p.addCircle(12, 12, 3.4f, Path.Direction.CW);
        return p;
    }

    private static void pt(Path p, boolean first, double a, double r) {
        float x = (float) (12 + r * Math.cos(a)), y = (float) (12 + r * Math.sin(a));
        if (first) p.moveTo(x, y);
        else p.lineTo(x, y);
    }

    // Material's account_circle: a disc with the head and shoulders cut out.
    private static Path accountPath() {
        Path disc = new Path();
        disc.addCircle(12, 12, 10, Path.Direction.CW);
        Path head = new Path();
        head.addCircle(12, 9.5f, 3.5f, Path.Direction.CW);
        Path shoulders = new Path();
        shoulders.addOval(new RectF(5.4f, 15.2f, 18.6f, 25.8f), Path.Direction.CW);
        Path inner = new Path();
        inner.addCircle(12, 12, 8.1f, Path.Direction.CW);
        shoulders.op(inner, Path.Op.INTERSECT);
        disc.op(head, Path.Op.DIFFERENCE);
        disc.op(shoulders, Path.Op.DIFFERENCE);
        return disc;
    }

    private void drawFilled(Canvas c, Path p) {
        fill.setColor(withAlpha(255));
        c.drawPath(p, fill);
    }

    private void stroke(Canvas c, float width, int a) {
        line.setStrokeWidth(width);
        line.setColor(withAlpha(a));
    }

    private void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, start, sweep, false, line);
    }

    private int withAlpha(int a) {
        return (color & 0x00FFFFFF) | ((a * alpha / 255 * (color >>> 24) / 255) << 24);
    }

    @Override public void setTint(int tint) { color = tint; invalidateSelf(); }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); line.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 48; }
    @Override public int getIntrinsicHeight() { return 48; }
}
