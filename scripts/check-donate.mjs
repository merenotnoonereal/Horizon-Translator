// Checks web/public/donate.json before you publish: prints what the website and the app will show,
// and anything they'll skip because an address is in the wrong format.
//   node scripts/check-donate.mjs
// It only checks the format. It can't tell whether an address is really yours: compare it
// character by character with the Receive screen of your wallet.
import { readFileSync } from "node:fs";

const FORMATS = { // same as web/public/app.js and desktop/src/horizonport/Support.java
  bitcoin: /^(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/,
  evm: /^0x[0-9a-fA-F]{40}$/,
  solana: /^[1-9A-HJ-NP-Za-km-z]{32,44}$/,
  litecoin: /^(ltc1[02-9ac-hj-np-z]{11,71}|[LM3][1-9A-HJ-NP-Za-km-z]{25,34})$/,
  monero: /^[48][1-9A-HJ-NP-Za-km-z]{94}([1-9A-HJ-NP-Za-km-z]{11})?$/,
};

const file = new URL("../web/public/donate.json", import.meta.url);
let d;
try {
  d = JSON.parse(readFileSync(file, "utf8"));
} catch (e) {
  console.error("donate.json isn't valid JSON: " + e.message + "\n(Check for a missing comma or quote.)");
  process.exit(1);
}

let problems = 0, shown = 0;
for (const w of d.wallets || []) {
  const a = (w.address || "").trim();
  if (!a) { console.log(`  -  ${w.name}: empty, hidden`); continue; }
  const f = FORMATS[(w.type || "").toLowerCase()];
  if (/\s/.test(a) || (f && !f.test(a))) {
    console.log(`  X  ${w.name}: "${a}" isn't a valid ${w.type} address, so it will be hidden`);
    problems++;
  } else {
    console.log(`  OK ${w.name}: ${a}${f ? "" : `  (no format check for type "${w.type}")`}`);
    shown++;
  }
  if (w.address !== a) console.log(`     note: remove the spaces around this address`);
}
for (const l of d.links || []) {
  if (!l.url) continue;
  if (/^https:\/\//.test(l.url)) { console.log(`  OK ${l.name}: ${l.url}`); shown++; }
  else { console.log(`  X  ${l.name}: links must start with https://`); problems++; }
}
console.log(shown ? `\n${shown} shown on the website and in the app.` : "\nNothing filled in: both show \"Donation addresses haven't been added yet\".");
process.exit(problems ? 1 : 0);
