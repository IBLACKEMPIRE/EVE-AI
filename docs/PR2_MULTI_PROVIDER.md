# PR #2 — Multi-provider intelligence (development)

## Confirmed product decisions
- **Automatic provider routing**; priority Anthropic → OpenAI → Gemini based on which keys are configured.
- **Personal API keys**, encrypted at rest using the existing Android Keystore-backed SecureSettings.
- **Multi-provider collaboration enabled** in Settings; read-only requests with no tool declarations fan out and label each provider response. For tool-enabled requests, only one model receives the request to prevent duplicate or conflicting phone actions.
- No keys or proprietary reference images belong in GitHub.

## Implemented
- Provider-neutral MultiProviderClient with transient-error fallback, no fallback for non-retryable API errors, and concurrent read-only collaboration.
- OpenAI Responses API client and Gemini generateContent client, including tool-call response mapping.
- Three key inputs and collaboration switch in Android Settings.
- Four core tests for fallback, non-retryable failures, collaboration, and single execution path for tool calls.

## Known limitations / required review
1. **Collaboration is limited to requests with no exposed tools.** The current Agent always advertises its registered tools; therefore ordinary chat currently follows single-provider routing. Implement explicit read-only collaborative analysis as a separate feature in a later commit, without executing tools in secondary models.
2. The provider order is fixed; no quality/cost-based classifier or manual provider override yet.
3. Gemini synthetic tool IDs and OpenAI/Gemini tool-call histories require live integration tests with real keys, including multiple tool calls and retries.
4. API keys are held in app-private encrypted preferences, but providers receive the prompt over the network; document this in onboarding.
5. The existing local audit log still records prompt excerpts. Redaction and deletion controls are required before release.
6. The Anthropic default model ID must be verified with a real API request.
7. API errors and provider selection should be made visible to users without exposing credentials.
8. Need tests for both Android JSON parsers, offline fallback, device upgrade, voice, memory, emergency stop and regression.
9. Model names are initial defaults, not dynamically discovered. Verify supported IDs before release.
10. All three API calls are direct from the device. For a commercial public product, a server-mediated credential strategy may be preferable.

## Verification
CI and physical Android tests **pending**. This branch is not approved for merge.
