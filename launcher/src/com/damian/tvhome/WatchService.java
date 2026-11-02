package com.damian.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Cât timp e pornit, readuce ecranul Acasă al acestei aplicații când sistemul deschide Google TV Home
 * (de exemplu la Înapoi din aplicația TV sau la butonul Home), în afară de cazul în care
 * utilizatorul a apăsat butonul „Google TV" sau a oprit opțiunea din Opțiuni.
 */
public class WatchService extends Service {
    private static final String GTV = "com.google.android.apps.tv.launcherx";
    private final Handler h = new Handler(Looper.getMainLooper());
    private long seen;
    private long handled;
    private SharedPreferences prefs;
    private UsageStatsManager usm;

    private final Runnable poll = new Runnable() {
        public void run() {
            try {
                check();
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 400);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("tvhome", MODE_PRIVATE);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        seen = System.currentTimeMillis() - 6000;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("watch", "Acasă", NotificationManager.IMPORTANCE_MIN));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "watch") : new Notification.Builder(this);
            Notification n = b.setSmallIcon(android.graphics.drawable.Icon.createWithResource(this, getApplicationInfo().icon)).setContentTitle("Acasă").setContentText("Readuce ecranul Acasă").build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, n);
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        h.removeCallbacks(poll);
        h.postDelayed(poll, 400);
        return START_STICKY;
    }

    private void check() {
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(seen - 1500, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null;
        long t = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = e.getPackageName();
                t = e.getTimeStamp();
            }
        }
        seen = now;
        if (pkg == null || t <= handled) return;
        handled = t;
        if (!GTV.equals(pkg)) return;
        if ("0".equals(prefs.getString("auto_home", "1"))) return;
        if ("1".equals(prefs.getString("gmode", "0"))) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isInteractive()) return;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
