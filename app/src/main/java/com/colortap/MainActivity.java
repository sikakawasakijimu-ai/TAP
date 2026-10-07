package com.colortap;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;

    private TextView status, toleranceLabel;
    private EditText color, interval, minHits;
    private SeekBar tolerance;
    private View preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        toleranceLabel = findViewById(R.id.toleranceLabel);
        color = findViewById(R.id.color);
        interval = findViewById(R.id.interval);
        minHits = findViewById(R.id.minHits);
        tolerance = findViewById(R.id.tolerance);
        preview = findViewById(R.id.preview);

        com.colortap.Settings s = com.colortap.Settings.load(this);
        color.setText(String.format("#%06X", s.color));
        tolerance.setProgress(s.tolerance);
        interval.setText(String.valueOf(s.intervalMs));
        minHits.setText(String.valueOf(s.minHits));
        updateToleranceLabel();
        updatePreview();

        color.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(Editable e) { updatePreview(); }
        });
        tolerance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { updateToleranceLabel(); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });

        findViewById(R.id.openA11y).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.start).setOnClickListener(v -> start());
        findViewById(R.id.stop).setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
            status.postDelayed(this::updateStatus, 300);
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        String a11y = TapService.instance != null ? "有効 ✔" : "無効 (①を押して有効にしてください)";
        String run = CaptureService.running ? "動作中" : "停止中";
        status.setText("ユーザー補助: " + a11y + "\n状態: " + run);
    }

    private void updateToleranceLabel() {
        toleranceLabel.setText("色の許容範囲: " + tolerance.getProgress()
                + " (大きいほど似た色も対象)");
    }

    private void updatePreview() {
        Integer c = parseColor();
        preview.setBackgroundColor(c == null ? Color.TRANSPARENT : 0xFF000000 | c);
    }

    private Integer parseColor() {
        String t = color.getText().toString().trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return null;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void start() {
        Integer c = parseColor();
        if (c == null) {
            Toast.makeText(this, "色は #RRGGBB の形式で入力してください", Toast.LENGTH_LONG).show();
            return;
        }
        if (TapService.instance == null) {
            Toast.makeText(this, "先にユーザー補助で ColorTap を有効にしてください", Toast.LENGTH_LONG).show();
            return;
        }
        if (CaptureService.running) {
            Toast.makeText(this, "すでに動作中です", Toast.LENGTH_SHORT).show();
            return;
        }
        com.colortap.Settings s = new com.colortap.Settings();
        s.color = c;
        s.tolerance = tolerance.getProgress();
        s.intervalMs = parseInt(interval, 300, 50, 60_000);
        s.minHits = parseInt(minHits, 3, 1, 100);
        s.save(this);

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent i = Build.VERSION.SDK_INT >= 34
                // Force whole-screen capture so screen coordinates map 1:1.
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "画面キャプチャが許可されませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        startForegroundService(new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data));
        // Get out of the way so the target app is on screen.
        moveTaskToBack(true);
    }
}
