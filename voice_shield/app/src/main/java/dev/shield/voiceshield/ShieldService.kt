package dev.shield.voiceshield

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground-сервис с типом mediaPlayback: гарантирует, что акустическое
 * покрытие продолжает играть при погасшем экране и свёрнутом приложении
 * (Android 12+ ограничивает фоновое выполнение обычных компонентов).
 *
 * Вся логика шумогенерации и захвата микрофона живёт в ShieldViewModel —
 * сервис лишь удерживает процесс в foreground-состоянии и обновляет
 * уведомление статуса.
 */
class ShieldService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = MainActivity.viewModel ?: return START_NOT_STICKY.also { stopSelf() }
        val notification = vm.buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ShieldViewModel.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(ShieldViewModel.NOTIF_ID, notification)
        }
        // При убийстве сервиса системой щит должен честно погаснуть.
        return START_STICKY
    }

    override fun onDestroy() {
        MainActivity.viewModel?.let { vm ->
            MainActivity.onServiceDestroyed(vm, applicationContext)
        }
        super.onDestroy()
    }
}
