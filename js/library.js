/* Song library: the built-in demo plus songs imported from folders or .zip packs, stored in IndexedDB. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});
  const { extension, baseName, dirName } = FR.util;
  const STEP_EXT = new Set(["sm", "ssc"]);
  const AUDIO_EXT = ["ogg", "mp3", "wav", "m4a", "flac", "aac", "mp4", "opus", "webm"];

  function openDb() {
    return new Promise((resolve, reject) => {
      if (!("indexedDB" in globalThis)) { reject(new Error("IndexedDB unavailable")); return; }
      const req = indexedDB.open("flashrev", 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("songs")) db.createObjectStore("songs", { keyPath: "id" });
        if (!db.objectStoreNames.contains("cache")) db.createObjectStore("cache");
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  function tx(db, mode, fn, storeName = "songs") {
    return new Promise((resolve, reject) => {
      const t = db.transaction(storeName, mode); const store = t.objectStore(storeName);
      let result; try { result = fn(store); } catch (e) { reject(e); return; }
      t.oncomplete = () => resolve(result && "result" in result ? result.result : result);
      t.onerror = () => reject(t.error); t.onabort = () => reject(t.error);
    });
  }

  /** Builds a Song from a parsed step file and its audio source. */
  function makeSong(id, parsed, audio) {
    return {
      id, title: parsed.title || id, subtitle: parsed.subtitle, artist: parsed.artist,
      displayBpm: parsed.displayBpm, sampleStart: parsed.sampleStart, charts: parsed.charts,
      audio, // { kind: "pcm", pcm, sampleRate } or { kind: "encoded", data: ArrayBuffer, name }
      get fullTitle() { return this.subtitle ? `${this.title} ${this.subtitle}` : this.title; },
    };
  }

  class SongLibrary {
    constructor() { this.songs = []; this.db = null; }

    /** Returns the demo song's PCM, rendering it once and caching the result in IndexedDB. */
    async demoPcm(composition) {
      const key = "demo-pcm-v" + FR.demo.PCM_VERSION;
      try {
        if (this.db) {
          const cached = await tx(this.db, "readonly", (s) => s.get(key), "cache");
          if (cached instanceof ArrayBuffer) {
            const pcm = new Float32Array(cached);
            if (pcm.length > FR.demo.SAMPLE_RATE * 10) return pcm;
          }
        }
      } catch (e) { /* fall through to rendering */ }
      const pcm = FR.demo.renderPcm(composition);
      try { if (this.db) await tx(this.db, "readwrite", (s) => s.put(pcm.buffer.slice(0), key), "cache"); } catch (e) { /* cache is optional */ }
      return pcm;
    }

    async load() {
      try { this.db = this.db || await openDb(); } catch (e) { console.warn("Song storage unavailable:", e); }
      if (!this.demoSong) {
        const composition = FR.demo.compose();
        const parsed = FR.chart.SmParser.parse(composition.smText);
        this.demoSong = makeSong(FR.demo.DEMO_ID, parsed, { kind: "pcm", pcm: await this.demoPcm(composition), sampleRate: composition.sampleRate });
      }
      const songs = [this.demoSong];
      try {
        if (!this.db) throw new Error("no db");
        const rows = await tx(this.db, "readonly", (s) => s.getAll());
        for (const row of rows) {
          try {
            const p = FR.chart.SmParser.parse(row.smText);
            if (p.charts.length) songs.push(makeSong(row.id, p, { kind: "encoded", data: row.audio, name: row.audioName }));
          } catch (e) { console.warn("Skipping stored song", row.id, e); }
        }
      } catch (e) { console.warn("Song storage unavailable:", e); }
      this.songs = songs.sort((a, b) => (a.id === FR.demo.DEMO_ID ? -1 : b.id === FR.demo.DEMO_ID ? 1 : a.title.localeCompare(b.title)));
      return this.songs;
    }

    find(id) { return this.songs.find((s) => s.id === id) || null; }

    async delete(id) {
      if (id === FR.demo.DEMO_ID) return;
      if (this.db) await tx(this.db, "readwrite", (s) => s.delete(id));
      this.songs = this.songs.filter((s) => s.id !== id);
    }

    /**
     * Imports from a list of {path, size, text(), arrayBuffer()} entries (from a directory input or a zip).
     * Returns {imported, skipped}.
     */
    async importEntries(entries, onProgress) {
      const groups = new Map();
      for (const e of entries) {
        const dir = dirName(e.path);
        if (!groups.has(dir)) groups.set(dir, []);
        groups.get(dir).push(e);
      }
      let imported = 0, skipped = 0, done = 0;
      this.db = this.db || await openDb();
      for (const [dir, files] of groups) {
        const step = files.find((f) => extension(f.path) === "sm") || files.find((f) => extension(f.path) === "ssc");
        if (!step) continue;
        done++;
        if (onProgress) onProgress(done, groups.size);
        try {
          const smText = await step.text();
          const parsed = FR.chart.SmParser.parse(smText);
          if (!parsed.charts.length) { skipped++; continue; }
          const wanted = baseName(parsed.music || "").toLowerCase();
          let audio = wanted ? files.find((f) => baseName(f.path).toLowerCase() === wanted) : null;
          if (!audio) for (const ext of AUDIO_EXT) { audio = files.find((f) => extension(f.path) === ext); if (audio) break; }
          if (!audio) { skipped++; continue; }
          const data = await audio.arrayBuffer();
          const id = (baseName(dir) || parsed.title || "song").replace(/[^\w .()\[\]-]/g, "_").slice(0, 80) || "song";
          const row = { id: id.startsWith("_") ? "s" + id : id, smText, audio: data, audioName: baseName(audio.path), addedAt: Date.now() };
          await tx(this.db, "readwrite", (s) => s.put(row));
          imported++;
        } catch (e) { console.warn("Import failed for", dir, e); skipped++; }
      }
      await this.load();
      return { imported, skipped };
    }

    /** Entries from an <input type=file webkitdirectory> FileList. */
    static entriesFromFiles(fileList) {
      return Array.from(fileList).map((f) => ({ path: f.webkitRelativePath || f.name, size: f.size, text: () => f.text(), arrayBuffer: () => f.arrayBuffer() }));
    }

    /** Entries from a .zip File, decoded lazily with DecompressionStream. Only step and audio files are listed. */
    static async entriesFromZip(file) {
      const size = file.size;
      const tailLen = Math.min(size, 65557 + 22);
      const tail = new DataView(await file.slice(size - tailLen, size).arrayBuffer());
      let eocd = -1;
      for (let i = tail.byteLength - 22; i >= 0; i--) if (tail.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
      if (eocd < 0) throw new Error("Not a zip file");
      const count = tail.getUint16(eocd + 10, true), cdSize = tail.getUint32(eocd + 12, true), cdOffset = tail.getUint32(eocd + 16, true);
      const cd = new DataView(await file.slice(cdOffset, cdOffset + cdSize).arrayBuffer());
      const entries = [];
      let p = 0;
      const dec = new TextDecoder();
      for (let i = 0; i < count && p + 46 <= cd.byteLength; i++) {
        if (cd.getUint32(p, true) !== 0x02014b50) break;
        const method = cd.getUint16(p + 10, true), csize = cd.getUint32(p + 20, true), usize = cd.getUint32(p + 24, true);
        const nameLen = cd.getUint16(p + 28, true), extraLen = cd.getUint16(p + 30, true), commentLen = cd.getUint16(p + 32, true);
        const localOffset = cd.getUint32(p + 42, true);
        const name = dec.decode(new Uint8Array(cd.buffer, cd.byteOffset + p + 46, nameLen));
        p += 46 + nameLen + extraLen + commentLen;
        if (name.endsWith("/")) continue;
        const ext = extension(name);
        if (!STEP_EXT.has(ext) && !AUDIO_EXT.includes(ext)) continue;
        if (method !== 0 && method !== 8) continue;
        const read = async () => {
          const lh = new DataView(await file.slice(localOffset, localOffset + 30).arrayBuffer());
          const dataStart = localOffset + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
          const blob = file.slice(dataStart, dataStart + csize);
          if (method === 0) return blob.arrayBuffer();
          const ds = new DecompressionStream("deflate-raw");
          return new Response(blob.stream().pipeThrough(ds)).arrayBuffer();
        };
        entries.push({ path: name, size: usize, arrayBuffer: read, text: async () => dec.decode(await read()) });
      }
      return entries;
    }
  }

  FR.library = { SongLibrary, STEP_EXT, AUDIO_EXT };
})();
