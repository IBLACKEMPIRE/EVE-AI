# Dependencies and licensing

## Current prototype dependencies

| Dependency | Version | License | Used for |
|---|---|---|---|
| Kotlin stdlib, kotlinx-coroutines | 2.1.21 / 1.10.2 | Apache-2.0 | everything |
| AndroidX core-ktx, activity-compose, lifecycle | 1.16.0 / 1.10.1 / 2.9.0 | Apache-2.0 | app shell |
| Jetpack Compose BOM, Material 3 | 2025.05.00 | Apache-2.0 | UI |
| JUnit 4 (test) | 4.13.2 | EPL-1.0 | tests |
| org.json (test only) | 20250107 | Public domain (JSON License removed since 2022) | JVM unit tests of the Claude mapping |
| Android platform: SpeechRecognizer, TextToSpeech, Keystore, org.json | SDK 35 | Apache-2.0 | voice, secrets, JSON |
| WorkManager (planned, M5) | 2.10.x | Apache-2.0 | background tasks |

There are no native, closed-source or alpha libraries. Everything in the APK is Apache-2.0.

## Reference repositories: what we may reuse

| Repo | License | May copy code? | Obligations |
|---|---|---|---|
| OPEN-AVATAR | MIT © 2026 D1on_cn | Yes | Keep the copyright and MIT notice in `THIRD_PARTY_NOTICES` for any copied files |
| ├ Avatar_Sunny.vrm | VRM meta: CC-BY, commercial OK, author D1ON_WONG | Test only | Credit the author if it's ever shown |
| ├ Genies SDK folder | Genies' own terms | **No** (not needed) | — |
| └ uLipSync | MIT © hecomi | Yes | MIT notice. Pin a release tag, not `#upm` |
| vrm-companion | **No license** | **No** | Reimplement from the design only, or get written permission |
| ├ Mixamo FBX clips | Adobe Mixamo terms | Download our own from Mixamo | Use in the app is allowed; don't redistribute raw files in a public repo |
| └ "Suisei" models | Third-party IP (Hololive/Cover) | **No** | — |
| sannabotapp | MIT (README statement only) | Yes, with care | Archive the README statement. Ask the maintainers for a LICENSE file. Include the MIT notice |
| ava-android-assistant | MIT © 2026 Maya AI Development Team | Yes | MIT notice. We currently copy nothing |

**Current status:** this repository contains **no code copied** from any reference repo. Everything is written from scratch, so there are no attribution obligations yet. Add a `THIRD_PARTY_NOTICES.md` the moment anything is copied.

## Planned production dependencies (to review at each milestone)

| Component | Candidate | License / cost | Note |
|---|---|---|---|
| 3D runtime | Unity 2022.3 LTS / Unity 6 as a Library | Unity EULA; Personal tier below the revenue threshold, otherwise Pro | Use the global editor build, not the `c1` China build |
| VRM / glTF | UniVRM | MIT | |
| Lip-sync | uLipSync | MIT | |
| Character | Commissioned EVE model **or** Character Creator 4 / Daz | Per-asset EULA. Check the "game/real-time redistribution" clause | You must own the commercial rights. See 05 |
| Animations | Mixamo / mocap / Move.ai | Mixamo free for projects; mocap per vendor | |
| Wake word | Porcupine (Picovoice) or openWakeWord | Commercial / Apache-2.0 | |
| Cloud TTS with visemes | Azure Neural TTS (viseme events) or ElevenLabs (alignment) | Paid API | Gives true phoneme timing for lip-sync |
| On-device STT | whisper.cpp / Vosk | MIT / Apache-2.0 | For offline use |

## Security notes
- Root access (AVA's `libsu`) is excluded permanently.
- SMS, call-log and phone-state permissions are excluded until a SENSITIVE-gated milestone, and even then they block Google Play distribution unless EVE is the default handler. The prototype uses intents instead (dialer, SMS draft), which need no permission.
- Pin Unity git dependencies to tags or commits. Don't install editor AI plugins from moving branches.
