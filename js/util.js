/* Small shared helpers. */
(() => {
  "use strict";
  const FR = globalThis.FR || (globalThis.FR = {});
  FR.util = {
    clamp: (v, lo, hi) => Math.min(hi, Math.max(lo, v)),
    fmtInt: (n) => Math.round(n).toLocaleString("en-US"),
    pct: (v, digits = 2) => (v * 100).toFixed(digits) + "%",
    extension: (name) => { const i = name.lastIndexOf("."); return i < 0 ? "" : name.slice(i + 1).toLowerCase(); },
    baseName: (p) => p.replace(/\\/g, "/").split("/").pop(),
    dirName: (p) => { const s = p.replace(/\\/g, "/"); const i = s.lastIndexOf("/"); return i < 0 ? "" : s.slice(0, i); },
    escapeHtml: (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])),
  };
})();
