/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.ILogCallback
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ShellScrcpyPipeline
import com.kitsumed.shizucallrecorder.services.shell.recording.ShellRecordingEngine
import com.kitsumed.shizucallrecorder.services.shell.utils.ShellWorkarounds
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ShellService runs inside the privileged shell process (UID 2000 or 0) managed by Shizuku.
 *
 * Shizuku requirements:
 *  - Must have a no-arg constructor AND a single-Context constructor (Shizuku v13+).
 *  - Must be annotated with [@Keep] so ProGuard/R8 does not remove/rename the class.
 *  - [destroy] must call [kotlin.system.exitProcess] to terminate the shell process when Shizuku asks.
 */
@Keep
class ShellService : IShellService.Stub {

    private val isInitialized = AtomicBoolean(false)
    private val recordingEngine by lazy { ShellRecordingEngine() }
    private val commandExecutor by lazy { ShellCommandExecutor() }
    private val scrcpyPipeline by lazy { ShellScrcpyPipeline() }

    private var context: Context? = null

    // ---- Shizuku-required constructors

    /**
     * No-arg constructor required by older versions of Shizuku.
     */
    @Keep
    constructor() : this(null)

    /**
     * Context constructor required by Shizuku v13+ for user-service instantiation.
     *
     * @param context [android.content.Context] provided by Shizuku, or null on older versions.
     */
    @Keep
    constructor(context: Context?) {
        this.context = context
    }
    
    /**
     * Ensure the shell service thread is initialized. Should generally be called in every method.
     *
     * Note: We do not use init because we prefer to have AppLogger to be initialized as remote to send backlogs,
     * which is impossible inside init scope
     */
    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            AppLogger.d("ShellService is running its first initialization, running as UID ${android.os.Process.myUid()}")
            ShellWorkarounds.apply()
            AppLogger.d("Applied workarounds to replicate a valid app (shell) ActivityThread context")
        } else {
            AppLogger.d("ShellService already initialized")
        }
    }

    // -------- IShellService AIDL implementation

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) = clearIdentity {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
        ensureInitialized() // Call after so AppLogger can send output via IPC
    }

    override fun startRecording(
        outputFile: ParcelFileDescriptor,
        plan: RecordingPlan,
        callback: IRecordingCallback
    ) = clearIdentity {
        ensureInitialized()
        recordingEngine.start(
            outputFile = outputFile,
            plan = plan,
            callback = callback
        )
    }

    override fun stopRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.stop()
        scrcpyPipeline.stop()
    }

    override fun pauseRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.pause()
    }

    override fun resumeRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.resume()
    }

    override fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantAppOpByPackage(packageName, opName, userProfileId)
    }

    override fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantAppOpByUid(uid, opName, userProfileId)
    }

    override fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantRole(packageName, roleName, userProfileId)
    }

    override fun startScrcpyRecording(
        outputFile: ParcelFileDescriptor,
        serverPath: String,
        recordScreen: Boolean,
        audioSource: String,
        callback: IRecordingCallback
    ) = clearIdentity {
        ensureInitialized()
        try {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
        } catch (_: Throwable) {}
        val ctx = this.context ?: com.kitsumed.shizucallrecorder.services.shell.utils.FakeContext.get()
        scrcpyPipeline.start(
            context = ctx,
            outputFile = outputFile,
            serverPath = serverPath,
            recordScreen = recordScreen,
            audioSource = audioSource,
            callback = callback
        )
    }

    override fun getForegroundPackage(): String? = clearIdentity {
        ensureInitialized()
        // Method 1: dumpsys window
        try {
            val process = ProcessBuilder("dumpsys", "window").start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.contains("mCurrentFocus") || l.contains("mFocusedApp")) {
                    val regex = Regex("""([a-zA-Z0-9_.]+)/""")
                    val match = regex.find(l)
                    if (match != null) {
                        val pkg = match.groupValues[1]
                        if (pkg.isNotEmpty() && !pkg.contains("StatusBar") && !pkg.contains("SystemUI") && !pkg.contains("launcher")) {
                            runCatching { process.destroy() }
                            return@clearIdentity pkg
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Method 2: dumpsys activity activities
        try {
            val process = ProcessBuilder("dumpsys", "activity", "activities").start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.contains("topResumedActivity=") || l.contains("ResumedActivity:")) {
                    val regex = Regex("""([a-zA-Z0-9_.]+)/""")
                    val match = regex.find(l)
                    if (match != null) {
                        val pkg = match.groupValues[1]
                        if (pkg.isNotEmpty() && !pkg.contains("StatusBar") && !pkg.contains("SystemUI") && !pkg.contains("launcher")) {
                            runCatching { process.destroy() }
                            return@clearIdentity pkg
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return@clearIdentity null
    }


    /**
     * Called by Shizuku when it wants to shut down this user service.
     * @see IShellService.destroy
     */
    override fun destroy() {
        AppLogger.i("ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }

    /**
     * Clears the calling identity and work source for the duration of the provided block, restoring them afterward.
     *
     * @param block The code block to execute with cleared identity and work source.
     * @return The result of the block execution.
     */
    private inline fun <T> clearIdentity(block: () -> T): T {
        /*
            NOTE: This is not always needed but here to be safe. Without it, AudioRecord createRecord() log warnings
            about mismatching UID ("createRecord uid 2000 tried to pass itself off as 10176") on AOSP, which may cause failure on other OEMs.
            In other cases, some permission checks fully fails, for example, for the AudioPlayback workaround via AudioPolicy, createAudioRecordSink
            checks the calling UID at a lower-level and fails.
         */
        val identityToken = Binder.clearCallingIdentity()
        val workSourceToken = Binder.clearCallingWorkSource()
        try {
            return block()
        } finally {
            Binder.restoreCallingWorkSource(workSourceToken)
            Binder.restoreCallingIdentity(identityToken)
        }
    }
}