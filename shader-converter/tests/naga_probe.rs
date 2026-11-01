//! Probes what naga's GLSL reader accepts, for choosing rewrites. `cargo test --test naga_probe -- --nocapture`
fn ok(src: &str) -> Result<(), String> {
    let module = naga::front::glsl::Frontend::default()
        .parse(&naga::front::glsl::Options::from(naga::ShaderStage::Fragment), src)
        .map_err(|e| format!("parse: {e:?}").chars().take(300).collect::<String>())?;
    naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
        .validate(&module)
        .map_err(|e| format!("valid: {:?}", e.into_inner()).chars().take(300).collect::<String>())?;
    Ok(())
}

#[test]
fn struct_array_members() {
    let head = "#version 450\nlayout(location = 0) out vec4 o;\n";
    for (name, body) in [
        ("prefix-array-const", "struct S { float a; float[3] c; };\nvoid main() { const S s = S(1.0, float[3](1.0, 2.0, 3.0)); o = vec4(s.c[1]); }"),
        ("prefix-array-var", "struct S { float a; float[3] c; };\nvoid main() { S s = S(1.0, float[3](1.0, 2.0, 3.0)); o = vec4(s.c[1]); }"),
        ("suffix-array-const", "struct S { float a; float c[3]; };\nvoid main() { const S s = S(1.0, float[3](1.0, 2.0, 3.0)); o = vec4(s.c[1]); }"),
        ("suffix-array-var", "struct S { float a; float c[3]; };\nvoid main() { S s = S(1.0, float[3](1.0, 2.0, 3.0)); o = vec4(s.c[1]); }"),
        ("member-assign-array", "struct S { float a; float[3] c; };
void main() { S s; s.a = 1.0; s.c = float[3](1.0, 2.0, 3.0); o = vec4(s.c[1]); }"),
        ("member-assign-elements", "struct S { float a; float[3] c; };
void main() { S s; s.a = 1.0; s.c[0] = 1.0; s.c[1] = 2.0; s.c[2] = 3.0; o = vec4(s.c[1]); }"),
        ("bigger-member", "struct S { float a; float[10] c; };\nvoid main() { S s = S(1.0, float[6](1.0, 2.0, 3.0, 4.0, 5.0, 6.0)); o = vec4(s.c[1]); }"),
    ] {
        println!("{name}: {:?}", ok(&format!("{head}{body}")));
    }
}

fn spv(src: &str) -> Result<(), String> {
    let module = naga::front::glsl::Frontend::default()
        .parse(&naga::front::glsl::Options::from(naga::ShaderStage::Fragment), src)
        .map_err(|e| format!("parse: {e:?}").chars().take(400).collect::<String>())?;
    let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
        .validate(&module)
        .map_err(|e| format!("valid: {:?}", e.into_inner()).chars().take(400).collect::<String>())?;
    naga::back::spv::write_vec(&module, &info, &naga::back::spv::Options::default(), None)
        .map_err(|e| format!("spv: {e}"))?;
    Ok(())
}

#[test]
fn texture_overload() {
    let head = "#version 450\nlayout(location = 0) out vec4 o;\nlayout(set = 0, binding = 0) uniform texture2D t2;\nlayout(set = 0, binding = 1) uniform sampler s;\n\
struct hz_tex3d { int id; };\n\
const vec3 hz_t[4] = vec3[](vec3(0.0), vec3(1.0), vec3(0.5), vec3(0.25));\n\
vec4 texture(hz_tex3d t, vec3 p) { int i = int(p.x * 3.0) & 3; return vec4(hz_t[i], 1.0); }\n\
vec4 textureLod(hz_tex3d t, vec3 p, float l) { return texture(t, p); }\n\
ivec3 textureSize(hz_tex3d t, int l) { return ivec3(32); }\n\
const hz_tex3d noise3 = hz_tex3d(1);\n";
    for (name, body) in [
        ("overload-only", "void main() { o = texture(noise3, vec3(0.5)); }"),
        ("overload+builtin", "void main() { o = texture(noise3, vec3(0.5)) + texture(sampler2D(t2, s), vec2(0.5)); }"),
        ("lod+size", "void main() { o = textureLod(noise3, vec3(0.5), 0.0) + vec4(textureSize(noise3, 0)); }"),
        ("param", "float f(hz_tex3d x, vec3 p) { return texture(x, p).r; }\nvoid main() { o = vec4(f(noise3, vec3(0.2))); }"),
    ] {
        println!("{name}: {:?}", spv(&format!("{head}{body}")));
    }
}
