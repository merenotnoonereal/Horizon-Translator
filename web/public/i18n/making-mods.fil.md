# Paggawa ng mga Horizon mod

Ang tab na **Gumawa ng mod** ng Horizon Translator ay gumagawa ng mga mod para sa [Horizon](https://github.com/horizon-rust/horizon), ang Rust rewrite ng Minecraft Java. Maaari kang gumawa ng mod nang hindi sumusulat ng anumang code, at maaari mong buksan ang code nito sa ibang pagkakataon upang magpatuloy pa.

- **Walang code:** lagyan ng tsek kung ano ang ginagawa ng iyong mod, punan ang mga kahon, pindutin ang **Gumawa ng mod**. Makakakuha ka ng handang `.hnmod` file.
- **May code:** bawat mod ay may kasamang Rust source, test setup at check button. Gamitin ito bilang starter kit.
- **Patuloy na gumagana:** ang mga kit mod ay gumagamit lamang ng mga bahagi ng Horizon na pinananatiling matatag ng team nito para sa mga mod (ang mga "hook point"), kaya binubuo rin ng launcher ang mga ito sa mga mas bagong bersyon ng Horizon. Hindi mo kailangan ng bagong bersyon para sa bawat pag-update ng Horizon.
- **Tumatanggap ng mga update:** sinusuri ng app ang GitHub para sa bagong bersyon kapag nagsimula ito at sinasabi sa iyo. Ang mga bagong bersyon ay nagdadala ng mga bagong feature sa builder at sa gabay.

## Mabilis na pagsisimula

1. Kunin ang Horizon Translator para sa Windows o Linux mula sa website (o sa pahina ng GitHub Releases) at buksan ito.
2. Buksan ang tab na **Gumawa ng mod**. Pumili ng halimbawa sa ilalim ng **Magsimula sa** at pindutin ang **Gamitin**, o magsimula sa blangkong mod.
3. Bigyan ng **pangalan** ang iyong mod. Ang **mod id** ay awtomatikong napupunan: panatilihin itong pareho para sa bawat bagong bersyon ng parehong mod.
4. Lagyan ng tsek ang mga bahaging gusto mo (HUD box, chat commands, keys, zoom, mga mensahe sa chat) at punan ang mga ito.
5. Iwanang may tsek ang **Idagdag ito sa Horizon** at pindutin ang **Gumawa ng mod**.
6. Sa pahina ng **Mods** ng Horizon launcher: **Build** sa ilalim ng "Native build of Horizon" (kung wala ito roon, i-on muna ang **Settings > Game > Native mods**). Ang unang pagbuo ay nagda-download ng Rust at tumatagal ng 20 hanggang 30 minuto; ang mga susunod na pagbuo ay tumatagal lamang ng ilang minuto.
7. Maglaro. I-type ang `/<iyong mod id>` sa chat para makita ang mga command at key ng iyong mod.

Nai-save ang iyong mod sa `Documents/Horizon Translator/<mod id>/` (baguhin ang folder sa ibaba ng app).

## Ano ang maaari mong gawin nang walang code

### HUD box

Teksto sa isang sulok ng screen, tulad ng iyong coordinates, ang direksyong kinakaharap mo o ang iyong FPS. Maaaring pagsamahin ng bawat linya ang plain text sa mga placeholder (sa ibaba). Nagtatago ang kahon sa ilalim ng F1 at umiiwas sa screen ng F3. Pumili ng kulay ng teksto, madilim na kahon sa likod nito o wala, at isang key upang ipakita o itago ito. Maaari rin itong i-off ng mga manlalaro sa **Options > Mods**.

### Mga chat command

Mga command na tina-type ng mga manlalaro sa chat, tulad ng `/hello`. Ang isang command ay maaaring:

- **Magsabi sa chat:** magpakita ng isa o higit pang mga linya (gamitin ang `|` sa pagitan ng mga linya sa kahon).
- **Ipakita / itago ang HUD box.**
- **Mag-zoom in / out.**

Tumatakbo ang mga command sa sariling laro ng manlalaro, bago ito makita ng server, kaya gumagana ang mga ito sa anumang server. Huwag muling gamitin ang pangalan ng command ng server (`/help`, `/home`, `/spawn`...): sasakupin ito ng iyong mod. Palaging idinaragdag ang `/<mod id>`: inililista nito ang iyong mga command at key.

### Mga Key

Mga key na gumagawa ng parehong mga bagay tulad ng mga command. Maaaring baguhin ng mga manlalaro ang bawat key sa **Options > Mods > iyong mod**. Gumagana lamang ang mga key habang naglalaro, hindi habang nagta-type sa chat o kapag may nakabukas na menu.

### Zoom

Isang zoom key tulad ng sa OptiFine: ilang beses na mas malapit (1.5 hanggang 10), habang hawak lamang o pindutin para buksan / pindutin para patayin, makinis tulad ng spyglass, at ang mouse wheel na nagzo-zoom nang mas malapit o mas malayo habang nagzo-zoom. Bumagal ang pag-ikot ng mouse habang naka-zoom.

### Mga mensahe sa chat

- **Mensahe ng pagbati:** mga linyang ipinapakita kapag sumali ka sa isang mundo.
- **Mga nakatakdang mensahe:** mga linyang ipinapakita bawat ilang minuto habang naglalaro ka (binibilang mula sa pagsali sa mundo).

Ipinapakita lamang ang mga mensahe sa iyong screen; walang ipinapadala sa server.

### Mga Placeholder

Maaaring maglaman ang anumang teksto ng mga ito. Napupunan ang mga ito kapag ipinapakita ang teksto.

| Placeholder | Ipinapakita |
|---|---|
| `{x}` `{y}` `{z}` | Ang iyong posisyon, isang decimal: `12.3` |
| `{bx}` `{by}` `{bz}` | Ang block kung nasaan ka: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Kung saan ka nakatingin, sa degrees |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Frames bawat segundo |
| `{name}` `{version}` | Pangalan at bersyon ng iyong mod |

### Mga setting na nakukuha ng mga manlalaro

Bawat kit mod ay may hilera sa **Options > Mods** na may mga key nito at isang ON/OFF button para sa bawat bahagi (HUD box, zoom, mensahe ng pagbati, mga nakatakdang mensahe). Maaari ring i-off ng mga manlalaro ang buong mod doon.

## Paggawa ng bagong bersyon

1. **Magbukas ng mod na ginawa ko...** at piliin ang folder ng iyong mod. Napupunan ang form sa pinili mo noong huling pagkakataon.
2. Baguhin ang gusto mo at itaas ang **bersyon** (1.0.0 → 1.1.0).
3. **Gumawa ng mod**. Mapupunta ang bagong bersyon sa isang bagong folder (`<mod id>-2`...), hindi kailanman papatungan ang iyong luma, at papalitan ang lumang `.hnmod` sa Horizon. Magbuo muli sa launcher.

Panatilihin ang parehong **mod id**: kinikilala ng Horizon ang isang mod sa pamamagitan ng id nito, at mananatili ang mga setting at key ng mga manlalaro.

## Pagbabahagi ng iyong mod

Ipadala ang `.hnmod` file. Ini-install ito ng mga manlalaro mula sa pahina ng Mods ng launcher (o kopyahin ito sa `%APPDATA%\horizon\horizon-native-mods\` sa Windows, `~/.local/share/horizon/horizon-native-mods/` sa Linux) at magbuo.

Ang mga native mod ay kino-compile sa laro at tumatakbo nang may mga karapatan ng manlalaro; hindi sila naka-sandbox. Kaya naman nagtatanong ang launcher bago magbuo. Magbahagi lamang ng mga mod na alam mo ang code, at mag-install lamang ng mga mod mula sa mga taong pinagkakatiwalaan mo.

## Pagpapatuloy pa: ang code

Bawat folder ng mod ay isang maliit na proyekto sa Rust:

| File | Kung ano ito |
|---|---|
| `mixins/<id>/runtime.rs` | Ang mod. Ang mga setting nito sa itaas (mga teksto, key, command), ang logic nito, at mga test sa ibaba |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Ang mga hook: bawat isa ay tumatakbo kapag tinawag ito ng Horizon (isang key, ang HUD, isang command, isang tick) |
| `mixins/<id>/horizon-mixin.toml` | Aling hook point ang nagpapatakbo ng aling file |
| `Cargo.toml` | Hinahayaan ang `cargo test` na patakbuhin ang mga test sa `runtime.rs` nang wala ang laro |
| `compatibility.json` | Ang bersyon ng hook points na kailangan ng mod at ang commit ng Horizon kung saan ito ginawa |
| `horizon-kit.json` | Kung ano ang pinili mo sa builder |
| `<id>.hnmod` | Ang naka-pack na mod |

Upang baguhin ang code:

1. I-edit ang mga file sa anumang editor (ang VS Code na may rust-analyzer extension ay isang magandang pagpipilian).
2. Sa app: **Gumawa ng mod > Suriin at i-pack ang isang folder...**, at piliin ang folder ng iyong mod. Kapag naka-install ang Rust (rustup.rs), kino-compile nito ang iyong code laban sa mga mod helper ng Horizon at pinapatakbo muna ang iyong mga test; nang walang Rust, nag-iimpake ito kaagad at ipinapaalam sa iyo ng build ng launcher ang mga pagkakamali. Pagkatapos ay nag-iimpake ito ng bagong `.hnmod` at idinaragdag ito sa Horizon.
3. Buuin sa launcher at subukan ito.

**Huwag gamitin ang Gumawa ng mod sa isang mod na manu-mano mong binago ang code:** magsisimula itong muli mula sa form. Gamitin ang Suriin at i-pack.

### Mula sa command line (at para sa mga AI agent)

Ginagawa rin ng `horizon-port.jar` ng app ang pareho mula sa terminal, at nagdaragdag ng buong pagsusuri. Bawat folder ng mod ay may `AGENTS.md` na naglalaman ng mga command na ito para sa mga AI coding agent, at `HOOKS.md` na naglilista ng bawat hook point at helper.

| Command | Ano ang ginagawa nito |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Gumagawa ng mod mula sa spec ng builder (ang `kit-examples/` ay may ilan) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Inililista ang mga hook point at helper ng Horizon na iyon, kasama ang `[[injection]]` para sa bawat hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Ang buong pagsusuri: inilalagay ang mod sa isang kopya ng Horizon at kino-compile ito, tulad ng ginagawa ng launcher |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Buong pagsusuri, mga test, pag-pack, pagdagdag sa Horizon |

Ang `SRC` ay ang source ng Horizon, na na-unpack para sa `compile`. Kailangan ng `compile` ang Rust at ang tool na horizon-mixins ng launcher (naka-install sa unang native build nito); ang unang pagtakbo nito ay tumatagal ng ilang GB at ilang minuto. Hindi tulad ng **Suriin at i-pack**, sinusuri rin nito ang code na nagbabasa ng sariling data ng laro (`game.player`, `game.items()`...), na hindi bahagi ng mga hook point.

### Paano gumagana ang isang hook

Tumatawag ang Horizon ng mga walang laman na function sa mga nakapirming lugar (ang mga **hook point**). Bawat linyang `[[injection]]` sa `horizon-mixin.toml` ay naglalagay ng isa sa iyong mga file sa simula ng isa sa mga function na iyon. Naglalaman ang iyong file ng isang function, na tumatanggap ng mga value na nakalista sa ilalim ng `bindings`. Halimbawa, nakukuha ng `key.rs` ang game, ang pangalan ng key at kung ito ay pinindot pababa o pataas:

```rust
fn my_mod_key(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {
    use crate::game::kit_my_mod as m;
    if !game.native_on(m::ID) {
        return;
    }
    if pressed && game.native_playing() && key == game.native_mod_key(m::ID, "jump_note", "key.keyboard.j") {
        game.native_chat("You pressed J");
    }
}
```

Ang `runtime.rs` ay idinaragdag sa laro bilang isang module (`crate::game::kit_<id>`) at ito rin ang sariling library ng proyekto, kaya anumang hindi nangangailangan ng laro (math, teksto, estado ng iyong mod) ay napupunta roon at maaaring subukan gamit ang `cargo test`.

Mga patakarang itinakda ng horizon-mixins: walang mga comment na `//!` o `#![...]` attribute sa `runtime.rs`, walang mga `mod x;` file at walang `include!`. Ilagay ang lahat sa nag-iisang file na iyon (maayos ang inline na `mod x { ... }`).

### Ang mga hook point

Ito ang mga building block ng mga kit mod. Ang mga pangalan at argumento ay nananatiling pareho sa iba't ibang bersyon ng Horizon.

| Hook point | Kailan ito tinatawag ng Horizon | Kung ano ang nakukuha nito |
|---|---|---|
| `native_frame` | Bawat frame, nasa mundo man o wala | `elapsed: Duration` |
| `native_tick` | Bawat tick ng laro (20 bawat segundo), pati sa server ng single player: suriin ang `native_window()` | |
| `native_hud` | Habang iginuguhit ang HUD (wala sa ilalim ng F1) | `batch`, `corners`: maglagay ng kahon gamit ang `corners.place(corner, w, h)` |
| `native_key` | Ang isang key o pindutan ng mouse ay pinindot pababa o pataas (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Mouse wheel habang naglalaro | `steps`, `claimed`: itakda ito upang mapanatili ang gulong mula sa hotbar |
| `native_command` | Isang chat command, nang walang slash nito, bago ito makita ng server | `command`, `ran`: itakda ito kapag ito ay sa iyo |
| `native_zoom` | Bawat frame habang naglalaro | `zoom: &mut Option<NativeZoom>`: itakda ito upang mag-zoom |
| `native_minimap` | Bawat frame | `minimap: &mut Option<NativeMinimap>`: itakda ito upang ipakita ang minimap ng Horizon |
| `native_mods` | Inililista ng Options > Mods ang mga mod | `mods`: i-push ang hilera at mga key ng iyong mod |
| `native_settings` | Bubukas ang pahina ng mga setting ng iyong mod | `mod_id`, `buttons`: i-push ang mga label ng button |
| `native_settings_click` | Isang button sa iyong pahina ng mga setting ang na-click | `mod_id`, `button` |

Bawat hook ay nakakakuha ng `game` kapag hinihingi ito ng `bindings` nito: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`&crate::game::Game<'_>` at `&*self` para sa `native_zoom` at `native_minimap`). Ipinapakita ng mga file na isinusulat ng kit ang `bindings` ng bawat isa.

### Ang mga game helper

Tawagan ang mga ito sa `game` mula sa anumang hook.

| Helper | Ano ang ginagawa nito |
|---|---|
| `native_on(id)` | Kung iniwan ng manlalaro na naka-on ang iyong mod sa Options > Mods. Suriin muna ito sa bawat hook |
| `native_playing()` | Naglalaro, nang walang menu, chat o sign na bukas |
| `native_window()` | Ito ang laro ng window, hindi ng isang server |
| `native_player()` | Ang manlalaro sa isang mundo: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, o `None` |
| `native_frame_rate()` | FPS ngayon, ang pinakamabagal na frame at ang 1% low |
| `native_clicks()` | Mga pag-click sa pag-atake at paggamit sa huling segundo |
| `native_mapping("key.jump")` | Kung pinipigilan ang isang vanilla control, at ang pangalan ng key nito |
| `native_mod_key(id, name, default)` | Ang key na pinili ng manlalaro para sa iyong key `name` |
| `native_chat(text)` | Isang linya sa chat ng manlalaro |
| `native_text(batch, text, [x, y], rgb, shadow)` | Teksto sa HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Isang parihaba sa HUD |
| `native_text_width(text)` | Gaano kalapad ang isang teksto, sa mga pixel ng GUI |
| `native_corner_shown(corner)` | Kung libre ang isang sulok (kinukuha ng F3 ang mga nasa itaas) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Basahin at panatilihin ang mga setting ng iyong mod |
| `native_data_dir(id)` | Sariling folder ng iyong mod, para sa sarili nitong mga file |

Ang mga mas bagong bersyon ng Horizon ay nagdaragdag ng higit pa (isang item panel sa tabi ng mga imbentaryo, mga recipe, pagbibigay ng mga item sa creative). Ang isang mod na gumagamit ng mga iyon ay nangangailangan ng bersyong iyon ng Horizon; napapansin ito ng **Suriin at i-pack** at isinusulat ito sa mod.

### Mga ideyang susubukan

- Isang **CPS counter**: isang linya ng HUD na may `native_clicks()`.
- **Mga Keystroke**: mga kahon na umiilaw habang pinipigilan ang `native_mapping("key.forward")` at ang iba pa.
- Isang paalala sa **mga coordinates ng pagkamatay**: tandaan ang `native_player()` sa bawat tick at sabihin kung nasaan ka noong naging `None` ito.
- Isang **calculator**: `/calc 2+2`, binabasa ang mga numero pagkatapos ng pangalan ng command.
- Isang **session timer**: bilangin ang mga tick sa `native_tick` at ipakita ang oras na nilaro sa HUD box.

## Ano ang hindi pa magagawa ng mga mod

Ibinibigay ng Horizon sa mga native mod ang mga hook point sa itaas, hindi ang buong laro. Hindi pa magagawa ng mga mod ang:

- magdagdag ng mga block, item, mob, recipe o texture (mga content mod ang mga iyon, `.hmod`, na hindi ginagawa ng kit na ito);
- baguhin kung paano gumagana ang mundo o mga mob sa isang server;
- basahin ang mundo sa paligid ng manlalaro (mga block, mob, item) o ang imbentaryo ng manlalaro;
- magpadala ng mga mensahe sa chat o mga command sa server.

Kapag nagdagdag ang Horizon ng mga hook point, magagamit ng kit ang mga ito: dinadala ng mga update ng app ang mga ito sa builder.

## Mga Problema

- **Nabigo ang pagbuo ng launcher:** ipinapakita ng pahina ng Mods nito ang mga huling linya ng error. Patakbuhin ang **Suriin at i-pack** nang naka-install ang Rust upang makita ang buong error kasama ang file at linya. Kopyahin ang error sa isang isyu sa GitHub kung ikaw ay natigil.
- **"Not packed: TODO":** tinatanggihan ng packer ang code na may `TODO` dito (iyon ang paraan kung paano minamarkahan ang mga hindi natapos na na-convert na mod). Tapusin o alisin ang mga linyang iyon.
- **Walang ginagawa ang aking key:** suriin ang **Options > Mods > iyong mod**: maaaring gumamit ng parehong key ang ibang mod o ang Horizon.
- **Napupunta sa halip ang aking command sa server:** ang mga mod id at pangalan ng command ay maliliit na titik; suriin kung inililista ito ng `/<mod id>`.
