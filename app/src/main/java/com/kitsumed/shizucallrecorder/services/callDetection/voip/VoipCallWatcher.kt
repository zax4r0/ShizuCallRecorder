/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.voip

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.services.RecordingDecisionEngine
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches for VoIP audio mode transitions (like Snapchat or WhatsApp calls) and triggers
 * screen and audio recording when a selected app is active.
 */
class VoipCallWatcher private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: VoipCallWatcher? = null

        fun getInstance(context: Context): VoipCallWatcher {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoipCallWatcher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val appPreferences = AppPreferences(appContext)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val isVoipSessionActive = AtomicBoolean(false)
    private var isWatcherRunning = false
    private var pollJob: Job? = null

    fun start() {
        if (isWatcherRunning) return
        isWatcherRunning = true
        AppLogger.i("VoipCallWatcher: Starting VoIP call watcher...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val executor = Executors.newSingleThreadExecutor()
                audioManager.addOnModeChangedListener(executor) { mode ->
                    onAudioModeChanged(mode)
                }
            } catch (e: Exception) {
                AppLogger.w("VoipCallWatcher: Failed to add OnModeChangedListener, falling back to polling: ${e.message}")
                startPolling()
            }
        } else {
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastMode = audioManager.mode
            while (isWatcherRunning) {
                delay(1000)
                val currentMode = audioManager.mode
                if (currentMode != lastMode) {
                    lastMode = currentMode
                    onAudioModeChanged(currentMode)
                }
            }
        }
    }

    private fun onAudioModeChanged(mode: Int) {
        AppLogger.d("VoipCallWatcher: Audio mode changed to $mode")
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            handleInCommunicationMode()
        } else if (mode == AudioManager.MODE_NORMAL) {
            handleNormalMode()
        }
    }

    private fun handleInCommunicationMode() {
        if (isVoipSessionActive.get()) return
        if (!appPreferences.isScreenRecordingEnabled()) return

        scope.launch {
            // Give the app a moment to switch foreground/audio
            delay(300)

            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) return@launch

            val detectedPackage = resolveForegroundPackage()
            AppLogger.d("VoipCallWatcher: Detected package during MODE_IN_COMMUNICATION: $detectedPackage")

            if (detectedPackage != null && appPreferences.isAppSelectedForScreenRecord(detectedPackage)) {
                if (isVoipSessionActive.compareAndSet(false, true)) {
                    val appName = try {
                        val pm = appContext.packageManager
                        val info = pm.getApplicationInfo(detectedPackage, 0)
                        pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        detectedPackage
                    }

                    AppLogger.i("VoipCallWatcher: Triggering screen + audio recording for $appName ($detectedPackage)")
                    val rawData = RawCallData(
                        rawPhoneNumber = "",
                        direction = CallDirection.OUTGOING,
                        osProvidedCallerName = "$appName Call",
                        packageName = detectedPackage,
                        isCallAudioVoIpBased = true,
                        isScreenRecording = true
                    )
                    RecordingDecisionEngine.getInstance(appContext).executeDecisionPipeline(rawData)
                }
            }
        }
    }

    private fun handleNormalMode() {
        if (isVoipSessionActive.compareAndSet(true, false)) {
            AppLogger.i("VoipCallWatcher: Audio mode returned to normal. Ending VoIP recording session.")
            RecordingDecisionEngine.getInstance(appContext).endRecordingSession()
        }
    }

    private suspend fun resolveForegroundPackage(): String? {
        if (!ShizukuConnectionManager.hasPermission(appContext)) return null

        return try {
            val shizukuManager = ShizukuConnectionManager(appContext)
            val shellService = shizukuManager.getShellService()
            shellService.foregroundPackage
        } catch (e: Exception) {
            AppLogger.w("VoipCallWatcher: Failed to resolve foreground package via ShellService: ${e.message}")
            null
        }
    }
}
