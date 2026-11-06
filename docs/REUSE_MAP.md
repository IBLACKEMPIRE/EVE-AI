# Reuse map: exact files worth reusing

Paths refer to the audited commits listed in [REFERENCE_AUDIT.md](REFERENCE_AUDIT.md). "Port" means reimplement in Kotlin or C# using the file as a design reference. "Copy" means take the code itself, which requires keeping its license notice in `THIRD_PARTY_NOTICES.md`.

**As of this PR, no code has been copied from any reference repository.** Everything in `ai-brain/` and `android-app/` is original.

## SannaBot (MIT, stated in README only)

| File | Lines | Value | Action | Target in EVE | Milestone |
|---|---|---|---|---|---|
| `src/agent/tool-loop.ts` | 129 | Clean LLM → tools → LLM loop with early-exit hook | **Ported** (concept) | `ai-brain/.../agent/Agent.kt`, which adds policy, approvals, retries and cancellation | MVP ✅ |
| `src/agent/tool-registry.ts` | 97 | Tool definition and registry | **Ported** (concept) | `ai-brain/.../tools/Tools.kt` | MVP ✅ |
| `src/agent/context-manager.ts` | 182 | Context-window trimming | Port | `Agent` history window (simple version done; token-aware later) | M3 |
| `src/agent/personal-memory-store.ts`, `src/tools/personal-memory-tool.ts` | 22 + tool | User-curated memory as a tool | **Ported** (concept) | `ai-brain/.../memory/`, `CoreTools.memory` | MVP ✅ |
| `src/agent/system-prompt.ts` | 411 | Prompt structure for tool use and phone context | Port selectively | `AgentConfig.persona` | M3 |
| `src/agent/scheduler-headless.ts`, `src/tools/scheduler-tool.ts` | 326 + tool | Scheduled sub-agent runs | Port | `background-services/` with WorkManager + headless agent using `DenyAllApprovals` | M5 |
| `android/.../native/SchedulerUtils.kt` | 103 | Daily, weekly and interval next-trigger maths | **Copy candidate** (pure Kotlin, only uses `org.json` + `Calendar`) | `ai-brain/.../tasks/` | M5 |
| `android/.../native/BootReceiver.kt` | 96 | Re-arm schedules after reboot | Port | `background-services/` | M5 |
| `src/agent/notification-sub-agent.ts`, `android/.../NotificationListenerService.kt` | 216 + 246 | Notification → rules → agent | Port | `background-services/` | M7 |
| `src/agent/accessibility-sub-agent.ts`, `android/.../SannaAccessibilityService.kt` | 639 + 524 | UI tree capture with node IDs, gesture fallback, per-app hint learning | Port, with a per-app allow-list and on-screen indicator added | `android-app/automation/` | M7 |
| `android/.../SpeechModule.kt`, `TTSModule.kt` | 501 + 255 | Robust SpeechRecognizer/TTS handling | Reference only (they are bound to React Native `ReactContextBaseJavaModule`) | `android-app/.../voice/` | M3 |
| `src/agent/skill-loader.ts`, `skill-validator.ts` | 393 + 143 | Declarative skills | Port later | `ai-brain/skills/` | post-M7 |

## OPEN-AVATAR (MIT © 2026 D1on_cn)

| File | Lines | Value | Action | Target in EVE | Milestone |
|---|---|---|---|---|---|
| `Assets/03_Scripts/Avatar/EmotionExpressionMapper.cs` | 235 | Emotion → VRM blendshape profiles with lerped transitions | **Copy candidate** | `avatar-engine/` Unity project | M4 |
| `Assets/03_Scripts/Avatar/SimpleAudioMouthSync.cs` | 336 | FFT-band vowel estimation (A/E/I/O/U) when uLipSync isn't calibrated | Copy candidate (fallback path) | `avatar-engine/` | M4 |
| `Assets/03_Scripts/Avatar/AvatarAgentController.cs` | 1627 | Animator + Playables fallback, idle return, root motion | Port selectively (too monolithic to copy) | `avatar-engine/EveAvatarBridge` | M4 |
| `Assets/03_Scripts/Core/AgentEmotion.cs`, `AgentState.cs` | 7 + 8 | Enums | Already mirrored | `ai-brain/.../avatar/Avatar.kt` (`Emotion`, `AvatarMode`) | MVP ✅ |
| `Assets/Models/Avatar_Sunny.vrm` | — | CC-BY test avatar (author D1ON_WONG) | Test fixture only, credited, never shipped as EVE | `avatar-engine/` dev scenes | M4 |
| `Assets/Genies/**` | — | Proprietary SDK | **Do not use** | — | — |
| `LLMService.cs`, UI and demo scripts | — | Superseded by `ai-brain` | Skip | — | — |

## VRM Companion (NO LICENSE: design reference only)

| File | Lines | Value | Action |
|---|---|---|---|
| `Assets/Scripts/IdleBehavior.cs` | 289 | Blink, breathing and sway timing | Reimplement from the behaviour description; don't copy |
| `Assets/Scripts/WebSocketClient.cs` | 295 | Reconnect with 2–30 s exponential backoff | Same pattern already in `RetryPolicy` |
| `Assets/Scripts/LipSyncController.cs`, `ExpressionController.cs` | 106 + 127 | Amplitude mouth and emotion blend | Superseded by uLipSync + OPEN-AVATAR mapper |
| `Assets/Animations/*.fbx` | — | Raw Mixamo clips | **Do not take.** Download our own from Mixamo |

## AVA Android Assistant (MIT © 2026 Maya AI Development Team)

| File | Lines | Value | Action |
|---|---|---|---|
| `services/FloatingBubbleService.kt` | 156 | `TYPE_APPLICATION_OVERLAY` window and drag handling | Reference for M7. Must change `foregroundServiceType` from `systemExempted` to `specialUse` |
| `utils/RootShell.kt` | — | Arbitrary root commands | **Never use** |
| `ai/providers/LlamaLocalClient.kt` | — | Placeholder that fakes replies | **Never use** |
| Everything else | — | Generic Compose/Room scaffolding | Not needed |
