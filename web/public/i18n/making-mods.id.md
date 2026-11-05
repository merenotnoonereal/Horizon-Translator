# Membuat mod Horizon

Tab **Buat mod** pada Horizon Translator membuat mod untuk [Horizon](https://github.com/horizon-rust/horizon), penulisan ulang Minecraft Java dalam Rust. Kamu dapat membuat mod tanpa menulis kode apa pun, dan kamu dapat membuka kodenya nanti untuk melangkah lebih jauh.

- **Tanpa kode:** centang apa yang dilakukan mod kamu, isi kotak-kotaknya, tekan **Buat mod**. Kamu mendapatkan file `.hnmod` yang siap pakai.
- **Dengan kode:** setiap mod dilengkapi dengan kode sumber Rust, konfigurasi pengujian, dan tombol pemeriksaan. Gunakan ini sebagai starter kit.
- **Tetap berfungsi:** mod kit hanya menggunakan bagian Horizon yang dijaga stabil oleh timnya untuk mod ("hook points"), sehingga peluncur dapat membangunnya pada versi Horizon yang lebih baru juga. Kamu tidak memerlukan versi baru untuk setiap pembaruan Horizon.
- **Mendapatkan pembaruan:** aplikasi memeriksa GitHub untuk versi baru saat dimulai dan memberitahu kamu. Versi baru menghadirkan fitur-fitur baru ke builder dan panduan.

## Mulai cepat

1. Dapatkan Horizon Translator untuk Windows atau Linux dari situs web (atau halaman Rilis GitHub) dan buka aplikasinya.
2. Buka tab **Buat mod**. Pilih contoh di bawah **Mulai dari** dan tekan **Gunakan**, atau mulai dengan mod kosong.
3. Beri mod kamu sebuah **nama**. **ID mod** akan terisi secara otomatis: jaga agar tetap sama untuk setiap versi baru dari mod yang sama.
4. Centang bagian yang kamu inginkan (kotak HUD, perintah obrolan, tombol, zoom, pesan obrolan) dan isi rinciannya.
5. Biarkan opsi **Tambahkan ke Horizon** tercentang dan tekan **Buat mod**.
6. Di halaman **Mod** peluncur Horizon: klik **Build** di bawah "Native build of Horizon" (jika belum ada di sana, aktifkan **Settings > Game > Native mods** terlebih dahulu). Proses build pertama kali akan mengunduh Rust dan memerlukan waktu 20 hingga 30 menit; build berikutnya hanya memerlukan beberapa menit.
7. Mainkan. Ketik `/<id mod kamu>` dalam obrolan untuk melihat perintah dan tombol mod kamu.

Mod kamu disimpan di `Documents/Horizon Translator/<mod id>/` (ubah folder di bagian bawah aplikasi).

## Apa yang dapat kamu buat tanpa kode

### Kotak HUD

Teks di sudut layar, seperti koordinat kamu, arah hadap, atau FPS kamu. Setiap baris dapat menggabungkan teks biasa dengan placeholder (di bawah). Kotak akan disembunyikan di bawah F1 dan bergeser agar tidak menghalangi layar F3. Pilih warna teks, latar belakang kotak gelap atau tidak, dan tombol untuk menampilkan atau menyembunyikannya. Pemain juga dapat mematikannya di **Options > Mods**.

### Perintah obrolan

Perintah yang diketik pemain dalam obrolan, seperti `/hello`. Sebuah perintah dapat:

- **Katakan di obrolan:** menampilkan satu atau beberapa baris (gunakan `|` di antara baris dalam kotak).
- **Tampilkan / sembunyikan kotak HUD.**
- **Perbesar / perkecil (zoom).**

Perintah berjalan pada game pemain itu sendiri, sebelum server melihatnya, sehingga berfungsi di server mana pun. Jangan gunakan kembali nama perintah server (`/help`, `/home`, `/spawn`...): mod kamu akan mengambil alihnya. `/<mod id>` selalu ditambahkan: ini mencantumkan perintah dan tombol kamu.

### Tombol

Tombol yang melakukan hal yang sama seperti perintah. Pemain dapat mengubah setiap tombol di **Options > Mods > mod kamu**. Tombol hanya berfungsi saat bermain, tidak saat mengetik di obrolan atau saat menu sedang terbuka.

### Zoom

Tombol zoom seperti milik OptiFine: berapa kali lebih dekat (1.5 hingga 10), hanya saat ditahan atau tekan untuk aktif / tekan untuk nonaktif, mulus seperti spyglass, dan roda mouse memperbesar atau memperkecil lebih lanjut saat melakukan zooming. Gerakan mouse melambat saat dizoom.

### Pesan obrolan

- **Pesan selamat datang:** baris yang ditampilkan saat kamu bergabung ke sebuah dunia.
- **Pesan berkala:** baris yang ditampilkan setiap beberapa menit saat kamu bermain (dihitung sejak bergabung ke dunia).

Pesan hanya ditampilkan di layar kamu; tidak ada yang dikirim ke server.

### Placeholder

Teks apa pun dapat memuat placeholder ini. Semuanya akan diisi saat teks ditampilkan.

| Placeholder | Shows |
|---|---|
| `{x}` `{y}` `{z}` | Posisi kamu, satu desimal: `12.3` |
| `{bx}` `{by}` `{bz}` | Blok tempat kamu berada: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Arah pandangan kamu, dalam derajat |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Frame per detik |
| `{name}` `{version}` | Nama dan versi mod kamu |

### Pengaturan yang didapatkan pemain

Setiap mod kit memiliki baris di **Options > Mods** dengan tombol-tombolnya dan tombol ON/OFF untuk setiap bagian (kotak HUD, zoom, pesan selamat datang, pesan berkala). Pemain juga dapat mematikan seluruh mod di sana.

## Membuat versi baru

1. **Buka mod buatan saya...** dan pilih folder mod kamu. Formulir akan terisi dengan apa yang kamu pilih sebelumnya.
2. Ubah apa yang kamu inginkan dan naikkan **versi** (1.0.0 → 1.1.0).
3. **Buat mod**. Versi baru masuk ke folder baru (`<mod id>-2`...), tidak pernah menimpa folder lama kamu, dan menggantikan file `.hnmod` lama di Horizon. Bangun kembali di peluncur.

Pertahankan **ID mod** yang sama: Horizon mengenali mod berdasarkan ID-nya, dan pengaturan serta tombol pemain akan tetap tersimpan bersamanya.

## Membagikan mod kamu

Kirimkan file `.hnmod`. Pemain menginstalnya dari halaman Mod peluncur (atau menyalinnya ke `%APPDATA%\horizon\horizon-native-mods\` di Windows, `~/.local/share/horizon/horizon-native-mods/` di Linux) lalu melakukan build.

Mod native dikompilasi ke dalam game dan berjalan dengan hak akses pemain; mod ini tidak di-sandbox. Itulah sebabnya peluncur meminta konfirmasi sebelum melakukan build. Hanya bagikan mod yang kodenya kamu ketahui, dan hanya instal mod dari orang yang kamu percayai.

## Melangkah lebih jauh: kodenya

Setiap folder mod adalah proyek kecil Rust:

| File | What it is |
|---|---|
| `mixins/<id>/runtime.rs` | Mod itu sendiri. Pengaturannya di bagian atas (teks, tombol, perintah), logikanya, dan tes di bagian bawah |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Hook: masing-masing berjalan saat Horizon memanggilnya (tombol, HUD, perintah, tick) |
| `mixins/<id>/horizon-mixin.toml` | Hook point mana yang menjalankan file mana |
| `Cargo.toml` | Memungkinkan `cargo test` menjalankan tes di `runtime.rs` tanpa game |
| `compatibility.json` | Versi hook point yang dibutuhkan mod dan commit Horizon saat mod dibuat |
| `horizon-kit.json` | Apa yang kamu pilih di builder |
| `<id>.hnmod` | Mod yang sudah dikemas |

Untuk mengubah kodenya:

1. Edit file di editor mana pun (VS Code dengan ekstensi rust-analyzer adalah pilihan yang bagus).
2. Di dalam aplikasi: **Buat mod > Periksa dan kemas folder...**, dan pilih folder mod kamu. Jika Rust terinstal (rustup.rs), ini akan mengompilasi kode kamu terhadap pembantu mod Horizon dan menjalankan tes kamu terlebih dahulu; tanpa Rust, ini akan mengemas langsung dan proses build peluncur akan memberitahu kamu tentang kesalahan. Kemudian aplikasi mengemas file `.hnmod` baru dan menambahkannya ke Horizon.
3. Bangun di peluncur dan coba mainkan.

**Jangan gunakan Buat mod pada mod yang kodenya kamu ubah secara manual:** ini akan memulai kembali dari formulir. Gunakan Periksa dan kemas.

### Dari baris perintah (dan untuk agen AI)

File `horizon-port.jar` milik aplikasi melakukan hal yang sama dari terminal, dan menambahkan pemeriksaan penuh. Setiap folder mod memiliki `AGENTS.md` dengan perintah-perintah ini untuk agen koding AI, dan `HOOKS.md` yang mencantumkan setiap hook point dan helper.

| Command | What it does |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Membuat mod dari spesifikasi builder (`kit-examples/` memiliki beberapa contoh) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Mencantumkan hook point dan helper Horizon tersebut, beserta `[[injection]]` untuk setiap hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Pemeriksaan penuh: menempatkan mod ke dalam salinan Horizon dan mengompilasinya, seperti yang dilakukan peluncur |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Pemeriksaan penuh, tes, pengemasan, penambahan ke Horizon |

`SRC` adalah kode sumber Horizon yang diekstrak untuk `compile`. `compile` memerlukan Rust dan alat horizon-mixins milik peluncur (diinstal oleh build native pertamanya); proses pertama kali memakan beberapa GB dan beberapa menit. Berbeda dengan **Periksa dan kemas**, ini juga memeriksa kode yang membaca data milik game itu sendiri (`game.player`, `game.items()`...), yang bukan merupakan bagian dari hook points.

### Cara kerja sebuah hook

Horizon memanggil fungsi-fungsi kosong di tempat yang telah ditentukan (**hook points**). Setiap baris `[[injection]]` di `horizon-mixin.toml` menempatkan salah satu file kamu di awal salah satu fungsi tersebut. File kamu berisi satu fungsi, yang menerima nilai-nilai yang tercantum di bawah `bindings`. Sebagai contoh, `key.rs` menerima objek game, nama tombol, dan apakah tombol tersebut ditekan atau dilepas:

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

`runtime.rs` ditambahkan ke game sebagai modul (`crate::game::kit_<id>`) dan juga menjadi pustaka milik proyek itu sendiri, sehingga apa pun yang tidak membutuhkan game (matematika, teks, status mod kamu) diletakkan di sana dan dapat diuji dengan `cargo test`.

Aturan yang ditetapkan horizon-mixins: tidak boleh ada komentar `//!` atau atribut `#![...]` di `runtime.rs`, tidak boleh ada file `mod x;` dan tidak boleh ada `include!`. Letakkan semuanya di dalam satu file tersebut (inline `mod x { ... }` diperbolehkan).

### Hook points

Ini adalah blok pembangun mod kit. Nama dan argumennya tetap sama di seluruh versi Horizon.

| Hook point | When Horizon calls it | What it gets |
|---|---|---|
| `native_frame` | Setiap frame, berada di dunia atau tidak | `elapsed: Duration` |
| `native_tick` | Setiap tick game (20 kali per detik), juga di server single player: periksa `native_window()` | |
| `native_hud` | Saat menggambar HUD (tidak saat F1) | `batch`, `corners`: tempatkan kotak dengan `corners.place(corner, w, h)` |
| `native_key` | Tombol keyboard atau mouse ditekan atau dilepas (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Roda mouse saat bermain | `steps`, `claimed`: atur ini untuk mencegah roda mouse berpindah di hotbar |
| `native_command` | Perintah obrolan, tanpa tanda garis miring, sebelum server melihatnya | `command`, `ran`: atur ini jika perintah tersebut adalah milik kamu |
| `native_zoom` | Setiap frame saat bermain | `zoom: &mut Option<NativeZoom>`: atur ini untuk zoom |
| `native_minimap` | Setiap frame | `minimap: &mut Option<NativeMinimap>`: atur ini untuk menampilkan minimap Horizon |
| `native_mods` | Options > Mods mencantumkan daftar mod | `mods`: masukkan baris dan tombol mod kamu |
| `native_settings` | Halaman pengaturan mod kamu terbuka | `mod_id`, `buttons`: masukkan label tombol |
| `native_settings_click` | Tombol pada halaman pengaturan kamu diklik | `mod_id`, `button` |

Setiap hook menerima `game` jika `bindings`-nya memintanya: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`&crate::game::Game<'_>` dan `&*self` untuk `native_zoom` dan `native_minimap`). File yang ditulis kit menunjukkan `bindings` dari masing-masing hook.

### Helper game

Panggil fungsi-fungsi ini pada `game` dari hook mana pun.

| Helper | What it does |
|---|---|
| `native_on(id)` | Apakah pemain membiarkan mod kamu aktif di Options > Mods. Periksa ini terlebih dahulu di setiap hook |
| `native_playing()` | Sedang bermain, tanpa menu, obrolan, atau papan tanda yang terbuka |
| `native_window()` | Ini adalah game dari jendela, bukan server |
| `native_player()` | Pemain di dalam dunia: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, atau `None` |
| `native_frame_rate()` | FPS saat ini, frame terlambat, dan 1% low |
| `native_clicks()` | Klik serang dan klik gunakan dalam satu detik terakhir |
| `native_mapping("key.jump")` | Apakah kontrol vanilla sedang ditahan, dan nama tombolnya |
| `native_mod_key(id, name, default)` | Tombol yang dipilih pemain untuk nama tombol kamu `name` |
| `native_chat(text)` | Satu baris dalam obrolan pemain |
| `native_text(batch, text, [x, y], rgb, shadow)` | Teks pada HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Persegi panjang pada HUD |
| `native_text_width(text)` | Lebar teks dalam piksel GUI |
| `native_corner_shown(corner)` | Apakah sudut layar kosong (F3 mengambil sudut atas) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Membaca dan menyimpan pengaturan mod kamu |
| `native_data_dir(id)` | Folder milik mod kamu sendiri, untuk file-file miliknya |

Versi Horizon yang lebih baru menambahkan lebih banyak lagi (panel item di samping inventaris, resep, memberikan item dalam mode kreatif). Mod yang menggunakannya memerlukan versi Horizon tersebut; **Periksa dan kemas** akan mendeteksinya dan menuliskannya ke dalam mod.

### Ide untuk dicoba

- **Penghitung CPS**: baris HUD dengan `native_clicks()`.
- **Keystrokes**: kotak yang menyala saat `native_mapping("key.forward")` dan tombol lainnya sedang ditahan.
- **Catatan koordinat kematian**: ingat `native_player()` di setiap tick dan tampilkan di mana posisi kamu saat nilainya berubah menjadi `None`.
- **Kalkulator**: `/calc 2+2`, membaca angka-angka setelah nama perintah.
- **Pengatur waktu sesi**: hitung tick di `native_tick` dan tampilkan waktu bermain di kotak HUD.

## Apa yang belum dapat dilakukan mod

Horizon memberi mod native hook points di atas, bukan keseluruhan game. Mod belum dapat:

- menambahkan blok, item, mob, resep, atau tekstur (itu adalah mod konten, `.hmod`, yang tidak dibuat oleh kit ini);
- mengubah cara kerja dunia atau mob di server;
- membaca dunia di sekitar pemain (blok, mob, item) atau inventaris pemain;
- mengirim pesan obrolan atau perintah ke server.

Saat Horizon menambahkan hook points baru, kit dapat menggunakannya: pembaruan aplikasi menghadirkannya ke builder.

## Masalah

- **Build peluncur gagal:** halaman Mod peluncur menampilkan baris-baris terakhir dari kesalahan. Jalankan **Periksa dan kemas** dengan Rust terinstal untuk melihat kesalahan lengkap beserta file dan nomor barisnya. Salin kesalahan ke issue GitHub jika kamu mengalami kebuntuan.
- **"Not packed: TODO":** pengemas menolak kode yang memuat `TODO` di dalamnya (begitulah cara mod konversi yang belum selesai ditandai). Selesaikan atau hapus baris-baris tersebut.
- **Tombol saya tidak berfungsi apa-apa:** periksa **Options > Mods > mod kamu**: mod lain atau Horizon mungkin menggunakan tombol yang sama.
- **Perintah saya justru terkirim ke server:** ID mod dan nama perintah menggunakan huruf kecil; periksa apakah `/<id mod>` mencantumkannya.
