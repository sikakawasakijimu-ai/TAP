package com.colortap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Captures the screen at reduced resolution and taps the largest patch of the target color.
 * Runs until stopped from the overlay button, the notification, or the app.
 */
public class CaptureService extends Service {

    static final String ACTION_STOP = "com.colortap.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";

    static volatile boolean running;
    static volatile CaptureService instance;

    /** Capture is 1/SCALE of the screen size in each direction: 1/9 the pixels to scan. */
    private static final int SCALE = 3;
    /** Sample every STEP-th pixel of the captured image. */
    private static final int STEP = 2;
    /** Matches are grouped into CELL x CELL blocks (captured pixels); the densest block wins. */
    private static final int CELL = 12;

    private static final String CHANNEL = "run";

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;

    private int capW, capH;
    private float toScreenX, toScreenY;
    private Settings settings;

    private int cells;
    // Per color: [color * cells + cell]
    private int[] cellCount, cellSumX, cellSumY;
    private byte[] row;
    // Last hits in screen coordinates (x0, y0, x1, y1, ...); reused while the screen is unchanged.
    private float[] hits;
    private int hitCount;

    // Color picking: while true, frames are copied into `frame` instead of scanned, and no taps happen.
    private volatile boolean picking;
    private java.util.List<Template> templates = new java.util.ArrayList<>();
    private byte[] frame;
    private boolean frameValid;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startForegroundCompat();

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                intent.getParcelableExtra(EXTRA_DATA));
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        settings = Settings.load(this);
        templates = Template.loadAll(this);

        thread = new HandlerThread("scan", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, handler);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        capW = Math.max(1, dm.widthPixels / SCALE);
        capH = Math.max(1, dm.heightPixels / SCALE);
        toScreenX = dm.widthPixels / (float) capW;
        toScreenY = dm.heightPixels / (float) capH;

        int cols = (capW + CELL - 1) / CELL, rows = (capH + CELL - 1) / CELL;
        cells = cols * rows;
        allocate();

        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("ColorTap", capW, capH,
                Math.max(1, dm.densityDpi / SCALE),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);

        instance = this;
        TapService svc = TapService.instance;
        if (svc != null) svc.showPanel();

        handler.postDelayed(scan, settings.intervalMs);
        return START_NOT_STICKY;
    }

    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
            } catch (IllegalStateException ignored) {
                // Reader closed while stopping.
            }
            if (img != null) {
                try {
                    if (picking) {
                        copyFrame(img);
                    } else {
                        hitCount = 0;
                        // Images take priority over colors when only one spot is tapped.
                        if (!templates.isEmpty()) {
                            copyFrame(img);
                            findImages();
                        }
                        if (settings.tapEachColor || hitCount == 0) findTarget(img);
                    }
                } finally {
                    img.close();
                }
            }
            TapService svc = TapService.instance;
            int delay = settings.intervalMs;
            if (svc != null && hitCount > 0 && !picking) {
                svc.tap(hits, hitCount);
                // Let the whole tap sequence finish before the next one replaces it.
                delay = Math.max(delay, hitCount * 80 + 40);
            }
            handler.postDelayed(this, delay);
        }
    };

    private void allocate() {
        int k = settings.colors.length;
        cellCount = new int[k * cells];
        cellSumX = new int[k * cells];
        cellSumY = new int[k * cells];
        hits = new float[(k + templates.size()) * 2];
        hitCount = 0;
    }

    /** Re-reads settings saved by the app, so edits apply without restarting. */
    void reloadSettings() {
        handler.post(() -> {
            settings = Settings.load(this);
            templates = Template.loadAll(this);
            allocate();
        });
    }

    void setPicking(boolean on) {
        handler.post(() -> {
            picking = on;
            frameValid = false;
            hitCount = 0;
        });
    }

    /** Adds the color at screen point (x, y) to the target list, then resumes tapping. */
    void pickAt(float x, float y) {
        handler.post(() -> {
            // Grab the newest frame (the pick layer is gone by now or nearly so).
            Image img = reader.acquireLatestImage();
            if (img != null) {
                try {
                    copyFrame(img);
                } finally {
                    img.close();
                }
            }
            int color = -1;
            boolean added = false;
            if (frameValid) {
                int cx = Math.max(0, Math.min(capW - 1, (int) (x / toScreenX)));
                int cy = Math.max(0, Math.min(capH - 1, (int) (y / toScreenY)));
                int i = (cy * capW + cx) * 4;
                color = ((frame[i] & 0xFF) << 16) | ((frame[i + 1] & 0xFF) << 8) | (frame[i + 2] & 0xFF);
                added = true;
                for (int c : settings.colors) if (c == color) added = false;
                if (added) {
                    int[] colors = java.util.Arrays.copyOf(settings.colors, settings.colors.length + 1);
                    colors[colors.length - 1] = color;
                    settings.colors = colors;
                    settings.save(this);
                    allocate();
                }
            }
            picking = false;
            frameValid = false;
            TapService svc = TapService.instance;
            if (svc != null) svc.onPicked(color, added);
        });
    }

    /** Saves the screen region (screen pixels) as a new image target, then resumes tapping. */
    void pickImage(float left, float top, float right, float bottom) {
        // Wait for a frame without the selection rectangle drawn on it.
        handler.postDelayed(() -> {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                try {
                    copyFrame(img);
                } finally {
                    img.close();
                }
            }
            int x0 = Math.max(0, (int) (left / toScreenX));
            int y0 = Math.max(0, (int) (top / toScreenY));
            int x1 = Math.min(capW, (int) Math.ceil(right / toScreenX));
            int y1 = Math.min(capH, (int) Math.ceil(bottom / toScreenY));
            boolean ok = false;
            if (frameValid && x1 - x0 >= 4 && y1 - y0 >= 4) {
                try {
                    Template.save(this, frame, capW, x0, y0, x1 - x0, y1 - y0);
                    templates = Template.loadAll(this);
                    allocate();
                    ok = true;
                } catch (java.io.IOException ignored) {
                }
            }
            picking = false;
            frameValid = false;
            TapService svc = TapService.instance;
            if (svc != null) svc.onImagePicked(ok);
        }, 250);
    }

    /** Copies the image into `frame` as tightly packed RGBA. */
    private void copyFrame(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int rowBytes = capW * 4;
        if (frame == null) frame = new byte[capW * capH * 4];
        int h = Math.min(img.getHeight(), capH);
        for (int y = 0; y < h; y++) {
            buf.position(y * rowStride);
            buf.get(frame, y * rowBytes, Math.min(rowBytes, buf.remaining()));
        }
        frameValid = true;
    }

    /** Adds the centre of each template found in `frame` to hits (only the first unless tapEachColor). */
    private void findImages() {
        for (Template t : templates) {
            if (matchTemplate(t) && !settings.tapEachColor) return;
        }
    }

    /**
     * Finds the position where the most sample points of t match (within tolerance).
     * Coarse pass on every 2nd position with early exit, then a fine pass around the best.
     */
    private boolean matchTemplate(Template t) {
        int n = t.sx.length;
        int allowed = n - (int) Math.ceil(n * settings.matchPct / 100.0);
        int[] best = {allowed + 1, -1, -1}; // misses, x, y
        int maxX = capW - t.w, maxY = capH - t.h;
        for (int y = 0; y <= maxY; y += 2) {
            for (int x = 0; x <= maxX; x += 2) tryAt(t, x, y, best);
        }
        if (best[1] < 0) return false;
        int bx = best[1], by = best[2];
        for (int y = Math.max(0, by - 1); y <= Math.min(maxY, by + 1); y++) {
            for (int x = Math.max(0, bx - 1); x <= Math.min(maxX, bx + 1); x++) tryAt(t, x, y, best);
        }
        hits[hitCount * 2] = (best[1] + t.w / 2f) * toScreenX;
        hits[hitCount * 2 + 1] = (best[2] + t.h / 2f) * toScreenY;
        hitCount++;
        return true;
    }

    /** Counts mismatching sample points at (x, y); records it in best if it beats best[0]. */
    private void tryAt(Template t, int x, int y, int[] best) {
        int tol = settings.tolerance, limit = best[0], miss = 0;
        byte[] f = frame;
        for (int i = 0; i < t.sx.length; i++) {
            int p = ((y + t.sy[i]) * capW + x + t.sx[i]) * 4;
            if (Math.abs((f[p] & 0xFF) - t.sr[i]) > tol
                    || Math.abs((f[p + 1] & 0xFF) - t.sg[i]) > tol
                    || Math.abs((f[p + 2] & 0xFF) - t.sb[i]) > tol) {
                if (++miss >= limit) return;
            }
        }
        best[0] = miss;
        best[1] = x;
        best[2] = y;
    }

    /** Adds to hits the centres of the densest patches of the target colors. */
    private void findTarget(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        int w = Math.min(img.getWidth(), capW), h = Math.min(img.getHeight(), capH);
        if (row == null || row.length < rowStride) row = new byte[rowStride];

        int[] colors = settings.colors;
        int k = colors.length;
        int[] tr = new int[k], tg = new int[k], tb = new int[k];
        for (int j = 0; j < k; j++) {
            tr[j] = (colors[j] >> 16) & 0xFF;
            tg[j] = (colors[j] >> 8) & 0xFF;
            tb[j] = colors[j] & 0xFF;
        }
        int tol = settings.tolerance;
        int cols = (capW + CELL - 1) / CELL;

        // Skip our own stop button so it is never tapped.
        int exL = 0, exT = 0, exR = 0, exB = 0;
        TapService svc = TapService.instance;
        if (svc != null) {
            exL = (int) (svc.btnLeft / toScreenX);
            exT = (int) (svc.btnTop / toScreenY);
            exR = (int) Math.ceil(svc.btnRight / toScreenX);
            exB = (int) Math.ceil(svc.btnBottom / toScreenY);
        }

        java.util.Arrays.fill(cellCount, 0);
        java.util.Arrays.fill(cellSumX, 0);
        java.util.Arrays.fill(cellSumY, 0);

        for (int y = 0; y < h; y += STEP) {
            buf.position(y * rowStride);
            buf.get(row, 0, Math.min(rowStride, buf.remaining()));
            boolean rowExcluded = y >= exT && y < exB;
            int cellRow = (y / CELL) * cols;
            for (int x = 0; x < w; x += STEP) {
                int i = x * pixStride;
                int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF;
                for (int j = 0; j < k; j++) {
                    if (Math.abs(r - tr[j]) > tol || Math.abs(g - tg[j]) > tol
                            || Math.abs(b - tb[j]) > tol) continue;
                    if (rowExcluded && x >= exL && x < exR) break;
                    int c = j * cells + cellRow + x / CELL;
                    cellCount[c]++;
                    cellSumX[c] += x;
                    cellSumY[c] += y;
                    break; // first matching color wins
                }
            }
        }

        int threshold = Math.max(1, settings.minHits) - 1;
        if (settings.tapEachColor) {
            for (int j = 0; j < k; j++) addBest(j * cells, (j + 1) * cells, threshold);
        } else {
            addBest(0, k * cells, threshold);
        }
    }

    /** Adds the centre of the densest cell in [from, to) to hits, if it beats threshold. */
    private void addBest(int from, int to, int threshold) {
        int best = -1, bestCount = threshold;
        for (int c = from; c < to; c++) {
            if (cellCount[c] > bestCount) {
                bestCount = cellCount[c];
                best = c;
            }
        }
        if (best < 0) return;
        hits[hitCount * 2] = (cellSumX[best] / (float) bestCount + 0.5f) * toScreenX;
        hits[hitCount * 2 + 1] = (cellSumY[best] / (float) bestCount + 0.5f) * toScreenY;
        hitCount++;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "動作中", NotificationManager.IMPORTANCE_LOW));

        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ColorTap 動作中")
                .setContentText("タップで設定を開く")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        instance = null;
        if (handler != null) {
            // Release on the scan thread so an in-progress scan never sees a closed reader.
            handler.removeCallbacksAndMessages(null);
            VirtualDisplay d = display;
            ImageReader r = reader;
            MediaProjection p = projection;
            handler.post(() -> {
                d.release();
                r.close();
                p.stop();
            });
            thread.quitSafely();
        } else if (projection != null) {
            projection.stop();
        }
        TapService svc = TapService.instance;
        if (wasRunning && svc != null) svc.hidePanel();
        super.onDestroy();
    }
}
