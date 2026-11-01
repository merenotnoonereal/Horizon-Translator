//! How Horizon's custom-uniform evaluator reads `m.a.b`. `cargo test --release --test matrix_probe -- --nocapture`
use horizon_shaders::{Custom, Macros, Properties, Val};
#[test]
fn matrix_components() {
    let text = "uniform.float.a01 = m.0.1\nuniform.float.a10 = m.1.0\nuniform.float.a23 = m.2.3\n";
    let props = Properties::parse(text, &Macros::standard());
    let mut c = Custom::parse(&props);
    // Column-major storage, as Horizon's m4 (to_cols_array): entry = 10*column + row.
    let mut m = [0f64; 16];
    for col in 0..4 { for row in 0..4 { m[col * 4 + row] = (10 * col + row) as f64; } }
    let out = c.eval(0.0, &|n| (n == "m").then(|| Val::vector(&m)));
    for ((name, _), v) in c.uniforms().zip(out) { println!("{name} = {} (10*column + row)", v.v[0]); }
    println!("notes: {:?}", c.notes);
}
