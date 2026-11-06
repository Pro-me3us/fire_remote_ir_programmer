package com.prome3us.irprog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.amazon.tv.devicecontrol.commons.Definitions.RemoteButton;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagram of the Fire TV remote for picking which button to program. The remote artwork itself is
 * the user-supplied PNG at assets/remote_outline.png (a transparent-background, white-outline image,
 * 350x1272) - this view draws that bitmap as-is and overlays only colored highlight shapes on top of
 * it, rather than drawing the remote's icons/body by hand.
 *
 * Shows every physical button; the 20 that map to a real RemoteButton are navigable and color-coded by
 * programming state, the rest (Home, Alexa mic, brightness, and the 4 app-shortcut rectangles) are
 * left undrawn/non-interactive - they're not part of the IR API (see Definitions.RemoteButton's full
 * constant list).
 *
 * Physical button mapping confirmed by hardware testing on a real remote (not just icon-reading): the
 * TV-icon button is GUIDE_LIVE; CUSTOM_A/B/C are the '1', '2', and top-right headphone-icon buttons
 * respectively - NOT any of the 4 app-shortcut rectangles, which turn out to be fixed OS app-launch
 * shortcuts with no IR capability at all. Home, Alexa/mic, and brightness remain non-programmable too.
 */
public class RemoteDiagramView extends View {

    private static final String ASSET_NAME = "remote_outline.png";
    private static final float ASSET_ASPECT = 350f / 1272f; // match the actual PNG's pixel dimensions

    enum Shape { CIRCLE, ROCKER_TOP, ROCKER_BOTTOM, DPAD_ARC, DPAD_CENTER, APP_RECT }

    static final class Slot {
        final RemoteButton button; // null = decorative/non-programmable
        final Shape shape;
        final float cx, cy;        // normalized DRAWING center, 0..1
        final float w, h;          // normalized size, 0..1
        final float startAngle, sweep; // for DPAD_ARC, degrees
        final float navCx, navCy;  // normalized NAVIGATION anchor - see note below

        // Most shapes draw and navigate from the same point, so this is the common-case constructor.
        Slot(RemoteButton button, Shape shape, float cx, float cy, float w, float h, float startAngle, float sweep) {
            this(button, shape, cx, cy, w, h, startAngle, sweep, cx, cy);
        }

        /**
         * navCx/navCy exist because several buttons share the exact same DRAWING center: the 4 d-pad
         * arcs + SELECT are all drawn around one ring's center, and a rocker's top/bottom halves are
         * both drawn from the full pill's center. Left-vs-right-vs-up-vs-down directional movement is
         * computed purely from each slot's (cx,cy) - if two buttons sit at the identical point, there's
         * zero distance/direction between them and navigation can never move from one to the other
         * (whichever is listed first "wins" and the other becomes an unreachable dead end, despite
         * still being drawn and highlighted). This constructor lets such slots keep their shared
         * drawing center while getting distinct, spatially-separated points for navigation instead.
         */
        Slot(RemoteButton button, Shape shape, float cx, float cy, float w, float h, float startAngle, float sweep,
             float navCx, float navCy) {
            this.button = button; this.shape = shape;
            this.cx = cx; this.cy = cy; this.w = w; this.h = h;
            this.startAngle = startAngle; this.sweep = sweep;
            this.navCx = navCx; this.navCy = navCy;
        }
        boolean isProgrammable() { return button != null; }
    }

    /** A DPAD_ARC slot whose drawing geometry is the shared ring, but whose nav anchor is offset
     *  toward midAngleDeg (270=up, 0=right, 90=down, 180=left in screen coords) so it's spatially
     *  distinct from the ring's other 3 arcs and from SELECT at the ring's true center. */
    private static Slot dpadArcSlot(RemoteButton b, float ringCx, float ringCy, float ringW, float ringH,
                                     float drawStartAngle, float midAngleDeg, float navK) {
        double rad = Math.toRadians(midAngleDeg);
        float navCx = ringCx + (float) Math.cos(rad) * (ringW / 2f) * navK;
        float navCy = ringCy + (float) Math.sin(rad) * (ringH / 2f) * navK;
        return new Slot(b, Shape.DPAD_ARC, ringCx, ringCy, ringW, ringH, drawStartAngle, 90, navCx, navCy);
    }

    /** A ROCKER_TOP/BOTTOM slot whose drawing geometry is the full shared pill, but whose nav anchor
     *  sits in its own quarter of the pill so it's spatially distinct from the other half. */
    private static Slot rockerSlot(RemoteButton b, Shape half, float pillCx, float pillCy, float pillW, float pillH) {
        float navCy = pillCy + (half == Shape.ROCKER_TOP ? -0.25f : 0.25f) * pillH;
        return new Slot(b, half, pillCx, pillCy, pillW, pillH, 0, 0, pillCx, navCy);
    }

    // Coordinates measured directly from assets/remote_outline.png via connected-component analysis
    // (scipy.ndimage.label on the alpha channel, bounding box of each button's outline shape) - NOT
    // eyeballed. The PNG's circles aren't equal-fraction in w/h because the image itself is far from
    // square (350x1272): the same physical circle has a very different width-fraction than
    // height-fraction, which is exactly what tripped up an earlier eyeballed version of this file.
    private static List<Slot> buildSlots() {
        List<Slot> s = new ArrayList<Slot>();
        s.add(new Slot(RemoteButton.POWER, Shape.CIRCLE, 0.1986f, 0.0605f, 0.1743f, 0.0487f, 0, 0));
        s.add(new Slot(RemoteButton.CUSTOM_C, Shape.CIRCLE, 0.7971f, 0.0605f, 0.1771f, 0.0487f, 0, 0));

        // D-pad: 4 arc quadrants on the OUTER ring, SELECT on the smaller INNER circle. All 4 arcs
        // share the ring's center as their DRAWING point (that's what makes them one ring visually),
        // so each gets a separate navigation anchor offset toward its own arc's angular midpoint -
        // 270 deg=up, 0 deg=right, 90 deg=down, 180 deg=left in screen coordinates (y increases downward).
        {
            float ringCx = 0.4914f, ringCy = 0.2645f, ringW = 0.7429f, ringH = 0.2068f;
            float navK = 0.55f; // fraction of the ring's radius to offset the nav anchor outward
            s.add(dpadArcSlot(RemoteButton.UP, ringCx, ringCy, ringW, ringH, 225, 270, navK));
            s.add(dpadArcSlot(RemoteButton.RIGHT, ringCx, ringCy, ringW, ringH, 315, 0, navK));
            s.add(dpadArcSlot(RemoteButton.DOWN, ringCx, ringCy, ringW, ringH, 45, 90, navK));
            s.add(dpadArcSlot(RemoteButton.LEFT, ringCx, ringCy, ringW, ringH, 135, 180, navK));
        }
        s.add(new Slot(RemoteButton.SELECT, Shape.DPAD_CENTER, 0.4943f, 0.2649f, 0.4457f, 0.1226f, 0, 0));

        s.add(new Slot(RemoteButton.BACK, Shape.CIRCLE, 0.2200f, 0.4127f, 0.2286f, 0.0629f, 0, 0));
        s.add(new Slot(RemoteButton.MENU, Shape.CIRCLE, 0.7800f, 0.4131f, 0.2286f, 0.0621f, 0, 0));

        s.add(new Slot(RemoteButton.REWIND, Shape.CIRCLE, 0.2186f, 0.4898f, 0.2257f, 0.0629f, 0, 0));
        s.add(new Slot(RemoteButton.PLAY_PAUSE, Shape.CIRCLE, 0.5000f, 0.4902f, 0.2286f, 0.0621f, 0, 0));
        s.add(new Slot(RemoteButton.FAST_FORWARD, Shape.CIRCLE, 0.7800f, 0.4894f, 0.2286f, 0.0621f, 0, 0));

        // Volume/channel rockers: the FULL pill's box is used for both halves' DRAWING (ROCKER_TOP/
        // BOTTOM each draws only its half, but needs the whole pill's geometry to shape that half
        // correctly) - but each half needs its own nav anchor, same reasoning as the d-pad arcs above,
        // or VOLUME_DOWN/CHANNEL_DOWN become highlighted-but-unreachable dead ends.
        s.add(rockerSlot(RemoteButton.VOLUME_UP, Shape.ROCKER_TOP, 0.2186f, 0.6034f, 0.2200f, 0.1376f));
        s.add(rockerSlot(RemoteButton.VOLUME_DOWN, Shape.ROCKER_BOTTOM, 0.2186f, 0.6034f, 0.2200f, 0.1376f));
        s.add(new Slot(RemoteButton.GUIDE_LIVE, Shape.CIRCLE, 0.5000f, 0.5668f, 0.2286f, 0.0629f, 0, 0));
        s.add(rockerSlot(RemoteButton.CHANNEL_UP, Shape.ROCKER_TOP, 0.7771f, 0.6034f, 0.2229f, 0.1376f));
        s.add(rockerSlot(RemoteButton.CHANNEL_DOWN, Shape.ROCKER_BOTTOM, 0.7771f, 0.6034f, 0.2229f, 0.1376f));

        s.add(new Slot(RemoteButton.MUTE, Shape.CIRCLE, 0.2214f, 0.7166f, 0.2314f, 0.0637f, 0, 0));
        s.add(new Slot(RemoteButton.CUSTOM_A, Shape.CIRCLE, 0.5000f, 0.7170f, 0.2286f, 0.0629f, 0, 0));
        s.add(new Slot(RemoteButton.CUSTOM_B, Shape.CIRCLE, 0.7814f, 0.7170f, 0.2314f, 0.0629f, 0, 0));

        // 4 app-shortcut rectangles are intentionally NOT added: confirmed non-programmable.
        return s;
    }

    private static final List<Slot> ALL_SLOTS = buildSlots(); // every slot here is programmable now
    private static final List<Slot> NAV_SLOTS = ALL_SLOTS;

    interface CodeStateProvider {
        boolean hasCode(RemoteButton b);   // a code is learned/set locally for this button
        boolean isPushed(RemoteButton b);  // that code has been copied to the physical remote
    }
    interface OnButtonSelectedListener { void onButtonSelected(RemoteButton b); }   // short press
    interface OnButtonLongPressedListener { void onButtonLongPressed(RemoteButton b); }

    private CodeStateProvider codeStateProvider;
    private OnButtonSelectedListener selectedListener;
    private OnButtonLongPressedListener longPressListener;
    private int focusedNavIndex = 0;
    private boolean longPressFired = false;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            longPressFired = true;
            if (longPressListener != null) longPressListener.onButtonLongPressed(getFocusedButton());
        }
    };
    private RemoteButton learningButton; // non-null while a learn is in progress
    private boolean pulseOn = true;
    private Bitmap remoteBitmap;
    private final Handler pulseHandler = new Handler(Looper.getMainLooper());
    private final Runnable pulseTick = new Runnable() {
        @Override public void run() {
            if (learningButton != null) {
                pulseOn = !pulseOn;
                invalidate();
                pulseHandler.postDelayed(this, 400);
            }
        }
    };

    private static final int COLOR_EMPTY = 0xFFFFC107;      // yellow - no code stored at all
    private static final int COLOR_LEARNING = 0xFFE53935;   // red - actively learning now
    private static final int COLOR_LEARNING_DIM = 0xFF8C1A17;
    private static final int COLOR_LEARNED = 0xFF2196F3;    // blue - code stored locally, not yet pushed
    private static final int COLOR_PROGRAMMED = 0xFF43A047; // green - pushed to the physical remote
    private static final int COLOR_FOCUS_FILL = 0x80FFFFFF;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RemoteDiagramView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        loadBitmap(ctx);
    }

    private void loadBitmap(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(ASSET_NAME);
            remoteBitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(MainActivity.TAG, "Could not load " + ASSET_NAME + " from assets", e);
            remoteBitmap = null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    void setCodeStateProvider(CodeStateProvider p) { this.codeStateProvider = p; }
    void setOnButtonSelectedListener(OnButtonSelectedListener l) { this.selectedListener = l; }
    void setOnButtonLongPressedListener(OnButtonLongPressedListener l) { this.longPressListener = l; }

    RemoteButton getFocusedButton() { return NAV_SLOTS.get(focusedNavIndex).button; }

    /** Call when a learn sequence starts/ends for `b` (null = ended) to drive the red pulsing state. */
    void setLearningButton(RemoteButton b) {
        this.learningButton = b;
        pulseHandler.removeCallbacks(pulseTick);
        if (b != null) {
            pulseOn = true;
            pulseHandler.postDelayed(pulseTick, 400);
        }
        invalidate();
    }

    /** Call after any code storage change (learn finished, clear, etc.) so colors refresh. */
    void refresh() { invalidate(); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int targetW = w, targetH = (int) (w / ASSET_ASPECT);
        if (targetH > h) { targetH = h; targetW = (int) (h * ASSET_ASPECT); }
        setMeasuredDimension(targetW, targetH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();

        if (remoteBitmap != null) {
            Rect src = new Rect(0, 0, remoteBitmap.getWidth(), remoteBitmap.getHeight());
            Rect dst = new Rect(0, 0, w, h);
            canvas.drawBitmap(remoteBitmap, src, dst, bitmapPaint);
        }

        float stroke = Math.max(2f, w * 0.012f);
        for (Slot s : ALL_SLOTS) drawSlot(canvas, s, w, h, stroke);
    }

    private void drawSlot(Canvas canvas, Slot s, int w, int h, float baseStroke) {
        boolean focused = isFocused() && NAV_SLOTS.get(focusedNavIndex) == s;
        int color = colorFor(s);
        float stroke = focused ? baseStroke * 2.2f : baseStroke;
        strokePaint.setStrokeWidth(stroke);
        strokePaint.setColor(color);

        float cx = s.cx * w, cy = s.cy * h, rw = s.w * w, rh = s.h * h;

        switch (s.shape) {
            case CIRCLE: case DPAD_CENTER: {
                float r = Math.min(rw, rh) / 2f;
                if (focused) { fillPaint.setColor(COLOR_FOCUS_FILL); canvas.drawCircle(cx, cy, r, fillPaint); }
                canvas.drawCircle(cx, cy, r, strokePaint);
                break;
            }
            case DPAD_ARC: {
                float r = Math.min(rw, rh) / 2f;
                RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
                if (focused) {
                    Path fillArc = new Path();
                    fillArc.moveTo(cx, cy);
                    fillArc.arcTo(oval, s.startAngle, s.sweep);
                    fillArc.close();
                    fillPaint.setColor(COLOR_FOCUS_FILL);
                    canvas.drawPath(fillArc, fillPaint);
                }
                Path arc = new Path();
                arc.addArc(oval, s.startAngle, s.sweep);
                canvas.drawPath(arc, strokePaint);
                break;
            }
            case ROCKER_TOP: case ROCKER_BOTTOM: {
                // Both halves of a rocker share the same (cx,cy,w,h) - the full pill's geometry - so
                // each half can shape itself (the arc cap) correctly relative to the whole pill. But
                // that means a focus-fill drawn from that same full-pill rect would cover BOTH halves
                // at once, regardless of which one is actually focused - exactly the "volume up and
                // down highlight together" bug. Fix: build the half-only Path FIRST, then use that same
                // clipped shape for both the fill and the stroke, instead of a separate full-pill rect.
                float left = cx - rw / 2f, right = cx + rw / 2f, top = cy - rh / 2f, bottom = cy + rh / 2f;
                float midY = cy;
                Path half = new Path();
                if (s.shape == Shape.ROCKER_TOP) {
                    RectF topArc = new RectF(left, top, right, top + rw);
                    half.arcTo(topArc, 180, 180);
                    half.lineTo(right, midY);
                    half.lineTo(left, midY);
                    half.close();
                } else {
                    RectF botArc = new RectF(left, bottom - rw, right, bottom);
                    half.moveTo(left, midY);
                    half.lineTo(right, midY);
                    half.arcTo(botArc, 0, 180);
                    half.close();
                }
                if (focused) {
                    fillPaint.setColor(COLOR_FOCUS_FILL);
                    canvas.drawPath(half, fillPaint);
                }
                canvas.drawPath(half, strokePaint);
                break;
            }
            case APP_RECT: {
                RectF rect = new RectF(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f);
                if (focused) { fillPaint.setColor(COLOR_FOCUS_FILL); canvas.drawRoundRect(rect, rh / 2f, rh / 2f, fillPaint); }
                canvas.drawRoundRect(rect, rh / 2f, rh / 2f, strokePaint);
                break;
            }
        }
    }

    private int colorFor(Slot s) {
        if (s.button == learningButton) return pulseOn ? COLOR_LEARNING : COLOR_LEARNING_DIM;
        if (codeStateProvider == null || !codeStateProvider.hasCode(s.button)) return COLOR_EMPTY;
        return codeStateProvider.isPushed(s.button) ? COLOR_PROGRAMMED : COLOR_LEARNED;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return moveFocus(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return moveFocus(0, 1);
            case KeyEvent.KEYCODE_DPAD_LEFT: return moveFocus(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return moveFocus(1, 0);
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                // Only arm the timer on the initial press (repeatCount==0), not on OS key-repeat
                // events that fire while the key stays held - those would otherwise keep restarting
                // the timer and the long-press would never actually fire.
                if (event.getRepeatCount() == 0) {
                    longPressFired = false;
                    longPressHandler.removeCallbacks(longPressRunnable);
                    longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressHandler.removeCallbacks(longPressRunnable);
            if (!longPressFired && selectedListener != null) {
                selectedListener.onButtonSelected(getFocusedButton());
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                              android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    private boolean moveFocus(int dx, int dy) {
    
        if (dx > 0 && NAV_SLOTS.get(focusedNavIndex).navCx >= 0.68f) {
            return false;
        }
        
        float[] xs = new float[NAV_SLOTS.size()], ys = new float[NAV_SLOTS.size()];
        for (int i = 0; i < NAV_SLOTS.size(); i++) {
            xs[i] = NAV_SLOTS.get(i).navCx;
            ys[i] = NAV_SLOTS.get(i).navCy;
        }
        int next = findNextInDirection(xs, ys, focusedNavIndex, dx, dy);
        if (next < 0) return false;
        focusedNavIndex = next;
        invalidate();
        return true;
    }

    /**
     * Nearest-neighbor spatial navigation, with no Android dependencies so it's directly unit
     * testable: given parallel arrays of point coordinates, the current index, and a direction
     * (dx,dy each in {-1,0,1}), returns the index of the best next point in that direction, or -1 if
     * none exists (the caller should then treat the key as unhandled, e.g. to let focus leave this
     * view for a neighboring one). Prefers points within a ~45 degree cone of the requested direction;
     * falls back to the nearest point anywhere further along that direction if the cone is empty, so
     * navigation never gets stuck even with an irregular button layout.
     */
    static int findNextInDirection(float[] xs, float[] ys, int curIndex, int dx, int dy) {
        float curX = xs[curIndex], curY = ys[curIndex];
        int best = -1; float bestScore = Float.MAX_VALUE;
        int fallback = -1; float fallbackScore = Float.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            if (i == curIndex) continue;
            float ddx = xs[i] - curX, ddy = ys[i] - curY;
            float along = ddx * dx + ddy * dy;      // movement in requested direction
            float across = Math.abs(ddx * dy - ddy * dx); // perpendicular drift
            if (along <= 0.001f) continue;
            float dist = ddx * ddx + ddy * ddy;
            if (across <= along * 1.0f) { // within a ~45 degree cone
                if (dist < bestScore) { bestScore = dist; best = i; }
            }
            if (dist < fallbackScore) { fallbackScore = dist; fallback = i; }
        }
        return best != -1 ? best : fallback;
    }
}
