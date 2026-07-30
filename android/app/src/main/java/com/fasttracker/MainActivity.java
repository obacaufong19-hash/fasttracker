package com.fasttracker;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
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
    private static final String PREFS    = "FastTrackerPrefs";
    private static final String CHAN_EVT = "ft_events";
    private static final int    PERM_REQ = 101;
    private static int          notifId  = 2000;

    private BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final long elapsed = intent.getLongExtra("elapsed", 0);
            final String js = "window.onServiceTick && window.onServiceTick(" + elapsed + ")";
            if (webView != null) {
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(js, null);
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Edge-to-edge
        if (Build.VERSION.SDK_INT >= 30) { // Build.VERSION_CODES.R
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }

        if (Build.VERSION.SDK_INT >= 21) { // LOLLIPOP
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        requestNotificationPermission();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new StorageBridge(),      "AndroidStorage");
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidNotif");
        webView.addJavascriptInterface(new ServiceBridge(),      "AndroidService");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(FastingService.ACTION_TICK);
        // Use basic registerReceiver for compatibility across all API levels
        registerReceiver(tickReceiver, filter);

        if (webView != null) {
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.evaluateJavascript(
                        "window.onAppResume && window.onAppResume()", null);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(tickReceiver);
        } catch (Exception ignored) {}
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERM_REQ);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQ) {
            final boolean granted = results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
            if (webView != null) {
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(
                            "window.onNotifPermission && window.onNotifPermission("
                            + granted + ")", null);
                    }
                });
            }
        }
    }

    private void fireEventNotif(String title, String body, String bigText, int priority) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
        }
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHAN_EVT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(priority)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(0xFFC9A96E);

        if (bigText != null) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
        }
        NotificationManagerCompat.from(this).notify(notifId++, b.build());
    }

    private void doVibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 26) { // OREO
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ══ STORAGE BRIDGE ════════════════════════════════════════
    public class StorageBridge {
        @JavascriptInterface
        public void setItem(String key, String value) {
            if (key == null || !key.matches("[a-zA-Z0-9_]+")) return;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String getItem(String key) {
            if (key == null || !key.matches("[a-zA-Z0-9_]+")) return null;
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null);
        }

        @JavascriptInterface
        public void removeItem(String key) {
            if (key == null || !key.matches("[a-zA-Z0-9_]+")) return;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().remove(key).apply();
        }
    }

    // ══ NOTIFICATION BRIDGE ═══════════════════════════════════
    public class NotificationBridge {

        @JavascriptInterface
        public void fastStarted(String goalHours, String lastMeal) {
            String g = goalHours != null
                ? goalHours.replaceAll("[^0-9hm: ]", "") : "?";
            String m = lastMeal != null
                ? lastMeal.replaceAll("[^0-9a-zA-Z:,/ -]", "") : "?";
            fireEventNotif(
                "Fast Started",
                "Your " + g + " fast has begun.",
                "Last meal: " + m + "\nGoal: " + g,
                NotificationCompat.PRIORITY_DEFAULT);
            doVibrate(new long[]{0, 80, 60, 80});
        }

        @JavascriptInterface
        public void fastStopped(String duration) {
            String d = duration != null
                ? duration.replaceAll("[^0-9:hm ]", "") : "?";
            fireEventNotif(
                "Fast Ended",
                "You fasted for " + d + ". Great work.",
                "Your fast of " + d + " has been recorded.",
                NotificationCompat.PRIORITY_DEFAULT);
            doVibrate(new long[]{0, 60, 40, 60, 40, 60});
        }

        @JavascriptInterface
        public void goalReached(String goalHours) {
            String g = goalHours != null
                ? goalHours.replaceAll("[^0-9h]", "") : "?";
            fireEventNotif(
                "Goal Reached!",
                "You completed your " + g + " fast!",
                "Outstanding! You hit your " + g + " fasting goal.",
                NotificationCompat.PRIORITY_HIGH);
            doVibrate(new long[]{0, 100, 80, 100, 80, 200});
        }

        @JavascriptInterface
        public void phaseMilestone(String name, String hour, String insight) {
            fireEventNotif(
                hour + " — " + name,
                insight,
                insight,
                NotificationCompat.PRIORITY_DEFAULT);
            doVibrate(new long[]{0, 50, 80, 50});
        }

        @JavascriptInterface
        public boolean hasPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void requestPermission() {
            requestNotificationPermission();
        }
    }

    // ══ SERVICE BRIDGE ════════════════════════════════════════
    public class ServiceBridge {

        @JavascriptInterface
        public void startFasting(long startTime, float goalHours) {
            Intent i = new Intent(MainActivity.this, FastingService.class);
            i.setAction(FastingService.ACTION_START);
            i.putExtra("startTime", startTime);
            i.putExtra("goalHours", goalHours);
            if (Build.VERSION.SDK_INT >= 26) { // OREO
                startForegroundService(i);
            } else {
                startService(i);
            }
        }

        @JavascriptInterface
        public void stopFasting() {
            Intent i = new Intent(MainActivity.this, FastingService.class);
            i.setAction(FastingService.ACTION_STOP);
            startService(i);
        }
    }
}