
// ---- java.util.Properties: `key=value` text files ----

#[derive(Clone, Debug, Default)]
pub struct JProps(pub std::collections::BTreeMap<String, String>);

fn j_props_unescape(s: &str) -> String {
    let mut out = String::new();
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('f') => out.push('\u{c}'),
            Some('u') => {
                let hex: String = (0..4).filter_map(|_| it.next()).collect();
                out.push(u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32).unwrap_or('\u{FFFD}'));
            }
            Some(o) => out.push(o),
            None => {}
        }
    }
    out
}

fn j_props_escape(s: &str, key: bool) -> String {
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{c}' => out.push_str("\\f"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            ' ' if key || i == 0 => out.push_str("\\ "),
            c if (c as u32) < 0x20 || (c as u32) > 0x7e => out.push_str(&format!("\\u{:04X}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

impl JProps {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn get_property(&self, k: &str) -> Option<String> {
        self.0.get(k).cloned()
    }
    pub fn get_property_or(&self, k: &str, default: &str) -> String {
        self.0.get(k).cloned().unwrap_or_else(|| default.to_string())
    }
    pub fn set_property(&mut self, k: &str, v: &str) -> Option<String> {
        self.0.insert(k.to_string(), v.to_string())
    }
    pub fn contains_key(&self, k: &str) -> bool {
        self.0.contains_key(k)
    }
    pub fn remove(&mut self, k: &str) -> Option<String> {
        self.0.remove(k)
    }
    pub fn names(&self) -> JSet<String> {
        JSet::from_iter(self.0.keys().cloned())
    }
    /// Properties.load: `key=value`, `key: value` or `key value`, `#`/`!` comments, `\` continuations.
    pub fn load(&mut self, text: &str) {
        let mut logical = String::new();
        for raw in text.lines() {
            let line = raw.trim_start();
            if logical.is_empty() && (line.is_empty() || line.starts_with('#') || line.starts_with('!')) {
                continue;
            }
            let trailing = line.chars().rev().take_while(|&c| c == '\\').count();
            if trailing % 2 == 1 {
                logical.push_str(&line[..line.len() - 1]);
                continue;
            }
            logical.push_str(line);
            let chars: Vec<char> = logical.chars().collect();
            let mut i = 0;
            while i < chars.len() && !(matches!(chars[i], '=' | ':') || chars[i].is_whitespace()) {
                i += if chars[i] == '\\' { 2 } else { 1 };
            }
            let key: String = chars[..i.min(chars.len())].iter().collect();
            let mut j = i;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            if j < chars.len() && matches!(chars[j], '=' | ':') {
                j += 1;
            }
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            let value: String = chars[j.min(chars.len())..].iter().collect();
            self.0.insert(j_props_unescape(&key), j_props_unescape(&value));
            logical.clear();
        }
    }
    /// Properties.store: the comment, then `key=value` lines (Java also writes the date, left out here).
    pub fn store_text(&self, comment: Option<&str>) -> String {
        let mut out = String::new();
        if let Some(c) = comment {
            for l in c.lines() {
                out.push('#');
                out.push_str(l);
                out.push('\n');
            }
        }
        for (k, v) in &self.0 {
            out.push_str(&j_props_escape(k, true));
            out.push('=');
            out.push_str(&j_props_escape(v, false));
            out.push('\n');
        }
        out
    }
}
