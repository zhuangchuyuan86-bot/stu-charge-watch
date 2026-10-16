package com.stu.chargewatch;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.List;

final class Notifications {
    static final String CHANNEL = "available_ports";
    static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "充电桩空位提醒", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("关注的充电桩从无空位变为有空位时提醒");
        manager.createNotificationChannel(channel);
    }
    static boolean allowed(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }
    static List<String> transitions(List<Station> before, List<Station> after) {
        return Station.availableTransitions(before, after, System.currentTimeMillis());
    }
    static void report(Context context, List<Station> before, List<Station> after) {
        if (!Settings.watch(context) || !allowed(context)) return;
        createChannel(context);
        for (String id : transitions(before, after)) {
            Station station = null;
            for (Station item : after) if (item.id.equals(id)) station = item;
            if (station == null) continue;
            long notified = Settings.prefs(context).getLong("notified_" + id, 0);
            if (notified == station.lastSuccess) continue;
            Intent intent = new Intent(context, MainActivity.class).putExtra("device", id);
            PendingIntent pending = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_charge).setContentTitle(Settings.name(context, id) + " 有空位了")
                .setContentText("本次查询有 " + station.free() + " 个空闲插座，点击查看")
                .setContentIntent(pending).setAutoCancel(true).setShowWhen(true).build();
            context.getSystemService(NotificationManager.class).notify(id.hashCode(), notification);
            Settings.prefs(context).edit().putLong("notified_" + id, station.lastSuccess).apply();
        }
    }
}
