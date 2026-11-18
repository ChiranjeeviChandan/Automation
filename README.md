# Verity — an AI camera that grades, never repaints

An Android camera and photo enhancer built around the *Natural 4K Photographic Enhancement* brief
(`director/src/main/resources/master_prompt.md`, embedded verbatim as Claude's system prompt).

## The core decision

The brief contradicts itself if you hand it to a generative image model. "Enhance to 4K", "recover detail",
"restore old photos" all invite a diffusion/GAN model to **re-synthesise** pixels. Re-synthesis is exactly what
breaks "never change anyone's face", "never invent missing details", "never alter jewellery". Every generative
enhancer drifts identity a little; on group photos it drifts everyone independently.

So Verity splits the job:

| Layer | Who | Does | Cannot |
|---|---|---|---|
| Judgement | Claude (`:director`) | Looks at a 1024-px preview + measured statistics, decides scene type, which colour cast is unwanted vs. atmosphere, how far to lift shadows, how much to protect skin | Return pixels. Its output is 19 numbers, each clamped to a safe range |
| Execution | Deterministic renderer (`:core`) | Tone and colour transforms, edge-aware local contrast, noise reduction, halo-clamped sharpening, Lanczos 4K resize, square framing | Move, warp, or synthesise a pixel |

Identity preservation is therefore a property of the architecture, not a request the model may ignore.
Offline (no key, no network, refusal, malformed answer) the deterministic auto-grader is used; the app always works.

## Pipeline (`core/…/Renderer.kt`)

1. White balance + exposure: per-channel gains in linear light. Illuminant = grey-world refined twice on pixels that look neutral *after* correction. Strong casts are only partly removed (golden hour, candle light survive).
2. Tone curve on luminance, applied as an RGB ratio (hue-preserving; skin does not shift toward yellow when brightened). Black point, shadow lift, exponential highlight shoulder, midtone contrast. Proven monotonic across the whole parameter box (test).
3. Soft gamut mapping: out-of-range colours desaturate toward luminance instead of clipping one channel.
4. Clarity: fast guided filter (He et al.) base/detail split on a 0.4 MP proxy, coefficients upsampled per pixel. Edge-aware, so no halos; midtones only.
5. Chroma noise reduction: luma-guided filter on Cb/Cr with coring, so true colour edges survive.
6. Colour: vibrance, saturation and four hue bands (reds, golds, greens, blues), multiplied by `1 − skinProtect × skinLikelihood`, with a hard chroma ceiling. Near-greys are never boosted (that re-creates casts).
7. Optional 1:1 profile crop: head-and-shoulders box sized to fit inside the circular avatar mask.
8. Optional Lanczos-3 upscale to a 3840-px long edge.
9. Detail pass at output size: 3×3 base/residual, noise-level residuals attenuated, larger ones amplified, clamped to local min/max (no ringing).

No full-resolution float buffers: a 12 MP frame is processed in one row-parallel pass over packed ARGB.

## Object and person removal (explicit request only)

The brief allows removal "only when explicitly requested", so nothing is ever removed automatically.

**Selecting** (Remove objects tab):
- *Brush / Unselect* — paint over what should go; trim with Unselect.
- *AI suggestions* — Claude (`director/…/RemovalScout.kt`) proposes up to 6 distractions (photobombers behind the subject, litter, wires, bins, signs) as boxes. It is instructed never to propose the main subject, anyone in the group, clothing, jewellery, or religious and cultural elements. Tap a chip to accept.
- *Describe what to remove* — "the man in the red shirt on the left": Claude locates it and pre-selects it. Boxes are approximate; check and trim before removing.

**Filling** (`core/…/ObjectRemover.kt`, all on the phone):
1. Selection grown by ~0.4% of the long edge so outlines, hair and contact shadows go too.
2. Optional neural pass: LaMa (ONNX, ~200 MB, downloaded from Settings) sees a context crop at 512 px and proposes the structure behind the object.
3. PatchMatch completion (`PatchInpainter.kt`: multi-scale PatchMatch + EM voting + dominant self-similarity offsets) rebuilds the region at full resolution from real patches of the same photo, guided by the neural structure if present. This replaces the model's soft low-res texture with the scene's own grain.
4. Only selected pixels are written; every other pixel is bit-identical (tested).

Undo is one level. Saved files carry `Edited with Verity: … object removal` in their MediaStore description; originals are never overwritten.

Measured (JVM, 4 cores): 80×100 px object on a brick wall, 0.25 s, fill error 11.2 vs 26.1 for an ideal flat fill; 12 MP frame with a 400×900 px person-sized hole, 5.5 s. Phones will be slower.

## What it will not do (deliberately)

- **"4K" is interpolation, not super-resolution.** Phones already shoot 12 MP (> 4K UHD), so most photos are not upscaled at all. Small/old images are resized cleanly; no detail is invented, per brief §9.
- **Scratches on old photos are not auto-detected.** Paint over them in Remove objects; the fill borrows from the surrounding print.
- **Removal is never automatic.** Even AI suggestions need a tap.

## Modules

- `core/` — pure Kotlin/JVM pipeline. `gradle :core:test` (10 tests: cast removal, skin hue drift < 4°, edge positions, saturation ceiling, curve monotonicity, 4K geometry, avatar circle fit; writes `core/build/visual/before.png|after.png`).
- `director/` — Claude integration via the official `anthropic-java` SDK: `claude-opus-5-5`, structured JSON output, low effort, server-side refusal fallback, image input. `gradle :director:test`.
- `inpaint/` — ONNX Runtime wrapper for LaMa-style models (`OnnxInpainter`). Tested against a toy model with LaMa's exact I/O signature; the real model's output quality is not tested here.
- `app/` — Android (Compose + CameraX): capture, gallery import, "Share to Verity", before/after drag comparison, strength slider (blends parameters, not pixels), Save 4K, Save profile 1:1 to `Pictures/Verity`. Settings hold the API key in Keystore-encrypted prefs.

## Build

```bash
# JVM modules (no Android SDK needed)
./gradlew :core:test :director:test :inpaint:test

# Android app: needs an Android SDK (local.properties sdk.dir=… or ANDROID_HOME)
./gradlew :app:assembleDebug
```

`app` is only included in the build when an SDK is detected (see `settings.gradle.kts`).

## Privacy and keys

With AI on, a 1024-px JPEG preview and image statistics go to the Anthropic API; full-resolution pixels never leave the phone.
The key entered in Settings is fine for personal use. For a published app, put `GradeDirector` behind your own backend — an API key inside an APK is extractable.
