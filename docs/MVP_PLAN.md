# First Android MVP: implementation plan

**Goal:** a verified, buildable Android foundation, and nothing advanced until it's green in CI and installed on a real phone.

## Scope of the MVP (this PR)

| Area | Included | Explicitly excluded until later milestones |
|---|---|---|
| App shell | Kotlin/Compose app `ai.eve.android`, minSdk 26, target/compile 35; Chat · Tasks · Settings screens | Navigation library, DI framework |
| Conversation | Text chat; voice input (platform `SpeechRecognizer`); spoken replies (platform TTS) | Wake word, barge-in, cloud TTS |
| AI | `LlmClient` interface; Claude Messages API with tool use; offline demo model when no key is set | Other providers, on-device LLM |
| Memory | User-requested facts only, stored in app-private files; view and delete in Settings | Embeddings, sync |
| Safety | `PolicyEngine` risk levels; approval dialog with 60 s auto-deny; STOP/RESUME; audit log screen | Standing-grant UI |
| Phone actions | Open app, set alarm, open dialer pre-filled, open SMS draft (the user presses Call/Send) | Silent SMS/calls, Accessibility automation |
| Avatar | 2D placeholder driven by the renderer-neutral `AvatarController` (blink, gaze, breathing, emotion, lip-sync) | 3D EVE (needs reference images and a model) |
| Secrets | API key encrypted with an Android Keystore AES-256-GCM key | — |
| Background | — (the `ai-brain` scheduling model exists and is tested, but nothing runs in the background) | WorkManager, notifications (M5), overlay (M7) |

## Module layout

```
settings.gradle.kts           include(":ai-brain", ":android-app")
ai-brain/                     pure Kotlin/JVM, no Android imports (portable to Windows/robot)
  agent/  tools/  policy/  memory/  audit/  tasks/  llm/  avatar/
android-app/                  Android application
  EveApplication.kt           composition root
  llm/AnthropicClient.kt      Claude adapter (HttpURLConnection + org.json)
  security/SecureSettings.kt  Keystore-encrypted settings
  tools/AndroidTools.kt       phone tools (intent-based)
  voice/Voice.kt              voice-engine (to be extracted to voice-engine/ at M3)
  avatar/PlaceholderAvatar.kt avatar-engine host side (Unity module joins at M4)
  ui/                         Compose screens + ViewModel
tools/test-core.sh            runs ai-brain tests without Gradle
.github/workflows/android.yml CI: tests → lint → debug APK artifact
```

`voice-engine/`, `avatar-engine/` and `background-services/` stay as packages inside `android-app` for the MVP. They become separate Gradle modules when they gain their own dependencies (M3–M5), so the first build has as few moving parts as possible.

## Verification steps (Definition of Done for the MVP)

| # | Check | Command | Status |
|---|---|---|---|
| 1 | ai-brain unit tests (25) | `./tools/test-core.sh` (kotlinc) | ✅ passed 25/25 on 6 Nov 2026 |
| 2 | ai-brain tests via Gradle | `./gradlew :ai-brain:test` | ⏳ CI |
| 3 | App unit tests (Claude mapping, 3) | `./gradlew :android-app:testDebugUnitTest` | ⏳ CI |
| 4 | Lint | `./gradlew :android-app:lintDebug` | ⏳ CI |
| 5 | Debug APK | `./gradlew :android-app:assembleDebug` | ⏳ CI |
| 6 | Device smoke test | the steps below, on a physical phone | ⏳ needs you |

### Device smoke test
1. Install the APK and launch it. The placeholder avatar blinks and breathes.
2. With no key, type `remember that my sister is Dana`, force-stop the app, relaunch, then type `what do you remember about sister`. The answer mentions Dana.
3. Type `forget everything`. The approval dialog appears. **Deny** keeps the memory. Repeat and tap **Allow once**, and it's deleted.
4. Tap the mic and allow access. Say "what time is it". The avatar shows listening, then thinking, and the reply is spoken with mouth movement.
5. Type `open settings`. The Android Settings app opens.
6. Settings: paste an Anthropic API key and save. The header shows the model. Ask a free-form question.
7. Tap **STOP** while EVE is thinking. The task is cancelled, the banner shows, and requests are refused until **RESUME**.
8. Tasks tab: tasks and audit entries are listed. The API key never appears.

## After the MVP is green
M3 conversation hardening (voice-engine module, context trimming) → M4 avatar-engine (Unity as a Library) → M5 background services → M6 integration and performance → M7 overlay and automation → M8 cloud and other platforms. See [ROADMAP.md](ROADMAP.md).
