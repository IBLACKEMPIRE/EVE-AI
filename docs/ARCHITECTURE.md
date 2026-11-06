# EVE AI — Initial architecture (proposed)

## Android host
Native Android (Kotlin) for permissions, foreground service rules, notifications, audio routing and optional overlay. Rendering engine selection is pending repository audit and performance tests.

## Avatar engine
Candidate: Unity/VRM, with mobile-optimized rig, facial blendshapes, lip sync and animation state machine. Existing EVE images are design references, **not** a rigged model.

## AI brain
Provider-agnostic conversation and tool interface. Long-term memory must be user-controlled and deletable. External services require explicit consent and secure key storage.

## Voice
Microphone capture with permission, speech recognition, speech synthesis and audio-driven lip sync. No promise of always-on hotword support across all Android states.

## Background work
Android WorkManager for deferrable tasks, foreground services only for permitted user-visible ongoing operations, and optional server-side workers for 24/7 tasks. Sensitive actions require confirmation and audit logs.

## Boundaries
Modules communicate via versioned interfaces. No code is copied from reference repositories until licensing and compatibility are verified.
