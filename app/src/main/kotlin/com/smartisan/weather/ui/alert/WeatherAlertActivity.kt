package com.smartisan.weather.ui.alert

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.smartisan.weather.R
import com.smartisan.weather.data.model.WeatherAlert
import com.smartisan.weather.data.notification.WeatherNotificationManager
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.ui.navigation.WeatherEdgeToEdgeActivity
import kotlinx.coroutines.launch

/** 原版锤子天气风格的预警详情页。 */
class WeatherAlertActivity : WeatherEdgeToEdgeActivity() {
    private val settings by lazy(LazyThreadSafetyMode.NONE) { WeatherSettings.getInstance(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            lifecycleScope.launch {
                settings.setAlertNotificationEnabled(true)
                WeatherNotificationManager.ensureChannelCreated(this@WeatherAlertActivity)
            }
        } else {
            Toast.makeText(this, R.string.weather_alert_notification_permission_tips, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alert = readAlert() ?: run {
            finish()
            return
        }
        setContent {
            val alertEnabled by settings.alertNotificationEnabled.collectAsStateWithLifecycle(initialValue = true)
            val dailyEnabled by settings.dailyNotificationEnabled.collectAsStateWithLifecycle(initialValue = true)
            WeatherAlertScreen(
                alerts = alert.infos,
                alertNotificationEnabled = alertEnabled,
                dailyNotificationEnabled = dailyEnabled,
                onToggleAlertNotification = ::handleToggleAlertNotification,
                onToggleDailyNotification = ::handleToggleDailyNotification,
                onBack = ::finish,
            )
        }
    }

    private fun handleToggleAlertNotification(target: Boolean) {
        if (target && !checkAndRequestNotificationPermission()) return
        lifecycleScope.launch {
            settings.setAlertNotificationEnabled(target)
            if (target) {
                WeatherNotificationManager.ensureChannelCreated(this@WeatherAlertActivity)
            }
        }
    }

    private fun handleToggleDailyNotification(target: Boolean) {
        if (target && !checkAndRequestNotificationPermission()) return
        lifecycleScope.launch {
            settings.setDailyNotificationEnabled(target)
            if (target) {
                WeatherNotificationManager.ensureChannelCreated(this@WeatherAlertActivity)
                com.smartisan.weather.data.notification.DailyWeatherNotificationScheduler.scheduleNext(this@WeatherAlertActivity)
            }
        }
    }

    private fun checkAndRequestNotificationPermission(): Boolean {
        val managerCompat = NotificationManagerCompat.from(this)
        if (!managerCompat.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, R.string.weather_alert_notification_permission_tips, Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            return false
        }
        return true
    }

    private fun readAlert(): WeatherAlert? =
        IntentCompat.getSerializableExtra(intent, EXTRA_ALERT, WeatherAlert::class.java)

    companion object {
        const val EXTRA_ALERT = "alert"
    }
}
