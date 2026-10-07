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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Captures the screen at reduced resolution and taps the registered targets (colors and images)
 * in priority order. Runs until stopped from the overlay panel, the notification, or the app.
 */
public class CaptureService extends Service {

    static final String ACTION_STOP = "com.colortap.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";

    static volatile boolean running;
    static volatile CaptureService instance;

    /** Capture is 1/SCALE of the screen size in each direction: 1/9 the pixels to scan. */
    private static final int SCALE = 3;
    /** Sample every STEP-th pixel of the captured image when looking for colors. */
    private static final int STEP = 2;
    /** Color matches are grouped into CELL x CELL blocks (captured pixels); the densest block wins. */
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

    // Targets in priority order: KIND_COLOR -> colors[index], KIND_IMAGE -> templates.get(index).
    private static final int KIND_COLOR = 0, KIND_IMAGE = 1;
    private int[] kind = new int[0], index = new int[0];
    private int[] colors = new int[0];
    private List<Template> templates = new ArrayList<>();

    private int cells;
    // Per color: [color * cells + cell]
    private int[] cellCount, cellSumX, cellSumY;
    private boolean[] colorFound;
    private float[] colorX, colorY;
    private byte[] row;
    // Hits to tap, in screen coordinates (x0, y0, x1, y1, ...); reused while the screen is unchanged.
    private float[] hits = new float[0];
    private int hitCount;

    // Picking: while true, frames are copied into `frame` instead of scanned, and no taps happen.
    private volatile boolean picking;
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
        loadTargets();

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
                    if (picking) copyFrame(img);
                    else findTargets(img);
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

    /** Builds the per-kind lookup tables from settings.targets. */
    private void loadTargets() {
        int n = settings.targets.size();
        int[] k = new int[n], idx = new int[n];
        int[] cs = new int[n];
        List<Template> ts = new ArrayList<>();
        int nc = 0, m = 0;
        for (String t : settings.targets) {
            if (Settings.isColor(t)) {
                k[m] = KIND_COLOR;
                idx[m++] = nc;
                cs[nc++] = Settings.color(t);
            } else {
                Template tpl = Template.load(this, Settings.imageName(t));
                if (tpl == null) continue;
                k[m] = KIND_IMAGE;
                idx[m++] = ts.size();
                ts.add(tpl);
            }
        }
        kind = Arrays.copyOf(k, m);
        index = Arrays.copyOf(idx, m);
        colors = Arrays.copyOf(cs, nc);
        templates = ts;

        cellCount = new int[nc * cells];
        cellSumX = new int[nc * cells];
        cellSumY = new int[nc * cells];
        colorFound = new boolean[nc];
        colorX = new float[nc];
        colorY = new float[nc];
        hits = new float[m * 2];
        hitCount = 0;
    }

    /** Re-reads settings saved by the app, so edits apply without restarting. */
    void reloadSettings() {
        handler.post(() -> {
            settings = Settings.load(this);
            loadTargets();
        });
    }

    void setPicking(boolean on) {
        handler.post(() -> {
            picking = on;
            frameValid = false;
            hitCount = 0;
        });
    }

    /** Adds the color at screen point (x, y) as the lowest-priority target, then resumes tapping. */
    void pickAt(float x, float y) {
        handler.post(() -> {
            // Grab the newest frame (the pick layer is gone by now or nearly so).
            grabFrame();
            int color = -1;
            boolean added = false;
            if (frameValid) {
                int cx = Math.max(0, Math.min(capW - 1, (int) (x / toScreenX)));
                int cy = Math.max(0, Math.min(capH - 1, (int) (y / toScreenY)));
                int i = (cy * capW + cx) * 4;
                color = ((frame[i] & 0xFF) << 16) | ((frame[i + 1] & 0xFF) << 8) | (frame[i + 2] & 0xFF);
                String t = Settings.colorTarget(color);
                added = !settings.targets.contains(t);
                if (added) addTarget(t);
            }
            picking = false;
            frameValid = false;
            TapService svc = TapService.instance;
            if (svc != null) svc.onPicked(color, added);
        });
    }

    /** Saves the screen region (screen pixels) as the lowest-priority target, then resumes tapping. */
    void pickImage(float left, float top, float right, float bottom) {
        // Wait for a frame without the selection rectangle drawn on it.
        handler.postDelayed(() -> {
            grabFrame();
            int x0 = Math.max(0, (int) (left / toScreenX));
            int y0 = Math.max(0, (int) (top / toScreenY));
            int x1 = Math.min(capW, (int) Math.ceil(right / toScreenX));
            int y1 = Math.min(capH, (int) Math.ceil(bottom / toScreenY));
            boolean ok = false;
            if (frameValid && x1 - x0 >= 4 && y1 - y0 >= 4) {
                try {
                    String name = Template.save(this, frame, capW, x0, y0, x1 - x0, y1 - y0);
                    addTarget(Settings.imageTarget(name));
                    ok = true;
                } catch (IOException ignored) {
                }
            }
            picking = false;
            frameValid = false;
            TapService svc = TapService.instance;
            if (svc != null) svc.onImagePicked(ok);
        }, 250);
    }

    private void addTarget(String t) {
        // Re-read first so priorities changed in the app since the last reload are kept.
        settings = Settings.load(this);
        settings.targets.add(t);
        settings.save(this);
        loadTargets();
    }

    private void grabFrame() {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            copyFrame(img);
        } finally {
            img.close();
        }
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

    /**
     * Fills hits with the found targets in priority order; stops at the first one unless tapAll.
     * The color pass and the frame copy are each done at most once, and only when needed.
     */
    private void findTargets(Image img) {
        hitCount = 0;
        boolean colorsDone = false, frameDone = false;
        for (int i = 0; i < kind.length; i++) {
            boolean found;
            if (kind[i] == KIND_COLOR) {
                if (!colorsDone) {
                    findColors(img);
                    colorsDone = true;
                }
                int j = index[i];
                found = colorFound[j];
                if (found) addHit(colorX[j], colorY[j]);
            } else {
                if (!frameDone) {
                    copyFrame(img);
                    frameDone = true;
                }
                found = matchTemplate(templates.get(index[i]));
            }
            if (found && !settings.tapAll) return;
        }
    }

    private void addHit(float x, float y) {
        hits[hitCount * 2] = x;
        hits[hitCount * 2 + 1] = y;
        hitCount++;
    }

    /**
     * Finds the position where the most sample points of t match (within tolerance) and adds its
     * centre to hits. Coarse pass on every 2nd position with early exit, then a 1px refinement.
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
        addHit((best[1] + t.w / 2f) * toScreenX, (best[2] + t.h / 2f) * toScreenY);
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

    /** One pass over the image: for each color, the centre of its densest patch (colorFound/X/Y). */
    private void findColors(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        int w = Math.min(img.getWidth(), capW), h = Math.min(img.getHeight(), capH);
        if (row == null || row.length < rowStride) row = new byte[rowStride];

        int k = colors.length;
        int[] tr = new int[k], tg = new int[k], tb = new int[k];
        for (int j = 0; j < k; j++) {
            tr[j] = (colors[j] >> 16) & 0xFF;
            tg[j] = (colors[j] >> 8) & 0xFF;
            tb[j] = colors[j] & 0xFF;
        }
        int tol = settings.tolerance;
        int cols = (capW + CELL - 1) / CELL;

        // Skip our own panel so it is never tapped.
        int exL = 0, exT = 0, exR = 0, exB = 0;
        TapService svc = TapService.instance;
        if (svc != null) {
            exL = (int) (svc.btnLeft / toScreenX);
            exT = (int) (svc.btnTop / toScreenY);
            exR = (int) Math.ceil(svc.btnRight / toScreenX);
            exB = (int) Math.ceil(svc.btnBottom / toScreenY);
        }

        Arrays.fill(cellCount, 0);
        Arrays.fill(cellSumX, 0);
        Arrays.fill(cellSumY, 0);

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
                    break; // a pixel counts for the highest-priority color it matches
                }
            }
        }

        int threshold = Math.max(1, settings.minHits) - 1;
        for (int j = 0; j < k; j++) {
            int best = -1, bestCount = threshold;
            for (int c = j * cells, end = c + cells; c < end; c++) {
                if (cellCount[c] > bestCount) {
                    bestCount = cellCount[c];
                    best = c;
                }
            }
            colorFound[j] = best >= 0;
            if (best >= 0) {
                colorX[j] = (cellSumX[best] / (float) bestCount + 0.5f) * toScreenX;
                colorY[j] = (cellSumY[best] / (float) bestCount + 0.5f) * toScreenY;
            }
        }
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
