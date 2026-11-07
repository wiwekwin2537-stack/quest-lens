package com.questlens.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public final class MainActivity extends Activity {
    static final String OPEN_CONSENT = "com.questlens.recorder.OPEN_CONSENT";
    private static final int CAPTURE_REQUEST = 41;
    private static final int SAVE_REQUEST = 43;
    private TextView status;
    private Button overlayButton, start, share, save, preview, delete;
    private CheckBox landscape;
    private boolean asking;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        asking = saved != null && saved.getBoolean("asking");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this, 22);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(scroll);
        root.addView(Ui.text(this, "QUEST LENS  /  01", 13, Ui.ACCENT, true));
        root.addView(Ui.text(this, "บันทึกการเล่นเควส", 30, Ui.TEXT, true));
        root.addView(Ui.text(this, "เก็บวิดีโอและจุดสำคัญไว้ในเครื่อง แล้วเลือกไฟล์ที่ต้องการส่งเอง", 16, Ui.MUTED, false));

        status = Ui.text(this, "", 16, Ui.ACCENT, true);
        status.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 5));
        root.addView(status);
        overlayButton = Ui.button(root, "", false, this::toggleOverlay);
        landscape = new CheckBox(this);
        landscape.setText("เกมแนวนอน");
        landscape.setTextColor(Ui.TEXT);
        landscape.setChecked(getPreferences(MODE_PRIVATE).getBoolean("landscape", true));
        landscape.setOnCheckedChangeListener((view, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("landscape", checked).apply());
        root.addView(landscape);
        start = Ui.button(root, "", true, () -> {
            if (CaptureService.running()) CaptureService.stopCurrent();
            else requestCapture();
        });
        preview = Ui.button(root, "เปิดดูคลิปล่าสุด", false, () -> openExport(false));
        share = Ui.button(root, "แชร์ชุดสาธิต ZIP", true, () -> openExport(true));
        save = Ui.button(root, "บันทึก ZIP ลงเครื่อง", false, this::chooseSaveLocation);
        delete = Ui.button(root, "ลบชุดสาธิตล่าสุด", false, () ->
                new android.app.AlertDialog.Builder(this).setMessage("ลบคลิปและไฟล์ ZIP ในแอป?")
                        .setNegativeButton("ยกเลิก", null).setPositiveButton("ลบ", (dialog, which) -> {
                            Exports.deleteSaved(this);
                            CaptureService.clearResult();
                            refresh();
                        }).show());
        root.addView(Ui.text(this,
                "\nเลือกแชร์เฉพาะแอปเกมเพื่อเก็บเฉพาะเกม ถ้าต้องการเห็นจุดแตะ ให้ลองเปิด Show taps แล้วทดสอบคลิปสั้นก่อน จุดแตะอาจเห็นได้เฉพาะโหมดทั้งหน้าจอในบางเครื่อง",
                14, Ui.MUTED, false));
        root.addView(Ui.text(this, "แตะปุ่มลอยเพื่อเปิดแผง กด “ทำเครื่องหมาย” ขณะเห็นหน้าที่ต้องการตรวจ บันทึกได้รอบละ 90 วินาที", 14, Ui.MUTED, false));
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
        status.postDelayed(poll, 350);
        handleOpenIntent();
    }

    @Override public void onPause() {
        status.removeCallbacks(poll);
        super.onPause();
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (hasWindowFocus()) handleOpenIntent();
    }

    @Override public void onSaveInstanceState(Bundle out) {
        out.putBoolean("asking", asking);
        super.onSaveInstanceState(out);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            status.postDelayed(this, 350);
        }
    };

    private void handleOpenIntent() {
        if (OPEN_CONSENT.equals(getIntent().getAction())) {
            setIntent(new Intent(this, MainActivity.class));
            requestCapture();
        }
    }

    private void toggleOverlay() {
        if (OverlayService.active()) {
            stopService(new Intent(this, OverlayService.class));
        } else if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "เปิดสิทธิ์แสดงทับแอป แล้วกลับมากดเปิดปุ่มลอยอีกครั้ง", Toast.LENGTH_LONG).show();
        } else if (notificationsAllowed()) {
            startForegroundService(new Intent(this, OverlayService.class));
        }
    }

    private boolean notificationsAllowed() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            return true;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
        Toast.makeText(this, "เปิดการแจ้งเตือนเพื่อให้มีปุ่มหยุด แล้วกดใหม่", Toast.LENGTH_LONG).show();
        return false;
    }

    private void requestCapture() {
        if (asking || CaptureService.busy() || !notificationsAllowed()) return;
        asking = true;
        startActivityForResult(getSystemService(MediaProjectionManager.class)
                .createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_REQUEST) {
            if (result == RESULT_OK && data != null && data.getData() != null) saveArchive(data.getData());
            return;
        }
        if (request != CAPTURE_REQUEST) return;
        asking = false;
        if (result == RESULT_OK && data != null && !CaptureService.busy()) {
            startForegroundService(new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.CONSENT, data)
                    .putExtra(CaptureService.LANDSCAPE, landscape.isChecked()));
            moveTaskToBack(true);
        }
    }

    private void refresh() {
        File archive = Exports.latest(this, "zip");
        boolean busy = CaptureService.busy();
        status.setText(CaptureService.status(archive != null));
        overlayButton.setText(OverlayService.active() ? "ปิดปุ่มลอย" : "เปิดปุ่มลอย");
        start.setText(CaptureService.running() ? "หยุดบันทึก" : "เลือกหน้าจอและเริ่มบันทึก");
        start.setEnabled(!busy || CaptureService.running());
        landscape.setEnabled(!busy);
        preview.setEnabled(!busy && archive != null);
        share.setEnabled(!busy && archive != null);
        save.setEnabled(!busy && archive != null);
        delete.setEnabled(!busy && archive != null);
    }

    private void chooseSaveLocation() {
        File archive = Exports.latest(this, "zip");
        if (archive == null) return;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, archive.getName()), SAVE_REQUEST);
    }

    private void saveArchive(Uri destination) {
        File archive = Exports.latest(this, "zip");
        if (archive == null) return;
        new Thread(() -> {
            String message;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new java.io.IOException("Destination unavailable");
                java.nio.file.Files.copy(archive.toPath(), output);
                message = "บันทึก ZIP ลงเครื่องแล้ว";
            } catch (java.io.IOException | SecurityException error) {
                message = "บันทึก ZIP ไม่สำเร็จ ลองเลือกที่เก็บไฟล์ใหม่";
            }
            String toast = message;
            runOnUiThread(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
        }, "quest-save-copy").start();
    }

    private void openExport(boolean zip) {
        File file = Exports.latest(this, zip ? "zip" : "mp4");
        if (file == null) return;
        Uri uri = ExportProvider.uri(file);
        Intent intent = zip
                ? new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
        intent.setClipData(ClipData.newRawUri(file.getName(), uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, zip ? "ส่งไฟล์สาธิต" : "เปิดคลิป"));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "ไม่พบแอปสำหรับเปิดไฟล์นี้", Toast.LENGTH_SHORT).show();
        }
    }
}
