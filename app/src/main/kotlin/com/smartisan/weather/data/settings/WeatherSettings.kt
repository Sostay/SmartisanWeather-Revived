package com.smartisan.weather.data.settings

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartisan.weather.appwidget.WeatherWidgetUpdateNotifier
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.weatherDataStore by preferencesDataStore(name = "weather_settings")

/**
 * 应用级设置仓库。
 *
 * DataStore 是唯一持久化来源；[tempUnit] 同时提供同步可读的内存快照，供原版 View
 * 在绘制和动画期间读取，避免再维护一份 SharedPreferences 状态。
 */
class WeatherSettings private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = appContext.weatherDataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    val tempUnit: StateFlow<Int> = preferences
        .map { it[KEY_TEMP_UNIT] ?: UNIT_CELSIUS }
        .stateIn(scope, SharingStarted.Eagerly, UNIT_CELSIUS)

    val isCelsius: Flow<Boolean> = tempUnit.map { it == UNIT_CELSIUS }

    val startupNoticeAccepted: Flow<Boolean> = preferences
        .map { it[KEY_STARTUP_NOTICE_ACCEPTED] ?: false }

    val alertNotificationEnabled: Flow<Boolean> = preferences
        .map { it[KEY_ALERT_NOTIFICATION_ENABLED] ?: true }

    suspend fun setAlertNotificationEnabled(enabled: Boolean) {
        appContext.weatherDataStore.edit { it[KEY_ALERT_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun readNotifiedAlertKeys(): Set<String> =
        preferences.first()[KEY_NOTIFIED_ALERT_KEYS] ?: emptySet()

    suspend fun addNotifiedAlertKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        appContext.weatherDataStore.edit { prefs ->
            val current = prefs[KEY_NOTIFIED_ALERT_KEYS] ?: emptySet()
            val updated = (current + keys).takeLast(100).toSet()
            prefs[KEY_NOTIFIED_ALERT_KEYS] = updated
        }
    }

    suspend fun setTempUnit(unit: Int) {
        require(unit == UNIT_CELSIUS || unit == UNIT_FAHRENHEIT)
        appContext.weatherDataStore.edit { it[KEY_TEMP_UNIT] = unit }
        WeatherWidgetUpdateNotifier.notifyDataChanged(appContext)
    }

    fun setTempUnitAsync(unit: Int) {
        scope.launch { setTempUnit(unit) }
    }

    suspend fun setStartupNoticeAccepted(accepted: Boolean) {
        appContext.weatherDataStore.edit { it[KEY_STARTUP_NOTICE_ACCEPTED] = accepted }
        WeatherWidgetUpdateNotifier.notifyDataChanged(
            context = appContext,
            requestRefresh = accepted,
        )
    }

    /** 直接读取持久化温度单位，避免后台任务拿到 [tempUnit] 的初始占位值。 */
    suspend fun readTempUnit(): Int =
        preferences.first()[KEY_TEMP_UNIT] ?: UNIT_CELSIUS

    /** 一次读取一组小组件的固定城市选择；缺失项表示自动选择。 */
    suspend fun readWidgetCitySelections(appWidgetIds: IntArray): Map<Int, String> {
        if (appWidgetIds.isEmpty()) return emptyMap()
        val snapshot = preferences.first()
        return appWidgetIds.asSequence().mapNotNull { appWidgetId ->
            snapshot[widgetCityKey(appWidgetId)]?.let { appWidgetId to it }
        }.toMap()
    }

    suspend fun setWidgetCitySelection(appWidgetId: Int, cityKey: String) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        appContext.weatherDataStore.edit { preferences ->
            preferences[widgetCityKey(appWidgetId)] = cityKey
        }
    }

    /** 读取小部件背景风格：0=全透明（默认），1=半透明磨砂，2=经典天空卡片。 */
    suspend fun readWidgetBackgroundStyle(appWidgetId: Int): Int {
        val key = widgetBgKey(appWidgetId)
        return preferences.first()[key] ?: WIDGET_BG_STYLE_TRANSPARENT
    }

    suspend fun setWidgetBackgroundStyle(appWidgetId: Int, style: Int) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        appContext.weatherDataStore.edit { preferences ->
            preferences[widgetBgKey(appWidgetId)] = style
        }
    }

    suspend fun removeWidgetCitySelections(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        appContext.weatherDataStore.edit { preferences ->
            appWidgetIds.forEach { appWidgetId ->
                preferences.remove(widgetCityKey(appWidgetId))
                preferences.remove(widgetBgKey(appWidgetId))
            }
        }
    }

    suspend fun remapWidgetCitySelections(oldIds: IntArray, newIds: IntArray) {
        if (oldIds.isEmpty() || oldIds.size != newIds.size) return
        appContext.weatherDataStore.edit { preferences ->
            oldIds.indices.forEach { index ->
                val oldKey = widgetCityKey(oldIds[index])
                val newKey = widgetCityKey(newIds[index])
                preferences[oldKey]?.let { selection -> preferences[newKey] = selection }
                preferences.remove(oldKey)

                val oldBgKey = widgetBgKey(oldIds[index])
                val newBgKey = widgetBgKey(newIds[index])
                preferences[oldBgKey]?.let { style -> preferences[newBgKey] = style }
                preferences.remove(oldBgKey)
            }
        }
    }

    companion object {
        val KEY_TEMP_UNIT = intPreferencesKey("temp_unit")
        // Keep the persisted key stable so existing development installs are not prompted again.
        private val KEY_STARTUP_NOTICE_ACCEPTED = booleanPreferencesKey("privacy_accepted")
        private val KEY_ALERT_NOTIFICATION_ENABLED = booleanPreferencesKey("alert_notification_enabled")
        private val KEY_NOTIFIED_ALERT_KEYS = stringSetPreferencesKey("notified_alert_keys")
        const val UNIT_CELSIUS = 1
        const val UNIT_FAHRENHEIT = 2
        private const val WIDGET_CITY_KEY_PREFIX = "weather_widget_city_"
        private const val WIDGET_BG_KEY_PREFIX = "weather_widget_bg_"

        const val WIDGET_BG_STYLE_TRANSPARENT = 0
        const val WIDGET_BG_STYLE_TRANSLUCENT = 1
        const val WIDGET_BG_STYLE_CLASSIC = 2

        @Volatile
        private var instance: WeatherSettings? = null

        fun getInstance(context: Context): WeatherSettings =
            instance ?: synchronized(this) {
                instance ?: WeatherSettings(context).also { instance = it }
            }

        private fun widgetCityKey(appWidgetId: Int) =
            stringPreferencesKey("$WIDGET_CITY_KEY_PREFIX$appWidgetId")

        private fun widgetBgKey(appWidgetId: Int) =
            intPreferencesKey("$WIDGET_BG_KEY_PREFIX$appWidgetId")
    }
}
