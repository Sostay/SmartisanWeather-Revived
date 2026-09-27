package com.smartisan.weather.appwidget

import com.smartisan.weather.data.model.SavedCity
import com.smartisan.weather.data.model.Weather
import com.smartisan.weather.data.settings.WeatherSettings
import java.time.Instant
import java.time.LocalTime

internal const val AUTO_CITY_SELECTION = "@auto-location"

internal object WeatherWidgetCityResolver {
    fun resolve(cities: List<SavedCity>, selection: String?): SavedCity? {
        val automatic = cities.firstOrNull(SavedCity::isLocationCity) ?: cities.firstOrNull()
        if (selection.isNullOrBlank() || selection == AUTO_CITY_SELECTION) return automatic
        return cities.firstOrNull { it.locationKey == selection } ?: automatic
    }

    fun isStaleSelection(cities: List<SavedCity>, selection: String?): Boolean =
        !selection.isNullOrBlank() &&
            selection != AUTO_CITY_SELECTION &&
            cities.none { it.locationKey == selection }
}

internal enum class WeatherWidgetUpdateState {
    IDLE,
    REFRESHING,
    FAILED,
}

internal enum class WeatherWidgetEmptyState {
    SETUP_REQUIRED,
    CITY_REQUIRED,
    WEATHER_LOADING,
    WEATHER_UNAVAILABLE,
}

/**
 * A single composition whose rhythm scales with the exact space assigned by the launcher.
 *
 * The original RemoteViews layout sized the hero temperature with `autoSizeTextType` inside
 * a fixed row, i.e. it shrank to fit whatever the launcher granted. Glance has no autosize,
 * so the same contract is reproduced here in Kotlin: measure the box, subtract the rows that
 * must survive (header, gap, condition, and whichever optional rows still fit), then turn
 * what is left into a type size. That keeps a 130dp strip from clipping the way a fixed
 * ladder of sizes did, and lets a 4x4 panel keep its artwork from looking under-filled.
 */
internal data class WeatherWidgetLayoutSpec(
    val isWide: Boolean,
    val outerPaddingDp: Int,
    val headerHeightDp: Int,
    val bodyGapDp: Int,
    val temperatureSp: Int,
    val currentColumnWidthDp: Int,
    val forecastSlots: Int,
    val showMeta: Boolean,
    val showWideAqi: Boolean,
    val detailMetricSlots: Int,
) {
    companion object {
        fun fromSize(widthDp: Int, heightDp: Int): WeatherWidgetLayoutSpec {
            val wide = widthDp >= WIDE_MIN_WIDTH_DP
            val paddingDp = if (widthDp < TIGHT_MAX_WIDTH_DP || heightDp < TIGHT_MAX_HEIGHT_DP) {
                TIGHT_PADDING_DP
            } else {
                ROOMY_PADDING_DP
            }
            val gapDp = if (wide) WIDE_BODY_GAP_DP else COMPACT_BODY_GAP_DP
            val metaBlockDp = if (heightDp >= META_MIN_HEIGHT_DP) META_BLOCK_DP else 0
            val bodyDp = heightDp - 2 * paddingDp - HEADER_HEIGHT_DP - gapDp

            // The wide column also carries the AQI line, so it gets the shorter budget; the
            // AQI line is dropped first when the panel is too short to hold it.
            val showWideAqi = wide && bodyDp - CONDITION_ROW_DP - AQI_ROW_DP >= MIN_HERO_DP
            val heroDp = bodyDp - CONDITION_ROW_DP - metaBlockDp -
                (if (showWideAqi) AQI_ROW_DP else 0)
            val temperatureSp = (heroDp / LINE_HEIGHT_RATIO)
                .toInt()
                .coerceIn(MIN_TEMPERATURE_SP, MAX_TEMPERATURE_SP)

            val leftoverDp = bodyDp - (temperatureSp * LINE_HEIGHT_RATIO).toInt() -
                CONDITION_ROW_DP - (if (showWideAqi) AQI_ROW_DP else 0) - metaBlockDp

            return WeatherWidgetLayoutSpec(
                isWide = wide,
                outerPaddingDp = paddingDp,
                headerHeightDp = HEADER_HEIGHT_DP,
                bodyGapDp = gapDp,
                temperatureSp = temperatureSp,
                currentColumnWidthDp = (widthDp * CURRENT_COLUMN_RATIO)
                    .toInt()
                    .coerceIn(CURRENT_COLUMN_MIN_DP, CURRENT_COLUMN_MAX_DP),
                forecastSlots = when {
                    widthDp >= FORECAST_FOUR_SLOT_WIDTH_DP -> 4
                    widthDp >= FORECAST_THREE_SLOT_WIDTH_DP -> 3
                    else -> 2
                },
                showMeta = heightDp >= META_MIN_HEIGHT_DP,
                showWideAqi = showWideAqi,
                detailMetricSlots = (leftoverDp / DETAIL_ROW_DP).coerceIn(
                    0,
                    MAX_DETAIL_METRIC_SLOTS,
                ),
            )
        }

        private const val WIDE_MIN_WIDTH_DP = 250
        private const val TIGHT_MAX_WIDTH_DP = 145
        private const val TIGHT_MAX_HEIGHT_DP = 130
        private const val META_MIN_HEIGHT_DP = 138
        private const val TIGHT_PADDING_DP = 10
        private const val ROOMY_PADDING_DP = 12
        private const val HEADER_HEIGHT_DP = 30
        private const val COMPACT_BODY_GAP_DP = 5
        private const val WIDE_BODY_GAP_DP = 3
        private const val CONDITION_ROW_DP = 18
        private const val AQI_ROW_DP = 12
        private const val META_BLOCK_DP = 22
        private const val DETAIL_ROW_DP = 14
        private const val MAX_DETAIL_METRIC_SLOTS = 3
        private const val LINE_HEIGHT_RATIO = 1.2f
        private const val MIN_TEMPERATURE_SP = 32
        private const val MAX_TEMPERATURE_SP = 44
        private const val MIN_HERO_DP = 38
        private const val CURRENT_COLUMN_RATIO = 0.33f
        private const val CURRENT_COLUMN_MIN_DP = 108
        private const val CURRENT_COLUMN_MAX_DP = 132
        private const val FORECAST_FOUR_SLOT_WIDTH_DP = 330
        private const val FORECAST_THREE_SLOT_WIDTH_DP = 280
    }
}

internal data class WeatherWidgetForecastSlot(
    val code: String,
    val temperature: String?,
    val hour: Int,
    val isNight: Boolean,
)

internal data class WeatherWidgetContent(
    val code: String,
    val currentTemperature: String?,
    val feelsLikeTemperature: String?,
    val humidity: String?,
    val windDirection: String?,
    val windSpeed: String?,
    val highTemperature: String?,
    val lowTemperature: String?,
    val isNight: Boolean,
    val aqiValue: Int?,
    val forecast: List<WeatherWidgetForecastSlot>,
)

internal object WeatherWidgetContentFactory {
    fun create(
        weather: Weather,
        tempUnit: Int,
        now: Instant = Instant.now(),
    ): WeatherWidgetContent {
        val isCelsius = tempUnit == WeatherSettings.UNIT_CELSIUS
        val daily = weather.dailyForecast.firstOrNull()
        val observe = weather.observe
        val forecast = weather.hourForecast.take(MAX_FORECAST_SLOTS).map { hour ->
            val temperature = if (isCelsius) hour.tempC else hour.tempF
            WeatherWidgetForecastSlot(
                code = hour.weatherCode.ifBlank { hour.code },
                temperature = temperature
                    .takeIf {
                        it != UNKNOWN_HOURLY_TEMPERATURE || hour.temp.isNotBlank()
                    }
                    ?.toString(),
                hour = hour.hour,
                isNight = hour.night,
            )
        }

        return WeatherWidgetContent(
            code = weather.themeCode,
            currentTemperature = (if (isCelsius) observe.tempC else observe.tempF)
                .temperatureOrNull(),
            feelsLikeTemperature = (if (isCelsius) observe.bodyFeelC else observe.bodyFeelF)
                .temperatureOrNull(),
            humidity = observe.humidity.ifBlank { weather.relativeHumidity }.valueOrNull(),
            windDirection = observe.wind.ifBlank { weather.windDirection }.valueOrNull(),
            windSpeed = observe.speed.ifBlank { weather.windSpeed }.valueOrNull(),
            highTemperature = (if (isCelsius) observe.highTempC else observe.highTempF)
                .temperatureOrNull()
                ?: daily?.let { if (isCelsius) it.highTempC else it.highTempF }?.toString(),
            lowTemperature = (if (isCelsius) observe.lowTempC else observe.lowTempF)
                .temperatureOrNull()
                ?: daily?.let { if (isCelsius) it.lowTempC else it.lowTempF }?.toString(),
            isNight = isNight(weather, weather.localTimeAt(now)),
            aqiValue = weather.airQuality.aqiInt.takeIf { it >= 0 }
                ?: observe.aqi.trim().toIntOrNull()?.takeIf { it >= 0 },
            forecast = forecast,
        )
    }

    private fun String.temperatureOrNull(): String? =
        trim().takeUnless { it.isEmpty() || it.equals(UNKNOWN_TEMPERATURE, ignoreCase = true) }

    /** Global AccuWeather cities carry no humidity, feels-like or wind; drop those instead of guessing. */
    private fun String.valueOrNull(): String? =
        trim().takeUnless { it.isEmpty() || it.equals(UNKNOWN_TEMPERATURE, ignoreCase = true) }

    private fun isNight(weather: Weather, now: LocalTime): Boolean {
        val observeSunTimes = listOf(
            weather.observe.curSunRise,
            weather.observe.curSunSet,
        ).mapNotNull(::extractTimeInMinutes)
        val sunTimes = if (observeSunTimes.size == 2) {
            observeSunTimes
        } else {
            TIME_REGEX.findAll(weather.dailyForecast.firstOrNull()?.sunriseAndSunset.orEmpty())
                .mapNotNull { extractTimeInMinutes(it.value) }
                .take(2)
                .toList()
        }
        if (sunTimes.size != 2) return weather.hourForecast.firstOrNull()?.night == true

        val nowInMinutes = now.hour * MINUTES_PER_HOUR + now.minute
        return nowInMinutes < sunTimes[0] || nowInMinutes >= sunTimes[1]
    }

    private fun extractTimeInMinutes(value: String): Int? {
        val match = TIME_REGEX.find(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return hour * MINUTES_PER_HOUR + minute
    }

    private const val MAX_FORECAST_SLOTS = 4
    private const val MINUTES_PER_HOUR = 60
    private const val UNKNOWN_TEMPERATURE = "UNKNOWN"
    private const val UNKNOWN_HOURLY_TEMPERATURE = -1
    private val TIME_REGEX = Regex("(?:^|\\D)([01]?\\d|2[0-3]):([0-5]\\d)(?:$|\\D)")
}
