/* DOM screens: title, song select, settings, calibration, results, and the import flow. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});
  const { escapeHtml, fmtInt, pct } = FR.util;
  const { rankOf, RANKS, maxRawScore, accuracy } = FR.engine;

  const LOGO = `<svg class="logo" viewBox="0 0 108 108"><rect width="108" height="108" rx="24" fill="#0b0d16"/><path fill="#ff4040" d="M24 54 40 38v10h12v12H40v10z"/><path fill="#4080ff" d="M84 54 68 38v10H56v12h12v10z"/><path fill="#ffe040" d="M54 20 70 36H60v10H48V36H38z"/><path fill="#c040ff" d="M54 88 70 72H60V62H48v10H38z"/></svg>`;
  const LANE_NAMES = ["Left", "Down", "Up", "Right"];

  function keyName(code) {
    if (!code) return "—";
    if (code.startsWith("Key")) return code.slice(3);
    if (code.startsWith("Digit")) return code.slice(5);
    if (code.startsWith("Arrow")) return { ArrowLeft: "←", ArrowDown: "↓", ArrowUp: "↑", ArrowRight: "→" }[code];
    return code.replace(/([a-z])([A-Z])/g, "$1 $2");
  }

  class App {
    constructor() {
      this.ui = document.getElementById("ui");
      this.canvas = document.getElementById("game");
      this.pauseMenu = document.getElementById("pause-menu");
      this.settings = new FR.settings.Settings();
      this.scores = new FR.settings.ScoreStore();
      this.audio = new FR.audio.AudioEngine();
      this.library = new FR.library.SongLibrary();
      this.game = null;
      this.bufferCache = new Map();
      this.pauseMenu.addEventListener("click", (e) => {
        const a = e.target.closest("[data-action]"); if (!a || !this.game) return;
        ({ resume: () => this.game.resume(), restart: () => this.game.restart(), quit: () => this.game.quit() })[a.dataset.action]();
      });
    }

    async init() {
      this.render(`<div class="screen narrow">${LOGO}<h1>FlashRev</h1><p class="tagline">Loading…</p></div>`);
      await this.library.load();
      this.showTitle();
    }

    render(html) { this.ui.innerHTML = html; this.ui.classList.remove("hidden"); this.ui.scrollTop = 0; }
    on(selector, event, fn) { this.ui.querySelectorAll(selector).forEach((el) => el.addEventListener(event, fn)); }
    showPauseMenu(visible) { this.pauseMenu.classList.toggle("hidden", !visible); }

    // ------------------------------------------------------------ title
    showTitle() {
      const n = this.library.songs.length;
      this.render(`
        <div class="screen narrow">
          ${LOGO}<h1>FlashRev</h1><p class="tagline">A four-key rhythm game</p>
          <button class="btn primary" id="play">Play</button>
          <button class="btn" id="import">Import songs</button>
          <button class="btn" id="calibrate">Calibrate offset</button>
          <button class="btn" id="settings">Settings</button>
          <button class="btn" id="fullscreen">Toggle fullscreen <span class="kbd">F11</span></button>
          <p class="status">${n} song${n === 1 ? "" : "s"} in your library · Keys: ← ↓ ↑ → or D F J K</p>
        </div>`);
      this.on("#play", "click", () => { this.audio.ensure(); this.showSongs(); });
      this.on("#import", "click", () => this.showImport());
      this.on("#calibrate", "click", () => { this.audio.ensure(); this.showCalibration(); });
      this.on("#settings", "click", () => this.showSettings());
      this.on("#fullscreen", "click", () => this.toggleFullscreen());
    }

    toggleFullscreen() {
      if (document.fullscreenElement) document.exitFullscreen(); else document.documentElement.requestFullscreen().catch(() => {});
    }

    // ------------------------------------------------------------ song select
    showSongs() {
      const songs = this.library.songs;
      const cards = songs.map((song) => {
        const charts = song.charts.map((chart, i) => {
          const best = this.scores.best(FR.chart.chartKey(song, chart));
          const bestText = best ? `Best ${fmtInt(best.rawScore)} · ${RANKS[rankOf(best)]}` : "Not played";
          const name = chart.difficulty.key === "EDIT" && chart.description ? chart.description : chart.difficulty.name;
          return `<button class="btn chart" data-song="${escapeHtml(song.id)}" data-chart="${i}"><span>${escapeHtml(name)} ${chart.meter}</span><small>${chart.notes.length} notes · ${bestText}</small></button>`;
        }).join("");
        const del = song.id === FR.demo.DEMO_ID ? "" : `<div class="actions"><button class="btn small danger" data-delete="${escapeHtml(song.id)}">Delete</button></div>`;
        return `<div class="card song"><div class="title">${escapeHtml(song.fullTitle)}</div><div class="meta">${escapeHtml(song.artist ? song.artist + "  ·  " : "")}${escapeHtml(song.displayBpm)} BPM</div><div class="charts">${charts}</div>${del}</div>`;
      }).join("");
      this.render(`
        <div class="screen wide">
          <div class="topbar"><button class="btn small" id="back">‹ Back</button><h2>Song select</h2><button class="btn small" id="import">Import</button></div>
          ${cards || `<div class="empty">No songs yet.</div>`}
        </div>`);
      this.on("#back", "click", () => this.showTitle());
      this.on("#import", "click", () => this.showImport());
      this.on("[data-chart]", "click", (e) => {
        const song = this.library.find(e.currentTarget.dataset.song);
        const chart = song && song.charts[Number(e.currentTarget.dataset.chart)];
        if (song && chart) this.startGame(song, chart);
      });
      this.on("[data-delete]", "click", async (e) => {
        const song = this.library.find(e.currentTarget.dataset.delete);
        if (song && confirm(`Remove “${song.fullTitle}” from your library?`)) {
          await this.library.delete(song.id);
          this.scores.clearPrefix(song.id + "|");
          this.bufferCache.delete(song.id);
          this.showSongs();
        }
      });
    }

    // ------------------------------------------------------------ gameplay
    async decodeSong(song) {
      if (this.bufferCache.has(song.id)) return this.bufferCache.get(song.id);
      const buffer = song.audio.kind === "pcm"
        ? this.audio.bufferFromPcm(song.audio.pcm, song.audio.sampleRate)
        : await this.audio.decode(song.audio.data);
      this.bufferCache.set(song.id, buffer);
      return buffer;
    }

    async startGame(song, chart) {
      this.audio.ensure();
      if (this.game) { this.game.destroy(); this.game = null; }
      this.render(`<div class="screen narrow"><h2>${escapeHtml(song.fullTitle)}</h2><p class="tagline">Loading audio…</p></div>`);
      let buffer;
      try { buffer = await this.decodeSong(song); }
      catch (e) { alert("Could not decode the song audio: " + (e.message || e)); this.showSongs(); return; }
      this.ui.classList.add("hidden");
      this.canvas.classList.remove("hidden");
      this.game = new FR.game.GameScreen({
        app: this, song, chart, buffer, canvas: this.canvas, settings: this.settings, audio: this.audio,
        onFinished: (result) => this.showResults(song, chart, result),
        onQuit: () => { this.endGame(); this.showSongs(); },
      });
      this.game.start();
    }

    endGame() {
      if (this.game) { this.game.destroy(); this.game = null; }
      this.canvas.classList.add("hidden");
      this.showPauseMenu(false);
    }

    // ------------------------------------------------------------ results
    showResults(song, chart, result) {
      this.endGame();
      const key = FR.chart.chartKey(song, chart);
      const newBest = this.scores.submit(key, result);
      const rank = rankOf(result);
      const stat = (label, val, cls = "") => `<div class="stat"><span class="label ${cls}">${label}</span><span class="val">${val}</span></div>`;
      this.render(`
        <div class="screen narrow results">
          <p class="hint" style="margin:0">RESULTS</p>
          <h2 style="text-align:center">${escapeHtml(song.fullTitle)}</h2>
          <p class="hint" style="margin:2px 0 0">${escapeHtml(chart.difficulty.name)} ${chart.meter}</p>
          <div class="rank ${rank}">${RANKS[rank]}</div>
          ${newBest ? `<p class="newbest">New best!</p>` : `<p style="margin:0 0 10px"></p>`}
          <div class="card">
            ${stat("Score", fmtInt(result.score))}${stat("Raw score", `${fmtInt(result.rawScore)} / ${fmtInt(maxRawScore(result))}`)}
            ${stat("Accuracy", pct(accuracy(result)))}${stat("Max combo", result.maxCombo)}
          </div>
          <div class="card">
            ${stat("Amazing", result.amazing, "AMAZING")}${stat("Perfect", result.perfect, "PERFECT")}${stat("Good", result.good, "GOOD")}
            ${stat("Average", result.average, "AVERAGE")}${stat("Miss", result.miss, "MISS")}${stat("Boo", result.boo, "BOO")}
          </div>
          <div class="row"><button class="btn" id="songs">Song select</button><button class="btn primary" id="retry">Retry <span class="kbd">Enter</span></button></div>
        </div>`);
      this.on("#songs", "click", () => this.showSongs());
      this.on("#retry", "click", () => this.startGame(song, chart));
      const onKey = (e) => { if (e.code === "Enter") { window.removeEventListener("keydown", onKey); this.startGame(song, chart); } if (e.code === "Escape") { window.removeEventListener("keydown", onKey); this.showSongs(); } };
      window.addEventListener("keydown", onKey);
      this.ui.addEventListener("click", () => window.removeEventListener("keydown", onKey), { once: true });
    }

    // ------------------------------------------------------------ import
    showImport() {
      this.render(`
        <div class="screen narrow">
          <div class="topbar"><button class="btn small" id="back">‹ Back</button><h2>Import songs</h2></div>
          <div class="card">
            <p style="margin-top:0">Add StepMania songs: a song folder (with its <b>.sm</b>/<b>.ssc</b> file and audio), a pack folder containing many song folders, or a <b>.zip</b> of such folders. Only dance-single charts are used. Songs are stored in your browser.</p>
            <button class="btn primary" id="pick-folder">Choose a folder…</button>
            <button class="btn" id="pick-zip">Choose a .zip pack…</button>
            <input type="file" id="folder-input" webkitdirectory multiple class="hidden">
            <input type="file" id="zip-input" accept=".zip,application/zip" class="hidden">
            <p class="hint" id="import-status"></p>
          </div>
        </div>`);
      this.on("#back", "click", () => this.showSongs());
      const status = this.ui.querySelector("#import-status");
      const folderInput = this.ui.querySelector("#folder-input"), zipInput = this.ui.querySelector("#zip-input");
      this.on("#pick-folder", "click", () => folderInput.click());
      this.on("#pick-zip", "click", () => zipInput.click());
      const run = async (entriesPromise) => {
        status.textContent = "Importing…";
        try {
          const entries = await entriesPromise;
          const r = await this.library.importEntries(entries, (done, total) => { status.textContent = `Importing… ${done} / ${total}`; });
          status.textContent = r.imported ? `Imported ${r.imported} song${r.imported === 1 ? "" : "s"}${r.skipped ? ` (${r.skipped} skipped)` : ""}.` : "No playable songs were found in that selection.";
        } catch (e) { status.textContent = "Import failed: " + (e.message || e); }
      };
      folderInput.addEventListener("change", () => { if (folderInput.files.length) run(Promise.resolve(FR.library.SongLibrary.entriesFromFiles(folderInput.files))); });
      zipInput.addEventListener("change", () => { if (zipInput.files.length) run(FR.library.SongLibrary.entriesFromZip(zipInput.files[0])); });
    }

    // ------------------------------------------------------------ settings
    showSettings() {
      const s = this.settings;
      const keys = s.get("keys");
      const keyCells = keys.map((codes, lane) => `<div class="key" data-lane="${lane}"><div class="lane">${LANE_NAMES[lane]}</div><div class="codes">${codes.map(keyName).join(" / ")}</div></div>`).join("");
      this.render(`
        <div class="screen narrow">
          <div class="topbar"><button class="btn small" id="back">‹ Back</button><h2>Settings</h2></div>
          <div class="card setting"><div class="head"><span>Scroll speed</span><span class="value" id="speed-val"></span></div><div class="desc">How fast arrows travel.</div><input type="range" id="speed" min="0.5" max="5" step="0.1"></div>
          <div class="card setting"><div class="head"><span>Scroll direction</span></div>
            <label class="check"><input type="radio" name="dir" id="dir-up"> Up — arrows rise to receptors at the top (FFR style)</label>
            <label class="check"><input type="radio" name="dir" id="dir-down"> Down — arrows fall to receptors at the bottom</label></div>
          <div class="card setting"><div class="head"><span>Global offset</span><span class="value" id="offset-val"></span></div><div class="desc">Positive values make notes later relative to the music. Use Calibrate to measure it.</div>
            <input type="range" id="offset" min="-300" max="300" step="1">
            <div class="row"><button class="btn small" id="offset-minus">−5 ms</button><button class="btn small" id="offset-plus">+5 ms</button><button class="btn small" id="calibrate">Calibrate…</button></div></div>
          <div class="card setting"><div class="head"><span>Lane size</span><span class="value" id="lane-val"></span></div><div class="desc">Width of the four arrow columns.</div><input type="range" id="lane" min="70" max="160" step="2"></div>
          <div class="card setting"><div class="head"><span>Keys</span><button class="btn small" id="rebind">Rebind…</button></div><div class="desc">Two keys per lane. Click Rebind, then press a key for each lane in order.</div><div class="keys" id="keys">${keyCells}</div></div>
          <div class="card setting">
            <label class="check"><input type="checkbox" id="hit"> Hit sounds</label><div class="desc">Play a click when you hit a note.</div>
            <label class="check"><input type="checkbox" id="early"> Show early / late</label><div class="desc">Small indicator under the judgment.</div>
            <label class="check"><input type="checkbox" id="snap"> Colour notes by rhythm</label><div class="desc">Red 4ths, blue 8ths, purple 12ths, yellow 16ths…</div>
          </div>
          <button class="btn danger" id="reset">Reset to defaults</button>
        </div>`);
      const q = (id) => this.ui.querySelector(id);
      const refresh = () => {
        q("#speed").value = s.get("scrollSpeed"); q("#speed-val").textContent = s.get("scrollSpeed").toFixed(1) + "x";
        q("#dir-up").checked = s.get("scrollUp"); q("#dir-down").checked = !s.get("scrollUp");
        q("#offset").value = s.get("offsetMs"); q("#offset-val").textContent = s.get("offsetMs") + " ms";
        q("#lane").value = s.get("laneWidth"); q("#lane-val").textContent = s.get("laneWidth") + " px";
        q("#hit").checked = s.get("hitSounds"); q("#early").checked = s.get("earlyLate"); q("#snap").checked = s.get("snapColors");
      };
      refresh();
      this.on("#back", "click", () => this.showTitle());
      this.on("#speed", "input", (e) => { s.set("scrollSpeed", parseFloat(e.target.value)); refresh(); });
      this.on("#dir-up", "change", () => { s.set("scrollUp", true); refresh(); });
      this.on("#dir-down", "change", () => { s.set("scrollUp", false); refresh(); });
      this.on("#offset", "input", (e) => { s.set("offsetMs", parseInt(e.target.value, 10)); refresh(); });
      this.on("#offset-minus", "click", () => { s.set("offsetMs", s.get("offsetMs") - 5); refresh(); });
      this.on("#offset-plus", "click", () => { s.set("offsetMs", s.get("offsetMs") + 5); refresh(); });
      this.on("#calibrate", "click", () => { this.audio.ensure(); this.showCalibration(); });
      this.on("#lane", "input", (e) => { s.set("laneWidth", parseInt(e.target.value, 10)); refresh(); });
      this.on("#hit", "change", (e) => s.set("hitSounds", e.target.checked));
      this.on("#early", "change", (e) => s.set("earlyLate", e.target.checked));
      this.on("#snap", "change", (e) => s.set("snapColors", e.target.checked));
      this.on("#reset", "click", () => { s.reset(); this.showSettings(); });
      this.on("#rebind", "click", () => this.rebindKeys());
    }

    rebindKeys() {
      const cells = [...this.ui.querySelectorAll("#keys .key")];
      const newKeys = [];
      let lane = 0;
      cells.forEach((c, i) => { c.classList.toggle("waiting", i === 0); c.querySelector(".codes").textContent = i === 0 ? "press a key…" : "…"; });
      const onKey = (e) => {
        e.preventDefault();
        if (e.code === "Escape") { window.removeEventListener("keydown", onKey); this.showSettings(); return; }
        if (newKeys.some((k) => k[0] === e.code)) return;
        newKeys.push([e.code]);
        cells[lane].classList.remove("waiting");
        cells[lane].querySelector(".codes").textContent = keyName(e.code);
        lane++;
        if (lane < 4) { cells[lane].classList.add("waiting"); cells[lane].querySelector(".codes").textContent = "press a key…"; return; }
        window.removeEventListener("keydown", onKey);
        // Keep the arrow keys as a second binding unless the player rebound to them.
        const arrows = ["ArrowLeft", "ArrowDown", "ArrowUp", "ArrowRight"];
        const keys = newKeys.map((k, i) => (newKeys.some((o) => o[0] === arrows[i]) ? k : [k[0], arrows[i]]));
        this.settings.set("keys", keys);
        this.showSettings();
      };
      window.addEventListener("keydown", onKey);
    }

    // ------------------------------------------------------------ calibration
    async showCalibration() {
      const demo = this.library.find(FR.demo.DEMO_ID);
      const buffer = await this.decodeSong(demo);
      const playback = this.audio.createPlayback(buffer);
      const beat = 60 / FR.demo.BPM, leadIn = FR.demo.LEAD_IN;
      const offsets = [];
      this.render(`
        <div class="calib">
          <div><h2 style="text-align:center">Offset calibration</h2><p>Press any lane key (or click) on the beat. Keep going for a dozen beats; the measured offset appears below. Press Save when the number settles. This measures your audio path's latency through the same playback the game uses.</p></div>
          <div class="ring" id="ring"></div>
          <div class="bottom"><div class="measured" id="measured">Waiting for taps…</div><div class="row"><button class="btn" id="cancel">Cancel <span class="kbd">Esc</span></button><button class="btn primary" id="save">Save <span class="kbd">Enter</span></button></div></div>
        </div>`);
      const measured = this.ui.querySelector("#measured"), ring = this.ui.querySelector("#ring");
      const mean = () => { const s = offsets.slice().sort((a, b) => a - b); const trim = s.length >= 8 ? Math.floor(s.length / 8) : 0; const core = s.slice(trim, s.length - trim); return core.reduce((a, b) => a + b, 0) / core.length; };
      const tap = (perfMs) => {
        const t = playback.positionAt(this.audio.heardTimeAt(perfMs)) - leadIn;
        if (t < 0) return;
        const err = (t - Math.round(t / beat) * beat) * 1000;
        if (Math.abs(err) > 200) return;
        offsets.push(err); if (offsets.length > 24) offsets.shift();
        measured.textContent = `Measured offset: ${Math.round(mean())} ms (${offsets.length} taps)`;
        ring.style.background = "rgba(255,255,255,0.6)"; setTimeout(() => { ring.style.background = "transparent"; }, 120);
      };
      let raf = 0;
      const finish = () => { cancelAnimationFrame(raf); playback.stop(); window.removeEventListener("keydown", onKey); };
      const onKey = (e) => {
        if (e.repeat) return;
        if (e.code === "Escape") { finish(); this.showTitle(); return; }
        if (e.code === "Enter") { save(); return; }
        if (this.settings.laneForCode(e.code) >= 0 || e.code === "Space") { e.preventDefault(); tap(e.timeStamp); }
      };
      const save = () => { if (offsets.length < 4) { measured.textContent = "Keep tapping — a few more beats needed."; return; } this.settings.set("offsetMs", Math.round(mean())); finish(); this.showTitle(); };
      window.addEventListener("keydown", onKey);
      this.on("#cancel", "click", () => { finish(); this.showTitle(); });
      this.on("#save", "click", save);
      this.ui.querySelector(".calib").addEventListener("pointerdown", (e) => { if (!e.target.closest("button")) tap(e.timeStamp); });
      const animate = () => {
        raf = requestAnimationFrame(animate);
        const t = playback.positionAt() - leadIn;
        const phase = t >= 0 ? (t % beat) / beat : 1;
        ring.style.transform = `scale(${1 + 0.6 * (1 - phase)})`; ring.style.opacity = String(1 - phase * 0.7);
        if (playback.ended) playback.start(0);
      };
      playback.start(0);
      animate();
    }
  }

  FR.App = App;
})();
