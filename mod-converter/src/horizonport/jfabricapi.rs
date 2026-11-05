// ---- Fabric API layer (jfabricapi.rs, shipped by horizon-port) ----
// Fabric's events and client commands, run on Horizon's hook points. Every ported mod gets the same copy; the
// translator fills `J_FABRIC` (which handlers each event runs) and `j_fabric_commands` (the command trees).
//
//   Fabric                                             Horizon
//   ClientLifecycleEvents.CLIENT_STARTED               the window's first frame (or tick)
//   ClientTickEvents.START_CLIENT_TICK / END_...       the window's tick hook, before / after the rest
//   ClientTickEvents.START_LEVEL_TICK / END_...        the same tick, while a world is loaded
//   ClientPlayConnectionEvents.JOIN / DISCONNECT       the tick a player appears / goes away
//   ClientCommandRegistrationCallback (Brigadier)      the command hook, parsed by `j_cmd_run`

/// A Fabric event handler, translated: it gets the window's Game.
pub type JFabricFn = for<'a, 'b> fn(&'a mut crate::game::Game<'b>);

/// A command's `executes`, translated: its result is Brigadier's (1 is success).
pub type JCmdFn = for<'a, 'b> fn(&'a mut crate::game::Game<'b>) -> i32;

/// The handlers each Fabric event runs, in the order the mod registered them.
pub struct JFabricEvents {
    pub started: &'static [JFabricFn],
    pub tick_start: &'static [JFabricFn],
    pub tick_end: &'static [JFabricFn],
    pub level_tick_start: &'static [JFabricFn],
    pub level_tick_end: &'static [JFabricFn],
    pub join: &'static [JFabricFn],
    pub disconnect: &'static [JFabricFn],
}

impl JFabricEvents {
    pub const NONE: JFabricEvents = JFabricEvents {
        started: &[],
        tick_start: &[],
        tick_end: &[],
        level_tick_start: &[],
        level_tick_end: &[],
        join: &[],
        disconnect: &[],
    };
}

static J_FABRIC_STARTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static J_FABRIC_IN_WORLD: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn j_fabric_run(game: &mut crate::game::Game<'_>, handlers: &[JFabricFn]) {
    for h in handlers {
        h(game);
    }
}

/// CLIENT_STARTED, once, in the window's Game.
fn j_fabric_started(game: &mut crate::game::Game<'_>, events: &JFabricEvents) {
    if !J_FABRIC_STARTED.swap(true, std::sync::atomic::Ordering::Relaxed) {
        j_fabric_run(game, events.started);
    }
}

/// The frame hook: CLIENT_STARTED fires on the window's first frame, in a world or not.
pub fn j_fabric_frame(game: &mut crate::game::Game<'_>, events: &JFabricEvents) {
    if game.native_window() {
        j_fabric_started(game, events);
    }
}

/// The tick hook: Fabric's client tick events, in Fabric's order. Client events belong to the window: in single
/// player the integrated server's Game ticks too, and runs none of them.
pub fn j_fabric_tick(game: &mut crate::game::Game<'_>, events: &JFabricEvents) {
    if !game.native_window() {
        return;
    }
    j_fabric_started(game, events);
    j_fabric_run(game, events.tick_start);
    let now = game.native_player().is_some();
    let was = J_FABRIC_IN_WORLD.swap(now, std::sync::atomic::Ordering::Relaxed);
    if now && !was {
        j_fabric_run(game, events.join);
    } else if was && !now {
        j_fabric_run(game, events.disconnect);
    }
    if now {
        j_fabric_run(game, events.level_tick_start);
        j_fabric_run(game, events.level_tick_end);
    }
    j_fabric_run(game, events.tick_end);
}

// ---- client commands (Brigadier) ----

/// An argument's type: Brigadier's IntegerArgumentType, StringArgumentType and the others, with their bounds.
#[derive(Clone, Debug)]
pub enum JArgType {
    Int(i32, i32),
    Long(i64, i64),
    Float(f32, f32),
    Double(f64, f64),
    Bool,
    /// StringArgumentType.word(): one word.
    Word,
    /// StringArgumentType.string(): one word, or "quoted text".
    Quotable,
    /// StringArgumentType.greedyString(): the rest of the line.
    Greedy,
}

/// A parsed argument's value.
#[derive(Clone, Debug, PartialEq)]
pub enum JArgValue {
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    Bool(bool),
    Str(String),
}

/// A node of a command tree: a literal word or a typed argument, what it runs when the command ends there, and
/// what may follow it.
pub struct JCmdNode {
    pub name: &'static str,
    pub arg: Option<JArgType>,
    pub exec: Option<JCmdFn>,
    pub children: Vec<JCmdNode>,
}

impl JCmdNode {
    /// `ClientCommands.literal(name)`
    pub fn literal(name: &'static str) -> Self {
        JCmdNode { name, arg: None, exec: None, children: Vec::new() }
    }

    /// `ClientCommands.argument(name, type)`
    pub fn argument(name: &'static str, arg: JArgType) -> Self {
        JCmdNode { name, arg: Some(arg), exec: None, children: Vec::new() }
    }

    /// `.executes(..)`
    pub fn executes(mut self, f: JCmdFn) -> Self {
        self.exec = Some(f);
        self
    }

    /// `.then(..)`
    pub fn then(mut self, child: JCmdNode) -> Self {
        self.children.push(child);
        self
    }
}

/// The command being run: its whole line and its parsed arguments, which `getInteger(ctx, "n")` and the other
/// getters read (translated code reaches them through `j_cmd_arg_*`).
#[derive(Default, Clone, Debug)]
pub struct JCmdRun {
    pub input: String,
    pub args: Vec<(&'static str, JArgValue)>,
}

static J_CMD_RUN: std::sync::Mutex<Option<JCmdRun>> = std::sync::Mutex::new(None);

fn j_cmd_arg(name: &str) -> Option<JArgValue> {
    let g = J_CMD_RUN.lock().unwrap_or_else(|e| e.into_inner());
    g.as_ref()?.args.iter().find(|(n, _)| *n == name).map(|(_, v)| v.clone())
}

fn j_cmd_missing(name: &str, kind: &str) -> ! {
    panic!("no {kind} argument \"{name}\" in this command")
}

pub fn j_cmd_arg_int(name: &str) -> i32 {
    match j_cmd_arg(name) {
        Some(JArgValue::Int(v)) => v,
        _ => j_cmd_missing(name, "integer"),
    }
}

pub fn j_cmd_arg_long(name: &str) -> i64 {
    match j_cmd_arg(name) {
        Some(JArgValue::Long(v)) => v,
        Some(JArgValue::Int(v)) => v as i64,
        _ => j_cmd_missing(name, "long"),
    }
}

pub fn j_cmd_arg_float(name: &str) -> f32 {
    match j_cmd_arg(name) {
        Some(JArgValue::Float(v)) => v,
        _ => j_cmd_missing(name, "float"),
    }
}

pub fn j_cmd_arg_double(name: &str) -> f64 {
    match j_cmd_arg(name) {
        Some(JArgValue::Double(v)) => v,
        _ => j_cmd_missing(name, "double"),
    }
}

pub fn j_cmd_arg_bool(name: &str) -> bool {
    match j_cmd_arg(name) {
        Some(JArgValue::Bool(v)) => v,
        _ => j_cmd_missing(name, "bool"),
    }
}

pub fn j_cmd_arg_string(name: &str) -> String {
    match j_cmd_arg(name) {
        Some(JArgValue::Str(v)) => v,
        _ => j_cmd_missing(name, "string"),
    }
}

/// `CommandContext.getInput()`: the command line as typed, without its slash.
pub fn j_cmd_input() -> String {
    J_CMD_RUN.lock().unwrap_or_else(|e| e.into_inner()).as_ref().map(|r| r.input.clone()).unwrap_or_default()
}

/// One argument read off the front of `rest`: its value and what's left, or why it doesn't fit.
fn j_cmd_parse(arg: &JArgType, rest: &str) -> Result<(JArgValue, usize), String> {
    let word_len = rest.find(' ').unwrap_or(rest.len());
    let word = &rest[..word_len];
    let range = |v: f64, lo: f64, hi: f64, what: &str| -> Result<(), String> {
        if v < lo {
            Err(format!("{what} must not be less than {lo}, found {v}"))
        } else if v > hi {
            Err(format!("{what} must not be more than {hi}, found {v}"))
        } else {
            Ok(())
        }
    };
    match arg {
        JArgType::Int(lo, hi) => {
            let v: i32 = word.parse().map_err(|_| format!("Expected integer, found '{word}'"))?;
            range(v as f64, *lo as f64, *hi as f64, "Integer")?;
            Ok((JArgValue::Int(v), word_len))
        }
        JArgType::Long(lo, hi) => {
            let v: i64 = word.parse().map_err(|_| format!("Expected long, found '{word}'"))?;
            if v < *lo || v > *hi {
                return Err(format!("Long must be between {lo} and {hi}, found {v}"));
            }
            Ok((JArgValue::Long(v), word_len))
        }
        JArgType::Float(lo, hi) => {
            let v: f32 = word.parse().map_err(|_| format!("Expected float, found '{word}'"))?;
            range(v as f64, *lo as f64, *hi as f64, "Float")?;
            Ok((JArgValue::Float(v), word_len))
        }
        JArgType::Double(lo, hi) => {
            let v: f64 = word.parse().map_err(|_| format!("Expected double, found '{word}'"))?;
            range(v, *lo, *hi, "Double")?;
            Ok((JArgValue::Double(v), word_len))
        }
        JArgType::Bool => match word {
            "true" => Ok((JArgValue::Bool(true), word_len)),
            "false" => Ok((JArgValue::Bool(false), word_len)),
            _ => Err(format!("Invalid boolean, expected true or false but found '{word}'")),
        },
        JArgType::Word => {
            if word.is_empty() {
                return Err("Expected a word".to_string());
            }
            Ok((JArgValue::Str(word.to_string()), word_len))
        }
        JArgType::Quotable => {
            let Some(body) = rest.strip_prefix('"') else {
                if word.is_empty() {
                    return Err("Expected a string".to_string());
                }
                return Ok((JArgValue::Str(word.to_string()), word_len));
            };
            let mut out = String::new();
            let mut chars = body.char_indices();
            while let Some((i, ch)) = chars.next() {
                match ch {
                    '\\' => match chars.next() {
                        Some((_, e)) => out.push(e),
                        None => break,
                    },
                    '"' => return Ok((JArgValue::Str(out), i + 2)),
                    _ => out.push(ch),
                }
            }
            Err("Unclosed quoted string".to_string())
        }
        JArgType::Greedy => Ok((JArgValue::Str(rest.to_string()), rest.len())),
    }
}

/// Walks the tree from `node` over `rest`: the function to run and the arguments met, or the error to show.
fn j_cmd_walk(node: &JCmdNode, rest: &str, args: &mut Vec<(&'static str, JArgValue)>) -> Result<JCmdFn, String> {
    let rest = rest.trim_start_matches(' ');
    if rest.is_empty() {
        return node.exec.ok_or_else(|| "Unknown or incomplete command".to_string());
    }
    // Literals first, as Brigadier prefers them; then arguments, in the order they were added.
    let word = rest.split(' ').next().unwrap_or("");
    for child in node.children.iter().filter(|c| c.arg.is_none()) {
        if child.name == word {
            return j_cmd_walk(child, &rest[word.len()..], args);
        }
    }
    let mut last_err = None;
    for child in node.children.iter() {
        let Some(arg) = &child.arg else { continue };
        match j_cmd_parse(arg, rest) {
            Ok((value, used)) => {
                let mark = args.len();
                args.push((child.name, value));
                match j_cmd_walk(child, &rest[used..], args) {
                    Ok(f) => return Ok(f),
                    Err(e) => {
                        args.truncate(mark);
                        last_err = Some(e);
                    }
                }
            }
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| "Incorrect argument for command".to_string()))
}

/// The command hook: runs `command` (typed without its slash) when its first word is one of `roots`. Returns
/// whether it was this mod's; a command it can't parse answers in chat with Brigadier's message.
pub fn j_cmd_run(game: &mut crate::game::Game<'_>, roots: &[JCmdNode], command: &str) -> bool {
    let first = command.split(' ').next().unwrap_or("");
    let Some(root) = roots.iter().find(|r| r.arg.is_none() && r.name == first) else {
        return false;
    };
    let mut args = Vec::new();
    match j_cmd_walk(root, &command[first.len()..], &mut args) {
        Ok(f) => {
            let before = J_CMD_RUN.lock().unwrap_or_else(|e| e.into_inner()).replace(JCmdRun { input: command.to_string(), args });
            f(game);
            *J_CMD_RUN.lock().unwrap_or_else(|e| e.into_inner()) = before;
        }
        Err(e) => game.native_chat(&format!("{e}: /{command}")),
    }
    true
}
