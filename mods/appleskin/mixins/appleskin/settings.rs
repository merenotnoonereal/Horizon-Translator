// The mod's settings page (Options > Mods): one ON/OFF button per option of TOGGLES, under its keys.
fn appleskin_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {
    use crate::game::kit_appleskin as m;
    if mod_id != m::ID {
        return;
    }
    for &(key, label) in m::TOGGLES {
        let on = m::flag(key, |k| game.native_setting(m::ID, k));
        buttons.push(m::toggle_label(label, on));
    }
}
