/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.Closeable
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Muxes scrcpy video (H.264/H.265) and audio (AAC/Opus) packets into an MP4 container.
 */
class ScrcpyMediaMuxer(
    outputFileDescriptor: FileDescriptor,
    private val recordVideo: Boolean,
    private val recordAudio: Boolean,
    initialWidth: Int = 1080,
    initialHeight: Int = 2400
) : Closeable {

    private val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    private var videoWidth = initialWidth
    private var videoHeight = initialHeight
    private var videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC

    private var audioSampleRate = 48000
    private var audioChannels = 2
    private var audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC

    private var firstPtsUs: Long = -1L
    private var lastVideoPtsUs: Long = -1L
    private var lastAudioPtsUs: Long = -1L

    private data class QueuedSample(
        val isVideo: Boolean,
        val ptsUs: Long,
        val isKeyFrame: Boolean,
        val data: ByteArray
    )

    private val pendingSamples = ConcurrentLinkedQueue<QueuedSample>()

    @Synchronized
    fun setVideoFormat(mimeType: String, width: Int, height: Int) {
        videoMimeType = mimeType
        if (width > 0) videoWidth = width
        if (height > 0) videoHeight = height
        AppLogger.i("ScrcpyMediaMuxer: Video format configured: ${videoWidth}x${videoHeight}, format: $videoMimeType")
    }

    @Synchronized
    fun writeVideoPacket(ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean, data: ByteArray) {
        if (isConfig) {
            if (videoTrackIndex < 0) {
                val parsed = H264Utils.parseConfig(data, videoWidth, videoHeight)
                if (parsed.width != null && parsed.width > 0) videoWidth = parsed.width
                if (parsed.height != null && parsed.height > 0) videoHeight = parsed.height

                AppLogger.i("ScrcpyMediaMuxer: Adding video track: ${videoWidth}x${videoHeight}, format: $videoMimeType, SPS: ${parsed.sps.size}b, PPS: ${parsed.pps?.size ?: 0}b")
                val format = MediaFormat.createVideoFormat(videoMimeType, videoWidth, videoHeight).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(parsed.sps))
                    if (parsed.pps != null) {
                        setByteBuffer("csd-1", ByteBuffer.wrap(parsed.pps))
                    }
                }
                videoTrackIndex = muxer.addTrack(format)
                checkStartMuxer()
            }
            return
        }

        if (!isMuxerStarted) {
            pendingSamples.add(QueuedSample(isVideo = true, ptsUs = ptsUs, isKeyFrame = isKeyFrame, data = data))
            return
        }

        writeVideoSampleInternal(ptsUs, isKeyFrame, data)
    }

    @Synchronized
    fun writeAudioPacket(ptsUs: Long, isConfig: Boolean, data: ByteArray) {
        if (isConfig) {
            if (audioTrackIndex < 0) {
                if (data.size >= 2) {
                    val b0 = data[0].toInt() and 0xFF
                    val b1 = data[1].toInt() and 0xFF
                    val freqIndex = ((b0 and 0x07) shl 1) or ((b1 and 0x80) ushr 7)
                    val chanConfig = (b1 and 0x78) ushr 3
                    val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                    if (freqIndex in sampleRates.indices) {
                        audioSampleRate = sampleRates[freqIndex]
                    }
                    if (chanConfig in 1..8) {
                        audioChannels = chanConfig
                    }
                }
                AppLogger.i("ScrcpyMediaMuxer: Adding audio track with ${data.size} bytes CSD, sampleRate=$audioSampleRate, channels=$audioChannels")
                val format = MediaFormat.createAudioFormat(audioMimeType, audioSampleRate, audioChannels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(data.clone()))
                }
                audioTrackIndex = muxer.addTrack(format)
                checkStartMuxer()
            }
            return
        }

        if (!isMuxerStarted) {
            pendingSamples.add(QueuedSample(isVideo = false, ptsUs = ptsUs, isKeyFrame = false, data = data))
            return
        }

        writeAudioSampleInternal(ptsUs, data)
    }

    @Synchronized
    private fun checkStartMuxer() {
        if (isMuxerStarted) return

        val videoReady = !recordVideo || videoTrackIndex >= 0
        val audioReady = !recordAudio || audioTrackIndex >= 0

        if (videoReady && audioReady) {
            AppLogger.i("ScrcpyMediaMuxer: Starting MediaMuxer (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
            try {
                muxer.start()
                isMuxerStarted = true
            } catch (e: Exception) {
                AppLogger.e("ScrcpyMediaMuxer: Failed to start MediaMuxer: ${e.message}", e)
                return
            }

            // Drain queued packets
            while (pendingSamples.isNotEmpty()) {
                val sample = pendingSamples.poll() ?: break
                if (sample.isVideo) {
                    writeVideoSampleInternal(sample.ptsUs, sample.isKeyFrame, sample.data)
                } else {
                    writeAudioSampleInternal(sample.ptsUs, sample.data)
                }
            }
        }
    }

    private fun writeVideoSampleInternal(ptsUs: Long, isKeyFrame: Boolean, data: ByteArray) {
        if (videoTrackIndex < 0 || !isMuxerStarted) return

        if (firstPtsUs == -1L) {
            firstPtsUs = ptsUs
        }
        var normalizedPts = maxOf(0L, ptsUs - firstPtsUs)
        if (normalizedPts <= lastVideoPtsUs) {
            normalizedPts = lastVideoPtsUs + 1L
        }
        lastVideoPtsUs = normalizedPts

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = normalizedPts
            flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), bufferInfo)
        } catch (e: Exception) {
            AppLogger.e("ScrcpyMediaMuxer: Error writing video sample: ${e.message}")
        }
    }

    private fun writeAudioSampleInternal(ptsUs: Long, data: ByteArray) {
        if (audioTrackIndex < 0 || !isMuxerStarted) return

        if (firstPtsUs == -1L) {
            firstPtsUs = ptsUs
        }
        var normalizedPts = maxOf(0L, ptsUs - firstPtsUs)
        if (normalizedPts <= lastAudioPtsUs) {
            normalizedPts = lastAudioPtsUs + 1L
        }
        lastAudioPtsUs = normalizedPts

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = normalizedPts
            flags = 0
        }

        try {
            muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), bufferInfo)
        } catch (e: Exception) {
            AppLogger.e("ScrcpyMediaMuxer: Error writing audio sample: ${e.message}")
        }
    }

    @Synchronized
    override fun close() {
        AppLogger.i("ScrcpyMediaMuxer: Finalizing recording and closing muxer (isStarted=$isMuxerStarted)...")
        val started = isMuxerStarted
        isMuxerStarted = false
        try {
            if (started) {
                muxer.stop()
            }
        } catch (e: Exception) {
            AppLogger.w("ScrcpyMediaMuxer: Error stopping muxer: ${e.message}")
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            AppLogger.w("ScrcpyMediaMuxer: Error releasing muxer: ${e.message}")
        }
    }
}
