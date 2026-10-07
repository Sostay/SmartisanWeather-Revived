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
    const val CHANNEL_ID_DAILY = "weather_daily"
    private const val NOTIFICATION_ID_DAILY = 2001

    /** 确保在 Android 8.0+ 上创建了天气预警与每日天气早报通知渠道。 */
    fun ensureChannelCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alertsName = context.getString(R.string.weather_alert_notification_channel_name)
            val alertsDesc = context.getString(R.string.weather_alert_notification_channel_desc)
            val alertsChannel = NotificationChannel(CHANNEL_ID_ALERTS, alertsName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = alertsDesc
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(alertsChannel)

            val dailyName = context.getString(R.string.weather_daily_notification_channel_name)
            val dailyDesc = context.getString(R.string.weather_daily_notification_channel_desc)
            val dailyChannel = NotificationChannel(CHANNEL_ID_DAILY, dailyName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = dailyDesc
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(dailyChannel)
        }
    }

    /**
     * 发送每日天气早报通知。
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyDailyWeather(
        context: Context,
        cityName: String,
        weather: com.smartisan.weather.data.model.Weather,
    ) {
        val settings = WeatherSettings.getInstance(context)
        if (!settings.dailyNotificationEnabled.first()) return

        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) return

        ensureChannelCreated(context)

        val title = context.getString(R.string.weather_daily_notification_title, cityName)

        val isCelsius = settings.readTempUnit() == WeatherSettings.UNIT_CELSIUS
        val currentTemp = (if (isCelsius) weather.observe.tempC else weather.observe.tempF)
            .takeUnless { it == "UNKNOWN" }
            ?.let { "$it°" } ?: ""

        val daily = weather.dailyForecast.firstOrNull()
        val tempRange = if (daily != null) {
            val high = if (isCelsius) daily.highTempC else daily.highTempF
            val low = if (isCelsius) daily.lowTempC else daily.lowTempF
            "$low° / $high°"
        } else ""

        val conditionRes = com.smartisan.weather.util.WeatherCodeMapping.textResMap[weather.themeCode]
            ?: R.string.weather_text_99
        val condition = context.getString(conditionRes)

        val aqiValue = weather.observe.aqi.ifBlank { weather.airQuality.aqiValue }
        val aqiText = aqiValue.toIntOrNull()?.let { aqi ->
            val level = com.smartisan.weather.util.WeatherCodeMapping.AqiLevel.getLevel(aqi)
            val levelText = context.getString(com.smartisan.weather.util.WeatherCodeMapping.AqiLevel.levelTextRes[level])
            " · 空气 $aqi $levelText"
        }.orEmpty()

        val contentParts = buildList {
            add(condition)
            if (currentTemp.isNotBlank()) add("当前 $currentTemp")
            if (tempRange.isNotBlank()) add("全天 $tempRange")
        }
        val contentText = contentParts.joinToString("，") + aqiText

        val intent = Intent(context, com.smartisan.weather.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val weatherCode = weather.themeCode.ifBlank { weather.observe.code }
        val weatherIconRes = com.smartisan.weather.util.WeatherCodeMapping.getNotificationIcon(weatherCode, isNight = false)
            .takeIf { it > 0 } ?: R.drawable.ic_notify_icon_sunny

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DAILY)
            .setSmallIcon(weatherIconRes)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        managerCompat.notify(NOTIFICATION_ID_DAILY, notification)
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
