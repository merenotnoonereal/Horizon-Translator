# Horizon-Mods erstellen

Der Tab **Mod erstellen** von Horizon Translator erstellt Mods für [Horizon](https://github.com/horizon-rust/horizon), den Rust-Rewrite von Minecraft Java. Du kannst eine Mod ganz ohne Programmierkenntnisse erstellen und ihren Code später öffnen, um weiterzugehen.

- **Ohne Code:** Wähle aus, was deine Mod tut, fülle die Felder aus und klicke auf **Mod erstellen**. Du erhältst eine fertige `.hnmod`-Datei.
- **Mit Code:** Jede Mod enthält ihren Rust-Quellcode, ein Test-Setup und eine Schaltfläche zur Prüfung. Nutze sie als Starter-Kit.
- **Dauerhaft kompatibel:** Kit-Mods nutzen nur die Teile von Horizon, die das Team für Mods stabil hält (die "Hook Points"), sodass der Launcher sie auch bei neueren Horizon-Versionen baut. Du benötigst keine neue Version für jedes Horizon-Update.
- **Erhält Updates:** Die App sucht beim Start auf GitHub nach neuen Versionen und benachrichtigt dich. Neue Versionen bringen neue Funktionen für den Builder und die Anleitung.

## Schnellanleitung

1. Lade Horizon Translator für Windows oder Linux von der Website (oder der GitHub-Releases-Seite) herunter und öffne die App.
2. Öffne den Tab **Mod erstellen**. Wähle ein Beispiel unter **Starten mit** und klicke auf **Verwenden**, oder beginne mit der leeren Mod.
3. Gib deiner Mod einen **Namen**. Die **Mod-ID** wird automatisch ausgefüllt: Behalte sie für jede neue Version derselben Mod bei.
4. Aktiviere die gewünschten Bereiche (HUD-Box, Chat-Befehle, Tasten, Zoom, Chat-Nachrichten) und fülle sie aus.
5. Lass **Zu Horizon hinzufügen** aktiviert und klicke auf **Mod erstellen**.
6. Auf der Seite **Mods** im Horizon-Launcher: Klicke auf **Build** unter "Native build of Horizon" (falls nicht vorhanden, aktiviere zuerst **Einstellungen > Spiel > Native Mods**). Der erste Build lädt Rust herunter und dauert 20 bis 30 Minuten; spätere Builds dauern nur wenige Minuten.
7. Spielen. Gib `/<deine Mod-ID>` im Chat ein, um die Befehle und Tasten deiner Mod zu sehen.

Deine Mod wird in `Documents/Horizon Translator/<mod id>/` gespeichert (du kannst den Ordner unten in der App ändern).

## Was du ohne Code erstellen kannst

### HUD-Box

Text in einer Bildschirmecke, wie etwa deine Koordinaten, deine Blickrichtung oder deine FPS. Jede Zeile kann normalen Text mit Platzhaltern (siehe unten) kombinieren. Die Box wird bei F1 ausgeblendet und weicht dem F3-Bildschirm aus. Wähle eine Textfarbe, optional eine dunkle Box dahinter und eine Taste zum Ein- oder Ausblenden. Spieler können sie auch unter **Optionen > Mods** ausschalten.

### Chat-Befehle

Befehle, die Spieler im Chat eingeben, wie `/hello`. Ein Befehl kann:

- **Im Chat ausgeben:** Eine oder mehrere Zeilen anzeigen (verwende `|` zwischen Zeilen im Eingabefeld).
- **HUD-Box ein- / ausblenden.**
- **Vergrößern / verkleinern (Zoom).**

Befehle werden im Spiel des Spielers ausgeführt, bevor der Server sie sieht, und funktionieren daher auf jedem Server. Verwende keine Befehlsnamen des Servers (`/help`, `/home`, `/spawn`...): Deine Mod würde diese sonst überschreiben. `/<mod id>` wird immer hinzugefügt: Es listet deine Befehle und Tasten auf.

### Tasten

Tasten, die dieselben Aktionen wie Befehle ausführen. Spieler können jede Taste unter **Optionen > Mods > deine Mod** anpassen. Tasten funktionieren nur während des Spielens, nicht beim Schreiben im Chat oder in geöffneten Menüs.

### Zoom

Eine Zoom-Taste wie bei OptiFine: Vergrößerungsfaktor (1,5 bis 10), wahlweise nur bei gehaltener Taste oder Ein-/Ausschalten per Tastendruck, sanfter Übergang wie beim Fernrohr und Verändern des Zoomfaktors mit dem Mausrad während des Zoomens. Die Mausbewegung wird beim Zoomen verlangsamt.

### Chat-Nachrichten

- **Willkommensnachricht:** Zeilen, die beim Betreten einer Welt angezeigt werden.
- **Zeitgesteuerte Nachrichten:** Zeilen, die während des Spielens alle paar Minuten angezeigt werden (gezählt ab dem Betreten der Welt).

Nachrichten erscheinen nur auf deinem Bildschirm; es wird nichts an den Server gesendet.

### Platzhalter

Jeder Text kann diese Platzhalter enthalten. Sie werden beim Anzeigen des Textes ausgefüllt.

| Platzhalter | Zeigt |
|---|---|
| `{x}` `{y}` `{z}` | Deine Position, eine Nachkommastelle: `12.3` |
| `{bx}` `{by}` `{bz}` | Der Block, in dem du dich befindest: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Blickrichtung in Grad |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Bilder pro Sekunde |
| `{name}` `{version}` | Name und Version deiner Mod |

### Einstellungen für Spieler

Jede Kit-Mod hat eine Zeile in **Optionen > Mods** mit ihren Tasten und einem AN/AUS-Schalter für jeden Teil (HUD-Box, Zoom, Willkommensnachricht, zeitgesteuerte Nachrichten). Spieler können dort auch die gesamte Mod deaktivieren.

## Eine neue Version erstellen

1. **Erstellte Mod öffnen...** und den Ordner deiner Mod auswählen. Das Formular wird mit den Einstellungen vom letzten Mal ausgefüllt.
2. Nimm gewünschte Änderungen vor und erhöhe die **Version** (1.0.0 → 1.1.0).
3. **Mod erstellen**. Die neue Version wird in einen neuen Ordner (`<mod id>-2`...) gespeichert, überschreibt niemals den alten und ersetzt die alte `.hnmod` in Horizon. Baue sie im Launcher erneut.

Behalte dieselbe **Mod-ID** bei: Horizon erkennt eine Mod anhand ihrer ID, und die Einstellungen und Tasten der Spieler bleiben erhalten.

## Mod weitergeben

Sende die `.hnmod`-Datei weiter. Spieler installieren sie über die Mods-Seite des Launchers (oder kopieren sie unter Windows nach `%APPDATA%\horizon\horizon-native-mods\`, unter Linux nach `~/.local/share/horizon/horizon-native-mods/`) und bauen das Spiel.

Native Mods werden in das Spiel kompiliert und laufen mit den Rechten des Spielers; sie sind nicht in einer Sandbox isoliert. Deshalb fragt der Launcher vor dem Bauen nach. Teile nur Mods, deren Quellcode du kennst, und installiere nur Mods von Personen, denen du vertraust.

## Für Fortgeschrittene: Der Code

Jeder Mod-Ordner ist ein kleines Rust-Projekt:

| Datei | Beschreibung |
|---|---|
| `mixins/<id>/runtime.rs` | Die Mod. Einstellungen ganz oben (Texte, Tasten, Befehle), Logik in der Mitte und Tests ganz unten |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Die Hooks: Jeder wird ausgeführt, wenn Horizon ihn aufruft (Taste, HUD, Befehl, Tick) |
| `mixins/<id>/horizon-mixin.toml` | Welcher Hook Point welche Datei ausführt |
| `Cargo.toml` | Ermöglicht `cargo test`, um die Tests in `runtime.rs` ohne das Spiel auszuführen |
| `compatibility.json` | Die von der Mod benötigte Version der Hook Points und der Horizon-Commit, auf dem sie erstellt wurde |
| `horizon-kit.json` | Deine Auswahl im Builder |
| `<id>.hnmod` | Die gepackte Mod |

Um den Code zu bearbeiten:

1. Bearbeite die Dateien in einem beliebigen Editor (VS Code mit der Erweiterung rust-analyzer wird empfohlen).
2. In der App: **Mod erstellen > Ordner prüfen und packen...** und den Mod-Ordner auswählen. Wenn Rust installiert ist (rustup.rs), wird dein Code gegen Horizons Mod-Helfer kompiliert und deine Tests werden zuerst ausgeführt; ohne Rust wird sofort gepackt und der Build des Launchers zeigt etwaige Fehler an. Anschließend wird eine neue `.hnmod` gepackt und zu Horizon hinzugefügt.
3. Im Launcher bauen und ausprobieren.

**Verwende "Mod erstellen" nicht für eine Mod, deren Code du manuell geändert hast:** Dadurch wird das Formular zurückgesetzt. Verwende "Ordner prüfen und packen".

### Über die Befehlszeile (und für KI-Agenten)

Die `horizon-port.jar` der App führt dasselbe im Terminal aus und bietet eine vollständige Prüfung. Jeder Mod-Ordner enthält eine `AGENTS.md` mit diesen Befehlen für KI-Coding-Agenten sowie eine `HOOKS.md`, die jeden Hook Point und Helfer auflistet.

| Befehl | Funktion |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Erstellt eine Mod aus einer Builder-Spezifikation (`kit-examples/` enthält Beispiele) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Listet die Hook Points und Helfer dieser Horizon-Version auf, inklusive `[[injection]]` für jeden Hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Die vollständige Prüfung: Fügt die Mod in eine Kopie von Horizon ein und kompiliert sie wie der Launcher |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Vollständige Prüfung, Tests, Packen und Hinzufügen zu Horizon |

`SRC` ist Horizons Quelltext, entpackt für `compile`. `compile` benötigt Rust und das horizon-mixins-Werkzeug des Launchers (installiert durch dessen ersten nativen Build); der erste Durchlauf benötigt einige GB Speicherplatz und mehrere Minuten. Im Gegensatz zu **Ordner prüfen und packen** prüft es auch Code, der Spieldaten direkt ausliest (`game.player`, `game.items()`...), was nicht Teil der Hook Points ist.

### Funktionsweise eines Hooks

Horizon ruft an festen Stellen leere Funktionen auf (die **Hook Points**). Jede Zeile `[[injection]]` in `horizon-mixin.toml` platziert eine deiner Dateien am Anfang einer dieser Funktionen. Deine Datei enthält eine Funktion, welche die unter `bindings` aufgelisteten Werte erhält. Beispielsweise erhält `key.rs` das Spiel, den Namen der Taste und ob sie gedrückt oder losgelassen wurde:

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

`runtime.rs` wird als Modul (`crate::game::kit_<id>`) in das Spiel eingebunden und ist gleichzeitig die Bibliothek des Projekts. Alles, was das Spiel nicht benötigt (Mathematik, Text, Status deiner Mod), gehört dorthin und kann mit `cargo test` getestet werden.

Regeln von horizon-mixins: Keine `//!`-Kommentare oder `#![...]`-Attribute in `runtime.rs`, keine `mod x;`-Dateien und kein `include!`. Platziere alles in dieser einen Datei (inline `mod x { ... }` ist zulässig).

### Die Hook Points

Dies sind die Bausteine von Kit-Mods. Namen und Argumente bleiben über verschiedene Horizon-Versionen hinweg stabil.

| Hook Point | Wann Horizon ihn aufruft | Was übergeben wird |
|---|---|---|
| `native_frame` | Bei jedem Frame, in einer Welt oder im Menü | `elapsed: Duration` |
| `native_tick` | Bei jedem Spiel-Tick (20 pro Sekunde), auch auf dem Einzelspieler-Server: `native_window()` prüfen | |
| `native_hud` | Beim Zeichnen des HUDs (nicht bei F1) | `batch`, `corners`: Box platzieren mit `corners.place(corner, w, h)` |
| `native_key` | Eine Taste oder Maustaste wird gedrückt oder losgelassen (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Mausrad während des Spielens | `steps`, `claimed`: setzen, um das Weiterrad in der Schnellleiste zu blockieren |
| `native_command` | Ein Chat-Befehl ohne Schrägstrich, bevor der Server ihn sieht | `command`, `ran`: setzen, wenn es dein Befehl war |
| `native_zoom` | Bei jedem Frame während des Spielens | `zoom: &mut Option<NativeZoom>`: setzen zum Zoomen |
| `native_minimap` | Bei jedem Frame | `minimap: &mut Option<NativeMinimap>`: setzen, um Horizons Minimap anzuzeigen |
| `native_mods` | Optionen > Mods listet die Mods auf | `mods`: Zeile und Tasten deiner Mod hinzufügen |
| `native_settings` | Einstellungsseite deiner Mod wird geöffnet | `mod_id`, `buttons`: Beschriftungen der Schaltflächen übergeben |
| `native_settings_click` | Klick auf eine Schaltfläche deiner Einstellungsseite | `mod_id`, `button` |

Jeder Hook erhält `game`, wenn seine `bindings` dies verlangen: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }` (`&crate::game::Game<'_>` und `&*self` für `native_zoom` und `native_minimap`). Die vom Kit erzeugten Dateien zeigen die `bindings` für jeden Hook.

### Spiel-Helferfunktionen

Rufe diese Funktionen auf `game` aus beliebigen Hooks auf.

| Helferfunktion | Was sie tut |
|---|---|
| `native_on(id)` | Ob der Spieler deine Mod unter Optionen > Mods aktiviert gelassen hat. Prüfe dies stets zuerst in jedem Hook |
| `native_playing()` | Im Spielgeschehen, ohne geöffnetes Menü, Chat oder Schild |
| `native_window()` | Läuft im Fenster des Spielers, nicht auf einem dedizierten Server |
| `native_player()` | Der Spieler in einer Welt: `x`, `y`, `z`, `yaw`, `pitch`, `dimension` oder `None` |
| `native_frame_rate()` | Aktuelle FPS, langsamster Frame und 1%-Low |
| `native_clicks()` | Angriffs- und Aktionsklicks der letzten Sekunde |
| `native_mapping("key.jump")` | Ob eine Vanilla-Steuerungstaste gehalten wird, sowie deren Tastenname |
| `native_mod_key(id, name, default)` | Die Taste, die der Spieler für deinen Tastennamen `name` gewählt hat |
| `native_chat(text)` | Eine Nachricht im Chat des Spielers ausgeben |
| `native_text(batch, text, [x, y], rgb, shadow)` | Text auf dem HUD anzeigen |
| `native_fill(batch, [x, y, w, h], argb)` | Ein Rechteck auf dem HUD zeichnen |
| `native_text_width(text)` | Breite eines Textes in GUI-Pixeln |
| `native_corner_shown(corner)` | Ob eine Bildschirmecke frei ist (F3 belegt die oberen Ecken) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Einstellungen deiner Mod lesen und speichern |
| `native_data_dir(id)` | Eigener Ordner deiner Mod für benutzerdefinierte Dateien |

Neuere Horizon-Versionen bieten weitere Helfer (Gegenstandsleiste neben Inventaren, Rezepte, Vergeben von Gegenständen im Kreativmodus). Eine Mod, die diese nutzt, benötigt die entsprechende Horizon-Version; **Ordner prüfen und packen** erkennt dies und trägt es in die Mod ein.

### Ideen zum Ausprobieren

- Ein **CPS-Zähler**: Eine HUD-Zeile mit `native_clicks()`.
- **Tastenanzeige (Keystrokes)**: Kästchen, die aufleuchten, während `native_mapping("key.forward")` und die anderen Tasten gehalten werden.
- Eine **Todeskoordinaten-Notiz**: `native_player()` in jedem Tick abfragen und die Position ausgeben, sobald der Wert `None` wird.
- Ein **Taschenrechner**: `/calc 2+2`, der die Zahlen nach dem Befehlsnamen liest.
- Ein **Sitzungstimer**: Ticks in `native_tick` zählen und die Spielzeit in der HUD-Box anzeigen.

## Was Mods noch nicht können

Horizon stellt nativen Mods die obigen Hook Points bereit, nicht das gesamte Spiel. Mods können derzeit noch nicht:

- Blöcke, Gegenstände, Mobs, Rezepte oder Texturen hinzufügen (dies sind Content-Mods, `.hmod`, die dieses Kit nicht erstellt);
- die Funktionsweise der Spielwelt oder von Mobs auf einem Server verändern;
- die Welt um den Spieler herum (Blöcke, Mobs, Items) oder das Inventar des Spielers auslesen;
- Chat-Nachrichten oder Befehle an den Server senden.

Wenn Horizon neue Hook Points hinzufügt, kann das Kit diese nutzen: App-Updates bringen sie in den Builder.

## Problemlösung

- **Der Build des Launchers schlägt fehl:** Die Mods-Seite zeigt die letzten Zeilen des Fehlers an. Führe **Ordner prüfen und packen** mit installiertem Rust aus, um die vollständige Fehlermeldung mit Datei und Zeile zu sehen. Kopiere den Fehler in ein GitHub-Issue, wenn du nicht weiterkommst.
- **"Nicht gepackt: TODO":** Der Packer lehnt Code ab, der `TODO` enthält (so werden unfertige konvertierte Mods markiert). Entferne oder vervollständige diese Zeilen.
- **Meine Taste macht nichts:** Überprüfe **Optionen > Mods > deine Mod**: Möglicherweise verwendet eine andere Mod oder Horizon dieselbe Taste.
- **Mein Befehl wird an den Server gesendet:** Mod-IDs und Befehlsnamen werden kleingeschrieben; prüfe, ob `/<mod id>` den Befehl auflistet.
