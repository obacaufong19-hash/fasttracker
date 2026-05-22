package com.fasttracker;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.Manifest;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String PREFS_NAME   = "FastTrackerPrefs";
    private static final String CHANNEL_ID   = "fast_tracker_channel";
    private static final String CHANNEL_MILE = "fast_tracker_milestones";
    private static final int    PERM_REQ     = 101;
    private static int          notifId      = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#080810"));
        }

        createNotificationChannels();
        requestNotificationPermission();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new StorageBridge(),      "AndroidStorage");
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidNotif");
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ── NOTIFICATION CHANNELS ─────────────────────────────────
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            // General actions channel
            NotificationChannel general = new NotificationChannel(
                CHANNEL_ID,
                "Fast Actions",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            general.setDescription("Fast started, stopped, and goal reached notifications");
            general.enableLights(true);
            general.setLightColor(Color.parseColor("#c9a96e"));
            general.enableVibration(true);
            nm.createNotificationChannel(general);

            // Milestones channel (silent-ish)
            NotificationChannel miles = new NotificationChannel(
                CHANNEL_MILE,
                "Fasting Milestones",
                NotificationManager.IMPORTANCE_LOW
            );
            miles.setDescription("Biological phase milestone notifications");
            miles.enableLights(true);
            miles.setLightColor(Color.parseColor("#5cc8c0"));
            nm.createNotificationChannel(miles);
        }
    }

    // ── PERMISSION ────────────────────────────────────────────
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_REQ);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQ) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            // Notify JS of result
            final String js = "window.onNotifPermission && window.onNotifPermission(" + granted + ")";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    }

    // ── HELPER: fire a notification ───────────────────────────
    private void fireNotification(String channel, String title, String body,
                                  String bigText, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
        }

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(importance)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#c9a96e"));

        if (bigText != null && !bigText.isEmpty()) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
        }

        NotificationManagerCompat.from(this).notify(notifId++, b.build());
    }

    // ── HELPER: vibrate ───────────────────────────────────────
    private void doVibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ══════════════════════════════════════════════════════════
    // STORAGE BRIDGE
    // ══════════════════════════════════════════════════════════
    public class StorageBridge {
        @JavascriptInterface
        public void setItem(String key, String value) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, value).apply();
        }
        @JavascriptInterface
        public String getItem(String key) {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        }
        @JavascriptInterface
        public void removeItem(String key) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(key).apply();
        }
    }

    // ══════════════════════════════════════════════════════════
    // NOTIFICATION BRIDGE  — called from JavaScript
    // ══════════════════════════════════════════════════════════
    public class NotificationBridge {

        /** Fast started */
        @JavascriptInterface
        public void fastStarted(String goalHours, String lastMeal) {
            fireNotification(
                CHANNEL_ID,
                "⏱ Fast Started",
                "Your " + goalHours + "h fast has begun. Stay strong.",
                "Last meal: " + lastMeal + "\nGoal: " + goalHours + " hours — you've got this.",
                NotificationCompat.PRIORITY_DEFAULT
            );
            doVibrate(new long[]{0, 80, 60, 80});
        }

        /** Fast stopped manually */
        @JavascriptInterface
        public void fastStopped(String duration) {
            fireNotification(
                CHANNEL_ID,
                "⏹ Fast Ended",
                "You fasted for " + duration + ". Great work.",
                "Your fast of " + duration + " has been recorded. Rest, rehydrate, and break your fast mindfully.",
                NotificationCompat.PRIORITY_DEFAULT
            );
            doVibrate(new long[]{0, 60, 40, 60, 40, 60});
        }

        /** Goal reached */
        @JavascriptInterface
        public void goalReached(String goalHours) {
            fireNotification(
                CHANNEL_ID,
                "🎯 Goal Reached!",
                "You've completed your " + goalHours + "h fast!",
                "Outstanding! You hit your " + goalHours + "-hour fasting goal. Your body has been through remarkable biological changes. You can stop now or extend further.",
                NotificationCompat.PRIORITY_HIGH
            );
            doVibrate(new long[]{0, 100, 80, 100, 80, 200});
        }

        /** Phase milestone reached */
        @JavascriptInterface
        public void phaseMilestone(String phaseName, String phaseHour, String insight) {
            fireNotification(
                CHANNEL_MILE,
                "✦ " + phaseHour + "h — " + phaseName,
                insight,
                insight,
                NotificationCompat.PRIORITY_LOW
            );
            doVibrate(new long[]{0, 50, 80, 50});
        }

        /** Reminder: still fasting */
        @JavascriptInterface
        public void fastingReminder(String elapsed, String remaining) {
            fireNotification(
                CHANNEL_MILE,
                "⏱ Fasting — " + elapsed + " in",
                remaining + " remaining to goal. Keep going.",
                null,
                NotificationCompat.PRIORITY_LOW
            );
        }

        /** Check if notifications are permitted */
        @JavascriptInterface
        public boolean hasPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        /** Request permission (re-trigger) */
        @JavascriptInterface
        public void requestPermission() {
            requestNotificationPermission();
        }
    }
}