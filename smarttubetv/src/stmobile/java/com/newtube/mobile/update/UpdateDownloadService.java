package com.newtube.mobile.update;

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
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.liskovsoft.appupdatechecker2.UpdateInfo;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.update.MobileUpdateActivity;

/**
 * NEWTUBE(update-flow): keeps an update download alive when the user leaves the app. Android 15+
 * cuts a background app's network within seconds (the emulator logged "Destroyed live tcp sockets"
 * 5 s after Home, and the APK download died); a foreground service is what lets the transfer finish.
 * The download itself stays in {@link AppUpdates} - this only holds the process in the foreground,
 * with a progress notification and a Cancel action, for as long as it runs.
 */
public class UpdateDownloadService extends Service implements AppUpdates.Listener {
    private static final String TAG = "UpdateDownloadService";
    private static final String CHANNEL_ID = "newtube_updates";
    private static final int PROGRESS_NOTIFICATION_ID = 0x4E5501;
    private static final int READY_NOTIFICATION_ID = 0x4E5502;
    private static final String ACTION_CANCEL = "com.newtube.mobile.update.CANCEL";
    /** The system drops notification updates posted faster than a few per second. */
    private static final long NOTIFY_INTERVAL_MS = 1_000;

    private AppUpdates mUpdates;
    private NotificationManager mNotifications;
    private boolean mForeground;
    private long mLastNotifyMs;

    /** From AppUpdates.download(), while the user is looking at the sheet (a foreground start). */
    static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, UpdateDownloadService.class));
        } catch (RuntimeException e) {
            // API 31+ refuses a start from the background; the download still runs while the app is open
            Log.w(TAG, "not started: " + e);
        }
    }

    /** The download finished while the app was in the background: one tap to the installer. */
    static void postReady(Context context, UpdateInfo info) {
        NotificationManager notifications = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notifications == null) {
            return;
        }

        createChannel(context, notifications);
        Intent intent = MobileUpdateActivity.installIntent(context);
        PendingIntent tap = PendingIntent.getActivity(context, READY_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        notifications.notify(READY_NOTIFICATION_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mobile_update)
                .setContentTitle(context.getString(R.string.mobile_update_row_ready))
                .setContentText(context.getString(R.string.mobile_update_app_version, info.versionName))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /** The update sheet is open: the "ready" notification has done its job. */
    public static void clearReady(Context context) {
        NotificationManager notifications = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.cancel(READY_NOTIFICATION_ID);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mUpdates = AppUpdates.instance(this);
        mNotifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel(this, mNotifications);
        mUpdates.addListener(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Every start must promote promptly: a startForegroundService() that never reaches
        // startForeground() is an ANR-class crash on API 26+ - the Cancel action included.
        promote(buildProgressNotification());

        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            mUpdates.cancelDownload();
        }

        stopIfDone();
        return START_NOT_STICKY;
    }

    @Override
    public void onUpdateStateChanged() {
        if (stopIfDone()) {
            return;
        }

        long nowMs = SystemClock.elapsedRealtime();

        if (mForeground && nowMs - mLastNotifyMs >= NOTIFY_INTERVAL_MS) {
            mLastNotifyMs = nowMs;
            mNotifications.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification());
        }
    }

    private boolean stopIfDone() {
        if (mUpdates.getPhase() == AppUpdates.Phase.DOWNLOADING) {
            return false;
        }

        if (mForeground) {
            mForeground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }

        stopSelf();
        return true;
    }

    /**
     * Android 15+: dataSync services share a daily time budget (video downloads use it too). When it
     * runs out the service must leave the foreground within seconds or the app is killed. The download
     * carries on while the app is open; it only loses its protection in the background.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "foreground time budget used up");
        if (mForeground) {
            mForeground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        mUpdates.removeListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void promote(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, notification);
            }
            mForeground = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground promotion refused: " + e);
        }
    }

    private Notification buildProgressNotification() {
        UpdateInfo info = mUpdates.getInfo();
        String version = info != null ? info.versionName : "";
        long bytes = mUpdates.getDownloadedBytes();
        long total = mUpdates.getDownloadTotal();
        int percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1;

        Intent cancel = new Intent(this, UpdateDownloadService.class).setAction(ACTION_CANCEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent cancelIntent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 0, cancel, flags)
                : PendingIntent.getService(this, 0, cancel, flags);
        PendingIntent open = PendingIntent.getActivity(this, PROGRESS_NOTIFICATION_ID,
                MobileUpdateActivity.showIntent(this), flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mobile_update)
                .setContentTitle(getString(R.string.mobile_update_downloading_title))
                .setContentText(percent >= 0 ? getString(R.string.mobile_update_row_downloading_percent, version, percent)
                        : getString(R.string.mobile_update_app_version, version))
                .setProgress(100, Math.max(0, percent), percent < 0)
                .setContentIntent(open)
                .addAction(0, getString(R.string.mobile_update_cancel), cancelIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private static void createChannel(Context context, @Nullable NotificationManager notifications) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.mobile_update_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.mobile_update_channel_desc));
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }
    }
}
