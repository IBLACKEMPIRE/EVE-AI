# EVE AI — Digital Human

Android-first AI assistant with a future photorealistic 3D avatar, voice conversation, persistent memory, and permission-controlled background tasks.

> **Status:** Phase 1 audit complete. The Android MVP foundation **builds, passes unit tests and lint in CI** (debug APK produced). It has **not yet been smoke-tested on a physical device**. The 3D avatar is not implemented; a 2D placeholder stands in.

## Goals
- Android application with voice and text conversation
- Rigged 3D EVE avatar with expressions, lip sync and gestures
- Persistent, user-controlled memory
- Background scheduling and notifications within Android restrictions
- Optional remote service for continuous autonomous tasks
- Future Windows and robotics compatibility

## Reference projects to audit
- [OPEN-AVATAR](https://github.com/timesbye/OPEN-AVATAR)
- [SannaBot](https://github.com/sannabotdev/sannabotapp)
- [VRM Companion](https://github.com/abdulyasir100/vrm-companion)
- [AVA Android Assistant](https://github.com/crftsmnd/ava-android-assistant)

These are **references, not integrated dependencies**. The audit is in [docs/REFERENCE_AUDIT.md](docs/REFERENCE_AUDIT.md), and the file-level reuse plan is in [docs/REUSE_MAP.md](docs/REUSE_MAP.md). No reference code has been copied so far.

## Repository structure
- `docs/`: [architecture](docs/ARCHITECTURE.md), [roadmap](docs/ROADMAP.md), [audit](docs/REFERENCE_AUDIT.md), [reuse map](docs/REUSE_MAP.md), [MVP plan](docs/MVP_PLAN.md), [open decisions](docs/DECISIONS.md), [licensing](docs/DEPENDENCIES_LICENSING.md), [avatar pipeline](docs/AVATAR_PIPELINE.md)
- `ai-brain/`: agent orchestration, safety policy, memory, audit (pure Kotlin, portable). **Implemented, 25 unit tests**
- `android-app/`: Android host application (Kotlin + Compose). **MVP implemented; CI build green; device test pending**
- `voice-engine/`: speech pipeline. Currently `android-app/.../voice/`; extracted at M3
- `avatar-engine/`: avatar rendering and animation. Placeholder in `android-app/.../avatar/`; Unity module at M4
- `background-services/`: scheduled tasks and notifications (M5, not started)
- `assets/`: public, redistributable assets only

## Build

Requirements: JDK 17+, Android SDK (platform 35) via Android Studio, internet for the first Gradle sync.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties      # or open the project in Android Studio
./gradlew :ai-brain:test :android-app:testDebugUnitTest  # unit tests
./gradlew :android-app:assembleDebug                     # -> android-app/build/outputs/apk/debug/android-app-debug.apk
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
```

On Windows use `gradlew.bat`. CI ([.github/workflows/android.yml](.github/workflows/android.yml)) runs tests, lint and the debug build on every push, and uploads the APK as the `eve-debug-apk` artifact.

Brain tests without Gradle or an SDK: `KOTLIN_HOME=/path/to/kotlinc ./tools/test-core.sh`.

## Security
Never commit API keys, tokens, private conversations, personal data, or proprietary EVE reference images without explicit approval.
