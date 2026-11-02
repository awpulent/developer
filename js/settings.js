/* User settings and best scores, persisted in localStorage. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});
  const { clamp } = FR.util;

  const DEFAULTS = {
    scrollSpeed: 2.0,        // 1.0 = an arrow travels half the field height per second
    scrollUp: true,          // FFR style: arrows rise to receptors at the top
    offsetMs: 0,             // positive = notes judged/drawn later relative to the music
    hitSounds: false,
    earlyLate: true,
    snapColors: true,
    laneWidth: 110,          // pixels per lane at 1080p, scaled with the window
    keys: [["ArrowLeft", "KeyD"], ["ArrowDown", "KeyF"], ["ArrowUp", "KeyJ"], ["ArrowRight", "KeyK"]],
  };
  const LIMITS = { scrollSpeed: [0.5, 5], offsetMs: [-300, 300], laneWidth: [70, 160] };

  class Settings {
    constructor() { this.data = { ...DEFAULTS, keys: DEFAULTS.keys.map((k) => k.slice()) }; this.load(); }
    load() {
      try {
        const raw = JSON.parse(localStorage.getItem("flashrev.settings") || "{}");
        for (const k of Object.keys(DEFAULTS)) if (raw[k] !== undefined) this.data[k] = raw[k];
        if (!Array.isArray(this.data.keys) || this.data.keys.length !== 4) this.data.keys = DEFAULTS.keys.map((k) => k.slice());
      } catch (e) { /* ignore corrupt storage */ }
      this.normalize();
    }
    normalize() {
      for (const [k, [lo, hi]] of Object.entries(LIMITS)) this.data[k] = clamp(Number(this.data[k]) || DEFAULTS[k], lo, hi);
    }
    save() { this.normalize(); try { localStorage.setItem("flashrev.settings", JSON.stringify(this.data)); } catch (e) { /* storage disabled */ } }
    get(k) { return this.data[k]; }
    set(k, v) { this.data[k] = v; this.save(); }
    reset() { this.data = { ...DEFAULTS, keys: DEFAULTS.keys.map((k) => k.slice()) }; this.save(); }
    /** Lane index bound to a KeyboardEvent.code, or -1. */
    laneForCode(code) { return this.data.keys.findIndex((codes) => codes.includes(code)); }
  }

  class ScoreStore {
    constructor() { this.data = {}; try { this.data = JSON.parse(localStorage.getItem("flashrev.scores") || "{}"); } catch (e) { this.data = {}; } }
    best(chartKey) { return this.data[chartKey] || null; }
    /** Stores `result` if it beats the current best; returns true when a new best was recorded. */
    submit(chartKey, result) {
      if (!result.completed) return false;
      const cur = this.best(chartKey);
      if (cur && !FR.engine.isBetter(result, cur)) return false;
      this.data[chartKey] = result;
      try { localStorage.setItem("flashrev.scores", JSON.stringify(this.data)); } catch (e) { /* ignore */ }
      return true;
    }
    clearPrefix(prefix) {
      for (const k of Object.keys(this.data)) if (k.startsWith(prefix)) delete this.data[k];
      try { localStorage.setItem("flashrev.scores", JSON.stringify(this.data)); } catch (e) { /* ignore */ }
    }
  }

  FR.settings = { DEFAULTS, Settings, ScoreStore };
})();
