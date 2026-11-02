/* Bootstrap. */
(() => {
  "use strict";
  const app = new globalThis.FR.App();
  globalThis.FR.app = app;
  app.init().catch((e) => { console.error(e); document.getElementById("ui").innerHTML = `<div class="screen narrow"><h2>Failed to start</h2><p class="hint">${e.message || e}</p></div>`; });
})();
