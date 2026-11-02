/* Chart model, StepMania (.sm / .ssc) parser and timing data. Pure logic, no DOM. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});

  /** Rhythmic subdivision names used for note colouring (StepMania/FFR convention). */
  const SNAPS = ["4th", "8th", "12th", "16th", "24th", "32nd", "48th", "64th", "other"];

  /** Classify a row by its position in the measure, in 192nds. */
  function snapFromRow192(row) {
    if (row % 48 === 0) return "4th";
    if (row % 24 === 0) return "8th";
    if (row % 16 === 0) return "12th";
    if (row % 12 === 0) return "16th";
    if (row % 8 === 0) return "24th";
    if (row % 6 === 0) return "32nd";
    if (row % 4 === 0) return "48th";
    if (row % 3 === 0) return "64th";
    return "other";
  }

  const DIFFICULTIES = {
    BEGINNER: { key: "BEGINNER", name: "Beginner", order: 0 },
    EASY: { key: "EASY", name: "Easy", order: 1 },
    MEDIUM: { key: "MEDIUM", name: "Medium", order: 2 },
    HARD: { key: "HARD", name: "Hard", order: 3 },
    CHALLENGE: { key: "CHALLENGE", name: "Challenge", order: 4 },
    EDIT: { key: "EDIT", name: "Edit", order: 5 },
  };

  function parseDifficulty(raw) {
    const s = String(raw || "").trim().toLowerCase();
    if (s.startsWith("beginner") || s === "novice") return DIFFICULTIES.BEGINNER;
    if (s.startsWith("easy") || s === "basic" || s === "light") return DIFFICULTIES.EASY;
    if (s.startsWith("medium") || ["another", "trick", "standard", "difficult"].includes(s)) return DIFFICULTIES.MEDIUM;
    if (s.startsWith("hard") || ["ssr", "maniac", "heavy"].includes(s)) return DIFFICULTIES.HARD;
    if (s.startsWith("challenge") || ["smaniac", "expert", "oni"].includes(s)) return DIFFICULTIES.CHALLENGE;
    return DIFFICULTIES.EDIT;
  }

  /**
   * Converts beats to seconds using an offset, BPM changes and stops.
   * `offset` follows the .sm convention: beat 0 occurs at -offset seconds.
   */
  class TimingData {
    constructor(offset, bpms, stops) {
      this.offset = offset;
      const b = (bpms || []).filter((p) => p[1] > 0).sort((x, y) => x[0] - y[0]);
      this.bpms = b.length ? b : [[0, 120]];
      this.stops = (stops || []).slice().sort((x, y) => x[0] - y[0]);
      this.segmentStart = new Array(this.bpms.length);
      let t = 0;
      for (let i = 0; i < this.bpms.length; i++) {
        this.segmentStart[i] = t;
        if (i + 1 < this.bpms.length) t += (this.bpms[i + 1][0] - this.bpms[i][0]) * 60 / this.bpms[i][1];
      }
    }
    get minBpm() { return Math.min(...this.bpms.map((p) => p[1])); }
    get maxBpm() { return Math.max(...this.bpms.map((p) => p[1])); }
    beatToTime(beat) {
      let idx = 0;
      while (idx + 1 < this.bpms.length && this.bpms[idx + 1][0] <= beat) idx++;
      const [segBeat, bpm] = this.bpms[idx];
      let t = this.segmentStart[idx] + (beat - segBeat) * 60 / bpm;
      for (const [stopBeat, seconds] of this.stops) {
        if (stopBeat < beat) t += seconds; else break;
      }
      return t - this.offset;
    }
    /** Parses "beat=value,beat=value" lists (#BPMS, #STOPS). */
    static parsePairs(raw) {
      const out = [];
      for (const entry of String(raw || "").split(",")) {
        const parts = entry.trim().split("=");
        if (parts.length !== 2) continue;
        const a = parseFloat(parts[0]), b = parseFloat(parts[1]);
        if (Number.isFinite(a) && Number.isFinite(b)) out.push([a, b]);
      }
      return out;
    }
  }

  const SmParser = {
    /** Splits the file into [key, value] tags, ignoring // comments. */
    tags(text) {
      const stripped = text.split(/\r?\n/).map((l) => { const i = l.indexOf("//"); return i >= 0 ? l.slice(0, i) : l; }).join("\n");
      const result = [];
      let i = 0;
      for (;;) {
        const hash = stripped.indexOf("#", i);
        if (hash < 0) break;
        const end = stripped.indexOf(";", hash);
        if (end < 0) break;
        const body = stripped.slice(hash + 1, end);
        const colon = body.indexOf(":");
        if (colon > 0) result.push([body.slice(0, colon).trim(), body.slice(colon + 1)]);
        i = end + 1;
      }
      return result;
    },

    parseNoteData(noteData, timing) {
      const notes = [];
      const measures = noteData.split(",");
      measures.forEach((measure, measureIndex) => {
        const rows = measure.split(/\r?\n/).map((r) => r.trim()).filter((r) => r.length > 0);
        if (!rows.length) return;
        const rowCount = rows.length;
        rows.forEach((row, rowIndex) => {
          const beat = measureIndex * 4 + 4 * rowIndex / rowCount;
          const row192 = 192 % rowCount === 0 ? rowIndex * (192 / rowCount) : -1;
          const snap = row192 >= 0 ? snapFromRow192(row192) : "other";
          const time = timing.beatToTime(beat);
          let lane = 0, c = 0;
          while (c < row.length && lane < 4) {
            const ch = row[c];
            if (ch === "{") { const j = row.indexOf("}", c); c = j < 0 ? row.length : j + 1; continue; }
            if (ch === "[") { const j = row.indexOf("]", c); c = j < 0 ? row.length : j + 1; continue; }
            if (ch === "1" || ch === "2" || ch === "4") notes.push({ time, lane, beat, snap }); // tap, hold head, roll head
            lane++; c++;
          }
        });
      });
      notes.sort((a, b) => a.time - b.time || a.lane - b.lane);
      return notes;
    },

    displayBpm(raw, timing) {
      const fmt = (v) => (Number.isInteger(v) ? String(v) : v.toFixed(1));
      const trimmed = (raw || "").trim();
      if (trimmed && trimmed !== "*") {
        const parts = trimmed.split(":").map((p) => parseFloat(p)).filter(Number.isFinite);
        if (parts.length === 1) return fmt(parts[0]);
        if (parts.length >= 2) return `${fmt(parts[0])}–${fmt(parts[1])}`;
      }
      const lo = timing.minBpm, hi = timing.maxBpm;
      return lo === hi ? fmt(lo) : `${fmt(lo)}–${fmt(hi)}`;
    },

    /** Parses a .sm or .ssc file; only dance-single charts are kept. Holds become taps, mines are dropped. */
    parse(text) {
      const header = new Map();
      const raw = [];
      let current = null;
      for (const [key, value] of this.tags(text)) {
        const k = key.toUpperCase();
        if (k === "NOTEDATA") { current = { stepsType: "", difficulty: "", meter: "", description: "", noteData: "" }; raw.push(current); continue; }
        if (k === "NOTES" && current === null) {
          const parts = value.split(":");
          if (parts.length >= 6) raw.push({ stepsType: parts[0].trim(), description: parts[1].trim(), difficulty: parts[2].trim(), meter: parts[3].trim(), noteData: parts.slice(5).join(":") });
          continue;
        }
        if (current !== null) {
          switch (k) {
            case "STEPSTYPE": current.stepsType = value.trim(); break;
            case "DIFFICULTY": current.difficulty = value.trim(); break;
            case "METER": current.meter = value.trim(); break;
            case "DESCRIPTION": case "CHARTNAME": if (!current.description) current.description = value.trim(); break;
            case "BPMS": current.bpms = value; break;
            case "STOPS": current.stops = value; break;
            case "OFFSET": current.offset = value; break;
            case "NOTES": case "NOTES2": current.noteData = value; current = null; break;
          }
          continue;
        }
        if (!header.has(k)) header.set(k, value);
      }
      const songOffset = parseFloat(header.get("OFFSET")) || 0;
      const songBpms = TimingData.parsePairs(header.get("BPMS"));
      const songStops = TimingData.parsePairs(header.get("STOPS"));
      const charts = raw
        .filter((r) => r.stepsType.toLowerCase() === "dance-single")
        .map((r) => {
          const bpms = r.bpms !== undefined ? TimingData.parsePairs(r.bpms) : [];
          const timing = new TimingData(
            r.offset !== undefined && Number.isFinite(parseFloat(r.offset)) ? parseFloat(r.offset) : songOffset,
            bpms.length ? bpms : songBpms,
            r.stops !== undefined ? TimingData.parsePairs(r.stops) : songStops,
          );
          const notes = this.parseNoteData(r.noteData, timing);
          if (!notes.length) return null;
          return { difficulty: parseDifficulty(r.difficulty), meter: parseInt(r.meter, 10) || 0, description: r.description, notes };
        })
        .filter(Boolean)
        .sort((a, b) => a.difficulty.order - b.difficulty.order || a.meter - b.meter);
      const timing = new TimingData(songOffset, songBpms, songStops);
      return {
        title: (header.get("TITLE") || "").trim(),
        subtitle: (header.get("SUBTITLE") || "").trim(),
        artist: (header.get("ARTIST") || "").trim(),
        music: (header.get("MUSIC") || "").trim(),
        displayBpm: this.displayBpm(header.get("DISPLAYBPM"), timing),
        sampleStart: parseFloat(header.get("SAMPLESTART")) || 0,
        charts,
      };
    },
  };

  function chartKey(song, chart) { return `${song.id}|${chart.difficulty.key}|${chart.description}`; }

  FR.chart = { SNAPS, snapFromRow192, DIFFICULTIES, parseDifficulty, TimingData, SmParser, chartKey };
})();
