/*
 * Bridge service in the MAIN process, bound by PushProcessService (the `:push`
 * process). Exists so the push process can detect the main process dying
 * (onServiceDisconnected) and wake it back up. It also makes sure the push
 * connection is (re)established whenever the main process comes up.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class PushBridgeService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("TFOSS", "PushBridgeService created");
        // Ensure the local push connection gets re-established when the
        // main process is woken up by the push process.
        ApplicationLoader.startPushService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ApplicationLoader.startPushService();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    public class LocalBinder extends android.os.Binder {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("TFOSS", "PushBridgeService destroyed");
    }
}
