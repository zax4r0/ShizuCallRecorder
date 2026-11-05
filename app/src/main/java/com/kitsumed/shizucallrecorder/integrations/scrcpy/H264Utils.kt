/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

object H264Utils {

    data class SpsPps(
        val sps: ByteArray,
        val pps: ByteArray?,
        val width: Int?,
        val height: Int?
    )

    fun parseConfig(data: ByteArray, fallbackWidth: Int = 1080, fallbackHeight: Int = 2400): SpsPps {
        val startIndices = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                startIndices.add(i)
                i += 4
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                startIndices.add(i)
                i += 3
            } else {
                i++
            }
        }

        var sps: ByteArray = data
        var pps: ByteArray? = null

        if (startIndices.size >= 2) {
            sps = data.copyOfRange(startIndices[0], startIndices[1])
            pps = data.copyOfRange(startIndices[1], data.size)
        }

        val dimensions = parseSpsDimensions(sps)
        return SpsPps(
            sps = sps,
            pps = pps,
            width = dimensions?.first ?: fallbackWidth,
            height = dimensions?.second ?: fallbackHeight
        )
    }

    private fun parseSpsDimensions(spsBytes: ByteArray): Pair<Int, Int>? {
        return try {
            var start = 0
            while (start < spsBytes.size - 3) {
                if (spsBytes[start] == 0.toByte() && spsBytes[start + 1] == 0.toByte() && spsBytes[start + 2] == 1.toByte()) {
                    start += 3
                    break
                }
                if (spsBytes[start] == 0.toByte() && spsBytes[start + 1] == 0.toByte() && spsBytes[start + 2] == 0.toByte() && spsBytes[start + 3] == 1.toByte()) {
                    start += 4
                    break
                }
                start++
            }
            if (start >= spsBytes.size) return null

            val reader = BitReader(spsBytes, start + 1)
            val profileIdc = reader.readBits(8)
            reader.skipBits(8)
            reader.skipBits(8)
            reader.readExpGolomb()

            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chromaFormatIdc = reader.readExpGolomb()
                if (chromaFormatIdc == 3) {
                    reader.skipBits(1)
                }
                reader.readExpGolomb()
                reader.readExpGolomb()
                reader.skipBits(1)
                val seqScalingMatrixPresentFlag = reader.readBits(1)
                if (seqScalingMatrixPresentFlag != 0) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (j in 0 until count) {
                        val seqScalingListPresentFlag = reader.readBits(1)
                        if (seqScalingListPresentFlag != 0) {
                            val size = if (j < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            for (k in 0 until size) {
                                if (nextScale != 0) {
                                    val deltaScale = reader.readSignedExpGolomb()
                                    nextScale = (lastScale + deltaScale + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            reader.readExpGolomb()
            val picOrderCntType = reader.readExpGolomb()
            if (picOrderCntType == 0) {
                reader.readExpGolomb()
            } else if (picOrderCntType == 1) {
                reader.skipBits(1)
                reader.readSignedExpGolomb()
                reader.readSignedExpGolomb()
                val numRefFramesInPicOrderCntCycle = reader.readExpGolomb()
                for (j in 0 until numRefFramesInPicOrderCntCycle) {
                    reader.readSignedExpGolomb()
                }
            }

            reader.readExpGolomb()
            reader.skipBits(1)
            val picWidthInMbsMinus1 = reader.readExpGolomb()
            val picHeightInMapUnitsMinus1 = reader.readExpGolomb()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.skipBits(1)
            }
            reader.skipBits(1)
            val frameCroppingFlag = reader.readBits(1)
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (frameCroppingFlag != 0) {
                cropLeft = reader.readExpGolomb()
                cropRight = reader.readExpGolomb()
                cropTop = reader.readExpGolomb()
                cropBottom = reader.readExpGolomb()
            }

            val width = ((picWidthInMbsMinus1 + 1) * 16) - ((cropLeft + cropRight) * 2)
            val height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16) - ((cropTop + cropBottom) * 2)
            if (width > 0 && height > 0) Pair(width, height) else null
        } catch (_: Exception) {
            null
        }
    }

    private class BitReader(private val bytes: ByteArray, startOffset: Int) {
        private var byteOffset = startOffset
        private var bitOffset = 0

        fun readBits(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                if (byteOffset >= bytes.size) return 0
                val b = bytes[byteOffset].toInt() and 0xFF
                val bit = (b ushr (7 - bitOffset)) and 1
                result = (result shl 1) or bit
                bitOffset++
                if (bitOffset == 8) {
                    bitOffset = 0
                    byteOffset++
                    if (byteOffset + 1 < bytes.size &&
                        bytes[byteOffset - 2] == 0.toByte() &&
                        bytes[byteOffset - 1] == 0.toByte() &&
                        bytes[byteOffset] == 3.toByte()
                    ) {
                        byteOffset++
                    }
                }
            }
            return result
        }

        fun skipBits(n: Int) {
            for (i in 0 until n) {
                bitOffset++
                if (bitOffset == 8) {
                    bitOffset = 0
                    byteOffset++
                    if (byteOffset + 1 < bytes.size &&
                        bytes[byteOffset - 2] == 0.toByte() &&
                        bytes[byteOffset - 1] == 0.toByte() &&
                        bytes[byteOffset] == 3.toByte()
                    ) {
                        byteOffset++
                    }
                }
            }
        }

        fun readExpGolomb(): Int {
            var leadingZeros = 0
            while (readBits(1) == 0 && leadingZeros < 32) {
                leadingZeros++
            }
            return if (leadingZeros == 0) 0 else ((1 shl leadingZeros) - 1) + readBits(leadingZeros)
        }

        fun readSignedExpGolomb(): Int {
            val codeNum = readExpGolomb()
            val sign = if (codeNum % 2 == 1) 1 else -1
            return sign * ((codeNum + 1) / 2)
        }
    }
}
