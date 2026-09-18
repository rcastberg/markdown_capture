package org.castberg.markdowncapture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CREDENTIALS        = stringPreferencesKey("credentials")
        val TABS               = stringPreferencesKey("tabs")
        val DEFAULT_TAB        = intPreferencesKey("default_tab")
        val OUTPUT_FOLDER_URI  = stringPreferencesKey("output_folder_uri")
        val IMAGE_QUALITY      = intPreferencesKey("image_quality")
        val HISTORY            = stringPreferencesKey("history")

        // Legacy flat keys (very old installs)
        val L_PROVIDER_NAME      = stringPreferencesKey("provider_name")
        val L_LLM_URL            = stringPreferencesKey("llm_url")
        val L_API_KEY            = stringPreferencesKey("api_key")
        val L_DEFAULT_HIGH       = booleanPreferencesKey("default_high_effort")
        val L_HIGH_MODEL         = stringPreferencesKey("high_effort_model")
        val L_MEDIUM_MODEL       = stringPreferencesKey("medium_effort_model")
        val L_LOW_MODEL          = stringPreferencesKey("low_effort_model")
        val L_SYSTEM_PROMPT      = stringPreferencesKey("system_prompt")
        val L_MEDIUM_PROMPT      = stringPreferencesKey("medium_system_prompt")

        // Old 3-tab keys (previous version)
        val OLD_CAPTURE_TAB    = stringPreferencesKey("capture_tab")
        val OLD_DETAIL_TAB     = stringPreferencesKey("detail_tab")
        val OLD_TRANSCRIBE_TAB = stringPreferencesKey("transcribe_tab")

        // Removed: dedicated filename-model settings (filename is now generated inline
        // by the analysis call itself; see LlmClient.extractFilename)
        val L_FILENAME_PROVIDER = stringPreferencesKey("filename_provider")
        val L_FILENAME_MODEL    = stringPreferencesKey("filename_model")
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun credentialsToJson(list: List<ProviderCredential>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("providerName", c.providerName)
                put("apiKey", c.apiKey)
                put("customUrl", c.customUrl)
            })
        }
        return arr.toString()
    }

    private fun credentialsFromJson(json: String): List<ProviderCredential> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ProviderCredential(
                    providerName = o.getString("providerName"),
                    apiKey       = o.optString("apiKey", ""),
                    customUrl    = o.optString("customUrl", "")
                )
            }
        }.getOrDefault(emptyList())

    private fun tabsToJson(tabs: List<TabConfig>): String {
        val arr = JSONArray()
        tabs.forEach { tab ->
            arr.put(JSONObject().apply {
                put("id",           tab.id)
                put("name",         tab.name)
                put("providerName", tab.providerName)
                put("model",        tab.model)
                put("systemPrompt", tab.systemPrompt)
                put("maxTokens",    tab.maxTokens)
                put("noLlm",        tab.noLlm)
            })
        }
        return arr.toString()
    }

    private fun tabsFromJson(json: String): List<TabConfig> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o       = arr.getJSONObject(i)
                val default = DEFAULT_TABS.getOrElse(i) { DEFAULT_TABS[0] }
                TabConfig(
                    id           = o.optString("id",           "tab-$i"),
                    name         = o.optString("name",         "Tab ${i + 1}"),
                    providerName = o.optString("providerName", default.providerName),
                    model        = o.optString("model",        default.model),
                    systemPrompt = o.optString("systemPrompt", ""),
                    maxTokens    = o.optInt("maxTokens",       default.maxTokens),
                    noLlm        = o.optBoolean("noLlm",       false)
                )
            }.ifEmpty { DEFAULT_TABS }
        }.getOrDefault(DEFAULT_TABS)

    private fun tabFromJsonOld(json: String, default: TabConfig): TabConfig =
        runCatching {
            val o = JSONObject(json)
            default.copy(
                providerName = o.optString("providerName", default.providerName),
                model        = o.optString("model",        default.model),
                systemPrompt = o.optString("systemPrompt", default.systemPrompt),
                maxTokens    = o.optInt("maxTokens",       default.maxTokens)
            )
        }.getOrDefault(default)

    // ── Flows ─────────────────────────────────────────────────────────────────

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val legacyProvider = prefs[Keys.L_PROVIDER_NAME]
        val legacyKey      = prefs[Keys.L_API_KEY]
        val oldCaptureTab  = prefs[Keys.OLD_CAPTURE_TAB]
        val oldDetailTab   = prefs[Keys.OLD_DETAIL_TAB]

        when {
            // Migration from legacy flat keys (very old installs)
            legacyProvider != null && legacyKey != null && prefs[Keys.CREDENTIALS] == null -> {
                val legacyUrl   = prefs[Keys.L_LLM_URL] ?: ""
                val highModel   = prefs[Keys.L_HIGH_MODEL]    ?: "gpt-4o"
                val medModel    = prefs[Keys.L_MEDIUM_MODEL]  ?: "gpt-4o-mini"
                val highPrompt  = prefs[Keys.L_SYSTEM_PROMPT] ?: ""
                val medPrompt   = prefs[Keys.L_MEDIUM_PROMPT] ?: ""
                val defaultHigh = prefs[Keys.L_DEFAULT_HIGH]  ?: true
                AppSettings(
                    providerCredentials = listOf(ProviderCredential(
                        providerName = legacyProvider,
                        apiKey       = legacyKey,
                        customUrl    = if (legacyProvider == "Custom") legacyUrl else ""
                    )),
                    tabs = listOf(
                        DEFAULT_TABS[0].copy(providerName = legacyProvider, model = medModel,  systemPrompt = medPrompt),
                        DEFAULT_TABS[1].copy(providerName = legacyProvider, model = highModel, systemPrompt = highPrompt),
                        DEFAULT_TABS[2]
                    ),
                    defaultTab           = if (defaultHigh) 1 else 0,
                    outputFolderUri      = prefs[Keys.OUTPUT_FOLDER_URI] ?: "",
                    imageQuality         = prefs[Keys.IMAGE_QUALITY] ?: 85
                )
            }

            // Migration from old 3-tab format
            prefs[Keys.TABS] == null && (oldCaptureTab != null || oldDetailTab != null) -> {
                AppSettings(
                    providerCredentials  = credentialsFromJson(prefs[Keys.CREDENTIALS] ?: "[]"),
                    tabs = listOf(
                        tabFromJsonOld(oldCaptureTab ?: "",                 DEFAULT_TABS[0]),
                        tabFromJsonOld(oldDetailTab ?: "",                  DEFAULT_TABS[1]),
                        tabFromJsonOld(prefs[Keys.OLD_TRANSCRIBE_TAB] ?: "", DEFAULT_TABS[2])
                    ),
                    defaultTab           = prefs[Keys.DEFAULT_TAB]       ?: 0,
                    outputFolderUri      = prefs[Keys.OUTPUT_FOLDER_URI] ?: "",
                    imageQuality         = prefs[Keys.IMAGE_QUALITY]     ?: 85
                )
            }

            // Current format
            else -> AppSettings(
                providerCredentials  = credentialsFromJson(prefs[Keys.CREDENTIALS] ?: "[]"),
                tabs                 = tabsFromJson(prefs[Keys.TABS] ?: ""),
                defaultTab           = prefs[Keys.DEFAULT_TAB]       ?: 0,
                outputFolderUri      = prefs[Keys.OUTPUT_FOLDER_URI] ?: "",
                imageQuality         = prefs[Keys.IMAGE_QUALITY]     ?: 85
            )
        }
    }

    val history: Flow<List<CaptureRecord>> = context.dataStore.data.map { prefs ->
        runCatching {
            val arr = JSONArray(prefs[Keys.HISTORY] ?: return@runCatching emptyList())
            (0 until arr.length()).map { CaptureRecord.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CREDENTIALS]       = credentialsToJson(settings.providerCredentials)
            prefs[Keys.TABS]              = tabsToJson(settings.tabs)
            prefs[Keys.DEFAULT_TAB]       = settings.defaultTab
            prefs[Keys.OUTPUT_FOLDER_URI] = settings.outputFolderUri
            prefs[Keys.IMAGE_QUALITY]     = settings.imageQuality
            // Remove all old keys after migration
            prefs.remove(Keys.OLD_CAPTURE_TAB)
            prefs.remove(Keys.OLD_DETAIL_TAB)
            prefs.remove(Keys.OLD_TRANSCRIBE_TAB)
            prefs.remove(Keys.L_PROVIDER_NAME)
            prefs.remove(Keys.L_LLM_URL)
            prefs.remove(Keys.L_API_KEY)
            prefs.remove(Keys.L_DEFAULT_HIGH)
            prefs.remove(Keys.L_HIGH_MODEL)
            prefs.remove(Keys.L_MEDIUM_MODEL)
            prefs.remove(Keys.L_LOW_MODEL)
            prefs.remove(Keys.L_SYSTEM_PROMPT)
            prefs.remove(Keys.L_MEDIUM_PROMPT)
            prefs.remove(Keys.L_FILENAME_PROVIDER)
            prefs.remove(Keys.L_FILENAME_MODEL)
        }
    }

    suspend fun addRecord(record: CaptureRecord) {
        context.dataStore.edit { prefs ->
            val existing = runCatching {
                val arr = JSONArray(prefs[Keys.HISTORY] ?: "[]")
                (0 until arr.length()).map { CaptureRecord.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            val updated = (listOf(record) + existing).take(20)
            val arr = JSONArray()
            updated.forEach { arr.put(JSONObject(it.toJson())) }
            prefs[Keys.HISTORY] = arr.toString()
        }
    }
}
