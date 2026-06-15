# Daedalus Notes — Architecture & Design Document

This document outlines the architecture, components, workflows, and technical discoveries for the **Daedalus Notes** Android application (the companion app for the **ELVANZA FW920** / HUXGO OEM voice recorder).

---

## 1. Architectural Philosophy (Local-First AI)

The core mandate of Daedalus Notes is **on-device only processing**. Audio recordings, transcripts, summaries, and semantic database indices never leave the user's phone. This guarantees privacy, zero API costs, and full offline operation.

```
┌─────────────────┐       BLE GATT       ┌──────────────────┐
│  ELVANZA FW920  │  ──────────────────► │  Android Device  │
│  Voice Recorder │                      │  (Local-First)   │
└─────────────────┘                      └──────────────────┘
                                                   │
                                          ┌────────┴────────┐
                                          ▼                 ▼
                                    [Whisper STT]     [Gemma 3 LLM]
                                    Local Speech-     Local Summaries
                                     to-Text          & Mind Maps
```

---

## 2. Key System Components

The application follows the **MVVM (Model-View-ViewModel)** architecture pattern:

```
                  ┌────────────────────────┐
                  │       UI Screens       │
                  │   (Jetpack Compose)    │
                  └───────────┬────────────┘
                              │ observes StateFlows
                              ▼
                  ┌────────────────────────┐
                  │    RecordingViewModel  │
                  └───────────┬────────────┘
                              │ calls repository methods
                              ▼
                  ┌────────────────────────┐
                  │  RecordingRepository   │
                  └───────────┬────────────┘
                              │
          ┌───────────────────┴───────────────────┐
          ▼                                       ▼
┌──────────────────┐                    ┌──────────────────┐
│    Local DB      │                    │   BLE Manager    │
│  (Room + SQLite) │                    │ (Android BLE API)│
└──────────────────┘                    └──────────────────┘
```

### A. User Interface (UI)
* Built entirely with **Jetpack Compose** and **Material 3**.
* State is managed reactively via Kotlin **StateFlow** properties exposed by viewmodels.

### B. Local Database (SQLite / Room)
* **Room Database** acts as the local cache and metadata store.
* Standard queries, full-text search (FTS), and semantic search indices reside in a single SQLite file (`daedalus_notes.db`) using SQLite WAL (Write-Ahead Logging) mode.

### C. Bluetooth Low Energy (BLE) Engine
* **`BleManager`** handles scanning, connection state, service discovery, MTU negotiation, and packets transmission.
* Encapsulates the proprietary GATT commands of the FW920 protocol.

### E. Local AI Stack
* **Transcription**: Powered by the `sherpa-onnx` framework executing an int8-quantized Whisper base.en model locally.
* **LLM Analysis**: Powered by the `MediaPipe LLM Inference` API running Gemma 3 1B task-specific model to generate titles, summaries, topics, and structured mind map outputs in a single inference pass.
* **Vector Semantic Search**: Powered by the `MediaPipe Text Embedder` API running the Universal Sentence Encoder. Generates high-dimensional vectors stored as `BLOB`s in Room, ranked locally using cosine similarity.

---

## 3. BLE Protocol & Handshake Specifications

### GATT Profile UUIDs
* **Service**: `0000b0b0-0000-1000-8000-00805f9b34fb`
* **Write Characteristic**: `0000b0b1-0000-1000-8000-00805f9b34fb` (Commands sent here)
* **Notify B0B2**: Status, Acks, and Command responses (`0x0A` packet details)
* **Notify B0B3 / B0B4**: Audio streaming data chunks

### Packet Format
Commands are structured into a packet wrapper:
`[0xA0 0x0A 0x01] [CMD] [LEN] [PAYLOAD...] [CRC16-ARC-hi] [CRC16-ARC-lo]`
* **Header**: `A0 0A 01` (Indicates a command packet)
* **Length**: Number of bytes in the payload
* **CRC16-ARC**: Computed over the header + payload, appended in big-endian order.

### Storage Capacity Scaling Discovery
The device status command (`0x05`) returns raw numbers for `totalBytes` and `freeBytes` in its payload. 
* **Discovery**: The device reports storage sizes in **64-byte blocks/sectors**, not raw bytes.
* **Conversion**: To represent actual storage capacity (64GB), the values must be scaled:
  $$\text{Actual Bytes} = \text{Raw Value} \times 64$$
  For example, `1,021,968,384` blocks $\times 64 = 65,405,976,576$ bytes $\approx 60.9$ GiB (which represents the partition size of a 64GB drive).

---

## 4. Key Workflows & Handling

### A. Sync Workflow
1. App connects to FW920 over BLE.
2. App requests **MTU 512** (critical: streaming files fails on lower MTU sizes).
3. Executes `syncAllBleFiles()`:
   * First processes any pending deletions.
   * Sends listing command (`0x0A`) to get all files.
   * Compares filenames and sizes with database records.
   * Downloads new audio streams using command `0x0B`.
   * Triggers local audio analysis (Whisper + Gemma) when complete.

### B. Disconnected Deletions & Queueing
To support recording deletions when the user's phone is not connected to the FW920 recorder:

```
User deletes file (Disconnected)
         │
         ▼
Delete local audio cache
         │
         ▼
Set DB record `pendingDelete = true`
(Instantly hides file from UI/Search/Mind Map flows)
         │
         ▼
Device connects later & triggers Sync
         │
         ▼
Sync engine retrieves all `pendingDelete = true` records
         │
         ▼
Executes 2-phase BLE delete (CMD 0x0D stage & commit)
         │
         ▼
Purges record from DB upon successful hardware deletion
```

* **Connected Deletion**: Performs immediate physical deletion via BLE, deletes local files, and removes the DB record.
* **Disconnected Deletion**: Clears local files instantly, marks the record as `pendingDelete = true` (instantly hiding it from all UI flows). Upon the next connection and sync, the sync engine fetches these records, executes the two-phase BLE deletion on the FW920, and purges them from the database.
