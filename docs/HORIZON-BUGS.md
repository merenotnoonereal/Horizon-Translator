# Draft bug report for Horizon (for the user to post)

Not posted. Eleven separate issues; they could be filed as separate reports. 6-11 were found 2026-10-31 evening by comparing every pack in Horizon with the same pack in Java 26.2 + Iris 1.11.4.
Line numbers are from source `a46ce68`; all were seen in game on `nightly-2026-10-31-7aa0e28` (Windows 11, RTX 5060, driver 610.88).

---

## 1. Shader packs: view bobbing is in `gbufferProjection`, so lighting moves with bobbing

**What happens:** with any Iris/OptiFine shader pack and View Bobbing on, shadows and lighting sway while walking.
Turning View Bobbing off fixes it.

**Why:** `crates/horizon-client-render/src/gfx/pack_uniforms.rs` (`FrameUniforms::update`, ~line 285):

```rust
let projection = Mat4::perspective_rh_gl(fov, aspect, 0.05, depth_far) * frame.bob * frame.spin;
```

Packs treat `gbufferProjection` as a plain perspective matrix. They invert it to rebuild view-space positions
from depth, and then go to world and shadow space through `gbufferModelViewInverse`. With the bob in the
projection, those positions sway with the bob, so shadow lookups and lighting move.

**Suggested fix:** when a pack is on, keep `gbufferProjection` a plain perspective matrix and put the bob
into the model-view instead: `gbufferModelView = bob * view`. Do the same for the previous-frame matrices and for the
matrix the pack's terrain draw uses, so `gl_ProjectionMatrix * gl_ModelViewMatrix` is unchanged on screen.
As far as I remember, Iris does the same with a mixin that moves bobbing and nausea into the model-view matrix
(`MixinModelViewBobbing`; not checked against current Iris source).

---

## 2. Shader packs: every terrain vertex gets normal (0,1,0)

**What happens:** with a pack, the sides of blocks are lit as if they faced up. Normal maps are wrong too.

**Why:** `crates/horizon-client-render/src/gfx/pack_atlas.rs`, `terrain_inputs` (~line 183): the non-flat
path sets

```glsl
hz_Normal = vec3(0.0, 1.0, 0.0);
hz_tangent = vec4(1.0, 0.0, 0.0, 1.0);
```

Only the flat path (`FACE`, ~line 130) computes real normals, and on this GPU
`horizon-ctl section-draws flat` replies "flat section draws are off on this device", so every pack gets up normals.

**How to see it:** a tiny pack whose `gbuffers_terrain` writes `normalize(mat3(gbufferModelViewInverse) * gl_NormalMatrix * gl_Normal) * 0.5 + 0.5`
as colour: every face comes out light green (0.5, 1, 0.5).

**Suggested fix:** pass the face normal (and tangent) in the indirect section vertex data, or derive it per
face as the flat path does.

Workaround on our side: horizon-shaderport's `face-normals` rule rebuilds the face normal in the
fragment shader from screen-space derivatives.

---

## 3. Crash: gbuffers program with an output past its `RENDERTARGETS`

**What happens:** Horizon crashes as soon as the world draws with Photon 1.4 (after its terrain program compiles):

```
In a CommandEncoder, label = 'Section uploads'
  In a set_pipeline command
    Render pipeline targets are incompatible with render pass
      Incompatible color attachments at indices []: the RenderPass uses textures with formats
      [Some(Rgba16Unorm)] but the RenderPipeline with 'gbuffers_terrain' label uses attachments
      with formats [Some(Rgba16Unorm), None]
```

**Why:** `pack_post.rs` (~line 822) builds `targets` with one entry per output location and `None` where
`p.buffers` has no buffer for it. Photon's terrain declares `layout(location = 1) out vec4 gbuffer_data_1;` but
writes `/* RENDERTARGETS: 1 */` unless normal or specular mapping is on. The geometry passes only attach the
written buffers, so the trailing `None` makes the pipeline and pass incompatible, and the wgpu error is fatal.

**Suggested fix:** drop trailing `None` targets (GL ignores writes to outputs with no draw buffer), or skip the
program with a note rather than crash.

## 4. 3D textures: every pack texture is bound as 2D

**What happens:** Noble (`deferred2`, `composite1`, `composite2`) and Photon (`deferred`, `deferred1`, `deferred4`) are skipped:
`WrongTextureViewDimension { dim: D3 }`. Mellow's 3D textures work.

**Why (as far as I can tell):** the packs declare `uniform sampler3D x;` in programs where no
`texture.<stage>.x … TEXTURE_3D` applies (e.g. Noble `texture.deferred.colortex10.1` is only for `deferred1`,
but `deferred2` declares it too), so Horizon binds a 2D texture there. GL accepts the mismatch (the sampler reads
as incomplete); wgpu refuses the pipeline.

**Suggested fix:** bind a 1×1×1 placeholder 3D texture for `sampler3D`s that have no 3D texture. Also worth checking
how `.1`-suffixed names (`texture.deferred.colortex6.1`) are read.

## 5. Uniform defaults ignored; some uniforms easy to supply

Uniforms not supported yet are zero ("zero for now"), even when the pack declares a default
(`uniform float cloudHeight = 192.0;`, `uniform bool heavyFog = false;`), which GL keeps when nothing sets it.
`alphaTestRef` at zero means see-through texels are never discarded: Mellow's first-person arm draws black (the
skin's outer layer is see-through, black pixels). Easy ones: `alphaTestRef` (0.1 for cutout programs),
`cameraPositionInt`/`cameraPositionFract` (and previous), `eyePosition`, `playerLookVector`, `farPlane`,
`gbufferPreviousModelViewInverse`, `isRightHanded`, `cloudHeight`.
horizon-shaderport works around all of these (rules `zero-uniforms`, `drop-unbuffered-outputs`).

**Update (source 7aa0e28):** `pack_post.rs` `texture_entry` makes every pack texture binding `D2`, and `source()`
maps custom-texture names (`texture.<stage>.<name>` in shaders.properties) to buffers or the white stand-in: custom
texture files (PNG or raw `.dat`) are not loaded at all. So any program that samples a `sampler3D` is refused, and
2D custom textures (lookup tables, noise images) read buffers or white. horizon-shaderport works around the 3D case
(rule `stand-in-3d`: data rebuilt from the pack's `.dat` as a shrunk table or matched noise).

## 6. `normals` and `specular` read a white stand-in

**What happens:** packs that read the PBR atlases (10 of the 12 tested: BSL, Bliss, both Complementary, Mellow, Noble,
Solas, Photon, Rethinking Voxels, SDV) see every block as a perfect mirror with a bent normal. Noble showed mirror
sheen and black reflection patches on stone.

**Why:** `pack_post.rs` `source()`: `normals`/`specular` fall to `Source::Dummy`, a 1×1 white texture. Iris, with no
PBR resource pack, gives a flat normal (0.5, 0.5, 1, 1) and no specular (0, 0, 0, 0).

**Suggested fix:** bind 1×1 stand-ins with those values for `normals` and `specular` (white stays right for others).
Workaround: horizon-shaderport pass `pbr-defaults`.

## 7. Custom uniforms read matrix elements transposed

**What happens:** packs that build vectors from matrix elements in `shaders.properties`
(`gbufferModelViewInverse.0.0 * x + gbufferModelViewInverse.1.0 * y + ...`, Noble's and Photon's sun vectors) get them
rotated the wrong way.

**Why:** `custom.rs` reads `m.a.b` as row a, column b (`v[column * 4 + row]`). Iris (JOML `m01` = column 0, row 1) and the
packs read it as column a, row b. A test against the crate: `m.0.1` returns column 1, row 0.

**Suggested fix:** `Some((column, Some(row))) => v.v[column * 4 + row]`. Workaround: pass `matrix-order` swaps the numbers.

## 8. Without flat section draws, packs get no `mc_Entity` and no normals

**What happens:** on most GPUs (here an RTX 5060, Vulkan), leaves, grass and other foliage look dark and grey in every
pack, and block-specific effects (waving plants, foliage lighting, emissive ores) never run.

**Why:** `pack_atlas.rs` `terrain_inputs`: the non-flat path sets `mc_Entity = vec4(-1.0, -1.0, 0.0, 0.0)` and the
normal to (0, 1, 0) (see #2). Flat draws (`flat::wanted`) are only on for Metal and one Intel driver unless
`HORIZON_FLAT_DRAWS=1`. With `HORIZON_FLAT_DRAWS=1` on this PC, BSL's leaves and grass match Iris.

**Suggested fix:** use flat draws whenever a shader pack is on and the device supports them, or pass the block id and
face normal in the per-section vertex data.

## 9. `separateAo` is ignored

`pack_atlas.rs` sets `hz_Color = vec4(hz_v_color.rgb, 1.0)`: ambient occlusion stays in the colour and alpha is 1.
10 of the 12 packs set `separateAo = true`, expecting the tint in `gl_Color.rgb` and AO in its alpha.

## 10. No compute programs

`*.csh` programs (and `prepare`/`deferred`/`composite` `_a`… compute passes) do not run. Noble computes its sun and
sky light in `prepare.csh` (without it the world is black); Photon its sky-light harmonics in `deferred4_a.csh`
(without it everything in shade is black). Workarounds: rule `compute-to-fragment` (small single-image programs become
fragment passes) and pass `compute-off` (turns off the pack option that enables an unconvertible one).

## 11. Shadow near plane sign

`shadow_camera`: `Mat4::orthographic_rh_gl(-d, d, -d, d, -near, far)` negates `shadowNearPlane`; Iris uses it as given
(Noble sets -1.0). Minor: it only shifts the depth range by 2 blocks.

## 12. Deferred/composite passes start on a cleared target

`pack_post.rs` begins each deferred/composite pass with `LoadOp::Clear` ("Overwritten whole: clearing lets a tiled GPU
skip loading the old content"). Iris keeps the target's content: a pass whose quad covers part of the screen leaves the
rest as it was, and `blend.<program>` blends with it. Noble's and Mellow's bloom draw one tile per pass into corners of
`colortex5` and blend the upsampling passes (`blend.composite16 = ONE SRC_ALPHA ONE ONE`). In Horizon every other tile
is wiped and the blends add to zero; Noble's auto-exposure reads that bloom, settles at 0.0066 instead of 0.00002 and
the picture is about 330 times too bright (measured against Java + Iris at the same spot and time).
**Suggested fix:** `LoadOp::Load` for passes whose vertex stage is not a full-screen quad or that have `blend.*`
(or always). Workaround: pass `post-keep`.

## 13. `gl_FragDepth` read without a write

GL: a fragment shader that never assigns `gl_FragDepth` outputs the fragment's own depth, and reading it gives that.
Noble's water reads it (`calculateShadowMapping(..., gl_FragDepth)`) without writing it. Horizon's translator leaves it to naga, whose
GLSL reader makes any mention a depth output (most likely starting at 0, the nearest depth), so water under the beach was drawn over the sand (only with the shadow pass on, where the read
is compiled in). **Suggested fix:** when a shader never assigns `gl_FragDepth`, replace reads with `gl_FragCoord.z` in the translator. Workaround: pass
`fragdepth-read`.

## 14. `size.buffer.*` is ignored

Every colortex buffer is screen-sized. Iris makes `size.buffer.colortex9 = 0.25 0.25` a quarter-size buffer, and a
pass writing it covers that small buffer, so its `gl_FragCoord` and its texture coordinates agree. In Horizon the pass
covers the whole screen: Photon's cloud pass (`deferred1`, quarter size by default) reads depth through its texture
coordinate, finds terrain everywhere and draws no cloud; its half-size ambient-occlusion buffers came out as a dark band
across the screen. **Suggested fix:** create such buffers at the scaled (or absolute) size and set the pass's viewport
to it. Workaround: pass `buffer-size` (the writing pass draws into the matching corner; reads are scaled).

## 15. `noisetex` ignores the pack's own noise image

`texture.noise = image/noise.png` (or `texture.<stage>.noisetex`) is not loaded; `noisetex` is always the game's
256×256 white noise with alpha 1 (`pack_post.rs` `noise()`). Packs that build clouds from their own smooth noise get
the wrong shapes: Photon reads its cloud shape from the alpha channel, so cover was total and shapeless, and the whole
scene sat in cloud shadow. **Suggested fix:** load the image named by `texture.noise` (part of #4's custom textures).
Workaround: pass `noise-2d` (a stand-in matched to the image's channels: value noise at the measured feature size, or
per-texel hash for white-noise channels).

## 16. The whole sky pass blends; vanilla draws the sky disc unblended

`gbuffers_skybasic` is drawn with the translucent default blend for every sky part (`post.rs`: "The sky blends as
vanilla draws its sunset fan and stars"). Vanilla only blends the sunset fan and the stars; the sky disc and the void
disc are drawn with blending off, so Iris' `gbuffers_skybasic` overwrites the buffer there. Mellow writes `vec4(0)`
from it to clear `colortex0` (which has `colortex0Clear = false`) and then adds its sky in `deferred1`; in Horizon the
alpha-0 write does nothing, each frame adds its sky onto the last, and the sky turns white. **Suggested fix:** draw the
`SKY` and `VOID` parts with blending off (unless `blend.gbuffers_skybasic` says otherwise) and only `SUNSET_*` and
`STAR` with the translucent blend. Workaround: pass `sky-blend` (adds `blend.gbuffers_skybasic=off` when the sky
program writes alpha 0 into a buffer the pack never clears, and clears that buffer each frame when nothing drawn before
the scene reads it).

## 17. `clouds=off` is ignored

`shaders.properties` `clouds=off` tells the game not to draw its clouds. Horizon never reads the key; it leaves its
clouds out only when the pack has a `gbuffers_clouds` program (`post.rs`: "gbuffers_clouds: no fallback, the game
draws its clouds otherwise"; `pack_post.rs` `hides_clouds`). Bliss draws volumetric clouds in its composite passes,
sets `clouds=off` and has no `gbuffers_clouds`, so the game's blocky clouds are drawn into its gbuffers and shaded as
dark terrain floating in the sky. **Suggested fix:** with `clouds=off` (or `clouds=fast`/`fancy` honoured as Iris
does), skip the game's clouds whether or not the pack has a clouds program. Workaround: pass `clouds-off` (adds an
empty `gbuffers_clouds`).
