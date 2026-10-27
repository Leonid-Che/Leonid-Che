package dev.shield.voiceshield

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

/**
 * «Сентинел микрофона».
 *
 * Android моделирует микрофон как разделяемый ресурс, но фоновые приложения
 * не могут свободно писать с AudioSource.MIC, когда другой процесс уже ведёт
 * запись в приоритетном окне capture. Мы намеренно занимаем микрофон своим
 * MediaRecorder на всё время работы щита: любое другое приложение (включая
 * потенциально взломанное) либо получит ошибку захвата, либо будет вытеснено
 * системой. Если же захватить микрофон НЕ удалось — значит он уже занят
 * другим процессом, что является тревожным признаком активного прослушивателя.
 */
class MicSentinel(private val context: Context) {

    private var held: MediaRecorder? = null

    /**
     * Пытаемся занять микрофон. Успех => держим ресурс занятым до
     * releaseOccupation(). Провал => кто-то уже пишет (HIJACKED_POSSIBLY).
     */
    fun tryOccupyMic(): MicStatus {
        if (held != null) return MicStatus.OCCUPIED_BY_US
        val recorder = newRecorder()
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            // Пишем «в никуда»: файл во внешнем хранилище приватен для нас;
            // /dev/null недоступен приложению, используем кэш контекста.
            val sink = java.io.File(context.cacheDir, "sink.3gp").absolutePath
            recorder.setOutputFile(sink)
            recorder.prepare()
            recorder.start()
            held = recorder
            MicStatus.OCCUPIED_BY_US
        } catch (e: Exception) {
            runCatching { recorder.release() }
            MicStatus.HIJACKED_POSSIBLY
        }
    }

    fun releaseOccupation() {
        runCatching { held?.stop() }
        runCatching { held?.release() }
        held = null
    }

    /**
     * Аудит: держим ли мы по-прежнему микрофон.
     *
     * Ограничение платформы: Android не даёт публичного API «жив ли мой
     * capture». Поэтому стратегия консервативная: если рекордер ещё создан —
     * считаем захват удержанным (OCCUPIED_BY_US). Если он был потерян
     * (например, система вытеснила нас при звонке и held очищен внешним
     * вызовом), аудит пытается перезахватить микрофон заново. Для детекции
     * тихого вытеснения пользователь может нажать «Проверить захват» после
     * полного аудита (fullAudit) или ориентироваться на системный индикатор
     * микрофона Android 12+.
     */
    fun audit(): MicStatus = if (held != null) MicStatus.OCCUPIED_BY_US else tryOccupyMic()

    /** Полный аудит: освобождаем и пробуем захватить заново. */
    fun fullAudit(): MicStatus {
        releaseOccupation()
        return tryOccupyMic()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    enum class MicStatus {
        /** Микрофон захвачен нами — фоновые записи других приложений блокируются. */
        OCCUPIED_BY_US,
        /** Микрофон занят другим процессом — возможен активный прослушиватель. */
        HIJACKED_POSSIBLY
    }
}
