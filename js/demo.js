/* Procedurally generated demo song "Neon Circuit": ~57 s of chiptune plus three charts
 * derived from the same event list, so the steps always land on real musical onsets. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});

  const SAMPLE_RATE = 44100, BPM = 140, MEASURES = 32, SLOTS = 16, LEAD_IN = 1.0, TAIL = 1.5;
  const SEC_PER_SLOT = 60 / BPM / 4;
  const TITLE = "Neon Circuit", ARTIST = "FlashRev";

  function mulberry32(seed) {
    let a = seed >>> 0;
    return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  }
  const rngInt = (rnd, n) => Math.floor(rnd() * n);

  const sectionOf = (m) => (m <= 3 ? "INTRO" : m <= 11 ? "A" : m <= 19 ? "B" : m <= 27 ? "A2" : "OUTRO");

  const PENTA = [0, 3, 5, 7, 10], LEAD_BASE = 57, DEGREE_MAX = 9;
  const degreeToMidi = (d) => LEAD_BASE + 12 * Math.floor(d / 5) + PENTA[d % 5];

  const AM = { root: 45, tones: [0, 3, 7] }, F = { root: 41, tones: [8, 0, 3] }, C = { root: 48, tones: [3, 7, 10] }, G = { root: 43, tones: [10, 2, 5] };
  function chordFor(m) {
    const i = m % 8;
    switch (sectionOf(m)) {
      case "INTRO": return m < 2 ? AM : [AM, F][m - 2];
      case "A": case "A2": return [AM, F, C, G, AM, F, C, G][i];
      case "B": return [F, G, AM, C, F, G, AM, AM][i];
      default: return [AM, F, AM, AM][m - 28];
    }
  }

  const MASKS_A = [[0, 4, 6, 8, 12], [0, 2, 4, 8, 10, 12, 14], [0, 4, 8, 10, 12], [0, 6, 8, 14], [0, 2, 4, 6, 8, 12], [0, 4, 8, 12, 14]];
  const MASKS_B = [[0, 2, 4, 6, 8, 10, 12, 14], [0, 3, 6, 8, 11, 14], [0, 2, 3, 4, 8, 10, 11, 12], [0, 4, 7, 8, 12, 14], [0, 2, 4, 8, 9, 10, 12, 14], [0, 2, 4, 6, 8, 12, 14, 15]];
  const MASKS_OUTRO = [[0, 8], [0, 6], [0], []];
  const STEPS = [-2, -1, -1, 0, 1, 1, 2];

  function nearestChordTone(degree, chord, lo) {
    let best = degree, bestDist = Infinity;
    for (let d = lo; d <= DEGREE_MAX; d++) {
      const pc = (degreeToMidi(d) - 57) % 12;
      if (chord.tones.includes(pc)) { const dist = Math.abs(d - degree); if (dist < bestDist) { bestDist = dist; best = d; } }
    }
    return best;
  }

  function buildMelody(rnd) {
    const onsets = [];
    for (let m = 0; m < MEASURES; m++) {
      let mask;
      switch (sectionOf(m)) {
        case "INTRO": continue;
        case "A": case "A2": mask = MASKS_A[rngInt(rnd, MASKS_A.length)]; break;
        case "B": mask = MASKS_B[rngInt(rnd, MASKS_B.length)]; break;
        default: mask = MASKS_OUTRO[m - 28];
      }
      for (const s of mask) onsets.push([m, s]);
    }
    const notes = [];
    let degree = 2;
    onsets.forEach(([m, s], i) => {
      const chord = chordFor(m);
      const lo = sectionOf(m) === "B" ? 2 : 0;
      const nextIndex = i + 1 < onsets.length ? onsets[i + 1][0] * SLOTS + onsets[i + 1][1] : m * SLOTS + s + 4;
      const gap = nextIndex - (m * SLOTS + s);
      if (s === 0) degree = nearestChordTone(degree, chord, lo);
      else {
        let step;
        do { step = STEPS[rngInt(rnd, STEPS.length)]; } while (gap <= 1 && step === 0);
        let d = degree + step;
        if (d < lo) d = lo + (lo - d); else if (d > DEGREE_MAX) d = DEGREE_MAX - (d - DEGREE_MAX);
        degree = Math.min(DEGREE_MAX, Math.max(lo, d));
      }
      notes.push({ measure: m, slot: s, degree, midi: degreeToMidi(degree), length: Math.min(4, Math.max(1, gap - 1)) });
    });
    return notes;
  }

  function buildDrums() {
    const d = [];
    const add = (m, s, kind, v = 1) => d.push({ measure: m, slot: s, kind, velocity: v });
    for (let m = 0; m < MEASURES; m++) {
      const sec = sectionOf(m);
      if (sec === "INTRO") {
        add(m, 0, "KICK"); add(m, 8, "KICK");
        if (m >= 1) for (let s = 0; s < SLOTS; s += 2) add(m, s, "HAT", 0.8);
        if (m >= 2) { add(m, 4, "SNARE"); add(m, 12, "SNARE"); }
        if (m === 3) for (let s = 12; s <= 15; s++) add(m, s, "SNARE", 0.6 + 0.1 * (s - 12));
      } else if (sec === "A" || sec === "A2") {
        add(m, 0, "KICK"); add(m, 8, "KICK");
        if (sec === "A2") add(m, 6, "KICK", 0.8); else if (m % 2 === 1) add(m, 10, "KICK", 0.8);
        add(m, 4, "SNARE"); add(m, 12, "SNARE");
        for (let s = 0; s < SLOTS; s += 2) add(m, s, "HAT", 0.8);
        if (m % 4 === 3) add(m, 14, "OPEN_HAT", 0.8);
      } else if (sec === "B") {
        for (let s = 0; s < SLOTS; s += 4) add(m, s, "KICK");
        add(m, 4, "SNARE"); add(m, 12, "SNARE");
        for (let s = 0; s < SLOTS; s++) add(m, s, "HAT", s % 2 === 0 ? 0.8 : 0.35);
        if (m % 2 === 1) add(m, 14, "OPEN_HAT", 0.8);
      } else {
        add(m, 0, "KICK");
        if (m < 31) { add(m, 8, "KICK", 0.8); add(m, 4, "SNARE", 0.7); add(m, 12, "SNARE", 0.7); }
        if (m < 30) for (let s = 0; s < SLOTS; s += 2) add(m, s, "HAT", 0.6);
      }
    }
    return d;
  }

  function buildBass() {
    const b = [];
    for (let m = 0; m < MEASURES; m++) {
      const root = chordFor(m).root, sec = sectionOf(m);
      if (sec === "INTRO") { if (m >= 2) for (let s = 0; s < SLOTS; s += 2) b.push({ measure: m, slot: s, midi: root, length: 1 }); }
      else if (sec === "A" || sec === "A2") for (let s = 0; s < SLOTS; s += 2) b.push({ measure: m, slot: s, midi: s === 14 ? root + 7 : root, length: 1 });
      else if (sec === "B") for (let s = 0; s < SLOTS; s += 2) b.push({ measure: m, slot: s, midi: (s === 6 || s === 14) ? root + 12 : root, length: 1 });
      else b.push({ measure: m, slot: 0, midi: root, length: m < 31 ? 8 : 16 });
    }
    return b;
  }

  function buildPads() {
    const pads = [];
    for (let m = 0; m < MEASURES; m++) {
      const c = chordFor(m), root = c.root + 12;
      const tones = new Set(c.tones.map((pc) => { let n = root + ((pc - ((root - 45) % 12) + 24) % 12); if (n < root) n += 12; return n; }));
      pads.push({ measure: m, midis: [...tones].sort((a, b) => a - b) });
    }
    return pads;
  }

  function buildCharts(melody, drums, rnd) {
    const key = (m, s) => m * SLOTS + s;
    const melodyAt = new Map(melody.map((n) => [key(n.measure, n.slot), n]));
    const kickOrSnare = new Set(drums.filter((d) => d.kind === "KICK" || d.kind === "SNARE").map((d) => key(d.measure, d.slot)));
    const laneOf = (n) => n.degree % 4;
    const put = (map, k, lane) => { if (!map.has(k)) map.set(k, new Set()); map.get(k).add(lane); };

    const easy = new Map();
    let prevLane = -1;
    for (let m = 4; m < 28; m++) for (let s = 0; s < SLOTS; s += 4) {
      const k = key(m, s), mel = melodyAt.get(k);
      if (!mel && !kickOrSnare.has(k)) continue;
      let lane = mel ? laneOf(mel) : (prevLane + 1 + rngInt(rnd, 3)) % 4;
      if (lane === prevLane && rngInt(rnd, 3) !== 0) lane = (lane + 1 + rngInt(rnd, 3)) % 4;
      put(easy, k, lane); prevLane = lane;
    }

    const medium = new Map();
    for (const n of melody) if (n.slot % 2 === 0) put(medium, key(n.measure, n.slot), laneOf(n));

    const hard = new Map();
    for (const n of melody) {
      const k = key(n.measure, n.slot), lane = laneOf(n);
      put(hard, k, lane);
      if (sectionOf(n.measure) === "B" && n.slot === 0) put(hard, k, (lane + 2) % 4); // jumps on chorus downbeats
    }
    let lastHardLane = 0;
    for (let m = 20; m < 28; m++) for (const s of [4, 12]) {
      const k = key(m, s);
      if (hard.has(k)) continue;
      const prevKeys = [...hard.keys()].filter((x) => x < k);
      if (prevKeys.length) lastHardLane = [...hard.get(Math.max(...prevKeys))][0];
      put(hard, k, (lastHardLane + 2) % 4);
    }
    return [["Easy", 3, easy], ["Medium", 6, medium], ["Hard", 9, hard]];
  }

  function buildSm(charts) {
    let sb = `#TITLE:${TITLE};\n#SUBTITLE:(built-in demo);\n#ARTIST:${ARTIST};\n#MUSIC:demo.wav;\n#OFFSET:${(-LEAD_IN).toFixed(3)};\n`;
    sb += `#SAMPLESTART:${(LEAD_IN + 12 * SLOTS * SEC_PER_SLOT).toFixed(3)};\n#SAMPLELENGTH:12.000;\n#DISPLAYBPM:140;\n#BPMS:0.000=${BPM.toFixed(3)};\n#STOPS:;\n\n`;
    for (const [name, meter, lanesBySlot] of charts) {
      sb += `//---------------dance-single - ----------------\n#NOTES:\n     dance-single:\n     :\n     ${name}:\n     ${meter}:\n     0,0,0,0,0:\n`;
      for (let m = 0; m < MEASURES; m++) {
        for (let s = 0; s < SLOTS; s++) {
          const lanes = lanesBySlot.get(m * SLOTS + s);
          for (let lane = 0; lane < 4; lane++) sb += lanes && lanes.has(lane) ? "1" : "0";
          sb += "\n";
        }
        sb += m === MEASURES - 1 ? ";\n\n" : ",\n";
      }
    }
    return sb;
  }

  // ---------------------------------------------------------------- synthesis
  const slotTime = (m, s) => LEAD_IN + (m * SLOTS + s) * SEC_PER_SLOT;
  const midiHz = (midi) => 440 * Math.pow(2, (midi - 69) / 12);

  function mix(buf, startSec, durSec, fn) {
    const start = Math.floor(startSec * SAMPLE_RATE);
    const end = Math.min(buf.length, start + Math.floor(durSec * SAMPLE_RATE));
    for (let i = Math.max(0, start); i < end; i++) buf[i] += fn((i - start) / SAMPLE_RATE);
  }

  function render(melody, drums, bass, pads) {
    const total = LEAD_IN + MEASURES * SLOTS * SEC_PER_SLOT + TAIL;
    const n = Math.floor(total * SAMPLE_RATE);
    const buf = new Float32Array(n);
    const noise = mulberry32(7);
    const white = () => noise() * 2 - 1;
    const PI2 = Math.PI * 2;

    for (const p of pads) {
      const sec = sectionOf(p.measure), level = sec === "INTRO" ? 0.11 : 0.075, attack = sec === "INTRO" ? 0.6 : 0.08;
      const dur = SLOTS * SEC_PER_SLOT;
      for (const midi of p.midis) {
        const hz = midiHz(midi);
        mix(buf, slotTime(p.measure, 0), dur + 0.1, (t) => {
          const env = Math.min(1, t / attack) * (t > dur ? Math.exp(-(t - dur) * 25) : 1);
          const ph = (t * hz) % 1;
          return (4 * Math.abs(ph - 0.5) - 1) * env * level / p.midis.length;
        });
      }
    }
    for (const b of bass) {
      const hz = midiHz(b.midi), dur = b.length * SEC_PER_SLOT * 0.9;
      let lp = 0;
      mix(buf, slotTime(b.measure, b.slot), dur + 0.03, (t) => {
        const env = Math.min(1, t / 0.005) * (t > dur ? Math.exp(-(t - dur) * 120) : 1) * (0.6 + 0.4 * Math.exp(-t * 6));
        const saw = 2 * ((t * hz) % 1) - 1, sub = Math.sin(PI2 * hz * 0.5 * t);
        lp += 0.18 * ((saw * 0.7 + sub * 0.5) - lp);
        return lp * env * 0.42;
      });
    }
    for (const d of drums) {
      const t0 = slotTime(d.measure, d.slot), v = d.velocity;
      if (d.kind === "KICK") {
        let phase = 0; const dt = 1 / SAMPLE_RATE;
        mix(buf, t0, 0.28, (t) => { phase += PI2 * (45 + 130 * Math.exp(-t * 28)) * dt; return Math.sin(phase) * Math.exp(-t * 11) * 0.95 * v; });
      } else if (d.kind === "SNARE") {
        mix(buf, t0, 0.22, (t) => (white() * Math.exp(-t * 20) * 0.5 + Math.sin(PI2 * 185 * t) * Math.exp(-t * 32) * 0.45) * v);
      } else {
        const dur = d.kind === "HAT" ? 0.045 : 0.22, decay = 4.5 / dur;
        let prev = 0;
        mix(buf, t0, dur, (t) => { const w = white(); const hp = w - prev; prev = w; return hp * Math.exp(-t * decay) * 0.22 * v; });
      }
    }
    for (const m of melody) {
      const hz = midiHz(m.midi), d = Math.max(0.05, m.length * SEC_PER_SLOT - 0.02);
      mix(buf, slotTime(m.measure, m.slot), d + 0.06, (t) => {
        const env = Math.min(1, t / 0.008) * (t > d ? Math.exp(-(t - d) * 60) : 1);
        const vib = 1 + 0.004 * Math.sin(PI2 * 5.5 * t) * Math.min(1, t / 0.15);
        const p1 = (t * hz * vib) % 1, p2 = (t * hz * 1.003) % 1;
        return ((p1 < 0.25 ? 1 : -1) * 0.6 + (p2 < 0.5 ? 1 : -1) * 0.4) * env * 0.26;
      });
    }
    const fadeStart = slotTime(MEASURES - 2, 0);
    for (let i = Math.floor(fadeStart * SAMPLE_RATE); i < n; i++) {
      const t = i / SAMPLE_RATE;
      buf[i] *= Math.min(1, Math.max(0, 1 - (t - fadeStart) / (total - fadeStart)));
    }
    let peak = 1e-6;
    for (let i = 0; i < n; i++) { const a = Math.abs(buf[i]); if (a > peak) peak = a; }
    const gain = 0.89 / peak;
    for (let i = 0; i < n; i++) buf[i] *= gain;
    return buf;
  }

  /** Short click used as the hit sound. */
  function tickSample() {
    const n = Math.floor(0.03 * SAMPLE_RATE), out = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const t = i / SAMPLE_RATE;
      out[i] = (Math.sin(2 * Math.PI * 2000 * t) * Math.exp(-t * 180) * 0.8 + Math.sin(2 * Math.PI * 5000 * t) * Math.exp(-t * 400) * 0.3) * 0.8;
    }
    return out;
  }

  /** Bump when the synthesis changes so cached audio is regenerated. */
  const PCM_VERSION = 1;

  /** The cheap part: musical events, charts and the .sm text. */
  function compose() {
    const rnd = mulberry32(20260424);
    const melody = buildMelody(rnd), drums = buildDrums(), bass = buildBass(), pads = buildPads();
    const charts = buildCharts(melody, drums, rnd);
    return { melody, drums, bass, pads, smText: buildSm(charts), sampleRate: SAMPLE_RATE };
  }

  /** The expensive part (about a second): renders the composition to mono float PCM. */
  function renderPcm(composition) { return render(composition.melody, composition.drums, composition.bass, composition.pads); }

  let cached = null;
  function generate() {
    if (cached) return cached;
    const c = compose();
    cached = { ...c, pcm: renderPcm(c) };
    return cached;
  }

  FR.demo = { SAMPLE_RATE, BPM, LEAD_IN, TITLE, ARTIST, DEMO_ID: "_demo", PCM_VERSION, compose, renderPcm, generate, tickSample };
})();
