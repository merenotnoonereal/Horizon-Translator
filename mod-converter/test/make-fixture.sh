#!/usr/bin/env bash
# Writes the test fixture: a made-up Fabric mod ("Sneak HUD"), stand-ins for the game, Fabric API and
# Mixin APIs it compiles against, and a stand-in for Horizon's Rust source to test `match` on.
set -euo pipefail
cd "$(dirname "$0")"
F=fixture
S=$F/stubs
M=$F/mod/src/com/example/sneakhud
R=$F/mod/resources
H=$F/horizon-src/crates
rm -rf "$F"
mkdir -p $S/net/minecraft/client/player $S/net/minecraft/client/gui $S/net/minecraft/world/level/block \
  $S/net/minecraft/core/registries $S/net/fabricmc/api $S/net/fabricmc/fabric/api/event/player \
  $S/net/fabricmc/fabric/api/client/event/lifecycle/v1 $S/net/fabricmc/fabric/api/client/rendering/v1 \
  $S/net/fabricmc/fabric/api/client/keybinding/v1 $S/org/spongepowered/asm/mixin/injection/callback \
  $M/mixin $R/assets/sneakhud/lang $R/assets/sneakhud/textures/block $R/data/sneakhud/recipe \
  $R/data/sneakhud/loot_table/blocks $H/horizon-client/src/player $H/horizon-client/src/gui $H/horizon-server/src/entity

# ---- stand-ins (only their shapes matter; they are not put in the mod jar) ----
cat > $S/net/minecraft/client/Minecraft.java <<'EOF'
package net.minecraft.client;
import net.minecraft.client.player.LocalPlayer;
public class Minecraft { public LocalPlayer player; public Object font; public static Minecraft getInstance() { return null; } public void tick() {} }
EOF
cat > $S/net/minecraft/client/player/LocalPlayer.java <<'EOF'
package net.minecraft.client.player;
public class LocalPlayer { public boolean isShiftKeyDown() { return false; } public void aiStep() {} public void setSprinting(boolean on) {} public void resetFallDistance() {} }
EOF
cat > $S/net/minecraft/client/MouseHandler.java <<'EOF'
package net.minecraft.client;
public class MouseHandler { private void onScroll(long window, double x, double y) {} }
EOF
cat > $S/net/minecraft/client/KeyMapping.java <<'EOF'
package net.minecraft.client;
public class KeyMapping { public KeyMapping(String name, int key, String category) {} public boolean consumeClick() { return false; } }
EOF
cat > $S/net/minecraft/client/gui/GuiGraphics.java <<'EOF'
package net.minecraft.client.gui;
public class GuiGraphics { public void drawString(Object font, String text, int x, int y, int color) {} }
EOF
cat > $S/net/minecraft/world/level/block/Block.java <<'EOF'
package net.minecraft.world.level.block;
public class Block {
    public Block(Properties p) {}
    public int getLightEmission() { return 0; }
    public static class Properties { public static Properties of() { return new Properties(); } public Properties strength(float f) { return this; } }
}
EOF
cat > $S/net/minecraft/core/Registry.java <<'EOF'
package net.minecraft.core;
public interface Registry<T> { static <V, T extends V> T register(Registry<V> registry, String id, T value) { return value; } }
EOF
cat > $S/net/minecraft/core/registries/BuiltInRegistries.java <<'EOF'
package net.minecraft.core.registries;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
public class BuiltInRegistries { public static final Registry<Block> BLOCK = null; }
EOF
cat > $S/net/fabricmc/api/ClientModInitializer.java <<'EOF'
package net.fabricmc.api;
public interface ClientModInitializer { void onInitializeClient(); }
EOF
cat > $S/net/fabricmc/fabric/api/event/Event.java <<'EOF'
package net.fabricmc.fabric.api.event;
public abstract class Event<T> { public abstract void register(T listener); }
EOF
cat > $S/net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents.java <<'EOF'
package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
public final class ClientTickEvents { public static final Event<EndTick> END_CLIENT_TICK = null; public interface EndTick { void onEndTick(Minecraft client); } }
EOF
cat > $S/net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback.java <<'EOF'
package net.fabricmc.fabric.api.client.rendering.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.GuiGraphics;
public interface HudRenderCallback { Event<HudRenderCallback> EVENT = null; void onHudRender(GuiGraphics graphics, float delta); }
EOF
cat > $S/net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper.java <<'EOF'
package net.fabricmc.fabric.api.client.keybinding.v1;
import net.minecraft.client.KeyMapping;
public final class KeyBindingHelper { public static KeyMapping registerKeyBinding(KeyMapping key) { return key; } }
EOF
cat > $S/net/fabricmc/fabric/api/event/player/UseBlockCallback.java <<'EOF'
package net.fabricmc.fabric.api.event.player;
import net.fabricmc.fabric.api.event.Event;
public interface UseBlockCallback { Event<UseBlockCallback> EVENT = null; boolean interact(Object player); }
EOF
cat > $S/org/spongepowered/asm/mixin/Mixin.java <<'EOF'
package org.spongepowered.asm.mixin;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
public @interface Mixin { Class<?>[] value() default {}; String[] targets() default {}; }
EOF
cat > $S/org/spongepowered/asm/mixin/injection/At.java <<'EOF'
package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface At { String value(); String target() default ""; }
EOF
cat > $S/org/spongepowered/asm/mixin/injection/Inject.java <<'EOF'
package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface Inject { String[] method(); At[] at(); boolean cancellable() default false; }
EOF
cat > $S/org/spongepowered/asm/mixin/injection/Redirect.java <<'EOF'
package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface Redirect { String[] method(); At at(); }
EOF
cat > $S/org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java <<'EOF'
package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfo {}
EOF

# ---- the test mod ----
cat > $M/SneakHud.java <<'EOF'
package com.example.sneakhud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** Shows "Sneaking" on the HUD while sneaking; H toggles it. */
public class SneakHud implements ClientModInitializer {
    static KeyMapping toggle;
    static boolean enabled = true;
    public static final Block RUBY = Registry.register(BuiltInRegistries.BLOCK, "sneakhud:ruby_block", new RubyBlock());

    @Override
    public void onInitializeClient() {
        toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.sneakhud.toggle", 72, "key.categories.misc"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggle.consumeClick()) enabled = !enabled;
            if (client.player != null) client.player.resetFallDistance();
        });
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && mc.player != null && mc.player.isShiftKeyDown()) graphics.drawString(mc.font, "Sneaking", 4, 4, 0xFFFFFF);
        });
        UseBlockCallback.EVENT.register(player -> false);
    }
}
EOF
cat > $M/RubyBlock.java <<'EOF'
package com.example.sneakhud;

import net.minecraft.world.level.block.Block;

public class RubyBlock extends Block {
    public RubyBlock() { super(Properties.of().strength(3f)); }
    @Override public int getLightEmission() { return 7; }
}
EOF
cat > $M/mixin/MinecraftMixin.java <<'EOF'
package com.example.sneakhud.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sneakhud$onTick(CallbackInfo ci) {}
}
EOF
cat > $M/mixin/LocalPlayerMixin.java <<'EOF'
package com.example.sneakhud.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;setSprinting(Z)V"))
    private void sneakhud$beforeSprint(CallbackInfo ci) {}
}
EOF
cat > $M/mixin/MouseHandlerMixin.java <<'EOF'
package com.example.sneakhud.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Redirect(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isShiftKeyDown()Z"))
    private boolean sneakhud$scroll(Object player) { return false; }
}
EOF
cat > $R/fabric.mod.json <<'EOF'
{
  "schemaVersion": 1,
  "id": "sneakhud",
  "version": "1.0.0",
  "name": "Sneak HUD",
  "license": "MIT",
  "environment": "client",
  "entrypoints": { "client": ["com.example.sneakhud.SneakHud"] },
  "mixins": ["sneakhud.mixins.json"],
  "depends": { "fabricloader": ">=0.17", "minecraft": "26.3", "fabric-api": "*" }
}
EOF
cat > $R/sneakhud.mixins.json <<'EOF'
{ "required": true, "package": "com.example.sneakhud.mixin", "compatibilityLevel": "JAVA_21",
  "client": ["MinecraftMixin", "LocalPlayerMixin", "MouseHandlerMixin"] }
EOF
cat > $R/assets/sneakhud/lang/en_us.json <<'EOF'
{ "block.sneakhud.ruby_block": "Ruby Block", "key.sneakhud.toggle": "Toggle Sneak HUD" }
EOF
printf 'placeholder' > $R/assets/sneakhud/textures/block/ruby_block.png
cat > $R/data/sneakhud/recipe/ruby_block.json <<'EOF'
{ "type": "minecraft:crafting_shaped", "pattern": ["##", "##"], "key": { "#": "minecraft:redstone" }, "result": { "id": "sneakhud:ruby_block" } }
EOF
cat > $R/data/sneakhud/loot_table/blocks/ruby_block.json <<'EOF'
{ "type": "minecraft:block", "pools": [ { "rolls": 1, "entries": [ { "type": "minecraft:item", "name": "sneakhud:ruby_block" } ] } ] }
EOF

# ---- a stand-in shaped like Horizon's Rust source (the real source replaces it) ----
cat > $H/horizon-client/src/player/local_player.rs <<'EOF'
// Test fixture: shaped like Horizon's source, not Horizon's code.
pub struct LocalPlayer {
    sprinting: bool,
}

impl LocalPlayer {
    pub fn is_shift_key_down(&self) -> bool {
        false
    }

    pub fn set_sprinting(&mut self, on: bool) {
        self.sprinting = on;
    }

    pub fn ai_step(&mut self) {
        let _label = "{ a brace in a string }";
        if self.sprinting {
            self.set_sprinting(false);
        }
    }
}

fn helper_outside_impl() {}
EOF
cat > $H/horizon-client/src/gui/mod.rs <<'EOF'
// Test fixture.
pub struct GuiGraphics;

impl<'a> GuiGraphics {
    pub fn draw_string(&mut self, text: &str, x: i32, y: i32, color: u32) {}
}
EOF
mkdir -p $H/horizon-world/src
cat > $H/horizon-world/src/props.rs <<'EOF'
// Test fixture: a different name than vanilla's, found through the doc comment.
pub struct BlockProps;

/// `LocalPlayer.resetFallDistance`: the fall so far counts for nothing.
#[inline]
pub fn clear_fall(props: BlockProps) -> BlockProps {
    props
}

#[cfg(test)]
mod tests {
    /// `LocalPlayer.resetFallDistance` in a test.
    #[test]
    fn strength_rejects_negative() {}

    fn helper_in_tests() {}
}

pub fn after_tests() {}
EOF
cat > $H/horizon-server/src/entity/mob.rs <<'EOF'
// Test fixture: a decoy, the same function name on another type.
pub struct Mob;

impl Mob {
    pub fn ai_step(&mut self) {}
}
EOF
echo "Wrote $(find "$F" -type f | wc -l) fixture files"
