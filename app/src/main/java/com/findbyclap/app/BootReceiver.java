package com.findbyclap.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android does not allow an app to switch the microphone on by itself after a restart.
 * So after a restart we only show a reminder; one tap brings listening back.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!context.getSharedPreferences(ListenService.PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) {
            return;
        }
        ListenService.postResumeReminder(context);
    }
}
