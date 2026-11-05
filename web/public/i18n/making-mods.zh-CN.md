# 制作 Horizon 模组

Horizon Translator 的**制作模组**标签页可为 [Horizon](https://github.com/horizon-rust/horizon)（Minecraft Java 版的 Rust 重写版）制作模组。您无需编写任何代码即可制作模组，也可以在之后查看并修改其代码以进一步深入开发。

- **无需代码：**勾选您模组所需的功能，填写表单并点击**制作模组**。即可获得现成的 `.hnmod` 文件。
- **附带代码：**每个模组都附带其 Rust 源码、测试配置以及检查按钮。您可以将其作为开发的起始模板包。
- **长久兼容：**制作工具生成的模组仅使用 Horizon 团队保证向后稳定的接口（即“挂钩点”），因此在更新版本的 Horizon 上启动器也能顺利构建。无需在每次 Horizon 更新时都重新制作新版本。
- **自动更新提醒：**应用在启动时会检查 GitHub 是否有新版本并提示您。新版本会为制作工具和指南带来更多新特性。

## 快速上手

1. 从网站（或 GitHub Releases 页面）下载适用于 Windows 或 Linux 的 Horizon Translator 并打开。
2. 打开**制作模组**标签页。在**起始模板**中选择一个示例并点击**使用**，或者从空白模组开始。
3. 为模组指定一个**名称**。**模组 ID** 会自动生成：同一模组的每个新版本请务必保持相同的模组 ID。
4. 勾选您需要的部分（HUD 信息框、聊天栏命令、按键、放大、聊天栏消息）并填写相应内容。
5. 保持勾选**添加到 Horizon**，然后点击**制作模组**。
6. 在 Horizon 启动器的**模组**页面中：点击“Horizon 原生构建”下的**构建**（如果未找到，请先进入**设置 > 游戏 > 原生模组**开启）。首次构建会自动下载 Rust，大约需要 20 到 30 分钟；后续构建仅需几分钟。
7. 开始游玩。在聊天栏中输入 `/<您的模组ID>` 即可查看模组的命令与按键绑定。

您的模组会保存在 `Documents/Horizon Translator/<mod id>/` 目录下（可在应用底部修改保存目录）。

## 无需编写代码即可制作的功能

### HUD 信息框

在屏幕角落显示的文本，例如坐标、朝向或当前 FPS。每一行都可以将纯文本与占位符（见下文）自由组合。按下 F1 时信息框会自动隐藏，并且会避开 F3 调试屏幕的位置。您可以自定义文本颜色、是否显示深色半透明背景底框，以及设置用于显示或隐藏它的快捷键。玩家也可以在**选项 > 模组**中将其完全关闭。

### 聊天栏命令

玩家在聊天栏中输入的命令，例如 `/hello`。一条命令可以实现：

- **在聊天栏中发送：**显示一行或多行文本（在输入框中使用 `|` 分隔各行）。
- **显示 / 隐藏 HUD 信息框。**
- **放大 / 缩小。**

命令在玩家本地客户端运行，在服务端接收之前就会被拦截处理，因此可以在任何服务器上生效。请勿使用服务器已有的命令名称（如 `/help`、`/home`、`/spawn` 等），否则您的模组会将其覆盖。系统始终会自动添加 `/<mod id>` 命令：用于列出您的所有命令与按键。

### 按键

与命令具有相同功能的快捷键。玩家可以在**选项 > 模组 > 您的模组**中自定义每个按键绑定。按键仅在正常游玩中生效，在打字聊天或打开菜单时不会触发。

### 放大

类似 OptiFine 的放大按键：可设置放大倍数（1.5 到 10 倍）、仅按住时放大或按下切换开关、像望远镜一样平滑过渡，并在放大过程中支持鼠标滚轮微调放大倍率。放大状态下鼠标转动速度会自动放缓。

### 聊天栏消息

- **欢迎消息：**加入世界时在聊天栏中显示的文本。
- **定时消息：**游玩过程中每隔固定分钟数显示的文本（从加入世界起开始计时）。

消息仅在您自己的屏幕上显示，绝不会发送给服务器。

### 占位符

任何文本输入框中均可使用这些占位符。在显示文本时它们会被自动替换为实时数据。

| 占位符 | 显示内容 |
|---|---|
| `{x}` `{y}` `{z}` | 您的位置，保留一位小数：`12.3` |
| `{bx}` `{by}` `{bz}` | 您所在的方块坐标：`12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | 您的朝向视角，以度为单位 |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | 每秒帧数 |
| `{name}` `{version}` | 您的模组名称与版本 |

### 玩家可用的设置项

每个制作工具模组都会在**选项 > 模组**中拥有一行设置，包含其按键绑定以及针对各个组件（HUD 信息框、放大、欢迎消息、定时消息）的开关按钮。玩家也可以在此处直接关闭整个模组。

## 制作新版本

1. 点击**打开我制作的模组…**并选择您的模组文件夹。表单会自动填入您上次所选的内容。
2. 修改您想更改的内容，并提升**版本号**（例如 1.0.0 → 1.1.0）。
3. 点击**制作模组**。新版本会保存到一个新的文件夹中（如 `<mod id>-2` 等），绝不会覆盖您的旧文件，并会替换 Horizon 中的旧版 `.hnmod`。在启动器中再次构建即可。

请保持相同的**模组 ID**：Horizon 通过 ID 识别模组，这样可以完整保留玩家原有的设置和按键绑定。

## 分享您的模组

直接发送 `.hnmod` 文件即可。玩家可以通过启动器的模组页面安装（或将其复制到 Windows 上的 `%APPDATA%\horizon\horizon-native-mods\`，Linux 上的 `~/.local/share/horizon/horizon-native-mods/`）并构建。

原生模组会被直接编译进游戏中，以玩家自身的系统权限运行，不受沙盒环境限制。这也是为什么启动器在构建前会提示确认。请仅分享您知悉源码的模组，并且只安装来自可信作者的模组。

## 深入探索：代码开发

每个模组文件夹都是一个微型的 Rust 项目：

| 文件 | 说明 |
|---|---|
| `mixins/<id>/runtime.rs` | 模组核心。顶部为设置（文本、按键、命令），中部为逻辑，底部为测试 |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | 挂钩点：当 Horizon 调用时各自运行（按键、HUD、命令、tick） |
| `mixins/<id>/horizon-mixin.toml` | 配置哪个挂钩点运行哪个文件 |
| `Cargo.toml` | 允许通过 `cargo test` 独立于游戏运行 `runtime.rs` 中的测试 |
| `compatibility.json` | 模组所需的挂钩点版本以及制作时基于的 Horizon 提交记录 |
| `horizon-kit.json` | 您在制作工具表单中所选择的配置选项 |
| `<id>.hnmod` | 打包后的模组文件 |

若要修改代码：

1. 在任意编辑器（推荐使用带有 rust-analyzer 扩展的 VS Code）中编辑文件。
2. 在应用中：依次进入**制作模组 > 检查并打包文件夹…**，并选择您的模组文件夹。在安装了 Rust (rustup.rs) 的环境下，它会针对 Horizon 的模组辅助库编译您的代码并首先运行测试；未安装 Rust 时则直接打包，由启动器构建时报告语法错误。随后打包出新的 `.hnmod` 并添加到 Horizon 中。
3. 在启动器中构建并进入游戏体验。

**请勿对已手动修改代码的模组点击“制作模组”：**那将根据表单内容重新生成并覆盖代码。请使用“检查并打包”。

### 命令行工具（及面向 AI 编程智能体）

应用的 `horizon-port.jar` 可在终端中执行相同操作，并提供更全面的完整检查。每个模组文件夹都包含一个 `AGENTS.md`（包含面向 AI 编程智能体的命令说明）以及一个 `HOOKS.md`（列出所有挂钩点和辅助函数）。

| 命令 | 功能说明 |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | 根据制作工具配置生成模组（`kit-examples/` 中包含部分示例） |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | 列出该 Horizon 版本的挂钩点和辅助函数，以及每个挂钩的 `[[injection]]` |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | 完整检查：将模组放入 Horizon 副本中进行编译，与启动器的操作一致 |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | 完整检查、运行测试、打包并添加到 Horizon |

`SRC` 为 Horizon 的源代码目录，在执行 `compile` 时解压提供。`compile` 需要安装 Rust 以及启动器的 horizon-mixins 工具（在首次原生构建时会自动安装）；首次运行需要占用数 GB 空间并耗时数分钟。与**检查并打包**不同，它还会校验直接读取游戏底层数据的代码（如 `game.player`、`game.items()`…），这些并不属于公开挂钩点的一部分。

### 挂钩点的工作原理

Horizon 会在特定位置调用预设的空函数（即**挂钩点 (hook points)**）。`horizon-mixin.toml` 中的每一行 `[[injection]]` 都会将您的一个文件插入到这些函数的最前端。您的文件中包含一个函数，该函数接收 `bindings` 中列出的参数。例如 `key.rs` 接收游戏实例、按键名称以及该按键是按下还是释放：

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

`runtime.rs` 会作为模块被引入游戏（`crate::game::kit_<id>`），同时也是项目自身的独立库，因此任何不需要游戏上下文的代码（数学计算、文本格式化、模组状态数据）都可以放在此处并通过 `cargo test` 进行单元测试。

horizon-mixins 制定的规则：`runtime.rs` 中禁止使用 `//!` 顶级注释或 `#![...]` 模块属性，禁止使用 `mod x;` 外部文件引用以及 `include!` 宏。所有内容均需编写在单个文件中（内联声明 `mod x { ... }` 是允许的）。

### 挂钩点清单

这些是制作工具模组的核心构件。接口名称和参数在各 Horizon 版本之间保持稳定。

| 挂钩点 | Horizon 调用时机 | 传入参数 |
|---|---|---|
| `native_frame` | 每一帧，无论是否在世界中 | `elapsed: Duration` |
| `native_tick` | 游戏每个 tick（每秒 20 次），在单人服务端也会调用：需检查 `native_window()` | |
| `native_hud` | 绘制 HUD 时（F1 隐藏除外） | `batch`, `corners`：使用 `corners.place(corner, w, h)` 放置提示框 |
| `native_key` | 按键或鼠标按下或释放时（`key.keyboard.z`, `key.mouse.left`） | `key`, `pressed` |
| `native_wheel` | 游玩中滚动鼠标滚轮时 | `steps`, `claimed`：设置为 true 可阻止滚轮切换快捷栏 |
| `native_command` | 聊天栏命令（不含斜杠），在服务端接收之前 | `command`, `ran`：如果属于本模组命令则设置为 true |
| `native_zoom` | 游玩中的每一帧 | `zoom: &mut Option<NativeZoom>`：设置为进行缩放 |
| `native_minimap` | 每一帧 | `minimap: &mut Option<NativeMinimap>`：设置为显示 Horizon 的小地图 |
| `native_mods` | 选项 > 模组 列出模组时 | `mods`：添加您模组的配置行和按键绑定 |
| `native_settings` | 打开您模组的设置页面时 | `mod_id`, `buttons`：添加按钮标签 |
| `native_settings_click` | 点击您设置页面上的按钮时 | `mod_id`, `button` |

当在 `bindings` 中声明需要时，每个挂钩点均可获取 `game`：`{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }`（对于 `native_zoom` 和 `native_minimap` 为 `&crate::game::Game<'_>` 及 `&*self`）。制作工具生成的文件中展示了各自完整的 `bindings` 配置。

### 游戏辅助函数

可以在任意挂钩点中通过 `game` 调用这些辅助方法。

| 辅助函数 | 功能说明 |
|---|---|
| `native_on(id)` | 玩家是否在 选项 > 模组 中启用了您的模组。在每个挂钩中应优先检查 |
| `native_playing()` | 正在游玩，未打开任何菜单、聊天栏或告示牌 |
| `native_window()` | 当前为游戏客户端窗口，而非服务端 |
| `native_player()` | 处于世界中的玩家：`x`, `y`, `z`, `yaw`, `pitch`, `dimension`，或 `None` |
| `native_frame_rate()` | 当前 FPS、最慢帧时间以及 1% 低帧率 (1% low) |
| `native_clicks()` | 过去一秒内的攻击和使用点击次数 |
| `native_mapping("key.jump")` | 原版控制键是否处于按下状态及其按键名称 |
| `native_mod_key(id, name, default)` | 玩家为您自定义的按键 `name` 设置的绑定按键 |
| `native_chat(text)` | 在玩家聊天栏中显示一行文本 |
| `native_text(batch, text, [x, y], rgb, shadow)` | 在 HUD 上绘制文本 |
| `native_fill(batch, [x, y, w, h], argb)` | 在 HUD 上绘制矩形色块 |
| `native_text_width(text)` | 文本的宽度（以 GUI 像素为单位） |
| `native_corner_shown(corner)` | 某个角落是否空闲可用（F3 会占用上方两个角落） |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | 读取并保存您模组的配置项 |
| `native_data_dir(id)` | 您模组独有的专属目录，用于存放自带文件 |

更新版本的 Horizon 会加入更多特性（如物品栏旁的物品面板、合成配方、创造模式给予物品等）。使用这些新特性的模组需要对应版本的 Horizon 支持；**检查并打包**会自动识别并将其写入模组配置。

### 灵感点子

- **CPS 计数器：**在 HUD 上添加一行，通过 `native_clicks()` 显示点击率。
- **按键可视化 (Keystrokes)：**当按住 `native_mapping("key.forward")` 及其他移动键时高亮显示按键方块。
- **死亡坐标记录：**在每个 tick 中通过 `native_player()` 记录玩家位置，并在其变为 `None` 时提示死亡坐标。
- **计算器：**实现 `/calc 2+2`，解析命令名称后的算式并计算结果。
- **游玩时长计时器：**在 `native_tick` 中统计 tick 数量，并在 HUD 信息框中显示本次游玩时间。

## 模组目前尚未支持的功能

Horizon 仅向原生模组开放上述挂钩点，而非整个游戏的底层架构。模组目前尚无法实现：

- 添加方块、物品、生物、配方或纹理（这些属于内容模组 `.hmod`，本工具不制作此类模组）；
- 更改服务器上的世界生成机制或生物行为；
- 读取玩家周围的世界数据（方块、生物、掉落物）或玩家的物品栏；
- 向服务器发送聊天消息或服务端命令。

当 Horizon 未来开放更多挂钩点时，制作工具会跟进支持：应用的更新会将其同步至模组构建器中。

## 常见问题排查

- **启动器构建失败：**启动器的“模组”页面会显示报错信息的最后几行。在安装了 Rust 的环境中运行**检查并打包**，可以查看包含具体文件及行号的完整报错。如果无法解决，可将错误信息复制并提交至 GitHub Issue。
- **“未打包：TODO”：**打包工具会拒绝包含 `TODO` 标记的代码（这是未完成移植的标记方式）。请完善或删除相关行。
- **我的快捷键没有反应：**检查**选项 > 模组 > 您的模组**：其他模组或 Horizon 自身可能占用了相同的按键。
- **我的命令被发送到了服务器：**模组 ID 和命令名称必须全部为小写；请检查 `/<模组ID>` 帮助列表中是否正常列出了该命令。
