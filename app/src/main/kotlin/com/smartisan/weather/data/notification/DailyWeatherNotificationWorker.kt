package com.smartisan.weather.data.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.data.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * 每日早晨 07:30 触发的后台 Worker，发送今日天气概况。
 */
class DailyWeatherNotificationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        try {
            val settings = WeatherSettings.getInstance(applicationContext)
            if (!settings.dailyNotificationEnabled.first()) {
                return Result.success()
            }

            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            if (settings.readLastDailyNotificationDate() == today) {
                return Result.success()
            }

            val cities = CityRepository(applicationContext).savedCities.first()
            val targetCity = cities.firstOrNull { it.isLocationCity } ?: cities.firstOrNull()
            if (targetCity != null) {
                val weatherRepo = WeatherRepository(applicationContext)
                val snapshot = weatherRepo.getCachedWeatherSnapshot(targetCity.locationKey)
                val weather = snapshot?.weather?.takeIf { it.isComplete }
                    ?: weatherRepo.fetchWeather(targetCity.locationKey, notifyWidgets = false)

                if (weather != null && weather.isComplete) {
                    WeatherNotificationManager.notifyDailyWeather(
                        context = applicationContext,
                        cityName = targetCity.displayName,
                        weather = weather,
                    )
                    settings.setLastDailyNotificationDate(today)
                }
            }
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            // 无论是成功还是失败，均排队下一次早晨 07:30 的提醒
            DailyWeatherNotificationScheduler.scheduleNext(applicationContext)
        }
    }
}
