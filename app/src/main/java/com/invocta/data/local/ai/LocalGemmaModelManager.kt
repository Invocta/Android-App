package com.invocta.data.local.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

private val Context.gemmaModelDataStore by preferencesDataStore(name = "gemma_model_state")

sealed interface LocalGemmaModelStatus {
    data object Checking : LocalGemmaModelStatus
    data object NotDownloaded : LocalGemmaModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val message: String? = null) : LocalGemmaModelStatus
    data class Ready(val selected: Boolean) : LocalGemmaModelStatus
    data class Failed(val message: String) : LocalGemmaModelStatus
}

class LocalGemmaModelManager(context: Context) {
    companion object {
        const val MODEL_ID = "gemma-4-e2b-it"
        const val MODEL_NAME = "Gemma 4 E2B IT"
        const val MODEL_SIZE_BYTES = 2_583_085_056L
        const val MIN_DEVICE_MEMORY_GB = 8
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val DOWNLOAD_FILE_NAME = "$MODEL_FILE_NAME.download"
        private const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
        private const val WORK_NAME = "gemma_model_download"
        private const val REQUIRED_FREE_SPACE_BYTES = MODEL_SIZE_BYTES + 100L * 1024L * 1024L
        private val LEGACY_DOWNLOAD_ID = longPreferencesKey("download_id")
        private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        private val VALIDATED_MODEL_ID = stringPreferencesKey("validated_model_id")
        private val MODEL_URL = Uri.parse(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "$MODEL_REVISION/$MODEL_FILE_NAME?download=true"
        )
    }

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val downloadMutex = Mutex()
    private val _status = MutableStateFlow<LocalGemmaModelStatus>(LocalGemmaModelStatus.Checking)
    private var engine: Engine? = null
    val status: StateFlow<LocalGemmaModelStatus> = _status.asStateFlow()

    init {
        scope.launch { clearLegacyDownload() }
        scope.launch { observeDownload() }
        scope.launch { restoreState() }
    }

    suspend fun startDownload() = withContext(Dispatchers.IO) {
        try {
            downloadMutex.withLock {
                if (_status.value is LocalGemmaModelStatus.Downloading) return@withLock
                val model = modelFile() ?: return@withLock fail("App model storage is unavailable.")
                if (model.isFile && model.length() == MODEL_SIZE_BYTES && initializeModel().isSuccess) return@withLock
                val partial = downloadFile() ?: return@withLock fail("App model storage is unavailable.")
                if (StatFs(partial.parentFile!!.absolutePath).availableBytes < REQUIRED_FREE_SPACE_BYTES) {
                    return@withLock fail("At least 2.68 GB of free storage is required.")
                }
                if (model.exists()) {
                    closeEngine()
                    check(model.delete()) { "Could not prepare model storage. Try again." }
                    appContext.gemmaModelDataStore.edit { it.remove(VALIDATED_MODEL_ID); it.remove(SELECTED_MODEL_ID) }
                }
                val request = OneTimeWorkRequestBuilder<LocalGemmaDownloadWorker>()
                    .setInputData(workDataOf(
                        GEMMA_DOWNLOAD_URL to MODEL_URL.toString(),
                        GEMMA_DOWNLOAD_TEMP_PATH to partial.absolutePath,
                        GEMMA_DOWNLOAD_MODEL_PATH to model.absolutePath,
                        GEMMA_DOWNLOAD_TOTAL_BYTES to MODEL_SIZE_BYTES
                    ))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
                _status.value = LocalGemmaModelStatus.Downloading(partial.length(), MODEL_SIZE_BYTES)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error.message ?: "Could not start the Gemma download.")
        }
    }

    suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME).await()
        _status.value = readyStateIfInstalled() ?: LocalGemmaModelStatus.NotDownloaded
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME).await()
        closeEngine()
        val deleted = listOfNotNull(modelFile(), downloadFile()).all { !it.exists() || it.delete() }
        appContext.gemmaModelDataStore.edit { it.remove(SELECTED_MODEL_ID); it.remove(VALIDATED_MODEL_ID); it.remove(LEGACY_DOWNLOAD_ID) }
        _status.value = if (deleted) LocalGemmaModelStatus.NotDownloaded
        else LocalGemmaModelStatus.Failed("Could not remove the downloaded model. Try again.")
    }

    suspend fun useModel(): Result<Unit> {
        val result = initializeModel()
        if (result.isFailure) return result
        appContext.gemmaModelDataStore.edit { it[SELECTED_MODEL_ID] = MODEL_ID }
        _status.value = LocalGemmaModelStatus.Ready(selected = true)
        return Result.success(Unit)
    }

    suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            engineMutex.withLock {
                if (engine == null) {
                    val file = modelFile()
                    check(file != null && file.isFile && file.length() == MODEL_SIZE_BYTES) {
                        "Gemma 4 E2B is not downloaded or the model file is incomplete."
                    }
                    engine = createEngine(file)
                }
            }
            appContext.gemmaModelDataStore.edit { it[VALIDATED_MODEL_ID] = MODEL_ID }
            _status.value = LocalGemmaModelStatus.Ready(
                appContext.gemmaModelDataStore.data.first()[SELECTED_MODEL_ID] == MODEL_ID
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            fail(error.message ?: "Could not load Gemma 4 E2B.")
        }
    }

    suspend fun <T> withSelectedEngine(block: suspend (Engine) -> T): T {
        check(appContext.gemmaModelDataStore.data.first()[SELECTED_MODEL_ID] == MODEL_ID) {
            "Select Gemma 4 E2B in Settings to use local AI."
        }
        initializeModel().getOrThrow()
        return engineMutex.withLock { block(checkNotNull(engine)) }
    }

    private suspend fun clearLegacyDownload() {
        val oldId = appContext.gemmaModelDataStore.data.first()[LEGACY_DOWNLOAD_ID] ?: return
        appContext.getSystemService(DownloadManager::class.java).remove(oldId)
        appContext.gemmaModelDataStore.edit { it.remove(LEGACY_DOWNLOAD_ID) }
        // Keep the existing partial file so the WorkManager worker can resume it.
    }

    private suspend fun observeDownload() {
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collectLatest { infos ->
            val info = infos.lastOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                ?: infos.lastOrNull() ?: return@collectLatest
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    _status.value = LocalGemmaModelStatus.Downloading(
                        info.progress.getLong(GEMMA_DOWNLOAD_RECEIVED_BYTES, downloadFile()?.length() ?: 0L),
                        info.progress.getLong(GEMMA_DOWNLOAD_TOTAL_BYTES, MODEL_SIZE_BYTES),
                        if (info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED) "Waiting for a network connection…" else null
                    )
                }
                WorkInfo.State.SUCCEEDED -> {
                    val file = modelFile()
                    if (file?.isFile == true && file.length() == MODEL_SIZE_BYTES) {
                        if (initializeModel().isFailure) {
                            closeEngine()
                            file.delete()
                            appContext.gemmaModelDataStore.edit { it.remove(VALIDATED_MODEL_ID); it.remove(SELECTED_MODEL_ID) }
                            fail("Gemma could not start on this device. Check device compatibility, then retry the download.")
                        }
                    } else fail("Downloaded model size was incorrect. Delete it and try again.")
                }
                WorkInfo.State.FAILED -> fail(info.outputData.getString(GEMMA_DOWNLOAD_ERROR) ?: "Gemma download failed.")
                WorkInfo.State.CANCELLED -> if (_status.value != LocalGemmaModelStatus.Checking) {
                    _status.value = readyStateIfInstalled() ?: LocalGemmaModelStatus.NotDownloaded
                }
            }
        }
    }

    private suspend fun restoreState() {
        val file = modelFile()
        if (file?.isFile == true && file.length() == MODEL_SIZE_BYTES) {
            workManager.cancelUniqueWork(WORK_NAME).await()
            _status.value = LocalGemmaModelStatus.Checking
            val preferences = appContext.gemmaModelDataStore.data.first()
            if (preferences[VALIDATED_MODEL_ID] == MODEL_ID) {
                _status.value = LocalGemmaModelStatus.Ready(preferences[SELECTED_MODEL_ID] == MODEL_ID)
            } else initializeModel()
        } else if (file?.exists() == true) fail("Model setup was interrupted. Delete it and download again.")
        else _status.value = LocalGemmaModelStatus.NotDownloaded
    }

    private suspend fun readyStateIfInstalled(): LocalGemmaModelStatus.Ready? {
        val file = modelFile() ?: return null
        val prefs = appContext.gemmaModelDataStore.data.first()
        return if (file.isFile && file.length() == MODEL_SIZE_BYTES && prefs[VALIDATED_MODEL_ID] == MODEL_ID) {
            LocalGemmaModelStatus.Ready(prefs[SELECTED_MODEL_ID] == MODEL_ID)
        } else null
    }

    private fun fail(message: String) { _status.value = LocalGemmaModelStatus.Failed(message) }

    private suspend fun createEngine(file: File): Engine {
        val gpu = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU(), visionBackend = Backend.GPU(), cacheDir = appContext.cacheDir.absolutePath))
        return try { gpu.initialize(); gpu } catch (gpuError: Exception) {
            gpu.close()
            val cpu = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), visionBackend = Backend.CPU(), cacheDir = appContext.cacheDir.absolutePath))
            try { cpu.initialize(); cpu } catch (cpuError: Exception) { cpu.close(); cpuError.addSuppressed(gpuError); throw cpuError }
        }
    }

    private suspend fun closeEngine() = engineMutex.withLock { engine?.close(); engine = null }
    private fun modelFile() = modelDirectory()?.let { File(it, MODEL_FILE_NAME) }
    private fun downloadFile() = modelDirectory()?.let { File(it, DOWNLOAD_FILE_NAME) }
    private fun modelDirectory() = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "models") }
        ?.apply { if (!exists()) mkdirs() }?.takeIf { it.isDirectory }
}
