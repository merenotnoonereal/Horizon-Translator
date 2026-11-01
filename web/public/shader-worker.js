// Runs the shader converter (horizon-shaderport compiled to WebAssembly) inside the visitor's browser.
// The pack never leaves their computer. Runs in a Web Worker so the page stays responsive.
import { WASI, File, OpenFile, ConsoleStdout, PreopenDirectory, Directory } from "https://cdn.jsdelivr.net/npm/@bjorn3/browser_wasi_shim@0.3.0/+esm";

let modulePromise = null;
function loadModule() {
  if (!modulePromise) modulePromise = WebAssembly.compileStreaming(fetch("horizon-shaderport.wasm"));
  return modulePromise;
}

self.onmessage = async (e) => {
  const { name, bytes } = e.data;
  const log = [];
  const say = (line) => { log.push(line); self.postMessage({ type: "log", line }); };
  try {
    const module = await loadModule();
    const work = new PreopenDirectory("/work", new Map([[name, new File(new Uint8Array(bytes))]]));
    const fds = [new OpenFile(new File([])), ConsoleStdout.lineBuffered(say), ConsoleStdout.lineBuffered(say), work];
    const wasi = new WASI(["horizon-shaderport", "port", "/work/" + name, "--out", "/work/out"], [], fds, { debug: false });
    const instance = await WebAssembly.instantiate(module, { wasi_snapshot_preview1: wasi.wasiImport });
    let code;
    try {
      code = wasi.start(instance);
    } catch (err) {
      say("The converter stopped unexpectedly: " + (err && err.message ? err.message : err));
      code = -1;
    }
    const out = work.dir.contents.get("out");
    const files = {};
    if (out instanceof Directory) {
      for (const [file, node] of out.contents) if (node instanceof File) files[file] = node.data;
    }
    self.postMessage({ type: "done", code, log: log.join("\n"), files });
  } catch (err) {
    self.postMessage({ type: "done", code: -1, log: log.concat(["Couldn't start the converter: " + err.message]).join("\n"), files: {} });
  }
};
