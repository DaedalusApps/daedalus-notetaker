---
description: Turn a running app into a captioned demo video (mp4 + gif with music) and publish it — capture ordered screenshots, caption them, add a synthesized soundtrack, commit the gif into the README
---

# /demo-video

A demo video is an argument the product works, made to someone who won't run it. Compress a real session into a few captioned frames, and publish it where it's seen (the README), not as an attachment.

## Steps

1. **Capture a real session.** Screenshot the RUNNING app in order → `01-*.png … 0N-*.png` (filename order = play order) into a gitignored scratch dir. Warm the app first (curl it 2x — free tiers cold-start). Log in with creds from an env var / `.env` key BY NAME (never paste the secret). On async/AI screens wait for the FINISHED content (a completion marker), not a fixed sleep. Seed real demo data if the account is empty.
2. **Build with tools that are present.** `Pillow` for images/gif; `imageio-ffmpeg` for mp4 (it BUNDLES ffmpeg — `imageio_ffmpeg.get_ffmpeg_exe()`; no system ffmpeg). Synthesize audio with the STDLIB `wave`+`math` only — do NOT assume numpy.
3. **Captions.** Semi-transparent dark bar (~12% height) at the bottom of each frame, centered light text auto-shrunk to fit; TrueType font if available else `ImageFont.load_default()` (never crash). Same captions on BOTH mp4 and gif; normalize to one canvas size; handle odd dimensions. Captions in a `demo_captions.json` sidecar.
4. **Music (mp4 only; gif silent).** Gentle low-volume arpeggio over a calm progression (e.g. C–G–Am–F), sine + attack/decay ramps (no clicks), amplitude with headroom + clamp, fade in/out, sized to the video. Mux via the bundled ffmpeg (`-i video -i wav -c:v copy -c:a aac -shortest`). On failure, keep the silent mp4. Temp files in a TemporaryDirectory; close all handles before cleanup (Windows locks files).
5. **Verify by looking.** OPEN one captioned frame and confirm the caption is legible/correct; probe the mp4 for an audio stream (`ffmpeg -i`, look for `Audio:`); confirm the gif frame count + animation. A silent mp4 or unreadable caption that ships = no demo.
6. **Publish + ship.** Commit the gif to a TRACKED path (`docs/assets/demo.gif`, <~2 MB) and embed near the top of the README: `![demo](docs/assets/demo.gif)`. GITIGNORE the mp4 + raw frames (large, regenerating — hand the mp4 over as a file / attach to a Release). No secrets committed; scope the formatter to changed files; red-first tests for the pipeline gated with `pytest.importorskip`; branch → PR → review → CI → merge.
