# EVE AI — Unified architecture

Module ↔ folder mapping: `ai-brain/` (Gradle `:ai-brain`) and `android-app/` (Gradle `:android-app`). `voice-engine`, `avatar-engine` and `background-services` currently live as packages inside `android-app` and will be extracted to their own folders when they gain dependencies (see [MVP_PLAN.md](MVP_PLAN.md)).

## Layering

```
┌───────────────────────────── Host app (Android today; Windows / robot later) ─────────────────────────────┐
│ UI (Compose)      Voice I/O            Avatar renderer           Platform tools         Background (M5)    │
│ chat, approvals,  SpeechRecognizer,    PlaceholderAvatar (now)   open_app, alarm,       WorkManager:       │
│ tasks, settings,  TextToSpeech →       Unity-as-a-Library (M4)   dialer, SMS draft,     reminders,         │
│ STOP button       viseme mouth         implements AvatarController list_tasks          headless agent     │
│ SecureSettings (Android Keystore AES-GCM)          AnthropicClient (Claude Messages API, tool use)         │
└──────────────────────────────────────────────┬─────────────────────────────────────────────────────────────┘
                                               │ depends on (never the reverse)
┌──────────────────────────────────── :ai-brain — pure Kotlin/JVM, no Android────────────────────────────────┐
│ agent/   Agent: plan → policy → approval → act → observe loop, retries, step limit, cancellation          │
│ tools/   Tool, RiskLevel, ToolRegistry, CoreTools (time, memory)                                           │
│ policy/  PolicyEngine, EmergencyStop, ApprovalGateway (UI dialog / DenyAll for headless)                   │
│ memory/  MemoryStore (keyword recall now; embeddings later), FileMemoryStore                               │
│ audit/   AuditLog (append-only, secrets never written)                                                     │
│ tasks/   TaskTracker (status + history), ScheduledWorkflow/Trigger, RetryPolicy                            │
│ llm/     LlmClient contract, MockLlmClient (offline demo)                                                  │
│ avatar/  AvatarController, AvatarMode, Emotion, Viseme, BodyState, VisemeMapper                            │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                     optional ↕ HTTPS
┌──────────── Cloud services (optional, M6) ───────────┐
│ EVE relay: 24/7 workflows, push via FCM, cloud TTS   │
│ with viseme timing, encrypted memory sync            │
└───────────────────────────────────────────────────────┘
```

## Key decisions

| Decision | Choice | Why |
|---|---|---|
| App stack | Kotlin + Jetpack Compose | Native access to services, accessibility and overlays. No JS bridge. AVA's stack, minus its code |
| Portable brain | `:ai-brain` with no Android imports | The same agent runs on Windows (Compose Desktop / JVM) and on robot hosts (JVM on Linux). It's tested on a plain JVM |
| LLM | Claude Messages API with native tool use, through the `LlmClient` interface | Reliable tool calling. The provider can be swapped. `MockLlmClient` gives an offline demo with no key |
| Safety | Deterministic `PolicyEngine` between the model and the device | The model proposes calls. Only policy plus the user decide. See the table below |
| Background (M5) | WorkManager | Survives reboot, respects Doze. The honest Android contract (minutes-level timing, 15-minute minimum repeat) |
| Secrets | Android Keystore AES-256-GCM | The key never leaves the Keystore and is never logged or exposed as a tool |
| Avatar | `AvatarController` interface; 2D placeholder now, then Unity-as-a-Library | The renderer can change without touching the agent or UI |

## Risk policy (enforced in `PolicyEngine`, covered by unit tests)

| RiskLevel | Examples | Interactive | Headless (background) | Standing grant possible |
|---|---|---|---|---|
| READ_ONLY | get_time, recall, list_tasks | runs | runs | n/a |
| LOW | open_app, set_alarm, dialer/SMS *draft* | runs | runs | n/a |
| SENSITIVE | read SMS, place call (future) | asks | **refused** | yes, revocable |
| DESTRUCTIVE | forget_memory, forget_all_memories | asks every time | **refused** | no |
| FINANCIAL | payments (none implemented) | asks every time | **refused** | **never** |
| CREDENTIAL | passwords/tokens (none implemented) | asks every time | **refused** | **never** |

Some rules hold regardless of risk level:
- **Emergency stop** denies every call, cancels the running task and clears pending approvals. From M5 it also cancels all WorkManager jobs.
- An approval request that times out (60 s) counts as **deny**.
- Unknown tools are denied.
- A tool's risk is set by its author, and the model can't lower it.
- The audit log records every proposal, denial, approval, result and stop.

## Agent loop

1. Create a task (PENDING → RUNNING) and audit the input.
2. Build the system prompt: persona, current time, and the top-k memories the user explicitly asked EVE to store.
3. Call the LLM. Transient errors retry with exponential backoff; 401/400 fail fast.
4. If the reply has no tool calls, it's final: parse the emotion tag, then SUCCEEDED.
5. For each tool call: policy → (approval) → execute. Exceptions become error results, not crashes. Append the observation.
6. Repeat until done, the step limit (FAILED), emergency stop (CANCELLED) or coroutine cancellation (CANCELLED).

## Porting targets
- **Windows:** a Compose Desktop host reusing `:ai-brain` as-is. The avatar is the Unity standalone player implementing the same message protocol.
- **Robot:** a JVM host on the robot's Linux computer. `AvatarController.playBody(...)` maps to motion primitives, and tools wrap ROS 2 services. Physical-motion tools are registered as SENSITIVE or higher.
