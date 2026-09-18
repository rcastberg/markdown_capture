package org.castberg.markdowncapture.data

data class ProviderCredential(
    val providerName: String,
    val apiKey: String,
    val customUrl: String = ""
)

data class TabConfig(
    val id: String,
    val name: String,
    val providerName: String,
    val model: String,
    val systemPrompt: String,
    val maxTokens: Int,
    val noLlm: Boolean = false
)

val DEFAULT_TABS = listOf(
    TabConfig("capture",    "Capture",    "OpenAI",  "gpt-4o-mini",        "", 2000),
    TabConfig("detail",     "Detail",     "OpenAI",  "gpt-4o",             "", 4000),
    TabConfig("transcribe", "Transcribe", "Mistral", "mistral-ocr-latest", "", 4000),
)

data class AppSettings(
    val providerCredentials: List<ProviderCredential> = emptyList(),
    val tabs: List<TabConfig> = DEFAULT_TABS,
    val defaultTab: Int = 0,
    val outputFolderUri: String = "",
    val imageQuality: Int = 85
)
