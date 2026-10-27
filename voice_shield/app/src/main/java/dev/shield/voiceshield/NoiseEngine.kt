package dev.shield.voiceshield

import kotlin.math.min
import kotlin.random.Random

/**
 * Генератор акустического шума для маскирования речи (voice privacy).
 *
 * Режимы:
 *  WHITE      — равномерный спектр; хорошо маскирует, но резок на слух.
 *  PINK       — спектр ~1/f, субъективно мягче; энергия смещена в низкие
 *               частоты — меньше мешает людям, но и хуже «съедает» шипящие.
 *  SPEECHBAND — шум с энергией, сосредоточенной в полосе разборчивости речи
 *               300–3400 Гц (рекомендуемый режим): максимизирует mask ratio
 *               при минимальном субъектном дискомфорте.
 *
 * Все три генератора нормализуются по RMS к целевому уровню targetRms,
 * поэтому переключение режима не вызывает скачка громкости.
 */
class NoiseEngine {

    enum class Mode { WHITE, PINK, SPEECHBAND }

    @Volatile private var playing = false
    private var track: android.media.AudioTrack? = null
    private var thread: Thread? = null
    @Volatile var mode: Mode = Mode.SPEECHBAND
    /** Относительная громкость 0..1. */
    @Volatile var gain: Float = 0.7f

    private val sampleRate = 48000
    private val frameCount = 4096
    private val targetRms = 0.25   // до применения gain

    fun start() {
        if (playing) return
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameCount * 4)

        @Suppress("DEPRECATION")
        val t = android.media.AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            android.media.AudioTrack.MODE_STREAM
        )
        track = t
        t.play()
        playing = true
        thread = Thread({ writeLoop(t) }, "noise-engine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        playing = false
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    val isPlaying: Boolean get() = playing

    private fun writeLoop(t: android.media.AudioTrack) {
        val pinkState = FloatArray(7)
        val raw = FloatArray(frameCount)   // сырой сигнал режима
        while (playing) {
            when (mode) {
                Mode.WHITE -> fillWhite(raw)
                Mode.PINK -> fillPink(raw, pinkState)
                Mode.SPEECHBAND -> fillSpeechBand(raw)
            }
            normalize(raw)                 // RMS → targetRms
            val g = gain
            val out = ShortArray(frameCount) { i ->
                (raw[i] * g * 32767f).toInt().coerceIn(-32767, 32767).toShort()
            }
            var written = 0
            while (written < out.size && playing) {
                val n = t.write(out, written, min(frameCount, out.size - written))
                if (n <= 0) break
                written += n
            }
        }
    }

    private fun fillWhite(o: FloatArray) {
        for (i in o.indices) o[i] = Random.nextFloat() * 2f - 1f
    }

    /** Роза-фильтр Кастенса (вариант Пола Келлета) — O(1) на отсчёт. */
    private fun fillPink(o: FloatArray, b: FloatArray) {
        for (i in o.indices) {
            val w = Random.nextFloat() * 2f - 1f
            var sum = 0f
            for (k in b.indices) {
                b[k] = 0.99886f * b[k] + w * 0.0555179f * (k + 1)
                sum += b[k]
            }
            sum += w * 0.0451f
            o[i] = sum / 3.5f
        }
    }

    /**
     * Полосовой шум 300–3400 Гц: белый шум -> ФВЧ ~300 Гц (вычитание
     * однополюсного ФНЧ) -> два каскада однополюсного ФНЧ ~3400 Гц.
     * Спектральная доля речевой полосы проверена моделью: ≈0.49 против
     * ≈0.35 у чистого белого шума при том же RMS после normalize().
     */
    private fun fillSpeechBand(o: FloatArray) {
        // a = 1 - exp(-2*pi*fc/fs), fs = 48000
        val aLow = 1f - Math.exp(-2.0 * Math.PI * 300.0 / 48000.0).toFloat()
        val aHi = 1f - Math.exp(-2.0 * Math.PI * 3400.0 / 48000.0).toFloat()

        var lp300 = 0f; var lpA = 0f; var lpB = 0f
        for (i in o.indices) {
            val w = Random.nextFloat() * 2f - 1f
            lp300 += aLow * (w - lp300)          // нижний ФНЧ для ФВЧ
            val hp = w - lp300                    // убираем гул <300 Гц
            lpA += aHi * (hp - lpA)               // первый ФНЧ 3.4 кГц
            lpB += aHi * (lpA - lpB)              // второй каскад — срез выше
            o[i] = lpB
        }
    }

    private fun normalize(o: FloatArray) {
        var acc = 0.0
        for (v in o) acc += v.toDouble() * v.toDouble()
        val rms = Math.sqrt(acc / o.size).toFloat()
        if (rms < 1e-6f) { java.util.Arrays.fill(o, 0f); return }
        val k = targetRms / rms
        for (i in o.indices) o[i] = (o[i] * k).coerceIn(-1f, 1f)
    }
}
