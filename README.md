# FlashRev

A four-key rhythm game in the spirit of **Flash Flash Revolution**, playable in
any modern browser on Windows 11 (Edge or Chrome) with no installation and no
build step. Arrows scroll toward the receptors; you press the matching key on the
beat; FFR-style judgments decide your score, combo and rank.

## Play it

1. Download or clone this repository.
2. Double-click **`index.html`**. It opens in your default browser (Edge works
   out of the box).
3. Press **Play**, pick the built-in demo song *Neon Circuit* and a difficulty.

Controls: **← ↓ ↑ →** or **D F J K** (rebindable in Settings), **Esc** pauses,
**F11** for fullscreen.

Everything runs locally in the browser: the demo song is synthesised on first
launch (about a second) and cached, settings and best scores live in
localStorage, and imported songs are stored in IndexedDB.

## Features

- **FFR judgments and scoring**: ±17 ms Amazing, ±50 ms Perfect, ±83 ms Good,
  ±117 ms Average; notes that scroll past are Misses, presses with no note nearby
  are Boos. Raw score (50/50/25/5/−10/−5) plus an FFR-style combo bonus. Ranks:
  AAA, SDG, FC, Blackflag FC, Clear.
- **StepMania charts**: import `.sm` / `.ssc` song folders, whole pack folders,
  or a `.zip` pack (dance-single only). BPM changes and stops are honoured;
  hold/roll heads become taps and mines are ignored, as in FFR.
- **Built-in demo song** with Easy / Medium / Hard charts generated from the
  same musical events as the audio.
- **Rhythm-coloured arrows** (4ths red, 8ths blue, 12ths purple, 16ths yellow…),
  receptor flashes, hit glows, judgment pop-ups with early/late hints, combo.
- **Settings**: scroll speed, scroll direction (up, FFR style, or down), lane
  size, key bindings, hit sounds, global offset, plus a **tap-to-the-beat
  calibration** that measures your audio latency through the same playback path
  the game uses.
- **Best scores** per chart.

## Importing songs

Title screen → **Import songs**:

- **Choose a folder…** picks a StepMania song folder (with its `.sm`/`.ssc` and
  audio) or a pack folder containing many song folders.
- **Choose a .zip pack…** does the same for a zipped pack.

Only the step file and the audio (`.ogg`, `.mp3`, `.wav`, `.m4a`, `.flac`, …)
are kept, inside the browser's storage for this page. Use **Delete** on a song
card to remove it.

## Timing

Song position comes from the Web Audio clock via `getOutputTimestamp()`, which
refers to the audio actually being heard, and key presses are judged by their
own event timestamps, so judgments do not depend on the frame rate. Different
audio devices add different latency; run **Calibrate offset** once per machine.

## Project layout

```
index.html        page shell and script order
css/style.css     menus and overlays
js/chart.js       Note/Chart model, TimingData (BPMS/STOPS/OFFSET), SmParser
js/engine.js      Judgment windows, GameEngine, ranks and accuracy (pure logic)
js/demo.js        Procedural demo song: composition, charts, synthesis
js/audio.js       AudioContext playback with pause/resume and the heard-time clock
js/library.js     IndexedDB song store, folder and .zip import
js/settings.js    Settings and best scores (localStorage)
js/render.js      Canvas play field renderer
js/game.js        Game loop, keyboard input, pause/countdown, results hand-off
js/screens.js     Title, song select, settings, calibration, results, import
js/main.js        Bootstrap
tests/run.js      Node test runner for the pure-logic modules
```

## Development

The game is plain HTML, CSS and JavaScript loaded as classic scripts, so it
works straight from `file://` with no bundler or server. To run the logic tests:

```bash
node tests/run.js
```
