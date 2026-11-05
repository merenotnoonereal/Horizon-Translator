// Draws a food's AppleSkin rows in the room tooltip_size asked for. API 68.
fn appleskin_tooltip_draw(
    game: &crate::game::Game<'_>,
    stack: &horizon_client::items::ItemStack,
    batch: &mut horizon_client::hud::HudBatch,
    origin: [f32; 2],
) {
    use crate::game::kit_appleskin as m;
    if !game.native_on(m::ID) || !m::flag("tooltip", |k| game.native_setting(m::ID, k)) {
        return;
    }
    let Some((nutrition, saturation)) = game.items().get(&stack.item).and_then(|i| i.food) else {
        return;
    };
    let [ox, oy] = origin;
    for d in m::tooltip(nutrition, saturation, false) {
        match d {
            m::Draw::Sprite { name, x, y, .. } => {
                let at = [ox + x as f32, oy + y as f32, 9.0, 9.0];
                game.native_sprite(batch, name, [0.0, 0.0, 9.0, 9.0], at);
            }
            m::Draw::Fill { x0, y0, x1, y1, color } => {
                let rect = [ox + x0 as f32, oy + y0 as f32, (x1 - x0) as f32, (y1 - y0) as f32];
                game.native_fill(batch, rect, color);
            }
            m::Draw::Text { text, x, y, color } => {
                game.native_text(batch, &text, [ox + x as f32, oy + y as f32], color & 0x00FF_FFFF, true);
            }
        }
    }
}
