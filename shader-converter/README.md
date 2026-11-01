# horizon-shaderport

Makes Iris/OptiFine shader packs run in [Horizon](https://github.com/horizon-rust/horizon).

Horizon already reads the `shaders/` pack format itself (crate `horizon-shaders`, a clean-room loader
that translates the GLSL with naga). But any program it can't translate gets **skipped**, and a pack
missing programs draws wrong or not at all. This tool:

1. runs Horizon's own loader offline (the same `load_post` the game calls, per dimension, no GPU),
   then the step the game does next: each stage is written as SPIR-V and its outputs are checked
   against the buffer formats (`src/gpu.rs`), because the loader alone passes programs the game's
   GPU compile then skips,
2. lists which programs it would skip and why,
3. rewrites the GLSL it trips on, one rule at a time. After each rule it reloads the pack and keeps
   the rule only if no program that loaded before now fails (if it does, the rule is retried on
   halves of the files it touched),
4. writes `<pack>-horizon.zip` and a report `<pack>-horizon.md` (what changed, what is still skipped).

Packs Horizon already loads completely get only the fixes for how Horizon draws (below).

## Build and run

Needs Rust (1.85+) and Horizon's source in `../horizon-src` (run `scripts/get-horizon.sh`); it builds against
Horizon's real `horizon-shaders` crate, so the check is exactly what the game does.

```bash
cargo build --release
```

```bash
./target/release/horizon-shaderport check "path/to/Pack.zip"
```

```bash
./target/release/horizon-shaderport port "path/to/Pack.zip" --out out --install
```

```bash
./target/release/horizon-shaderport port-all path/to/folder-of-packs --out out
```

`--install` copies the result into `%APPDATA%/horizon/shaderpacks`; pick it on
Options > Horizon Settings > Shader Packs. `port-all` also writes `out/SUMMARY.md`.

Debugging: `HZ_TRACE=1` prints each keep/revert decision, and
`horizon-shaderport apply <pack> <rule> --out DIR` applies one rule without checking it.
`tests/naga_probe.rs` tries GLSL snippets against naga directly (`cargo test --test naga_probe -- --nocapture`).

## The rules

All but `images-off` keep the GLSL's meaning; they only work around what Horizon's preprocessor
or naga's GLSL reader can't take.

| Rule | What Horizon/naga can't take | Rewrite |
|---|---|---|
| join-macro-calls | a function-like macro call split over several lines (Horizon's preprocessor expands one line at a time) | the call is joined onto one line; the line count is kept so error lines still map back |
| const-in-params | `const in` parameters | `in` |
| rename-sampler | an identifier named `sampler` (a type in Vulkan GLSL) | `hz_sampler_` |
| fog-frag-coord | `gl_FogFragCoord` | a varying of the pack's own |
| infinite-literal | `-1.0 / 0.0` | `-3.0e38` |
| texture-gather | `textureGather`, `textureGatherOffset(s)` | four `texelFetch`es (same texels, same order); shadow samplers left alone |
| vector-from-bool | `vec3(a < b)` | `vec3(float(a < b))` |
| const-mix | `mix()` in a `const` | `a + (b - a) * t` |
| vertex-texture-lod | `texture()` in a vertex shader (needs derivatives) | `textureLod(…, 0.0)`, as GL does there |
| code-after-return | statements after an unconditional `return` | `if (true) return …` |
| legacy-functions | `texelFetch2D`, `textureSize2D`… | `texelFetch`, `textureSize` |
| declare-missing | a variable declared only under `MC_NORMAL_MAP` etc., which Iris defines and Horizon doesn't | declared again where used (a varying stays a varying) |
| struct-varyings | a struct passed between stages | one varying per member, copied out/in in `main` |
| struct-array-ctor | `S(…, float[6](…))` (naga bug with array members) | member-by-member assignment |
| missing-return | a function that can reach its end without a value | `return TYPE(0);` at its end |
| images-off | images / storage buffers (Horizon has none yet) | the pack's own switch (`#define VX_SUPPORT`) turned off, taking the path it uses on GPUs without them. **Loses that feature** (usually voxel/colored lighting) |
| int-vector-modulo | `ivec2 % int` | `ivec2 % ivec2(int)` |
| out-arg-temporary | an `out`/`inout` argument that is part of a variable (`f(v.z)`, `f(s.field)`): naga's SPIR-V writer panics ("Expression [N] is not cached") when the game compiles it | copied through a temporary: `float t = v.z; f(t); v.z = t;` |
| pad-outputs | a fragment output with fewer components than its buffer (`vec3` into RGBA): wgpu refuses the pipeline (`IncompatibleFormat`) | the output becomes a plain variable; a `vec4` output at the same location gets it (alpha 1) after the pack's `main` |
| drop-unbuffered-outputs | a gbuffers/shadow program with an output past its `RENDERTARGETS` (Photon terrain without normal mapping): **Horizon crashes** (its geometry pass has no attachment there); GL drops such writes | for those programs (at the pack's default options) such outputs become plain variables |
| stand-in-3d | the game binds every pack texture as 2D and loads no custom texture files: a program sampling a `sampler3D` is refused (Noble, Photon) | each `sampler3D` becomes a stand-in built from the pack's own `.dat` file: a shrunk table (≤256 texels, linear) for smooth data such as Photon's atmosphere LUT, value noise at the file's feature size, average and contrast for noise volumes (clouds, fog). APPROXIMATE |
| compute-to-fragment | Horizon runs no compute programs (Noble works out its sun and sky light in `prepare.csh`: black world without it) | a small one (≤256 invocations, one `colorimgN`, no `imageLoad`) becomes the fragment program of the same name, run on a corner as large as its work; each pixel runs every invocation and keeps the `imageStore` aimed at it; everything runs twice so `shared` data written before a `barrier()` is there |

## Fixes for how Horizon draws

Also: launch Horizon with `HORIZON_FLAT_DRAWS=1` when using a pack. Without it Horizon gives packs no block ids
(`mc_Entity` = -1) and up-facing normals, so foliage is dark and grey in every pack (bug report #8).

These run on every pack, also ones Horizon loads fully, and are kept only if no program stops loading.

| Rule | Horizon bug | Rewrite |
|---|---|---|
| face-normals | where its "flat" section draws are off (seen on an RTX 5060, nightly 7aa0e28), every terrain vertex gets the normal `(0,1,0)` and tangent `(1,0,0)`, so block sides are lit like tops | in `gbuffers_terrain` and `gbuffers_water`: finds the varyings the vertex shader derives from `gl_Normal`/`at_tangent` by rotation only (and their space: model, view, world); the fragment shader rebuilds the real face frame from screen derivatives of the view position and rotates them before the pack's `main`. Lighting a pack does in the vertex shader (MakeUp terrain, Miniature) can't be fixed this way |

| zero-uniforms | uniforms it doesn't support yet are passed as zero ("zero for now" in its log) | rebuilt from supported ones where they follow from them (`cameraPositionFract` = `fract(cameraPosition)`, `cameraPositionInt`, the previous-frame pair, `eyePosition` = `cameraPosition` in first person, `playerLookVector`, `gbufferPreviousModelViewInverse`, `farPlane` = `far`); `alphaTestRef` gets Iris' 0.1 (at zero, see-through texels draw black: Mellow's hand), `isRightHanded` true, `cloudHeight` 192; a declared default (`uniform float x = 1.0;`, which GL keeps and Horizon ignores) becomes a constant |
| matrix-order | Horizon reads `m.a.b` in `shaders.properties` expressions as row a, column b; Iris (and packs) as column a, row b | the two numbers are swapped (Noble's and Photon's sun vectors) |
| pbr-defaults | `normals`/`specular` read a white 1×1 stand-in: every block a mirror with a bent normal | they become constants with Iris' no-PBR values (flat normal, no specular), read through `texture()` overloads |
| compute-off | a compute program `compute-to-fragment` can't convert, turned on by one pack option (`program.world0/deferred4_a.enabled = SH_SKYLIGHT`) | that option is turned off, so the pack uses its other path (Photon: sky light without harmonics). A FEATURE IS LOST |
| post-keep | Horizon clears the target of every deferred/composite pass (`LoadOp::Clear`); a pass that draws part of the screen (bloom tiles) wipes the rest, and `blend.*` blends into zero (Noble's auto-exposure read broken bloom: 330× too bright) | partial passes draw the whole screen and copy the old texel outside the pack's quad; blended passes blend in the shader with the buffer's current texel and their `blend.*` becomes `off` |
| fragdepth-read | a file reads `gl_FragDepth` but never writes it: GL gives the fragment's own depth; in Horizon the program writes an unset depth (Noble's water under the sand drawn over it) | such reads become `gl_FragCoord.z` |
| stand-in-3d (pass) | a 3D texture the load check accepts is still refused in game (`sampler3D colortex6` is not provided yet: Mellow's sky) | the `stand-in-3d` stand-ins also go in as a pass whenever nothing stops loading |
| noise-2d | the game loads no custom noise image: `noisetex` is its own 256×256 white noise with alpha 1 (Photon: cloud shape from alpha, so total, shapeless cover and the scene in cloud shadow) | `noisetex` becomes a stand-in built from the pack's `texture.noise` image, per channel: value noise at the measured feature size, average, contrast and range; a per-texel hash for white-noise channels; a constant for flat ones. Smooth channels go through the image's own value distribution (17 quantiles), so the top tail packs cut clouds from matches (Photon's coverage). A pack helper handed `noisetex` (Noble's `textureBicubic(sampler2D, …)`) gets a copy taking the stand-in; without it the declaration's file broke a program and the stand-in was dropped everywhere. It is a `bvec3` (naga treats two struct types as one overload). A channel that is an exact copy of an earlier one moved by whole texels (Bliss: green is red 193 rows on, for its 3D noise from one fetch) reuses that channel's noise at the same offset; the shortest offset is taken when the image repeats itself. APPROXIMATE |
| buffer-size | Horizon ignores `size.buffer.*`: every buffer is screen-sized, so a pass writing a scaled buffer covers the whole screen and its `gl_FragCoord` and texture coordinates disagree (Photon's quarter-size cloud pass found terrain everywhere: no clouds; half-size AO gave a dark band) | for relative scales below 1 (the pack's option defaults decide the `#if`): the writing passes draw into the matching corner; reads with a texture coordinate (`texture*`, gathers, the pack's own `vec2` helpers) are scaled to it, `textureSize` returns the small size, `texelFetch` is unchanged. All or nothing; a buffer read some other way is left alone |
| sky-blend | Horizon blends the whole `gbuffers_skybasic` pass; vanilla draws the sky disc unblended, so a pack that clears with an alpha-0 write gets nothing (Mellow: its sky added up frame after frame and went white) | when every output of the sky program writes a constant alpha 0 into a buffer the pack never clears: `blend.gbuffers_skybasic=off`, and that buffer is cleared each frame if it has its own clear colour and nothing drawn before the scene reads it |
| custom-tex2d | the game loads no custom texture files: `texture.<stage>.colortexN = image.png` leaves `colortexN` the pack's buffer (SuperDuperVanilla read its cloud map from the scene colour: every bright pixel a cloud, a pink haze over the sky) | a small pack image (up to 64 KiB packed) is built into a generated include exactly: per-channel palettes of 1, 2 or 8 bits in a `uint` array, `texelFetch`/`texture`/`textureLod`/`textureSize` overloads (nearest or bilinear and repeat or clamp from its `.mcmeta`); the sampler becomes a `bvec2`/`bvec4` constant only in programs that run before anything in that stage writes the buffer. All or nothing (deferred, composite, prepare) |
| clouds-off | `clouds=off` is not read: the game hides its clouds only when the pack has `gbuffers_clouds` (no fallback), so Bliss got the game's blocky clouds shaded as dark terrain in its sky | each dimension folder the game loads without `gbuffers_clouds` gets an empty one (vertex off screen, fragment discards); kept only if it loads |

Not fixable here (Horizon): 3D textures some packs bind for one program but sample in others
(Noble's clouds/fog, Photon's sky; `WrongTextureViewDimension`), shown as notes by `check`;
lighting packs do in the vertex shader (MakeUp terrain, Miniature) with the `(0,1,0)` normals;
images/storage buffers/geometry stages (Rethinking Voxels).

`debug-packs/normals-debug.zip` shows the normals as colours (left half): every face light green means Horizon's bug; after `port`, sides show pink/teal/lilac/olive.

## Results (Horizon nightly a46ce68, 12 most-downloaded Modrinth packs)

See `out/SUMMARY.md` after a run. What can't be fixed by rewriting needs Horizon features:
geometry stages, compute shaders (`.csh`), images and storage buffers. Packs built entirely on those
(Rethinking Voxels) stay "not yet".

"Loads" means Horizon translates and validates every program. It doesn't prove the pack looks right
in game: the renderer can still refuse a pipeline, and Horizon may not supply every buffer or uniform a
pack reads. Try it in game.

## Sharing

Sharing this tool is fine: it holds no pack code. Converted packs are for your own use. Most shader
pack licences (Complementary, BSL, Bliss, Solas…) forbid sharing modified copies.
