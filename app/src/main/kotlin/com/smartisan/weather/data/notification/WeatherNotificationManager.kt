package com.smartisan.weather.data.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartisan.weather.R
import com.smartisan.weather.data.model.WeatherAlert
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.ui.alert.WeatherAlertActivity
import kotlinx.coroutines.flow.first

/**
 * 管理天气预警等系统通知的发送与通知渠道注册。
 */
object WeatherNotificationManager {

    const val CHANNEL_ID_ALERTS = "weather_alerts"

    /** 确保在 Android 8.0+ 上创建了天气预警高优先级通知渠道。 */
    fun ensureChannelCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.weather_alert_notification_channel_name)
            val descriptionText = context.getString(R.string.weather_alert_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID_ALERTS, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 检查用户设置、权限及预警防重，对未通知过的突发灾害气象预警发出系统高优先级通知。
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyAlertsIfEligible(
        context: Context,
        cityName: String,
        alert: WeatherAlert,
    ) {
        if (alert.isEmpty) return

        val settings = WeatherSettings.getInstance(context)
        if (!settings.alertNotificationEnabled.first()) return

        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) return

        ensureChannelCreated(context)

        val alreadyNotifiedKeys = settings.readNotifiedAlertKeys()
        val newKeys = mutableSetOf<String>()

        val validInfos = alert.infos.filter { it.content.isNotBlank() }
        for (info in validInfos) {
            val alertKey = "${cityName}_${info.type}_${info.level}_${info.publishTime}"
            if (alreadyNotifiedKeys.contains(alertKey)) continue

            val title = context.getString(
                R.string.weather_alert_notification_title,
                cityName,
                info.type,
                info.level,
            )

            val intent = Intent(context, WeatherAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(WeatherAlertActivity.EXTRA_ALERT, alert)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                alertKey.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.weather_error_icon)
                .setContentTitle(title)
                .setContentText(info.content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(info.content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            managerCompat.notify(alertKey.hashCode(), notification)
            newKeys.add(alertKey)
        }

        if (newKeys.isNotEmpty()) {
            settings.addNotifiedAlertKeys(newKeys)
        }
    }
}
