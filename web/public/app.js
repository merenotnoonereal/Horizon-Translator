// Horizon Translator website: converts shader packs in the browser, links to the Windows app.
// The project's GitHub repository (issue links and the app download).
const REPO_URL = "https://github.com/merenotnoonereal/Horizon-Translator";

const $ = (s, root = document) => root.querySelector(s);
const state = {
  mod: null,
  shader: null,
  kind: "mod",
  busy: false,
  step: "",
  verifyRelease: null,
  modsData: null,
  donateData: null,
  lastResult: null
};

// Both converters run in the visitor's own browser (WebAssembly): files are never uploaded anywhere.
const BROWSER_OK = typeof WebAssembly === "object" && typeof Worker === "function";

function bindRepoLinks(root = document) {
  const repoLink = document.getElementById("repo-link");
  if (repoLink) repoLink.href = REPO_URL;
  for (const a of root.querySelectorAll(".repo-link")) a.href = REPO_URL;
  for (const a of root.querySelectorAll(".repo-issues")) a.href = REPO_URL + "/issues/new/choose";
  for (const a of root.querySelectorAll(".repo-ideas")) a.href = REPO_URL + "/issues/new?template=feature_request.md";

  // The Windows app: the newest HorizonTranslator-Setup.exe attached to a GitHub Release.
  const APP_DOWNLOAD = REPO_URL + "/releases/latest/download/HorizonTranslator-Setup.exe";
  for (const a of root.querySelectorAll("[id=download-app]")) a.href = APP_DOWNLOAD;
  for (const a of root.querySelectorAll("[id=download-linux]")) a.href = REPO_URL + "/releases/latest/download/HorizonTranslator-linux-x64.tar.gz";
  for (const a of root.querySelectorAll("[id=download-mac-arm]")) a.href = REPO_URL + "/releases/latest/download/HorizonTranslator-mac-arm64.dmg";
  for (const a of root.querySelectorAll("[id=download-mac-intel]")) a.href = REPO_URL + "/releases/latest/download/HorizonTranslator-mac-x64.dmg";
  for (const a of root.querySelectorAll(".repo-releases")) a.href = REPO_URL + "/releases";
  for (const a of root.querySelectorAll(".open-verify")) {
    a.onclick = (e) => {
      e.preventDefault();
      const box = $("#verify-app");
      if (box) {
        box.open = true;
        box.scrollIntoView({ behavior: "smooth" });
      }
    };
  }
}
bindRepoLinks();

// "Check the file yourself": show the SHA-256 that GitHub itself computed for the latest installer,
// plus its VirusTotal page. If GitHub can't be reached, the static instructions still work.
(async () => {
  try {
    const api = REPO_URL.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest";
    const res = await fetch(api, { headers: { Accept: "application/vnd.github+json" } });
    if (!res.ok) return;
    const rel = await res.json();
    const asset = (rel.assets || []).find((a) => a.name === "HorizonTranslator-Setup.exe");
    const m = /^sha256:([0-9a-f]{64})$/.exec(asset?.digest || "");
    if (!m) return;
    state.verifyRelease = { tag: rel.tag_name || "latest", hash: m[1].toUpperCase(), rawHash: m[1] };
    updateVerifyLive();
  } catch {}
})();

function updateVerifyLive() {
  if (!state.verifyRelease) return;
  const box = $("#verify-app");
  if (!box) return;
  const liveEl = $(".verify-live", box);
  if (liveEl) {
    if (window.I18n && I18n.has("verify.1.live")) {
      I18n.setSafeHtml(liveEl, I18n.t("verify.1.live", state.verifyRelease.tag, state.verifyRelease.hash));
    } else {
      $(".verify-tag", box).textContent = state.verifyRelease.tag;
      $(".verify-hash", box).textContent = state.verifyRelease.hash;
    }
    liveEl.hidden = false;
  }
  const vtEl = $(".verify-vt", box);
  if (vtEl) {
    const link = $(".verify-vt-link", box);
    if (link) {
      link.href = "https://www.virustotal.com/gui/file/" + state.verifyRelease.rawHash;
      if (window.I18n && I18n.has("verify.3.vt")) {
        link.textContent = I18n.t("verify.3.vt");
      }
    }
    vtEl.hidden = false;
  }
}

// A new GitHub issue with the result's details filled in (the log is cut so the link stays short enough).
function issueLink(kind, name, d) {
  const what = kind === "mod" ? "Mod" : "Shader pack";
  const title = `${what} conversion: ${name} (${d.status || (d.ok ? "converted" : "failed")})`;
  const log = (d.log || "").slice(-3000);
  const body = [
    `**${what}:** ${name}`,
    `**Download link for it:** (please add where you got it)`,
    `**Result:** ${d.status || ""} ${d.ok ? d.summary || "" : d.error || ""}`,
    "",
    "**What went wrong / what did you expect?**",
    "",
    "",
    "<details><summary>Converter log (end)</summary>",
    "",
    "```",
    log,
    "```",
    "</details>",
  ].join("\n");
  return `${REPO_URL}/issues/new?title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`;
}

// ---- tabs
for (const tab of document.querySelectorAll(".tab")) {
  tab.addEventListener("click", () => selectTab(tab.id.slice(4)));
}
for (const a of document.querySelectorAll(".open-make")) {
  a.addEventListener("click", (e) => {
    e.preventDefault();
    selectTab("make");
    $("#convert").scrollIntoView({ behavior: "smooth" });
  });
}
function selectTab(kind) {
  if (kind !== "make") state.kind = kind;
  if (kind === "make") {
    loadGuide();
    loadMods();
  }
  for (const k of ["mod", "shader", "make"]) {
    const on = k === kind;
    $("#tab-" + k).classList.toggle("active", on);
    $("#tab-" + k).setAttribute("aria-selected", on);
    $("#panel-" + k).hidden = !on;
  }
}

// ---- the mod kit's guide (making-mods.md, also shipped in the app), loaded when its tab first opens
let guideLoaded = false;
async function loadGuide(force = false) {
  if (guideLoaded && !force) return;
  const el = $("#guide");
  if (!el) return;
  guideLoaded = true;
  const lang = window.I18n ? I18n.lang() : "en";
  try {
    let r = null;
    if (lang !== "en") {
      r = await fetch(`i18n/making-mods.${lang}.md`, { cache: "no-cache" });
      if (!r.ok) {
        // Fallback to English
        r = await fetch("making-mods.md", { cache: "no-cache" });
      }
    } else {
      r = await fetch("making-mods.md", { cache: "no-cache" });
    }
    if (!r.ok) throw new Error(r.status);
    el.innerHTML = renderMarkdown(await r.text());
  } catch {
    guideLoaded = false;
    const errUrl = `${REPO_URL}/blob/main/web/public/making-mods.md`;
    const errMsg = window.I18n
      ? I18n.t("guide.error", errUrl)
      : `The guide didn't load. Read it on <a href="${errUrl}" rel="noopener">GitHub</a>.`;
    el.innerHTML = `<p class="hint">${errMsg}</p>`;
  }
}

// ---- the shared mods (mods/index.json, written by scripts/mods-index.py), listed when the tab first opens
let modsLoaded = false;
async function loadMods() {
  if (modsLoaded) return;
  try {
    const r = await fetch("mods/index.json", { cache: "no-cache" });
    if (!r.ok) throw new Error(r.status);
    state.modsData = ((await r.json()).mods || []).filter((m) => /^[a-z0-9_]+\.hnmod$/.test(m.file));
    modsLoaded = true;
    renderMods();
  } catch {
    modsLoaded = false;
    renderModsError();
  }
}

function renderMods() {
  const el = $("#mod-list");
  if (!el || !state.modsData) return;
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const mods = state.modsData;
  if (!mods.length) {
    el.innerHTML = `<li class="hint">${window.I18n ? I18n.t("shared.none") : "No mods yet."}</li>`;
    return;
  }
  const dlText = window.I18n ? I18n.t("shared.download") : "Download .hnmod";
  const srcText = window.I18n ? I18n.t("shared.source") : "Source";

  el.innerHTML = mods.map((m) => {
    const extraNeeds = m.needs ? " (" + esc(m.needs) + ")" : "";
    const sizeKb = Math.max(1, Math.round(m.size / 1024));
    const sha = esc(m.sha256.slice(0, 16));
    const needsLine = window.I18n
      ? I18n.t("shared.needs", esc(m.hooks), extraNeeds, sizeKb, sha)
      : `Needs Horizon's hook points API ${esc(m.hooks)} or newer${extraNeeds} · ${sizeKb} KB · SHA-256 <code>${sha}…</code>`;

    return `
      <li class="mod">
        <div>
          <strong>${esc(m.name)}</strong> <span class="ver">${esc(m.version)}</span>
          <p>${esc(m.description)}</p>
          <p class="small-print">${needsLine}</p>
        </div>
        <div class="mod-actions">
          <a class="go" href="mods/${esc(m.file)}" download>${dlText}</a>
          <a class="ghost" href="${REPO_URL}/tree/main/mods/${esc(m.id)}" rel="noopener">${srcText}</a>
        </div>
      </li>`;
  }).join("");
}

function renderModsError() {
  const el = $("#mod-list");
  if (!el) return;
  const url = `${REPO_URL}/tree/main/mods`;
  if (window.I18n) {
    el.innerHTML = `<li class="hint">${I18n.t("shared.error", url)}</li>`;
  } else {
    el.innerHTML = `<li class="hint">The list didn't load. See the mods on <a href="${url}" rel="noopener">GitHub</a>.</li>`;
  }
}

if (location.hash === "#make") selectTab("make");

// ---- file choosing (click or drag and drop)
for (const drop of document.querySelectorAll(".drop")) {
  const kind = drop.dataset.kind;
  const input = $("input[type=file]", drop);
  input.addEventListener("change", () => input.files[0] && choose(kind, input.files[0]));
  drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", (e) => {
    e.preventDefault();
    drop.classList.remove("over");
    const f = e.dataTransfer.files[0];
    if (f) choose(kind, f);
  });
}

function choose(kind, file) {
  const ending = kind === "mod" ? ".jar" : ".zip";
  const drop = $(`.drop[data-kind=${kind}]`);
  if (!file.name.toLowerCase().endsWith(ending)) {
    const wrongText = window.I18n ? I18n.t("drop.wrong_type", ending) : `That's not a ${ending} file.`;
    $(".drop-file", drop).textContent = wrongText;
    state[kind] = null;
  } else {
    $(".drop-file", drop).textContent = `${file.name} · ${(file.size / 1048576).toFixed(1)} MB`;
    state[kind] = file;
  }
  $(`.go[data-kind=${kind}]`).disabled = !state[kind] || state.busy;
}

// ---- status line
function dot(on) {
  return `<span class="dot" style="background:${on ? "var(--ok)" : "var(--bad)"}"></span>`;
}

function updateStatusLine() {
  const el = $("#server-status");
  if (!el) return;
  const okMsg = window.I18n ? I18n.t("status.browser_ok") : "Shader packs convert in your browser";
  const noMsg = window.I18n ? I18n.t("status.browser_no") : "Your browser can't run the shader converter (it needs WebAssembly). Try a current Chrome, Edge, Firefox or Safari";
  const modsMsg = window.I18n ? I18n.t("status.mods") : "Mods convert in the desktop app";
  const neverMsg = window.I18n ? I18n.t("status.never_uploaded") : "your files are never uploaded.";

  el.innerHTML = (BROWSER_OK
    ? dot(true) + okMsg
    : dot(false) + noMsg)
    + " &nbsp;·&nbsp; " + dot(true) + modsMsg + " &nbsp;·&nbsp; " + neverMsg;
}
updateStatusLine();

// ---- converting
for (const btn of document.querySelectorAll(".go[data-kind]")) {
  btn.addEventListener("click", () => convert(btn.dataset.kind));
}

async function convert(kind) {
  const file = state[kind];
  if (!file || state.busy) return;
  setBusy(true);
  $("#result").hidden = true;
  $("#working").hidden = false;
  $("#working-title").textContent = window.I18n ? I18n.t("working.converting", file.name) : `Converting ${file.name}…`;
  state.step = window.I18n ? I18n.t("working.starting") : "Starting the shader converter.";
  const started = Date.now();
  const show = () => { $("#working-sub").textContent = `${state.step} (${Math.round((Date.now() - started) / 1000)}s)`; };
  show();
  const tick = setInterval(show, 1000);
  let data;
  try {
    data = await convertShaderInBrowser(file);
  } catch (e) {
    const errText = window.I18n
      ? I18n.t("error.stopped", (e && e.message ? e.message : e))
      : ("The converter stopped: " + (e && e.message ? e.message : e));
    data = { ok: false, status: "failed", error: errText };
  } finally {
    clearInterval(tick);
    $("#working").hidden = true;
    setBusy(false);
  }
  showResult(kind, file.name, data);
}

function safeName(name, ending) {
  let n = name.replace(/[^A-Za-z0-9._()+\- ]/g, "_");
  if (!n.toLowerCase().endsWith(ending)) n += ending;
  return n;
}

let lastBlobUrl = null;
function blobLink(blob) {
  if (lastBlobUrl) URL.revokeObjectURL(lastBlobUrl);
  lastBlobUrl = URL.createObjectURL(blob);
  return lastBlobUrl;
}

// ---- shader packs: converted in this browser by shader-worker.js
function convertShaderInBrowser(file) {
  const name = safeName(file.name, ".zip");
  const stem = name.replace(/\.zip$/i, "");
  if (!BROWSER_OK) {
    const noWasm = window.I18n
      ? I18n.t("error.no_wasm")
      : "Your browser can't run the converter (it needs WebAssembly). Try a current Chrome, Edge, Firefox or Safari.";
    return Promise.resolve({ ok: false, error: noWasm });
  }
  return new Promise(async (resolve) => {
    const bytes = await file.arrayBuffer();
    const worker = new Worker("shader-worker.js", { type: "module" });
    worker.onerror = (e) => {
      worker.terminate();
      const err = window.I18n
        ? I18n.t("error.worker", e.message || "unknown error")
        : ("The converter couldn't start: " + (e.message || "unknown error"));
      resolve({ ok: false, error: err });
    };
    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "log") { state.step = m.line.trim().slice(0, 140); return; }
      worker.terminate();
      const zip = m.files[stem + "-horizon.zip"];
      const report = m.files[stem + "-horizon.md"];
      const lines = m.log.split(/\r?\n/);
      if (m.code !== 0 || !zip) {
        const failedMsg = window.I18n ? I18n.t("error.pack_failed") : "This pack couldn't be converted (see the log).";
        resolve({ ok: false, status: "failed", error: failedMsg, log: m.log });
        return;
      }
      resolve({
        ok: true, status: "converted", log: m.log,
        summary: (lines.find((l) => l.includes("after:")) || "").trim(),
        before: (lines.find((l) => l.includes("before:")) || "").trim(),
        report: report ? new TextDecoder().decode(report) : "",
        download: blobLink(new Blob([zip], { type: "application/zip" })), fileName: stem + "-horizon.zip",
      });
    };
    worker.postMessage({ name, bytes }, [bytes]);
  });
}

function setBusy(b) {
  state.busy = b;
  for (const btn of document.querySelectorAll(".go[data-kind]")) btn.disabled = b || !state[btn.dataset.kind];
}

function showResult(kind, name, d) {
  state.lastResult = { kind, name, d };
  renderResult();
  $("#result").hidden = false;
  $("#result").scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderResult() {
  if (!state.lastResult) return;
  const { kind, name, d } = state.lastResult;
  const badge = $("#result-badge");
  const status = d.status || (d.ok ? "converted" : "failed");
  badge.className = "badge " + (d.ok ? "ok" : status === "refused" ? "warn" : "bad");

  const badgeLabels = {
    converted: window.I18n ? I18n.t("badge.converted") : "Converted",
    refused: window.I18n ? I18n.t("badge.refused") : "Licence",
    timeout: window.I18n ? I18n.t("badge.timeout") : "Timed out",
    failed: window.I18n ? I18n.t("badge.failed") : "Failed"
  };
  badge.textContent = badgeLabels[status] || status;
  $("#result-title").textContent = name;
  $("#result-summary").textContent = d.ok
    ? (d.summary || (window.I18n ? I18n.t("result.done") : "Done."))
    : (d.error || (window.I18n ? I18n.t("result.error") : "Something went wrong."));

  const dl = $("#result-download");
  dl.hidden = !d.download;
  if (d.download) {
    dl.href = d.download;
    dl.setAttribute("download", d.fileName || "");
    const fn = d.fileName || (window.I18n ? I18n.t("result.download") : "result");
    dl.textContent = window.I18n ? I18n.t("result.download_file", fn) : ("Download " + fn);
  }

  $("#result-issue").href = issueLink(kind, name, d);

  const next = $("#result-next");
  next.hidden = !d.ok;
  if (d.ok) {
    if (kind === "shader") {
      const p1 = window.I18n
        ? I18n.t("result.next.shader")
        : "<strong>Next:</strong> put the zip in Horizon's <code>shaderpacks</code> folder (<code>%APPDATA%\\horizon\\shaderpacks</code> on Windows) and pick it in Options → Horizon Settings → Shader Packs.";
      next.innerHTML = `<p>${p1}</p><p>${escapeHtml(d.before || "")}<br>${escapeHtml(d.summary || "")}</p>`;
    } else {
      const p1 = window.I18n
        ? I18n.t("result.next.mod.1")
        : "<strong>Next:</strong> unzip it, read <code>PORTING.md</code>, then build it into the game with <code>horizon-launcher native-build &lt;folder&gt;/mixins/&lt;modid&gt; --trust-native-code</code>.";
      const p2 = window.I18n
        ? I18n.t("result.next.mod.2")
        : "Native mods run with full access to your computer: only build code you trust.";
      next.innerHTML = `<p>${p1}</p><p>${p2}</p>`;
    }
  }

  $("#report-box").hidden = !d.report;
  $("#report").innerHTML = renderMarkdown(d.report || "");
  $("#translation-box").hidden = !d.translation;
  $("#translation").innerHTML = renderMarkdown(d.translation || "");
  $("#log").textContent = d.log || (window.I18n ? I18n.t("result.no_log") : "(no log)");
}

$("#result-again").addEventListener("click", () => {
  $("#result").hidden = true;
  state.lastResult = null;
  const drop = $(`.drop[data-kind=${state.kind}]`);
  $("input[type=file]", drop).value = "";
  $(".drop-file", drop).textContent = "";
  state[state.kind] = null;
  setBusy(false);
  document.getElementById("convert").scrollIntoView({ behavior: "smooth" });
});

// ---- Support me: addresses from donate.json (the Windows app reads the same file)
const WALLET_FORMATS = {
  bitcoin: /^(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/,
  evm: /^0x[0-9a-fA-F]{40}$/,
  solana: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/,
  litecoin: /^(ltc1[02-9ac-hj-np-z]{11,71}|[LM3][1-9A-HJ-NP-Za-km-z]{25,34})$/,
  monero: /^[48][1-9A-HJ-NP-Za-km-z]{94}([1-9A-HJ-NP-Za-km-z]{11})?$/,
};

function walletUsable(w) {
  const a = (w.address || "").trim();
  if (!a || /\s/.test(a)) return false;
  const f = WALLET_FORMATS[(w.type || "").toLowerCase()];
  return !f || f.test(a);
}

function pageLoaded() {
  return new Promise((r) => (document.readyState === "complete" ? r() : window.addEventListener("load", r, { once: true })));
}

async function showSupport() {
  if (!state.donateData) {
    try {
      const r = await fetch("donate.json", { cache: "no-cache" });
      if (r.ok) state.donateData = await r.json();
      else state.donateData = {};
    } catch {
      state.donateData = {};
    }
  }
  await pageLoaded(); // QR library loads with defer
  renderSupport();
}
showSupport();

function renderSupport() {
  const d = state.donateData || {};
  const wallets = [];
  for (const w of d.wallets || []) {
    if (!w) continue;
    if (walletUsable(w)) wallets.push(w);
    else if ((w.address || "").trim()) console.warn(`donate.json: "${w.name}" skipped, the address isn't a valid ${w.type} address`);
  }
  const links = (d.links || []).filter((l) => l && l.name && /^https:\/\//.test(l.url || ""));

  const defaultMsg = "Horizon Translator is free and made in my spare time. If it helped you, you can support me with crypto. Thank you!";
  if (!d.message || d.message === defaultMsg) {
    $("#support-message").textContent = window.I18n ? I18n.t("support.default_message") : defaultMsg;
  } else {
    $("#support-message").textContent = d.message;
  }

  if (!wallets.length && !links.length) {
    $("#support-empty").hidden = false;
    return;
  }
  $("#support-empty").hidden = true;

  const walletsContainer = $("#support-wallets");
  walletsContainer.innerHTML = "";
  for (const w of wallets) {
    const address = w.address.trim();
    const card = document.createElement("div");
    card.className = "wallet";
    const title = document.createElement("h3");
    title.textContent = w.coins ? `${w.name} (${w.coins})` : w.name;
    card.append(title);

    let netLabel = w.networks;
    if (window.I18n && w.type && I18n.has("support.network." + w.type.toLowerCase())) {
      netLabel = I18n.t("support.network." + w.type.toLowerCase());
    }
    if (netLabel) {
      const net = document.createElement("p");
      net.className = "networks";
      net.textContent = window.I18n ? I18n.t("support.send_on", netLabel) : ("Send on: " + netLabel);
      card.append(net);
    }

    const qr = document.createElement("div");
    qr.className = "qr-box";
    if (typeof QRCode === "undefined") {
      qr.textContent = window.I18n ? I18n.t("support.qr_error") : "The QR code couldn't load. Use Copy instead.";
    } else {
      new QRCode(qr, { text: address, width: 180, height: 180, colorDark: "#000000", colorLight: "#ffffff", correctLevel: QRCode.CorrectLevel.M });
    }
    qr.title = window.I18n ? I18n.t("support.qr_title", w.name) : (w.name + " address");

    const code = document.createElement("code");
    code.textContent = address;

    const copy = document.createElement("button");
    copy.className = "ghost small";
    const copyLabel = window.I18n ? I18n.t("support.copy") : "Copy address";
    copy.textContent = copyLabel;
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(address);
        copy.textContent = window.I18n ? I18n.t("support.copied") : "Copied";
      } catch {
        copy.textContent = window.I18n ? I18n.t("support.copy_select") : "Select it and copy";
      }
      setTimeout(() => {
        copy.textContent = window.I18n ? I18n.t("support.copy") : "Copy address";
      }, 2000);
    });

    card.append(qr, code, copy);
    walletsContainer.append(card);
  }
  $("#support-warning").hidden = !wallets.length;

  const linksContainer = $("#support-links");
  linksContainer.innerHTML = "";
  for (const l of links) {
    const a = document.createElement("a");
    a.className = "ghost small";
    a.href = l.url;
    a.rel = "noopener";
    a.target = "_blank";
    a.textContent = l.name;
    linksContainer.append(a);
  }
}

function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// React to language changes
if (window.I18n) {
  I18n.onChange(() => {
    bindRepoLinks();
    updateStatusLine();
    updateVerifyLive();
    if (modsLoaded) renderMods();
    if (guideLoaded) loadGuide(true);
    if (state.lastResult) renderResult();
    renderSupport();
  });
}
