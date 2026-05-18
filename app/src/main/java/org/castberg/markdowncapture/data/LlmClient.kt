package org.castberg.markdowncapture.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OcrResult(
    val markdown: String,
    val extractedImages: List<Pair<String, ByteArray>>  // image-id -> bytes
)

object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    const val DEFAULT_ANALYSIS_PROMPT =
        "You are creating a note for an Obsidian personal knowledge base. " +
        "Analyze the image and write a well-structured markdown note following Obsidian conventions:\n" +
        "- Start with a single # heading that names the main subject\n" +
        "- Use ## subheadings to organize sections\n" +
        "- Use [[wikilinks]] for any concepts, people, places, or topics that would make sense as linked notes\n" +
        "- Add a #tags section at the bottom with relevant tags (e.g. #photo #receipt #whiteboard)\n" +
        "- Describe what you see in detail — objects, people, setting, colors, composition\n" +
        "- If there is any written text visible in the image, include a ## Text section that transcribes or summarizes it\n" +
        "Return only the markdown content — no JSON, no preamble, no code fences."

    const val DEFAULT_MEDIUM_ANALYSIS_PROMPT =
        "You are creating a quick note for an Obsidian personal knowledge base. " +
        "Briefly analyze this image and write a concise markdown note:\n" +
        "- Start with a # heading naming the subject\n" +
        "- Write 2-4 sentences describing the key content\n" +
        "- Add 2-3 relevant #tags at the bottom\n" +
        "Return only the markdown content — no preamble, no code fences."

    const val DEFAULT_TRANSCRIBE_PROMPT =
        "You are transcribing a document for an Obsidian personal knowledge base. " +
        "Transcribe all visible text exactly as it appears. Use markdown formatting:\n" +
        "- Use # headings to match the document's structure\n" +
        "- Preserve lists, tables, and other formatting faithfully\n" +
        "- Use [[wikilinks]] for names, places, and key concepts\n" +
        "- Add a #tags section at the bottom\n" +
        "Return only the markdown content — no JSON, no preamble, no code fences."

    private const val FILENAME_PROMPT =
        "Generate a short filename for the following note. " +
        "Rules: lowercase kebab-case, max 50 characters, only letters/numbers/hyphens, no extension. " +
        "Respond with ONLY the filename — no explanation, no punctuation, nothing else."

    // ── Analysis ──────────────────────────────────────────────────────────────

    suspend fun analyzeImage(
        baseUrl: String,
        apiKey: String,
        imageBytes: ByteArray,
        model: String,
        systemPrompt: String,
        maxTokens: Int
    ): String {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("model", model.ifBlank { "gpt-4o" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Analyze this image.")
                        })
                    })
                })
            })
            put("max_tokens", maxTokens)
        }.toString()
        return callApi(baseUrl, apiKey, body)
    }

    suspend fun analyzeImages(
        baseUrl: String,
        apiKey: String,
        imageBytesList: List<ByteArray>,
        model: String,
        systemPrompt: String,
        maxTokens: Int
    ): String {
        val content = JSONArray().apply {
            imageBytesList.forEach { bytes ->
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Analyze these ${imageBytesList.size} images.")
            })
        }
        val body = JSONObject().apply {
            put("model", model.ifBlank { "gpt-4o" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("max_tokens", maxTokens)
        }.toString()
        return callApi(baseUrl, apiKey, body)
    }

    // ── Mistral OCR ───────────────────────────────────────────────────────────

    suspend fun ocrImage(
        baseUrl: String,
        apiKey: String,
        imageBytes: ByteArray
    ): OcrResult = withContext(Dispatchers.IO) {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val url = "${baseUrl.trimEnd('/')}/ocr"

        val requestBody = JSONObject().apply {
            put("model", "mistral-ocr-latest")
            put("document", JSONObject().apply {
                put("type", "image_url")
                put("image_url", "data:image/jpeg;base64,$base64")
            })
            put("include_image_base64", true)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            val rb = response.body?.string() ?: throw Exception("Empty OCR response")
            if (!response.isSuccessful) throw Exception("OCR error ${response.code}: $rb")
            rb
        }

        val json = JSONObject(responseBody)
        val pages = json.getJSONArray("pages")
        val markdownParts = mutableListOf<String>()
        val extractedImages = mutableListOf<Pair<String, ByteArray>>()

        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            markdownParts.add(page.optString("markdown", ""))
            val images = page.optJSONArray("images") ?: continue
            for (j in 0 until images.length()) {
                val img = images.getJSONObject(j)
                val id  = img.optString("id", "img-$i-$j")
                val b64 = img.optString("image_base64", "")
                if (b64.isNotBlank()) {
                    extractedImages.add(Pair(id, Base64.decode(b64, Base64.DEFAULT)))
                }
            }
        }

        OcrResult(
            markdown        = markdownParts.joinToString("\n\n"),
            extractedImages = extractedImages
        )
    }

    // ── Filename ──────────────────────────────────────────────────────────────

    suspend fun generateFilename(
        baseUrl: String,
        apiKey: String,
        model: String,
        markdown: String
    ): String {
        val body = JSONObject().apply {
            put("model", model.ifBlank { "gpt-4o-mini" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", FILENAME_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", markdown.take(1500))
                })
            })
            put("max_tokens", 30)
        }.toString()
        return sanitizeFilename(callApi(baseUrl, apiKey, body))
    }

    // ── Model discovery ───────────────────────────────────────────────────────

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        providerName: String
    ): List<ModelOption> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .apply {
                if (url.contains("openrouter.ai")) {
                    addHeader("HTTP-Referer", "https://github.com/castberg/obsidian-capture")
                    addHeader("X-Title", "Markdown Capture")
                }
            }
            .get()
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: throw Exception("Empty response")
        }

        val data = JSONObject(body).getJSONArray("data")
        when (providerName) {
            "OpenRouter" -> parseOpenRouterModels(data)
            "OpenAI"     -> parseOpenAiModels(data)
            "Google"     -> parseGoogleModels(data)
            "Mistral"    -> parseMistralModels(data)
            else         -> emptyList()
        }
    }

    private fun parseOpenRouterModels(data: JSONArray): List<ModelOption> {
        val models = mutableListOf<ModelOption>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val pricing = obj.optJSONObject("pricing")
            val promptPrice      = pricing?.optString("prompt",     "1") ?: "1"
            val completionPrice  = pricing?.optString("completion", "1") ?: "1"
            if (promptPrice.toDoubleOrNull() == 0.0 && completionPrice.toDoubleOrNull() == 0.0) continue
            val id   = obj.optString("id").ifBlank { continue }
            val name = obj.optString("name", id).ifBlank { id }
            models.add(ModelOption(id, name))
        }
        return models.sortedBy { it.label }
    }

    private fun parseOpenAiModels(data: JSONArray): List<ModelOption> {
        val chatPrefixes = listOf("gpt-", "o1", "o3", "o4", "chatgpt-")
        return (0 until data.length())
            .mapNotNull { i ->
                val id = data.getJSONObject(i).optString("id")
                if (chatPrefixes.none { id.startsWith(it) }) null
                else ModelOption(id, id)
            }
            .sortedBy { it.id }
    }

    private fun parseGoogleModels(data: JSONArray): List<ModelOption> =
        (0 until data.length())
            .mapNotNull { i ->
                val id = data.getJSONObject(i).optString("id")
                if (id.isBlank()) null else ModelOption(id, id)
            }
            .sortedBy { it.id }

    private fun parseMistralModels(data: JSONArray): List<ModelOption> =
        (0 until data.length())
            .mapNotNull { i ->
                val id = data.getJSONObject(i).optString("id")
                if (id.isBlank()) null else ModelOption(id, id)
            }
            .sortedBy { it.id }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun callApi(baseUrl: String, apiKey: String, requestBody: String): String =
        withContext(Dispatchers.IO) {
            val trimmed = baseUrl.trimEnd('/')
            val url = if (trimmed.endsWith("/chat/completions")) trimmed
                      else "$trimmed/chat/completions"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .apply {
                    if (url.contains("openrouter.ai")) {
                        addHeader("HTTP-Referer", "https://github.com/castberg/obsidian-capture")
                        addHeader("X-Title", "Markdown Capture")
                    }
                }
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response from API")
            if (!response.isSuccessful) throw Exception("API error ${response.code}: $body")

            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }

    private fun sanitizeFilename(name: String): String =
        name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trimStart('-').trimEnd('-')
            .take(50)
            .ifBlank { "captured-image" }
}
