package com.invocta.data.local.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

// The resumable streaming and WorkManager progress pattern follows Google AI Edge Gallery's
// Apache-2.0 DownloadWorker: https://github.com/google-ai-edge/gallery
internal const val GEMMA_DOWNLOAD_URL = "gemma_download_url"
internal const val GEMMA_DOWNLOAD_TEMP_PATH = "gemma_download_temp_path"
internal const val GEMMA_DOWNLOAD_MODEL_PATH = "gemma_download_model_path"
internal const val GEMMA_DOWNLOAD_TOTAL_BYTES = "gemma_download_total_bytes"
internal const val GEMMA_DOWNLOAD_RECEIVED_BYTES = "gemma_download_received_bytes"
internal const val GEMMA_DOWNLOAD_ERROR = "gemma_download_error"

internal class LocalGemmaDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(GEMMA_DOWNLOAD_URL)
        val tempFile = inputData.getString(GEMMA_DOWNLOAD_TEMP_PATH)?.let(::File)
        val modelFile = inputData.getString(GEMMA_DOWNLOAD_MODEL_PATH)?.let(::File)
        val expectedBytes = inputData.getLong(GEMMA_DOWNLOAD_TOTAL_BYTES, 0L)
        if (url == null || tempFile == null || modelFile == null || expectedBytes <= 0L) {
            return@withContext failure("Gemma download configuration is incomplete.")
        }

        try {
            tempFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Could not create model storage.") }
            modelFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Could not create model storage.") }
            setForeground(foregroundInfo(0))

            if (modelFile.isFile && modelFile.length() == expectedBytes) return@withContext Result.success()

            if (tempFile.length() == expectedBytes) {
                promote(tempFile, modelFile)
                return@withContext Result.success()
            }
            if (tempFile.length() > expectedBytes) tempFile.delete()

            val offset = tempFile.length()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                    val message = "Model download returned HTTP $responseCode."
                    return@withContext if (responseCode >= 500) retryOrFailure(message) else failure(message)
                }

                val append = responseCode == HttpURLConnection.HTTP_PARTIAL
                if (append) {
                    val responseOffset = Regex("^bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if (responseOffset != offset) {
                        tempFile.delete()
                        return@withContext retryOrFailure("Download server returned an invalid byte range.")
                    }
                }

                var receivedBytes = if (append) offset else 0L
                val responseLength = connection.contentLengthLong
                val totalBytes = (if (append) offset + responseLength else responseLength)
                    .takeIf { it > 0L } ?: expectedBytes
                var lastProgressUpdate = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, append).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            receivedBytes += count
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 500L) {
                                setProgress(progressData(receivedBytes, totalBytes))
                                setForeground(foregroundInfo((receivedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)))
                                lastProgressUpdate = now
                            }
                        }
                    }
                }

                if (tempFile.length() != expectedBytes) {
                    if (tempFile.length() > expectedBytes) tempFile.delete()
                    return@withContext retryOrFailure("The model download was interrupted.")
                }
                promote(tempFile, modelFile)
                setProgress(progressData(expectedBytes, expectedBytes))
                Result.success()
            } finally {
                connection.disconnect()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: IOException) {
            retryOrFailure(error.message ?: "Gemma download failed.")
        } catch (error: Exception) {
            failure(error.message ?: "Gemma download failed.")
        }
    }

    private fun promote(tempFile: File, modelFile: File) {
        if (modelFile.exists() && !modelFile.delete()) error("Could not replace the incomplete model file.")
        if (!tempFile.renameTo(modelFile)) error("Could not save the downloaded model.")
    }

    private fun progressData(receivedBytes: Long, totalBytes: Long) =
        Data.Builder()
            .putLong(GEMMA_DOWNLOAD_RECEIVED_BYTES, receivedBytes)
            .putLong(GEMMA_DOWNLOAD_TOTAL_BYTES, totalBytes)
            .build()

    private fun failure(message: String) =
        Result.failure(Data.Builder().putString(GEMMA_DOWNLOAD_ERROR, message).build())

    private fun retryOrFailure(message: String) =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(message)

    private fun foregroundInfo(progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${LocalGemmaModelManager.MODEL_NAME}")
            .setContentText("The download can continue in the background.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "gemma_model_download"
        const val NOTIFICATION_ID = 4101
        const val MAX_RETRIES = 5
    }
}
