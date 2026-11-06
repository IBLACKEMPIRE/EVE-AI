# Open decisions and blockers (need the owner's decision)

| # | Decision | Why it blocks | Options | Recommendation |
|---|---|---|---|---|
| D1 | **License for this repository** | The repo is **public** and has no LICENSE file, so legally nobody else may use it, and contributors' rights are unclear | MIT / Apache-2.0 (open) · proprietary "All rights reserved" · make the repo private | Decide before accepting outside contributions. If EVE is a commercial IBLACK EMPIRE product, either make the repo private or add an explicit proprietary notice |
| D2 | **EVE reference images** | The avatar pipeline can't start without them. `.gitignore` already excludes `assets/eve-reference-images/`, which is correct for a public repo | Share privately (Drive or a private repo) · commit only non-sensitive turnarounds | Keep them out of this public repo. Share them privately for the modeller |
| D3 | **3D character production route** | Determines cost, realism and licensing | Commission a custom model (best fidelity, you own the IP) · Character Creator 4 + Headshot (faster; check its real-time redistribution EULA) · stylised VRM (cheapest, not photorealistic) | Commission, or CC4 with a confirmed EULA |
| D4 | **Avatar runtime** | Affects APK size and the avatar-engine design | Unity as a Library (recommended) · Filament/SceneView · Unreal (desktop/robot only) | Unity; requires a Unity licence decision (Personal vs Pro, depending on revenue) |
| D5 | **Default AI provider and who pays** | The MVP calls the Claude API with a user-entered key | Each user brings a key · your backend proxies requests with your key (needs a server, auth and rate limits) | BYO key for the MVP; backend proxy at M8 |
| D6 | **Distribution channel** | Google Play forbids SMS/call-log permissions unless the app is the default handler, and requires an Accessibility declaration | Google Play (restricted permissions stay out) · direct APK / enterprise (wider automation possible) | Plan for Play compliance; keep high-risk automation as an opt-in sideload build flavour |
| D7 | **SannaBot license confirmation** | Its MIT grant appears only in the README | Ask maintainers to add a LICENSE file · port concepts only (current approach) | Port concepts only until confirmed |
| D8 | **VRM Companion** | No license | Ask the author for permission · reimplement (current approach) | Reimplement |
| D9 | **Cloud 24/7 service** | Needs hosting, cost and a data-protection posture | None · relay server · managed workflow platform | Defer until M8 |
| D10 | **Primary languages** | Speech and TTS locales, UI strings, RTL layout (Hebrew, Arabic) | Device locale only · explicit choice in Settings | Confirm which languages EVE must speak at launch |
