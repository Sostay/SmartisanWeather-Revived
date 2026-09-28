package com.smartisan.weather

import android.app.Application
import com.smartisan.weather.data.notification.WeatherNotificationManager

/**
 * 应用入口。
 *
 * 天气应用不需要复杂的全局初始化，Room 和 DataStore 都在各自的单例中懒加载。
 */
class SmartisanWeatherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WeatherNotificationManager.ensureChannelCreated(this)
    }
}
