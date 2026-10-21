package com.tablettouch.client

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * Парсер H.264 Annex-B из бинарных сообщений сервера + определение размера кадра
 * из SPS (чтобы создать декодер и подогнать SurfaceView пропорционально).
 */
object H264Utils {

    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_IDR = 5
    const val NAL_NON_IDR = 1

    /** Найти позиции start code (00 00 01 или 00 00 00 01) во всём буфере. */
    fun nalTypes(data: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() ||
                 (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()))
            ) {
                val scLen = if (data[i + 2] == 1.toByte()) 3 else 4
                val nalType = data[i + scLen].toInt() and 0x1F
                out += i to nalType
                i += scLen
            } else i++
        }
        return out
    }

    /**
     * Разбор минимально необходимого поля SPS: width/height в пикселях.
     * Использует упрощённый путь через profile-level-limits + exp-Golomb.
     */
    fun parseSpsSize(sps: ByteArray, offset: Int): Pair<Int, Int>? = try {
        val br = BitReader(sps, offset + 1 /* за nal header */)
        br.readBits(8)                       // profile_idc
        br.readBits(8)                       // constraint flags
        br.readBits(8)                       // level_idc
        readExpGolomb(br)                    // seq_parameter_set_id
        val profile = sps[offset + 1].toInt() and 0x1F
        if (profile == 100 || profile == 110 || profile == 122 ||
            profile == 244 || profile == 44 || profile == 83 ||
            profile == 86 || profile == 118 || profile == 128 ||
            profile == 138 || profile == 139 || profile == 134 || profile == 135
        ) {
            if (readExpGolomb(br) == 0L) br.readBits(4)   // chroma_format_idc=0 → separate_colour_plane_flag
            readExpGolomb(br)                             // bit_depth_luma_minus8
            readExpGolomb(br)                             // bit_depth_chroma_minus8
            br.readBits(1)                                // qpprime_y_zero_transform_bypass_flag
            if (br.readBits(1) == 1) {                    // seq_scaling_matrix_present_flag
                val cnt = if (profile != 118 && profile != 128) 8 else 12
                for (i in 0 until cnt) {
                    if (br.readBits(1) == 1) skipScalingList(br, if (i < 6) 16 else 64)
                }
            }
        }
        readExpGolomb(br)                    // log2_max_frame_num_minus4
        val picOrder = readExpGolomb(br)     // pic_order_cnt_type
        when (picOrder) {
            0L -> readExpGolomb(br)          // log2_max_pic_order_cnt_lsb_minus4
            1L -> {
                br.readBits(1); readExpGolomb(br); readExpGolomb(br)
                val n = readExpGolomb(br)
                repeat(n.toInt()) { readExpGolomb(br) }
            }
        }
        readExpGolomb(br)                    // max_num_ref_frames
        br.readBits(1)                       // gaps_in_frame_num_value_allowed_flag
        val wMb = readExpGolomb(br) + 1      // pic_width_in_mbs_minus1
        val hMap = readExpGolomb(br) + 1     // pic_height_in_map_units_minus1
        val frameMbs = br.readBits(1)        // frame_mbs_only_flag
        if (frameMbs == 0) br.readBits(1)    // mb_adaptive_frame_field_flag
        br.readBits(1)                       // direct_8x8_inference_flag
        val crop = br.readBits(1) == 1       // frame_cropping_flag
        var cl = 0L; var cr = 0L; var ct = 0L; var cb = 0L
        if (crop) { cl = readExpGolomb(br); cr = readExpGolomb(br); ct = readExpGolomb(br); cb = readExpGolomb(br) }
        val width = (wMb * 16 - (cl + cr) * 2).toInt()
        val height = ((2 - frameMbs) * hMap * 16 - (ct + cb) * (2 - frameMbs)).toInt()
        if (width > 0 && height > 0) width to height else null
    } catch (e: Exception) {
        Log.w("H264Utils", "SPS parse failed: ${e.message}")
        null
    }

    private fun skipScalingList(br: BitReader, size: Int) {
        var last = 8; var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                val delta = readSignedExpGolomb(br)
                next = (last + delta + 256) % 256
            }
            last = if (next == 0) last else next
        }
    }

    private fun readExpGolomb(br: BitReader): Long {
        var zeros = 0
        while (zeros < 32 && br.readBits(1) == 0) zeros++
        if (zeros == 0) return 0L
        return (1L shl zeros) - 1 + br.readBits(zeros)
    }

    private fun readSignedExpGolomb(br: BitReader): Int {
        val k = readExpGolomb(br)
        val v = ((k + 1) / 2).toInt()
        return if (k % 2 == 1L) v else -v
    }

    /** Ленький битовый читатель поверх ByteArray (MSB-first). */
    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var pos = startByte * 8

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data.getOrElse(pos / 8) { 0 }
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
    }
}
