/* Runs the pure-logic modules in a bare VM context (as the browser would load them) and checks them. */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert");

for (const f of ["util.js", "chart.js", "engine.js", "demo.js"]) {
  vm.runInThisContext(fs.readFileSync(path.join(__dirname, "..", "js", f), "utf8"), { filename: f });
}
const FR = globalThis.FR;
const { SmParser, TimingData, snapFromRow192, DIFFICULTIES } = FR.chart;
const { GameEngine, JUDGMENTS, judgeError, rankOf, accuracy } = FR.engine;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);
const chart = (...pairs) => ({ notes: pairs.map(([t, lane]) => ({ time: t, lane, beat: t * 2, snap: "4th" })).sort((a, b) => a.time - b.time || a.lane - b.lane) });

// ---------------------------------------------------------------- timing
test("timing: constant bpm", () => { const t = new TimingData(0, [[0, 120]], []); near(t.beatToTime(1), 0.5); near(t.beatToTime(4), 2); });
test("timing: bpm change", () => { const t = new TimingData(0, [[0, 60], [2, 120]], []); near(t.beatToTime(2), 2); near(t.beatToTime(3), 2.5); });
test("timing: stops and offset", () => { const t = new TimingData(0.25, [[0, 120]], [[1, 0.5]]); near(t.beatToTime(0), -0.25); near(t.beatToTime(1), 0.25); near(t.beatToTime(2), 1.25); });
test("timing: parsePairs ignores garbage", () => assert.strictEqual(JSON.stringify(TimingData.parsePairs("0=120, 4.5=180.25 ,, x=1, 8")), JSON.stringify([[0, 120], [4.5, 180.25]])));

// ---------------------------------------------------------------- parser
const SM = `#TITLE:Test Song;\n#ARTIST:Someone;\n#MUSIC:song.ogg;\n#OFFSET:-0.5;   // comment\n#SAMPLESTART:12.5;\n#BPMS:0.000=120.000,8.000=240.000;\n#STOPS:4.000=1.000;\n#NOTES:\n     dance-single:\n     author:\n     Hard:\n     9:\n     0,0,0,0,0:\n1000\n0100\n0010\n0001\n,\n2000\n0000\n3000\nM000\n,\n1100\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n;\n#NOTES:\n     dance-double:\n     :\n     Hard:\n     9:\n     0,0,0,0,0:\n10000000\n;\n`;
test("parser: header and single charts only", () => {
  const p = SmParser.parse(SM);
  assert.strictEqual(p.title, "Test Song"); assert.strictEqual(p.artist, "Someone"); assert.strictEqual(p.music, "song.ogg");
  near(p.sampleStart, 12.5); assert.strictEqual(p.displayBpm, "120–240"); assert.strictEqual(p.charts.length, 1);
  const c = p.charts[0]; assert.strictEqual(c.difficulty, DIFFICULTIES.HARD); assert.strictEqual(c.meter, 9); assert.strictEqual(c.description, "author");
});
test("parser: note times, lanes, snaps, holds and mines", () => {
  const n = SmParser.parse(SM).charts[0].notes;
  assert.strictEqual(n.length, 7);
  near(n[0].time, 0.5); assert.strictEqual(n[0].lane, 0); assert.strictEqual(n[0].snap, "4th");
  near(n[3].time, 2.0); assert.strictEqual(n[3].lane, 3);
  near(n[4].time, 2.5); assert.strictEqual(n[4].lane, 0);          // hold head; stop at beat 4 does not delay beat 4
  near(n[5].time, 5.5); near(n[6].time, 5.5); assert.strictEqual(n[5].lane + "," + n[6].lane, "0,1");
});
test("parser: ssc blocks", () => {
  const ssc = `#VERSION:0.83;\n#TITLE:SSC Song;\n#OFFSET:0.0;\n#BPMS:0=150;\n#NOTEDATA:;\n#STEPSTYPE:dance-single;\n#DIFFICULTY:Challenge;\n#METER:12;\n#NOTES:\n0000\n0000\n0000\n0000\n0000\n0000\n0000\n1000\n;\n#NOTEDATA:;\n#STEPSTYPE:dance-single;\n#DIFFICULTY:Easy;\n#METER:2;\n#BPMS:0=300;\n#NOTES:\n0001\n;\n`;
  const p = SmParser.parse(ssc);
  assert.strictEqual(p.charts.length, 2);
  const [easy, challenge] = p.charts;
  assert.strictEqual(easy.difficulty, DIFFICULTIES.EASY); assert.strictEqual(challenge.difficulty, DIFFICULTIES.CHALLENGE);
  near(challenge.notes[0].time, 1.4); assert.strictEqual(challenge.notes[0].snap, "8th");
  near(easy.notes[0].time, 0); assert.strictEqual(easy.notes[0].lane, 3);
});
test("parser: snap classification", () => {
  assert.strictEqual([0, 24, 16, 12, 8, 6, 4, 3, 1].map(snapFromRow192).join(), "4th,8th,12th,16th,24th,32nd,48th,64th,other");
});
test("parser: empty charts dropped", () => assert.strictEqual(SmParser.parse("#TITLE:x;\n#BPMS:0=120;\n#NOTES:\ndance-single:\n:\nEasy:\n1:\n:\n0000\n;").charts.length, 0));

// ---------------------------------------------------------------- engine
test("engine: windows", () => {
  assert.strictEqual(judgeError(-17), JUDGMENTS.AMAZING); assert.strictEqual(judgeError(17.5), JUDGMENTS.PERFECT); assert.strictEqual(judgeError(50), JUDGMENTS.PERFECT);
  assert.strictEqual(judgeError(-51), JUDGMENTS.GOOD); assert.strictEqual(judgeError(84), JUDGMENTS.AVERAGE); assert.strictEqual(judgeError(118), JUDGMENTS.MISS);
});
test("engine: hits and scoring", () => {
  const e = new GameEngine(chart([1, 0], [2, 0]));
  const ev = e.tap(0, 1.010); assert.strictEqual(ev.judgment, JUDGMENTS.AMAZING); near(ev.errorMs, 10, 1e-6);
  assert.strictEqual(e.combo, 1); assert.strictEqual(e.rawScore, 50); assert.strictEqual(e.score, 51);
  const ev2 = e.tap(0, 2.060); assert.strictEqual(ev2.judgment, JUDGMENTS.GOOD); assert.strictEqual(e.rawScore, 75); assert.strictEqual(e.score, 51 + 25 + 2); assert.ok(e.isFinished);
});
test("engine: boo keeps combo", () => {
  const e = new GameEngine(chart([1, 0], [5, 1])); e.tap(0, 1);
  const ev = e.tap(1, 1); assert.strictEqual(ev.judgment, JUDGMENTS.BOO); assert.strictEqual(e.combo, 1); assert.strictEqual(e.rawScore, 45); assert.ok(!e.isFinished);
});
test("engine: early tap outside window is boo, note stays", () => {
  const e = new GameEngine(chart([1, 2])); assert.strictEqual(e.tap(2, 0.80).judgment, JUDGMENTS.BOO); assert.strictEqual(e.tap(2, 0.97).judgment, JUDGMENTS.PERFECT);
});
test("engine: misses break combo", () => {
  const e = new GameEngine(chart([1, 0], [1.5, 1], [3, 2])); e.tap(0, 1);
  assert.strictEqual(e.update(1.2).length, 0);
  const m = e.update(1.7); assert.strictEqual(m.length, 1); assert.strictEqual(m[0].lane, 1);
  assert.strictEqual(e.combo, 0); assert.strictEqual(e.maxCombo, 1); assert.strictEqual(e.rawScore, 40); assert.strictEqual(e.judgedNotes, 2);
});
test("engine: earliest note first", () => {
  const e = new GameEngine(chart([1.00, 3], [1.06, 3]));
  assert.strictEqual(e.tap(3, 1.04).note.index, 0); const ev2 = e.tap(3, 1.07); assert.strictEqual(ev2.note.index, 1); assert.strictEqual(ev2.judgment, JUDGMENTS.AMAZING);
});
test("engine: ranks", () => {
  const aaa = { amazing: 10, perfect: 5, good: 0, average: 0, miss: 0, boo: 0, rawScore: 750, score: 800, maxCombo: 15, totalNotes: 15, completed: true };
  assert.strictEqual(rankOf(aaa), "AAA"); near(accuracy(aaa), 1);
  assert.strictEqual(rankOf({ ...aaa, good: 9 }), "SDG"); assert.strictEqual(rankOf({ ...aaa, good: 10 }), "FC");
  assert.strictEqual(rankOf({ ...aaa, boo: 1 }), "BLACKFLAG"); assert.strictEqual(rankOf({ ...aaa, miss: 1 }), "CLEAR");
});

// ---------------------------------------------------------------- demo
test("demo: audio and charts", () => {
  const t0 = Date.now();
  const g = FR.demo.generate();
  const seconds = g.pcm.length / g.sampleRate;
  assert.ok(seconds > 50 && seconds < 70, `duration ${seconds}`);
  let peak = 0; for (let i = 0; i < g.pcm.length; i++) peak = Math.max(peak, Math.abs(g.pcm[i]));
  assert.ok(peak > 0.8 && peak <= 1, `peak ${peak}`);
  const p = SmParser.parse(g.smText);
  assert.strictEqual(p.charts.length, 3);
  const counts = p.charts.map((c) => c.notes.length);
  assert.ok(counts[0] < counts[1] && counts[1] < counts[2], `counts ${counts}`);
  assert.ok(counts[0] >= 60);
  const secPerSlot = 60 / FR.demo.BPM / 4;
  for (const c of p.charts) for (const n of c.notes) {
    assert.ok(n.lane >= 0 && n.lane <= 3); assert.ok(n.time >= FR.demo.LEAD_IN - 1e-9 && n.time < seconds - 1);
    const slots = (n.time - FR.demo.LEAD_IN) / secPerSlot; assert.ok(Math.abs(slots - Math.round(slots)) < 1e-6, `off-grid ${n.time}`);
  }
  console.log(`  (demo generated in ${Date.now() - t0} ms, ${counts} notes)`);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log("ok   " + name); } catch (e) { failed++; console.log("FAIL " + name + "\n     " + (e.stack || e)); }
}
console.log(`\n${tests.length - failed}/${tests.length} passed`);
process.exit(failed ? 1 : 0);
