package com.example.wifiguardian

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion

/**
 * Управляет подключением/отключением ТОЛЬКО СОБСТВЕННОГО устройства
 * к выбранной Wi-Fi сети. Никакого воздействия на другие устройства
 * здесь нет и быть не может — это запрещено и платформой Android, и законом.
 *
 * Если вам нужно ограничить подключение других устройств, легальный путь —
 * настройки вашего роутера (MAC-фильтр, лимит клиентов, «детский контроль»).
 * См. [RouterControlHelp].
 */
class MyNetworkController(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Предлагает системе подключиться к сети [ssid] с паролем [password].
     * С Android 10 прямое добавление сетей через конфигурации закрыто для
     * сторонних приложений — используется механизм NetworkSuggestions:
     * система сама подключится, когда сеть появится в радиусе действия.
     *
     * @return true, если предложение принято системой.
     */
    fun connectThisDevice(ssid: String, password: String?, open: Boolean): Boolean {
        // В WifiNetworkSuggestion SSID должен быть в двойных кавычках.
        val quotedSsid = if (ssid.startsWith("\"")) ssid else "\"$ssid\""
        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(quotedSsid)

        if (open) {
            builder.setIsPersistentPreferred(true)
        } else {
            val pass = password.orEmpty()
            if (pass.length < 8) return false // WPA2 требует минимум 8 символов
            builder.setWpa2Passphrase(pass)
        }

        val status = wifiManager.addNetworkSuggestions(listOf(builder.build()))
        return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    /**
     * Отключает собственное устройство от текущей сети и удаляет
     * предложения подключения к ней. Устройство останется в сети оператора
     * (4G) или без интернета, пока пользователь не включит Wi-Fi снова.
     */
    fun disconnectThisDevice(ssidToRemove: String? = null) {
        if (ssidToRemove != null) {
            val current = wifiManager.networkSuggestions.orEmpty()
            val toRemove = current.filter { it.ssid?.trim('"') == ssidToRemove }
            if (toRemove.isNotEmpty()) {
                wifiManager.removeNetworkSuggestions(toRemove)
            }
        }
        @Suppress("DEPRECATION")
        wifiManager.disconnect()
    }

    /** SSID сети, к которой сейчас подключено устройство (или null). */
    @Suppress("DEPRECATION")
    fun connectedSsid(): String? =
        wifiManager.connectionInfo?.ssid?.takeIf { it != "<unknown ssid>" }?.trim('"')
}

/**
 * Справка о том, как ЛЕГАЛЬНО запретить подключение других устройств
 * к своей сети. Приложение не выполняет эти действия само — они
 * выполняются владельцем сети в интерфейсе его роутера.
 */
object RouterControlHelp {
    const val TEXT = """
Как законно ограничить подключение других устройств к ВАШЕЙ сети:

1. MAC-фильтрация (белый список) — разрешите подключение только известным
   устройствам. Есть почти в любом роутере (Keenetic, TP-Link, ASUS и т.д.).
2. Лимит количества клиентов / «Гостевая сеть» с изоляцией.
3. «Детский контроль» / расписание доступа по времени (Keenetic, Xiaomi и др.).
4. Смена пароля сети (WPA2/WPA3) — все устройства будут вынуждены
   переподключиться только с новым паролем.
5. На роутерах с OpenWrt/iOS-подобным API можно написать скрипт, который
   по вашему запросу блокирует конкретный MAC — но только в вашей сети.

⚠️ Воздействие на чужие сети (deauth-атаки, глушение каналов) незаконно:
ст. 272–274 УК РФ, 13.7 КоАП РФ, CFAA (США), Directive 2013/40/EU (ЕС).
Это приложение такие функции не реализует.
""".trimIndent()
}
