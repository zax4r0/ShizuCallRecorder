/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Manages the scrcpy-server execution and socket stream muxing for screen and audio recording.
 */
class ShellScrcpyPipeline {

    private val isRecordingActive = AtomicBoolean(false)
    private var scrcpyProcess: Process? = null
    private var serverSocket: LocalServerSocket? = null
    private var muxer: ScrcpyMediaMuxer? = null
    private var outputFile: ParcelFileDescriptor? = null

    private var videoSocket: LocalSocket? = null
    private var audioSocket: LocalSocket? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    companion object {
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = (1L shl 61) - 1L
    }

    fun start(
        context: Context,
        outputFile: ParcelFileDescriptor,
        serverPath: String,
        recordScreen: Boolean,
        audioSource: String,
        callback: IRecordingCallback
    ) {
        if (!isRecordingActive.compareAndSet(false, true)) {
            AppLogger.w("ShellScrcpyPipeline: Session already active.")
            return
        }

        try {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
        } catch (_: Throwable) {}

        this.outputFile = outputFile

        try {
            AppLogger.i("ShellScrcpyPipeline: Initializing scrcpy session (recordScreen=$recordScreen, audioSource=$audioSource)")

            val serverFile = File(serverPath)
            if (!serverFile.exists() || !ServerExtractor.verifyServerHash(serverFile)) {
                AppLogger.d("ShellScrcpyPipeline: Server jar missing or invalid at $serverPath, extracting...")
                val extracted = ServerExtractor.extractServer(context, serverPath)
                if (!extracted) {
                    AppLogger.e("ShellScrcpyPipeline: Failed to extract server jar to $serverPath")
                    isRecordingActive.set(false)
                    callback.onRecordingFailure(RecordingFailure.CAPTURE_START_FAILED)
                    return
                }
            }

            val (_, scidHex) = ScrcpyConfig.getRandomScid()
            val socketName = ScrcpyConfig.getSocketName(scidHex)
            serverSocket = LocalServerSocket(socketName)
            AppLogger.i("ShellScrcpyPipeline: Listening on abstract socket '$socketName'")

            val serverArgs = ScrcpyConfig.buildServerArgs(
                scidHex = scidHex,
                recordScreen = recordScreen,
                audioSource = audioSource
            )

            val cmd = mutableListOf(
                "app_process",
                "/",
                ScrcpyConfig.SERVER_MAIN_CLASS
            ).apply { addAll(serverArgs) }

            AppLogger.i("ShellScrcpyPipeline: Starting scrcpy server process: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
            pb.environment()["CLASSPATH"] = serverPath
            pb.redirectErrorStream(true)
            val process = pb.start()
            scrcpyProcess = process

            thread(name = "scrcpy-log-consumer") {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            AppLogger.d("scrcpy-server: $line")
                        }
                    }
                } catch (_: Exception) {}
            }

            val mediaMuxer = ScrcpyMediaMuxer(
                outputFileDescriptor = outputFile.fileDescriptor,
                recordVideo = recordScreen,
                recordAudio = true
            )
            muxer = mediaMuxer

            thread(name = "scrcpy-pipeline-accept") {
                try {
                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                } catch (_: Throwable) {}

                try {
                    if (recordScreen) {
                        // Connection 1: Video socket
                        val vSock = serverSocket?.accept()
                        videoSocket = vSock
                        AppLogger.i("ShellScrcpyPipeline: Video socket connected.")

                        // Connection 2: Audio socket
                        val aSock = serverSocket?.accept()
                        audioSocket = aSock
                        AppLogger.i("ShellScrcpyPipeline: Audio socket connected.")

                        if (vSock != null && isRecordingActive.get()) {
                            videoThread = thread(name = "scrcpy-video-reader") {
                                try {
                                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                                } catch (_: Throwable) {}
                                readPacketStream(vSock, isVideo = true, mediaMuxer)
                            }
                        }

                        if (aSock != null && isRecordingActive.get()) {
                            audioThread = thread(name = "scrcpy-audio-reader") {
                                try {
                                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                                } catch (_: Throwable) {}
                                readPacketStream(aSock, isVideo = false, mediaMuxer)
                            }
                        }
                    } else {
                        // Single connection: Audio socket
                        val aSock = serverSocket?.accept()
                        audioSocket = aSock
                        AppLogger.i("ShellScrcpyPipeline: Audio socket connected.")

                        if (aSock != null && isRecordingActive.get()) {
                            audioThread = thread(name = "scrcpy-audio-reader") {
                                try {
                                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                                } catch (_: Throwable) {}
                                readPacketStream(aSock, isVideo = false, mediaMuxer)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRecordingActive.get()) {
                        AppLogger.e("ShellScrcpyPipeline: Error accepting scrcpy connections: ${e.message}", e)
                        stop()
                        callback.onRecordingFailure(RecordingFailure.CAPTURE_START_FAILED)
                    }
                }
            }

        } catch (e: Exception) {
            AppLogger.e("ShellScrcpyPipeline: Error starting scrcpy pipeline: ${e.message}", e)
            stop()
            callback.onRecordingFailure(RecordingFailure.CAPTURE_START_FAILED)
        }
    }

    private fun readPacketStream(socket: LocalSocket, isVideo: Boolean, muxer: ScrcpyMediaMuxer) {
        val streamTag = if (isVideo) "Video" else "Audio"
        try {
            socket.inputStream.use { rawIn ->
                val dataIn = DataInputStream(rawIn)

                while (isRecordingActive.get()) {
                    val ptsAndFlags = dataIn.readLong()
                    val packetSize = dataIn.readInt()
                    if (packetSize <= 0 || packetSize > 10 * 1024 * 1024) {
                        AppLogger.w("ShellScrcpyPipeline: Invalid $streamTag packet size: $packetSize")
                        break
                    }
                    val payload = ByteArray(packetSize)
                    dataIn.readFully(payload)

                    val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                    val isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L
                    val ptsUs = ptsAndFlags and PTS_MASK

                    if (isVideo) {
                        muxer.writeVideoPacket(ptsUs, isConfig, isKeyFrame, payload)
                    } else {
                        muxer.writeAudioPacket(ptsUs, isConfig, payload)
                    }
                }
            }
        } catch (_: EOFException) {
            AppLogger.d("ShellScrcpyPipeline: $streamTag stream reached EOF.")
        } catch (e: Exception) {
            if (isRecordingActive.get()) {
                AppLogger.w("ShellScrcpyPipeline: Error reading $streamTag stream: ${e.message}")
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        if (!isRecordingActive.compareAndSet(true, false)) {
            return
        }

        AppLogger.i("ShellScrcpyPipeline: Stopping scrcpy recording pipeline...")

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            videoSocket?.close()
        } catch (_: Exception) {}
        videoSocket = null

        try {
            audioSocket?.close()
        } catch (_: Exception) {}
        audioSocket = null

        try {
            scrcpyProcess?.destroy()
        } catch (_: Exception) {}
        scrcpyProcess = null

        try {
            videoThread?.join(500)
            audioThread?.join(500)
        } catch (_: Exception) {}
        videoThread = null
        audioThread = null

        try {
            muxer?.close()
        } catch (_: Exception) {}
        muxer = null

        try {
            outputFile?.close()
        } catch (_: Exception) {}
        outputFile = null

        AppLogger.i("ShellScrcpyPipeline: Recording stopped and output finalized.")
    }
}
