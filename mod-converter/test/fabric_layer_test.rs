// Stand-alone test of the Fabric API layer (src/horizonport/jfabricapi.rs): event order and the command engine,
// against a stand-in Game with the three helpers the layer uses. Run by test/run-test.sh when Rust is installed.
#![allow(dead_code)]
mod game {
    pub struct Game<'a> {
        pub window: bool,
        pub player: bool,
        pub chat: Vec<String>,
        pub log: Vec<String>,
        pub _l: std::marker::PhantomData<&'a ()>,
    }
    impl Game<'_> {
        pub fn native_window(&self) -> bool {
            self.window
        }
        pub fn native_player(&self) -> Option<()> {
            self.player.then_some(())
        }
        pub fn native_chat(&mut self, t: &str) {
            self.chat.push(t.to_string());
        }
    }
}

mod side {
    include!("../src/horizonport/jfabricapi.rs");

    fn started(g: &mut crate::game::Game<'_>) { g.log.push("started".into()); }
    fn ts(g: &mut crate::game::Game<'_>) { g.log.push("tick_start".into()); }
    fn te(g: &mut crate::game::Game<'_>) { g.log.push("tick_end".into()); }
    fn join(g: &mut crate::game::Game<'_>) { g.log.push("join".into()); }
    fn disc(g: &mut crate::game::Game<'_>) { g.log.push("disconnect".into()); }
    fn lvl(g: &mut crate::game::Game<'_>) { g.log.push("level".into()); }

    pub static J_FABRIC: JFabricEvents = JFabricEvents {
        started: &[started],
        tick_start: &[ts],
        join: &[join],
        disconnect: &[disc],
        level_tick_end: &[lvl],
        tick_end: &[te],
        ..JFabricEvents::NONE
    };

    fn root(g: &mut crate::game::Game<'_>) -> i32 { g.log.push("root".into()); 1 }
    fn size(g: &mut crate::game::Game<'_>) -> i32 { g.log.push(format!("size {}", j_cmd_arg_int("n"))); 1 }
    fn say(g: &mut crate::game::Game<'_>) -> i32 { g.log.push(format!("say [{}]", j_cmd_arg_string("text"))); 1 }
    fn name(g: &mut crate::game::Game<'_>) -> i32 { g.log.push(format!("name [{}] {}", j_cmd_arg_string("who"), j_cmd_arg_bool("loud"))); 1 }
    fn scale(g: &mut crate::game::Game<'_>) -> i32 { g.log.push(format!("scale {}", j_cmd_arg_double("f"))); 1 }

    pub fn commands() -> Vec<JCmdNode> {
        vec![JCmdNode::literal("cmds").executes(root)
            .then(JCmdNode::literal("size").then(JCmdNode::argument("n", JArgType::Int(1, 16)).executes(size)))
            .then(JCmdNode::literal("say").then(JCmdNode::argument("text", JArgType::Greedy).executes(say)))
            .then(JCmdNode::literal("name").then(JCmdNode::argument("who", JArgType::Quotable)
                .then(JCmdNode::argument("loud", JArgType::Bool).executes(name))))
            .then(JCmdNode::argument("f", JArgType::Double(0.0, 2.0)).executes(scale))]
    }
}

fn main() {
    use side::*;
    let mut g = game::Game { window: true, player: false, chat: vec![], log: vec![], _l: Default::default() };
    let mut fails = 0;
    let mut check = |what: &str, ok: bool| {
        println!("  {}: {what}", if ok { "ok" } else { "FAIL" });
        if !ok { fails += 1; }
    };

    // Events: title screen tick, join, a world tick, leave.
    j_fabric_frame(&mut g, &J_FABRIC);
    j_fabric_tick(&mut g, &J_FABRIC);
    check("first frame fires CLIENT_STARTED once, then a tick with no world", g.log == ["started", "tick_start", "tick_end"]);
    g.log.clear();
    g.player = true;
    j_fabric_tick(&mut g, &J_FABRIC);
    check("joining: START, JOIN, level tick, END", g.log == ["tick_start", "join", "level", "tick_end"]);
    g.log.clear();
    j_fabric_tick(&mut g, &J_FABRIC);
    check("in a world: no second JOIN", g.log == ["tick_start", "level", "tick_end"]);
    g.log.clear();
    g.player = false;
    j_fabric_tick(&mut g, &J_FABRIC);
    check("leaving: DISCONNECT, no level tick", g.log == ["tick_start", "disconnect", "tick_end"]);
    g.log.clear();
    g.window = false;
    j_fabric_tick(&mut g, &J_FABRIC);
    check("the integrated server's Game runs no client events", g.log.is_empty());
    g.window = true;

    // Commands.
    let roots = commands();
    let run = |g: &mut game::Game<'_>, c: &str| -> bool {
        g.log.clear();
        g.chat.clear();
        j_cmd_run(g, &roots, c)
    };
    check("other mods' commands are left alone", !run(&mut g, "gamemode creative") && g.log.is_empty());
    check("root runs", run(&mut g, "cmds") && g.log == ["root"]);
    check("literal + int argument", run(&mut g, "cmds size 12") && g.log == ["size 12"]);
    check("int out of range: Brigadier's error in chat", run(&mut g, "cmds size 40") && g.log.is_empty()
        && g.chat.len() == 1 && g.chat[0].contains("must not be more than 16"));
    check("not a number", run(&mut g, "cmds size x") && g.chat[0].contains("Expected integer"));
    check("incomplete command", run(&mut g, "cmds size") && g.chat[0].starts_with("Unknown or incomplete command"));
    check("greedy string keeps spaces", run(&mut g, "cmds say hello  there world") && g.log == ["say [hello  there world]"]);
    check("quoted string + bool", run(&mut g, "cmds name \"Steve \\\"S\\\" Smith\" true") && g.log == ["name [Steve \"S\" Smith] true"]);
    check("unquoted string + bool", run(&mut g, "cmds name alex false") && g.log == ["name [alex] false"]);
    check("argument at the root's level after literals", run(&mut g, "cmds 1.5") && g.log == ["scale 1.5"]);
    check("unknown word", run(&mut g, "cmds nope") && !g.chat.is_empty() && g.log.is_empty());
    check("extra spaces between words", run(&mut g, "cmds   size   3") && g.log == ["size 3"]);
    check("arguments are cleared after the command", std::panic::catch_unwind(|| j_cmd_arg_int("n")).is_err());

    println!("{}", if fails == 0 { "ALL OK" } else { "FAILURES" });
    std::process::exit(fails);
}
