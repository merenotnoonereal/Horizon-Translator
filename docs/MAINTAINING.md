# Maintaining Horizon Translator

Notes for whoever runs this repository: publishing the site, releasing the app and editing the donation
addresses. Users don't need any of this; see the [README](../README.md).

## Publishing the website and the app download

**The website (free, on GitHub Pages):**

1. Push the repo to GitHub.
2. In the repository, open *Settings → Pages* and set *Source* to **GitHub Actions**.
3. The workflow `.github/workflows/pages.yml` publishes `web/public` on every push to `main`. The site
   appears at <https://merenotnoonereal.github.io/Horizon-Translator/>.

**The app download (GitHub Releases):**

1. Pick the new version number and build `HorizonTranslator-Setup.exe` with it:
   `APP_VERSION=1.0.3 bash scripts/build-desktop.sh` (the default is in the script).
   For Linux, on a Linux PC or in WSL (Ubuntu: `apt install openjdk-25-jdk`, plus Rust from rustup.rs), run
   `APP_VERSION=1.0.3 bash scripts/build-linux.sh`. It writes `dist/HorizonTranslator-linux-x64.tar.gz`.
2. On GitHub, open *Releases → Draft a new release*, make a tag with **the same number** (`1.0.3` or `v1.0.3`),
   and attach `HorizonTranslator-Setup.exe` and `HorizonTranslator-linux-x64.tar.gz` with exactly those names.
   The website's Linux button links to the second one.
3. Publish. The website's **Download for Windows** button always points to the newest release:
   <https://github.com/merenotnoonereal/Horizon-Translator/releases/latest/download/HorizonTranslator-Setup.exe>.

**Update notices:** when the app starts, it asks GitHub for the latest release (`Updates.java`). If that release's
tag is a higher number than the app's own version, the app shows a green "A newer version is out" bar with a
button to the release page. It never downloads or runs anything itself. So the tag must be a plain version
number, and it must be higher than the one before: a release tagged `1.0.2` with an app built as `1.0.1` would tell
everyone, its own users included, to update forever. Only apps built with this feature (1.0.2 and later) show the bar.

If your GitHub name or repo name differ from `merenotnoonereal/Horizon-Translator`, change `REPO_URL` at the
top of `web/public/app.js`, the address in `web/public/terms.html`, `.github/ISSUE_TEMPLATE/config.yml`,
`desktop/installer.iss`, `desktop/installer-notice.txt` and `REPO_URL` and `SITE_URL` in `desktop/src/horizonport/TranslatorApp.java`.

**About the unsigned installer:** Windows shows a SmartScreen warning for new, unsigned programs. It goes
away once enough people have downloaded the file, or for good with a code-signing certificate (paid,
roughly $100–400 a year). Publish SHA-256 checksums with each release so people can check their download:
`certutil -hashfile HorizonTranslator-Setup.exe SHA256`.

## Donation addresses (Support me)

The website and the app both have a **Support me** section showing each crypto address with a QR code.
Both read the same file, [`web/public/donate.json`](web/public/donate.json). Changing it on GitHub updates
the website straight away; **the app only gets new addresses with a new installer**, because it uses the
copy built into it. The app deliberately never downloads addresses and never writes to the clipboard
(people select the address and press Ctrl+C): that combination is what "clipper" malware does, and it makes
antivirus flag the app.

**Which addresses?** You don't need one per coin:

| Entry | Address looks like | Where people can send from | Covers |
|---|---|---|---|
| Bitcoin (`"type": "bitcoin"`) | `bc1q…` (or starts with `1` / `3`) | Bitcoin network only | BTC |
| Ethereum & EVM (`"type": "evm"`) | `0x` + 40 characters | Ethereum, Base, Arbitrum, Optimism, Polygon, BNB Chain… | ETH and tokens such as USDC, USDT on those networks |
| Solana (`"type": "solana"`) | 32–44 letters and numbers | Solana only | SOL, USDC on Solana |

One EVM address (`0x…`) works on every EVM network, because it's the same account on all of them. That's why
it's one entry. Bitcoin and Solana addresses are separate, because those are different blockchains.
Bitcoin + EVM covers most donors; Solana is optional. Any entry left as `""` is hidden; if all are empty, both
show "Donation addresses haven't been added yet". Types `litecoin` and `monero` are checked too, and you can
add more entries.

**Getting the addresses:** open your wallet app (for example MetaMask, Phantom, Exodus, Trust Wallet or
Coinbase Wallet), pick the coin, press **Receive** and copy the address. Paste it, don't type it.

- Only ever share the **receive address**. Never put your seed phrase / recovery words or private key anywhere.
- Prefer a wallet you control over an exchange deposit address (Coinbase, Binance…). Exchanges often only
  credit some networks, and coins sent on another one can be lost. If you use one, list only the networks
  it accepts in `"networks"`.
- Addresses are public: anyone can look up what an address has received. A separate wallet just for
  donations keeps your other funds private.

Then check the file and publish it:

```bash
node scripts/check-donate.mjs   # shows what will appear, flags mistyped addresses
```

The check only looks at the format. Before publishing, compare each address character by character with
your wallet's Receive screen. Crypto sent to a wrong address can't be got back.
