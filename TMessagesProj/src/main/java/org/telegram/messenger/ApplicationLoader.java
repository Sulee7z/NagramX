/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.json.JSONObject;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import static android.os.Build.VERSION.SDK_INT;

public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    private static PendingIntent pendingIntent;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;
    public static final CountDownLatch countDownLatch = new CountDownLatch(1);

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    // Refresh the screen state from the PowerManager directly. Used before
    // processing a push/updates while the app was frozen (Cirno tombstone):
    // the app may have missed the SCREEN_OFF broadcast, leaving isScreenOn
    // stale = true, which suppresses notifications for the opened dialog.
    public static void updateScreenState() {
        try {
            PowerManager pm = (PowerManager) applicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            boolean on = pm.isScreenOn();
            if (Build.VERSION.SDK_INT >= 20) {
                on = pm.isInteractive();
            }
            isScreenOn = on;
        } catch (Throwable ignore) {
        }
    }

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;
    private Thread.UncaughtExceptionHandler systemUncaughtExceptionHandler;

    @Override
    protected void attachBaseContext(Context base) {
        systemUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        super.attachBaseContext(base);
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {
        }
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = new GoogleLocationProvider();
        }
        return locationServiceProvider;
    }

    /*protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new GoogleLocationProvider();
    }*/

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            if (NekoConfig.useOSMDroidMap.Bool())
                mapsProvider = new OSMDroidMapsProvider();
            else {
                mapsProvider = new GoogleMapsProvider();
            }
        }
        return mapsProvider;
    }

    /*protected IMapsProvider onCreateMapsProvider() {
        return new GoogleMapsProvider();
    }*/

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = PushListenerController.getProvider();
        }
        return pushProvider;
    }

    /*protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }*/

    public static String getApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    /*protected String onGetApplicationId() {
        return null;
    }*/

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return true;
    }

    protected boolean isBeta() {
        return BuildConfig.DEBUG;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/" + BuildConfig.APPLICATION_ID + "/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        NekoConfig.init();
        NaConfig.init();
        SharedPrefsHelper.init(applicationContext);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(AndroidUtil.shouldEnableCrashlytics());
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        PushListenerController.reconcilePushRegistration();

        // init fcm
        initPushServices();
        FileLog.d("app initied");

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();

        // Push connection must stay ALWAYS enabled (TFOSS original behavior).
        // Dynamically disabling it via setPushConnectionEnabled(false) in
        // foreground broke background push entirely: tgnet's push connection is
        // not a separate spare socket - disabling it leaves the app with no
        // background channel at all. We only resume connections on background
        // transitions to fix frozen sockets.
        ForegroundDetector.getInstance().addListener(new ForegroundDetector.Listener() {
            @Override
            public void onBecameForeground() {
                // nothing: connection resume on foreground is handled in
                // onActivityStarted -> resumeConnections()
            }

            @Override
            public void onBecameBackground() {
                // resume a possibly frozen connection right away, so background
                // push keeps working
                resumeConnections();
            }
        });
    }

    public ApplicationLoader() {
        super();
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();
        installCrashReportFilter();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                    // After a long background period the MTProto connection may be
                    // frozen/half-dead (Doze killed the socket, half-open TCP). Just
                    // updating the network info does NOT reconnect. Force tgnet to
                    // resume so messages refresh immediately when the app reopens.
                    resumeConnections();
                }
            }
        };
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        org.osmdroid.config.Configuration.getInstance().setUserAgentValue("Telegram-FOSS ( NekoX ) " + BuildConfig.VERSION_NAME);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(new File(ApplicationLoader.applicationContext.getCacheDir(), "osmdroid"));

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
    }

    // Local Push Service, TFoss implementation
    // 60 minutes, EXACT + while-idle: Doze cannot postpone it for hours. If both
    // processes were killed this alarm is the ONLY thing that brings push back,
    // so it must actually fire on time. 60 min = 24 wake-ups/day worst case.
    // Real-world breakage starts after 3+ hours of idle, so a 60-min health
    // check catches a dying connection before push is actually lost.
    private static final long PUSH_SERVICE_RESTART_INTERVAL = 60 * 60 * 1000;

    /**
     * Tombstone resurrection: schedules the last-resort path that wakes the push
     * stack back up after the OS froze/killed BOTH processes. Exact + while-idle:
     * the system is not allowed to postpone it for hours under Doze. The alarm
     * targets the :push process - it then wakes the main process (and forces a
     * connection resume) if needed. The app UI is never touched.
     */
    public static void schedulePushServiceRestart() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Log.d("TFOSS", "Scheduling push service restart check");
                AlarmManager am = (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);
                Intent i = new Intent(applicationContext, PushProcessService.class);
                pendingIntent = PendingIntent.getForegroundService(applicationContext, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.cancel(pendingIntent);
                try {
                    // Exact + while-idle: fires on time even in Doze (max once per
                    // 9 min per app, 30 min is fine). Without it, an inexact alarm
                    // can be deferred for HOURS in Doze -> no push for hours.
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PUSH_SERVICE_RESTART_INTERVAL, pendingIntent);
                } catch (Throwable ignore) {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PUSH_SERVICE_RESTART_INTERVAL, PUSH_SERVICE_RESTART_INTERVAL, pendingIntent);
                }
            } catch (Throwable e) {
                Log.e("TFOSS", "Failed to schedule push service restart");
            }
        });
    }

    public static void startPushService() {
        Utilities.stageQueue.postRunnable(ApplicationLoader::startPushServiceInternal);
    }

    // Force tgnet to resume all connections (native_resumeNetwork) and refresh
    // network state. Fixes "stale connection" after long background: Doze may
    // freeze/kill the socket while tgnet still believes it is connected, so
    // no reconnection ever happens until the user sends something.
    public static void resumeConnections() {
        Utilities.stageQueue.postRunnable(() -> {
            try {
                if (!applicationInited) {
                    // ConnectionsManager is not initialized yet; a premature
                    // native_init would use wrong parameters. The normal init
                    // flow connects by itself.
                    return;
                }
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        continue;
                    }
                    ConnectionsManager.getInstance(a).checkConnection();
                    ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("resume connections");
                }
            } catch (Throwable ignore) {
            }
        });
    }

    private static void startPushServiceInternal() {
        if (PushListenerController.getProvider().hasServices()) {
            return;
        }
        if (NotificationsService.isRunning()) {
            // Already alive (restart broadcast from the service itself, or the alarm), skip restart
            return;
        }
        SharedPreferences preferences = MessagesController.getNotificationsSettings(UserConfig.selectedAccount);
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("pushService", enabled);
            editor.putBoolean("pushConnection", enabled);
            editor.apply();
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setPushConnectionEnabled(enabled);
        }
        if (enabled) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    Log.d("TFOSS", "Starting push service...");
                    // FGS is required on Android 8+ so the OS does not kill the push connection in background.
                    try {
                        applicationContext.startForegroundService(new Intent(applicationContext, NotificationsService.class));
                    } catch (Throwable e) {
                        // OEMs (MIUI etc.) may block FGS from background; try the plain start
                        try {
                            applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
                        } catch (Throwable ignore) {
                            Log.e("TFOSS", "Failed to start push service");
                        }
                    }
                    // Separate :push process: stays alive when the main process is killed,
                    // and wakes the main process back up in seconds (instead of 15 minutes).
                    try {
                        applicationContext.startForegroundService(new Intent(applicationContext, PushProcessService.class));
                    } catch (Throwable e) {
                        try {
                            applicationContext.startService(new Intent(applicationContext, PushProcessService.class));
                        } catch (Throwable ignore) {
                        }
                    }
                    // NOTE: no periodic alarm here on purpose. Under Cirno freezing the
                    // app must stay fully silent (pure network-message unfreeze); a
                    // repeating alarm would wake it up periodically and waste CPU.
                    // The one-shot resurrection alarm is armed only when a process
                    // actually dies (onTaskRemoved / onDestroy).
                } catch (Throwable e) {
                    Log.e("TFOSS", "Failed to start push service");
                }
            });

        } else AndroidUtilities.runOnUIThread(() -> {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));
            applicationContext.stopService(new Intent(applicationContext, PushProcessService.class));

            if (pendingIntent != null) {
                AlarmManager alarm = (AlarmManager)applicationContext.getSystemService(Context.ALARM_SERVICE);
                // cancels the alarm registered via PendingIntent.getForegroundService() in the enable branch
                alarm.cancel(pendingIntent);
                pendingIntent = null;
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
                startPushService();
            }
        }, 1000);
    }

    /*private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }*/

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        boolean exists = false;
        try {
            String fileName = FileLoader.getAttachFileName(document);
            File f = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true);
            if (exists = f.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                if (Build.VERSION.SDK_INT >= 24) {
                    intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f), "application/vnd.android.package-archive");
                } else {
                    intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                }
                try {
                    activity.startActivityForResult(intent, 500);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return exists;
    }

    @Nullable
    public static Intent registerReceiverNotExported(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return ApplicationLoader.registerReceiverNotExported(ApplicationLoader.applicationContext, receiver, filter);
    }

    @Nullable
    public static Intent registerReceiverNotExported(Context context, @Nullable BroadcastReceiver receiver, IntentFilter filter) {
        if (SDK_INT < 33) {
            return context.registerReceiver(receiver, filter);
        } else {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public void addItemOptions(ItemOptions itemOptions) {

    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }

    private void installCrashReportFilter() {
        Thread.UncaughtExceptionHandler crashlyticsHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (AndroidUtil.shouldReportCrashToCrashlytics(error)) {
                if (crashlyticsHandler != null) {
                    crashlyticsHandler.uncaughtException(thread, error);
                    return;
                }
            } else if (systemUncaughtExceptionHandler != null) {
                systemUncaughtExceptionHandler.uncaughtException(thread, error);
                return;
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }
}
