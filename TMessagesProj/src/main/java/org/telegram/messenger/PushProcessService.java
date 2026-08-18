/*
 * Tombstone-proof push process.
 *
 * Runs in a separate `:push` process so that it survives the main process
 * being killed by the OS (OEM battery killers, low memory, user swipe).
 *
 * It does NOT initialize tgnet/SQLite (that stays in the main process where
 * the connection and the database live - running two instances would corrupt
 * the DB and create a duplicate session). Instead it:
 *   1. stays alive as a foreground service (system keeps it in a separate
 *      process that is not affected by the main process's death),
 *   2. binds to the main process via PushBridgeService,
 *   3. the moment the main process dies (bind disconnects), it wakes the
 *      main process back up immediately - so the push connection is rebuilt
 *      within seconds instead of waiting for the 15-minute guard alarm.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class PushProcessService extends Service {

    private static final String CHANNEL_ID = "push_process_channel";
    private static final int NOTIFICATION_ID = 9998;
    private static final long REBIND_DELAY = 2000L;

    private static volatile boolean isRunning;
    private static volatile boolean bound;

    public static boolean isRunning() {
        return isRunning;
    }

    private final ServiceConnection bridgeConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // main process is alive and bound - nothing to do here
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // main process died (or is starting). Wake it up right away
            // instead of waiting for the periodic alarm.
            Log.d("TFOSS", "Main process died, waking it up...");
            bound = false;
            wakeUpMainProcess();
            scheduleRebind();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        startAsForeground();
        bindToMainProcess();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Alarm / sticky restart. Always wake the main process: its
        // onStartCommand calls resumeConnections(), which is the connection
        // health check that fixes frozen sockets.
        // NOTE: this does NOT re-arm the alarm - the one-shot resurrection
        // alarm is only armed on real process death (onTaskRemoved/onDestroy),
        // so under Cirno freezing there are no periodic wake-ups at all.
        if (!isRunning) {
            isRunning = true;
        }
        startAsForeground();
        wakeUpMainProcess();
        if (!bound) {
            bindToMainProcess();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // re-arm the guard alarm right now; the service keeps running anyway
        ApplicationLoader.schedulePushServiceRestart();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (bound) {
            try {
                unbindService(bridgeConnection);
            } catch (Throwable ignore) {
            }
            bound = false;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
    }

    private void startAsForeground() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.NagramXPushService), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setShowWhen(false)
                .setOngoing(true)
                .setSmallIcon(R.drawable.neko_notification)
                .setContentText(LocaleController.getString(R.string.NagramXPushService))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to start push process foreground");
        }
    }

    private void bindToMainProcess() {
        try {
            Intent intent = new Intent(this, PushBridgeService.class);
            bound = true;
            bindService(intent, bridgeConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to bind to main process");
            bound = false;
            scheduleRebind();
        }
    }

    private void scheduleRebind() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!isRunning || bound) {
                return;
            }
            try {
                Intent intent = new Intent(this, PushBridgeService.class);
                bound = true;
                bindService(intent, bridgeConnection, Context.BIND_AUTO_CREATE);
            } catch (Throwable ignore) {
                bound = false;
            }
        }, REBIND_DELAY);
    }

    private void wakeUpMainProcess() {
        // Start a component in the MAIN process: rebuilding the push connection
        // happens in NotificationsService.onCreate -> postInitApplication.
        // Note: NotificationsService.isRunning() is process-local; from the
        // :push process it always reads false. That is fine - starting an
        // already-running service is a no-op (just onStartCommand), so this
        // is idempotent and harmless.
        if (NotificationsService.isRunning()) {
            return;
        }
        try {
            startForegroundService(new Intent(this, NotificationsService.class));
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to wake main process");
            try {
                startService(new Intent(this, NotificationsService.class));
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        stopSelf();
    }
}
