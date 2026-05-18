package org.castberg.markdowncapture.data

data class ModelOption(val id: String, val label: String)

data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val models: List<ModelOption>
)

val PROVIDERS = listOf(
    ProviderConfig(
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(
            ModelOption("gpt-4o",       "GPT-4o"),
            ModelOption("gpt-4o-mini",  "GPT-4o mini"),
            ModelOption("gpt-4.1",      "GPT-4.1"),
            ModelOption("gpt-4.1-mini", "GPT-4.1 mini"),
        )
    ),
    ProviderConfig(
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf(
            ModelOption("openai/gpt-4o",                              "GPT-4o (OpenAI)"),
            ModelOption("openai/gpt-4.1",                             "GPT-4.1 (OpenAI)"),
            ModelOption("openai/gpt-4.1-mini",                        "GPT-4.1 mini (OpenAI)"),
            ModelOption("anthropic/claude-opus-4-7",                  "Claude Opus 4.7"),
            ModelOption("anthropic/claude-sonnet-4-6",                "Claude Sonnet 4.6"),
            ModelOption("anthropic/claude-haiku-4-5",                 "Claude Haiku 4.5"),
            ModelOption("google/gemini-2.5-pro",                      "Gemini 2.5 Pro"),
            ModelOption("google/gemini-2.0-flash",                    "Gemini 2.0 Flash"),
            ModelOption("google/gemma-4-27b-it",                      "Gemma 4 27B"),
            ModelOption("meta-llama/llama-3.2-90b-vision-instruct",   "Llama 3.2 90B Vision"),
        )
    ),
    ProviderConfig(
        name = "Google",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        models = listOf(
            ModelOption("gemini-2.5-pro",   "Gemini 2.5 Pro"),
            ModelOption("gemini-2.0-flash", "Gemini 2.0 Flash"),
            ModelOption("gemini-1.5-pro",   "Gemini 1.5 Pro"),
            ModelOption("gemini-1.5-flash", "Gemini 1.5 Flash"),
            ModelOption("gemma-4-27b-it",   "Gemma 4 27B"),
        )
    ),
    ProviderConfig(
        name = "Mistral",
        baseUrl = "https://api.mistral.ai/v1",
        models = listOf(
            ModelOption("mistral-ocr-latest",   "Mistral OCR"),
            ModelOption("pixtral-large-latest", "Pixtral Large"),
            ModelOption("pixtral-12b-2409",     "Pixtral 12B"),
        )
    ),
    ProviderConfig(
        name = "Custom",
        baseUrl = "",
        models = emptyList()
    )
)

fun providerByName(name: String): ProviderConfig =
    PROVIDERS.find { it.name == name } ?: PROVIDERS.last()

fun isMistralOcr(model: String): Boolean = model == "mistral-ocr-latest"
