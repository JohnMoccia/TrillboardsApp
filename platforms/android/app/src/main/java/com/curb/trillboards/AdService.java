package com.curb.trillboards;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;

public class AdService extends Service {

    private static final String TAG        = "CurbAdService";
    private static final String CHANNEL_ID = "curb_ad_channel";
    private static final int    NOTIF_ID   = 1001;

    // Check if MainActivity is alive every 60 seconds
    private static final long WATCHDOG_INTERVAL_MS = 60_000;
    // Wake lock timeout — 2 hours, re-acquired on each watchdog tick
    private static final long WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock  wifiLock;
    private Handler  watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdService created");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Ad player running"));
        acquireWakeLock();
        acquireWifiLock();
        startWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AdService onStartCommand");
        // Re-acquire locks if released
        if (wakeLock == null || !wakeLock.isHeld()) {
            acquireWakeLock();
        }
        if (wifiLock == null || !wifiLock.isHeld()) {
            acquireWifiLock();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "AdService destroyed — releasing resources");
        stopWatchdog();
        releaseWakeLock();
        releaseWifiLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Wake Lock ─────────────────────────────────────────────────────────────
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CurbAds::WakeLock");
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        Log.i(TAG, "WakeLock acquired (2hr timeout)");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.i(TAG, "WakeLock released");
        }
    }

    // ── WiFi Lock — prevents roaming/disconnect while app is running ──────────
    // The bugreport showed the device roaming between two APs every ~20 minutes
    // causing network connections to drop. WiFi lock prevents this.
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            // WIFI_MODE_FULL_HIGH_PERF keeps WiFi at full performance, no roaming
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "CurbAds::WifiLock");
            wifiLock.acquire();
            Log.i(TAG, "WifiLock acquired (FULL_HIGH_PERF)");
        } catch (Exception e) {
            Log.e(TAG, "WifiLock acquire failed: " + e.getMessage());
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
                Log.i(TAG, "WifiLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiLock release failed: " + e.getMessage());
        }
    }

    // ── Activity Watchdog ─────────────────────────────────────────────────────
    private void startWatchdog() {
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                // Re-acquire wake lock before it expires
                if (wakeLock == null || !wakeLock.isHeld()) {
                    Log.w(TAG, "WakeLock expired — re-acquiring");
                    acquireWakeLock();
                }

                // Re-acquire wifi lock if dropped
                if (wifiLock == null || !wifiLock.isHeld()) {
                    Log.w(TAG, "WifiLock dropped — re-acquiring");
                    acquireWifiLock();
                }

                if (!isMainActivityRunning()) {
                    Log.w(TAG, "MainActivity not found — restarting");
                    restartMainActivity();
                    updateNotification("Restarted at " + new java.util.Date().toString().substring(11, 19));
                } else {
                    Log.d(TAG, "Watchdog tick — MainActivity alive");
                }

                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        Log.i(TAG, "Watchdog started — checking every " + (WATCHDOG_INTERVAL_MS / 1000) + "s");
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private boolean isMainActivityRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);
        if (tasks == null) return false;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.topActivity != null &&
                task.topActivity.getPackageName().equals(getPackageName())) {
                return true;
            }
        }
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc.processName.equals(getPackageName()) &&
                    proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    return true;
                }
            }
        }
        return false;
    }

    private void restartMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            Log.i(TAG, "MainActivity restart intent sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart MainActivity: " + e.getMessage());
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Curb Ad Player")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Curb Ad Player", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
