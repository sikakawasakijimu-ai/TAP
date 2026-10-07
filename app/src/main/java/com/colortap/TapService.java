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
import android.widget.TextView;

/**
 * Accessibility service that performs taps and shows the floating stop button.
 * An accessibility overlay needs no "draw over other apps" permission.
 */
public class TapService extends AccessibilityService {

    static volatile TapService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView stopButton;
    private WindowManager.LayoutParams lp;

    // Stop button bounds in screen pixels, read by the scan thread to skip that area.
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
        hideStopButton();
        stopService(new Intent(this, CaptureService.class));
        return super.onUnbind(intent);
    }

    void tap(float x, float y) {
        main.post(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 40))
                    .build();
            dispatchGesture(g, null, null);
        });
    }

    void showStopButton() {
        main.post(() -> {
            if (stopButton != null) return;
            float d = getResources().getDisplayMetrics().density;

            TextView b = new TextView(this);
            b.setText("■ 停止");
            b.setTextColor(Color.WHITE);
            b.setTextSize(14);
            int pad = (int) (10 * d);
            b.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC333333);
            bg.setCornerRadius(8 * d);
            b.setBackground(bg);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (int) (8 * d);
            lp.y = (int) (120 * d);

            b.setOnTouchListener(new DragListener((int) (8 * d)));
            b.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> updateBounds());
            wm.addView(b, lp);
            stopButton = b;
        });
    }

    void hideStopButton() {
        main.post(() -> {
            if (stopButton == null) return;
            wm.removeView(stopButton);
            stopButton = null;
            btnLeft = btnTop = btnRight = btnBottom = 0;
        });
    }

    private void updateBounds() {
        if (stopButton == null) return;
        int[] loc = new int[2];
        stopButton.getLocationOnScreen(loc);
        btnLeft = loc[0];
        btnTop = loc[1];
        btnRight = loc[0] + stopButton.getWidth();
        btnBottom = loc[1] + stopButton.getHeight();
    }

    /** Drags the button; a touch that does not move far enough counts as a click (stop). */
    private class DragListener implements View.OnTouchListener {
        private final int slop;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        DragListener(int slop) {
            this.slop = slop;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        wm.updateViewLayout(v, lp);
                        v.post(TapService.this::updateBounds);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        startService(new Intent(TapService.this, CaptureService.class)
                                .setAction(CaptureService.ACTION_STOP));
                    }
                    return true;
            }
            return false;
        }
    }
}
