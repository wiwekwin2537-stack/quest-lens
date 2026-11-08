package com.questlens.recorder;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class CaptureService extends Service {
    static final String CONSENT = "consent";
    static final String LANDSCAPE = "landscape";
    private static final String STOP = "stop";
    private static final long LIMIT_MS = 90_000;
    private static CaptureService current;
    private static String resultMessage;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray bookmarks = new JSONArray();
    private final JSONArray captureSizes = new JSONArray();
    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay display;
    private File staging;
    private String stem;
    private boolean recording, finishing, destroying;
    private boolean captureVisible = true;
    private long startedAt;
    private int width, height;

    private final Runnable timeout = () -> finish("time_limit");
    private final MediaProjection.Callback callbacks = new MediaProjection.Callback() {
        @Override public void onStop() {
            finish("android_stopped");
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            if (!recording || captureVisible == visible) return;
            captureVisible = visible;
            OverlayService.refresh();
        }

        @Override public void onCapturedContentResize(int contentWidth, int contentHeight) {
            if (!recording) return;
            JSONObject size = new JSONObject();
            try {
                size.put("at_ms", elapsed());
                size.put("width", contentWidth);
                size.put("height", contentHeight);
                captureSizes.put(size);
            } catch (JSONException error) {
                Log.w("QuestLens", "Could not log capture dimensions", error);
            }
        }
    };

    static boolean busy() {
        return current != null;
    }

    static boolean running() {
        return current != null && current.recording;
    }

    static boolean contentHidden() {
        return running() && !current.captureVisible;
    }

    static void stopCurrent() {
        if (running()) current.finish("user_stopped");
    }

    static void markCurrent() {
        if (!running()) return;
        if (contentHidden()) {
            Toast.makeText(current, "เปิดแอปที่เลือกบันทึกก่อนทำเครื่องหมาย", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject marker = new JSONObject();
        try {
            long time = current.elapsed();
            marker.put("kind", "quest_page");
            marker.put("at_ms", time);
            marker.put("suggested_review_ms", Math.max(0, time - 1_500));
            current.bookmarks.put(marker);
        } catch (JSONException error) {
            Log.w("QuestLens", "Could not add bookmark", error);
        }
    }

    static String status(boolean saved) {
        if (running()) {
            if (contentHidden()) return "⚠ มองไม่เห็นแอปที่เลือกบันทึก";
            long remaining = Math.max(0, LIMIT_MS - current.elapsed() + 999) / 1000;
            return "● กำลังบันทึก · เหลือ " + remaining + " วินาที";
        }
        if (busy()) return "กำลังเตรียมไฟล์";
        if (resultMessage != null) return resultMessage;
        return saved ? "มีชุดสาธิตพร้อมแชร์" : "พร้อมบันทึก";
    }

    static void clearResult() {
        resultMessage = null;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            if (recording) finish("user_stopped");
            else if (current != this) stopSelf();
            return START_NOT_STICKY;
        }
        if (current == this) return START_NOT_STICKY;
        Intent consent = intent == null ? null : intent.getParcelableExtra(CONSENT, Intent.class);
        if (consent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        current = this;
        resultMessage = null;
        PendingIntent stop = PendingIntent.getService(this, 21,
                new Intent(this, CaptureService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        try {
            startForeground(101, Ui.notification(this, "capture", "Quest Lens · กำลังบันทึกหน้าจอ",
                    "บันทึกวิดีโอแบบไม่มีเสียง · สูงสุด 90 วินาที").addAction(0, "หยุดบันทึก", stop).build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            begin(consent, intent.getBooleanExtra(LANDSCAPE, true));
        } catch (RuntimeException | IOException error) {
            Log.w("QuestLens", "Screen capture could not start", error);
            releaseCapture(false);
            if (staging != null) staging.delete();
            complete("เริ่มบันทึกไม่สำเร็จ ลองเลือกหน้าจอใหม่");
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent consent, boolean landscape) throws IOException {
        File folder = Exports.directory(this);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create session directory");
        Exports.cleanStaging(folder);
        if (folder.getUsableSpace() < 100L * 1024 * 1024) throw new IOException("Insufficient storage");
        stem = "quest-demo-" + UUID.randomUUID();
        staging = new File(folder, stem + ".mp4.partial");

        Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        int longSide = Math.max(bounds.width(), bounds.height());
        int shortSide = Math.min(bounds.width(), bounds.height());
        double scale = Math.min(1, Math.min(1600.0 / longSide, 896.0 / shortSide));
        int longPixels = Math.max(16, (int) (longSide * scale) / 16 * 16);
        int shortPixels = Math.max(16, (int) (shortSide * scale) / 16 * 16);
        width = landscape ? longPixels : shortPixels;
        height = landscape ? shortPixels : longPixels;
        int density = getResources().getConfiguration().densityDpi;

        recorder = new MediaRecorder(this);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(staging.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(3_000_000);
        recorder.setOnErrorListener((source, error, detail) -> finish("encoder_error"));
        recorder.prepare();

        projection = getSystemService(MediaProjectionManager.class)
                .getMediaProjection(Activity.RESULT_OK, consent);
        if (projection == null) throw new IOException("Projection denied");
        projection.registerCallback(callbacks, main);
        display = projection.createVirtualDisplay("Quest Lens", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, main);
        if (display == null) throw new IOException("No capture display");
        startedAt = SystemClock.elapsedRealtime();
        recorder.start();
        recording = true;
        OverlayService.refresh();
        main.postDelayed(timeout, LIMIT_MS);
    }

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    private void finish(String reason) {
        if (!recording || finishing) return;
        finishing = true;
        recording = false;
        OverlayService.refresh();
        main.removeCallbacks(timeout);
        long duration = elapsed();
        if (!releaseCapture(true)) {
            staging.delete();
            complete("คลิปสั้นเกินไปหรือระบบบันทึกไม่สำเร็จ");
            return;
        }

        new Thread(() -> {
            try {
                long videoDuration;
                try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                    metadata.setDataSource(staging.getAbsolutePath());
                    if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                        throw new IOException("Missing video track");
                    }
                    videoDuration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                }
                if (!CaptureTiming.isComplete(duration, videoDuration)) {
                    staging.delete();
                    main.post(() -> complete("ภาพขาดช่วง · ไม่มีคลิปใหม่ ลองเลือกแอปเกมหรือโหมดทั้งหน้าจอ"));
                    return;
                }

                JSONObject session = new JSONObject()
                        .put("schema_version", 1)
                        .put("video", "recording.mp4")
                        .put("video_width", width)
                        .put("video_height", height)
                        .put("video_duration_ms", videoDuration)
                        .put("elapsed_ms", duration)
                        .put("stopped_by", reason)
                        .put("bookmarks", bookmarks)
                        .put("capture_sizes", captureSizes)
                        .put("touch_data", "visual markers only, if visible in video");
                Exports.publish(staging, stem, session.toString(2));
                main.post(() -> complete("บันทึกเสร็จแล้ว · พร้อมแชร์ชุดสาธิต"));
            } catch (Exception error) {
                Log.w("QuestLens", "Could not export demonstration", error);
                staging.delete();
                main.post(() -> complete("บันทึกไฟล์ไม่สำเร็จ ลองอัดใหม่"));
            }
        }, "quest-export").start();
    }

    private boolean releaseCapture(boolean finalizeVideo) {
        boolean valid = false;
        if (recorder != null && finalizeVideo) {
            try {
                recorder.stop();
                valid = true;
            } catch (RuntimeException error) {
                Log.w("QuestLens", "Could not finalize recording", error);
            }
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (projection != null) {
            projection.unregisterCallback(callbacks);
            projection.stop();
            projection = null;
        }
        return valid;
    }

    private void complete(String message) {
        if (current != this || destroying) return;
        resultMessage = message;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    @Override public void onDestroy() {
        destroying = true;
        main.removeCallbacks(timeout);
        if (recording) {
            recording = false;
            releaseCapture(true);
            if (staging != null) staging.delete();
        }
        if (current == this) current = null;
        OverlayService.refresh();
        super.onDestroy();
    }
}
