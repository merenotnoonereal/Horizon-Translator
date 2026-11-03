// The mod's chat commands (COMMANDS in runtime.rs) and /appleskin, which lists them. Horizon asks before the
// server sees a command: `ran` keeps it from going on.
fn appleskin_command(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {
    use crate::game::kit_appleskin as m;
    // Turned off in Options > Mods: nothing until the launcher's next build leaves the mod out.
    if !game.native_on(m::ID) {
        return;
    }
    let Some(action) = m::command(command) else {
        return;
    };
    *ran = true;
    match action {
        m::Action::Say(lines) => {
            let info = game.native_player().map(|p| m::Info::new((p.x, p.y, p.z, p.yaw, p.pitch, p.dimension), game.native_frame_rate().0));
            for line in lines {
                game.native_chat(&m::fill(line, info.as_ref()));
            }
        }
        m::Action::ToggleHud => {
            let on = !m::flag("hud", |k| game.native_setting(m::ID, k));
            m::set_flag("hud", on);
            game.native_set_setting(m::ID, "hud", m::stored(on));
        }
        m::Action::ToggleZoom | m::Action::HoldZoom => {
            if m::flag("zoom", |k| game.native_setting(m::ID, k)) {
                m::set_zoom(None);
            }
        }
        m::Action::Food => {
            let lines = match game.player.as_ref() {
                Some(p) => m::food_report(p.health.food.food, p.health.food.saturation, p.health.food.exhaustion),
                None => vec!["Join a world first.".to_string()],
            };
            for line in lines {
                game.native_chat(&line);
            }
        }
        m::Action::Help => {
            let lines = m::help(|name, default| m::key_label(&game.native_mod_key(m::ID, name, default)));
            for line in lines {
                game.native_chat(&line);
            }
        }
    }
}
