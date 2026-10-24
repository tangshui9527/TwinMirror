package com.example.remotescreen

/**
 * Minimal H.264 SPS parser to extract coded resolution.
 * Needed to keep touch coordinates correct after the target screen rotates
 * (the scrcpy server restarts its encoder and emits a new SPS).
 */
object SpsParser {

    private class BitReader(private val data: ByteArray, private var pos: Int = 0) {
        fun readBit(): Int = ((data[pos shr 3].toInt() shr (7 - (pos and 7))) and 1).also { pos++ }
        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or readBit()
            return v
        }
        fun readUe(): Int {
            var zeroBits = 0
            while (readBit() == 0) {
                zeroBits++
                if (zeroBits > 31) throw IllegalArgumentException("bad ue")
            }
            if (zeroBits == 0) return 0
            return (1 shl zeroBits) - 1 + readBits(zeroBits)
        }
        fun readSe(): Int {
            val k = readUe()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val delta = r.readSe()
                nextScale = (lastScale + delta + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    /** Parse WxH from an SPS NAL (with Annex-B start code). Returns null on failure. */
    fun parseResolution(sps: ByteArray): Pair<Int, Int>? {
        return try {
            // find start of NAL payload (after 000001 or 00000001)
            var i = 0
            if (sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 1.toByte()) {
                i = 3
            } else if (sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 0.toByte() && sps[3] == 1.toByte()) {
                i = 4
            } else {
                return null
            }
            val r = BitReader(sps, (i + 1) * 8) // skip start code + 1 NAL header byte

            val profileIdc = r.readBits(8)
            r.readBits(8) // constraint flags + reserved
            r.readBits(8) // level_idc
            r.readUe() // seq_parameter_set_id

            var chromaFormatIdc = 1 // implicit 4:2:0 outside the high-profile branch
            var subWidthC = 2
            var subHeightC = 2
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                chromaFormatIdc = r.readUe()
                when (chromaFormatIdc) {
                    0 -> { subWidthC = 1; subHeightC = 1 }
                    1 -> { subWidthC = 2; subHeightC = 2 }
                    2 -> { subWidthC = 2; subHeightC = 1 }
                    3 -> { subWidthC = 1; subHeightC = 1 }
                }
                if (chromaFormatIdc == 3) r.readBit() // separate_colour_plane_flag
                r.readUe() // bit_depth_luma_minus8
                r.readUe() // bit_depth_chroma_minus8
                r.readBit() // qpprime_y_zero_transform_bypass
                if (r.readBit() == 1) { // seq_scaling_matrix_present
                    val listCount = if (chromaFormatIdc != 3) 8 else 12
                    for (j in 0 until listCount) {
                        if (r.readBit() == 1) {
                            skipScalingList(r, if (j < 6) 16 else 64)
                        }
                    }
                }
            }

            r.readUe() // log2_max_frame_num_minus4
            val pocType = r.readUe()
            if (pocType == 0) {
                r.readUe() // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                r.readBit() // delta_pic_order_always_zero_flag
                r.readSe() // offset_for_non_ref_pic
                r.readSe() // offset_for_top_to_bottom_field
                val numRef = r.readUe()
                for (j in 0 until numRef) r.readSe()
            }
            r.readUe() // max_num_ref_frames
            r.readBit() // gaps_in_frame_num_value_allowed

            val picWidthInMbs = r.readUe() + 16
            val picHeightInMapUnits = r.readUe() + 16
            val frameMbsOnly = r.readBit() == 1
            if (!frameMbsOnly) r.readBit() // mb_adaptive_frame_field
            r.readBit() // direct_8x8_inference

            var width = picWidthInMbs * 16
            var height = picHeightInMapUnits * 16 * (if (frameMbsOnly) 1 else 2)

            if (r.readBit() == 1) { // frame_cropping_flag
                val left = r.readUe()
                val right = r.readUe()
                val top = r.readUe()
                val bottom = r.readUe()
                // H.264 7.4.2.1.1: crop offsets are in units of SubWidthC x SubHeightC
                // (x (2 - frame_mbs_only_flag)); 1 x (2 - frame_mbs_only_flag) for mono
                val frameMul = 2 - (if (frameMbsOnly) 1 else 0)
                val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
                val cropUnitY = (if (chromaFormatIdc == 0) 1 else subHeightC) * frameMul
                width -= (left + right) * cropUnitX
                height -= (top + bottom) * cropUnitY
            }

            if (width <= 0 || height <= 0) return null
            Pair(width, height)
        } catch (_: Exception) {
            null
        }
    }
}
