package com.smartisan.weather.data.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 负责安排每天早上 07:30 的每日天气早报推送。
 */
object DailyWeatherNotificationScheduler {

    private const val WORK_NAME = "daily_weather_morning_notification"
    private const val TARGET_HOUR = 7
    private const val TARGET_MINUTE = 30

    /**
     * 计算距离下一个早晨 07:30 的延时，并排队单次后台任务。
     * 执行完后 Worker 会自动排队下一次，形成每日循环。
     */
    fun scheduleNext(context: Context) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
            set(Calendar.MINUTE, TARGET_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果今天的 07:30 已经过去，安排到明天的 07:30
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelayMillis = target.timeInMillis - now.timeInMillis

        val request = OneTimeWorkRequestBuilder<DailyWeatherNotificationWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
