/// JOML's Vector3f as a value (Java's methods change the vector; the translation writes the result back).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct JVec3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}
impl JVec3 {
    pub const fn new(x: f32, y: f32, z: f32) -> Self {
        Self { x, y, z }
    }
    pub fn add(self, o: JVec3) -> Self {
        Self::new(self.x + o.x, self.y + o.y, self.z + o.z)
    }
    pub fn add3(self, x: f32, y: f32, z: f32) -> Self {
        Self::new(self.x + x, self.y + y, self.z + z)
    }
    pub fn sub(self, o: JVec3) -> Self {
        Self::new(self.x - o.x, self.y - o.y, self.z - o.z)
    }
    pub fn mul_s(self, s: f32) -> Self {
        Self::new(self.x * s, self.y * s, self.z * s)
    }
    pub fn mul_v(self, o: JVec3) -> Self {
        Self::new(self.x * o.x, self.y * o.y, self.z * o.z)
    }
    pub fn negate(self) -> Self {
        Self::new(-self.x, -self.y, -self.z)
    }
    pub fn dot(self, o: JVec3) -> f32 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }
    pub fn length(self) -> f32 {
        self.dot(self).sqrt()
    }
    pub fn length_squared(self) -> f32 {
        self.dot(self)
    }
    /// JOML's normalize: divides by the length (a zero vector becomes NaNs, as in Java).
    pub fn normalize(self) -> Self {
        let inv = 1.0 / self.length();
        self.mul_s(inv)
    }
    pub fn cross(self, o: JVec3) -> Self {
        Self::new(self.y * o.z - self.z * o.y, self.z * o.x - self.x * o.z, self.x * o.y - self.y * o.x)
    }
    /// JOML's lerp: this + (other - this) * t.
    pub fn lerp(self, o: JVec3, t: f32) -> Self {
        Self::new(self.x + (o.x - self.x) * t, self.y + (o.y - self.y) * t, self.z + (o.z - self.z) * t)
    }
    pub fn distance(self, o: JVec3) -> f32 {
        self.sub(o).length()
    }
}

/// JOML's Quaternionf (x, y, z, w).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct JQuat {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub w: f32,
}
impl Default for JQuat {
    fn default() -> Self {
        Self::IDENTITY
    }
}
impl JQuat {
    pub const IDENTITY: JQuat = JQuat { x: 0.0, y: 0.0, z: 0.0, w: 1.0 };
    pub const fn new(x: f32, y: f32, z: f32, w: f32) -> Self {
        Self { x, y, z, w }
    }
    /// `rotationX(angle)`: a turn about +X by `angle` radians (Axis.XP.rotation).
    pub fn rotation_x(a: f32) -> Self {
        let (s, c) = (a * 0.5).sin_cos();
        Self::new(s, 0.0, 0.0, c)
    }
    pub fn rotation_y(a: f32) -> Self {
        let (s, c) = (a * 0.5).sin_cos();
        Self::new(0.0, s, 0.0, c)
    }
    pub fn rotation_z(a: f32) -> Self {
        let (s, c) = (a * 0.5).sin_cos();
        Self::new(0.0, 0.0, s, c)
    }
    pub fn normalize(self) -> Self {
        let inv = 1.0 / (self.x * self.x + self.y * self.y + self.z * self.z + self.w * self.w).sqrt();
        Self::new(self.x * inv, self.y * inv, self.z * inv, self.w * inv)
    }
    /// this * q.
    pub fn mul(self, q: JQuat) -> Self {
        Self::new(
            self.w * q.x + self.x * q.w + self.y * q.z - self.z * q.y,
            self.w * q.y - self.x * q.z + self.y * q.w + self.z * q.x,
            self.w * q.z + self.x * q.y - self.y * q.x + self.z * q.w,
            self.w * q.w - self.x * q.x - self.y * q.y - self.z * q.z,
        )
    }
    pub fn rotate_x(self, a: f32) -> Self {
        self.mul(Self::rotation_x(a))
    }
    pub fn rotate_y(self, a: f32) -> Self {
        self.mul(Self::rotation_y(a))
    }
    pub fn rotate_z(self, a: f32) -> Self {
        self.mul(Self::rotation_z(a))
    }
}

/// JOML's Matrix3f, column-major, from three column vectors.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct JMat3 {
    pub m: [f32; 9],
}
impl JMat3 {
    pub fn from_cols(c0: JVec3, c1: JVec3, c2: JVec3) -> Self {
        Self { m: [c0.x, c0.y, c0.z, c1.x, c1.y, c1.z, c2.x, c2.y, c2.z] }
    }
}

/// JOML's Matrix4f: column-major, `m[col * 4 + row]`; `a.mul(b)` is a * b, and translate/rotate/scale
/// multiply on the right, as JOML does.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct JMat4 {
    pub m: [f32; 16],
}
impl Default for JMat4 {
    fn default() -> Self {
        Self::IDENTITY
    }
}
impl JMat4 {
    pub const IDENTITY: JMat4 = JMat4 { m: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0] };
    pub fn identity() -> Self {
        Self::IDENTITY
    }
    fn at(&self, row: usize, col: usize) -> f32 {
        self.m[col * 4 + row]
    }
    pub fn mul(self, r: JMat4) -> Self {
        let mut out = [0.0f32; 16];
        for col in 0..4 {
            for row in 0..4 {
                let mut v = 0.0;
                for k in 0..4 {
                    v += self.at(row, k) * r.at(k, col);
                }
                out[col * 4 + row] = v;
            }
        }
        Self { m: out }
    }
    pub fn translation(x: f32, y: f32, z: f32) -> Self {
        let mut t = Self::IDENTITY;
        t.m[12] = x;
        t.m[13] = y;
        t.m[14] = z;
        t
    }
    pub fn translate(self, x: f32, y: f32, z: f32) -> Self {
        self.mul(Self::translation(x, y, z))
    }
    pub fn translate_v(self, v: JVec3) -> Self {
        self.translate(v.x, v.y, v.z)
    }
    pub fn scaling(x: f32, y: f32, z: f32) -> Self {
        let mut t = Self::IDENTITY;
        t.m[0] = x;
        t.m[5] = y;
        t.m[10] = z;
        t
    }
    pub fn scale(self, x: f32, y: f32, z: f32) -> Self {
        self.mul(Self::scaling(x, y, z))
    }
    pub fn scale_s(self, s: f32) -> Self {
        self.scale(s, s, s)
    }
    /// The rotation a unit quaternion stands for.
    pub fn rotation(q: JQuat) -> Self {
        let (x, y, z, w) = (q.x, q.y, q.z, q.w);
        let (xx, yy, zz) = (x * x, y * y, z * z);
        let (xy, xz, yz, wx, wy, wz) = (x * y, x * z, y * z, w * x, w * y, w * z);
        Self {
            m: [
                1.0 - 2.0 * (yy + zz), 2.0 * (xy + wz), 2.0 * (xz - wy), 0.0,
                2.0 * (xy - wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz + wx), 0.0,
                2.0 * (xz + wy), 2.0 * (yz - wx), 1.0 - 2.0 * (xx + yy), 0.0,
                0.0, 0.0, 0.0, 1.0,
            ],
        }
    }
    pub fn rotate(self, q: JQuat) -> Self {
        self.mul(Self::rotation(q))
    }
    pub fn rotate_x(self, a: f32) -> Self {
        self.rotate(JQuat::rotation_x(a))
    }
    pub fn rotate_y(self, a: f32) -> Self {
        self.rotate(JQuat::rotation_y(a))
    }
    pub fn rotate_z(self, a: f32) -> Self {
        self.rotate(JQuat::rotation_z(a))
    }
    /// JOML's rotateXYZ: about X, then Y, then Z.
    pub fn rotate_xyz(self, x: f32, y: f32, z: f32) -> Self {
        self.rotate_x(x).rotate_y(y).rotate_z(z)
    }
    /// JOML's rotateAround(q, ox, oy, oz): about the point (ox, oy, oz).
    pub fn rotate_around(self, q: JQuat, ox: f32, oy: f32, oz: f32) -> Self {
        self.translate(ox, oy, oz).rotate(q).translate(-ox, -oy, -oz)
    }
    /// The general inverse (JOML's invert); a singular matrix gives infinities, as in Java.
    pub fn invert(self) -> Self {
        let a = &self.m;
        let mut inv = [0.0f32; 16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        let det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        let d = 1.0 / det;
        for v in inv.iter_mut() {
            *v *= d;
        }
        Self { m: inv }
    }
    /// The point v moved by this matrix (w = 1).
    pub fn transform_position(&self, v: JVec3) -> JVec3 {
        JVec3::new(
            self.at(0, 0) * v.x + self.at(0, 1) * v.y + self.at(0, 2) * v.z + self.at(0, 3),
            self.at(1, 0) * v.x + self.at(1, 1) * v.y + self.at(1, 2) * v.z + self.at(1, 3),
            self.at(2, 0) * v.x + self.at(2, 1) * v.y + self.at(2, 2) * v.z + self.at(2, 3),
        )
    }
    /// The direction v turned by this matrix (w = 0).
    pub fn transform_direction(&self, v: JVec3) -> JVec3 {
        JVec3::new(
            self.at(0, 0) * v.x + self.at(0, 1) * v.y + self.at(0, 2) * v.z,
            self.at(1, 0) * v.x + self.at(1, 1) * v.y + self.at(1, 2) * v.z,
            self.at(2, 0) * v.x + self.at(2, 1) * v.y + self.at(2, 2) * v.z,
        )
    }
    /// JOML's set3x3: the upper-left 3x3 from `m`, the rest kept.
    pub fn set3x3(self, m: JMat3) -> Self {
        let mut out = self;
        for col in 0..3 {
            for row in 0..3 {
                out.m[col * 4 + row] = m.m[col * 3 + row];
            }
        }
        out
    }
    /// JOML's transpose3x3: the upper-left 3x3 transposed, the rest kept.
    pub fn transpose3x3(self) -> Self {
        let mut out = self;
        for col in 0..3 {
            for row in 0..3 {
                out.m[col * 4 + row] = self.m[row * 4 + col];
            }
        }
        out
    }
    pub fn get_translation(&self) -> JVec3 {
        JVec3::new(self.m[12], self.m[13], self.m[14])
    }
}

/// Minecraft's PoseStack: a stack of matrices (normals aren't kept: Horizon's hand renderer takes one matrix).
#[derive(Clone, Debug)]
pub struct JPoseStack {
    pub stack: Vec<JMat4>,
}
impl Default for JPoseStack {
    fn default() -> Self {
        Self { stack: vec![JMat4::IDENTITY] }
    }
}
impl JPoseStack {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn of(m: JMat4) -> Self {
        Self { stack: vec![m] }
    }
    fn top(&mut self) -> &mut JMat4 {
        self.stack.last_mut().expect("PoseStack is empty")
    }
    pub fn push_pose(&mut self) {
        let m = *self.top();
        self.stack.push(m);
    }
    pub fn pop_pose(&mut self) {
        self.stack.pop();
    }
    /// Writes the top matrix (Java changed `pose.last().pose()` in place).
    pub fn set_last(&mut self, m: JMat4) {
        *self.top() = m;
    }
    pub fn last_pose(&self) -> JMat4 {
        *self.stack.last().expect("PoseStack is empty")
    }
    pub fn set_identity(&mut self) {
        *self.top() = JMat4::IDENTITY;
    }
    pub fn translate(&mut self, x: f32, y: f32, z: f32) {
        let m = self.top();
        *m = m.translate(x, y, z);
    }
    pub fn scale(&mut self, x: f32, y: f32, z: f32) {
        let m = self.top();
        *m = m.scale(x, y, z);
    }
    pub fn mul_pose(&mut self, q: JQuat) {
        let m = self.top();
        *m = m.rotate(q);
    }
    pub fn mul_pose_matrix(&mut self, r: JMat4) {
        let m = self.top();
        *m = m.mul(r);
    }
    pub fn rotate_around(&mut self, q: JQuat, x: f32, y: f32, z: f32) {
        let m = self.top();
        *m = m.rotate_around(q, x, y, z);
    }
}

#[cfg(test)]
mod joml_tests {
    use super::*;

    fn close(a: JVec3, b: JVec3) -> bool {
        (a.x - b.x).abs() < 1e-5 && (a.y - b.y).abs() < 1e-5 && (a.z - b.z).abs() < 1e-5
    }

    #[test]
    fn rotations_turn_axes_as_joml_does() {
        let q = JQuat::rotation_x(std::f32::consts::FRAC_PI_2);
        // A positive turn about X takes +Y to +Z (right-handed), as JOML's rotationX.
        assert!(close(JMat4::rotation(q).transform_position(JVec3::new(0.0, 1.0, 0.0)), JVec3::new(0.0, 0.0, 1.0)));
        let q = JQuat::rotation_y(std::f32::consts::FRAC_PI_2);
        assert!(close(JMat4::rotation(q).transform_position(JVec3::new(0.0, 0.0, 1.0)), JVec3::new(1.0, 0.0, 0.0)));
        let q = JQuat::rotation_z(std::f32::consts::FRAC_PI_2);
        assert!(close(JMat4::rotation(q).transform_position(JVec3::new(1.0, 0.0, 0.0)), JVec3::new(0.0, 1.0, 0.0)));
    }

    #[test]
    fn translate_then_rotate_multiplies_on_the_right() {
        // JOML: new Matrix4f().translate(1, 0, 0).rotateZ(90) moves (1, 0, 0) to (1, 1, 0).
        let m = JMat4::identity().translate(1.0, 0.0, 0.0).rotate_z(std::f32::consts::FRAC_PI_2);
        assert!(close(m.transform_position(JVec3::new(1.0, 0.0, 0.0)), JVec3::new(1.0, 1.0, 0.0)));
    }

    #[test]
    fn invert_undoes() {
        let m = JMat4::identity().translate(1.0, 2.0, 3.0).rotate_x(0.7).rotate_y(-0.3).scale(2.0, 0.5, 1.5);
        let p = JVec3::new(0.3, -1.2, 4.0);
        assert!(close(m.invert().transform_position(m.transform_position(p)), p));
        assert!(close(m.mul(m.invert()).transform_position(p), p));
    }

    #[test]
    fn rotate_around_keeps_its_point() {
        let m = JMat4::identity().rotate_around(JQuat::rotation_y(1.1), 0.5, 0.25, 0.0);
        assert!(close(m.transform_position(JVec3::new(0.5, 0.25, 0.0)), JVec3::new(0.5, 0.25, 0.0)));
    }

    #[test]
    fn pose_stack_push_pop() {
        let mut p = JPoseStack::new();
        p.translate(1.0, 0.0, 0.0);
        p.push_pose();
        p.scale(2.0, 2.0, 2.0);
        assert!(close(p.last_pose().transform_position(JVec3::new(1.0, 0.0, 0.0)), JVec3::new(3.0, 0.0, 0.0)));
        p.pop_pose();
        assert!(close(p.last_pose().transform_position(JVec3::new(1.0, 0.0, 0.0)), JVec3::new(2.0, 0.0, 0.0)));
    }

    #[test]
    fn quaternion_matrix_agrees_with_axis_turns() {
        let q = JQuat::rotation_x(0.4).mul(JQuat::rotation_y(-0.9));
        let a = JMat4::rotation(q);
        let b = JMat4::identity().rotate_x(0.4).rotate_y(-0.9);
        let p = JVec3::new(1.0, 2.0, 3.0);
        assert!(close(a.transform_position(p), b.transform_position(p)));
    }

    #[test]
    fn set3x3_and_transpose() {
        let m3 = JMat3::from_cols(JVec3::new(0.0, 1.0, 0.0), JVec3::new(-1.0, 0.0, 0.0), JVec3::new(0.0, 0.0, 1.0));
        let m = JMat4::identity().set3x3(m3);
        assert!(close(m.transform_position(JVec3::new(1.0, 0.0, 0.0)), JVec3::new(0.0, 1.0, 0.0)));
        assert!(close(m.transpose3x3().transform_position(JVec3::new(0.0, 1.0, 0.0)), JVec3::new(1.0, 0.0, 0.0)));
    }
}
