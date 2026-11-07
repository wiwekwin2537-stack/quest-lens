package com.questlens.recorder;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public final class OverlayService extends Service {
    private static OverlayService current;
    private WindowManager windows;
    private WindowManager.LayoutParams bubblePosition, panelPosition;
    private Button bubble;
    private LinearLayout panel;

    static boolean active() {
        return current != null;
    }

    static void refresh() {
        if (current != null) current.updateBubble();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (!Settings.canDrawOverlays(this) || (intent != null && "close".equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bubble != null) return START_NOT_STICKY;

        PendingIntent close = PendingIntent.getService(this, 20,
                new Intent(this, OverlayService.class).setAction("close"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(100, Ui.notification(this, "overlay", "Quest Lens · ปุ่มลอย",
                "แตะปุ่มลอยเพื่อเปิดแผงควบคุม").addAction(0, "ปิดปุ่มลอย", close).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        windows = getSystemService(WindowManager.class);
        try {
            attachBubble();
            current = this;
        } catch (RuntimeException error) {
            Toast.makeText(this, "เปิดปุ่มลอยไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private WindowManager.LayoutParams position(int width, int height, int extraFlags) {
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | extraFlags, PixelFormat.TRANSLUCENT);
        result.gravity = Gravity.TOP | Gravity.LEFT;
        return result;
    }

    private void attachBubble() {
        int size = Ui.dp(this, 54);
        bubblePosition = position(size, size, 0);
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        bubblePosition.x = bounds.width() - size - Ui.dp(this, 14);
        bubblePosition.y = Ui.dp(this, 76);
        constrain(bubblePosition);
        bubble = new Button(this);
        bubble.setMinWidth(0);
        bubble.setMinimumWidth(0);
        bubble.setPadding(0, 0, 0, 0);
        bubble.setGravity(Gravity.CENTER);
        bubble.setAllCaps(false);
        bubble.setTextSize(17);
        bubble.setOnClickListener(view -> {
            if (panel == null) showPanel();
            else hidePanel();
        });
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubblePosition.x;
                        startY = bubblePosition.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float x = event.getRawX() - downX;
                        float y = event.getRawY() - downY;
                        moved |= Math.hypot(x, y) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                        if (moved) {
                            hidePanel();
                            bubblePosition.x = startX + Math.round(x);
                            bubblePosition.y = startY + Math.round(y);
                            constrain(bubblePosition);
                            windows.updateViewLayout(bubble, bubblePosition);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });
        updateBubble();
        windows.addView(bubble, bubblePosition);
    }

    private void constrain(WindowManager.LayoutParams position) {
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        position.x = Math.max(0, Math.min(position.x, bounds.width() - position.width));
        position.y = Math.max(0, Math.min(position.y, bounds.height() - position.height - Ui.dp(this, 48)));
    }

    private void updateBubble() {
        boolean recording = CaptureService.running();
        bubble.setText(recording ? "●" : "QL");
        bubble.setTextColor(Ui.BG);
        bubble.setBackground(Ui.shape(this, recording ? Ui.REC : Ui.ACCENT, 28));
        bubble.setContentDescription(recording ? "กำลังบันทึก เปิดแผง Quest Lens" : "เปิดแผง Quest Lens");
        hidePanel();
    }

    private void showPanel() {
        panel = Ui.column(this, 14);
        panel.setBackground(Ui.shape(this, Ui.PANEL, 16));
        panel.addView(Ui.text(this, "Quest Lens", 18, Ui.ACCENT, true));
        if (CaptureService.running()) {
            panel.addView(Ui.text(this, "กำลังบันทึก", 14, Ui.REC, false));
            Ui.button(panel, "ทำเครื่องหมายหน้าเควส", true, () -> {
                CaptureService.markCurrent();
                hidePanel();
            });
            Ui.button(panel, "หยุดและบันทึกไฟล์", false, CaptureService::stopCurrent);
        } else if (CaptureService.busy()) {
            panel.addView(Ui.text(this, "กำลังเตรียมไฟล์", 14, Ui.MUTED, false));
        } else {
            Ui.button(panel, "เริ่มบันทึก", true, () -> openMain(MainActivity.OPEN_CONSENT));
            Ui.button(panel, "ดูและแชร์ไฟล์", false, () -> openMain(null));
        }
        Ui.button(panel, "ปิดแผง", false, this::hidePanel);
        Ui.button(panel, "ปิดปุ่มลอย", false, this::stopSelf);
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        int width = Math.min(Ui.dp(this, 248), bounds.width() - Ui.dp(this, 16));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height() - Ui.dp(this, 64), View.MeasureSpec.AT_MOST));
        panelPosition = position(width, panel.getMeasuredHeight(), 0);
        panelPosition.x = bubblePosition.x > width
                ? bubblePosition.x - width - Ui.dp(this, 7)
                : bubblePosition.x + bubblePosition.width + Ui.dp(this, 7);
        panelPosition.y = bubblePosition.y;
        constrain(panelPosition);
        windows.addView(panel, panelPosition);
    }

    private void openMain(String action) {
        hidePanel();
        startActivity(new Intent(this, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private void hidePanel() {
        if (panel != null) {
            if (panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
            panel = null;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (bubble != null && bubble.isAttachedToWindow()) {
            hidePanel();
            constrain(bubblePosition);
            windows.updateViewLayout(bubble, bubblePosition);
        }
    }

    @Override public void onDestroy() {
        current = null;
        hidePanel();
        if (bubble != null && bubble.isAttachedToWindow()) windows.removeViewImmediate(bubble);
        super.onDestroy();
    }
}
