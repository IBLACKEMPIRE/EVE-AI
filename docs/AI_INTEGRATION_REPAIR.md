# AI integration repair: audit, root cause and fixes

Branch `dev/ai-integration-repair`, based on `main` at `83eed94` (includes PR #3 and PR #4).
API facts were checked against the official Anthropic, OpenAI and Google documentation on 2026-11-06.

## 1. Root cause of "I couldn't reach my reasoning service"

There are several independent defects. Each one alone could produce the symptom.

| # | Defect | Status | Evidence |
|---|---|---|---|
| R1 | **Corrupted Anthropic model ID.** Before PR #4, Settings pre-filled the "Model" field with the router's display name (`auto:anthropic:claude-sonnet-5-5,openai:gpt-4.1-mini,…`). Pressing **Save** stored that string as the Claude model, so every Claude request was rejected. PR #4 fixed the screen but did **not** repair phones that had already saved the bad value. | **Confirmed in code** (`git show f8ae837`; no migration existed) | Fixed by the settings v2 migration (`SecureSettings.migrate`) and model-ID validation (`ModelCatalog.isPlausibleModelId`). Test: `corruptedRouterLabelIsNeverUsedAsModelId` |
| R2 | **No fallback on non-retryable errors.** The pre-PR #4 router stopped at the first 4xx (for example the 404 caused by R1), so valid OpenAI and Gemini keys were never tried. | **Confirmed in code** (fixed by PR #4 using string matching; now replaced by classified `ErrorKind.failover`) | `RouterTest.fallbackOnProviderFailureAndReportsOutcome` |
| R3 | **Claude tool use broken on current models.** Claude Sonnet 5.5 thinks adaptively by default, and the docs require *thinking blocks to be passed back* with `tool_use` during tool use. The client dropped them, so the second call of any tool round trip (remember, time, open app…) would be rejected. | **Confirmed against docs**; not observed live | `anthropicReplaysThinkingBlocksDuringToolUse`, `EndToEndTest.claudeToolRoundTrip…` |
| R4 | **Gemini 3/4 function calling broken.** The docs say thought signatures *must* be returned or the API answers with a 4xx. The client rebuilt parts and discarded `thoughtSignature`. It also sent `parameters` with empty `properties` for argument-less tools (`get_time`, `list_tasks`, `forget_all_memories`); Gemini rejects empty OBJECT schemas. | **Confirmed against docs / known API validation** | `geminiOmitsEmptySchemasAndReplaysThoughtSignatures` |
| R5 | **Output budget too small.** `max_tokens` was 1024. Thinking and reasoning tokens count against this budget, so answers could come back empty or cut off, and EVE showed nothing. | **Confirmed behaviour class**, magnitude unverified | Now 4096; truncation is reported to the user (`truncatedEmptyAnswerIsExplained`) |
| R6 | **Errors were never classified.** Every failure became one generic sentence, so a wrong key, no credit, an unknown model and no network all looked the same. | **Confirmed** | `ErrorClassifier` + per-provider tests |
| H1 | OpenAI default `gpt-4.1-mini` is an older model; it is still listed in the API catalog. | Hypothesis: not a cause of failure | Default changed to `gpt-6-luna`; any ID can be chosen |
| H2 | `claude-sonnet-5-5` is **valid**: it is the current Sonnet on the Claude API. The default ID was not the bug; the corrupted saved value (R1) was. | Verified in docs | — |

**Without the phone's actual error we can't say which of R1–R5 hit this device first.** The new build shows the classified reason for each provider (for example "Claude: unsupported or unavailable model ID"). Running **Settings → Test all providers** on the phone will confirm it.

## 2. Other defects found and fixed

| Area | Defect | Fix |
|---|---|---|
| STOP | HTTP requests ran on a blocking thread. Cancelling the coroutine did not abort them, so STOP waited for the read timeout (up to 90 s) | `UrlConnectionTransport` disconnects the socket on cancellation (`cancellationAbortsAnInFlightRequestImmediately`) |
| Fallback safety | Switching provider mid tool loop sent provider A's tool IDs and state to provider B, which would be rejected. A fallback model could also repeat a phone action | The router stays with the tool-calling provider. Turns from another provider are converted to text ("EVE already executed: …"). The agent suppresses identical repeated side-effect tool calls within a request (`providerSwitchMidLoopDoesNotRepeatThePhoneAction`) |
| Collaboration | It only ran when no tools were declared, which never happened, so it never ran. Its output also just pasted provider answers together | `CollaborationEngine`: eligibility heuristic, read-only analysts (no tools, ever), explicit synthesis stage, agreement note, graceful partial failure, and a single-answer fallback instead of concatenation |
| Privacy | OpenAI used the default `store` (server-side storage) | `store: false`; encrypted reasoning items are replayed per the stateless pattern |
| Settings | One shared "Remove key" button removed only the Claude key. There was no per-provider model, enable switch, test or status | Per-provider cards; key show/hide; model validation and suggestions; enable; Save/Test/Delete; status |
| Routing | Fixed priority only; no manual mode, preferred provider or fallback consent | `RoutingPolicy` (automatic/manual, preferred, fallback toggle) |
| UX | The header showed the router label, the same string that caused R1 | Display-only label that is guaranteed not to look like a model ID (`LabelsTest`) |
| Portability | Providers lived in the Android module (org.json), so they couldn't be tested here or reused on Windows/robots | Moved to `ai-brain` (JDK HTTP + a small JSON library) |

## 3. Security review

| Item | Result |
|---|---|
| Keystore | AES-256-GCM, non-exportable key `eve_api_key_v1` (alias unchanged, so existing ciphertext stays readable). An unreadable key (for example after a Keystore reset) is now shown as such instead of silently disappearing |
| Key exposure | Keys appear only in request headers to the official endpoints. Never in UI, audit, exceptions or diagnostics (`anthropicErrorsAreClassifiedWithoutLeakingBodies`, end-to-end audit check) |
| Error bodies | Parsed for classification only and then discarded. Messages contain provider + category, never raw text |
| Audit log | Records provider and category, never prompts, outputs or error bodies (redaction from PR #3 kept) |
| Network | HTTPS endpoints only; no cleartext; no new permissions |
| Provider data sharing | Prompts go only to **enabled** providers. With fallback **off**, a conversation never leaves the chosen provider. In manual mode only the chosen provider is used. Collaboration (on by default per the owner's decision) sends complex questions to every enabled provider, and the Settings switch carries an explicit cost/privacy note |
| Phone actions | Only the primary agent loop can call tools, and every call passes the PolicyEngine and approvals. Collaboration analysts and the synthesizer receive no tool declarations |
| STOP | Cancels the agent, in-flight HTTP, collaboration analysts and connection tests. The policy still denies every tool until RESUME |
| Upgrade safety | Same `applicationId` (`ai.eve.android`), same preference file, keys and memory files. The migration only removes invalid model IDs |
| Backups | `allowBackup="false"` (unchanged), so keys and memories are not copied to cloud backups |

## 4. Tests

| Suite | Count | Where it runs |
|---|---|---|
| `ai-brain` (JSON, providers, error classes, transport with a local HTTP server, router, collaboration, end-to-end agent, legacy agent/policy/memory) | 58 | `tools/test-core.sh` (here) and `:ai-brain:test` (CI) |
| `android-app` JVM (`LabelsTest`) | 2 | Locally with kotlinc, and `:android-app:testDebugUnitTest` (CI) |
| Live smoke (`:ai-brain:liveSmoke`) | per provider: connection + tool round trip | Only with real keys: local env vars, or the manual `live-smoke` CI job with secrets `EVE_ANTHROPIC_API_KEY`, `EVE_OPENAI_API_KEY`, `EVE_GEMINI_API_KEY` |

**Not automated (no emulator/device in CI):** Keystore save/reload, voice recognition, TTS, avatar rendering and STOP on a device. These are in the device checklist below.

## 5. Install, verify and roll back

**Install over the existing app (keeps keys and memories):**
1. Download the `eve-debug-apk` artifact from the PR's checks and unzip it.
2. `adb install -r android-app-debug.apk`, or open the APK on the phone.
   - It is signed with a debug key, like earlier CI builds. If Android reports a signature conflict (it was installed from a different machine's debug key), don't uninstall unless you accept losing local data; ask for a build signed with the same key.
3. Open EVE → Settings. Each provider shows "Key saved · not tested yet". Tap **Test all providers**.

**Device checklist:** (1) Test all providers shows ✓ or a specific reason for each. (2) Ask "what time is it" with each provider set to Manual: answer plus "via …" line. (3) "remember that my sister is Dana" with Claude (tool round trip). (4) Disable the preferred provider with fallback on, then off. (5) A complex question ("Compare renting vs buying for a student") shows "Claude + GPT + Gemini · synthesized by …". (6) Press STOP during a collaboration: it stops at once. (7) Earlier memories are still listed. (8) Voice in/out and avatar still work.

**Roll back:** reinstall the previous APK artifact (same package, so data is kept), or revert the merge commit on `main`. The settings migration only removes invalid model IDs, so older builds read the same preferences.

## 6. Limitations and what remains unverified

- **No live API calls were made from the development environment.** No API keys are available there, and none may be committed. Every live acceptance criterion (Claude/GPT/Gemini answering with real keys) is **unverified** until the live smoke job or the device checklist runs.
- Model IDs come from the official docs on 2026-11-06. The test button confirms availability for a specific key.
- The collaboration eligibility test is a keyword/length heuristic (English, Hebrew, Arabic keywords); it may miss or over-trigger some questions.
- Collaboration answers are not spoken until synthesis completes, so expect higher latency.
- Device-level behaviour (Keystore, voice, avatar) has no automated tests; there is no emulator in CI.
