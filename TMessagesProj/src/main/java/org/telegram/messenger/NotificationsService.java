/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import xyz.nextalone.nagram.NaConfig;

public class NotificationsService extends Service {

    private static volatile boolean isRunning;

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        ApplicationLoader.postInitApplication();
        // Always run as a foreground service so the OS (and OEM battery killers) do not
        // silently kill the push connection while the app is in background.
        String CHANNEL_ID = "push_service_channel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Low importance keeps the notification subtle (no sound, no badge) while still
        // protecting the service from being killed.
        boolean inAppDialog = NaConfig.INSTANCE.getPushServiceTypeInAppDialog().Bool();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.NagramXPushService), inAppDialog ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
//            Intent explainIntent = new Intent("android.intent.action.VIEW");
//            explainIntent.setData(Uri.parse("https://github.com/Telegram-FOSS-Team/Telegram-FOSS/blob/master/Notifications.md"));
//            PendingIntent explainPendingIntent = PendingIntent.getActivity(this, 0, explainIntent, 0);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
//                    .setContentIntent(explainPendingIntent)
                .setShowWhen(false)
                .setOngoing(true)
                .setSmallIcon(R.drawable.neko_notification)
                .setContentText(LocaleController.getString(R.string.NagramXPushService))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(9999, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(9999, notification);
            }
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to start push service");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every time this service is (re)started - by the :push process, by the
        // guard alarm, by boot - force tgnet to resume/reconnect. After a long
        // background period the socket is often frozen while tgnet still thinks
        // it is connected, which silently kills push delivery.
        ApplicationLoader.resumeConnections();
        // The guard alarm is one-shot; re-arm it so the health check chain
        // keeps going even if the :push process was not started.
        ApplicationLoader.schedulePushServiceRestart();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Tombstone: user swiped the app away. Re-arm the resurrection alarm immediately
        // instead of waiting for the next periodic check, so the push service keeps coming
        // back even on ROMs that cancel alarms when the task is removed.
        ApplicationLoader.schedulePushServiceRestart();
    }

    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            try {
                sendBroadcast(intent);
            } catch (Exception ex) {
                // 辣鷄miui 就你事最多.jpg
            }
        }
    }
}
