package org.castberg.markdowncapture

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.castberg.markdowncapture.data.AppSettings
import org.castberg.markdowncapture.data.CaptureRecord
import org.castberg.markdowncapture.data.DEFAULT_TABS
import org.castberg.markdowncapture.data.LlmClient
import org.castberg.markdowncapture.data.ProviderCredential
import org.castberg.markdowncapture.data.SettingsRepository
import org.castberg.markdowncapture.data.TabConfig
import org.castberg.markdowncapture.data.isMistralOcr
import org.castberg.markdowncapture.data.providerByName
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
    val address: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val httpClient = OkHttpClient()

    val settings = settingsRepo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings()
    )

    val history = settingsRepo.history.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    sealed class Screen {
        object Camera : Screen()
        object Settings : Screen()
        data class Processing(val step: String) : Screen()
        data class ViewCapture(val record: CaptureRecord) : Screen()
        data class Error(val message: String, val imageBytes: ByteArray?) : Screen()
    }

    var screen by mutableStateOf<Screen>(Screen.Camera)
        private set

    var saveMessage by mutableStateOf<String?>(null)
        private set

    private val _finishEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finishEvent: SharedFlow<Unit> = _finishEvent.asSharedFlow()

    private var inactivityJob: Job? = null

    fun startInactivityTimer() {
        if (screen !is Screen.Camera) return
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            delay(2 * 60 * 1000L)
            _finishEvent.emit(Unit)
        }
    }

    fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    var activeTabIndex by mutableStateOf(0)
        private set

    fun onTabSelected(index: Int) { activeTabIndex = index }

    var isMultiMode by mutableStateOf(false)
        private set

    var pendingImageCount by mutableStateOf(0)
        private set

    var backgroundJobCount by mutableStateOf(0)
        private set

    private val _pendingImages = mutableListOf<ByteArray>()

    fun toggleMultiMode() {
        isMultiMode = !isMultiMode
        if (!isMultiMode) {
            _pendingImages.clear()
            pendingImageCount = 0
        }
    }

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            activeTabIndex = s.defaultTab.coerceIn(0, (s.tabs.size - 1).coerceAtLeast(0))
        }
    }

    private var lastImageBytes: ByteArray? = null

    fun navigateToSettings() { cancelInactivityTimer(); screen = Screen.Settings }
    fun navigateToCamera()   { saveMessage = null; screen = Screen.Camera; startInactivityTimer() }
    fun clearSaveMessage()   { saveMessage = null }

    fun viewCapture(record: CaptureRecord) { cancelInactivityTimer(); screen = Screen.ViewCapture(record) }

    // ── Credential helpers ────────────────────────────────────────────────────

    private fun resolveCredential(s: AppSettings, providerName: String): ProviderCredential =
        s.providerCredentials.find { it.providerName == providerName }
            ?: ProviderCredential(providerName, "")

    private fun resolveBaseUrl(credential: ProviderCredential): String =
        if (credential.providerName == "Custom") credential.customUrl
        else providerByName(credential.providerName).baseUrl

    private fun resolveFilenameCredential(s: AppSettings): ProviderCredential? {
        val name = s.filenameProviderName.ifBlank { null }
            ?: s.providerCredentials.firstOrNull()?.providerName
            ?: return null
        return resolveCredential(s, name)
    }

    private fun defaultPromptForTab(tab: TabConfig) = when (tab.id) {
        "transcribe" -> LlmClient.DEFAULT_TRANSCRIBE_PROMPT
        "detail"     -> LlmClient.DEFAULT_ANALYSIS_PROMPT
        else         -> LlmClient.DEFAULT_MEDIUM_ANALYSIS_PROMPT
    }

    private fun currentTab(s: AppSettings): TabConfig =
        s.tabs.getOrElse(activeTabIndex) { s.tabs.firstOrNull() ?: DEFAULT_TABS[0] }

    private fun tabAt(s: AppSettings, index: Int): TabConfig =
        s.tabs.getOrElse(index) { s.tabs.firstOrNull() ?: DEFAULT_TABS[0] }

    // ── Single capture ────────────────────────────────────────────────────────

    fun onImageCaptured(bytes: ByteArray) {
        cancelInactivityTimer()
        if (isMultiMode) {
            _pendingImages.add(bytes)
            pendingImageCount = _pendingImages.size
            return
        }
        lastImageBytes = bytes
        screen = Screen.Camera
        val location = getLastKnownLocation()
        backgroundJobCount++
        viewModelScope.launch {
            try {
                val s = settings.value
                val corrected = fixImageOrientation(bytes, s.imageQuality)
                lastImageBytes = corrected

                val (baseName, markdown, hasError) = processAndSaveSingleImage(corrected, activeTabIndex, s, location)

                settingsRepo.addRecord(CaptureRecord(
                    id        = System.currentTimeMillis().toString(),
                    baseName  = baseName,
                    markdown  = markdown,
                    timestamp = System.currentTimeMillis(),
                    folderUri = s.outputFolderUri,
                    hasError  = hasError
                ))

                saveMessage = if (hasError) "Saved with errors: $baseName" else "Saved: $baseName"
                startInactivityTimer()
            } catch (e: Exception) {
                saveMessage = "Save failed: ${e.message ?: "Unknown error"}"
            } finally {
                backgroundJobCount--
            }
        }
    }

    // ── Multi-image capture ───────────────────────────────────────────────────

    fun onSendMultiImages() {
        cancelInactivityTimer()
        val images = _pendingImages.toList()
        if (images.isEmpty()) return
        _pendingImages.clear()
        pendingImageCount = 0
        val location = getLastKnownLocation()
        backgroundJobCount++
        viewModelScope.launch {
            try {
                val s = settings.value
                val corrected = images.map { fixImageOrientation(it, s.imageQuality) }

                val tab = currentTab(s)

                var hasError = false
                var markdown = ""
                var ocrImages: List<Pair<String, ByteArray>> = emptyList()

                if (tab.noLlm) {
                    val filename = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
                    val baseName = saveMultipleFiles(corrected, filename, "", s, location)
                    settingsRepo.addRecord(CaptureRecord(
                        id        = System.currentTimeMillis().toString(),
                        baseName  = baseName,
                        markdown  = "",
                        timestamp = System.currentTimeMillis(),
                        folderUri = s.outputFolderUri,
                        hasError  = false
                    ))
                    saveMessage = "Saved: $baseName"
                    startInactivityTimer()
                    return@launch
                }

                val credential = resolveCredential(s, tab.providerName)
                val baseUrl    = resolveBaseUrl(credential)

                try {
                    if (isMistralOcr(tab.model)) {
                        val parts = mutableListOf<String>()
                        val imgs  = mutableListOf<Pair<String, ByteArray>>()
                        corrected.forEach { bytes ->
                            val result = LlmClient.ocrImage(baseUrl, credential.apiKey, bytes)
                            parts.add(result.markdown)
                            imgs.addAll(result.extractedImages)
                        }
                        markdown  = parts.joinToString("\n\n---\n\n")
                        ocrImages = imgs
                    } else {
                        val prompt = tab.systemPrompt.ifBlank { defaultPromptForTab(tab) }
                        markdown  = LlmClient.analyzeImages(baseUrl, credential.apiKey, corrected, tab.model, prompt, tab.maxTokens)
                    }
                } catch (e: Exception) {
                    markdown = "> Analysis failed: ${e.message}"
                    hasError = true
                }

                val filename = try { generateFilename(s, markdown) } catch (_: Exception) { "captured-${System.currentTimeMillis()}" }
                val baseName = saveMultipleFiles(corrected, filename, markdown, s, location, ocrImages, tab.model)

                settingsRepo.addRecord(CaptureRecord(
                    id        = System.currentTimeMillis().toString(),
                    baseName  = baseName,
                    markdown  = markdown,
                    timestamp = System.currentTimeMillis(),
                    folderUri = s.outputFolderUri,
                    hasError  = hasError
                ))

                saveMessage = if (hasError) "Saved with errors: $baseName" else "Saved: $baseName"
                startInactivityTimer()
            } catch (e: Exception) {
                saveMessage = "Save failed: ${e.message ?: "Unknown error"}"
            } finally {
                backgroundJobCount--
            }
        }
    }

    // ── Resubmit ──────────────────────────────────────────────────────────────

    fun resubmit(record: CaptureRecord, tabIndex: Int) {
        cancelInactivityTimer()
        val location = getLastKnownLocation()
        viewModelScope.launch {
            runCatching {
                val context: Context = getApplication()
                val s = settings.value
                screen = Screen.Processing("Loading image…")

                val folder = withContext(Dispatchers.IO) {
                    DocumentFile.fromTreeUri(context, Uri.parse(record.folderUri))
                } ?: error("Cannot access folder")
                val noteDir = folder.findFile("_resources")?.findFile(record.baseName)
                    ?: error("Resources folder not found")
                val imageFile = noteDir.findFile("image.jpg") ?: error("Image not found")
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(imageFile.uri)?.use { it.readBytes() }
                } ?: error("Cannot read image")

                screen = Screen.Processing("Analyzing image…")
                val corrected = fixImageOrientation(bytes, s.imageQuality)
                lastImageBytes = corrected

                val (baseName, markdown, hasError) = processAndSaveSingleImage(
                    corrected, tabIndex, s, location,
                    onProgress = { step -> screen = Screen.Processing(step) }
                )

                settingsRepo.addRecord(CaptureRecord(
                    id        = System.currentTimeMillis().toString(),
                    baseName  = baseName,
                    markdown  = markdown,
                    timestamp = System.currentTimeMillis(),
                    folderUri = s.outputFolderUri,
                    hasError  = hasError
                ))

                saveMessage = if (hasError) "Saved with errors: $baseName" else "Saved: $baseName"
                screen = Screen.Camera
                startInactivityTimer()
            }.onFailure { e ->
                screen = Screen.Error(e.message ?: "Unknown error", lastImageBytes)
            }
        }
    }

    // ── Common single-image processing ────────────────────────────────────────

    private suspend fun processAndSaveSingleImage(
        corrected: ByteArray,
        tabIndex: Int,
        s: AppSettings,
        location: LocationData?,
        onProgress: (String) -> Unit = {}
    ): Triple<String, String, Boolean> {
        val tab = tabAt(s, tabIndex)

        if (tab.noLlm) {
            val filename = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
            onProgress("Saving…")
            val baseName = saveFiles(corrected, filename, "", s, location)
            return Triple(baseName, "", false)
        }

        val credential = resolveCredential(s, tab.providerName)
        val baseUrl    = resolveBaseUrl(credential)

        var hasError = false
        var markdown = ""
        var ocrImages: List<Pair<String, ByteArray>> = emptyList()

        try {
            if (isMistralOcr(tab.model)) {
                val result = LlmClient.ocrImage(baseUrl, credential.apiKey, corrected)
                markdown  = result.markdown
                ocrImages = result.extractedImages
            } else {
                val prompt = tab.systemPrompt.ifBlank { defaultPromptForTab(tab) }
                markdown  = LlmClient.analyzeImage(baseUrl, credential.apiKey, corrected, tab.model, prompt, tab.maxTokens)
            }
        } catch (e: Exception) {
            markdown = "> Analysis failed: ${e.message}"
            hasError = true
        }

        onProgress("Naming document…")
        val filename = try { generateFilename(s, markdown) } catch (_: Exception) { "captured-${System.currentTimeMillis()}" }

        onProgress("Saving…")
        val baseName = saveFiles(corrected, filename, markdown, s, location, ocrImages, tab.model)

        return Triple(baseName, markdown, hasError)
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    private suspend fun generateFilename(s: AppSettings, markdown: String): String {
        val cred = resolveFilenameCredential(s) ?: return "captured-${System.currentTimeMillis()}"
        return LlmClient.generateFilename(
            resolveBaseUrl(cred),
            cred.apiKey,
            s.filenameModel.ifBlank { "gpt-4o-mini" },
            markdown
        )
    }

    private suspend fun fixImageOrientation(bytes: ByteArray, quality: Int): ByteArray =
        withContext(Dispatchers.IO) {
            runCatching {
                val orientation = ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> return@withContext bytes
                }
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext bytes
                val rotated = Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(rotation) }, true
                )
                source.recycle()
                ByteArrayOutputStream().also { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    rotated.recycle()
                }.toByteArray()
            }.getOrDefault(bytes)
        }

    private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name) ?: parent.createDirectory(name)
            ?: error("Cannot create directory: $name")

    private fun buildFrontmatter(location: LocationData?, model: String) = buildString {
        append("---\n")
        if (location != null) {
            append("latitude: ${location.latitude}\n")
            append("longitude: ${location.longitude}\n")
            location.name?.let    { append("place: \"$it\"\n") }
            location.address?.let { append("address: \"$it\"\n") }
            append("map: \"https://www.google.com/maps?q=${location.latitude},${location.longitude}\"\n")
        }
        if (model.isNotBlank()) append("model: \"$model\"\n")
        append("---\n\n")
    }

    private suspend fun saveFiles(
        bytes: ByteArray,
        filename: String,
        markdown: String,
        settings: AppSettings,
        locationData: LocationData?,
        ocrImages: List<Pair<String, ByteArray>> = emptyList(),
        model: String = ""
    ): String = withContext(Dispatchers.IO) {
        val context: Context = getApplication()
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.outputFolderUri))
            ?: error("Cannot access output folder")

        val dateStr  = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val baseName = "$dateStr-$filename"

        val noteFolder = getOrCreateDir(getOrCreateDir(folder, "_resources"), baseName)

        val imageFile = noteFolder.createFile("image/jpeg", "image.jpg")
            ?: error("Cannot create image file")
        context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(bytes) }

        var processedMarkdown = markdown
        ocrImages.forEachIndexed { index, (id, imageBytes) ->
            val imageName = "extracted-${index + 1}.jpg"
            noteFolder.createFile("image/jpeg", imageName)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(imageBytes) }
            }
            processedMarkdown = processedMarkdown.replace(
                Regex("!\\[[^\\]]*]\\(${Regex.escape(id)}\\)"),
                "![[_resources/$baseName/$imageName]]"
            )
        }

        val fullLocation = if (locationData != null) {
            try { reverseGeocode(locationData) } catch (_: Exception) { locationData }
        } else null

        val mdContent = "${buildFrontmatter(fullLocation, model)}$processedMarkdown\n\n![$baseName](_resources/$baseName/image.jpg)\n"
        val mdFile = folder.createFile("text/markdown", "$baseName.md")
            ?: error("Cannot create markdown file")
        context.contentResolver.openOutputStream(mdFile.uri)?.use { it.write(mdContent.toByteArray()) }

        baseName
    }

    private suspend fun saveMultipleFiles(
        imagesList: List<ByteArray>,
        filename: String,
        markdown: String,
        settings: AppSettings,
        locationData: LocationData?,
        ocrImages: List<Pair<String, ByteArray>> = emptyList(),
        model: String = ""
    ): String = withContext(Dispatchers.IO) {
        val context: Context = getApplication()
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.outputFolderUri))
            ?: error("Cannot access output folder")

        val dateStr  = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val baseName = "$dateStr-$filename"

        val noteFolder = getOrCreateDir(getOrCreateDir(folder, "_resources"), baseName)

        val imageRefs = StringBuilder()
        imagesList.forEachIndexed { index, imgBytes ->
            val imageName = "image-${index + 1}.jpg"
            noteFolder.createFile("image/jpeg", imageName)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(imgBytes) }
            }
            imageRefs.append("![$baseName-${index + 1}](_resources/$baseName/$imageName)\n")
        }

        var processedMarkdown = markdown
        ocrImages.forEachIndexed { index, (id, imageBytes) ->
            val imageName = "extracted-${index + 1}.jpg"
            noteFolder.createFile("image/jpeg", imageName)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(imageBytes) }
            }
            processedMarkdown = processedMarkdown.replace(
                Regex("!\\[[^\\]]*]\\(${Regex.escape(id)}\\)"),
                "![[_resources/$baseName/$imageName]]"
            )
        }

        val fullLocation = if (locationData != null) {
            try { reverseGeocode(locationData) } catch (_: Exception) { locationData }
        } else null

        val mdContent = "${buildFrontmatter(fullLocation, model)}$processedMarkdown\n\n$imageRefs"
        val mdFile = folder.createFile("text/markdown", "$baseName.md")
            ?: error("Cannot create markdown file")
        context.contentResolver.openOutputStream(mdFile.uri)?.use { it.write(mdContent.toByteArray()) }

        baseName
    }

    // ── Location ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): LocationData? {
        val context: Context = getApplication()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: return null
        return LocationData(loc.latitude, loc.longitude)
    }

    private suspend fun reverseGeocode(location: LocationData): LocationData = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=${location.latitude}&lon=${location.longitude}&zoom=18&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "MarkdownCapture/1.0")
            .build()

        val body = httpClient.newCall(request).execute().use { it.body?.string() }
            ?: return@withContext location
        val json = JSONObject(body)

        val name = json.optString("name").takeIf { it.isNotBlank() }
        val addr = json.optJSONObject("address")
        val address = addr?.let {
            listOfNotNull(
                it.optString("road").takeIf    { s -> s.isNotBlank() },
                (it.optString("city").takeIf   { s -> s.isNotBlank() }
                    ?: it.optString("town").takeIf    { s -> s.isNotBlank() }
                    ?: it.optString("village").takeIf { s -> s.isNotBlank() }),
                it.optString("country").takeIf { s -> s.isNotBlank() }
            ).joinToString(", ").takeIf { s -> s.isNotBlank() }
        }

        location.copy(name = name, address = address)
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    fun retry()  { lastImageBytes?.let { onImageCaptured(it) } }
    fun retake() { screen = Screen.Camera; startInactivityTimer() }

    fun saveSettings(new: AppSettings) {
        viewModelScope.launch { settingsRepo.save(new) }
    }
}
