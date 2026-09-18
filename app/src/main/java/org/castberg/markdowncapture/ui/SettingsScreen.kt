package org.castberg.markdowncapture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.castberg.markdowncapture.BuildConfig
import org.castberg.markdowncapture.data.AppSettings
import org.castberg.markdowncapture.data.LlmClient
import org.castberg.markdowncapture.data.ModelOption
import org.castberg.markdowncapture.data.PROVIDERS
import org.castberg.markdowncapture.data.ProviderCredential
import org.castberg.markdowncapture.data.TabConfig
import org.castberg.markdowncapture.data.isMistralOcr
import org.castberg.markdowncapture.data.providerByName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    var credentials     by remember(settings.providerCredentials) { mutableStateOf(settings.providerCredentials) }
    var tabs            by remember(settings.tabs)                { mutableStateOf(settings.tabs) }
    var defaultTab      by remember(settings.defaultTab)          { mutableStateOf(settings.defaultTab) }
    var outputFolderUri by remember(settings.outputFolderUri)     { mutableStateOf(settings.outputFolderUri) }
    var imageQuality    by remember(settings.imageQuality)        { mutableStateOf(settings.imageQuality.toFloat()) }

    var showAddProvider by remember { mutableStateOf(false) }

    fun buildSettings() = AppSettings(
        providerCredentials  = credentials,
        tabs                 = tabs,
        defaultTab           = defaultTab,
        outputFolderUri      = outputFolderUri,
        imageQuality         = imageQuality.toInt()
    )

    BackHandler { onSave(buildSettings()); onNavigateBack() }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputFolderUri = it.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { onSave(buildSettings()); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── API Keys ──────────────────────────────────────────────────────
            Text("API Keys", style = MaterialTheme.typography.titleMedium)

            if (credentials.isEmpty()) {
                Text(
                    "No providers configured. Add one to get started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                credentials.forEach { credential ->
                    ProviderCredentialRow(
                        credential = credential,
                        onDelete   = { credentials = credentials.filter { it !== credential } }
                    )
                }
            }

            OutlinedButton(
                onClick  = { showAddProvider = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Provider")
            }

            // ── Tab sections ──────────────────────────────────────────────────
            tabs.forEachIndexed { index, tab ->
                key(tab.id) {
                    TabSection(
                        tab         = tab,
                        credentials = credentials,
                        canDelete   = tabs.size > 1,
                        onTabChange = { updated ->
                            tabs = tabs.toMutableList().also { it[index] = updated }
                        },
                        onDelete = {
                            tabs = tabs.filterIndexed { i, _ -> i != index }
                            if (defaultTab >= tabs.size) defaultTab = (tabs.size - 1).coerceAtLeast(0)
                        }
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    val newId = "custom-${System.currentTimeMillis()}"
                    val firstProvider = credentials.firstOrNull()?.providerName
                        ?: tabs.firstOrNull()?.providerName
                        ?: "OpenAI"
                    tabs = tabs + TabConfig(
                        id           = newId,
                        name         = "Custom",
                        providerName = firstProvider,
                        model        = providerByName(firstProvider).models.firstOrNull()?.id ?: "",
                        systemPrompt = "",
                        maxTokens    = 2000
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Tab")
            }

            // ── General ───────────────────────────────────────────────────────
            HorizontalDivider()
            Text("General", style = MaterialTheme.typography.titleMedium)

            Text("Default tab on launch", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = defaultTab == index,
                        onClick  = { defaultTab = index },
                        shape    = SegmentedButtonDefaults.itemShape(index, tabs.size),
                        label    = { Text(tab.name) }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose Output Folder")
                }
                if (outputFolderUri.isNotBlank()) {
                    val label = runCatching {
                        Uri.parse(outputFolderUri).lastPathSegment?.substringAfterLast(':') ?: outputFolderUri
                    }.getOrDefault(outputFolderUri)
                    Text("Folder: $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("No folder selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("JPEG Quality: ${imageQuality.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = imageQuality, onValueChange = { imageQuality = it }, valueRange = 50f..100f, steps = 9)
            }

            HorizontalDivider()

            Button(
                onClick  = { onSave(buildSettings()); onNavigateBack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            val buildDate = remember {
                java.time.Instant.ofEpochMilli(BuildConfig.BUILD_TIME)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
            }
            Text(
                "v${BuildConfig.VERSION_NAME} · $buildDate",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddProvider) {
        val alreadyAdded = credentials.map { it.providerName }.toSet()
        AddProviderSheet(
            alreadyAdded = alreadyAdded,
            onDismiss    = { showAddProvider = false },
            onAdd        = { credential ->
                credentials = credentials.filter { it.providerName != credential.providerName } + credential
                showAddProvider = false
            }
        )
    }
}

// ── Provider credential row ───────────────────────────────────────────────────

@Composable
private fun ProviderCredentialRow(
    credential: ProviderCredential,
    onDelete: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    val maskedKey = if (credential.apiKey.length > 8)
        credential.apiKey.take(4) + "••••" + credential.apiKey.takeLast(4)
    else "••••••••"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(credential.providerName, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (showKey) credential.apiKey else maskedKey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (credential.providerName == "Custom" && credential.customUrl.isNotBlank()) {
                Text(
                    credential.customUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { showKey = !showKey }) {
            Icon(
                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle key visibility"
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Remove provider")
        }
    }
}

// ── Add provider sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderSheet(
    alreadyAdded: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (ProviderCredential) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val available = PROVIDERS.filter { it.name == "Custom" || it.name !in alreadyAdded }
    var selectedProvider by remember { mutableStateOf(available.firstOrNull()?.name ?: "OpenAI") }
    var apiKey           by remember { mutableStateOf("") }
    var customUrl        by remember { mutableStateOf("") }
    var showKey          by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add Provider", style = MaterialTheme.typography.titleMedium)

            if (available.isEmpty()) {
                Text("All providers are already configured.", style = MaterialTheme.typography.bodyMedium)
            } else {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value         = selectedProvider,
                        onValueChange = {},
                        readOnly      = true,
                        label         = { Text("Provider") },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier      = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        available.forEach { p ->
                            DropdownMenuItem(
                                text    = { Text(p.name) },
                                onClick = { selectedProvider = p.name; expanded = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value               = apiKey,
                    onValueChange       = { apiKey = it },
                    label               = { Text("API Key") },
                    modifier            = Modifier.fillMaxWidth(),
                    singleLine          = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon        = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide" else "Show"
                            )
                        }
                    }
                )

                if (selectedProvider == "Custom") {
                    OutlinedTextField(
                        value         = customUrl,
                        onValueChange = { customUrl = it },
                        label         = { Text("Base URL") },
                        placeholder   = { Text("http://localhost:11434/v1") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick  = {
                            onAdd(ProviderCredential(
                                providerName = selectedProvider,
                                apiKey       = apiKey,
                                customUrl    = if (selectedProvider == "Custom") customUrl else ""
                            ))
                        },
                        enabled = apiKey.isNotBlank() && (selectedProvider != "Custom" || customUrl.isNotBlank())
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

// ── Per-tab settings section ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSection(
    tab: TabConfig,
    credentials: List<ProviderCredential>,
    canDelete: Boolean,
    onTabChange: (TabConfig) -> Unit,
    onDelete: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val matchedCredential = credentials.find { it.providerName == tab.providerName }

    var fetchedModels   by remember(tab.providerName, matchedCredential?.apiKey) { mutableStateOf<List<ModelOption>?>(null) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError     by remember { mutableStateOf<String?>(null) }

    suspend fun doFetch() {
        val cred = matchedCredential ?: return
        if (cred.providerName == "Custom") return
        isLoadingModels = true
        modelsError     = null
        fetchedModels   = null
        runCatching {
            val url = providerByName(cred.providerName).baseUrl
            LlmClient.fetchModels(url, cred.apiKey, cred.providerName)
        }.onSuccess { fetchedModels = it }.onFailure { modelsError = it.message }
        isLoadingModels = false
    }

    LaunchedEffect(tab.providerName, matchedCredential?.apiKey) { doFetch() }

    val effectiveModels = when {
        matchedCredential?.providerName == "Custom" -> emptyList()
        fetchedModels != null -> fetchedModels!!
        else -> providerByName(tab.providerName).models
    }

    val promptDisabled = isMistralOcr(tab.model)
    val defaultPrompt = when (tab.id) {
        "transcribe" -> LlmClient.DEFAULT_TRANSCRIBE_PROMPT
        "detail"     -> LlmClient.DEFAULT_ANALYSIS_PROMPT
        else         -> LlmClient.DEFAULT_MEDIUM_ANALYSIS_PROMPT
    }

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value         = tab.name,
            onValueChange = { onTabChange(tab.copy(name = it)) },
            label         = { Text("Tab name") },
            modifier      = Modifier.weight(1f),
            singleLine    = true
        )
        if (isLoadingModels) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (matchedCredential != null && matchedCredential.providerName != "Custom") {
            IconButton(
                onClick  = { coroutineScope.launch { doFetch() } },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh models", modifier = Modifier.size(20.dp))
            }
        }
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete tab")
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("No LLM (image only, timestamp filename)", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked         = tab.noLlm,
            onCheckedChange = { onTabChange(tab.copy(noLlm = it)) }
        )
    }

    if (!tab.noLlm) {
        if (credentials.isEmpty()) {
            Text(
                "Add providers in the API Keys section above",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ProviderDropdown(
                credentials = credentials,
                selected    = tab.providerName,
                onSelect    = { newProvider ->
                    val firstModel = providerByName(newProvider).models.firstOrNull()?.id ?: tab.model
                    onTabChange(tab.copy(providerName = newProvider, model = firstModel))
                }
            )
        }

        if (modelsError != null) {
            Text(
                "Could not load models: $modelsError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (effectiveModels.isNotEmpty()) {
            ModelDropdown(
                selectedModel = tab.model,
                models        = effectiveModels,
                label         = "Model",
                onSelect      = { onTabChange(tab.copy(model = it)) }
            )
        } else {
            OutlinedTextField(
                value         = tab.model,
                onValueChange = { onTabChange(tab.copy(model = it)) },
                label         = { Text("Model ID") },
                placeholder   = { Text("e.g. gpt-4o") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true
            )
        }

        OutlinedTextField(
            value         = tab.systemPrompt,
            onValueChange = { onTabChange(tab.copy(systemPrompt = it)) },
            label         = { Text(if (promptDisabled) "System Prompt (N/A for Mistral OCR)" else "System Prompt (blank = default)") },
            modifier      = Modifier.fillMaxWidth().height(120.dp),
            maxLines      = 5,
            enabled       = !promptDisabled
        )
        if (!promptDisabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onTabChange(tab.copy(systemPrompt = defaultPrompt)) }) {
                    Text("Reset to default")
                }
            }
        }

        OutlinedTextField(
            value         = tab.maxTokens.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { onTabChange(tab.copy(maxTokens = it)) } },
            label         = { Text("Max Tokens") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}


// ── Provider dropdown ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    credentials: List<ProviderCredential>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value         = selected.ifBlank { "Select provider" },
            onValueChange = {},
            readOnly      = true,
            label         = { Text("Provider") },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier      = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            credentials.forEach { cred ->
                DropdownMenuItem(
                    text    = { Text(cred.providerName) },
                    onClick = { onSelect(cred.providerName); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ── Model dropdown ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selectedModel: String,
    models: List<ModelOption>,
    label: String = "Model",
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = models.find { it.id == selectedModel }?.label ?: selectedModel

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value         = displayLabel,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier      = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { option ->
                DropdownMenuItem(
                    text    = { Text(option.label) },
                    onClick = { onSelect(option.id); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
