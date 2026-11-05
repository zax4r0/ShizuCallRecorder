/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import com.kitsumed.shizucallrecorder.BuildConfig
import java.security.SecureRandom
import java.util.Locale

object ScrcpyConfig {
    const val SCRCPY_VERSION: String = BuildConfig.SCRCPY_VERSION
    const val EXPECTED_SERVER_SHA256: String = BuildConfig.SCRCPY_SERVER_SHA256
    const val SERVER_MAIN_CLASS = "com.genymobile.scrcpy.Server"
    const val SERVER_JAR_PATH = "/data/local/tmp/scrcpy-5.0-server.jar"

    fun getServerPath(): String = SERVER_JAR_PATH

    fun buildServerArgs(
        scidHex: String,
        recordScreen: Boolean,
        audioSource: String = "mic-voice-communication",
        videoCodec: String = "h264",
        audioCodec: String = "aac",
        videoBitRate: Int = 4000000
    ): List<String> {
        val args = mutableListOf(
            SCRCPY_VERSION,
            "scid=$scidHex",
            "log_level=info",
            "video=$recordScreen",
            "audio=true",
            "control=false",
            "cleanup=false",
            "tunnel_forward=false",
            "send_dummy_byte=false",
            "send_device_meta=false",
            "send_frame_meta=true",
            "send_stream_meta=false",
            "audio_source=$audioSource",
            "audio_codec=$audioCodec"
        )
        if (recordScreen) {
            args.add("video_codec=$videoCodec")
            if (videoBitRate > 0) {
                args.add("video_bit_rate=$videoBitRate")
            }
            args.add("max_fps=30")
        }
        return args
    }

    fun getRandomScid(): Pair<Int, String> {
        val random = SecureRandom()
        val scidInt = random.nextInt(0x7FFFFFFF)
        val scidHex = String.format(Locale.ENGLISH, "%08x", scidInt)
        return Pair(scidInt, scidHex)
    }

    fun getSocketName(scidHex: String): String {
        return "scrcpy_$scidHex"
    }
}
