package com.example.wifiguardian

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Описание Wi-Fi сети, показываемое в списке.
 */
data class NetworkInfo(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val channel: Int,
    val capabilities: String,
    /** true — сеть защищена паролем (WPA/WPA2/WPA3/WEPI). */
    val secured: Boolean,
) {
    val isOpen: Boolean get() = !secured

    /** Человекочитаемая оценка уровня сигнала. */
    val signalLabel: String
        get() = when {
            rssiDbm >= -50 -> "Отличный"
            rssiDbm >= -60 -> "Хороший"
            rssiDbm >= -70 -> "Средний"
            else -> "Слабый"
        }

    val securityLabel: String
        get() = when {
            capabilities.contains("WPA3", true) -> "WPA3"
            capabilities.contains("WPA2", true) -> "WPA2"
            capabilities.contains("WPA", true) -> "WPA"
            capabilities.contains("WEP", true) -> "WEP (устаревший!)"
            else -> "Без пароля"
        }
}

/**
 * Обёртка над WifiManager для получения списка сетей вокруг.
 *
 * Начиная с Android 10 скан-результаты доступны только при включённой
 * геолокации/разрешении локаций, а с Android 13 — через разрешение
 * NEARBY_WIFI_DEVICES. Разрешения запрашивает Activity, здесь — только API.
 */
class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Включён ли Wi-Fi на устройстве. */
    val isWifiEnabled: Boolean get() = wifiManager.isWifiEnabled

    fun setWifiEnabled(enabled: Boolean) {
        // С Android 10 программа не может сама включать Wi-Fi —
        // пользователь должен сделать это в настройках или quick-settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enabled
        }
    }

    /**
     * Возвращает кэш последних результатов сканирования, сгруппированный по BSSID.
     * Пустой список — если скан ещё не выполнялся или нет разрешений.
     */
    @Suppress("MISSING_PERMISSION_CHECK")
    fun lastScan(): List<NetworkInfo> =
        wifiManager.scanResults.orEmpty().mapNotNull { it.toNetworkInfoOrNull() }
            .distinctBy { it.bssid }
            .sortedByDescending { it.rssiDbm }

    /**
     * Запускает фоновое сканирование; результат появится в [lastScan]
     * после broadcast ACTION_SCAN_RESULTS (подписка — в MainActivity).
     */
    fun startScan(): Boolean = wifiManager.startScan()

    private fun ScanResult.toNetworkInfoOrNull(): NetworkInfo? {
        val cleanSsid = SSID?.trim().orEmpty()
        if (cleanSsid.isEmpty() || BSSID.isNullOrEmpty()) return null
        val cap = capabilities.orEmpty()
        val secured = listOf("WPA", "WPA2", "WPA3", "WEP", "IE8021X")
            .any { cap.contains(it, ignoreCase = true) }
        return NetworkInfo(
            ssid = cleanSsid,
            bssid = BSSID,
            rssiDbm = level,
            channel = frequencyToChannel(frequency),
            capabilities = cap,
            secured = secured,
        )
    }

    companion object {
        /** Приближённый перевод частоты МГц в номер канала 2.4/5/6 ГГц. */
        fun frequencyToChannel(freqMHz: Int): Int = when {
            freqMHz in 2412..2472 -> (freqMHz - 2407) / 5
            freqMHz in 5170..5895 -> (freqMHz - 5000) / 5
            freqMHz in 5955..7115 -> (freqMHz - 5955) / 5 + 1   // 6 ГГц (WiFi-6E)
            else -> 0
        }
    }
}
