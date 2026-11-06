# Phase 2 — EVE Intelligence & Reliability

Status: **planning only**. The Phase 1 Android smoke test passed all six reported checks on a physical phone. No Phase 2 implementation is claimed by this document.

## Scope (in priority order)
1. **Live Anthropic integration:** verify actual model identifier, API errors, network timeouts, tool-use round trips, key rotation/removal. Do not hard-code or commit API credentials.
2. **Privacy:** stop storing raw user prompts in plaintext audit logs; redact tool outputs and exception messages; document retention and deletion behavior; add tests.
3. **Memory:** confirm persistence after force-stop, recall behavior, deny/approve deletion, Settings deletion, and correct audit behavior.
4. **Emergency stop:** verify cancellation of in-flight HTTP, TTS, speech input, and pending approvals. No further tool calls until resume.
5. **Voice:** handle missing recognition service, mic permission denial, interruption, locale selection, and speech output lifecycle.
6. **Languages:** establish English, Hebrew and Arabic localization with RTL layout tests; test supported STT/TTS locales on device.
7. **Tasks:** persist task history (not only current process); refresh task/audit screens when revisited.
8. **CI:** unit tests, lint, assembleDebug, privacy regression tests and repeatable device checklist.

## Acceptance criteria
- A real-key Anthropic chat and tool-use round trip succeeds on a test device.
- No API keys or raw conversation content appear in diagnostic audit files.
- Memory and task persistence survive app restart, with explicit deletion controls.
- STOP cancels current work and blocks new actions until RESUME.
- English, Hebrew and Arabic UI navigation renders without overlap; RTL direction is correct.
- Tests, lint, APK build and device regression checks pass.
- No new sensitive Android permissions without a separate review.

## Out of scope
Photorealistic 3D avatar, Unity integration, overlays, Accessibility automation, background autonomous work, cloud relay and robot control. These belong in later PRs.

## Owner decisions
- Confirm Anthropic API billing/key ownership (BYO key vs backend).
- Confirm English/Hebrew/Arabic as launch languages.
- Decide whether this commercial repository should remain public.
- Provide any device-specific failure reports and Android version.

## Development discipline
Implement in small commits; link evidence to each acceptance criterion. Do not merge until code review, green CI and a physical-device regression test.
