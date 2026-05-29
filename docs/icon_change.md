# Plan: App Icon Update + Regression Tests

## Context

Two tasks:
1. **App icon** — current icon is static PNG "DN" initials on dark navy. Update to a proper adaptive icon (API 26+, which is our minSdk) with a microphone vector foreground — matching the splash screen's mic branding — and the app's navy background color.
2. **Regression tests** — three existing test files reference deleted composables (DeviceScreen, RecordingsScreen) and a removed model ID (gemma_2b_cpu). Fix them. Add a `SmartAnalysisParserTest` covering the mindMap normalization logic that was buggy and fixed in a prior session.

---

## 1. App Icon

### Files to create

**`android/app/src/main/res/drawable/ic_launcher_background.xml`**
Solid shape with the app's navy brand color (`#0D2B4E` — `NavyBlue` from `Theme.kt`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FF0D2B4E"/>
</shape>
```

**`android/app/src/main/res/drawable/ic_launcher_foreground.xml`**
108×108dp adaptive icon viewport. White Material Design microphone (scaled 3× with 18dp offset to center in the 72dp safe zone):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="3"
        android:scaleY="3"
        android:translateX="18"
        android:translateY="18">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zm5.3,-3c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"/>
    </group>
</vector>
```

**`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`**
**`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`** (identical content)
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

Existing PNGs in `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` are kept (harmless dead code at minSdk=26).
Manifest already references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` — no changes needed.

---

## 2. Regression Tests

### A. Fix `ModelConfigTest.kt`
**File:** `android/app/src/test/java/com/daedalus/notes/ModelConfigTest.kt`

- Rename test to `selectedModel_alwaysReturnsGemma3_1B`
- Change `assertEquals("gemma_2b_cpu", model.id)` → `assertEquals("gemma3_1b", model.id)`

### B. Delete `DeviceScreenTest.kt`
**File:** `android/app/src/androidTest/java/com/daedalus/notes/DeviceScreenTest.kt`

Delete — imports `ConnectedContent` from deleted `DeviceScreen.kt`. Coverage replaced by `HomeScreenTest.kt`.

### C. Delete `RecordingsScreenTest.kt`, create `HomeScreenTest.kt`
**File to delete:** `android/app/src/androidTest/java/com/daedalus/notes/RecordingsScreenTest.kt`
— imports `RecordingItem` and `RecordingsScreen` (both deleted).

**File to create:** `android/app/src/androidTest/java/com/daedalus/notes/HomeScreenTest.kt`

Mock setup (same pattern as prior RecordingsScreenTest):
```kotlin
val bleManager = mockk<BleManager>(relaxed = true)
every { deviceViewModel.bleManager } returns bleManager
every { bleManager.bleState } returns MutableStateFlow(BleState(...))
every { recordingViewModel.filteredRecordings } returns MutableStateFlow(...)
every { recordingViewModel.searchQuery } returns MutableStateFlow("")
every { recordingViewModel.syncProgress } returns MutableStateFlow(null)
```

Four test cases:

1. `homeScreen_disconnected_showsNotConnectedBanner`
   — BleState=DISCONNECTED → assert `"FW920 not connected"` displayed

2. `homeScreen_connected_showsDeviceStatusRow`
   — BleState=CONNECTED (batteryPct=85) → assert `"FW920"` text visible in status area

3. `homeScreen_recordingCard_displaysTitle_and_summary`
   — recordings=[Recording(filename="20260524213434.mp3", title="Project Meeting", shortSummary="Discussed the new global mind map.")]
   → assert both title and summary are displayed

4. `homeScreen_longPress_entersSelectionMode`
   — recordings=[two items], long-press first card
   → assert `"1 selected"` in TopAppBar, checkbox with contentDescription `"Select item"` visible

### D. Add `SmartAnalysisParserTest.kt` (new unit test)
**File:** `android/app/src/test/java/com/daedalus/notes/SmartAnalysisParserTest.kt`

Six test cases for `SmartAnalysisParser.parse()`:

1. `parse_extractsTitle` — `"title": "My Title"` → `result.title == "My Title"`
2. `parse_extractsTopics` — `"topics": ["a","b","c"]` → `result.topics == listOf("a","b","c")`
3. `parse_bulletMindMap_passedThrough` — bullet list mindMap → returned unchanged
4. `parse_jsonObjectMindMap_convertsToBullets` — `[{"item":"Point","sub":"Detail"}]` → result.mindMap contains `"- Point"` and `"- Detail"`
5. `parse_jsonStringArrayMindMap_convertsToBullets` — mindMap value starts with `"` (JSON string array) → result.mindMap lines are plain text, not raw JSON
6. `parse_malformedInput_returnsRawFallback` — invalid input → no crash, `result.fullSummary == rawInput`

---

## Verification

1. **Icon:** `.\gradlew installDebug` via ADB, check launcher — white mic on dark navy, round variant correct.
2. **Unit tests:** `.\gradlew :app:testDebugUnitTest` — all pass including fixed ModelConfigTest + new SmartAnalysisParserTest.
3. **Instrumented tests:** `.\gradlew :app:connectedDebugAndroidTest` — HomeScreenTest passes, RecordingDaoTest and GlobalMindMapScreenTest still pass.
