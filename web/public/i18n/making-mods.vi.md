# Tạo mod Horizon

Tab **Tạo mod** của Horizon Translator tạo mod cho [Horizon](https://github.com/horizon-rust/horizon), bản viết lại bằng Rust của Minecraft Java. Bạn có thể tạo mod mà không cần viết bất kỳ dòng mã nào, và bạn có thể mở mã nguồn của nó sau này để phát triển thêm.

- **Không cần viết mã:** đánh dấu những gì mod sẽ làm, điền vào các ô, nhấn **Tạo mod**. Bạn sẽ nhận được tệp `.hnmod` hoàn chỉnh.
- **Có sẵn mã nguồn:** mỗi mod đi kèm với mã nguồn Rust, thiết lập thử nghiệm và nút kiểm tra. Hãy dùng nó như một bộ kit khởi đầu.
- **Tiếp tục hoạt động:** các mod kit chỉ sử dụng những phần Horizon mà nhóm phát triển giữ ổn định cho mod ("hook point"), vì vậy launcher cũng biên dịch chúng trên các phiên bản Horizon mới hơn. Bạn không cần phiên bản mới cho mỗi bản cập nhật Horizon.
- **Nhận cập nhật:** ứng dụng sẽ kiểm tra GitHub để tìm phiên bản mới khi khởi động và thông báo cho bạn. Các phiên bản mới mang đến tính năng mới cho trình tạo và hướng dẫn.

## Bắt đầu nhanh

1. Tải Horizon Translator cho Windows hoặc Linux từ trang web (hoặc trang GitHub Releases) và mở ứng dụng.
2. Mở tab **Tạo mod**. Chọn một mẫu trong **Bắt đầu từ** và nhấn **Sử dụng**, hoặc bắt đầu với mod trống.
3. Đặt **tên** cho mod của bạn. **Id mod** sẽ tự động điền: hãy giữ nguyên cho mọi phiên bản mới của cùng một mod.
4. Đánh dấu các phần bạn muốn (hộp HUD, lệnh trò chuyện, phím tắt, thu phóng, thông báo trò chuyện) và điền thông tin.
5. Để tùy chọn **Thêm vào Horizon** được đánh dấu và nhấn **Tạo mod**.
6. Trong trang **Mods** của Horizon launcher: nhấn **Build** dưới mục "Native build of Horizon" (nếu chưa thấy, hãy bật **Settings > Game > Native mods** trước). Lần biên dịch đầu tiên sẽ tải Rust và mất từ 20 đến 30 phút; các lần biên dịch sau chỉ mất vài phút.
7. Vào chơi. Gõ `/<id mod của bạn>` trong trò chuyện để xem các lệnh và phím tắt của mod.

Mod của bạn được lưu trong `Documents/Horizon Translator/<mod id>/` (thay đổi thư mục ở cuối ứng dụng).

## Những gì bạn có thể tạo mà không cần viết mã

### Hộp HUD

Văn bản ở một góc màn hình, chẳng hạn như tọa độ, hướng nhìn hoặc FPS của bạn. Mỗi dòng có thể kết hợp văn bản thuần với các placeholder (bên dưới). Hộp sẽ ẩn khi bấm F1 và dời đi để tránh màn hình F3. Chọn màu chữ, nền tối phía sau hoặc không, và một phím để hiện hoặc ẩn. Người chơi cũng có thể tắt nó trong **Options > Mods**.

### Lệnh trò chuyện

Các lệnh người chơi gõ vào khung trò chuyện, như `/hello`. Một lệnh có thể:

- **Nói trong trò chuyện:** hiển thị một hoặc nhiều dòng (sử dụng `|` giữa các dòng trong ô).
- **Hiện / ẩn hộp HUD.**
- **Thu phóng vào / ra.**

Các lệnh chạy ngay trên bản game của người chơi trước khi máy chủ nhận diện, vì vậy chúng hoạt động trên bất kỳ máy chủ nào. Đừng dùng trùng tên lệnh của máy chủ (`/help`, `/home`, `/spawn`...): mod của bạn sẽ ghi đè lên lệnh đó. Lệnh `/<mod id>` luôn được thêm vào: nó liệt kê các lệnh và phím tắt của bạn.

### Phím tắt

Các phím thực hiện những việc tương tự lệnh. Người chơi có thể đổi từng phím trong **Options > Mods > mod của bạn**. Phím chỉ hoạt động khi đang chơi, không hoạt động khi đang gõ trong trò chuyện hoặc khi đang mở menu.

### Thu phóng

Một phím thu phóng tương tự OptiFine: mức độ phóng to (1.5 đến 10 lần), chỉ phóng to khi giữ phím hoặc nhấn để bật / nhấn để tắt, mượt mà như ống nhòm spyglass, và con lăn chuột có thể phóng to hay thu nhỏ thêm trong khi đang phóng to. Tốc độ quay chuột sẽ chậm lại khi đang thu phóng.

### Thông báo trò chuyện

- **Thông báo chào mừng:** các dòng hiển thị khi bạn tham gia một thế giới.
- **Thông báo định kỳ:** các dòng hiển thị sau mỗi khoảng thời gian (phút) nhất định khi bạn chơi (tính từ lúc tham gia thế giới).

Thông báo chỉ xuất hiện trên màn hình của bạn; không có gì gửi tới máy chủ.

### Placeholder

Mọi văn bản đều có thể chứa những placeholder này. Chúng sẽ được điền giá trị khi văn bản hiển thị.

| Placeholder | Hiển thị |
|---|---|
| `{x}` `{y}` `{z}` | Vị trí của bạn, một chữ số thập phân: `12.3` |
| `{bx}` `{by}` `{bz}` | Khối bạn đang đứng: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Hướng nhìn của bạn, tính bằng độ |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Khung hình trên giây |
| `{name}` `{version}` | Tên và phiên bản mod của bạn |

### Cài đặt người chơi nhận được

Mỗi mod kit đều có một hàng trong **Options > Mods** với các phím của nó và nút BẬT/TẮT cho từng phần (hộp HUD, thu phóng, thông báo chào mừng, thông báo định kỳ). Người chơi cũng có thể tắt toàn bộ mod tại đó.

## Tạo một phiên bản mới

1. **Mở một mod tôi đã tạo...** và chọn thư mục mod của bạn. Biểu mẫu sẽ được điền lại những gì bạn đã chọn lần trước.
2. Thay đổi những gì bạn muốn và tăng **phiên bản** (1.0.0 → 1.1.0).
3. **Tạo mod**. Phiên bản mới sẽ nằm trong thư mục mới (`<mod id>-2`...), không bao giờ ghi đè lên thư mục cũ của bạn, và thay thế tệp `.hnmod` cũ trong Horizon. Biên dịch lại trong launcher.

Hãy giữ nguyên **mod id**: Horizon nhận diện mod qua id của nó, đồng thời cài đặt và phím tắt của người chơi sẽ được giữ lại.

## Chia sẻ mod của bạn

Gửi tệp `.hnmod`. Người chơi cài đặt từ trang Mods của launcher (hoặc sao chép vào `%APPDATA%\horizon\horizon-native-mods\` trên Windows, `~/.local/share/horizon/horizon-native-mods/` trên Linux) rồi biên dịch.

Mod native được biên dịch trực tiếp vào trò chơi và chạy với các quyền của người chơi; chúng không bị cô lập trong sandbox. Đó là lý do launcher sẽ hỏi xác nhận trước khi biên dịch. Chỉ chia sẻ mod khi bạn biết rõ mã nguồn, và chỉ cài đặt mod từ những người bạn tin cậy.

## Đi xa hơn: mã nguồn

Mỗi thư mục mod là một dự án Rust nhỏ:

| Tệp | Ý nghĩa |
|---|---|
| `mixins/<id>/runtime.rs` | Mã mod. Cài đặt ở trên đầu (văn bản, phím, lệnh), logic ở giữa, và kiểm thử ở dưới cùng |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Các hook: mỗi hook chạy khi Horizon gọi nó (phím, HUD, lệnh, tick) |
| `mixins/<id>/horizon-mixin.toml` | Hook point nào sẽ chạy tệp nào |
| `Cargo.toml` | Cho phép `cargo test` chạy các kiểm thử trong `runtime.rs` mà không cần trò chơi |
| `compatibility.json` | Phiên bản hook point mà mod yêu cầu và commit Horizon mà mod được tạo trên đó |
| `horizon-kit.json` | Những gì bạn đã chọn trong trình tạo |
| `<id>.hnmod` | Tệp mod đã đóng gói |

Để chỉnh sửa mã:

1. Chỉnh sửa các tệp bằng bất kỳ trình soạn thảo nào (VS Code với tiện ích mở rộng rust-analyzer là lựa chọn tốt).
2. Trong ứng dụng: **Tạo mod > Kiểm tra và đóng gói thư mục...**, rồi chọn thư mục mod của bạn. Khi đã cài đặt Rust (rustup.rs), ứng dụng sẽ biên dịch mã của bạn với các hàm hỗ trợ mod của Horizon và chạy kiểm thử trước; nếu không có Rust, ứng dụng sẽ đóng gói ngay lập tức và bản dựng của launcher sẽ thông báo về các lỗi. Sau đó ứng dụng đóng gói tệp `.hnmod` mới và thêm vào Horizon.
3. Biên dịch trong launcher và trải nghiệm.

**Không nhấn Tạo mod trên mod mà bạn đã chỉnh sửa mã thủ công:** thao tác đó sẽ bắt đầu lại từ biểu mẫu. Hãy sử dụng Kiểm tra và đóng gói.

### Từ dòng lệnh (và cho các trợ lý AI)

Tệp `horizon-port.jar` của ứng dụng cũng thực hiện điều tương tự từ terminal, kèm theo kiểm tra toàn diện. Mỗi thư mục mod có một tệp `AGENTS.md` chứa các lệnh này cho trợ lý lập trình AI, và tệp `HOOKS.md` liệt kê từng hook point cùng các hàm hỗ trợ.

| Lệnh | Chức năng |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Tạo mod từ đặc tả trình tạo (`kit-examples/` có một số mẫu) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Liệt kê các hook point và hàm hỗ trợ của Horizon đó, kèm theo `[[injection]]` cho từng hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Kiểm tra toàn diện: đưa mod vào bản sao của Horizon và biên dịch, tương tự như launcher thực hiện |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Kiểm tra toàn diện, chạy kiểm thử, đóng gói và thêm vào Horizon |

`SRC` là mã nguồn của Horizon, đã được giải nén cho `compile`. `compile` cần Rust và công cụ horizon-mixins của launcher (được cài đặt trong lần biên dịch native đầu tiên); lần chạy đầu tiên mất vài GB dung lượng và vài phút. Khác với **Kiểm tra và đóng gói**, thao tác này cũng kiểm tra cả mã đọc dữ liệu riêng của trò chơi (`game.player`, `game.items()`...), những phần không thuộc hook point.

### Cách một hook hoạt động

Horizon gọi các hàm trống tại những vị trí cố định (các **hook point**). Mỗi dòng `[[injection]]` trong `horizon-mixin.toml` đặt một trong các tệp của bạn ở đầu của một trong các hàm đó. Tệp của bạn chứa một hàm, nhận các giá trị được liệt kê dưới mục `bindings`. Ví dụ `key.rs` nhận game, tên phím và trạng thái phím được nhấn hay nhả:

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

`runtime.rs` được thêm vào trò chơi dưới dạng một mô-đun (`crate::game::kit_<id>`) và cũng là thư viện riêng của dự án, vì vậy bất kỳ thứ gì không cần trò chơi (toán học, văn bản, trạng thái mod của bạn) đều được đặt ở đó và có thể được kiểm thử bằng `cargo test`.

Các quy tắc mà horizon-mixins đặt ra: không sử dụng chú thích `//!` hoặc thuộc tính `#![...]` trong `runtime.rs`, không có tệp `mod x;` và không có `include!`. Hãy đặt tất cả vào tệp duy nhất đó (các khối nội tuyến `mod x { ... }` hoàn toàn hợp lệ).

### Các hook point

Đây là những khối xây dựng của mod kit. Tên và đối số được giữ nguyên qua các phiên bản Horizon.

| Hook point | Khi nào Horizon gọi | Những gì nhận được |
|---|---|---|
| `native_frame` | Mỗi khung hình, dù đang trong thế giới hay không | `elapsed: Duration` |
| `native_tick` | Mỗi tick trò chơi (20 lần một giây), kể cả trên máy chủ chơi đơn: kiểm tra `native_window()` | |
| `native_hud` | Trong khi vẽ HUD (không áp dụng khi bật F1) | `batch`, `corners`: đặt hộp với `corners.place(corner, w, h)` |
| `native_key` | Một phím hoặc nút chuột được nhấn xuống hoặc nhả ra (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Con lăn chuột khi đang chơi | `steps`, `claimed`: đặt giá trị để con lăn không tác động đến thanh phím tắt |
| `native_command` | Một lệnh trò chuyện, không có dấu gạch chéo, trước khi máy chủ nhìn thấy | `command`, `ran`: đánh dấu khi đó là lệnh của bạn |
| `native_zoom` | Mỗi khung hình khi đang chơi | `zoom: &mut Option<NativeZoom>`: đặt giá trị để thu phóng |
| `native_minimap` | Mỗi khung hình | `minimap: &mut Option<NativeMinimap>`: đặt giá trị để hiển thị bản đồ nhỏ của Horizon |
| `native_mods` | Options > Mods liệt kê các mod | `mods`: thêm hàng và phím tắt của mod bạn |
| `native_settings` | Trang cài đặt của mod bạn mở ra | `mod_id`, `buttons`: thêm nhãn nút bấm |
| `native_settings_click` | Một nút bấm trên trang cài đặt của bạn được nhấp | `mod_id`, `button` |

Mỗi hook nhận `game` khi mục `bindings` yêu cầu: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`&crate::game::Game<'_>` và `&*self` cho `native_zoom` và `native_minimap`). Các tệp do bộ kit tạo ra thể hiện rõ `bindings` của từng hook.

### Các hàm hỗ trợ trò chơi

Gọi các hàm này trên `game` từ bất kỳ hook nào.

| Hàm hỗ trợ | Chức năng |
|---|---|
| `native_on(id)` | Người chơi có bật mod của bạn trong Options > Mods hay không. Kiểm tra hàm này đầu tiên trong mỗi hook |
| `native_playing()` | Đang chơi, không mở menu, khung trò chuyện hay biển báo nào |
| `native_window()` | Đây là trò chơi của cửa sổ hiển thị, không phải của máy chủ |
| `native_player()` | Người chơi trong thế giới: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, hoặc `None` |
| `native_frame_rate()` | FPS hiện tại, khung hình chậm nhất và 1% mức thấp nhất |
| `native_clicks()` | Lượt nhấp tấn công và sử dụng trong giây vừa qua |
| `native_mapping("key.jump")` | Điều khiển mặc định có đang được giữ hay không, và tên phím của nó |
| `native_mod_key(id, name, default)` | Phím mà người chơi đã chọn cho tên phím `name` của bạn |
| `native_chat(text)` | Một dòng trong khung trò chuyện của người chơi |
| `native_text(batch, text, [x, y], rgb, shadow)` | Văn bản trên HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Một hình chữ nhật trên HUD |
| `native_text_width(text)` | Độ rộng của văn bản, tính theo pixel giao diện (GUI) |
| `native_corner_shown(corner)` | Góc màn hình có đang trống hay không (F3 sẽ chiếm các góc trên cùng) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Đọc và lưu các cài đặt cho mod của bạn |
| `native_data_dir(id)` | Thư mục riêng của mod, dùng để lưu trữ các tệp riêng |

Các phiên bản Horizon mới hơn sẽ bổ sung thêm (bảng vật phẩm bên cạnh túi đồ, công thức chế tạo, cấp vật phẩm trong chế độ sáng tạo). Mod sử dụng những tính năng đó cần phiên bản Horizon tương ứng; **Kiểm tra và đóng gói** sẽ nhận biết và ghi thông tin đó vào mod.

### Ý tưởng để thử nghiệm

- **Bộ đếm CPS**: một dòng trên HUD với `native_clicks()`.
- **Hiển thị phím bấm (Keystrokes)**: các ô sáng lên khi `native_mapping("key.forward")` và các phím khác được nhấn giữ.
- **Ghi lại tọa độ khi chết**: ghi nhớ `native_player()` mỗi tick và thông báo vị trí của bạn khi nó chuyển thành `None`.
- **Máy tính**: `/calc 2+2`, đọc các số phía sau tên lệnh.
- **Bộ đếm thời gian phiên chơi**: đếm số tick trong `native_tick` và hiển thị thời gian đã chơi trong hộp HUD.

## Những gì mod chưa thể làm

Horizon cung cấp cho mod native các hook point nêu trên, chứ không phải toàn bộ trò chơi. Các mod hiện chưa thể:

- thêm khối (block), vật phẩm (item), sinh vật (mob), công thức hoặc kết cấu (texture) (đó là các mod nội dung, `.hmod`, bộ kit này không tạo loại mod đó);
- thay đổi cách thế giới hoặc sinh vật hoạt động trên máy chủ;
- đọc thế giới xung quanh người chơi (khối, sinh vật, vật phẩm) hoặc túi đồ của người chơi;
- gửi tin nhắn trò chuyện hoặc lệnh tới máy chủ.

Khi Horizon bổ sung thêm hook point, bộ kit có thể sử dụng chúng: các bản cập nhật của ứng dụng sẽ mang chúng đến trình tạo.

## Sự cố thường gặp

- **Biên dịch của launcher thất bại:** trang Mods của launcher sẽ hiển thị những dòng cuối cùng của thông báo lỗi. Hãy chạy **Kiểm tra và đóng gói** khi đã cài đặt Rust để xem toàn bộ lỗi cùng tên tệp và số dòng. Hãy sao chép lỗi vào một GitHub issue nếu bạn gặp khó khăn.
- **"Not packed: TODO":** bộ đóng gói từ chối mã có chứa `TODO` (đó là cách đánh dấu các mod chuyển đổi chưa hoàn thiện). Hãy hoàn thiện hoặc xóa các dòng đó.
- **Phím của tôi không có tác dụng:** kiểm tra trong **Options > Mods > mod của bạn**: có thể một mod khác hoặc Horizon đang dùng cùng phím đó.
- **Lệnh của tôi lại gửi lên máy chủ:** id mod và tên lệnh đều là chữ thường; hãy kiểm tra xem `/<mod id>` có liệt kê lệnh đó không.
