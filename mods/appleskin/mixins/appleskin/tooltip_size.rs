// A food's tooltip gets room for AppleSkin's rows: its hunger shanks and its saturation shanks. API 68.
fn appleskin_tooltip_size(
    game: &crate::game::Game<'_>,
    stack: &horizon_client::items::ItemStack,
    size: &mut Option<[f32; 2]>,
) {
    use crate::game::kit_appleskin as m;
    if size.is_some() || !game.native_on(m::ID) || !m::flag("tooltip", |k| game.native_setting(m::ID, k)) {
        return;
    }
    let Some((nutrition, saturation)) = game.items().get(&stack.item).and_then(|i| i.food) else {
        return;
    };
    let width = m::tooltip_texts(nutrition, saturation)
        .iter()
        .flatten()
        .map(|t| game.native_text_width(t))
        .fold(0.0, f32::max);
    *size = m::tooltip_size(nutrition, saturation, width);
}
