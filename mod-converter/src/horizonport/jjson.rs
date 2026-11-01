
// ---- Gson (com.google.gson): JSON values, a reader and Gson's writer ----
// JsonElement, JsonObject, JsonArray and JsonPrimitive are all one Rust type. Numbers keep their text, as
// Gson's LazilyParsedNumber does. Objects keep their insertion order, as Gson's LinkedTreeMap does.

#[derive(Clone, Debug, PartialEq)]
pub enum JJson {
    Null,
    Bool(bool),
    Num(String),
    Str(String),
    Arr(Vec<JJson>),
    Obj(Vec<(String, JJson)>),
}

/// A float or double as Java prints it (Float.toString / Double.toString).
pub fn j_java_num(x: f64, float: bool) -> String {
    if x.is_nan() {
        return "NaN".into();
    }
    if x.is_infinite() {
        return if x > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    let a = x.abs();
    if x == 0.0 {
        return if x.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    let digits = if float { format!("{:e}", x as f32) } else { format!("{:e}", x) };
    let (mant, exp) = digits.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    if (1e-3..1e7).contains(&a) {
        let plain = if float { format!("{}", x as f32) } else { format!("{}", x) };
        if plain.contains('.') { plain } else { plain + ".0" }
    } else {
        let mant = if mant.contains('.') { mant.to_string() } else { format!("{mant}.0") };
        format!("{mant}E{exp}")
    }
}

struct JJsonReader<'a> {
    s: &'a [char],
    i: usize,
}

impl JJsonReader<'_> {
    fn ws(&mut self) {
        while self.i < self.s.len() && self.s[self.i].is_whitespace() {
            self.i += 1;
        }
    }
    fn fail(&self, what: &str) -> ! {
        panic!("JsonSyntaxException: {what} at {}", self.i)
    }
    fn value(&mut self) -> JJson {
        self.ws();
        match self.s.get(self.i) {
            Some('{') => {
                self.i += 1;
                let mut members = Vec::new();
                self.ws();
                if self.s.get(self.i) == Some(&'}') {
                    self.i += 1;
                    return JJson::Obj(members);
                }
                loop {
                    self.ws();
                    let k = match self.s.get(self.i) {
                        Some('"') | Some('\'') => self.string(),
                        _ => self.bare(),
                    };
                    self.ws();
                    if !matches!(self.s.get(self.i), Some(':') | Some('=')) {
                        self.fail("expected ':'");
                    }
                    self.i += 1;
                    let v = self.value();
                    // A repeated name: Gson keeps the last one.
                    members.retain(|(n, _): &(String, JJson)| *n != k);
                    members.push((k, v));
                    self.ws();
                    match self.s.get(self.i) {
                        Some(',') | Some(';') => self.i += 1,
                        Some('}') => {
                            self.i += 1;
                            return JJson::Obj(members);
                        }
                        _ => self.fail("expected ',' or '}'"),
                    }
                }
            }
            Some('[') => {
                self.i += 1;
                let mut items = Vec::new();
                self.ws();
                if self.s.get(self.i) == Some(&']') {
                    self.i += 1;
                    return JJson::Arr(items);
                }
                loop {
                    items.push(self.value());
                    self.ws();
                    match self.s.get(self.i) {
                        Some(',') | Some(';') => self.i += 1,
                        Some(']') => {
                            self.i += 1;
                            return JJson::Arr(items);
                        }
                        _ => self.fail("expected ',' or ']'"),
                    }
                }
            }
            Some('"') | Some('\'') => JJson::Str(self.string()),
            Some(_) => {
                let word = self.bare();
                match word.as_str() {
                    "true" => JJson::Bool(true),
                    "false" => JJson::Bool(false),
                    "null" => JJson::Null,
                    w if w.parse::<f64>().is_ok() => JJson::Num(w.to_string()),
                    // Gson is lenient: an unquoted word is a string.
                    w => JJson::Str(w.to_string()),
                }
            }
            None => self.fail("end of input"),
        }
    }
    fn bare(&mut self) -> String {
        let start = self.i;
        while self.i < self.s.len() && !matches!(self.s[self.i], ',' | ':' | '=' | ';' | '}' | ']' | '{' | '[' | '"' | '\'') && !self.s[self.i].is_whitespace() {
            self.i += 1;
        }
        if start == self.i {
            self.fail("expected a value");
        }
        self.s[start..self.i].iter().collect()
    }
    fn string(&mut self) -> String {
        let q = self.s[self.i];
        self.i += 1;
        let mut out = String::new();
        loop {
            let Some(&c) = self.s.get(self.i) else { self.fail("unterminated string") };
            self.i += 1;
            match c {
                c if c == q => return out,
                '\\' => {
                    let Some(&e) = self.s.get(self.i) else { self.fail("unterminated string") };
                    self.i += 1;
                    match e {
                        'n' => out.push('\n'),
                        't' => out.push('\t'),
                        'r' => out.push('\r'),
                        'b' => out.push('\u{8}'),
                        'f' => out.push('\u{c}'),
                        'u' => {
                            let hex: String = self.s[self.i..(self.i + 4).min(self.s.len())].iter().collect();
                            self.i += 4;
                            let unit = u16::from_str_radix(&hex, 16).unwrap_or_else(|_| self.fail("bad \\u escape"));
                            // A surrogate pair: two escapes make one char.
                            if (0xD800..0xDC00).contains(&unit) && self.s.get(self.i) == Some(&'\\') && self.s.get(self.i + 1) == Some(&'u') {
                                let hex2: String = self.s[self.i + 2..(self.i + 6).min(self.s.len())].iter().collect();
                                if let Ok(low) = u16::from_str_radix(&hex2, 16) {
                                    self.i += 6;
                                    out.extend(char::decode_utf16([unit, low]).map(|r| r.unwrap_or('\u{FFFD}')));
                                    continue;
                                }
                            }
                            out.push(char::from_u32(unit as u32).unwrap_or('\u{FFFD}'));
                        }
                        other => out.push(other),
                    }
                }
                c => out.push(c),
            }
        }
    }
}

impl JJson {
    /// JsonParser.parseString: panics (Java throws JsonSyntaxException) on bad JSON.
    pub fn parse(s: &str) -> JJson {
        let chars: Vec<char> = s.chars().collect();
        let mut r = JJsonReader { s: &chars, i: 0 };
        let v = r.value();
        r.ws();
        if r.i != chars.len() {
            r.fail("more after the value");
        }
        v
    }
    pub fn object() -> JJson {
        JJson::Obj(Vec::new())
    }
    pub fn array() -> JJson {
        JJson::Arr(Vec::new())
    }
    pub fn int(v: i64) -> JJson {
        JJson::Num(v.to_string())
    }
    pub fn float(v: f32) -> JJson {
        JJson::Num(j_java_num(v as f64, true))
    }
    pub fn double(v: f64) -> JJson {
        JJson::Num(j_java_num(v, false))
    }
    pub fn str(v: &str) -> JJson {
        JJson::Str(v.to_string())
    }
    pub fn is_json_object(&self) -> bool {
        matches!(self, JJson::Obj(_))
    }
    pub fn is_json_array(&self) -> bool {
        matches!(self, JJson::Arr(_))
    }
    pub fn is_json_null(&self) -> bool {
        matches!(self, JJson::Null)
    }
    pub fn is_json_primitive(&self) -> bool {
        matches!(self, JJson::Bool(_) | JJson::Num(_) | JJson::Str(_))
    }
    pub fn is_string(&self) -> bool {
        matches!(self, JJson::Str(_))
    }
    pub fn is_number(&self) -> bool {
        matches!(self, JJson::Num(_))
    }
    pub fn is_boolean(&self) -> bool {
        matches!(self, JJson::Bool(_))
    }
    /// getAsJsonObject() / getAsJsonArray(): Java throws IllegalStateException on the wrong kind.
    pub fn as_object(&self) -> JJson {
        assert!(self.is_json_object(), "Not a JSON Object: {self}");
        self.clone()
    }
    pub fn as_array(&self) -> JJson {
        assert!(self.is_json_array(), "Not a JSON Array: {self}");
        self.clone()
    }
    fn members(&self) -> &Vec<(String, JJson)> {
        match self {
            JJson::Obj(m) => m,
            _ => panic!("Not a JSON Object: {self}"),
        }
    }
    /// The (name, value) pairs of an object, as entrySet() walks them.
    pub fn entries(&self) -> impl Iterator<Item = (&String, &JJson)> {
        self.members().iter().map(|(k, v)| (k, v))
    }
    pub fn keys(&self) -> impl Iterator<Item = &String> {
        self.members().iter().map(|(k, _)| k)
    }
    pub fn elements(&self) -> impl Iterator<Item = &JJson> {
        match self {
            JJson::Arr(a) => a.iter(),
            _ => panic!("Not a JSON Array: {self}"),
        }
    }
    pub fn has(&self, k: &str) -> bool {
        self.members().iter().any(|(n, _)| n == k)
    }
    /// JsonObject.get: None (null) when absent.
    pub fn get(&self, k: &str) -> Option<JJson> {
        self.members().iter().find(|(n, _)| n == k).map(|(_, v)| v.clone())
    }
    /// JsonObject.getAsJsonObject(name) / getAsJsonArray(name): null when absent, a cast that throws on the wrong kind.
    pub fn member_object(&self, k: &str) -> Option<JJson> {
        self.get(k).map(|v| v.as_object())
    }
    pub fn member_array(&self, k: &str) -> Option<JJson> {
        self.get(k).map(|v| v.as_array())
    }
    /// JsonArray.get(i).
    pub fn at(&self, i: i32) -> JJson {
        match self {
            JJson::Arr(a) => a.get(i as usize).cloned().unwrap_or_else(|| panic!("Index {i} out of bounds for length {}", a.len())),
            _ => panic!("Not a JSON Array: {self}"),
        }
    }
    pub fn size(&self) -> i32 {
        match self {
            JJson::Arr(a) => a.len() as i32,
            JJson::Obj(m) => m.len() as i32,
            _ => panic!("size() of a JSON primitive"),
        }
    }
    pub fn is_empty(&self) -> bool {
        self.size() == 0
    }
    /// The single value of a one-element array, as Gson's JsonArray.getAsX does.
    fn single(&self) -> &JJson {
        match self {
            JJson::Arr(a) if a.len() == 1 => &a[0],
            JJson::Arr(_) => panic!("Array must have size 1"),
            other => other,
        }
    }
    pub fn as_string(&self) -> String {
        match self.single() {
            JJson::Str(s) => s.clone(),
            JJson::Num(n) => n.clone(),
            JJson::Bool(b) => b.to_string(),
            other => panic!("UnsupportedOperationException: {other} as a string"),
        }
    }
    fn number_text(&self) -> String {
        match self.single() {
            JJson::Num(n) | JJson::Str(n) => n.trim().to_string(),
            other => panic!("UnsupportedOperationException: {other} as a number"),
        }
    }
    pub fn as_f64(&self) -> f64 {
        let t = self.number_text();
        t.parse().unwrap_or_else(|_| panic!("NumberFormatException: {t}"))
    }
    pub fn as_float(&self) -> f32 {
        self.as_f64() as f32
    }
    pub fn as_double(&self) -> f64 {
        self.as_f64()
    }
    /// getAsInt / getAsLong: a whole number, else the number cut to one.
    pub fn as_long(&self) -> i64 {
        let t = self.number_text();
        t.parse::<i64>().unwrap_or_else(|_| t.parse::<f64>().map(|f| f as i64).unwrap_or_else(|_| panic!("NumberFormatException: {t}")))
    }
    pub fn as_int(&self) -> i32 {
        self.as_long() as i32
    }
    pub fn as_bool(&self) -> bool {
        match self.single() {
            JJson::Bool(b) => *b,
            // Gson: Boolean.parseBoolean of the text
            other => other.as_string().eq_ignore_ascii_case("true"),
        }
    }
    pub fn add(&mut self, k: &str, v: JJson) {
        match self {
            JJson::Obj(m) => {
                m.retain(|(n, _)| n != k);
                m.push((k.to_string(), v));
            }
            _ => panic!("Not a JSON Object: {self}"),
        }
    }
    pub fn remove(&mut self, k: &str) -> Option<JJson> {
        match self {
            JJson::Obj(m) => {
                let i = m.iter().position(|(n, _)| n == k)?;
                Some(m.remove(i).1)
            }
            _ => panic!("Not a JSON Object: {self}"),
        }
    }
    pub fn push(&mut self, v: JJson) {
        match self {
            JJson::Arr(a) => a.push(v),
            _ => panic!("Not a JSON Array: {self}"),
        }
    }
    fn write(&self, out: &mut String, pretty: bool, html: bool, depth: usize) {
        let nl = |out: &mut String, d: usize| {
            if pretty {
                out.push('\n');
                out.push_str(&"  ".repeat(d));
            }
        };
        match self {
            JJson::Null => out.push_str("null"),
            JJson::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            JJson::Num(n) => out.push_str(n),
            JJson::Str(s) => j_json_quote(s, html, out),
            JJson::Arr(a) => {
                out.push('[');
                for (i, v) in a.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    nl(out, depth + 1);
                    v.write(out, pretty, html, depth + 1);
                }
                if !a.is_empty() {
                    nl(out, depth);
                }
                out.push(']');
            }
            JJson::Obj(m) => {
                out.push('{');
                for (i, (k, v)) in m.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    nl(out, depth + 1);
                    j_json_quote(k, html, out);
                    out.push(':');
                    if pretty {
                        out.push(' ');
                    }
                    v.write(out, pretty, html, depth + 1);
                }
                if !m.is_empty() {
                    nl(out, depth);
                }
                out.push('}');
            }
        }
    }
}

fn j_json_quote(s: &str, html: bool, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '<' | '>' | '&' | '=' | '\'' if html => out.push_str(&format!("\\u{:04x}", c as u32)),
            '\u{2028}' | '\u{2029}' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// JsonElement.toString: compact, without Gson's HTML escaping.
impl std::fmt::Display for JJson {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut out = String::new();
        self.write(&mut out, false, false, 0);
        f.write_str(&out)
    }
}

/// A Gson (from `new Gson()` or a GsonBuilder): how it writes.
#[derive(Clone, Copy, Debug)]
pub struct JGson {
    pub pretty: bool,
    pub html: bool,
    pub nulls: bool,
}

impl Default for JGson {
    fn default() -> Self {
        JGson { pretty: false, html: true, nulls: false }
    }
}

impl JGson {
    /// Gson.toJson.
    pub fn to_json(&self, v: &JJson) -> String {
        let mut out = String::new();
        self.strip(v).write(&mut out, self.pretty, self.html, 0);
        out
    }
    /// Without serializeNulls, Gson leaves out null fields of objects.
    fn strip(&self, v: &JJson) -> JJson {
        match v {
            JJson::Obj(m) => JJson::Obj(m.iter().filter(|(_, x)| self.nulls || !x.is_json_null()).map(|(k, x)| (k.clone(), self.strip(x))).collect()),
            JJson::Arr(a) => JJson::Arr(a.iter().map(|x| self.strip(x)).collect()),
            other => other.clone(),
        }
    }
}

#[cfg(test)]
mod jjson_tests {
    use super::*;

    #[test]
    fn read_and_write() {
        let j = JJson::parse("{\"a\": 1, \"b\": [1.5, \"x\", true, null], \"c\": {\"d\": -2e3}}");
        assert!(j.has("a") && !j.has("z"));
        assert_eq!(j.get("a").unwrap().as_int(), 1);
        assert_eq!(j.member_array("b").unwrap().at(0).as_float(), 1.5);
        assert_eq!(j.member_array("b").unwrap().size(), 4);
        assert_eq!(j.member_object("c").unwrap().get("d").unwrap().as_double(), -2000.0);
        assert_eq!(j.get("z"), None);
        assert_eq!(j.to_string(), "{\"a\":1,\"b\":[1.5,\"x\",true,null],\"c\":{\"d\":-2e3}}");
        let g = JGson { pretty: true, ..JGson::default() };
        assert_eq!(g.to_json(&JJson::parse("{\"a\":[1,2],\"b\":{},\"n\":null,\"s\":\"<x>\"}")),
                   "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {},\n  \"s\": \"\\u003cx\\u003e\"\n}");
    }

    #[test]
    fn java_numbers() {
        assert_eq!(j_java_num(1.0, true), "1.0");
        assert_eq!(j_java_num(0.1, true), "0.1");
        assert_eq!(j_java_num(1.0e-4, false), "1.0E-4");
        assert_eq!(j_java_num(12345678.0, false), "1.2345678E7");
        assert_eq!(j_java_num(-0.75, false), "-0.75");
    }
}
