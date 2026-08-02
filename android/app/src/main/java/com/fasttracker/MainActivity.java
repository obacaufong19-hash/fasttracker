package com.fasttracker;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
    private static final String PREFS     = "FastTrackerPrefs";
    private static final String CHAN_MAIN = "vt_main";
    private static final int    PERM_REQ  = 101;
    private static int          notifId   = 1000;
    private boolean             rxReg     = false;

    private BroadcastReceiver tickRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            try {
                final long ms = i.getLongExtra("elapsed", 0);
                final String js = "window.onServiceTick&&window.onServiceTick(" + ms + ")";
                if (webView != null) webView.post(new Runnable() {
                    public void run() {
                        try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { requestWindowFeature(Window.FEATURE_NO_TITLE); } catch (Exception ignored) {}
        try { applyEdgeToEdge(); } catch (Exception ignored) {}
        try { createNotifChannel(); } catch (Exception ignored) {}
        try { requestNotifPermission(); } catch (Exception ignored) {}
        setupWebView();
    }

    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    private void setupWebView() {
        try {
            webView = new WebView(this);
            setContentView(webView);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);
            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setWebViewClient(new WebViewClient());
            webView.addJavascriptInterface(new Store(), "AndroidStorage");
            webView.addJavascriptInterface(new Notifs(), "AndroidNotif");
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Exception e) {
            try {
                if (webView == null) webView = new WebView(this);
                setContentView(webView);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.addJavascriptInterface(new Store(), "AndroidStorage");
                webView.addJavascriptInterface(new Notifs(), "AndroidNotif");
                webView.loadUrl("file:///android_asset/index.html");
            } catch (Exception ignored) {}
        }
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHAN_MAIN, "VitaTracker", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Fasting notifications");
            ch.enableLights(true);
            ch.setLightColor(0xFFC9A96E);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_REQ);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (!rxReg) {
                registerReceiver(tickRx, new IntentFilter("com.fasttracker.TICK"));
                rxReg = true;
            }
        } catch (Exception ignored) {}
        if (webView != null) webView.post(new Runnable() {
            public void run() {
                try { webView.evaluateJavascript("window.onAppResume&&window.onAppResume()", null); }
                catch (Exception ignored) {}
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (rxReg) { unregisterReceiver(tickRx); rxReg = false; } }
        catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (rxReg) { unregisterReceiver(tickRx); rxReg = false; } }
        catch (Exception ignored) {}
        try { if (webView != null) { webView.destroy(); webView = null; } }
        catch (Exception ignored) {}
    }

    private void postNotif(String title, String body, int priority) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
            }
            Intent tap = new Intent(this, MainActivity.class);
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHAN_MAIN)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body)
                .setPriority(priority).setContentIntent(pi)
                .setAutoCancel(true).setColor(0xFFC9A96E);
            NotificationManagerCompat.from(this).notify(notifId++, b.build());
        } catch (Exception ignored) {}
    }

    private void vibrate(long[] p) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            else v.vibrate(p, -1);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        try { if (webView != null && webView.canGoBack()) { webView.goBack(); return; } }
        catch (Exception ignored) {}
        super.onBackPressed();
    }

    // ══ STORAGE ═══════════════════════════════════════════════
    public class Store {
        @JavascriptInterface
        public void setItem(String k, String v) {
            try {
                if (k == null || !k.matches("[a-zA-Z0-9_]+")) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(k, v).apply();
            } catch (Exception ignored) {}
        }
        @JavascriptInterface
        public String getItem(String k) {
            try {
                if (k == null || !k.matches("[a-zA-Z0-9_]+")) return null;
                return getSharedPreferences(PREFS, MODE_PRIVATE).getString(k, null);
            } catch (Exception e) { return null; }
        }
        @JavascriptInterface
        public void removeItem(String k) {
            try {
                if (k == null || !k.matches("[a-zA-Z0-9_]+")) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(k).apply();
            } catch (Exception ignored) {}
        }
    }

    // ══ NOTIFICATIONS ══════════════════════════════════════════
    public class Notifs {
        @JavascriptInterface
        public void fastStarted(String goal, String meal) {
            postNotif("Fast Started", "Your " + goal + " fast has begun.", NotificationCompat.PRIORITY_DEFAULT);
            vibrate(new long[]{0, 80, 60, 80});
        }
        @JavascriptInterface
        public void fastStopped(String dur) {
            postNotif("Fast Ended", "You fasted for " + dur + ". Great work.", NotificationCompat.PRIORITY_DEFAULT);
            vibrate(new long[]{0, 60, 40, 60, 40, 60});
        }
        @JavascriptInterface
        public void goalReached(String goal) {
            postNotif("Goal Reached!", "You completed your " + goal + " fast!", NotificationCompat.PRIORITY_HIGH);
            vibrate(new long[]{0, 100, 80, 100, 80, 200});
        }
        @JavascriptInterface
        public void phaseMilestone(String name, String hour, String insight) {
            postNotif(hour + " — " + name, insight, NotificationCompat.PRIORITY_DEFAULT);
            vibrate(new long[]{0, 50, 80, 50});
        }
        @JavascriptInterface
        public boolean hasPermission() {
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    return ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            } catch (Exception ignored) {}
            return true;
        }
        @JavascriptInterface
        public void requestPermission() { requestNotifPermission(); }
    }
}