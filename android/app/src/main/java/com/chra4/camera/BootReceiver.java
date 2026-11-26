package com.chra4.camera;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/*
 کامێرا — after a phone restart (or an app update)
 Design & Development Hemn A.Muhammad

 Android does not let an app read GPS in the background by itself after a restart.
 If camera alerts were on, this shows one notification: a tap starts KameraService
 directly (no need to open the app), which Android allows because the person tapped it.
*/
public class BootReceiver extends BroadcastReceiver {

    static final int NOTE_ID = 78;
    private static final String CHANNEL = "kamera_resume";

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!KameraGuard.isEnabled(c)) return;
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.kamera_channel_resume), NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent svc = new Intent(c, KameraService.class).setAction(KameraService.ACTION_RESUME);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(c, 3, svc, fl)
                : PendingIntent.getService(c, 3, svc, fl);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_kamera)
                .setColor(0xFF16A34A)
                .setContentTitle(c.getString(R.string.kamera_resume_title))
                .setContentText(c.getString(R.string.kamera_resume_text))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(c.getString(R.string.kamera_resume_text)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(NOTE_ID, b.build());
        } catch (SecurityException ignored) { }
    }
}
