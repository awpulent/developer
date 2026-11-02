/* Canvas renderer for the play field. Stateless apart from cached geometry; the game passes in what to draw. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});

  const SNAP_COLORS = { "4th": "#ff4040", "8th": "#4080ff", "12th": "#c040ff", "16th": "#ffe040", "24th": "#ff80c0", "32nd": "#ff9040", "48th": "#40e0e0", "64th": "#80ff80", other: "#c0c0c0" };
  const JUDGE_COLORS = { AMAZING: "#60f0ff", PERFECT: "#ffd040", GOOD: "#60e070", AVERAGE: "#8a8cff", MISS: "#ff4c5b", BOO: "#9aa3c7" };
  const ROTATION = [-Math.PI / 2, Math.PI, 0, Math.PI / 2]; // Left, Down, Up, Right
  const RECEPTOR_FRACTION = 0.16, BASE_SPEED_FRACTION = 0.5;
  const FLASH_MS = 120, GLOW_MS = 220, JUDGMENT_MS = 700;

  class Renderer {
    constructor(canvas) {
      this.canvas = canvas; this.ctx = canvas.getContext("2d");
      this.w = 1; this.h = 1; this.dpr = 1;
      this.config = { scrollSpeed: 2, scrollUp: true, snapColors: true, earlyLate: true, laneWidth: 110 };
      this.drawCursor = 0;
    }
    configure(config) { this.config = { ...this.config, ...config }; this.layout(); }

    /** Resizes the backing store to the window and recomputes geometry. */
    resize() {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = window.innerWidth, h = window.innerHeight;
      if (this.canvas.width !== Math.floor(w * dpr) || this.canvas.height !== Math.floor(h * dpr)) {
        this.canvas.width = Math.floor(w * dpr); this.canvas.height = Math.floor(h * dpr);
        this.canvas.style.width = w + "px"; this.canvas.style.height = h + "px";
      }
      this.dpr = dpr; this.w = w; this.h = h;
      this.layout();
    }
    layout() {
      const c = this.config;
      this.laneWidth = c.laneWidth * (this.h / 1080);
      this.arrowSize = this.laneWidth * 0.8;
      this.lanesLeft = (this.w - this.laneWidth * 4) / 2;
      this.laneCenter = [0, 1, 2, 3].map((l) => this.lanesLeft + this.laneWidth * (l + 0.5));
      this.dirSign = c.scrollUp ? 1 : -1;
      this.receptorY = c.scrollUp ? this.h * RECEPTOR_FRACTION : this.h * (1 - RECEPTOR_FRACTION);
      this.pixelsPerSecond = c.scrollSpeed * this.h * BASE_SPEED_FRACTION;
    }

    arrowPath(ctx, size) {
      const s = size;
      ctx.beginPath();
      ctx.moveTo(0, -0.5 * s); ctx.lineTo(0.5 * s, 0); ctx.lineTo(0.22 * s, 0); ctx.lineTo(0.22 * s, 0.5 * s);
      ctx.lineTo(-0.22 * s, 0.5 * s); ctx.lineTo(-0.22 * s, 0); ctx.lineTo(-0.5 * s, 0); ctx.closePath();
    }

    /**
     * Draws one frame.
     * state: { engine, time, now, fx: {lastJudgment, lastJudgmentAt, lastErrorMs, laneFlashAt[], laneHitAt[], laneHitJudgment[]},
     *          songTitle, phase, countdownEnd, lastNoteTime }
     */
    draw(state) {
      const ctx = this.ctx, { w, h } = this;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.fillStyle = "#07080f";
      ctx.fillRect(0, 0, w, h);
      // Lane shading
      for (let lane = 0; lane < 4; lane++) {
        ctx.fillStyle = lane % 2 === 0 ? "rgba(255,255,255,0.045)" : "rgba(255,255,255,0.02)";
        ctx.fillRect(this.lanesLeft + lane * this.laneWidth, 0, this.laneWidth, h);
      }
      ctx.strokeStyle = "rgba(255,255,255,0.08)"; ctx.lineWidth = 1;
      ctx.beginPath();
      for (let i = 0; i <= 4; i++) { const x = Math.round(this.lanesLeft + i * this.laneWidth) + 0.5; ctx.moveTo(x, 0); ctx.lineTo(x, h); }
      ctx.stroke();

      this.drawReceptors(state);
      this.drawNotes(state);
      this.drawJudgment(state);
      this.drawHud(state);
      if (state.phase === "COUNTDOWN") this.drawCountdown(state);
    }

    drawReceptors({ now, fx }) {
      const ctx = this.ctx, size = this.arrowSize;
      for (let lane = 0; lane < 4; lane++) {
        ctx.save();
        ctx.translate(this.laneCenter[lane], this.receptorY);
        ctx.rotate(ROTATION[lane]);
        const flashAge = now - fx.laneFlashAt[lane];
        if (flashAge >= 0 && flashAge < FLASH_MS) {
          this.arrowPath(ctx, size);
          ctx.fillStyle = `rgba(255,255,255,${0.65 * (1 - flashAge / FLASH_MS)})`;
          ctx.fill();
        }
        this.arrowPath(ctx, size);
        ctx.lineWidth = Math.max(2, size * 0.07); ctx.strokeStyle = "#8a93b5"; ctx.lineJoin = "round";
        ctx.stroke();
        ctx.restore();
        const hitAge = now - fx.laneHitAt[lane], hj = fx.laneHitJudgment[lane];
        if (hj && hitAge >= 0 && hitAge < GLOW_MS) {
          const p = hitAge / GLOW_MS;
          ctx.save();
          ctx.globalAlpha = 1 - p;
          ctx.strokeStyle = JUDGE_COLORS[hj.key]; ctx.lineWidth = Math.max(2, size * 0.08);
          ctx.beginPath(); ctx.arc(this.laneCenter[lane], this.receptorY, size * (0.55 + 0.5 * p), 0, Math.PI * 2); ctx.stroke();
          ctx.restore();
        }
      }
    }

    drawNotes({ engine, time }) {
      const states = engine.states; if (!states.length) return;
      const ctx = this.ctx, h = this.h, size = this.arrowSize, pps = this.pixelsPerSecond;
      const pastEdge = this.config.scrollUp ? this.receptorY : h - this.receptorY;
      const futureEdge = this.config.scrollUp ? h - this.receptorY : this.receptorY;
      const minTime = time - (pastEdge / pps + 0.15), maxTime = time + (futureEdge / pps + 0.15);
      if (this.drawCursor > states.length) this.drawCursor = 0;
      while (this.drawCursor > 0 && states[this.drawCursor - 1].note.time >= minTime) this.drawCursor--; // after a restart/seek
      while (this.drawCursor < states.length && states[this.drawCursor].note.time < minTime) this.drawCursor++;
      let last = this.drawCursor;
      while (last < states.length && states[last].note.time <= maxTime) last++;
      for (let k = last - 1; k >= this.drawCursor; k--) {
        const st = states[k], j = st.judgment;
        if (j && j.key !== "MISS") continue;
        const note = st.note;
        const y = this.receptorY + this.dirSign * (note.time - time) * pps;
        if (y < -size || y > h + size) continue;
        ctx.save();
        ctx.translate(this.laneCenter[note.lane], y);
        ctx.rotate(ROTATION[note.lane]);
        this.arrowPath(ctx, size);
        ctx.fillStyle = this.config.snapColors ? SNAP_COLORS[note.snap] : SNAP_COLORS["4th"];
        if (j) ctx.globalAlpha = 0.35;
        ctx.fill();
        if (!j) {
          ctx.lineWidth = Math.max(1.5, size * 0.05); ctx.strokeStyle = "#101018"; ctx.lineJoin = "round"; ctx.stroke();
          this.arrowPath(ctx, size * 0.45); ctx.fillStyle = "rgba(255,255,255,0.33)"; ctx.fill();
        }
        ctx.restore();
      }
    }

    drawJudgment({ engine, now, fx }) {
      const j = fx.lastJudgment; if (!j) return;
      const age = now - fx.lastJudgmentAt; if (age > JUDGMENT_MS) return;
      const ctx = this.ctx, w = this.w, h = this.h;
      const centerY = this.config.scrollUp ? h * 0.46 : h * 0.40;
      const pop = age < 110 ? 1.25 - 0.25 * (age / 110) : 1;
      const alpha = age > JUDGMENT_MS - 200 ? (JUDGMENT_MS - age) / 200 : 1;
      ctx.save();
      ctx.globalAlpha = alpha; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.font = `bold ${Math.round(h * 0.042 * pop)}px "Segoe UI", system-ui, sans-serif`;
      ctx.fillStyle = JUDGE_COLORS[j.key];
      ctx.fillText(j.label.toUpperCase(), w / 2, centerY);
      if (this.config.earlyLate && !["MISS", "BOO", "AMAZING"].includes(j.key)) {
        ctx.font = `${Math.round(h * 0.018)}px "Segoe UI", system-ui, sans-serif`;
        ctx.fillStyle = fx.lastErrorMs < 0 ? "#6fb8ff" : "#ff9a6f";
        ctx.fillText(fx.lastErrorMs < 0 ? "early" : "late", w / 2, centerY + h * 0.032);
      }
      if (engine.combo >= 2) {
        ctx.font = `bold ${Math.round(h * 0.06 * (age < 110 ? 1.12 - 0.12 * (age / 110) : 1))}px "Segoe UI", system-ui, sans-serif`;
        ctx.fillStyle = "#f2f4ff";
        ctx.fillText(String(engine.combo), w / 2, centerY + h * 0.1);
        ctx.font = `${Math.round(h * 0.018)}px "Segoe UI", system-ui, sans-serif`;
        ctx.fillStyle = "#8a93b5";
        ctx.fillText("COMBO", w / 2, centerY + h * 0.135);
      }
      ctx.restore();
    }

    drawHud({ engine, time, songTitle, lastNoteTime }) {
      const ctx = this.ctx, w = this.w, h = this.h;
      ctx.save();
      ctx.textBaseline = "top";
      ctx.textAlign = "left";
      ctx.font = `bold ${Math.round(h * 0.026)}px "Segoe UI", system-ui, sans-serif`;
      ctx.fillStyle = "#e8ecff";
      ctx.fillText(FR.util.fmtInt(engine.score), w * 0.03, h * 0.03);
      ctx.font = `${Math.round(h * 0.016)}px "Segoe UI", system-ui, sans-serif`;
      ctx.fillStyle = "#8a93b5";
      ctx.fillText("RAW " + FR.util.fmtInt(engine.rawScore), w * 0.03, h * 0.064);
      ctx.textAlign = "right";
      ctx.fillText(`${engine.judgedNotes} / ${engine.totalNotes}`, w * 0.97, h * 0.03);
      ctx.fillText(songTitle, w * 0.97, h * 0.052);
      ctx.fillText("Esc: pause", w * 0.97, h * 0.074);
      const progress = Math.min(1, Math.max(0, time / Math.max(0.001, lastNoteTime)));
      ctx.fillStyle = "#ffd040";
      ctx.fillRect(0, 0, w * progress, Math.max(2, h * 0.004));
      ctx.restore();
    }

    drawCountdown({ now, countdownEnd, countdownMs }) {
      const remaining = Math.max(0, countdownEnd - now);
      const n = Math.min(3, Math.max(1, Math.floor(remaining / (countdownMs / 3)) + 1));
      const ctx = this.ctx;
      ctx.save();
      ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.font = `bold ${Math.round(this.h * 0.14)}px "Segoe UI", system-ui, sans-serif`;
      ctx.fillStyle = "#ffd040";
      ctx.fillText(String(n), this.w / 2, this.h / 2);
      ctx.restore();
    }
  }

  FR.render = { Renderer, SNAP_COLORS, JUDGE_COLORS };
})();
