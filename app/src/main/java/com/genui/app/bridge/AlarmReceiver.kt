package com.genui.app.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.os.Build

/**
 * 闹钟响铃（alarm_set 工具的真实落地）：
 * 到点后发系统通知——这是真实的闹钟行为，用户可点掉。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "提醒"
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "mo_alarm"
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(ch, "GenUI · 闹钟提醒", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(9001, NotificationCompat.Builder(context, ch)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $label")
            .setContentText("到点了 —— 来自 GenUI Agent 的提醒")
            .setAutoCancel(true).build())
    }
}
