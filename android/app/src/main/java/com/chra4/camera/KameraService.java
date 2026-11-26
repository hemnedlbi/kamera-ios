package com.chra4.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/*
 کامێرا — foreground service owned by the app (not by the page)
 Design & Development Hemn A.Muhammad

 Keeps the process and background location alive while camera alerts are on, also after
 the app is swiped away from the recent-apps screen. It is started while the app is on
 screen (KameraGuard.startLocationIfAllowed) or when the person taps the "tap to turn
 alerts back on" notification after a restart (BootReceiver), which Android allows.
*/
public class KameraService extends Service {

    static final String ACTION_RESUME = "com.chra4.camera.RESUME";
    private static final String CHANNEL = "kamera_service";
    private static final int NOTE_ID = 77;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTE_ID, build(this));
        } catch (Exception e) {
            // not allowed right now (e.g. location permission missing): give up quietly
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            NotificationManagerCompat.from(this).cancel(BootReceiver.NOTE_ID);
            KameraGuard.get(this).resumeFromPrefs();
        }
        // if Android kills it, it must not restart from the background (no location there)
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static void start(Context c) {
        try {
            ContextCompat.startForegroundService(c, new Intent(c, KameraService.class));
        } catch (Exception ignored) {
            // started from the background: KameraGuard tries again when the app is on screen
        }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, KameraService.class)); } catch (Exception ignored) { }
    }

    private static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.kamera_channel_service), NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        ch.setShowBadge(false);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static Notification build(Context c) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_kamera)
                .setColor(0xFF16A34A)
                .setContentTitle(c.getString(R.string.kamera_service_title))
                .setContentText(c.getString(R.string.kamera_service_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build();
    }
}
