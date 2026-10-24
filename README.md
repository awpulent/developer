# FlashRev

A four-key rhythm game for Android in the spirit of **Flash Flash Revolution**:
arrows scroll toward the receptors, you tap the matching lane on the beat, and
FFR-style judgments (Amazing / Perfect / Good / Average / Miss / Boo) decide your
score, combo and rank.

The app is written in Kotlin against the plain Android framework (no AndroidX,
no third-party runtime libraries), so it builds quickly and the APK stays tiny.

## Features

- **FFR judgments and scoring**: ±17 ms Amazing, ±50 ms Perfect, ±83 ms Good,
  ±117 ms Average; notes that scroll past are Misses, taps with no note nearby are
  Boos. Raw score (50/50/25/5/−10/−5) plus an FFR-style combo bonus. Ranks:
  AAA, SDG, FC, Blackflag FC, Clear.
- **StepMania charts**: imports `.sm` and `.ssc` song folders or `.zip` packs
  (dance-single only). BPM changes and stops are honoured; hold/roll heads become
  taps and mines are ignored, as in FFR.
- **Built-in demo song**: "Neon Circuit" is synthesised on first launch, with
  Easy / Medium / Hard charts generated from the same musical events, so the game
  is playable without any downloads.
- **Rhythm-coloured arrows** (4ths red, 8ths blue, 12ths purple, 16ths yellow…),
  receptor flashes, hit glows, judgment pop-ups, early/late indicator, combo.
- **Scroll speed**, scroll direction (up, FFR style, or down), lane size,
  hit sounds and a **tap-to-the-beat offset calibration** that measures audio
  latency through the same playback path the game uses.
- **Best scores** per chart with rank marks on the song list.

## Building

Requirements: Android Studio (Ladybug or newer) or a JDK 17+ with the Android
SDK (compileSdk 35) installed.

```bash
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # runs the JVM unit tests
./gradlew installDebug           # installs on a connected device or emulator
```

Minimum Android version: 7.0 (API 24). Target: Android 15 (API 35).

## Playing

1. Launch the app. The demo song is prepared on first run (about a second).
2. **Play** → pick a song → pick a chart.
3. The screen is split into four touch columns (Left, Down, Up, Right). Tap a
   column when its arrow reaches the receptors. Multi-touch jumps are supported.
4. Pause with the on-screen button or the back button.

If notes feel consistently early or late, run **Calibrate offset** from the title
screen: tap along with the demo track and save the measured offset. You can also
nudge the global offset by hand in **Settings**.

## Importing songs

**Import songs** on the title screen accepts either:

- a **folder** picked with the system file picker: a single StepMania song
  folder, or a pack folder containing many song folders (searched up to three
  levels deep); or
- a **.zip** containing such folders.

Only the step file and audio (`.ogg`, `.mp3`, `.wav`, `.m4a`, `.flac`, `.aac`)
are copied into the app's private storage. Long-press a song in the list to delete it.

## Project layout

```
app/src/main/java/com/awpulent/flashrev/
  chart/   Note/Chart/Song models, TimingData (BPMS/STOPS/OFFSET), SmParser,
           SongLibrary (scans songs dir), SongImporter (SAF folder & zip import)
  game/    Judgment windows, GameEngine (pure logic), GameResult, GameView
           (SurfaceView renderer + touch), GameActivity
  audio/   SongPlayer (MediaPlayer with a drift-corrected clock), HitSounds, WavWriter
  demo/    DemoSongGenerator (procedural track + charts), DemoInstaller
  data/    Settings (SharedPreferences), ScoreStore (best results)
  ui/      Title, song select, settings, calibration and results screens
app/src/test/   JUnit tests for the parser, timing, engine and demo generator
```

The gameplay engine, parser, timing and demo generator have no Android
dependencies and are covered by unit tests.

## Notes on timing

Song time is derived from `SystemClock.uptimeMillis()` anchored to the
`MediaPlayer` position and gently re-synchronised, and touch events are judged
using their own `MotionEvent.eventTime`, so judgment accuracy does not depend on
the frame rate. Device audio latency varies, which is what the offset calibration
is for.
