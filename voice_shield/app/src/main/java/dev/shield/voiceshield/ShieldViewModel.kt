package dev.shield.voiceshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.shield.voiceshield.MicSentinel.MicStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel: оркестрирует шумовое покрытие и «сентинел» микрофона.
 *
 * Логика защиты от прослушивания через сам смартфон:
 *  1) При активации щита мы сами захватываем AudioSource.MIC (MicSentinel).
 *     Android (6.0+) даёт эксклюзивный захват микрофона тому приложению,
 *     которое первым запросило запись в текущем audio-constraint-окне; все
 *     остальные приложения получают тихий/заглушённый сигнал. Таким образом взломанное
 *     приложение не сможет записать разговор, пока щит активен.
 *  2) Если захватить микрофон НЕ удалось — значит ресурс уже занят другим
 *     процессом (потенциальный прослушиватель). Щит честно об этом сообщает:
 *     в этом режиме акустическое покрытие всё равно работает, но статус
 *     помечается как WARNING.
 *  3) Индикатор доступа к микрофону (Android 12+) дублирует проверку на
 *     уровне системы — пользователь видит зелёную точку, если кто-то пишет.
 */
class ShieldViewModel : ViewModel() {

    private val engine = NoiseEngine()
    private var sentinel: MicSentinel? = null

    private val _shieldActive = MutableLiveData(false)
    val shieldActive: LiveData<Boolean> = _shieldActive

    private val _micStatus = MutableLiveData<MicStatus>(null)
    val micStatus: LiveData<MicStatus?> = _micStatus

    private val _log = MutableLiveData<List<String>>(emptyList())
    val log: LiveData<List<String>> = _log

    var noiseMode: NoiseEngine.Mode
        get() = engine.mode
        set(v) { engine.mode = v; addLog("Режим шума → $v") }

    var gain: Float
        get() = engine.gain
        set(v) { engine.gain = v.coerceIn(0.1f, 1f) }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = mutableListOf<String>()

    fun activate(ctx: Context) {
        if (_shieldActive.value == true) return
        val s = MicSentinel(ctx.applicationContext)
        sentinel = s
        // 1. Захватываем микрофон ДО запуска шума
        val status = s.tryOccupyMic()
        _micStatus.postValue(status)
        // 2. Включаем акустическое покрытие
        engine.start()
        _shieldActive.postValue(true)
        addLog(
            when (status) {
                MicStatus.OCCUPIED_BY_US ->
                    "ЩИТ АКТИВЕН: шум + микрофон захвачены (перехват записи заблокирован)"
                MicStatus.HIJACKED_POSSIBLY ->
                    "ВНИМАНИЕ: микрофон занят другим процессом! Шум включён, но возможен активный перехват"
            }
        )
        startNotification(ctx)
    }

    fun deactivate(ctx: Context) {
        if (_shieldActive.value != true) return
        engine.stop()
        sentinel?.releaseOccupation()
        runCatching { java.io.File(ctx.cacheDir, "sink.3gp").delete() }
        sentinel = null
        _micStatus.postValue(null)
        _shieldActive.postValue(false)
        addLog("Щит деактивирован")
        stopNotification(ctx)
    }

    /** Периодический аудит: держим ли мы микрофон по-прежнему. */
    fun reaudit() {
        val s = sentinel ?: return
        val st = s.audit()
        _micStatus.postValue(st)
        if (st == MicStatus.HIJACKED_POSSIBLY) {
            addLog("АУДИТ: потеряли захват микрофона — возможно, работает скрытое приложение!")
        } else {
            addLog("АУДИТ: микрофон по-прежнему под нашим контролем")
        }
    }

    private fun addLine(line: String) {
        synchronized(entries) {
            entries.add(0, "${timeFmt.format(Date())}  $line")
            while (entries.size > 50) entries.removeAt(entries.size - 1)
            _log.postValue(entries.toList())
        }
    }

    fun addLog(s: String) = addLine(s)

    // ---- foreground notification (шум должен играть при выключенном экране) ----

    private val CHANNEL_ID = "voice_shield_channel"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Voice Shield", NotificationManager.IMPORTANCE_LOW
            )
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun buildNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val active = _shieldActive.value == true
        val hijack = _micStatus.value == MicStatus.HIJACKED_POSSIBLY
        val text = when {
            !active -> "Готов к активации"
            hijack -> "⚠ Шум включён, микрофон перехвачен другим процессом!"
            else -> "Шумовое покрытие активно, микрофон под контролем"
        }
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Shield")
            .setContentText(text)
            .setOngoing(active)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startNotification(ctx: Context) {
        ensureChannel(ctx)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(ctx))
    }

    private fun stopNotification(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

    companion object { const val NOTIF_ID = 4711 }

    override fun onCleared() {
        engine.stop()
        sentinel?.releaseOccupation()
    }
}
