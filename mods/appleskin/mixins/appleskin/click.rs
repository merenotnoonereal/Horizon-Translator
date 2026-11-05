// A click on the settings page: flips that option and keeps it.
fn appleskin_click(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {
    use crate::game::kit_appleskin as m;
    if mod_id != m::ID {
        return;
    }
    let Some(&(key, _)) = m::TOGGLES.get(button) else {
        return;
    };
    let on = !m::flag(key, |k| game.native_setting(m::ID, k));
    m::set_flag(key, on);
    game.native_set_setting(m::ID, key, m::stored(on));
}
