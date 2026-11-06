# Third-party repository audit (Phase 1)

Audited 6 Nov 2026 from fresh clones. Everything below comes from reading each repo's source, manifests and build files. **None of the four were built or run on a device**, because the Unity editor and an Android SDK aren't available in the audit environment. Build requirements are taken from each repo's configuration.

## Verified sources

| Project | URL | Commit audited | Commit date |
|---|---|---|---|
| OPEN-AVATAR | https://github.com/timesbye/OPEN-AVATAR | `dc9e45211042062ed1c64912c00d45e78a0087aa` | 2026-06-08 |
| VRM Companion | https://github.com/abdulyasir100/vrm-companion | `e3b4c3838a691952446ec09922737224c0fa32c0` | 2026-03-17 |
| SannaBot | https://github.com/sannabotdev/sannabotapp | `7d84aa708fe3c56aa7202635abdaa442cc4781f3` | 2026-03-27 |
| AVA Android Assistant | https://github.com/crftsmnd/ava-android-assistant | `af36a41c945274996e26e8487843c40daadf9fde` | 2026-06-12 |

## Build requirements and Android compatibility

| | OPEN-AVATAR | VRM Companion | SannaBot | AVA |
|---|---|---|---|---|
| Build system | Unity Editor 2022.3.62f3c1 (China build) | Unity Editor 2022.3.62f3 + Android Build Support | Node ≥ 22.11, RN CLI 20, Gradle (RN 0.84 template) | Gradle 8.2 wrapper, AGP 8.2.2, Kotlin 1.9.22, KSP |
| Android min / target SDK | not configured for Android | Unity player settings (portrait, tablet) | RN 0.84 defaults | 26 / 34 |
| Key dependencies | UniVRM, uLipSync (`#upm`, unpinned), Genies SDK, URP | UniVRM (vendored), NativeWebSocket `#upm`, CoplayDev plugin `#beta`, unity-mcp `#main` | react-native-keychain, -permissions, -fs, google-signin, app-auth, nativewind | Compose BOM 2024.02, Room 2.6, Retrofit, libsu 5.2.2 (JitPack), TFLite 2.14, security-crypto 1.1.0-alpha06 |
| Android-specific finding | — | WebSocket to a LAN server IP in StreamingAssets | Requests SMS, call-log, CALL_PHONE, exact-alarm and Accessibility permissions | `FloatingBubbleService` declares `foregroundServiceType="systemExempted"`, which is reserved for system and privileged apps and is not usable by a normal app on Android 14+ |


## Summary matrix

| | OPEN-AVATAR | vrm-companion | sannabotapp (Sanna) | ava-android-assistant (AVA) |
|---|---|---|---|---|
| **What it is** | Unity framework: LLM → emotion → VRM avatar | Unity Android "display head" for a VRM avatar driven by a WebSocket server | React Native voice agent that operates the phone | Kotlin/Compose voice assistant with system controls |
| **Stack** | Unity 2022.3.62f3**c1** (China build), URP, C# | Unity 2022.3.62f3, C# | RN 0.84, React 19.2, TS, 29 Kotlin native modules | Kotlin 1.9.22, AGP 8.2.2, Compose, Room |
| **License** | MIT (LICENSE file) | **None** (all rights reserved) | MIT (stated in README, **no LICENSE file**) | MIT (LICENSE file) |
| **History** | 2 commits, last 2026-06-08 (one-shot "open-source release") | 5 commits, last 2026-03-17 | 55 commits, last 2026-03-27 | 2 commits, last 2026-06-12 |
| **Tests** | None found | None found | 1 smoke test (`App.test.tsx`) | None (`app/src` has only `main`) |
| **Android** | Not targeted (desktop scene, HUD) | Yes (Galaxy Tab A9+, portrait) | Yes, primary target | Yes, primary target |
| **Avatar** | VRM 0.x, BlendShape emotions, uLipSync, Animator + Playables | VRM via UniVRM, amplitude lip-sync, blink/breath/sway, Mixamo clips | 2D only (`SannaAvatar.tsx`) | None (Lottie bubble) |
| **Agent / tools** | LLM chat only (Ollama / custom JSON / mock) | None, server-driven | **Most mature**: tool loop, sub-agents (scheduler, notifications, accessibility), skills, personal memory, notification rules | Command dispatch, multiple providers, custom commands |
| **Memory** | Conversation only | None | Personal memory store, journal, conversation store | Room conversation history |
| **Background** | — | — | HeadlessJS services: scheduler, timer, notifications, accessibility; boot receiver | WorkManager, boot receiver, foreground voice service |
| **Secrets** | n/a | Server IP in StreamingAssets | `react-native-keychain` ✔ | `security-crypto 1.1.0-alpha06` (alpha, now deprecated) |
| **Main risks** | Genies SDK (proprietary, auto-managed folder); unpinned `uLipSync#upm` git dep; China editor build | No license; Hololive "Suisei" character IP referenced; raw Mixamo FBX redistributed; unpinned `CoplayDev …#beta` / `unity-mcp#main` editor AI plugins (supply chain) | Very broad permissions (READ/SEND_SMS, CALL_PHONE, READ_CALL_LOG, exact alarms, Accessibility) → Google Play restricted-permission policy; JS↔native bridge complexity | **Root shell** (`libsu`, arbitrary command exec); local LLaMA is a **placeholder** that returns fake replies (`LlamaLocalClient.kt` TODO); 6 exported components; TFLite deps for unimplemented code; old toolchain |

## Per-repository findings

### 1. OPEN-AVATAR (timesbye)
- **Reusable:** the emotion state machine (`AgentEmotion`, `EmotionExpressionMapper`), the dual-pipeline animation controller (Animator triggers plus a Playables crossfade fallback) and the idea of an LLM "mock mode". uLipSync (MIT, hecomi) is the right Unity lip-sync choice.
- **Bundled avatar:** `Avatar_Sunny.vrm`. Its VRM meta says author D1ON_WONG, commercial use allowed, `LicenseType: 2`, which is CC-BY in the UniVRM 0.x enum, so it needs attribution. It is a stylised anime avatar, not EVE, so we use it only for testing.
- **Concerns:** the `Assets/Genies` folder is managed by the Genies SDK, which has its own terms. Don't ship it without reviewing those. The repo is a single import commit with no tests. Its desktop HUD scene doesn't transfer to Android.
- **Verdict:** a reference for avatar behaviour code. Not a base.

### 2. vrm-companion (abdulyasir100)
- **Technically closest** to what EVE's avatar layer must do on Android: runtime VRM loading, a WebSocket link with exponential back-off, emotion → blendshape, amplitude lip-sync, idle blink/breath/sway, sleep mode, subtitles.
- **Legally unusable as code.** There's no LICENSE file, so default copyright applies: no right to copy or modify. The default models are named after a Hololive talent (third-party IP), and the VRM files themselves aren't committed. The Mixamo FBX clips are committed raw. Adobe's Mixamo terms allow using them in projects but not redistributing them as standalone files.
- **Supply chain:** editor packages pulled from moving branches (`#beta`, `#main`).
- **Verdict:** study the design and reimplement it. Copy nothing. If we want the code, ask the author for a license first.

### 3. sannabotapp (sannabotdev)
- **Strongest agent design.** `tool-loop.ts`, `conversation-pipeline.ts`, sub-agents for scheduler, notification and accessibility work, `skill-loader`/`skill-validator`, `personal-memory-store`, `accessibility-hint-store` (learns UI flows per app). It's the most actively maintained of the four.
- **Licensing:** "MIT" appears only in the README. Before copying code, record the README statement and ideally ask the maintainers to add a LICENSE file.
- **Android concerns:** it requests SMS, call-log and phone permissions. Google Play restricts these to default-handler apps, so a Play-distributed build would be rejected. Accessibility-based control of other apps also needs a policy declaration and prominent disclosure.
- **Stack mismatch:** React Native plus HeadlessJS adds a JS runtime and a bridge, and embedding a native 3D avatar is harder there than in a Kotlin app.
- **Verdict:** the architecture reference for EVE's agent, scheduler and automation layers. We port the concepts to Kotlin and don't fork the RN app.

### 4. ava-android-assistant (crftsmnd)
- **Right stack** (Kotlin, Compose, Room, WorkManager), with a feature list that overlaps EVE's phone features: floating bubble, notification listener, SMS and call receivers.
- **Quality issues:** `LlamaLocalClient` returns placeholder text, so it pretends to answer. `RootShell` runs arbitrary commands as root, which is unacceptable for an LLM-driven agent. It depends on an alpha crypto library, has no tests, uses an outdated AGP/Kotlin, and `FIXES.md` reads like auto-generated patch notes. There are 2 commits.
- **Verdict:** confirms the native stack choice. Reference it only for small snippets, such as the floating-bubble service pattern, after review. Don't use it as a base.

## Recommendation

**Don't fork any of the four.** Build EVE as a clean native Kotlin project (this repository):

| Layer | Foundation | Source of ideas |
|---|---|---|
| `android-app/` | New Kotlin + Jetpack Compose app | AVA confirms the stack; none of its code is used |
| `ai-brain/` | New pure-Kotlin module (agent loop, policy, memory, audit) | Concepts ported from SannaBot's tool loop, sub-agents and skills (MIT) |
| `avatar-engine/` | Renderer-neutral `AvatarController` interface now; Unity-as-a-Library + UniVRM + uLipSync later | OPEN-AVATAR (MIT) emotion mapping and mouth sync; VRM Companion's design only (no license) |

This keeps every reused piece MIT or reimplemented. The brain stays portable to Windows and robot hosts. And we don't inherit root access, fake local inference, a `systemExempted` service or Play-restricted permissions.

The file-by-file reuse list is in [REUSE_MAP.md](REUSE_MAP.md).
