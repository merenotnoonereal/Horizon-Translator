// The hunger bar, as AppleSkin draws it. Horizon lays out the health, armour and hunger rows as it always does
// (its own layout keeps the rows' heights and the hearts' blinking), then the exhaustion bar goes under the shanks
// and the saturation frames and the held food's flashing preview go over them. API 27.
fn appleskin_health_hud(
    game: &mut crate::game::Game<'_>,
    inputs: &horizon_client::hud_layout::HealthInputs,
    wi: i32,
    hi: i32,
    draws: &mut Option<Vec<horizon_client::hud_layout::GuiDraw>>,
) {
    use crate::game::kit_appleskin as m;
    use horizon_client::hud_layout::{Blend, GuiDraw};
    // Another mod drew the rows already, or this one is turned off.
    if draws.is_some() || !game.native_on(m::ID) {
        return;
    }
    let shown = m::Shown::read(|k| game.native_setting(m::ID, k));
    if !shown.any() {
        return;
    }
    let exhaustion = game.player.as_ref().map_or(0.0, |p| p.health.food.exhaustion);
    let held = game.held().and_then(|s| game.items().get(&s.item)).and_then(|i| i.food);
    let mut out = game.health_hud.layout(inputs, wi, hi);
    let is_shank = |d: &GuiDraw| matches!(d, GuiDraw::Sprite { sprite, .. } if sprite.starts_with("hud/food_empty"));
    let icons: Vec<(i32, i32)> = out
        .iter()
        .filter(|d| is_shank(d))
        .filter_map(|d| match d {
            GuiDraw::Sprite { x, y, .. } => Some((*x, *y)),
            _ => None,
        })
        .collect();
    let bar = m::Bar {
        food: inputs.food,
        saturation: inputs.saturation,
        exhaustion,
        held,
        hunger_effect: inputs.hunger,
        millis: inputs.millis,
    };
    let (under, over) = m::bar(&icons, &bar, shown);
    let gui = |d: m::Draw| match d {
        m::Draw::Sprite { name, x, y, color } => GuiDraw::Sprite {
            sprite: std::borrow::Cow::Borrowed(name),
            x,
            y,
            w: 9,
            h: 9,
            region: None,
            color,
            blend: Blend::Textured,
        },
        m::Draw::Fill { x0, y0, x1, y1, color } => GuiDraw::Fill { x0, y0, x1, y1, color },
        m::Draw::Text { text, x, y, color } => GuiDraw::Text {
            text: horizon_client::text::Text::literal(&text),
            x,
            y,
            color,
            shadow: true,
        },
    };
    let at = out.iter().position(is_shank).unwrap_or(out.len());
    out.splice(at..at, under.into_iter().map(gui));
    out.extend(over.into_iter().map(gui));
    *draws = Some(out);
}
