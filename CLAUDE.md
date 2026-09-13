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
- `Camera` — live camera preview, capture button, zoom/lens controls, filled tab row (one tab per configured `TabConfig`)
- `Settings` — provider API keys (multi-provider), add/remove/rename tabs with per-tab model/prompt/token config, filename model, general
- `Processing` — progress indicator (used only for `resubmit`; normal captures run in background)
- `ViewCapture` — rendered markdown view of a saved capture record (inline images, share as MD/PDF)
- `Error` — shows file errors with retry/retake options (only from `resubmit`)

## Source Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point; wires ViewModel state to Composable screens |
| `MainViewModel.kt` | Capture workflow, location lookup, file saving, navigation, `activeTabIndex` |
| `data/AppSettings.kt` | `AppSettings`, `TabConfig`, `ProviderCredential`, `DEFAULT_TABS` |
| `data/SettingsRepository.kt` | DataStore-backed persistence; migrates old flat keys on first run |
| `data/LlmClient.kt` | OkHttp client: chat/completions + Mistral OCR endpoint, `OcrResult` |
| `data/ProviderConfig.kt` | Provider/model catalogue (OpenAI, OpenRouter, Google, Mistral, Custom) |
| `data/CaptureRecord.kt` | Capture history entry (serialised to/from JSON) |
| `ui/CameraScreen.kt` | Camera preview, filled tab row + HorizontalPager, pinch-zoom, lens+zoom group, shutter feedback |
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

`AppSettings` stores a `List<ProviderCredential>` (provider name + API key + optional custom URL). Each capture tab is a `TabConfig` in `AppSettings.tabs` that references a provider by name. The ViewModel resolves the credential at capture time; a tab whose provider has no credential gets an empty key and the request fails gracefully (`hasError`).

### Tab System (`List<TabConfig>`)

Tabs are user-configurable (add / delete / rename in Settings). `DEFAULT_TABS` seeds three:

| id | Name | Default provider / model | Default prompt |
|----|------|--------------------------|----------------|
| `capture` | Capture | OpenAI / gpt-4o-mini | `DEFAULT_MEDIUM_ANALYSIS_PROMPT` |
| `detail` | Detail | OpenAI / gpt-4o | `DEFAULT_ANALYSIS_PROMPT` |
| `transcribe` | Transcribe | Mistral / mistral-ocr-latest | `DEFAULT_TRANSCRIBE_PROMPT` |

User-added tabs get id `custom-<millis>` and the medium prompt. `MainViewModel.activeTabIndex` tracks the current tab. Each `TabConfig` carries: `id`, `name`, `providerName`, `model`, `systemPrompt`, `maxTokens`, `noLlm` (save image only, timestamp filename).

### Mistral OCR

When a tab's model is `mistral-ocr-latest` (`isMistralOcr()`), `LlmClient.ocrImage()` is called instead of `analyzeImage()`. It POSTs to `/ocr`, collects `pages[].markdown` (concatenated), and returns `OcrResult` containing extracted image bytes (`image_base64` arrives as a data URI; the prefix is stripped before decoding). These are saved as `extracted-N.jpg` alongside the note and their inline references in the markdown are rewritten to Obsidian `![[...]]` wikilinks.

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

`buildCameraSelector(cameraId)` creates a `CameraSelector` using `Camera2CameraInfo` filter. The `LaunchedEffect` re-runs on `selectedLensIndex` or `imageCapture` change (a new `ImageCapture` is built whenever `imageQuality` changes and must be re-bound), unbinding and rebinding with the new selector. No `@OptIn` is needed — `ExperimentalCamera2Interop` is stable in CameraX 1.6.0.

### Zoom

- **Pinch gesture** detected via `awaitPointerEventScope` with `PointerEventPass.Initial` on the outer Box — consumes multi-touch events before the HorizontalPager sees them, leaving single-finger swipes for tab navigation
- Zoom ratio computed from finger distance delta: `currDist / prevDist`
- Current zoom ratio and lens chips displayed together bottom-left

### Tab Navigation

A custom filled tab row (one cell per configured tab) sits below the TopAppBar. `HorizontalPager` (transparent pages) is overlaid on the camera preview to handle horizontal swipe gestures. Pager state and `MainViewModel.activeTabIndex` are kept in sync via bidirectional `LaunchedEffect`s.

### Permissions request

Camera is requested together with `ACCESS_COARSE_LOCATION` **and** `ACCESS_FINE_LOCATION` in one call — Android 12+ silently ignores a request for FINE alone. The user may grant only approximate location, so `getLastKnownLocation()` only queries the GPS provider when FINE is granted and wraps every provider lookup in `runCatching` (location is best-effort and must never fail a capture).

## Capture Workflow

1. `CameraScreen` → user selects a tab and taps FAB
2. CameraX captures JPEG to temp file in `cacheDir`; on button press the preview flashes white (250 ms `Animatable` overlay) and the device vibrates (`vibrateShutter()`, `EFFECT_CLICK` on API 29+) as shutter feedback
3. `MainViewModel.onImageCaptured(bytes)` increments `backgroundJobCount` and launches a background coroutine (camera stays open):
   - `fixImageOrientation(bytes, quality)` — reads EXIF rotation tag, physically rotates if needed
   - Resolves `TabConfig` from `activeTabIndex`, looks up `ProviderCredential` by provider name
   - `noLlm` tabs skip the LLM entirely and use a `HHmmss` timestamp as the filename
   - **TRANSCRIBE + `mistral-ocr-latest`**: `LlmClient.ocrImage()` → `OcrResult`
   - **Other modes**: `LlmClient.analyzeImage(baseUrl, apiKey, bytes, model, prompt, maxTokens)` → markdown
   - LLM errors are caught: fallback markdown `"> Analysis failed: …"` is used, `hasError = true`
   - `LlmClient.generateFilename(baseUrl, apiKey, model, markdown)` → kebab-case name
   - `getLastKnownLocation()` → GPS coordinates
   - `reverseGeocode()` → human-readable address via Nominatim
   - `saveFiles()` → picks a unique base name via `uniqueBaseName()` (`-2`, `-3`, … suffix if `{date}-{name}.md` or its `_resources` folder already exists — SAF would otherwise silently create `name (1).md` pointing at the wrong resources), then writes image + OCR extracted images + markdown with YAML frontmatter (always runs, even on LLM error)
4. Record persisted to DataStore history (last 20 entries); `hasError` flag set on LLM failure
5. `backgroundJobCount` decremented in `finally`; a toast confirms save or reports file error

`backgroundJobCount` is shown as a badge on the history icon in `CameraScreen`. It also gates auto-close: `MainActivity.onStop` only calls `finish()` on screen-off, and the 2-minute inactivity timer only emits `finishEvent`, when no job is running — finishing clears the ViewModel and would cancel the in-flight coroutine, losing the photo.

**File layout on disk:**
- `{output_folder}/{date}-{name}.md`
- `{output_folder}/_resources/{date}-{name}/image.jpg` (captured photo)
- `{output_folder}/_resources/{date}-{name}/image-1.jpg`, `image-2.jpg`, … (multi-image)
- `{output_folder}/_resources/{date}-{name}/extracted-1.jpg`, … (OCR-extracted images from Mistral)

## Persistence

**DataStore Preferences** keys (`SettingsRepository.kt`):
`credentials` (JSON array of `ProviderCredential`), `tabs` (JSON array of `TabConfig`), `filename_provider`, `filename_model`, `default_tab`, `output_folder_uri`, `image_quality`, `history` (JSON array)

Two older layouts are migrated on read and their keys removed on the next save: the flat keys (`provider_name`, `api_key`, `high_effort_model`, …) from very old installs, and the fixed 3-tab keys (`capture_tab`, `detail_tab`, `transcribe_tab`).

**YAML frontmatter fields**:
`model` (the LLM model used for analysis; omitted for `noLlm` tabs); plus when location is available: `latitude`, `longitude`, `place`, `address`, `map` (Google Maps URL `https://www.google.com/maps?q=lat,lon`). String values are emitted through `yamlQuote()` (double-quoted, `\` and `"` escaped).

## Permissions

| Permission | Use |
|-----------|-----|
| `CAMERA` | Camera capture |
| `INTERNET` | LLM API calls + Nominatim geocoding |
| `ACCESS_FINE_LOCATION` | GPS coordinates for note frontmatter |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `VIBRATE` | Haptic shutter feedback on capture |

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

- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (Gradle wrapper 9.4.1 is committed; needs `ANDROID_HOME` or `local.properties`)
- `gradle.properties` pins `org.gradle.java.home` to JDK 21 because the system default `java` is a JRE-only 25 with no compiler. CI overrides it per-invocation with `-Dorg.gradle.java.home="$JAVA_HOME"` rather than editing the file.
- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- Portrait-only (`android:screenOrientation="portrait"`)
- Language: Kotlin 2.x + Jetpack Compose (Material3); AGP 9.x built-in Kotlin (no separate `kotlin-android` plugin)
- Install to phone: `adb install app/build/outputs/apk/debug/app-debug.apk`

### Signed release build

The release keystore already exists at `~/Documents/android-keystore/markdown-capture-release.jks` (alias `markdown-capture`, PKCS12). It is gitignored (`*.jks`, `keystore.properties`) and **losing it means never being able to update an installed app again**, so keep a backup outside this repo.

```bash
cp ~/Documents/android-keystore/markdown-capture-release.jks .
cp keystore.properties.example keystore.properties   # then fill in the passwords
./gradlew assembleRelease                            # app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release build still succeeds and produces `app-release-unsigned.apk` rather than failing on a missing property — a fresh clone must build. `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` environment variables take precedence over the file. In a `.properties` file a backslash is an escape character — double it, or use the env vars instead.

### CI (`.github/workflows/build.yml`)

Same shape as `~/Documents/diaryapp`. One job, `apk`, **gated to a `v*` tag or a manual `workflow_dispatch` run** — a plain push to `main` does not build anything. Trade-off, accepted deliberately: a broken commit is not caught until the next tag or manual run. There is no unit-test suite, so there is no `test` job.

**The APK is signed with the release key whenever the job runs, not only for a tag**: Android refuses to install an APK over one signed with a different key, so one key throughout means every install is an in-place upgrade. Without the secrets the job still succeeds and produces an unsigned *debug* APK, but a **`v*` tag fails loudly** if it cannot sign, because an unsigned tagged release looks official and cannot be installed over anything.

Only a `v*` tag publishes a GitHub Release with direct `markdown-capture.apk` and `markdown-capture.aab` downloads (`generate_release_notes: true`). A manual dispatch off a non-tag ref leaves the APK as a 30-day Actions artifact only (served as a zip).

Three repo secrets are needed (Settings → Secrets and variables → Actions):
`KEYSTORE_BASE64` (`base64 -w0 markdown-capture-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`markdown-capture`). There is deliberately **no `KEY_PASSWORD`**: this is a PKCS12 keystore, where the key is protected by the store password (keytool enforces it, and keytool unlocked the key with the store password alone on 2026-09-13). apksigner is run without `--key-pass` and so uses the store password. A separate `KEY_PASSWORD` secret existed briefly and cost five failed runs because its value never matched — do not reintroduce it unless the keystore is regenerated with a distinct key password.

**CI signs the APK with `apksigner`, not Gradle.** Gradle runs `assembleRelease` with no signing config and yields `app-release-unsigned.apk`; the job then runs `zipalign` and `apksigner sign` with the passwords passed as `env:` references. **The App Bundle (`markdown-capture.aab`, required by Google Play) is built and signed by Gradle** (`bundleRelease` with the `SIGNING_*` env vars, key password = store password) and checked with `jarsigner -verify`; both files go on the release. With Play App Signing the keystore is the *upload* key and Google re-signs what users install, so a Play install and a sideloaded GitHub APK cannot upgrade over each other. Gradle's own signing failed on this keystore twice on 2026-09-13 — via `keystore.properties` (parsed as Latin-1) and via `SIGNING_*` environment variables — while keytool accepted the same passwords; apksigner tries several password encodings itself, which is why it is the robust path. The Gradle `signingConfig` remains for local `assembleRelease` with `keystore.properties` or `SIGNING_*` env vars.

Three guards worth keeping: a keytool step checks the store password and alias within seconds and names the wrong secret (note that keytool cannot check a *distinct* key password on PKCS12 — it ignores `-keypass` and uses the store password); the job runs `apksigner verify` before publishing (a broken secret would otherwise silently ship an uninstallable APK); and the keystore is shredded in an `if: always()` step so a failure part-way through leaves no key material on the runner.

**Bump `versionCode` in `app/build.gradle.kts` before tagging** — Android refuses a downgrade, and CI will happily build a duplicate.

**If only "Publish the tagged release" fails** (seen 2026-09-13 for `v1.0`: build, signing and the Actions artifact all succeeded, but GitHub answered every create-release call with 500/502 for over half an hour, from the action and from `gh release create` alike, leaving empty `untagged-*` drafts behind). First just `gh run rerun <run-id> --failed`. If it keeps failing, publish by hand — the two-step path worked when the one-shot create did not:

```bash
gh run download <run-id> -n markdown-capture-apk -D /tmp/apk        # the signed APK from the run
ID=$(gh api -X POST repos/rcastberg/markdown_capture/releases -f tag_name=v1.0 -f name=v1.0 \
       -f body="…" -F draft=true --jq .id)
gh api --method POST -H "Content-Type: application/vnd.android.package-archive" \
  "https://uploads.github.com/repos/rcastberg/markdown_capture/releases/$ID/assets?name=markdown-capture.apk" \
  --input /tmp/apk/markdown-capture.apk                                 # NOT `gh release upload <tag>`: with several drafts for the tag it picks the wrong one
gh api -X PATCH repos/rcastberg/markdown_capture/releases/$ID -F draft=false
```

Then delete the stray drafts: `gh api repos/rcastberg/markdown_capture/releases --jq '.[] | select(.draft) | .id'` and `gh api -X DELETE …/releases/<id>` (these too may 500 for a while).
