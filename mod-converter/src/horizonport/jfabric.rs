
/// Fabric Loader's `Version` and `SemanticVersion` (net.fabricmc.loader.api), parsed and compared as the loader
/// does: numbers separated by dots, then an optional `-pre.release` and `+build` (which comparing ignores).
#[derive(Clone, Debug, PartialEq)]
pub struct JVersion {
    friendly: String,
    /// The numbers of a semantic version; None for one that isn't (compared as text).
    parts: Option<Vec<i64>>,
    pre: Option<String>,
}

impl JVersion {
    /// `SemanticVersion.parse`: an error (VersionParsingException) when it isn't one.
    pub fn parse_semantic(s: &str) -> std::io::Result<JVersion> {
        Self::semantic(s).ok_or_else(|| std::io::Error::other(format!("Could not parse version number '{s}'")))
    }

    /// `Version.parse`: a semantic version when it is one, else the text itself.
    pub fn parse(s: &str) -> std::io::Result<JVersion> {
        if s.is_empty() {
            return Err(std::io::Error::other("Empty version"));
        }
        Ok(Self::semantic(s).unwrap_or(JVersion { friendly: s.to_string(), parts: None, pre: None }))
    }

    fn semantic(s: &str) -> Option<JVersion> {
        let core_pre = s.split_once('+').map_or(s, |(c, _)| c);
        let (core, pre) = match core_pre.split_once('-') {
            Some((c, p)) => (c, Some(p.to_string())),
            None => (core_pre, None),
        };
        if let Some(p) = &pre {
            if !p.split('.').all(|x| !x.is_empty() && x.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')) {
                return None;
            }
        }
        let mut parts = Vec::new();
        for c in core.split('.') {
            if c.is_empty() || !c.chars().all(|ch| ch.is_ascii_digit()) {
                return None;
            }
            parts.push(c.parse::<i64>().ok()?);
        }
        Some(JVersion { friendly: s.to_string(), parts: Some(parts), pre })
    }

    /// `getFriendlyString`: the text it was parsed from.
    pub fn friendly_string(&self) -> String {
        self.friendly.clone()
    }

    /// `compareTo`: below, at or above zero.
    pub fn compare_to(&self, o: &JVersion) -> i32 {
        let (Some(a), Some(b)) = (&self.parts, &o.parts) else {
            return match self.friendly.cmp(&o.friendly) {
                std::cmp::Ordering::Less => -1,
                std::cmp::Ordering::Equal => 0,
                std::cmp::Ordering::Greater => 1,
            };
        };
        for i in 0..a.len().max(b.len()) {
            let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
            if x != y {
                return if x < y { -1 } else { 1 };
            }
        }
        match (&self.pre, &o.pre) {
            (None, None) => 0,
            (None, Some(_)) => 1,
            (Some(_), None) => -1,
            (Some(p), Some(q)) => {
                let (ps, qs): (Vec<&str>, Vec<&str>) = (p.split('.').collect(), q.split('.').collect());
                for i in 0..ps.len().min(qs.len()) {
                    let c = match (ps[i].parse::<i64>(), qs[i].parse::<i64>()) {
                        (Ok(x), Ok(y)) => x.cmp(&y),
                        (Ok(_), Err(_)) => std::cmp::Ordering::Less,
                        (Err(_), Ok(_)) => std::cmp::Ordering::Greater,
                        _ => ps[i].cmp(qs[i]),
                    };
                    if c != std::cmp::Ordering::Equal {
                        return if c == std::cmp::Ordering::Less { -1 } else { 1 };
                    }
                }
                (ps.len() as i32 - qs.len() as i32).signum()
            }
        }
    }

    /// The numbers, a missing one as 0.
    fn part(&self, i: usize) -> i64 {
        self.parts.as_ref().and_then(|p| p.get(i).copied()).unwrap_or(0)
    }
}

/// One dependency of a mod's fabric.mod.json: its kind (`depends`, `recommends`, `suggests`, `conflicts`,
/// `breaks`), the mod it names and its version ranges (any one matches).
#[derive(Clone, Debug, PartialEq)]
pub struct JModDep {
    pub kind: &'static str,
    pub mod_id: String,
    pub ranges: Vec<String>,
}

impl JModDep {
    /// `ModDependency.matches`: a range's space-separated predicates all hold (`>=1.2`, `~26.2`, `^1.0`, `1.20.x`, `*`).
    pub fn matches(&self, v: &JVersion) -> bool {
        self.ranges.iter().any(|r| r.split_whitespace().all(|p| Self::predicate(p, v)))
    }

    fn predicate(p: &str, v: &JVersion) -> bool {
        if p == "*" {
            return true;
        }
        for op in [">=", "<=", ">", "<", "=", "~", "^"] {
            if let Some(rest) = p.strip_prefix(op) {
                let Ok(w) = JVersion::parse(rest) else {
                    return false;
                };
                let c = v.compare_to(&w);
                return match op {
                    ">=" => c >= 0,
                    "<=" => c <= 0,
                    ">" => c > 0,
                    "<" => c < 0,
                    "=" => c == 0,
                    // ~1.2.3: at least it, below the next minor version.
                    "~" => c >= 0 && v.part(0) == w.part(0) && v.part(1) == w.part(1),
                    // ^1.2.3: at least it, below the next major version.
                    _ => c >= 0 && v.part(0) == w.part(0),
                };
            }
        }
        // 1.20.x: the numbers before the x match.
        if p.ends_with(".x") || p.ends_with(".X") || p.ends_with(".*") {
            let head: Vec<&str> = p[..p.len() - 2].split('.').collect();
            return head.iter().enumerate().all(|(i, h)| h.parse::<i64>().is_ok_and(|n| v.part(i) == n));
        }
        JVersion::parse(p).is_ok_and(|w| v.compare_to(&w) == 0)
    }
}

/// A mod as `FabricLoader.getModContainer` gives it, from the mod's fabric.mod.json at conversion time
/// (and `minecraft` as the version Horizon plays).
#[derive(Clone, Debug, PartialEq)]
pub struct JMod {
    pub id: String,
    pub version: JVersion,
    pub deps: Vec<JModDep>,
}

impl JMod {
    pub fn new(id: &str, version: &str, deps: &[(&'static str, &str, &[&str])]) -> JMod {
        JMod {
            id: id.to_string(),
            version: JVersion::parse(version).unwrap_or(JVersion { friendly: version.to_string(), parts: None, pre: None }),
            deps: deps
                .iter()
                .map(|(k, m, r)| JModDep { kind: k, mod_id: m.to_string(), ranges: r.iter().map(|s| s.to_string()).collect() })
                .collect(),
        }
    }
}

#[cfg(test)]
mod jfabric_tests {
    use super::*;

    #[test]
    fn versions_compare_like_fabric() {
        let v = |s| JVersion::parse_semantic(s).unwrap();
        assert!(v("1.0.1").compare_to(&v("1.0.0")) > 0);
        assert!(v("1.0").compare_to(&v("1.0.0")) == 0);
        assert!(v("1.0.0-beta.2").compare_to(&v("1.0.0")) < 0);
        assert!(v("1.0.0-beta.10").compare_to(&v("1.0.0-beta.2")) > 0);
        assert!(v("1.0.0+26.2").compare_to(&v("1.0.0+26.3")) == 0);
        assert!(JVersion::parse_semantic("v1.0").is_err());
        assert_eq!(v("1.2.3+build").friendly_string(), "1.2.3+build");
    }

    #[test]
    fn dependency_ranges() {
        let m = JMod::new("x", "1.0.0", &[("depends", "minecraft", &["~26.2"]), ("depends", "fabricloader", &[">=0.19"])]);
        let mc = |s| JVersion::parse(s).unwrap();
        assert!(m.deps[0].matches(&mc("26.2")));
        assert!(m.deps[0].matches(&mc("26.2.1")));
        assert!(!m.deps[0].matches(&mc("26.3")));
        assert!(m.deps[1].matches(&mc("0.19.2")));
        let d = JModDep { kind: "depends", mod_id: "minecraft".into(), ranges: vec!["1.20.x".into(), ">=26.3 <27".into()] };
        assert!(d.matches(&mc("1.20.4")) && d.matches(&mc("26.3")) && !d.matches(&mc("27.0")));
    }
}
