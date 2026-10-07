package com.colortap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;

    private TextView status, toleranceLabel;
    private EditText color, interval, minHits, matchPct;
    private LinearLayout templates;
    private SeekBar tolerance;
    private LinearLayout preview;
    private CheckBox tapEachColor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        toleranceLabel = findViewById(R.id.toleranceLabel);
        color = findViewById(R.id.color);
        interval = findViewById(R.id.interval);
        minHits = findViewById(R.id.minHits);
        matchPct = findViewById(R.id.matchPct);
        templates = findViewById(R.id.templates);
        tolerance = findViewById(R.id.tolerance);
        preview = findViewById(R.id.preview);
        tapEachColor = findViewById(R.id.tapEachColor);

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
        // Reload: colors may have been added from the screen while we were away.
        com.colortap.Settings s = com.colortap.Settings.load(this);
        color.setText(com.colortap.Settings.formatColors(s.colors));
        tapEachColor.setChecked(s.tapEachColor);
        tolerance.setProgress(s.tolerance);
        interval.setText(String.valueOf(s.intervalMs));
        minHits.setText(String.valueOf(s.minHits));
        matchPct.setText(String.valueOf(s.matchPct));
        updateTemplates();
        updateToleranceLabel();
        updatePreview();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save edits and apply them to a running session right away.
        if (saveSettings() != null) {
            CaptureService cs = CaptureService.instance;
            if (cs != null) cs.reloadSettings();
        }
    }

    /** Saves the form; returns null (and saves nothing) if the color list is invalid. */
    private com.colortap.Settings saveSettings() {
        int[] c = parseColors();
        if (c == null) return null;
        com.colortap.Settings s = new com.colortap.Settings();
        s.colors = c;
        s.tapEachColor = tapEachColor.isChecked();
        s.tolerance = tolerance.getProgress();
        s.intervalMs = parseInt(interval, 300, 50, 60_000);
        s.minHits = parseInt(minHits, 3, 1, 100);
        s.matchPct = parseInt(matchPct, 85, 1, 100);
        s.save(this);
        return s;
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
        preview.removeAllViews();
        int[] colors = parseColors();
        if (colors == null) return;
        int size = (int) (32 * getResources().getDisplayMetrics().density);
        for (int c : colors) {
            View v = new View(this);
            v.setBackgroundColor(0xFF000000 | c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(size / 4);
            preview.addView(v, lp);
        }
    }

    private void updateTemplates() {
        templates.removeAllViews();
        int size = (int) (56 * getResources().getDisplayMetrics().density);
        for (Template t : Template.loadAll(this)) {
            ImageView v = new ImageView(this);
            v.setImageBitmap(t.toBitmap());
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setOnClickListener(x -> new AlertDialog.Builder(this)
                    .setMessage("この画像を削除しますか？")
                    .setPositiveButton("削除", (dlg, w) -> {
                        t.file.delete();
                        CaptureService cs = CaptureService.instance;
                        if (cs != null) cs.reloadSettings();
                        updateTemplates();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(size / 6);
            templates.addView(v, lp);
        }
    }

    private int[] parseColors() {
        return com.colortap.Settings.parseColors(color.getText().toString().trim());
    }

    private static int parseInt(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void start() {
        com.colortap.Settings s = saveSettings();
        if (s == null) {
            Toast.makeText(this, "色は #RRGGBB の形式で入力してください (複数はカンマ区切り)", Toast.LENGTH_LONG).show();
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
        if (s.colors.length == 0 && Template.loadAll(this).isEmpty()) {
            Toast.makeText(this, "画面の「＋色」「＋画像」で対象を追加してください", Toast.LENGTH_LONG).show();
        }

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
