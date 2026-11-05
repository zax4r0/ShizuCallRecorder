/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.content.Context
import androidx.annotation.WorkerThread
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object ServerExtractor {

    @WorkerThread
    fun ensureServerFile(context: Context, serverPath: String): Boolean {
        val file = File(serverPath)
        if (file.exists() && file.length() > 0 && verifyServerHash(file)) {
            AppLogger.d("Server file already present and verified at $serverPath")
            return true
        }
        AppLogger.d("Server file absent or hash mismatch, extracting to $serverPath...")
        return extractServer(context, serverPath)
    }

    fun extractServer(context: Context, destPath: String): Boolean {
        val destFile = File(destPath)
        return try {
            destFile.parentFile?.mkdirs()
            var extracted = false

            // Strategy 1: Open from packageCodePath (APK file)
            try {
                val apkPath = context.packageCodePath
                if (apkPath != null && File(apkPath).exists()) {
                    ZipFile(File(apkPath)).use { zip ->
                        val entry = zip.getEntry("assets/${BuildConfig.SCRCPY_SERVER_ASSET_NAME}")
                        if (entry != null) {
                            zip.getInputStream(entry).use { input ->
                                writeFile(destFile, input)
                                extracted = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("Extract via ZipFile failed: ${e.message}, trying AssetManager")
            }

            // Strategy 2: Open from AssetManager
            if (!extracted) {
                context.assets.open(BuildConfig.SCRCPY_SERVER_ASSET_NAME).use { input ->
                    writeFile(destFile, input)
                }
            }

            destFile.setReadable(true, false)
            destFile.setWritable(true, true)

            val verified = verifyServerHash(destFile)
            if (verified) {
                AppLogger.i("Server extracted and verified at $destPath (size=${destFile.length()})")
            } else {
                AppLogger.w("Server extracted at $destPath but hash verification FAILED")
            }
            verified
        } catch (e: Exception) {
            AppLogger.e("Server extraction failed: ${e.message}", e)
            false
        }
    }

    private fun writeFile(destFile: File, input: InputStream) {
        val tempFile = File("${destFile.absolutePath}.tmp")
        FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead = input.read(buffer)
            while (bytesRead > 0) {
                output.write(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
            output.flush()
        }
        if (destFile.exists()) {
            destFile.delete()
        }
        tempFile.renameTo(destFile)
    }

    fun verifyServerHash(serverFile: File): Boolean {
        if (!serverFile.exists() || serverFile.length() == 0L) return false
        val expectedSha = BuildConfig.SCRCPY_SERVER_SHA256
        if (expectedSha.isBlank()) {
            return serverFile.length() > 0
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            serverFile.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            hash.equals(expectedSha, ignoreCase = true)
        } catch (e: Exception) {
            AppLogger.w("Error calculating SHA-256 of server jar: ${e.message}")
            false
        }
    }
}
