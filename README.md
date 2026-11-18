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

## What it will not do (deliberately)

- **"4K" is interpolation, not super-resolution.** Phones already shoot 12 MP (> 4K UHD), so most photos are not upscaled at all. Small/old images are resized cleanly; no detail is invented, per brief §9.
- **Scratch removal on old photos** needs inpainting, i.e. inventing pixels. Not implemented; colour, contrast and noise are restored.
- **"Remove distracting elements"** is not implemented (same reason; brief says only on explicit request).

## Modules

- `core/` — pure Kotlin/JVM pipeline. `gradle :core:test` (10 tests: cast removal, skin hue drift < 4°, edge positions, saturation ceiling, curve monotonicity, 4K geometry, avatar circle fit; writes `core/build/visual/before.png|after.png`).
- `director/` — Claude integration via the official `anthropic-java` SDK: `claude-opus-5-5`, structured JSON output, low effort, server-side refusal fallback, image input. `gradle :director:test`.
- `app/` — Android (Compose + CameraX): capture, gallery import, "Share to Verity", before/after drag comparison, strength slider (blends parameters, not pixels), Save 4K, Save profile 1:1 to `Pictures/Verity`. Settings hold the API key in Keystore-encrypted prefs.

## Build

```bash
# JVM modules (no Android SDK needed)
./gradlew :core:test :director:test

# Android app: needs an Android SDK (local.properties sdk.dir=… or ANDROID_HOME)
./gradlew :app:assembleDebug
```

`app` is only included in the build when an SDK is detected (see `settings.gradle.kts`).

## Privacy and keys

With AI on, a 1024-px JPEG preview and image statistics go to the Anthropic API; full-resolution pixels never leave the phone.
The key entered in Settings is fine for personal use. For a published app, put `GradeDirector` behind your own backend — an API key inside an APK is extractable.
