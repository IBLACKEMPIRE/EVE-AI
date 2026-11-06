# EVE avatar production pipeline

## Status
- **No EVE reference images are in this repository** (correctly: `.gitignore` excludes `assets/eve-reference-images/` and this repo is public). Share them privately and they become the design source of truth for step 1. See DECISIONS D2.
- Reference images are 2D concept art, **not a usable 3D model**. A rigged character must be modelled, textured, rigged and animated. AI image-to-3D tools can give a blockout, but not a production-quality realistic human with clean topology and facial rig.
- The app currently draws an abstract 2D **placeholder**, labelled as such, that exercises the same `AvatarController` interface the real character will use.

## Renderer choice

| Option | Realism | Animation tooling | Android integration | Verdict |
|---|---|---|---|---|
| **Unity as a Library (URP / HDRP-lite)** | High (skin SSS approximations, hair cards) | Mecanim state machines, Timeline, uLipSync, UniVRM/glTF | Mature; ~25–40 MB APK overhead | **Production choice** |
| Filament / SceneView (glTF) | Medium–high PBR | You build state machines yourself | Native, light | Fallback if APK size is critical |
| Unreal (MetaHuman) | Highest | Excellent | Heavy on mobile; MetaHuman licence is restricted to Unreal Engine | Desktop/robot display only |

## Pipeline

1. **Design lock.** Create a turnaround sheet from the approved EVE images (front, side, back, ¾ view), face close-ups with neutral and key expressions, a hair and clothing breakdown, and body proportions in head-heights. The art director signs it off.
2. **Model.** Base mesh from Character Creator 4 (Headshot can project the face) or commissioned sculpting (ZBrush → retopology).
   - Budget for mobile: 40–60k triangles for body and clothing, ~15k for hair cards, 3–4 materials, 2K textures (4K for the face).
   - Separate LODs for the overlay mini-avatar.
3. **Facial rig.** The 52 ARKit blendshapes, plus the 15 visemes used by `ai.eve.core.avatar.Viseme` (SIL, PP, FF, TH, DD, KK, CH, SS, NN, RR, AA, E, IH, OH, OU). Add eye look and blink shapes, and corrective shapes for jaw-open with smile.
4. **Body rig.** Humanoid skeleton (Unity Humanoid / Mixamo-compatible), finger bones, and twist bones for forearms and upper arms. Add spring bones for hair and cloth (UniVRM spring bone or Magica Cloth).
5. **Animation set.** One clip per `BodyState`:
   - Idle (stand and sit, each with 3 variations), walk, turn left/right, sit down, stand up.
   - Explain-gesture ×4, wave, nod, head tilt.
   - Breathing additive layer.
   - Use mocap where possible and clean it in MotionBuilder or Blender.
6. **Unity project** (`avatar-engine/`, exported as an Android library).
   - The Animator has a base locomotion layer, a gesture layer (masked to the upper body), a face layer driven by code, and an additive breathing layer.
   - The `EveAvatarBridge.cs` message API mirrors `AvatarController`:
     - `setMode`, `setEmotion(emotion, intensity)`, `playVisemes(json)`, `setMouthOpen`, `playBody`, `lookAt`
   - Procedural layers: blink every 2–6 s, saccades, look-at the camera or user, micro head motion while speaking.
   - Lip-sync: uLipSync on the TTS audio for platform TTS, or viseme timelines from a cloud TTS (Azure viseme events). Crossfade visemes over 40–60 ms.
7. **Android integration.** The `avatar-engine` AAR, with a `UnityAvatarController` (Kotlin) that implements `AvatarController` by calling `UnityPlayer.UnitySendMessage`. Show it in-app on demand. Use a lighter LOD in the overlay (M7).
8. **QA gates.**
   - Side-by-side check of the identity against the reference images (face proportions, hairstyle, outfit).
   - At least 55 fps on the target mid-range device; thermal test of 20 minutes of conversation.
   - Lip-sync offset under 80 ms.
   - No uncanny artefacts in a review of expression transitions.

## Rights checklist
- You own the EVE design. Make sure the modeller's contract assigns copyright and grants commercial rights for real-time redistribution inside an app.
- Asset store or base-mesh EULAs must allow embedding in a distributed application (CC4/Daz: check the "game developer" or "interactive" licence).
- If the design is based on a real person, get a signed likeness release.
