# Horizon modları yapma

Horizon Translator'ın **Mod yap** sekmesi, Minecraft Java'nın Rust ile yeniden yazımı olan [Horizon](https://github.com/horizon-rust/horizon) için modlar oluşturur. Hiç kod yazmadan bir mod yapabilir ve daha ileri gitmek için daha sonra kodunu açabilirsiniz.

- **Kodsuz:** modunuzun ne yapacağını işaretleyin, kutuları doldurun, **Mod yap**'a basın. Hazır bir `.hnmod` dosyası elde edersiniz.
- **Kod ile:** her mod Rust kaynak kodu, bir test ortamı ve bir denetleme düğmesiyle birlikte gelir. Bir başlangıç kiti olarak kullanın.
- **Çalışmaya devam eder:** kit modları Horizon'ın yalnızca ekibin modlar için kararlı tuttuğu kısımlarını ("hook points") kullanır, böylece başlatıcı bunları daha yeni Horizon sürümlerinde de derler. Her Horizon güncellemesi için yeni bir sürüme ihtiyacınız olmaz.
- **Güncellemeleri alır:** uygulama açıldığında GitHub'da yeni bir sürüm olup olmadığını kontrol eder ve size bildirir. Yeni sürümler oluşturucuya ve rehbere yeni özellikler getirir.

## Hızlı başlangıç

1. Web sitesinden (veya GitHub Sürümler sayfasından) Windows veya Linux için Horizon Translator'ı edinin ve açın.
2. **Mod yap** sekmesini açın. **Şununla başla** altından bir örnek seçip **Kullan**'a basın veya boş modla başlayın.
3. Modunuza bir **ad** verin. **Mod id** otomatik olarak doldurulur: aynı modun her yeni sürümü için bunu aynı tutun.
4. İstediğiniz kısımları işaretleyin (HUD kutusu, sohbet komutları, tuşlar, yakınlaştırma, sohbet mesajları) ve doldurun.
5. **Horizon'a ekle** seçeneğini işaretli bırakın ve **Mod yap**'a basın.
6. Horizon başlatıcısının **Mods** sayfasında: "Native build of Horizon" altındaki **Build** düğmesine basın (görünmüyorsa önce **Settings > Game > Native mods** ayarını açın). İlk derleme Rust'ı indirir ve 20 ila 30 dakika sürer; sonraki derlemeler birkaç dakika alır.
7. Oynayın. Modunuzun komutlarını ve tuşlarını görmek için sohbete `/<mod id niz>` yazın.

Modunuz `Documents/Horizon Translator/<mod id>/` klasörüne kaydedilir (klasörü uygulamanın alt kısmından değiştirebilirsiniz).

## Kod yazmadan neler yapabilirsiniz

### HUD kutusu

Ekranın bir köşesinde koordinatlarınız, baktığınız yön veya FPS gibi metinler. Her satır düz metin ile yer tutucuları (aşağıda) birleştirebilir. Kutu F1 altında gizlenir ve F3 ekranının önünden çekilir. Bir metin rengi, arkasında koyu bir kutu olup olmayacağını ve onu göstermek veya gizlemek için bir tuş seçin. Oyuncular bunu **Seçenekler > Modlar** altından da kapatabilir.

### Sohbet komutları

Oyuncuların sohbete yazdığı `/hello` gibi komutlar. Bir komut şunları yapabilir:

- **Sohbette söyle:** bir veya daha fazla satır gösterir (kutudaki satırlar arasında `|` kullanın).
- **HUD kutusunu göster / gizle.**
- **Yakınlaştır / uzaklaştır.**

Komutlar sunucu görmeden önce oyuncunun kendi oyununda çalışır, bu yüzden her sunucuda çalışır. Bir sunucunun komut adını (`/help`, `/home`, `/spawn`...) tekrar kullanmayın: modunuz onun yerini alır. `/<mod id>` her zaman eklenir: komutlarınızı ve tuşlarınızı listeler.

### Tuşlar

Komutlarla aynı şeyleri yapan tuşlar. Oyuncular her tuşu **Seçenekler > Modlar > modunuz** altından değiştirebilir. Tuşlar yalnızca oyun oynarken etkindir; sohbete yazarken veya bir menü açıkken çalışmaz.

### Yakınlaştırma (Zoom)

OptiFine gibi bir yakınlaştırma tuşu: kaç kat yakınlaşacağı (1.5 ile 10 arası), yalnızca basılı tutulduğunda veya aç/kapat şeklinde, dürbün gibi pürüzsüz ve yakınlaştırma sırasında fare tekerleğiyle daha yakın veya daha uzak ayarlama. Yakınlaştırıldığında fare dönüşü yavaşlar.

### Sohbet mesajları

- **Karşılama mesajı:** bir dünyaya katıldığınızda gösterilen satırlar.
- **Zaman ayarlı mesajlar:** oynarken birkaç dakikada bir gösterilen satırlar (dünyaya katılma anından itibaren sayılır).

Mesajlar yalnızca sizin ekranınızda görünür; sunucuya hiçbir şey gönderilmez.

### Yer tutucular

Herhangi bir metin bunları içerebilir. Metin gösterildiğinde doldurulurlar.

| Yer tutucu | Gösterir |
|---|---|
| `{x}` `{y}` `{z}` | Konumunuz, bir ondalık: `12.3` |
| `{bx}` `{by}` `{bz}` | İçinde bulunduğunuz blok: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Baktığınız yer, derece cinsinden |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Saniye başına kare |
| `{name}` `{version}` | Modunuzun adı ve sürümü |

### Oyuncuların edindiği ayarlar

Her kit modunun **Seçenekler > Modlar** altında tuşlarını ve her bölüm için bir AÇIK/KAPALI düğmesini (HUD kutusu, yakınlaştırma, karşılama mesajı, zaman ayarlı mesajlar) içeren bir satırı vardır. Oyuncular tüm modu oradan da kapatabilir.

## Yeni bir sürüm yapma

1. **Yaptığım bir modu aç...**'a basın ve modunuzun klasörünü seçin. Form geçen sefer seçtiklerinizle doldurulur.
2. İstediğinizi değiştirin ve **sürümü** yükseltin (1.0.0 → 1.1.0).
3. **Mod yap**'a basın. Yeni sürüm asla eskisinin üzerine yazılmaz, yeni bir klasöre gider (`<mod id>-2`...) ve Horizon'daki eski `.hnmod` dosyasının yerini alır. Başlatıcıda tekrar derleyin.

Aynı **mod id**'yi koruyun: Horizon bir modu kimliğine göre tanır ve oyuncuların ayarları ile tuşları onunla birlikte kalır.

## Modunuzu paylaşma

`.hnmod` dosyasını gönderin. Oyuncular bunu başlatıcının Mods sayfasından yükler (veya Windows'ta `%APPDATA%\horizon\horizon-native-mods\`, Linux'ta `~/.local/share/horizon/horizon-native-mods/` içine kopyalar) ve derler.

Yerel modlar oyunun içine derlenir ve oyuncunun yetkileriyle çalışır; yalıtılmış bir alanda (sandbox) değillerdir. Başlatıcının derlemeden önce sormasının nedeni budur. Yalnızca kodunu bildiğiniz modları paylaşın ve yalnızca güvendiğiniz kişilerin modlarını yükleyin.

## Daha ileri gitmek: kod

Her mod klasörü küçük bir Rust projesidir:

| Dosya | Ne olduğu |
|---|---|
| `mixins/<id>/runtime.rs` | Modun kendisi. En üstte ayarları (metinler, tuşlar, komutlar), ortada mantığı, en altta testleri |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Hook'lar: her biri Horizon çağırdığında çalışır (bir tuş, HUD, bir komut, bir tick) |
| `mixins/<id>/horizon-mixin.toml` | Hangi hook noktasının hangi dosyayı çalıştırdığı |
| `Cargo.toml` | `cargo test` komutunun oyuna gerek kalmadan `runtime.rs` içindeki testleri çalıştırmasını sağlar |
| `compatibility.json` | Modun ihtiyaç duyduğu hook noktaları sürümü ve yapıldığı Horizon commit'i |
| `horizon-kit.json` | Oluşturucuda seçtikleriniz |
| `<id>.hnmod` | Paketlenmiş mod |

Kodu değiştirmek için:

1. Dosyaları herhangi bir düzenleyicide düzenleyin (rust-analyzer eklentisiyle VS Code iyi bir seçimdir).
2. Uygulamada: **Mod yap > Bir klasörü kontrol et ve paketle...**'ye tıklayın ve modunuzun klasörünü seçin. Rust kuruluysa (rustup.rs), kodunuzu Horizon'ın mod yardımcılarına karşı derler ve önce testlerinizi çalıştırır; Rust olmadan doğrudan paketler ve başlatıcının derlemesi hataları bildirir. Ardından yeni bir `.hnmod` paketler ve bunu Horizon'a ekler.
3. Başlatıcıda derleyin ve deneyin.

**Kodunu elle değiştirdiğiniz bir modda Mod yap düğmesini kullanmayın:** formdan yeniden başlar. Kontrol et ve paketle'yi kullanın.

### Komut satırından (ve yapay zeka ajanları için)

Uygulamanın `horizon-port.jar` dosyası aynı işlemi bir terminalden yapar ve tam bir kontrol ekler. Her mod klasöründe kod yazan yapay zeka ajanları için bu komutları içeren bir `AGENTS.md` ve her hook noktasını ve yardımcısını listeleyen bir `HOOKS.md` bulunur.

| Komut | Ne yapar |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Bir oluşturucu belirtiminden mod yapar (`kit-examples/` içinde bazıları vardır) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | O Horizon'ın hook noktalarını ve yardımcılarını, her hook için `[[injection]]` ile listeler |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Tam kontrol: başlatıcının yaptığı gibi modu Horizon'ın bir kopyasına koyar ve derler |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Tam kontrol, testler, paketleme, Horizon'a ekleme |

`SRC`, `compile` için açılmış Horizon kaynak kodudur. `compile` işlemi Rust'a ve başlatıcının horizon-mixins aracına ihtiyaç duyar (ilk yerel derlemesi tarafından kurulur); ilk çalışması birkaç GB yer kaplar ve birkaç dakika sürer. **Bir klasörü kontrol et ve paketle** seçeneğinin aksine, oyunun hook noktalarının parçası olmayan kendi verilerini (`game.player`, `game.items()`...) okuyan kodları da kontrol eder.

### Bir hook nasıl çalışır

Horizon sabit yerlerde boş fonksiyonlar çağırır (**hook noktaları**). `horizon-mixin.toml` içindeki her `[[injection]]` satırı, dosyalarınızdan birini bu fonksiyonlardan birinin başına yerleştirir. Dosyanız `bindings` altında listelenen değerleri alan tek bir fonksiyon içerir. Örneğin `key.rs` oyunu, tuşun adını ve basılıp basılmadığını alır:

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

`runtime.rs` oyuna bir modül olarak eklenir (`crate::game::kit_<id>`) ve aynı zamanda projenin kendi kütüphanesidir, bu nedenle oyuna ihtiyaç duymayan her şey (matematik, metin, modunuzun durumu) oraya gider ve `cargo test` ile test edilebilir.

horizon-mixins kuralları: `runtime.rs` içinde `//!` yorumları veya `#![...]` öznitelikleri olamaz, `mod x;` dosyaları ve `include!` kullanılamaz. Her şeyi o tek dosyaya koyun (satır içi `mod x { ... }` kullanılabilir).

### Hook noktaları

Bunlar kit modlarının yapı taşlarıdır. İsimler ve bağımsız değişkenler Horizon sürümleri boyunca aynı kalır.

| Hook point | Horizon ne zaman çağırır | Ne alır |
|---|---|---|
| `native_frame` | Bir dünyada olsun ya da olmasın her karede | `elapsed: Duration` |
| `native_tick` | Her oyun tick'inde (saniyede 20), tek oyunculunun sunucusunda da: `native_window()` kontrol edin | |
| `native_hud` | HUD çizilirken (F1 altında değil) | `batch`, `corners`: `corners.place(corner, w, h)` ile bir kutu yerleştirin |
| `native_key` | Bir tuş veya fare düğmesine basıldığında veya bırakıldığında (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Oynarken fare tekerleği hareketinde | `steps`, `claimed`: tekerleğin hotbar'ı değiştirmesini önlemek için ayarlayın |
| `native_command` | Sunucu görmeden önce, eğik çizgi olmadan bir sohbet komutunda | `command`, `ran`: sizinki olduğunda ayarlayın |
| `native_zoom` | Oynarken her karede | `zoom: &mut Option<NativeZoom>`: yakınlaştırmak için ayarlayın |
| `native_minimap` | Her karede | `minimap: &mut Option<NativeMinimap>`: Horizon'ın mini haritasını göstermek için ayarlayın |
| `native_mods` | Seçenekler > Modlar modları listelerken | `mods`: modunuzun satırını ve tuşlarını ekleyin |
| `native_settings` | Modunuzun ayarlar sayfası açıldığında | `mod_id`, `buttons`: düğme etiketlerini ekleyin |
| `native_settings_click` | Ayarlar sayfanızdaki bir düğmeye tıklandığında | `mod_id`, `button` |

`bindings` talep ettiğinde her hook `game` nesnesini alır: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`native_zoom` ve `native_minimap` için `&crate::game::Game<'_>` ve `&*self`). Kitin yazdığı dosyalar her birinin `bindings` tanımlarını gösterir.

### Oyun yardımcıları

Bunları herhangi bir hook içinden `game` üzerinde çağırın.

| Yardımcı | Ne yapar |
|---|---|
| `native_on(id)` | Oyuncunun Seçenekler > Modlar altında modunuzu açık bırakıp bırakmadığı. Her hook'ta önce bunu kontrol edin |
| `native_playing()` | Menü, sohbet veya tabela açık olmadan oynama durumu |
| `native_window()` | Bunun bir sunucu değil, pencerenin oyunu olup olmadığı |
| `native_player()` | Bir dünyadaki oyuncu: `x`, `y`, `z`, `yaw`, `pitch`, `dimension` veya `None` |
| `native_frame_rate()` | Şimdiki FPS, en yavaş kare ve %1 en düşük değer |
| `native_clicks()` | Son bir saniyedeki saldırı ve kullanım tıklamaları |
| `native_mapping("key.jump")` | Standart bir kontrolün basılı tutulup tutulmadığı ve tuşunun adı |
| `native_mod_key(id, name, default)` | Oyuncunun tuş `name` için seçtiği tuş |
| `native_chat(text)` | Oyuncunun sohbetinde bir satır |
| `native_text(batch, text, [x, y], rgb, shadow)` | HUD üzerinde metin |
| `native_fill(batch, [x, y, w, h], argb)` | HUD üzerinde bir dikdörtgen |
| `native_text_width(text)` | Bir metnin GUI pikselleri cinsinden genişliği |
| `native_corner_shown(corner)` | Bir köşenin boş olup olmadığı (F3 üst köşeleri alır) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Modunuzun ayarlarını okuma ve saklama |
| `native_data_dir(id)` | Modunuzun kendi dosyaları için kendi klasörü |

Daha yeni Horizon sürümleri daha fazlasını ekler (envanterlerin yanında bir eşya paneli, tarifler, yaratıcı modda eşya verme). Bunları kullanan bir mod o Horizon sürümüne ihtiyaç duyar; **Bir klasörü kontrol et ve paketle** bunu fark eder ve moda yazar.

### Denenebilecek fikirler

- Bir **CPS sayacı**: `native_clicks()` ile bir HUD satırı.
- **Tuş vuruşları (Keystrokes)**: `native_mapping("key.forward")` ve diğerleri basılıyken yanan kutular.
- Bir **ölüm koordinatları** notu: her tick'te `native_player()`'ı hatırlayın ve `None` olduğunda nerede olduğunuzu söyleyin.
- Bir **hesap makinesi**: komut adından sonraki sayıları okuyan `/calc 2+2`.
- Bir **oturum zamanlayıcısı**: `native_tick` içinde tick'leri sayın ve HUD kutusunda oynanan süreyi gösterin.

## Modların henüz yapamadıkları

Horizon yerel modlara tüm oyunu değil, yukarıdaki hook noktalarını verir. Modlar henüz şunları yapamaz:

- bloklar, eşyalar, canavarlar, tarifler veya dokular ekleme (bunlar bu kitin yapmadığı içerik modlarıdır, `.hmod`);
- bir sunucuda dünyanın veya yaratıkların çalışma şeklini değiştirme;
- oyuncunun etrafındaki dünyayı (bloklar, canavarlar, eşyalar) veya oyuncunun envanterini okuma;
- sunucuya sohbet mesajları veya komutlar gönderme.

Horizon hook noktaları ekledikçe kit bunları kullanabilir: uygulamanın güncellemeleri bunları oluşturucuya getirir.

## Sorunlar

- **Başlatıcının derlemesi başarısız oluyor:** Mods sayfası hatanın son satırlarını gösterir. Dosya ve satırla birlikte hatanın tamamını görmek için Rust kurulu durumdayken **Bir klasörü kontrol et ve paketle** işlemini çalıştırın. Takılırsanız hatayı bir GitHub sorun kaydına kopyalayın.
- **"Paketlenmedi: TODO":** paketleyici içinde `TODO` bulunan kodları reddeder (bitmemiş dönüştürülmüş modlar bu şekilde işaretlenir). Bu satırları tamamlayın veya kaldırın.
- **Tuşum hiçbir şey yapmıyor:** **Seçenekler > Modlar > modunuz** bölümünü kontrol edin: başka bir mod veya Horizon aynı tuşu kullanıyor olabilir.
- **Komutum sunucuya gidiyor:** mod id'leri ve komut adları küçük harflidir; `/<mod id>` listesinde olup olmadığını kontrol edin.
