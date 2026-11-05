# Creación de mods para Horizon

La pestaña **Crear un mod** de Horizon Translator crea mods para [Horizon](https://github.com/horizon-rust/horizon), la reimplementación
en Rust de Minecraft Java. Puedes crear un mod sin escribir nada de código, y abrir su código fuente más adelante para ir más lejos.

- **Sin programar:** marca lo que hace tu mod, completa las casillas y pulsa **Crear mod**. Obtienes un archivo `.hnmod` listo para usar.
- **Con código:** cada mod incluye su código fuente en Rust, una configuración para pruebas y un botón de comprobación. Úsalo como plantilla inicial.
- **Sigue funcionando:** los mods del kit solo usan las partes de Horizon que su equipo mantiene estables para mods (los "puntos de enlace" o hook points),
  por lo que el lanzador los compila también en versiones más recientes de Horizon. No necesitas una nueva versión por cada actualización de Horizon.
- **Recibe actualizaciones:** la aplicación comprueba en GitHub si hay una nueva versión al iniciarse y te avisa. Las nuevas versiones aportan nuevas
  funciones al creador y a la guía.

## Inicio rápido

1. Descarga Horizon Translator para Windows o Linux desde el sitio web (o la página de Releases de GitHub) y ábrelo.
2. Abre la pestaña **Crear un mod**. Elige un ejemplo en **Empezar desde** y pulsa **Usar**, o empieza con el mod en blanco.
3. Asigna un **nombre** a tu mod. El **id del mod** se completa automáticamente: consérvalo idéntico para cada nueva versión del mismo mod.
4. Marca los elementos que desees (cuadro de HUD, comandos de chat, teclas, zoom, mensajes de chat) y complétalos.
5. Deja marcada la casilla **Añadirlo a Horizon** y pulsa **Crear mod**.
6. En la página de **Mods** del lanzador de Horizon: pulsa **Build** bajo "Native build of Horizon" (si no aparece, activa primero
   **Settings > Game > Native mods**). La primera compilación descarga Rust y
   tarda de 20 a 30 minutos; las compilaciones posteriores tardan unos pocos minutos.
7. Juega. Escribe `/<id de tu mod>` en el chat para ver los comandos y teclas de tu mod.

Tu mod se guarda en `Documents/Horizon Translator/<mod id>/` (puedes cambiar la carpeta en la parte inferior de la aplicación).

## Qué puedes crear sin escribir código

### Cuadro de HUD

Texto en una esquina de la pantalla, como tus coordenadas, hacia dónde miras o tus FPS. Cada línea puede combinar texto sin formato
con marcadores de posición (ver abajo). El cuadro se oculta con F1 y se aparta de la pantalla F3. Elige un color de texto, un
fondo oscuro detrás o no, y una tecla para mostrarlo u ocultarlo. Los jugadores también pueden desactivarlo en **Opciones > Mods**.

### Comandos de chat

Comandos que los jugadores escriben en el chat, como `/hello`. Un comando puede:

- **Decir en el chat:** mostrar una o más líneas (usa `|` entre líneas en la casilla).
- **Mostrar / ocultar el cuadro de HUD.**
- **Acercar / alejar zoom.**

Los comandos se ejecutan en el propio cliente del jugador, antes de que el servidor los reciba, por lo que funcionan en cualquier servidor. No reutilices
nombres de comandos propios del servidor (`/help`, `/home`, `/spawn`...): tu mod los interceptaría. Siempre se añade `/<mod id>`:
muestra una lista de tus comandos y teclas.

### Teclas

Teclas que realizan las mismas acciones que los comandos. Los jugadores pueden cambiar cada tecla en **Opciones > Mods > tu mod**. Las teclas solo
actúan mientras juegas, no mientras escribes en el chat ni con un menú abierto.

### Zoom

Una tecla de zoom como la de OptiFine: cuántas veces más cerca (de 1.5 a 10), solo mientras se mantiene pulsada o pulsar para activar / desactivar, suave como
el catalejo, y con la rueda del ratón acercando o alejando mientras haces zoom. El movimiento del ratón se ralentiza durante el zoom.

### Mensajes de chat

- **Mensaje de bienvenida:** líneas mostradas al unirte a un mundo.
- **Mensajes programados:** líneas mostradas cada cierta cantidad de minutos mientras juegas (contados desde que te unes al mundo).

Los mensajes solo se muestran en tu pantalla; nada se envía al servidor.

### Marcadores de posición

Cualquier texto puede contenerlos. Se completan dinámicamente cuando el texto se muestra.

| Marcador de posición | Muestra |
|---|---|
| `{x}` `{y}` `{z}` | Tu posición, con un decimal: `12.3` |
| `{bx}` `{by}` `{bz}` | El bloque en el que estás: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Hacia dónde miras, en grados |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Fotogramas por segundo |
| `{name}` `{version}` | El nombre y versión de tu mod |

### Ajustes que reciben los jugadores

Cada mod del kit añade una fila en **Opciones > Mods** con sus teclas y un botón de encendido/apagado para cada elemento (cuadro de HUD, zoom, mensaje
de bienvenida, mensajes programados). Los jugadores también pueden desactivar el mod completo desde allí.

## Crear una nueva versión

1. **Abrir un mod que creé...** y elige la carpeta de tu mod. El formulario se completará con lo que elegiste la última vez.
2. Modifica lo que desees y aumenta la **versión** (1.0.0 → 1.1.0).
3. **Crear mod**. La nueva versión se guarda en una carpeta nueva (`<mod id>-2`...), nunca sobre la anterior, y reemplaza al
   `.hnmod` antiguo en Horizon. Compila de nuevo en el lanzador.

Mantén el mismo **id del mod**: Horizon reconoce un mod por su id, conservando así los ajustes y teclas de los jugadores.

## Compartir tu mod

Envía el archivo `.hnmod`. Los jugadores lo instalan desde la página de Mods del lanzador (o lo copian en
`%APPDATA%\horizon\horizon-native-mods\` en Windows, `~/.local/share/horizon/horizon-native-mods/` en Linux) y compilan.

Los mods nativos se compilan dentro del juego y se ejecutan con los permisos del usuario; no están aislados en un entorno seguro. Por eso el lanzador
pide confirmación antes de compilar. Comparte únicamente mods cuyo código conozcas, e instala solo mods de personas de confianza.

## Ir más lejos: el código

Cada carpeta de mod es un pequeño proyecto de Rust:

| Archivo | Qué es |
|---|---|
| `mixins/<id>/runtime.rs` | El mod. Sus ajustes arriba (textos, teclas, comandos), su lógica y sus pruebas abajo |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Los hooks: cada uno se ejecuta cuando Horizon lo invoca (una tecla, el HUD, un comando, un tick) |
| `mixins/<id>/horizon-mixin.toml` | Qué punto de enlace ejecuta cada archivo |
| `Cargo.toml` | Permite que `cargo test` ejecute las pruebas en `runtime.rs` sin el juego |
| `compatibility.json` | La versión de puntos de enlace que el mod necesita y el commit de Horizon en el que se creó |
| `horizon-kit.json` | Lo que seleccionaste en el creador visual |
| `<id>.hnmod` | El mod empaquetado |

Para modificar el código:

1. Edita los archivos en cualquier editor (VS Code con la extensión rust-analyzer es una buena opción).
2. En la aplicación: **Crear un mod > Comprobar y empaquetar una carpeta...**, y elige la carpeta de tu mod. Con Rust instalado
   (rustup.rs), compila tu código con los módulos auxiliares de Horizon y ejecuta tus pruebas primero; sin Rust empaqueta
   directamente y la compilación del lanzador te informará de cualquier error. Luego empaqueta un nuevo `.hnmod` y lo añade a Horizon.
3. Compila en el lanzador y pruébalo.

**No uses Crear mod en un mod cuyo código modificaste a mano:** se reiniciaría desde el formulario. Usa Comprobar y empaquetar.

### Desde la línea de comandos (y para agentes de IA)

El archivo `horizon-port.jar` de la aplicación realiza lo mismo desde una terminal, y añade una verificación completa. Cada carpeta de mod contiene un
archivo `AGENTS.md` con estos comandos para agentes de código por IA, y un `HOOKS.md` que lista cada punto de enlace y función auxiliar.

| Comando | Qué hace |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Crea un mod a partir de una especificación del creador (`kit-examples/` tiene algunas) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Lista los puntos de enlace y funciones auxiliares de Horizon, con la `[[injection]]` de cada hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Comprobación completa: coloca el mod en una copia de Horizon y lo compila, igual que el lanzador |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Comprobación completa, pruebas, empaquetado y añadido a Horizon |

`SRC` es el código fuente de Horizon, descomprimido para `compile`. `compile` requiere Rust y la herramienta horizon-mixins del lanzador (instalada
con su primera compilación nativa); su primera ejecución descarga varios GB y tarda varios minutos. A diferencia de **Comprobar y empaquetar**, también comprueba
código que lee datos propios del juego (`game.player`, `game.items()`...), los cuales no forman parte de los puntos de enlace.

### Cómo funciona un hook

Horizon invoca funciones vacías en lugares fijos (los **puntos de enlace** o hook points). Cada línea `[[injection]]` en `horizon-mixin.toml`
inserta uno de tus archivos al principio de una de esas funciones. Tu archivo contiene una función, que recibe los valores
indicados bajo `bindings`. Por ejemplo, `key.rs` recibe el juego, el nombre de la tecla y si se pulsó o se soltó:

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

`runtime.rs` se añade al juego como módulo (`crate::game::kit_<id>`) y es a la vez la propia biblioteca del proyecto, de modo
que todo lo que no requiera el juego (matemáticas, texto, el estado de tu mod) va allí y se puede probar con `cargo test`.

Reglas que establece horizon-mixins: nada de comentarios `//!` ni atributos `#![...]` en `runtime.rs`, nada de archivos `mod x;` ni de
`include!`. Coloca todo en ese único archivo (se permite `mod x { ... }` en línea).

### Los puntos de enlace (hook points)

Estos son los bloques de construcción para los mods del kit. Sus nombres y argumentos se mantienen idénticos entre las diferentes versiones de Horizon.

| Punto de enlace | Cuándo lo invoca Horizon | Qué recibe |
|---|---|---|
| `native_frame` | En cada fotograma, estés o no dentro de un mundo | `elapsed: Duration` |
| `native_tick` | En cada tick del juego (20 por segundo), también en el servidor para un jugador: comprueba `native_window()` | |
| `native_hud` | Al dibujar la interfaz de HUD (no bajo F1) | `batch`, `corners`: ubica un cuadro con `corners.place(corner, w, h)` |
| `native_key` | Al presionar o soltar una tecla o botón del ratón (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Con la rueda del ratón mientras juegas | `steps`, `claimed`: establécelo para evitar que la rueda cambie la barra de acceso rápido |
| `native_command` | Un comando de chat, sin su barra inclinada, antes de que lo reciba el servidor | `command`, `ran`: establécelo cuando fue gestionado por tu mod |
| `native_zoom` | En cada fotograma mientras juegas | `zoom: &mut Option<NativeZoom>`: establécelo para aplicar zoom |
| `native_minimap` | En cada fotograma | `minimap: &mut Option<NativeMinimap>`: establécelo para mostrar el minimapa de Horizon |
| `native_mods` | Opciones > Mods lista los mods | `mods`: inserta la fila y las teclas de tu mod |
| `native_settings` | Al abrir la página de ajustes de tu mod | `mod_id`, `buttons`: inserta las etiquetas de los botones |
| `native_settings_click` | Al pulsar un botón en la página de ajustes de tu mod | `mod_id`, `button` |

Cada hook recibe `game` cuando su bloque `bindings` lo solicita: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }`
(`&crate::game::Game<'_>` y `&*self` para `native_zoom` y `native_minimap`). Los archivos generados por el kit muestran las
`bindings` de cada uno.

### Funciones auxiliares del juego

Llama a estas funciones en `game` desde cualquier hook.

| Función auxiliar | Qué hace |
|---|---|
| `native_on(id)` | Si el jugador dejó activo tu mod en Opciones > Mods. Compruébalo siempre al principio de cada hook |
| `native_playing()` | Si está jugando, sin ningún menú, chat ni cartel abierto |
| `native_window()` | Indica que este es el cliente gráfico del juego, no un servidor dedicado |
| `native_player()` | El jugador en un mundo: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, o `None` |
| `native_frame_rate()` | FPS actuales, el fotograma más lento y el percentil 1% más bajo |
| `native_clicks()` | Clics de ataque y uso registrados en el último segundo |
| `native_mapping("key.jump")` | Si se mantiene pulsado un control original del juego, y el nombre de su tecla |
| `native_mod_key(id, name, default)` | La tecla que el jugador configuró para tu tecla `name` |
| `native_chat(text)` | Envía una línea en el chat del jugador |
| `native_text(batch, text, [x, y], rgb, shadow)` | Dibuja texto en el HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Dibuja un rectángulo coloreado en el HUD |
| `native_text_width(text)` | Ancho del texto en píxeles de la interfaz gráfica (GUI) |
| `native_corner_shown(corner)` | Si una esquina está despejada (la pantalla F3 ocupa las superiores) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Lee y almacena los ajustes de tu mod |
| `native_data_dir(id)` | Carpeta propia de tu mod, para almacenar sus archivos |

Las versiones más recientes de Horizon añaden más funciones (un panel de objetos junto a los inventarios, recetas, entrega de objetos en modo creativo). Un mod que use
esas funciones necesitará esa versión de Horizon; **Comprobar y empaquetar** lo detecta y lo registra en el mod.

### Ideas para probar

- Un **contador de CPS**: una línea en el HUD con `native_clicks()`.
- **Visualizador de pulsaciones (keystrokes)**: casillas que se iluminan al mantener pulsado `native_mapping("key.forward")` y los demás controles.
- Un aviso con las **coordenadas de muerte**: guarda `native_player()` en cada tick y muestra dónde estabas cuando pase a ser `None`.
- Una **calculadora**: `/calc 2+2`, leyendo los números tras el nombre del comando.
- Un **temporizador de sesión**: cuenta los ticks en `native_tick` y muestra el tiempo jugado en el cuadro de HUD.

## Lo que los mods aún no pueden hacer

Horizon proporciona a los mods nativos los puntos de enlace descritos arriba, no el control total del juego. Los mods todavía no pueden:

- añadir bloques, objetos, criaturas (mobs), recetas ni texturas (esos son mods de contenido, `.hmod`, que este kit no crea);
- modificar el comportamiento del mundo o de las criaturas en un servidor;
- inspeccionar el entorno que rodea al jugador (bloques, criaturas, objetos caídos) ni el inventario del jugador;
- enviar mensajes de chat o comandos directamente al servidor.

A medida que Horizon incorpore más puntos de enlace, el kit podrá aprovecharlos: las actualizaciones de la aplicación los integrarán en el creador visual.

## Solución de problemas

- **La compilación en el lanzador falla:** la página de Mods muestra las últimas líneas del error. Ejecuta **Comprobar y empaquetar** con Rust
  instalado para ver el error completo indicando archivo y línea. Copia el error en un issue de GitHub si necesitas ayuda.
- **"No se empaquetó: TODO"**: el empaquetador rechaza código que contenga `TODO` (así se marca el código inacabado en mods convertidos).
  Completa o elimina esas líneas.
- **Mi tecla no hace nada:** comprueba en **Opciones > Mods > tu mod**: otro mod o el propio Horizon podrían tener asignada la misma tecla.
- **Mi comando se envía al servidor en su lugar:** los identificadores de mod y nombres de comando deben ir en minúsculas; verifica que `/<id del mod>` lo liste.
