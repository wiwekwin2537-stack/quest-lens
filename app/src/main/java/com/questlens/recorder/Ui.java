package com.questlens.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xff101c25;
    static final int PANEL = 0xff1b2c38;
    static final int TEXT = 0xfff1f7f7;
    static final int MUTED = 0xffbecfd6;
    static final int ACCENT = 0xff7be4c2;
    static final int REC = 0xffff987e;

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        return result;
    }

    static LinearLayout column(Context context, int padding) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(context, padding);
        result.setPadding(inset, inset, inset, inset);
        return result;
    }

    static TextView text(Context context, String content, int size, int color, boolean bold) {
        TextView result = new TextView(context);
        result.setText(content);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setLineSpacing(dp(context, 3), 1);
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }

    static Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        Context context = parent.getContext();
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(15);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setTextColor(primary ? BG : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(context, primary ? ACCENT : 0xff304553, 11));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 7);
        parent.addView(button, params);
        return button;
    }

    static PendingIntent open(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 10, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification.Builder notification(Context context, String channel, String title, String detail) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                channel, title, NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_quest).setContentTitle(title).setContentText(detail)
                .setContentIntent(open(context)).setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
    }
}
