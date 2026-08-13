# Real damaged-capture MP3 fixtures (#106)

`real_damaged_early_structure_only.mp3` and `real_damaged_mid_structure_only.mp3` are derived
from real interrupted BLE transfers captured on FW920 hardware on 2026-08-13 (recording
force-stopped mid-download).

All audio payload has been zeroed. Only the 4-byte MPEG frame headers that
`Mp3FrameScan.kt`'s chain-validation algorithm actually inspects to produce its result are
preserved (every other byte — frame payloads, gap/resync spans, and any trailing bytes — is
`0x00`). Both fixtures decode to digital silence (ffmpeg `volumedetect`: -91.0 dB mean/max for
both).

`Mp3FrameScan.scan()`'s output on these fixtures (framesOk/gapCount/gapBytes/firstGapOffset,
pinned in `Mp3FrameScanTest.kt`) was verified identical to the real, unmodified captures before
this reduction.

The raw, unmodified captures are deliberately **not** committed: this repository is public and
the audio is the owner's own voice.
