# EVE AI — Digital Human

Android-first AI assistant with a future photorealistic 3D avatar, voice conversation, persistent memory, and permission-controlled background tasks.

> **Status:** Planning / repository bootstrap. No Android APK or working avatar is implemented yet.

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

These are **references, not yet integrated dependencies**. Verify availability, licensing, Android compatibility and security before reusing code.

## Repository structure
- `docs/`: architecture, roadmap, repository audit
- `android-app/`: Android host application (to be implemented)
- `avatar-engine/`: avatar rendering and animation (to be implemented)
- `ai-brain/`: agent orchestration and memory (to be implemented)
- `voice-engine/`: speech pipeline (to be implemented)
- `background-services/`: scheduled tasks and notifications (to be implemented)
- `assets/`: public, redistributable assets only

## Security
Never commit API keys, tokens, private conversations, personal data, or proprietary EVE reference images without explicit approval.
