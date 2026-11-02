// Horizon Translator website: converts shader packs in the browser, links to the Windows app.
// The project's GitHub repository (issue links and the app download).
const REPO_URL = "https://github.com/merenotnoonereal/Horizon-Translator";

const $ = (s, root = document) => root.querySelector(s);
const state = { mod: null, shader: null, kind: "mod", busy: false, step: "" };
// Both converters run in the visitor's own browser (WebAssembly): files are never uploaded anywhere.
const BROWSER_OK = typeof WebAssembly === "object" && typeof Worker === "function";

document.getElementById("repo-link").href = REPO_URL;
for (const a of document.querySelectorAll(".repo-link")) a.href = REPO_URL;
for (const a of document.querySelectorAll(".repo-issues")) a.href = REPO_URL + "/issues/new/choose";
for (const a of document.querySelectorAll(".repo-ideas")) a.href = REPO_URL + "/issues/new?template=feature_request.md";
// The Windows app: the newest HorizonTranslator-Setup.exe attached to a GitHub Release.
const APP_DOWNLOAD = REPO_URL + "/releases/latest/download/HorizonTranslator-Setup.exe";
for (const a of document.querySelectorAll("#download-app")) a.href = APP_DOWNLOAD;
// The Linux app: the newest HorizonTranslator-linux-x64.tar.gz on the same release.
for (const a of document.querySelectorAll("#download-linux")) a.href = REPO_URL + "/releases/latest/download/HorizonTranslator-linux-x64.tar.gz";
for (const a of document.querySelectorAll(".repo-releases")) a.href = REPO_URL + "/releases";
for (const a of document.querySelectorAll(".open-verify")) a.addEventListener("click", () => { $("#verify-app").open = true; });

// "Check the file yourself": show the SHA-256 that GitHub itself computed for the latest installer,
// plus its VirusTotal page. If GitHub can't be reached, the static instructions still work.
(async () => {
  try {
    const api = REPO_URL.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest";
    const res = await fetch(api, { headers: { Accept: "application/vnd.github+json" } });
    if (!res.ok) return;
    const rel = await res.json();
    const asset = (rel.assets || []).find(a => a.name === "HorizonTranslator-Setup.exe");
    const m = /^sha256:([0-9a-f]{64})$/.exec(asset?.digest || "");
    if (!m) return;
    const box = $("#verify-app");
    $(".verify-tag", box).textContent = rel.tag_name || "latest";
    $(".verify-hash", box).textContent = m[1].toUpperCase();
    $(".verify-vt-link", box).href = "https://www.virustotal.com/gui/file/" + m[1];
    $(".verify-live", box).hidden = false;
    $(".verify-vt", box).hidden = false;
  } catch {}
})();

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
  tab.addEventListener("click", () => selectTab(tab.id === "tab-mod" ? "mod" : "shader"));
}
function selectTab(kind) {
  state.kind = kind;
  for (const k of ["mod", "shader"]) {
    const on = k === kind;
    $("#tab-" + k).classList.toggle("active", on);
    $("#tab-" + k).setAttribute("aria-selected", on);
    $("#panel-" + k).hidden = !on;
  }
}

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
    $(".drop-file", drop).textContent = `That's not a ${ending} file.`;
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
$("#server-status").innerHTML = (BROWSER_OK
  ? dot(true) + "Shader packs convert in your browser"
  : dot(false) + "Your browser can't run the shader converter (it needs WebAssembly). Try a current Chrome, Edge, Firefox or Safari")
  + " &nbsp;·&nbsp; " + dot(true) + "Mods convert in the desktop app &nbsp;·&nbsp; your files are never uploaded.";

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
  $("#working-title").textContent = `Converting ${file.name}…`;
  state.step = "Starting the shader converter.";
  const started = Date.now();
  const show = () => { $("#working-sub").textContent = `${state.step} (${Math.round((Date.now() - started) / 1000)}s)`; };
  show();
  const tick = setInterval(show, 1000);
  let data;
  try {
    data = await convertShaderInBrowser(file);
  } catch (e) {
    data = { ok: false, status: "failed", error: "The converter stopped: " + (e && e.message ? e.message : e) };
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
  if (!BROWSER_OK) return Promise.resolve({ ok: false, error: "Your browser can't run the converter (it needs WebAssembly). Try a current Chrome, Edge, Firefox or Safari." });
  return new Promise(async (resolve) => {
    const bytes = await file.arrayBuffer();
    const worker = new Worker("shader-worker.js", { type: "module" });
    worker.onerror = (e) => { worker.terminate(); resolve({ ok: false, error: "The converter couldn't start: " + (e.message || "unknown error") }); };
    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "log") { state.step = m.line.trim().slice(0, 140); return; }
      worker.terminate();
      const zip = m.files[stem + "-horizon.zip"];
      const report = m.files[stem + "-horizon.md"];
      const lines = m.log.split(/\r?\n/);
      if (m.code !== 0 || !zip) {
        resolve({ ok: false, status: "failed", error: "This pack couldn't be converted (see the log).", log: m.log });
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
  const badge = $("#result-badge");
  const status = d.status || (d.ok ? "converted" : "failed");
  badge.className = "badge " + (d.ok ? "ok" : status === "refused" ? "warn" : "bad");
  badge.textContent = { converted: "Converted", refused: "Licence", timeout: "Timed out", failed: "Failed" }[status] || status;
  $("#result-title").textContent = name;
  $("#result-summary").textContent = d.ok ? (d.summary || "Done.") : (d.error || "Something went wrong.");

  const dl = $("#result-download");
  dl.hidden = !d.download;
  if (d.download) {
    dl.href = d.download;
    dl.setAttribute("download", d.fileName || "");
    dl.textContent = "Download " + (d.fileName || "result");
  }

  $("#result-issue").href = issueLink(kind, name, d);

  const next = $("#result-next");
  next.hidden = !d.ok;
  next.innerHTML = kind === "shader"
    ? `<p><strong>Next:</strong> put the zip in Horizon's <code>shaderpacks</code> folder (<code>%APPDATA%\\horizon\\shaderpacks</code> on Windows) and pick it in Options → Horizon Settings → Shader Packs.</p>
       <p>${escapeHtml(d.before || "")}<br>${escapeHtml(d.summary || "")}</p>`
    : `<p><strong>Next:</strong> unzip it, read <code>PORTING.md</code>, then build it into the game with
       <code>horizon-launcher native-build &lt;folder&gt;/mixins/&lt;modid&gt; --trust-native-code</code>.</p>
       <p>Native mods run with full access to your computer: only build code you trust.</p>`;

  $("#report-box").hidden = !d.report;
  $("#report").innerHTML = renderMarkdown(d.report || "");
  $("#translation-box").hidden = !d.translation;
  $("#translation").innerHTML = renderMarkdown(d.translation || "");
  $("#log").textContent = d.log || "(no log)";

  $("#result").hidden = false;
  $("#result").scrollIntoView({ behavior: "smooth", block: "start" });
}

$("#result-again").addEventListener("click", () => {
  $("#result").hidden = true;
  const drop = $(`.drop[data-kind=${state.kind}]`);
  $("input[type=file]", drop).value = "";
  $(".drop-file", drop).textContent = "";
  state[state.kind] = null;
  setBusy(false);
  document.getElementById("convert").scrollIntoView({ behavior: "smooth" });
});

// ---- Support me: addresses from donate.json (the Windows app reads the same file)
// Same format checks as the app (desktop/src/horizonport/Support.java) and scripts/check-donate.mjs.
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

(async function showSupport() {
  let d = {};
  try {
    const r = await fetch("donate.json", { cache: "no-cache" });
    if (r.ok) d = await r.json();
  } catch { /* shows the "not added yet" line */ }
  const wallets = [];
  for (const w of d.wallets || []) {
    if (!w) continue;
    if (walletUsable(w)) wallets.push(w);
    else if ((w.address || "").trim()) console.warn(`donate.json: "${w.name}" skipped, the address isn't a valid ${w.type} address`);
  }
  const links = (d.links || []).filter((l) => l && l.name && /^https:\/\//.test(l.url || ""));
  $("#support-message").textContent = d.message || "";
  if (!wallets.length && !links.length) { $("#support-empty").hidden = false; return; }

  await pageLoaded(); // the QR library loads with defer
  for (const w of wallets) {
    const address = w.address.trim();
    const card = document.createElement("div");
    card.className = "wallet";
    const title = document.createElement("h3");
    title.textContent = w.coins ? `${w.name} (${w.coins})` : w.name;
    card.append(title);
    if (w.networks) {
      const net = document.createElement("p");
      net.className = "networks";
      net.textContent = "Send on: " + w.networks;
      card.append(net);
    }
    const qr = document.createElement("div");
    qr.className = "qr-box";
    if (typeof QRCode === "undefined") qr.textContent = "The QR code couldn't load. Use Copy instead.";
    else new QRCode(qr, { text: address, width: 180, height: 180, colorDark: "#000000", colorLight: "#ffffff", correctLevel: QRCode.CorrectLevel.M });
    qr.title = w.name + " address";
    const code = document.createElement("code");
    code.textContent = address;
    const copy = document.createElement("button");
    copy.className = "ghost small";
    copy.textContent = "Copy address";
    copy.addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(address); copy.textContent = "Copied"; }
      catch { copy.textContent = "Select it and copy"; }
      setTimeout(() => (copy.textContent = "Copy address"), 2000);
    });
    card.append(qr, code, copy);
    $("#support-wallets").append(card);
  }
  $("#support-warning").hidden = !wallets.length;
  for (const l of links) {
    const a = document.createElement("a");
    a.className = "ghost small";
    a.href = l.url;
    a.rel = "noopener";
    a.target = "_blank";
    a.textContent = l.name;
    $("#support-links").append(a);
  }
})();

function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
