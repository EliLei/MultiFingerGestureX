package com.fan.edgex.config

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import com.fan.edgex.IShellCallback
import com.fan.edgex.IShellExecutor
import com.topjohnwu.superuser.Shell
import java.io.IOException

class ShellExecutorService : Service() {

    private val stub = object : IShellExecutor.Stub() {
        override fun execute(command: String, runAsRoot: Boolean, callback: IShellCallback?) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "")
                return
            }
            Thread {
                try {
                    if (runAsRoot) {
                        val result = Shell.cmd(command).exec()
                        val output = if (result.isSuccess) {
                            result.out.joinToString("\n").trim()
                        } else {
                            result.err.joinToString("\n").trim()
                        }
                        callback?.onResult(result.isSuccess, output)
                    } else {
                        val process = ProcessBuilder("sh", "-c", command)
                            .redirectErrorStream(true)
                            .start()
                        process.outputStream.close()
                        val output = process.inputStream.bufferedReader().readText().trim()
                        val exitCode = process.waitFor()
                        callback?.onResult(exitCode == 0, output)
                    }
                } catch (e: Exception) {
                    callback?.onResult(false, e.message.orEmpty())
                }
            }.start()
        }

        override fun savePngToGallery(
            png: ParcelFileDescriptor?,
            displayName: String?,
            callback: IShellCallback?,
        ) {
            if (!isSystemServerCaller()) {
                callback?.onResult(false, "")
                png?.close()
                return
            }
            Thread {
                var insertedUri: android.net.Uri? = null
                try {
                    if (png == null) throw IOException("PNG pipe is null")
                    val now = System.currentTimeMillis()
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            displayName?.takeIf { it.isNotBlank() } ?: "Screenshot_$now.png",
                        )
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                        put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
                        put(MediaStore.Images.Media.DATE_TAKEN, now)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = contentResolver
                    insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore insert returned null")
                    ParcelFileDescriptor.AutoCloseInputStream(png).use { input ->
                        val output = resolver.openOutputStream(insertedUri, "w")
                            ?: throw IOException("MediaStore output stream is null")
                        output.use { input.copyTo(it) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val publishValues = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        resolver.update(insertedUri, publishValues, null, null)
                    }
                    callback?.onResult(true, insertedUri.toString())
                } catch (e: Exception) {
                    insertedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                    callback?.onResult(false, e.message.orEmpty())
                    png?.close()
                }
            }.start()
        }
    }

    override fun onBind(intent: Intent): IBinder = stub

    private fun isSystemServerCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callerUid)
        return callerUid == Process.SYSTEM_UID && callerPackages?.contains("android") == true
    }
}
