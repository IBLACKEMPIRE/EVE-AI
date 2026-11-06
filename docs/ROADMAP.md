# Development milestones

| # | Milestone | Scope | Status |
|---|---|---|---|
| 1 | **Audit** | Verify reference repositories, licenses, build systems, APIs, maintenance and reuse strategy | ✅ Done: [REFERENCE_AUDIT.md](REFERENCE_AUDIT.md), [REUSE_MAP.md](REUSE_MAP.md), [DECISIONS.md](DECISIONS.md) |
| 2 | **Android shell** | Launchable app with basic navigation, permissions and placeholder avatar | 🟡 Code in PR; awaiting a green CI build and the device smoke test ([MVP_PLAN.md](MVP_PLAN.md)) |
| 3 | **Conversation** | Voice/text input, AI response, speech output and minimal secure memory | 🟡 Basic version in the same PR; hardening (voice-engine module, context trimming, locales) after M2 is verified |
| 4 | **Avatar** | Import the custom rigged EVE model; animate face, lips, body and gestures | ⏳ Blocked on D2–D4 |
| 5 | **Background** | Scheduled tasks, notifications, explicit approvals and activity history | ⏳ Approvals and activity history exist; WorkManager and notifications not started |
| 6 | **Integration** | Test performance, battery use, security, accessibility and APK packaging | ⏳ |
| 7 | **Overlay and automation** | Floating mini-avatar (`specialUse` foreground service); Accessibility automation with a per-app allow-list and on-screen indicator | ⏳ Needs D6 |
| 8 | **Cloud and other platforms** | Optional 24/7 relay; Windows host; robot (ROS 2) adapter reusing `ai-brain` | ⏳ Needs D5, D9 |

## Definition of done
Each milestone requires code, reproducible build instructions, tests and verified results. Do not claim APK functionality until an Android build succeeds.
