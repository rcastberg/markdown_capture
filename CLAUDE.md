# Markdown Capture — Technical Overview

> **Keep this file up to date with every significant change to the project.**

## What This App Does

Android app that captures photos, sends them to a vision-capable LLM, and saves the AI-generated markdown note plus the original image to an Obsidian vault folder on-device (via Storage Access Framework).

## Architecture

Single-Activity Compose app with a ViewModel-driven navigation model (no Jetpack Navigation library).

```
MainActivity
  └── MainViewModel          ← all business logic, navigation state, settings
        ├── SettingsRepository ← DataStore persistence
        └── LlmClient          ← HTTP calls to AI providers
```

**Screens** (sealed class `MainViewModel.Screen`):
- `Camera` — live camera preview, capture button, zoom/lens controls, Capture/Detail/Transcribe tab row
- `Settings` — provider API keys (multi-provider), per-tab model/prompt/token config, filename model, general
- `Processing` — progress indicator (used only for `resubmit`; normal captures run in background)
- `ViewCapture` — rendered markdown view of a saved capture record (inline images, share as MD/PDF)
- `Error` — shows file errors with retry/retake options (only from `resubmit`)

## Source Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point; wires ViewModel state to Composable screens |
| `MainViewModel.kt` | Capture workflow, location lookup, file saving, navigation, CaptureMode state |
| `data/AppSettings.kt` | `AppSettings`, `TabConfig`, `ProviderCredential`, `CaptureMode` enum |
| `data/SettingsRepository.kt` | DataStore-backed persistence; migrates old flat keys on first run |
| `data/LlmClient.kt` | OkHttp client: chat/completions + Mistral OCR endpoint, `OcrResult` |
| `data/ProviderConfig.kt` | Provider/model catalogue (OpenAI, OpenRouter, Google, Mistral, Custom) |
| `data/CaptureRecord.kt` | Capture history entry (serialised to/from JSON) |
| `ui/CameraScreen.kt` | Camera preview, TabRow+HorizontalPager, pinch-zoom, lens+zoom group |
| `ui/SettingsScreen.kt` | API Keys section, per-tab sections, filename model, general settings |
| `ui/ProcessingScreen.kt` | Spinner with step label |
| `ui/ResultScreen.kt` | `CaptureDetailScreen` — rendered/raw toggle, share as MD and PDF |
| `ui/MarkdownViewer.kt` | `parseMarkdown`, `RenderedMarkdown` composable, SAF image loader |
| `ui/PdfExport.kt` | `generateAndSharePdf` (PdfDocument API), `shareMarkdownFile` (FileProvider) |
| `ui/HistorySheet.kt` / `ErrorScreen.kt` | Supporting screens |
| `ui/theme/Theme.kt` | Material3 theme |

## AI Provider Integration

Most providers expose an **OpenAI-compatible `/chat/completions` endpoint**. Mistral OCR uses a separate `/ocr` endpoint.

| Provider | Base URL | Notes |
|----------|----------|-------|
| OpenAI | `https://api.openai.com/v1` | |
| OpenRouter | `https://openrouter.ai/api/v1` | Adds `HTTP-Referer` + `X-Title` headers |
| Google | `https://generativelanguage.googleapis.com/v1beta/openai` | Gemini + Gemma models |
| Mistral | `https://api.mistral.ai/v1` | `mistral-ocr-latest` uses `/ocr` endpoint |
| Custom | User-specified | Any OpenAI-compatible endpoint (e.g. Ollama) |

### Multi-Provider Credential System

`AppSettings` stores a `List<ProviderCredential>` (provider name + API key + optional custom URL). Each of the three capture tabs (`captureTab`, `detailTab`, `transcribeTab`) has its own `TabConfig` that references a provider by name. The ViewModel resolves the credential at capture time.

### Tab System (`CaptureMode` enum)

| Mode | Description | Default model |
|------|-------------|---------------|
| `CAPTURE` | Quick note | gpt-4o-mini |
| `DETAIL` | Detailed analysis | gpt-4o |
| `TRANSCRIBE` | Text extraction / OCR | mistral-ocr-latest |

`MainViewModel.activeTab` tracks the current mode. Each `TabConfig` carries: `providerName`, `model`, `systemPrompt`, `maxTokens`.

### Mistral OCR

When `transcribeTab.model == "mistral-ocr-latest"`, `LlmClient.ocrImage()` is called instead of `analyzeImage()`. It POSTs to `/ocr`, collects `pages[].markdown` (concatenated), and returns `OcrResult` containing extracted image bytes. These are saved as `extracted-N.jpg` alongside the note and their inline references in the markdown are rewritten to Obsidian `![[...]]` wikilinks.

### Dynamic Model Fetching

Per-tab settings sections fetch models from the configured provider's `/models` endpoint on provider change. Filtering per provider: OpenRouter strips free models; OpenAI keeps only chat models; Google/Mistral return all.

### Available Models (ProviderConfig.kt)

**OpenAI:** gpt-4o, gpt-4o-mini, gpt-4.1, gpt-4.1-mini  
**OpenRouter:** GPT-4o/4.1/4.1-mini, Claude Opus 4.7 / Sonnet 4.6 / Haiku 4.5, Gemini 2.5 Pro / 2.0 Flash, Gemma 4 27B, Llama 3.2 90B Vision  
**Google:** Gemini 2.5 Pro / 2.0 Flash / 1.5 Pro / 1.5 Flash, Gemma 4 27B  
**Mistral:** mistral-ocr-latest, pixtral-large-latest, pixtral-12b-2409

## Camera Implementation

Uses **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` — all 1.6.0).

### Lens Detection (`CameraScreen.kt`)

On first compose, `detectLenses()` uses `android.hardware.camera2.CameraManager` to enumerate back-facing cameras, reads their minimum focal length, sorts them (shortest = widest), and assigns labels:
- 1 camera → "Main"
- Multiple cameras → "Wide" / "Main" / "Tele"

Zoom factors shown in the UI are relative to the widest lens (widest = 1x).

### Lens Switching

`buildCameraSelector(cameraId)` creates a `CameraSelector` using `Camera2CameraInfo` filter. The `LaunchedEffect` re-runs on `selectedLensIndex` change, unbinding and rebinding with the new selector. No `@OptIn` is needed — `ExperimentalCamera2Interop` is stable in CameraX 1.6.0.

### Zoom

- **Pinch gesture** detected via `awaitPointerEventScope` with `PointerEventPass.Initial` on the outer Box — consumes multi-touch events before the HorizontalPager sees them, leaving single-finger swipes for tab navigation
- Zoom ratio computed from finger distance delta: `currDist / prevDist`
- Current zoom ratio and lens chips displayed together bottom-left

### Tab Navigation

`TabRow` with three tabs (Capture / Detail / Transcribe) sits below the TopAppBar. `HorizontalPager` (transparent pages) is overlaid on the camera preview to handle horizontal swipe gestures. Pager state and `MainViewModel.activeTab` are kept in sync via bidirectional `LaunchedEffect`s.

## Capture Workflow

1. `CameraScreen` → user selects tab (Capture/Detail/Transcribe) and taps FAB
2. CameraX captures JPEG to temp file in `cacheDir`
3. `MainViewModel.onImageCaptured(bytes)` increments `backgroundJobCount` and launches a background coroutine (camera stays open):
   - `fixImageOrientation(bytes, quality)` — reads EXIF rotation tag, physically rotates if needed
   - Resolves `TabConfig` from `activeTab`, looks up `ProviderCredential` by provider name
   - **TRANSCRIBE + `mistral-ocr-latest`**: `LlmClient.ocrImage()` → `OcrResult`
   - **Other modes**: `LlmClient.analyzeImage(baseUrl, apiKey, bytes, model, prompt, maxTokens)` → markdown
   - LLM errors are caught: fallback markdown `"> Analysis failed: …"` is used, `hasError = true`
   - `LlmClient.generateFilename(baseUrl, apiKey, model, markdown)` → kebab-case name
   - `getLastKnownLocation()` → GPS coordinates
   - `reverseGeocode()` → human-readable address via Nominatim
   - `saveFiles()` → writes image + OCR extracted images + markdown with YAML frontmatter (always runs, even on LLM error)
4. Record persisted to DataStore history (last 20 entries); `hasError` flag set on LLM failure
5. `backgroundJobCount` decremented in `finally`; a toast confirms save or reports file error

`backgroundJobCount` is shown as a badge on the history icon in `CameraScreen`.

**File layout on disk:**
- `{output_folder}/{date}-{name}.md`
- `{output_folder}/_resources/{date}-{name}/image.jpg` (captured photo)
- `{output_folder}/_resources/{date}-{name}/image-1.jpg`, `image-2.jpg`, … (multi-image)
- `{output_folder}/_resources/{date}-{name}/extracted-1.jpg`, … (OCR-extracted images from Mistral)

## Persistence

**DataStore Preferences** keys (`SettingsRepository.kt`):
`credentials` (JSON array of `ProviderCredential`), `capture_tab`, `detail_tab`, `transcribe_tab` (JSON `TabConfig`s), `filename_provider`, `filename_model`, `default_tab`, `output_folder_uri`, `image_quality`, `history` (JSON array)

Old flat keys (`provider_name`, `api_key`, etc.) are migrated on first save and then removed.

**YAML frontmatter fields** (always present):
`model` (the LLM model used for analysis); plus when location is available: `latitude`, `longitude`, `place`, `address`, `map` (Google Maps URL `https://www.google.com/maps?q=lat,lon`)

## Permissions

| Permission | Use |
|-----------|-----|
| `CAMERA` | Camera capture |
| `INTERNET` | LLM API calls + Nominatim geocoding |
| `ACCESS_FINE_LOCATION` | GPS coordinates for note frontmatter |
| `ACCESS_COARSE_LOCATION` | Fallback location |

## Key Dependencies

```
androidx.camera:camera-*:1.6.0
androidx.datastore:datastore-preferences:1.1.1
com.squareup.okhttp3:okhttp:4.12.0
androidx.compose:compose-bom:2026.03.00
androidx.lifecycle:lifecycle-runtime-compose:2.8.6
androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6
```

## Build

- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- Portrait-only (`android:screenOrientation="portrait"`)
- Language: Kotlin 2.x + Jetpack Compose (Material3)
