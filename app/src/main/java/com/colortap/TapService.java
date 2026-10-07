package com.colortap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Accessibility service that performs taps and shows the floating control panel
 * ("＋色" to pick a color from the screen, "■ 停止" to stop).
 * Accessibility overlays need no "draw over other apps" permission.
 */
public class TapService extends AccessibilityService {

    static volatile TapService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private View pickOverlay;

    // Panel bounds in screen pixels, read by the scan thread to skip that area.
    volatile int btnLeft, btnTop, btnRight, btnBottom;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        hidePanel();
        stopService(new Intent(this, CaptureService.class));
        return super.onUnbind(intent);
    }

    /**
     * Taps the first n points of xy (x0, y0, x1, y1, ...) one after another.
     * They go in one gesture because a new dispatchGesture cancels one in progress.
     */
    void tap(float[] xy, int n) {
        float[] pts = java.util.Arrays.copyOf(xy, n * 2);
        main.post(() -> {
            GestureDescription.Builder b = new GestureDescription.Builder();
            int max = Math.min(n, GestureDescription.getMaxStrokeCount());
            for (int i = 0; i < max; i++) {
                Path path = new Path();
                path.moveTo(pts[i * 2], pts[i * 2 + 1]);
                b.addStroke(new GestureDescription.StrokeDescription(path, i * 80L, 40));
            }
            dispatchGesture(b.build(), null, null);
        });
    }

    void showPanel() {
        main.post(() -> {
            if (panel != null) return;
            float d = getResources().getDisplayMetrics().density;

            LinearLayout p = new LinearLayout(this);
            p.setOrientation(LinearLayout.HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC333333);
            bg.setCornerRadius(8 * d);
            p.setBackground(bg);

            TextView add = button("＋色", d);
            TextView stop = button("■ 停止", d);
            add.setOnTouchListener(new DragListener((int) (8 * d), this::startPick));
            stop.setOnTouchListener(new DragListener((int) (8 * d), () ->
                    startService(new Intent(this, CaptureService.class)
                            .setAction(CaptureService.ACTION_STOP))));
            p.addView(add);
            p.addView(stop);

            panelLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.x = (int) (8 * d);
            panelLp.y = (int) (120 * d);

            p.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> updateBounds());
            wm.addView(p, panelLp);
            panel = p;
        });
    }

    private TextView button(String text, float d) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        int pad = (int) (10 * d);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    void hidePanel() {
        main.post(() -> {
            cancelPick();
            if (panel == null) return;
            wm.removeView(panel);
            panel = null;
            btnLeft = btnTop = btnRight = btnBottom = 0;
        });
    }

    private void updateBounds() {
        if (panel == null) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        btnLeft = loc[0];
        btnTop = loc[1];
        btnRight = loc[0] + panel.getWidth();
        btnBottom = loc[1] + panel.getHeight();
    }

    /** Shows a transparent full-screen layer; the next tap on it picks the color under the finger. */
    private void startPick() {
        CaptureService cs = CaptureService.instance;
        if (pickOverlay != null || cs == null) return;
        cs.setPicking(true);
        float d = getResources().getDisplayMetrics().density;

        FrameLayout layer = new FrameLayout(this);
        TextView banner = new TextView(this);
        banner.setText("色を取りたい場所をタップ\n(この帯をタップでキャンセル)");
        banner.setTextColor(Color.WHITE);
        banner.setBackgroundColor(0xCC000000);
        banner.setGravity(Gravity.CENTER);
        int pad = (int) (12 * d);
        banner.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        blp.bottomMargin = (int) (64 * d);
        banner.setOnClickListener(v -> cancelPick());
        layer.addView(banner, blp);

        layer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = e.getRawX(), y = e.getRawY();
                removePickOverlay();
                CaptureService c = CaptureService.instance;
                if (c != null) c.pickAt(x, y);
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(layer, lp);
        pickOverlay = layer;
    }

    private void cancelPick() {
        if (pickOverlay == null) return;
        removePickOverlay();
        CaptureService cs = CaptureService.instance;
        if (cs != null) cs.setPicking(false);
    }

    private void removePickOverlay() {
        if (pickOverlay == null) return;
        wm.removeView(pickOverlay);
        pickOverlay = null;
    }

    /** Called by CaptureService after a pick; color is RGB, or -1 if it could not be read. */
    void onPicked(int color, boolean added) {
        main.post(() -> {
            String msg = color < 0 ? "色を取得できませんでした。もう一度試してください"
                    : String.format(added ? "#%06X を追加しました" : "#%06X は登録済みです", color);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    /** Drags the panel; a touch that does not move far enough counts as a click. */
    private class DragListener implements View.OnTouchListener {
        private final int slop;
        private final Runnable onClick;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        DragListener(int slop, Runnable onClick) {
            this.slop = slop;
            this.onClick = onClick;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = panelLp.x;
                    startY = panelLp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging && panel != null) {
                        panelLp.x = startX + (int) dx;
                        panelLp.y = startY + (int) dy;
                        wm.updateViewLayout(panel, panelLp);
                        panel.post(TapService.this::updateBounds);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) onClick.run();
                    return true;
            }
            return false;
        }
    }
}
