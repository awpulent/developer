/* Gameplay engine: FFR judgments, combo and scoring. Pure logic, no DOM. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});

  /**
   * FFR judgments. Windows are the classic half-frame steps at 30 fps:
   * ±17 ms Amazing, ±50 ms Perfect, ±83 ms Good, ±117 ms Average.
   * Boos cost points but keep the combo ("blackflag"); misses reset it.
   */
  const JUDGMENTS = {
    AMAZING: { key: "AMAZING", label: "Amazing", points: 50, window: 17, extendsCombo: true, breaksCombo: false },
    PERFECT: { key: "PERFECT", label: "Perfect", points: 50, window: 50, extendsCombo: true, breaksCombo: false },
    GOOD: { key: "GOOD", label: "Good", points: 25, window: 83, extendsCombo: true, breaksCombo: false },
    AVERAGE: { key: "AVERAGE", label: "Average", points: 5, window: 117, extendsCombo: true, breaksCombo: false },
    MISS: { key: "MISS", label: "Miss", points: -10, window: 0, extendsCombo: false, breaksCombo: true },
    BOO: { key: "BOO", label: "Boo", points: -5, window: 0, extendsCombo: false, breaksCombo: false },
  };
  const JUDGMENT_ORDER = ["AMAZING", "PERFECT", "GOOD", "AVERAGE", "MISS", "BOO"];
  const MAX_WINDOW_MS = 117;

  function judgeError(errorMs) {
    const e = Math.abs(errorMs);
    if (e <= 17) return JUDGMENTS.AMAZING;
    if (e <= 50) return JUDGMENTS.PERFECT;
    if (e <= 83) return JUDGMENTS.GOOD;
    if (e <= 117) return JUDGMENTS.AVERAGE;
    return JUDGMENTS.MISS;
  }

  class GameEngine {
    constructor(chart) {
      this.chart = chart;
      this.states = chart.notes.map((note, index) => ({ note, index, judgment: null, errorMs: 0 }));
      this.laneNotes = [0, 1, 2, 3].map((lane) => this.states.filter((s) => s.note.lane === lane).map((s) => s.index));
      this.laneCursor = [0, 0, 0, 0];
      this.counts = { AMAZING: 0, PERFECT: 0, GOOD: 0, AVERAGE: 0, MISS: 0, BOO: 0 };
      this.combo = 0; this.maxCombo = 0; this.rawScore = 0; this.score = 0; this.judgedNotes = 0;
      this.errorSum = 0; this.errorCount = 0;
    }
    get totalNotes() { return this.states.length; }
    get isFinished() { return this.judgedNotes >= this.states.length; }
    get meanErrorMs() { return this.errorCount ? this.errorSum / this.errorCount : 0; }

    /** Registers a tap on `lane` at `songTime` seconds. Earliest unjudged note within the window is hit, else Boo. */
    tap(lane, songTime) {
      const window = MAX_WINDOW_MS / 1000;
      const indices = this.laneNotes[lane];
      while (this.laneCursor[lane] < indices.length) {
        const st = this.states[indices[this.laneCursor[lane]]];
        if (st.judgment) { this.laneCursor[lane]++; continue; }
        if (st.note.time < songTime - window) { this._apply(st, JUDGMENTS.MISS, 0); this.laneCursor[lane]++; continue; }
        break;
      }
      if (this.laneCursor[lane] < indices.length) {
        const st = this.states[indices[this.laneCursor[lane]]];
        const errorMs = (songTime - st.note.time) * 1000;
        if (Math.abs(errorMs) <= MAX_WINDOW_MS) {
          const j = judgeError(errorMs);
          this._apply(st, j, errorMs);
          this.laneCursor[lane]++;
          return { judgment: j, lane, note: st, errorMs, combo: this.combo };
        }
      }
      this._applyJudgment(JUDGMENTS.BOO);
      return { judgment: JUDGMENTS.BOO, lane, note: null, errorMs: 0, combo: this.combo };
    }

    /** Advances time: unjudged notes older than the window become misses. Returns miss events. */
    update(songTime) {
      const window = MAX_WINDOW_MS / 1000;
      const events = [];
      for (let lane = 0; lane < 4; lane++) {
        const indices = this.laneNotes[lane];
        while (this.laneCursor[lane] < indices.length) {
          const st = this.states[indices[this.laneCursor[lane]]];
          if (st.judgment) { this.laneCursor[lane]++; continue; }
          if (st.note.time < songTime - window) {
            this._apply(st, JUDGMENTS.MISS, 0);
            this.laneCursor[lane]++;
            events.push({ judgment: JUDGMENTS.MISS, lane, note: st, errorMs: 0, combo: this.combo });
            continue;
          }
          break;
        }
      }
      return events;
    }

    abandon() { for (const st of this.states) if (!st.judgment) this._apply(st, JUDGMENTS.MISS, 0); }

    _apply(st, j, errorMs) {
      st.judgment = j; st.errorMs = errorMs; this.judgedNotes++;
      if (j !== JUDGMENTS.MISS) { this.errorSum += Math.abs(errorMs); this.errorCount++; }
      this._applyJudgment(j);
    }
    _applyJudgment(j) {
      this.counts[j.key]++;
      this.rawScore += j.points;
      if (j.breaksCombo) this.combo = 0;
      if (j.extendsCombo) {
        this.combo++;
        if (this.combo > this.maxCombo) this.maxCombo = this.combo;
        this.score += j.points + this.combo; // FFR-style running score: points plus combo bonus
      } else {
        this.score += j.points;
      }
    }

    result(completed) {
      return {
        amazing: this.counts.AMAZING, perfect: this.counts.PERFECT, good: this.counts.GOOD, average: this.counts.AVERAGE,
        miss: this.counts.MISS, boo: this.counts.BOO, rawScore: this.rawScore, score: this.score, maxCombo: this.maxCombo,
        totalNotes: this.totalNotes, completed: !!completed,
      };
    }
  }

  const RANKS = { AAA: "AAA", SDG: "SDG", FC: "FC", BLACKFLAG: "Blackflag FC", CLEAR: "Clear" };
  function rankOf(r) {
    if (r.good === 0 && r.average === 0 && r.miss === 0 && r.boo === 0) return "AAA";
    if (r.good < 10 && r.average === 0 && r.miss === 0 && r.boo === 0) return "SDG";
    if (r.miss === 0 && r.boo === 0) return "FC";
    if (r.miss === 0) return "BLACKFLAG";
    return "CLEAR";
  }
  function maxRawScore(r) { return r.totalNotes * JUDGMENTS.AMAZING.points; }
  function accuracy(r) { return r.totalNotes ? Math.min(1, Math.max(0, r.rawScore / maxRawScore(r))) : 0; }
  function isBetter(a, b) { return a.rawScore > b.rawScore || (a.rawScore === b.rawScore && a.maxCombo > b.maxCombo); }

  FR.engine = { JUDGMENTS, JUDGMENT_ORDER, MAX_WINDOW_MS, judgeError, GameEngine, RANKS, rankOf, maxRawScore, accuracy, isBetter };
})();
