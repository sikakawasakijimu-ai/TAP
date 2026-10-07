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

    private int[] cellCount, cellSumX, cellSumY;
    private byte[] row;
    // Last hit in screen coordinates; reused when the screen has not changed since the last scan.
    private float lastX = -1, lastY = -1;

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
        cellCount = new int[cols * rows];
        cellSumX = new int[cols * rows];
        cellSumY = new int[cols * rows];

        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("ColorTap", capW, capH,
                Math.max(1, dm.densityDpi / SCALE),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);

        TapService svc = TapService.instance;
        if (svc != null) svc.showStopButton();

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
                    findTarget(img);
                } finally {
                    img.close();
                }
            }
            TapService svc = TapService.instance;
            if (svc != null && lastX >= 0) svc.tap(lastX, lastY);
            handler.postDelayed(this, settings.intervalMs);
        }
    };

    /** Sets lastX/lastY to the centre of the densest patch of matching color, or -1 if none. */
    private void findTarget(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        int w = Math.min(img.getWidth(), capW), h = Math.min(img.getHeight(), capH);
        if (row == null || row.length < rowStride) row = new byte[rowStride];

        int tr = (settings.color >> 16) & 0xFF;
        int tg = (settings.color >> 8) & 0xFF;
        int tb = settings.color & 0xFF;
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
                if (Math.abs((row[i] & 0xFF) - tr) > tol) continue;
                if (Math.abs((row[i + 1] & 0xFF) - tg) > tol) continue;
                if (Math.abs((row[i + 2] & 0xFF) - tb) > tol) continue;
                if (rowExcluded && x >= exL && x < exR) continue;
                int c = cellRow + x / CELL;
                cellCount[c]++;
                cellSumX[c] += x;
                cellSumY[c] += y;
            }
        }

        int best = -1, bestCount = Math.max(1, settings.minHits) - 1;
        for (int c = 0; c < cellCount.length; c++) {
            if (cellCount[c] > bestCount) {
                bestCount = cellCount[c];
                best = c;
            }
        }
        if (best < 0) {
            lastX = lastY = -1;
        } else {
            lastX = (cellSumX[best] / (float) bestCount + 0.5f) * toScreenX;
            lastY = (cellSumY[best] / (float) bestCount + 0.5f) * toScreenY;
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
        if (wasRunning && svc != null) svc.hideStopButton();
        super.onDestroy();
    }
}
