package com.fasttracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

public class FastingService extends Service {

    public static final String CHANNEL_ONGOING = "ft_ongoing";
    public static final String CHANNEL_EVENTS  = "ft_events";
    public static final String CHANNEL_MILES   = "ft_milestones";

    public static final String ACTION_START     = "com.fasttracker.START";
    public static final String ACTION_STOP      = "com.fasttracker.STOP";
    public static final String ACTION_MILESTONE = "com.fasttracker.MILESTONE";
    public static final String ACTION_TICK      = "com.fasttracker.TICK";

    private static final int NOTIF_ONGOING = 1;
    private static int notifCounter = 100;

    private static final String PREFS     = "FastTrackerPrefs";
    private static final String KEY_STATE = "vt_fast";

    private Handler handler;
    private Runnable ticker;
    private long startTime = 0;
    private float goalHours = 16;
    private boolean running = false;

    private static final float[] MILESTONES = {2f, 4f, 8f, 12f, 16f, 24f};
    private static final String[] MILESTONE_NAMES = {
        "Transition Phase", "Glycogen Depletion", "Fat Burning Initiated",
        "Ketosis Entry", "Autophagy Accelerating", "Deep Cellular Repair"
    };
    private static final String[] MILESTONE_INSIGHTS = {
        "Insulin declining. Your body is shifting fuel sources.",
        "Liver glycogen being used. Fat utilization ramping up.",
        "Lipolysis in full swing. Early ketone production beginning.",
        "Ketone production rising. Your brain is switching to premium fuel.",
        "Cellular cleanup active. Your body is repairing and renewing.",
        "Full autophagy and immune regeneration underway."
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            restoreAndResume();
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            restoreAndResume();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            startTime = intent.getLongExtra("startTime", System.currentTimeMillis());
            goalHours = intent.getFloatExtra("goalHours", 16f);
            running = true;
            startForeground(NOTIF_ONGOING, buildOngoingNotif());
            startTicker();
            scheduleMilestoneAlarms();
        } else if (ACTION_STOP.equals(action)) {
            stopTicker();
            cancelMilestoneAlarms();
            stopForeground(true);
            stopSelf();
        } else if (ACTION_MILESTONE.equals(action)) {
            int idx = intent.getIntExtra("milestoneIdx", -1);
            if (idx >= 0 && idx < MILESTONE_NAMES.length) {
                fireMilestoneNotif(idx);
            }
        }

        return START_STICKY;
    }

    private void startTicker() {
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                updateOngoingNotif();
                broadcastTick();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(ticker);
    }

    private void stopTicker() {
        running = false;
        if (ticker != null) handler.removeCallbacks(ticker);
    }

    private void broadcastTick() {
        Intent i = new Intent(ACTION_TICK);
        i.putExtra("elapsed", System.currentTimeMillis() - startTime);
        sendBroadcast(i);
    }

    private Notification buildOngoingNotif() {
        long elapsed = System.currentTimeMillis() - startTime;
        String elapsedStr = formatDuration(elapsed / 1000);
        float hoursElapsed = elapsed / 3600000f;
        int pct = (int) Math.min(100, (hoursElapsed / goalHours) * 100);
        String phase = getCurrentPhaseName(hoursElapsed);

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, FastingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fasting — " + elapsedStr)
            .setContentText(phase + " · " + pct + "% of " + (int) goalHours + "h goal")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setProgress((int) goalHours * 60, (int) (hoursElapsed * 60), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateOngoingNotif() {
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ONGOING, buildOngoingNotif());
        }
    }

    private void scheduleMilestoneAlarms() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        for (int i = 0; i < MILESTONES.length; i++) {
            long triggerAt = startTime + (long) (MILESTONES[i] * 3600000);
            if (triggerAt <= System.currentTimeMillis()) continue;

            Intent intent = new Intent(this, FastingService.class);
            intent.setAction(ACTION_MILESTONE);
            intent.putExtra("milestoneIdx", i);

            PendingIntent pi = PendingIntent.getService(this, i + 200, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    private void cancelMilestoneAlarms() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        for (int i = 0; i < MILESTONES.length; i++) {
            Intent intent = new Intent(this, FastingService.class);
            intent.setAction(ACTION_MILESTONE);
            PendingIntent pi = PendingIntent.getService(this, i + 200, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }
    }

    private void fireMilestoneNotif(int idx) {
        doVibrate(new long[]{0, 50, 80, 50});

        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_MILES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle((int) MILESTONES[idx] + "h — " + MILESTONE_NAMES[idx])
            .setContentText(MILESTONE_INSIGHTS[idx])
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(MILESTONE_INSIGHTS[idx]))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(0xFFC9A96E)
            .build();

        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifCounter++, n);
    }

    private void restoreAndResume() {
        try {
            SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);
            String raw = prefs.getString(KEY_STATE, null);
            if (raw == null) { stopSelf(); return; }
            startTime  = extractLong(raw, "start");
            goalHours  = extractFloat(raw, "goal");
            boolean on = raw.contains("\"on\":true");
            if (!on || startTime == 0) { stopSelf(); return; }
            running = true;
            startForeground(NOTIF_ONGOING, buildOngoingNotif());
            startTicker();
        } catch (Exception e) {
            stopSelf();
        }
    }

    private long extractLong(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int i = json.indexOf(search);
            if (i < 0) return 0;
            i += search.length();
            int j = json.indexOf(",", i);
            if (j < 0) j = json.indexOf("}", i);
            if (j < 0) return 0;
            return Long.parseLong(json.substring(i, j).trim());
        } catch (Exception e) { return 0; }
    }

    private float extractFloat(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int i = json.indexOf(search);
            if (i < 0) return 16f;
            i += search.length();
            int j = json.indexOf(",", i);
            if (j < 0) j = json.indexOf("}", i);
            if (j < 0) return 16f;
            return Float.parseFloat(json.substring(i, j).trim());
        } catch (Exception e) { return 16f; }
    }

    private String getCurrentPhaseName(float hours) {
        if (hours >= 24) return "Deep Cellular Repair";
        if (hours >= 16) return "Autophagy Accelerating";
        if (hours >= 12) return "Ketosis Entry";
        if (hours >= 8)  return "Fat Burning";
        if (hours >= 4)  return "Glycogen Depletion";
        if (hours >= 2)  return "Transition Phase";
        return "Fed State";
    }

    private String formatDuration(long totalSecs) {
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        long s = totalSecs % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private void doVibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel ongoing = new NotificationChannel(
            CHANNEL_ONGOING, "Fasting Timer",
            NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("Live timer while fasting");
        ongoing.setShowBadge(false);
        nm.createNotificationChannel(ongoing);

        NotificationChannel events = new NotificationChannel(
            CHANNEL_EVENTS, "Fast Events",
            NotificationManager.IMPORTANCE_DEFAULT);
        events.setDescription("Fast started, stopped, goal reached");
        events.enableLights(true);
        events.setLightColor(0xFFC9A96E);
        events.enableVibration(true);
        nm.createNotificationChannel(events);

        NotificationChannel miles = new NotificationChannel(
            CHANNEL_MILES, "Milestones",
            NotificationManager.IMPORTANCE_DEFAULT);
        miles.setDescription("Biological phase milestone alerts");
        miles.enableLights(true);
        miles.setLightColor(0xFF5CC8C0);
        nm.createNotificationChannel(miles);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTicker();
    }
}