/* Web Audio playback with an output-referenced clock, plus the hit sound. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});

  class AudioEngine {
    constructor() { this.ctx = null; this.master = null; this.tickBuffer = null; }

    /** Creates/resumes the AudioContext. Must be called from a user gesture the first time. */
    ensure() {
      if (!this.ctx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        this.ctx = new Ctx({ latencyHint: "interactive" });
        this.master = this.ctx.createGain();
        this.master.gain.value = 0.9;
        this.master.connect(this.ctx.destination);
      }
      if (this.ctx.state === "suspended") this.ctx.resume();
      return this.ctx;
    }

    async decode(arrayBuffer) { return this.ensure().decodeAudioData(arrayBuffer.slice(0)); }

    bufferFromPcm(pcm, sampleRate) {
      const ctx = this.ensure();
      const b = ctx.createBuffer(1, pcm.length, sampleRate);
      b.copyToChannel(pcm, 0);
      return b;
    }

    /**
     * Context time that is being heard right now (accounts for output latency),
     * optionally for a given performance.now() timestamp such as a key event's.
     */
    heardTimeAt(perfMs) {
      const ctx = this.ctx;
      if (!ctx) return 0;
      if (ctx.getOutputTimestamp) {
        const ts = ctx.getOutputTimestamp();
        if (ts && Number.isFinite(ts.contextTime) && Number.isFinite(ts.performanceTime) && ts.performanceTime > 0) {
          return ts.contextTime + ((perfMs ?? performance.now()) - ts.performanceTime) / 1000;
        }
      }
      const latency = ctx.outputLatency || 0;
      const now = ctx.currentTime - latency;
      return perfMs === undefined ? now : now + (perfMs - performance.now()) / 1000;
    }

    createPlayback(buffer) { return new Playback(this, buffer); }

    playTick() {
      const ctx = this.ensure();
      if (!this.tickBuffer) this.tickBuffer = this.bufferFromPcm(FR.demo.tickSample(), FR.demo.SAMPLE_RATE);
      const src = ctx.createBufferSource();
      src.buffer = this.tickBuffer;
      const g = ctx.createGain(); g.gain.value = 0.5;
      src.connect(g); g.connect(this.master);
      src.start();
    }
  }

  /** One song playing through an AudioBufferSourceNode, with pause/resume and a position clock. */
  class Playback {
    constructor(engine, buffer) {
      this.engine = engine; this.buffer = buffer; this.source = null;
      this.playing = false; this.ended = false; this.pausedAt = 0; this.startCtx = 0;
    }
    get duration() { return this.buffer.duration; }
    start(offsetSec = 0) {
      const ctx = this.engine.ensure();
      this.stopSource();
      const src = ctx.createBufferSource();
      src.buffer = this.buffer;
      src.connect(this.engine.master);
      const when = ctx.currentTime + 0.03;
      const offset = Math.max(0, offsetSec);
      src.start(when, offset);
      this.startCtx = when - offset;
      this.source = src; this.playing = true; this.ended = false;
      src.onended = () => { if (this.source === src && this.playing) { this.playing = false; this.ended = true; this.pausedAt = this.duration; } };
    }
    /** Song position in seconds at the given context time (default: what is being heard now). */
    positionAt(ctxTime) {
      if (!this.playing) return this.pausedAt;
      return (ctxTime ?? this.engine.heardTimeAt()) - this.startCtx;
    }
    pause() {
      if (!this.playing) return;
      this.pausedAt = Math.max(0, this.positionAt(this.engine.ctx.currentTime));
      this.playing = false;
      this.stopSource();
    }
    resume() { if (!this.playing && !this.ended) this.start(this.pausedAt); }
    stop() { this.playing = false; this.stopSource(); }
    stopSource() {
      if (this.source) { const s = this.source; this.source = null; s.onended = null; try { s.stop(); } catch (e) { /* already stopped */ } s.disconnect(); }
    }
  }

  FR.audio = { AudioEngine, Playback };
})();
