package com.example.wifiguardian

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Главный экран: список сетей вокруг + панель команд.
 *
 * Команды, которые реально выполняет приложение:
 *  • «Сканировать» — обновить список сетей;
 *  • «Подключить это устройство» — предложить системе подключиться к сети;
 *  • «Отключить это устройство» — разорвать собственное Wi-Fi-соединение;
 *  • «Как ограничить других в СВОЕЙ сети» — правовая/техническая справка.
 *
 * Команд воздействия на чужие устройства приложение не реализует (см. README).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scanner: WifiScanner
    private lateinit var controller: MyNetworkController
    private lateinit var adapter: NetworkAdapter

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) refreshList()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts
            .RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) runScan()
            else toast("Без разрешений список сетей недоступен")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanner = WifiScanner(this)
        controller = MyNetworkController(this)
        adapter = NetworkAdapter(onNetworkClicked = ::showNetworkActions)

        findViewById<RecyclerView>(R.id.networksList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            requestPermissionsIfNeeded { runScan() }
        }

        findViewById<TextView>(R.id.disclaimerText).text = RouterControlHelp.TEXT
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(
            this, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
    }

    // ---------------- Разрешения ----------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded(after: () -> Unit) {
        if (hasAllPermissions()) after() else permissionLauncher.launch(requiredPermissions())
    }

    // ---------------- Сканирование ----------------

    private fun runScan() {
        if (!scanner.isWifiEnabled) {
            toast("Включите Wi-Fi в настройках системы")
            scanner.setWifiEnabled(true)
            return
        }
        toast("Сканирование…")
        scanner.startScan() // результат придёт через broadcast → refreshList()
        refreshList()       // на случай свежего кэша
    }

    private fun refreshList() {
        val list = scanner.lastScan()
        adapter.submit(list)
        findViewById<TextView>(R.id.emptyHint).visibility =
            if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- Панель действий по сети ----------------

    private fun showNetworkActions(info: NetworkInfo) {
        val items = arrayOf(
            "ℹ️  Параметры сети",
            "📶 Подключить ЭТО устройство к сети",
            "🔌 Отключить ЭТО устройство от Wi-Fi",
            "🛡️ Как ограничить других устройств в СВОЕЙ сети (справка)",
        )
        AlertDialog.Builder(this)
            .setTitle(info.ssid)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDetails(info)
                    1 -> askPasswordAndConnect(info)
                    2 -> {
                        controller.disconnectThisDevice(info.ssid)
                        toast("Собственное устройство отключено от Wi-Fi")
                    }
                    3 -> showLegalHelp()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showDetails(info: NetworkInfo) {
        AlertDialog.Builder(this)
            .setTitle(info.ssid)
            .setMessage(
                """
                BSSID: ${info.bssid}
                Канал: ${info.channel}
                Уровень сигнала: ${info.rssiDbm} дБм (${info.signalLabel})
                Шифрование: ${info.securityLabel}
                """.trimIndent()
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun askPasswordAndConnect(info: NetworkInfo) {
        if (!info.secured) {
            val ok = controller.connectThisDevice(info.ssid, null, open = true)
            toast(if (ok) "Предложение подключения добавлено" else "Система отклонила предложение")
            return
        }
        val input = EditText(this).apply { hint = "Пароль WPA2/WPA3 (мин. 8 символов)" }
        AlertDialog.Builder(this)
            .setTitle("Пароль сети ${info.ssid}")
            .setView(input)
            .setPositiveButton("Подключить") { _, _ ->
                val ok = controller.connectThisDevice(info.ssid, input.text.toString(), open = false)
                toast(if (ok) "Предложение подключения добавлено"
                      else "Ошибка: пароль слишком короткий или система отклонила")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showLegalHelp() {
        AlertDialog.Builder(this)
            .setTitle("Легальное управление СВОЕЙ сетью")
            .setMessage(RouterControlHelp.TEXT)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ---------------- RecyclerView adapter ----------------

class NetworkAdapter(
    private val onNetworkClicked: (NetworkInfo) -> Unit,
) : RecyclerView.Adapter<NetworkAdapter.VH>() {

    private val items = mutableListOf<NetworkInfo>()

    fun submit(newItems: List<NetworkInfo>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ssid: TextView = view.findViewById(R.id.itemSsid)
        val meta: TextView = view.findViewById(R.id.itemMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val info = items[position]
        holder.ssid.text = info.ssid
        holder.meta.text = "${info.securityLabel} • канал ${info.channel} • " +
                "${info.rssiDbm} дБм (${info.signalLabel})"
        holder.itemView.setOnClickListener { onNetworkClicked(info) }
    }

    override fun getItemCount(): Int = items.size
}
