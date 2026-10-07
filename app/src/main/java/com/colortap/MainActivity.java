package com.colortap;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;

    private TextView status, toleranceLabel;
    private EditText colorInput, interval, minHits, matchPct;
    private LinearLayout targets;
    private SeekBar tolerance;
    private CheckBox tapAll;
    /** Loaded in onResume; the target list is edited in place and saved immediately. */
    private com.colortap.Settings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        toleranceLabel = findViewById(R.id.toleranceLabel);
        colorInput = findViewById(R.id.colorInput);
        interval = findViewById(R.id.interval);
        minHits = findViewById(R.id.minHits);
        matchPct = findViewById(R.id.matchPct);
        targets = findViewById(R.id.targets);
        tolerance = findViewById(R.id.tolerance);
        tapAll = findViewById(R.id.tapAll);

        tolerance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { updateToleranceLabel(); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });

        findViewById(R.id.openA11y).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.addColor).setOnClickListener(v -> addColor());
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
        // Reload: targets may have been added from the screen while we were away.
        settings = com.colortap.Settings.load(this);
        tapAll.setChecked(settings.tapAll);
        tolerance.setProgress(settings.tolerance);
        interval.setText(String.valueOf(settings.intervalMs));
        minHits.setText(String.valueOf(settings.minHits));
        matchPct.setText(String.valueOf(settings.matchPct));
        updateTargets();
        updateToleranceLabel();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    /** Saves the form and applies it to a running session right away. */
    private void saveSettings() {
        settings.tapAll = tapAll.isChecked();
        settings.tolerance = tolerance.getProgress();
        settings.intervalMs = parseInt(interval, 300, 50, 60_000);
        settings.minHits = parseInt(minHits, 3, 1, 100);
        settings.matchPct = parseInt(matchPct, 85, 1, 100);
        settings.save(this);
        CaptureService cs = CaptureService.instance;
        if (cs != null) cs.reloadSettings();
    }

    private void updateStatus() {
        String a11y = TapService.instance != null ? "有効 ✔" : "無効 (①を押して有効にしてください)";
        String run = CaptureService.running ? "動作中" : "停止中";
        status.setText("ユーザー補助: " + a11y + "\n状態: " + run);
    }

    private void updateToleranceLabel() {
        toleranceLabel.setText("色の許容範囲: " + tolerance.getProgress()
                + " (大きいほど似た色も対象。画像の判定にも使用)");
    }

    private void addColor() {
        int rgb = com.colortap.Settings.parseColor(colorInput.getText().toString());
        if (rgb < 0) {
            Toast.makeText(this, "色は #RRGGBB の形式で入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        String t = com.colortap.Settings.colorTarget(rgb);
        if (settings.targets.contains(t)) {
            Toast.makeText(this, "登録済みです", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.targets.add(t);
        colorInput.setText("");
        saveSettings();
        updateTargets();
    }

    /** Rebuilds the target rows: [swatch or image] [label] [↑] [↓] [✕]. */
    private void updateTargets() {
        targets.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int thumb = (int) (40 * d);
        if (settings.targets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("(まだありません)");
            targets.addView(empty);
            return;
        }
        for (int i = 0; i < settings.targets.size(); i++) {
            final int pos = i;
            String t = settings.targets.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView num = new TextView(this);
            num.setText((i + 1) + ".");
            num.setMinWidth((int) (24 * d));
            row.addView(num);

            View icon;
            String label;
            if (com.colortap.Settings.isColor(t)) {
                icon = new View(this);
                icon.setBackgroundColor(0xFF000000 | com.colortap.Settings.color(t));
                label = "色 #" + t.substring(2);
            } else {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Template tpl = Template.load(this, com.colortap.Settings.imageName(t));
                if (tpl != null) iv.setImageBitmap(tpl.toBitmap());
                icon = iv;
                label = tpl != null ? "画像" : "画像 (読み込めません)";
            }
            row.addView(icon, new LinearLayout.LayoutParams(thumb, thumb));

            TextView name = new TextView(this);
            name.setText(label);
            name.setPadding((int) (8 * d), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.addView(smallButton("↑", pos > 0, v -> move(pos, pos - 1)));
            row.addView(smallButton("↓", pos < settings.targets.size() - 1, v -> move(pos, pos + 1)));
            row.addView(smallButton("✕", true, v -> remove(pos)));
            targets.addView(row);
        }
    }

    private Button smallButton(String text, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setEnabled(enabled);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private void move(int from, int to) {
        Collections.swap(settings.targets, from, to);
        saveSettings();
        updateTargets();
    }

    private void remove(int pos) {
        String t = settings.targets.remove(pos);
        if (!com.colortap.Settings.isColor(t)) {
            new java.io.File(Template.dir(this), com.colortap.Settings.imageName(t)).delete();
        }
        saveSettings();
        updateTargets();
    }

    private static int parseInt(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void start() {
        if (TapService.instance == null) {
            Toast.makeText(this, "先にユーザー補助で ColorTap を有効にしてください", Toast.LENGTH_LONG).show();
            return;
        }
        if (CaptureService.running) {
            Toast.makeText(this, "すでに動作中です", Toast.LENGTH_SHORT).show();
            return;
        }
        saveSettings();
        if (settings.targets.isEmpty()) {
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
