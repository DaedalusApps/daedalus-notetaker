---
name: demo-video
description: Turn a running app into a captioned demo video (mp4 + gif with music) and publish it — capture ordered screenshots, overlay explanatory captions, synthesize a soundtrack, commit the gif into the README. Use when asked to make a demo video/gif of an application, a walkthrough recording, or a README hero animation.
---

# Demo Video

A demo video is an argument that the product works, made to someone who will not run it. The whole job is to compress a real session into a few captioned frames that a viewer understands in ten seconds — and to publish it where it's seen (the README), not buried as an attachment. The failure modes are all avoidable: a video of a broken/empty state, captions no one can read, a pipeline that needs tools the box doesn't have, or a 30 MB binary committed to `main`.

## 1. Capture a real session, not an empty shell

Screenshot the **running** app, in order, one PNG per beat, named `01-*.png … 0N-*.png` (filename order = play order) into a **gitignored** scratch dir. Two things separate a convincing capture from a hollow one:

- **Warm it up.** Free-tier hosts cold-start — curl the health endpoint / homepage a couple times before the first shot, or frame 1 is a spinner.
- **Wait for real content, not a fixed sleep.** Log in with credentials read from an **env var / `.env` key by name** (never paste the secret). On async or AI-generated screens, wait for the finished content to appear (a completion marker), not `sleep(3)` — a half-rendered answer is worse than none. Seed real demo data first if the account is empty; a dashboard of zeroes sells nothing.

If a browser can't reach the app, degrade to a manual screenshot checklist with the same filenames — the build steps below are identical either way.

## 2. Build with tools that are actually present

The two things that break demo pipelines on a fresh machine, pre-empted:

- **Audio without numpy.** Synthesize the soundtrack with the Python **stdlib** (`wave` + `math` + `array`) — a gentle, low-volume arpeggio over a calm progression (e.g. C–G–Am–F), sine waves with per-note attack/decay ramps so there are no clicks, amplitude with headroom, a defensive clamp on the 16-bit packing, and a short fade in/out sized to the video length. Do **not** reach for numpy; it is frequently absent.
- **Video without a system ffmpeg.** `imageio-ffmpeg` bundles an ffmpeg binary — get it via `imageio_ffmpeg.get_ffmpeg_exe()` and shell out to it. Mux the wav into the mp4 with `-i video -i wav -c:v copy -c:a aac -shortest`. If muxing fails, keep the silent mp4 rather than emitting a corrupt one. Keep temp files in a `TemporaryDirectory` and **close every reader/writer handle before cleanup** (Windows locks open files).

**Captions** are the point — a frame without one is a mystery. Draw a semi-transparent dark bar across the bottom ~12% of each frame with centered light text that auto-shrinks to fit; load a TrueType font if one exists, fall back to `ImageFont.load_default()` without crashing. Normalize all frames to one canvas size and apply captions **identically to both the mp4 and the gif** so they match. Handle odd/non-even dimensions (the mp4 codec rejects them). Keep captions in a `demo_captions.json` sidecar so text edits don't touch code.

## 3. Verify by looking, not by trusting

The build "succeeding" proves nothing about what a human sees. Before shipping: **open one captioned frame** and confirm the caption is legible and correct; probe the mp4 for a real audio stream (`ffmpeg -i` stderr shows `Audio:`); confirm the gif has the expected frame count and is animated. A silent mp4 or an unreadable caption that ships is the same as no demo.

## 4. Publish the gif, hand over the mp4

- **Commit the gif** to a tracked path (`docs/assets/demo.gif`), keep it under ~2 MB (drop gif width / raise per-frame hold if larger), and embed it near the top of the README: `![<app> demo](docs/assets/demo.gif)` with a one-line caption. GitHub renders it inline — that's the whole reason to prefer gif for the README.
- **Gitignore the mp4 and the raw frames** — the mp4 is large and regenerates; deliver it as a file for review or attach it to a Release. Committing large, regenerating binaries bloats the repo permanently.

## 5. Ship it like any change

Branch, red-first tests for the pipeline code (caption overlay produces a visibly different frame; the mp4 has an audio stream with music on and none with it off; odd dimensions don't crash — all hermetic, handles closed before tmp cleanup), gate the tooling tests with `pytest.importorskip` so CI without Pillow/imageio-ffmpeg skips instead of erroring at collection, scope the formatter to the files you changed, then PR → review → CI → merge. Report the caption list, the audio-stream confirmation, the committed gif path + size, and the PR link; deliver the mp4 as a file.
