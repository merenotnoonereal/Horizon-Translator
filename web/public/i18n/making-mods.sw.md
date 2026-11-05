# Kutengeneza mod za Horizon

Kichupo cha Horizon Translator cha **Tengeneza mod** kinatengeneza mod kwa ajili ya [Horizon](https://github.com/horizon-rust/horizon), uandishi upya wa Minecraft Java katika Rust. Unaweza kutengeneza mod bila kuandika msimbo wowote, na unaweza kufungua msimbo wake baadaye ili kwenda mbali zaidi.

- **Bila msimbo:** weka alama kwenye kile mod yako inachofanya, jaza visanduku, bonyeza **Tengeneza mod**. Unapata faili tayari ya `.hnmod`.
- **Ikiwa na msimbo:** kila mod inakuja na msimbo wake wa chanzo wa Rust, usanidi wa majaribio na kitufe cha kukagua. Itumie kama kifaa cha kuanzia (starter kit).
- **Inaendelea kufanya kazi:** mod za kit hutumia tu sehemu za Horizon ambazo timu yake huziweka imara kwa ajili ya mod ("hook points"), kwa hivyo kianzilishi huijenga kwenye matoleo mapya ya Horizon pia. Huhitaji toleo jipya kwa kila sasisho la Horizon.
- **Inapata masasisho:** programu hukagua GitHub kwa toleo jipya inapoanza na inakujulisha. Matoleo mapya huleta vipengele vipya kwenye mjenzi na mwongozo.

## Mwanzo wa haraka

1. Pata Horizon Translator kwa Windows au Linux kutoka kwenye tovuti (au ukurasa wa GitHub Releases) na uifungue.
2. Fungua kichupo cha **Tengeneza mod**. Chagua mfano chini ya **Anzia kutoka** na ubonyeze **Tumia**, au anza na mod tupu.
3. Ipe mod yako **jina**. **Kitambulisho cha mod** kinajijaza chenyewe: kiweke sawa kwa kila toleo jipya la mod hiyo hiyo.
4. Weka alama kwenye sehemu unazotaka (kisanduku cha HUD, amri za gumzo, vitufe, zoom, jumbe za gumzo) na uzijaze.
5. Acha **Ongeza kwenye Horizon** ikiwa na alama na ubonyeze **Tengeneza mod**.
6. Katika ukurasa wa **Mods** wa kianzilishi cha Horizon: **Build** chini ya "Native build of Horizon" (ikiwa haipo hapo, washa **Settings > Game > Native mods** kwanza). Ujenzi wa kwanza hupakua Rust na huchukua dakika 20 hadi 30; ujenzi wa baadaye huchukua dakika chache tu.
7. Cheza. Andika `/<kitambulisho cha mod yako>` kwenye gumzo ili kuona amri na vitufe vya mod yako.

Mod yako imehifadhiwa katika `Documents/Horizon Translator/<mod id>/` (badilisha folda chini ya programu).

## Unachoweza kutengeneza bila msimbo

### Kisanduku cha HUD

Maandishi katika kona ya skrini, kama vile viwianishi vyako, mwelekeo unaotazama au FPS yako. Kila mstari unaweza kuchanganya maandishi ya kawaida na vishikilia-nafasi (hapa chini). Kisanduku hujificha chini ya F1 na kuondoka ili kupisha skrini ya F3. Chagua rangi ya maandishi, kisanduku cha giza nyuma yake au la, na kitufe cha kuonyesha au kukificha. Wachezaji wanaweza pia kukizima katika **Options > Mods**.

### Amri za gumzo

Amri ambazo wachezaji huandika kwenye gumzo, kama vile `/hello`. Amri inaweza:

- **Kusema kwenye gumzo:** kuonyesha mstari mmoja au zaidi (tumia `|` kati ya mistari kwenye kisanduku).
- **Kuonyesha / kuficha kisanduku cha HUD.**
- **Kuza / kupunguza ukubwa (Zoom in / out).**

Amri huendeshwa kwenye mchezo wa mchezaji mwenyewe, kabla ya seva kuziona, kwa hivyo zinafanya kazi kwenye seva yoyote. Usitumie tena jina la amri ya seva (`/help`, `/home`, `/spawn`...): mod yako itaichukua. `/<mod id>` huongezwa daima: inaorodhesha amri na vitufe vyako.

### Vitufe

Vitufe vinavyofanya mambo sawa na amri. Wachezaji wanaweza kubadilisha kila kitufe katika **Options > Mods > mod yako**. Vitufe hufanya kazi tu wakati wa kucheza, si wakati wa kuandika kwenye gumzo au menyu ikiwa wazi.

### Kuza picha (Zoom)

Kitufe cha kukuza picha kama cha OptiFine: mara ngapi karibu zaidi (1.5 hadi 10), tu kikiwa kimeshikiliwa au bonyeza kuwasha / bonyeza kuzima, laini kama darubini, na gurudumu la kipanya linakuza karibu au mbali zaidi wakati wa kukuza. Mzunguko wa kipanya hupungua kasi ukiwa umekuza.

### Jumbe za gumzo

- **Ujumbe wa kukaribisha:** mistari inayoonyeshwa unapojiunga na ulimwengu.
- **Jumbe za wakati:** mistari inayoonyeshwa kila baada ya dakika kadhaa unapocheza (kuhesabiwa kuanzia kujiunga na ulimwengu).

Jumbe huonyeshwa kwenye skrini yako pekee; hakuna kinachotumwa kwa seva.

### Vishikilia-nafasi (Placeholders)

Maandishi yoyote yanaweza kushikilia hivi. Yanajazwa wakati maandishi yanaonyeshwa.

| Placeholder | Inachoonyesha |
|---|---|
| `{x}` `{y}` `{z}` | Nafasi yako, desimali moja: `12.3` |
| `{bx}` `{by}` `{bz}` | Kitalu ulichopo: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Unapotazama, kwa nyuzi |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Fremu kwa sekunde |
| `{name}` `{version}` | Jina na toleo la mod yako |

### Mipangilio wanayopata wachezaji

Kila mod ya kit ina safu mlalo katika **Options > Mods** ikiwa na vitufe vyake na kitufe cha KUWASHA/KUZIMA kwa kila sehemu (kisanduku cha HUD, zoom, ujumbe wa kukaribisha, jumbe za wakati). Wachezaji wanaweza pia kuzima mod nzima hapo.

## Kutengeneza toleo jipya

1. **Fungua mod niliyoitengeneza...** na uchague folda ya mod yako. Fomu inajazwa na kile ulichochagua mara ya mwisho.
2. Badilisha kile unachotaka na uongeze **toleo** (1.0.0 → 1.1.0).
3. **Tengeneza mod**. Toleo jipya linaenda kwenye folda mpya (`<mod id>-2`...), bila kufuta ya zamani kamwe, na inachukua nafasi ya `.hnmod` ya zamani katika Horizon. Jenga tena kwenye kianzilishi.

Weka **kitambulisho cha mod** sawa: Horizon inatambua mod kwa kitambulisho chake, na mipangilio na vitufe vya wachezaji vinabaki nayo.

## Kushiriki mod yako

Tuma faili ya `.hnmod`. Wachezaji huisakinisha kutoka ukurasa wa Mods wa kianzilishi (au kuinakili kwenye `%APPDATA%\horizon\horizon-native-mods\` kwenye Windows, `~/.local/share/horizon/horizon-native-mods/` kwenye Linux) na kuijenga.

Mod asilia zimekusanywa kwenye mchezo na huendeshwa zikiwa na haki za mchezaji; hazijawekwa kwenye sandbox. Ndiyo maana kianzilishi huuliza kabla ya kujenga. Shiriki tu mod ambazo unajua msimbo wake, na sakinisha tu mod kutoka kwa watu unaowaamini.

## Kwenda mbali zaidi: msimbo

Kila folda ya mod ni mradi mdogo wa Rust:

| Faili | Ni nini |
|---|---|
| `mixins/<id>/runtime.rs` | Mod yenyewe. Mipangilio yake juu (maandishi, vitufe, amri), mantiki yake, na majaribio chini |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Hook: kila moja huendeshwa Horizon inapoita (kitufe, HUD, amri, tick) |
| `mixins/<id>/horizon-mixin.toml` | Ni hook point gani inayoendesha faili ipi |
| `Cargo.toml` | Huruhusu `cargo test` kuendesha majaribio katika `runtime.rs` bila mchezo |
| `compatibility.json` | Toleo la hook points ambalo mod inahitaji na commit ya Horizon ilipotengenezwa |
| `horizon-kit.json` | Ulichochagua katika mjenzi |
| `<id>.hnmod` | Mod iliyofungwa |

Ili kubadilisha msimbo:

1. Hariri faili katika kihariri chochote (VS Code yenye kiendelezi cha rust-analyzer ni chaguo zuri).
2. Katika programu: **Tengeneza mod > Kagua na ufunge folda...**, na uchague folda ya mod yako. Rust ikiwa imesakinishwa (rustup.rs), inakusanya msimbo wako dhidi ya wasaidizi wa mod wa Horizon na kuendesha majaribio yako kwanza; bila Rust inafunga mara moja na ujenzi wa kianzilishi unakuambia kuhusu makosa. Kisha inafunga `.hnmod` mpya na kuiongeza kwenye Horizon.
3. Jenga kwenye kianzilishi na uijaribu.

**Usitumie Tengeneza mod kwenye mod ambayo ulibadilisha msimbo wake kwa mkono:** inaanza tena kutoka kwenye fomu. Tumia Kagua na ufunge.

### Kutoka mstari wa amri (na kwa maajenti wa AI)

Faili ya programu ya `horizon-port.jar` inafanya vivyo hivyo kutoka kwa terminal, na kuongeza ukaguzi kamili. Kila folda ya mod ina `AGENTS.md` yenye amri hizi kwa maajenti wa uandishi wa msimbo wa AI, na `HOOKS.md` inayoorodhesha kila hook point na msaidizi.

| Amri | Inachofanya |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Hutengeneza mod kutoka kwa spec ya mjenzi (`kit-examples/` ina mifano) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Huorodhesha hook points na wasaidizi wa Horizon hiyo, ikiwa na `[[injection]]` kwa kila hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Ukaguzi kamili: huweka mod kwenye nakala ya Horizon na kuikusanya, kama kianzilishi kinavyofanya |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Ukaguzi kamili, majaribio, kufunga, na kuongeza kwenye Horizon |

`SRC` ni chanzo cha Horizon, kilichofunguliwa kwa ajili ya `compile`. `compile` inahitaji Rust na zana ya kianzilishi ya horizon-mixins (iliyosakinishwa na ujenzi wake wa kwanza wa asili); uendeshaji wake wa kwanza huchukua GB kadhaa na dakika kadhaa. Tofauti na **Kagua na ufunge**, pia hukagua msimbo unaosoma data ya mchezo wenyewe (`game.player`, `game.items()`...), ambayo si sehemu ya hook points.

### Jinsi hook inavyofanya kazi

Horizon huita kazi tupu katika maeneo yasiyobadilika (hizo **hook points**). Kila mstari `[[injection]]` katika `horizon-mixin.toml` huweka mojawapo ya faili zako mwanzoni mwa mojawapo ya kazi hizo. Faili yako inashikilia kazi moja, inayopata maadili yaliyoorodheshwa chini ya `bindings`. Kwa mfano `key.rs` inapata mchezo, jina la kitufe na ikiwa kilibonyezwa chini au juu:

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

`runtime.rs` huongezwa kwenye mchezo kama moduli (`crate::game::kit_<id>`) na pia ni maktaba ya mradi wenyewe, kwa hivyo chochote kisichohitaji mchezo (hisabati, maandishi, hali ya mod yako) huenda huko na kinaweza kujaribiwa kwa `cargo test`.

Sheria zilizowekwa na horizon-mixins: hakuna maoni ya `//!` au sifa za `#![...]` katika `runtime.rs`, hakuna faili za `mod x;` na hakuna `include!`. Weka kila kitu katika faili hiyo moja (inline `mod x { ... }` inakubalika).

### Hizo hook points

Hivi ni vizuizi vya ujenzi vya mod za kit. Majina na hoja hubaki vile vile katika matoleo mbalimbali ya Horizon.

| Hook point | Wakati Horizon inapoiita | Inachopata |
|---|---|---|
| `native_frame` | Kila fremu, ukiwa katika ulimwengu au la | `elapsed: Duration` |
| `native_tick` | Kila tick ya mchezo (20 kwa sekunde), pia kwenye seva ya mchezaji mmoja: kagua `native_window()` | |
| `native_hud` | Wakati wa kuchora HUD (si chini ya F1) | `batch`, `corners`: weka kisanduku kwa `corners.place(corner, w, h)` |
| `native_key` | Kitufe au kitufe cha kipanya kinapobonyezwa chini au juu (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Gurudumu la kipanya unapocheza | `steps`, `claimed`: iweke ili gurudumu lisiguse hotbar |
| `native_command` | Amri ya gumzo, bila mkwaju wake, kabla ya seva kuiona | `command`, `ran`: iweke ikiwa ilikuwa yako |
| `native_zoom` | Kila fremu unapocheza | `zoom: &mut Option<NativeZoom>`: iweke ili kukuza |
| `native_minimap` | Kila fremu | `minimap: &mut Option<NativeMinimap>`: iweke ili kuonyesha ramani ndogo ya Horizon |
| `native_mods` | Options > Mods huorodhesha mod | `mods`: sukuma safu ya mod yako na vitufe |
| `native_settings` | Ukurasa wa mipangilio wa mod yako unafunguka | `mod_id`, `buttons`: sukuma lebo za vitufe |
| `native_settings_click` | Kitufe kwenye ukurasa wako wa mipangilio kinapobonyezwa | `mod_id`, `button` |

Kila hook inapata `game` wakati `bindings` zake zinapoomba: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`&crate::game::Game<'_>` na `&*self` kwa `native_zoom` na `native_minimap`). Faili ambazo kit huandika huonyesha `bindings` za kila moja.

### Wasaidizi wa mchezo

Wapigie simu hawa kwenye `game` kutoka hook yoyote.

| Msaidizi | Inachofanya |
|---|---|
| `native_on(id)` | Ikiwa mchezaji aliiacha mod yako ikiwashwa katika Options > Mods. Ikague kwanza katika kila hook |
| `native_playing()` | Unacheza, bila menyu, gumzo au bango kufunguliwa |
| `native_window()` | Huu ni mchezo wa dirisha, si wa seva |
| `native_player()` | Mchezaji katika ulimwengu: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, au `None` |
| `native_frame_rate()` | FPS sasa, fremu ya polepole zaidi na 1% ya chini |
| `native_clicks()` | Mibofyo ya mashambulizi na matumizi katika sekunde iliyopita |
| `native_mapping("key.jump")` | Ikiwa kidhibiti cha kawaida kimeshikiliwa, na jina la kitufe chake |
| `native_mod_key(id, name, default)` | Kitufe ambacho mchezaji alichagua kwa jina la kitufe chako `name` |
| `native_chat(text)` | Mstari katika gumzo la mchezaji |
| `native_text(batch, text, [x, y], rgb, shadow)` | Maandishi kwenye HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Mstatili kwenye HUD |
| `native_text_width(text)` | Upana wa maandishi, katika saizi za GUI |
| `native_corner_shown(corner)` | Ikiwa kona iko wazi (F3 huchukua zile za juu) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Soma na uhifadhi mipangilio ya mod yako |
| `native_data_dir(id)` | Folda ya mod yako mwenyewe, kwa ajili ya faili zake |

Matoleo mapya ya Horizon huongeza zaidi (paneli ya vitu kando ya ghala, mapishi, kutoa vitu katika hali ya ubunifu). Mod inayotumia hizo inahitaji toleo hilo la Horizon; **Kagua na ufunge** hutambua na kuiandika kwenye mod.

### Mawazo ya kujaribu

- **Kikaunta cha CPS**: mstari wa HUD wenye `native_clicks()`.
- **Vitufe vya kubofya (Keystrokes)**: visanduku vinavyowaka wakati `native_mapping("key.forward")` na vingine vimeshikiliwa.
- Ujumbe wa **viwianishi vya kifo**: kumbuka `native_player()` kila tick na useme mahali ulipokuwa inapogeuka kuwa `None`.
- **Kikokotoo**: `/calc 2+2`, kusoma nambari baada ya jina la amri.
- **Kipima muda cha kipindi**: hesabu ticks katika `native_tick` na uonyeshe muda uliochezwa kwenye kisanduku cha HUD.

## Nini mod haziwezi kufanya bado

Horizon huwapa mod asilia hizo hook points zilizo hapo juu, si mchezo mzima. Mod bado haziwezi:

- kuongeza vitalu (blocks), vitu (items), viumbe (mobs), mapishi au maumbo (textures) (hizo ni mod za maudhui, `.hmod`, ambazo kit hii haitengenezi);
- kubadilisha jinsi ulimwengu au viumbe wanavyofanya kazi kwenye seva;
- kusoma ulimwengu unaomzunguka mchezaji (vitalu, viumbe, vitu) au ghala la mchezaji;
- kutuma jumbe za gumzo au amri kwa seva.

Wakati Horizon inapoongeza hook points, kit inaweza kuzitumia: masasisho ya programu huleta kwenye mjenzi.

## Matatizo

- **Ujenzi wa kianzilishi unashindwa:** ukurasa wake wa Mods huonyesha mistari ya mwisho ya hitilafu. Endesha **Kagua na ufunge** ukiwa na Rust iliyosakinishwa ili kuona hitilafu kamili ikiwa na faili na mstari. Nakili hitilafu kwenye suala la GitHub ikiwa umekwama.
- **"Not packed: TODO":** mfungaji anakataa msimbo wenye `TODO` ndani yake (hivyo ndivyo mod zilizobadilishwa ambazo hazijakamilika zinavyowekwa alama). Maliza au ondoa mistari hiyo.
- **Kitufe changu hakifanyi chochote:** angalia **Options > Mods > mod yako**: mod nyingine au Horizon inaweza kutumia kitufe sawa.
- **Amri yangu inaenda kwa seva badala yake:** vitambulisho vya mod na majina ya amri ni herufi ndogo; angalia ikiwa `/<mod id>` inaiorodhesha.
