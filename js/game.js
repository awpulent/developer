/* Game screen: owns the engine, playback, keyboard input, the frame loop and pause handling. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});
  const { JUDGMENTS } = FR.engine;
  const PREROLL_SECONDS = 2.0, END_DELAY_SECONDS = 1.2, COUNTDOWN_MS = 1500;

  class GameScreen {
    /**
     * @param opts {app, song, chart, buffer (AudioBuffer), canvas, settings, audio, onFinished(result), onQuit()}
     */
    constructor(opts) {
      Object.assign(this, opts);
      this.engine = new FR.engine.GameEngine(this.chart);
      this.renderer = new FR.render.Renderer(this.canvas);
      this.renderer.configure({
        scrollSpeed: this.settings.get("scrollSpeed"), scrollUp: this.settings.get("scrollUp"),
        snapColors: this.settings.get("snapColors"), earlyLate: this.settings.get("earlyLate"), laneWidth: this.settings.get("laneWidth"),
      });
      this.offsetSec = this.settings.get("offsetMs") / 1000;
      this.hitSounds = this.settings.get("hitSounds");
      this.playback = this.audio.createPlayback(this.buffer);
      this.lastNoteTime = this.chart.notes.length ? this.chart.notes[this.chart.notes.length - 1].time : 0;
      this.fx = { lastJudgment: null, lastJudgmentAt: 0, lastErrorMs: 0, laneFlashAt: [0, 0, 0, 0], laneHitAt: [0, 0, 0, 0], laneHitJudgment: [null, null, null, null] };
      this.phase = "IDLE"; this.finished = false; this.raf = 0;
      this.onKeyDown = (e) => this.handleKey(e);
      this.onResize = () => this.renderer.resize();
      this.onBlur = () => { if (this.phase === "PLAYING" || this.phase === "PREROLL") this.pause(); };
    }

    start() {
      const firstNote = this.chart.notes.length ? this.chart.notes[0].time : 0;
      this.preRollStart = Math.min(-PREROLL_SECONDS, firstNote - PREROLL_SECONDS);
      this.phaseStart = performance.now();
      this.phase = "PREROLL";
      window.addEventListener("keydown", this.onKeyDown);
      window.addEventListener("resize", this.onResize);
      window.addEventListener("blur", this.onBlur);
      this.renderer.resize();
      const loop = () => { this.raf = requestAnimationFrame(loop); this.frame(); };
      loop();
    }

    destroy() {
      cancelAnimationFrame(this.raf);
      window.removeEventListener("keydown", this.onKeyDown);
      window.removeEventListener("resize", this.onResize);
      window.removeEventListener("blur", this.onBlur);
      this.playback.stop();
      this.phase = "IDLE";
    }

    /** Song clock (seconds) at a performance.now() timestamp. */
    clockAt(perfMs) {
      switch (this.phase) {
        case "PREROLL": return this.preRollStart + (perfMs - this.phaseStart) / 1000;
        case "PLAYING": case "FINISHED": return this.playback.positionAt(this.audio.heardTimeAt(perfMs));
        case "PAUSED": case "COUNTDOWN": return this.pausedSongTime;
        default: return 0;
      }
    }
    judgeTimeAt(perfMs) { return this.clockAt(perfMs) - this.offsetSec; }

    handleKey(e) {
      if (e.repeat) return;
      if (e.code === "Escape") { e.preventDefault(); if (this.phase === "PAUSED") this.resume(); else this.pause(); return; }
      if (this.phase === "PAUSED") {
        if (e.code === "Enter") this.resume();
        else if (e.code === "KeyR") this.restart();
        else if (e.code === "KeyQ") this.quit();
        return;
      }
      const lane = this.settings.laneForCode(e.code);
      if (lane < 0) return;
      e.preventDefault();
      if (this.phase !== "PLAYING" && this.phase !== "PREROLL") return;
      const ev = this.engine.tap(lane, this.judgeTimeAt(e.timeStamp));
      const now = performance.now();
      this.fx.laneFlashAt[lane] = now;
      if (ev.judgment !== JUDGMENTS.BOO) {
        this.fx.laneHitAt[lane] = now; this.fx.laneHitJudgment[lane] = ev.judgment;
        if (this.hitSounds) this.audio.playTick();
      }
      this.showJudgment(ev.judgment, ev.errorMs, now);
    }

    showJudgment(j, errorMs, now) { this.fx.lastJudgment = j; this.fx.lastJudgmentAt = now; this.fx.lastErrorMs = errorMs; }

    frame() {
      const now = performance.now();
      if (this.phase === "PREROLL" && this.clockAt(now) >= 0) {
        this.playback.start(0);
        this.phase = "PLAYING";
      } else if (this.phase === "COUNTDOWN" && now >= this.countdownEnd) {
        if (this.pausedSongTime < 0) { this.preRollStart = this.pausedSongTime; this.phaseStart = now; this.phase = "PREROLL"; }
        else { this.playback.resume(); this.phase = "PLAYING"; }
      }
      if (this.phase === "PLAYING" || this.phase === "PREROLL") {
        const t = this.judgeTimeAt(now);
        for (const ev of this.engine.update(t)) this.showJudgment(JUDGMENTS.MISS, 0, now);
        const songOver = this.phase === "PLAYING" && this.playback.ended;
        if (songOver && !this.engine.isFinished) this.engine.abandon();
        if (this.engine.isFinished && (t >= this.lastNoteTime + END_DELAY_SECONDS || songOver)) {
          this.phase = "FINISHED";
          if (!this.finished) { this.finished = true; setTimeout(() => this.onFinished(this.engine.result(true)), 0); }
        }
      }
      this.renderer.draw({
        engine: this.engine, time: this.judgeTimeAt(now), now, fx: this.fx, songTitle: this.song.fullTitle,
        phase: this.phase, countdownEnd: this.countdownEnd, countdownMs: COUNTDOWN_MS, lastNoteTime: this.lastNoteTime,
      });
    }

    pause() {
      if (this.phase === "PLAYING") { this.pausedSongTime = this.playback.positionAt(this.audio.ctx.currentTime); this.playback.pause(); }
      else if (this.phase === "PREROLL") this.pausedSongTime = this.clockAt(performance.now());
      else if (this.phase !== "COUNTDOWN") return;
      this.phase = "PAUSED";
      this.app.showPauseMenu(true);
    }
    resume() {
      if (this.phase !== "PAUSED") return;
      this.app.showPauseMenu(false);
      this.phase = "COUNTDOWN";
      this.countdownEnd = performance.now() + COUNTDOWN_MS;
    }
    restart() { this.app.showPauseMenu(false); this.app.startGame(this.song, this.chart); }
    quit() { this.app.showPauseMenu(false); this.onQuit(); }
  }

  FR.game = { GameScreen };
})();
