
// ---- Java regular expressions (java.util.regex), a small backtracking engine ----
// Bundles can't add the regex crate, so this covers what mods use: literals, `.`, classes with ranges and
// \d \s \w, groups (capturing, `(?:`, `(?<name>`), lookahead, alternation, greedy/lazy quantifiers, ^ $ \b.
// Positions are in chars, like the translated String methods.

#[derive(Clone, Debug)]
enum JClassItem {
    Range(char, char),
    Digit(bool),
    Space(bool),
    Word(bool),
}

#[derive(Clone, Debug)]
enum JNode {
    Char(char),
    Any,
    Class(Vec<JClassItem>, bool),
    Start,
    End,
    WordB(bool),
    Group(Box<JNode>, Option<usize>),
    Look(Box<JNode>, bool),
    Alt(Vec<JNode>),
    Concat(Vec<JNode>),
    Repeat(Box<JNode>, u32, u32, bool),
    Backref(usize),
}

type JCaps = Vec<Option<(usize, usize)>>;

/// A compiled `Pattern`.
#[derive(Clone, Debug)]
pub struct JRegex {
    node: JNode,
    groups: usize,
    names: Vec<(String, usize)>,
    ci: bool,
}

fn j_is_word(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

fn j_is_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

struct JRegexParser<'a> {
    p: &'a [char],
    i: usize,
    groups: usize,
    names: Vec<(String, usize)>,
}

impl JRegexParser<'_> {
    fn peek(&self) -> Option<char> {
        self.p.get(self.i).copied()
    }
    fn eat(&mut self, c: char) -> bool {
        if self.peek() == Some(c) {
            self.i += 1;
            true
        } else {
            false
        }
    }
    fn alt(&mut self) -> JNode {
        let mut arms = vec![self.concat()];
        while self.eat('|') {
            arms.push(self.concat());
        }
        if arms.len() == 1 { arms.pop().unwrap() } else { JNode::Alt(arms) }
    }
    fn concat(&mut self) -> JNode {
        let mut items = Vec::new();
        while let Some(c) = self.peek() {
            if c == '|' || c == ')' {
                break;
            }
            let atom = self.atom();
            items.push(self.quantified(atom));
        }
        JNode::Concat(items)
    }
    fn number(&mut self) -> Option<u32> {
        let start = self.i;
        while self.peek().is_some_and(|c| c.is_ascii_digit()) {
            self.i += 1;
        }
        if start == self.i { None } else { self.p[start..self.i].iter().collect::<String>().parse().ok() }
    }
    fn quantified(&mut self, atom: JNode) -> JNode {
        let (min, max) = match self.peek() {
            Some('*') => (0, u32::MAX),
            Some('+') => (1, u32::MAX),
            Some('?') => (0, 1),
            Some('{') => {
                let save = self.i;
                self.i += 1;
                let Some(lo) = self.number() else {
                    self.i = save;
                    return atom;
                };
                let hi = if self.eat(',') { self.number().unwrap_or(u32::MAX) } else { lo };
                if !self.eat('}') {
                    panic!("bad repetition in regex");
                }
                self.i -= 1;
                (lo, hi)
            }
            _ => return atom,
        };
        self.i += 1;
        let greedy = !self.eat('?');
        if greedy {
            self.eat('+');
        }
        self.quantified(JNode::Repeat(Box::new(atom), min, max, greedy))
    }
    fn escape(&mut self, in_class: bool) -> JClassItem {
        let c = self.peek().expect("regex ends with a backslash");
        self.i += 1;
        let lit = |c: char| JClassItem::Range(c, c);
        match c {
            'd' => JClassItem::Digit(true),
            'D' => JClassItem::Digit(false),
            's' => JClassItem::Space(true),
            'S' => JClassItem::Space(false),
            'w' => JClassItem::Word(true),
            'W' => JClassItem::Word(false),
            't' => lit('\t'),
            'n' => lit('\n'),
            'r' => lit('\r'),
            'f' => lit('\u{0C}'),
            'e' => lit('\u{1B}'),
            '0' => lit('\0'),
            'u' => {
                let hex: String = self.p[self.i..self.i + 4].iter().collect();
                self.i += 4;
                lit(char::from_u32(u32::from_str_radix(&hex, 16).expect("bad \\u escape")).unwrap_or('\u{FFFD}'))
            }
            'x' => {
                let hex: String = self.p[self.i..self.i + 2].iter().collect();
                self.i += 2;
                lit(char::from_u32(u32::from_str_radix(&hex, 16).expect("bad \\x escape")).unwrap_or('\u{FFFD}'))
            }
            c if !in_class && c.is_ascii_alphabetic() => panic!("unsupported regex escape \\{c}"),
            c => lit(c),
        }
    }
    fn class(&mut self) -> JNode {
        let negated = self.eat('^');
        let mut items = Vec::new();
        let mut first = true;
        loop {
            let c = self.peek().expect("unclosed [ in regex");
            if c == ']' && !first {
                self.i += 1;
                break;
            }
            first = false;
            if c == '[' {
                panic!("nested character classes aren't supported");
            }
            self.i += 1;
            let item = if c == '\\' { self.escape(true) } else { JClassItem::Range(c, c) };
            // a-z
            if let JClassItem::Range(lo, _) = item
                && self.peek() == Some('-')
                && self.p.get(self.i + 1).is_some_and(|&n| n != ']')
            {
                self.i += 1;
                let hc = self.peek().unwrap();
                self.i += 1;
                let hi = if hc == '\\' {
                    match self.escape(true) {
                        JClassItem::Range(h, _) => h,
                        _ => panic!("bad range in regex"),
                    }
                } else {
                    hc
                };
                items.push(JClassItem::Range(lo, hi));
                continue;
            }
            items.push(item);
        }
        JNode::Class(items, negated)
    }
    fn atom(&mut self) -> JNode {
        let c = self.peek().unwrap();
        self.i += 1;
        match c {
            '.' => JNode::Any,
            '^' => JNode::Start,
            '$' => JNode::End,
            '[' => self.class(),
            '(' => {
                let mut index = None;
                let mut look = None;
                if self.eat('?') {
                    if self.eat(':') {
                    } else if self.eat('=') {
                        look = Some(false);
                    } else if self.eat('!') {
                        look = Some(true);
                    } else if self.eat('<') {
                        let start = self.i;
                        while self.peek().is_some_and(|c| c != '>') {
                            self.i += 1;
                        }
                        let name: String = self.p[start..self.i].iter().collect();
                        self.i += 1;
                        self.groups += 1;
                        self.names.push((name, self.groups));
                        index = Some(self.groups);
                    } else {
                        panic!("unsupported regex group (?{:?}", self.peek());
                    }
                } else {
                    self.groups += 1;
                    index = Some(self.groups);
                }
                let inner = self.alt();
                if !self.eat(')') {
                    panic!("unclosed ( in regex");
                }
                match look {
                    Some(neg) => JNode::Look(Box::new(inner), neg),
                    None => JNode::Group(Box::new(inner), index),
                }
            }
            '\\' => match self.peek() {
                Some('b') => {
                    self.i += 1;
                    JNode::WordB(true)
                }
                Some('B') => {
                    self.i += 1;
                    JNode::WordB(false)
                }
                Some('A') => {
                    self.i += 1;
                    JNode::Start
                }
                Some('z') | Some('Z') => {
                    self.i += 1;
                    JNode::End
                }
                Some(d) if d.is_ascii_digit() && d != '0' => {
                    self.i += 1;
                    JNode::Backref(d as usize - '0' as usize)
                }
                Some('Q') => {
                    self.i += 1;
                    let mut lits = Vec::new();
                    while self.i < self.p.len() && !(self.p[self.i] == '\\' && self.p.get(self.i + 1) == Some(&'E')) {
                        lits.push(JNode::Char(self.p[self.i]));
                        self.i += 1;
                    }
                    self.i = (self.i + 2).min(self.p.len());
                    JNode::Concat(lits)
                }
                _ => match self.escape(false) {
                    JClassItem::Range(c, _) => JNode::Char(c),
                    other => JNode::Class(vec![other], false),
                },
            },
            c => JNode::Char(c),
        }
    }
}

impl JRegex {
    /// `Pattern.compile(p)`. Java throws PatternSyntaxException on a bad pattern; this panics.
    pub fn new(p: &str) -> Self {
        Self::with_flags(p, 0)
    }
    /// `Pattern.compile(p, flags)`: CASE_INSENSITIVE (2) is honoured.
    pub fn with_flags(p: &str, flags: i32) -> Self {
        let chars: Vec<char> = p.chars().collect();
        let (chars, ci) = match chars.strip_prefix(&['(', '?', 'i', ')']) {
            Some(rest) => (rest.to_vec(), true),
            None => (chars, flags & 2 != 0),
        };
        let mut parser = JRegexParser { p: &chars, i: 0, groups: 0, names: Vec::new() };
        let node = parser.alt();
        if parser.i != chars.len() {
            panic!("unmatched ) in regex {p:?}");
        }
        JRegex { node, groups: parser.groups, names: parser.names, ci }
    }

    fn same(&self, a: char, b: char) -> bool {
        a == b || self.ci && (j_lower_char(a) == j_lower_char(b) || j_upper_char(a) == j_upper_char(b))
    }

    fn in_class(&self, items: &[JClassItem], c: char) -> bool {
        items.iter().any(|it| match *it {
            JClassItem::Range(lo, hi) => {
                (lo..=hi).contains(&c) || self.ci && ((lo..=hi).contains(&j_lower_char(c)) || (lo..=hi).contains(&j_upper_char(c)))
            }
            JClassItem::Digit(yes) => c.is_ascii_digit() == yes,
            JClassItem::Space(yes) => j_is_space(c) == yes,
            JClassItem::Word(yes) => j_is_word(c) == yes,
        })
    }

    fn m(&self, n: &JNode, s: &[char], i: usize, caps: &mut JCaps, k: &mut dyn FnMut(usize, &mut JCaps) -> bool) -> bool {
        match n {
            JNode::Char(c) => i < s.len() && self.same(s[i], *c) && k(i + 1, caps),
            JNode::Any => i < s.len() && !matches!(s[i], '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}') && k(i + 1, caps),
            JNode::Class(items, neg) => i < s.len() && self.in_class(items, s[i]) != *neg && k(i + 1, caps),
            JNode::Start => i == 0 && k(i, caps),
            JNode::End => (i == s.len() || i + 1 == s.len() && s[i] == '\n') && k(i, caps),
            JNode::WordB(yes) => {
                let before = i > 0 && j_is_word(s[i - 1]);
                let after = i < s.len() && j_is_word(s[i]);
                (before != after) == *yes && k(i, caps)
            }
            JNode::Group(inner, index) => match *index {
                None => self.m(inner, s, i, caps, k),
                Some(g) => self.m(inner, s, i, caps, &mut |j, caps: &mut JCaps| {
                    let prev = caps[g];
                    caps[g] = Some((i, j));
                    if k(j, caps) {
                        return true;
                    }
                    caps[g] = prev;
                    false
                }),
            },
            JNode::Look(inner, neg) => {
                let mut probe = caps.clone();
                let found = self.m(inner, s, i, &mut probe, &mut |_, _| true);
                if found == *neg {
                    return false;
                }
                if !neg {
                    *caps = probe;
                }
                k(i, caps)
            }
            JNode::Alt(arms) => arms.iter().any(|a| self.m(a, s, i, caps, k)),
            JNode::Concat(items) => self.seq(items, s, i, caps, k),
            JNode::Repeat(inner, min, max, greedy) => self.rep(inner, *min, *max, *greedy, 0, s, i, caps, k),
            JNode::Backref(g) => match caps.get(*g).copied().flatten() {
                Some((a, b)) => {
                    let len = b - a;
                    i + len <= s.len() && (0..len).all(|d| self.same(s[a + d], s[i + d])) && k(i + len, caps)
                }
                None => false,
            },
        }
    }

    fn seq(&self, items: &[JNode], s: &[char], i: usize, caps: &mut JCaps, k: &mut dyn FnMut(usize, &mut JCaps) -> bool) -> bool {
        match items.split_first() {
            None => k(i, caps),
            Some((first, rest)) => self.m(first, s, i, caps, &mut |j, caps: &mut JCaps| self.seq(rest, s, j, caps, k)),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn rep(&self, n: &JNode, min: u32, max: u32, greedy: bool, count: u32, s: &[char], i: usize, caps: &mut JCaps,
           k: &mut dyn FnMut(usize, &mut JCaps) -> bool) -> bool {
        let more = |caps: &mut JCaps, k: &mut dyn FnMut(usize, &mut JCaps) -> bool| {
            count < max
                && self.m(n, s, i, caps, &mut |j, caps: &mut JCaps| {
                    // An empty pass can't make progress: stop repeating once the minimum is met.
                    (j != i || count < min) && self.rep(n, min, max, greedy, count + 1, s, j, caps, k)
                })
        };
        if count < min {
            return more(caps, k);
        }
        if greedy { more(caps, k) || k(i, caps) } else { k(i, caps) || more(caps, k) }
    }

    /// A match starting exactly at `i`; `whole`: it must also end at the end of the text.
    fn at(&self, s: &[char], i: usize, whole: bool) -> Option<JCaps> {
        let mut caps: JCaps = vec![None; self.groups + 1];
        let mut end = None;
        let ok = self.m(&self.node, s, i, &mut caps, &mut |j, _| {
            if whole && j != s.len() {
                return false;
            }
            end = Some(j);
            true
        });
        if !ok {
            return None;
        }
        caps[0] = Some((i, end.unwrap()));
        Some(caps)
    }

    fn find_from(&self, s: &[char], from: usize) -> Option<JCaps> {
        (from..=s.len()).find_map(|i| self.at(s, i, false))
    }

    /// `String.matches` / `Pattern.matches`: the whole text.
    pub fn matches(&self, s: &str) -> bool {
        let cs: Vec<char> = s.chars().collect();
        self.at(&cs, 0, true).is_some()
    }

    /// `Pattern.matcher(s).find()` as a test.
    pub fn is_found(&self, s: &str) -> bool {
        let cs: Vec<char> = s.chars().collect();
        self.find_from(&cs, 0).is_some()
    }

    fn expand(&self, s: &[char], caps: &JCaps, rep: &str, out: &mut String) {
        let r: Vec<char> = rep.chars().collect();
        let mut i = 0;
        while i < r.len() {
            match r[i] {
                '\\' if i + 1 < r.len() => {
                    out.push(r[i + 1]);
                    i += 2;
                }
                '$' if i + 1 < r.len() && r[i + 1].is_ascii_digit() => {
                    let g = r[i + 1] as usize - '0' as usize;
                    if let Some((a, b)) = caps.get(g).copied().flatten() {
                        out.extend(&s[a..b]);
                    } else if g > self.groups {
                        panic!("No group {g}");
                    }
                    i += 2;
                }
                '$' if r.get(i + 1) == Some(&'{') => {
                    let close = r[i..].iter().position(|&c| c == '}').expect("bad ${name} in replacement") + i;
                    let name: String = r[i + 2..close].iter().collect();
                    let g = self.names.iter().find(|(n, _)| *n == name).map(|(_, g)| *g).expect("no group with that name");
                    if let Some((a, b)) = caps[g] {
                        out.extend(&s[a..b]);
                    }
                    i = close + 1;
                }
                c => {
                    out.push(c);
                    i += 1;
                }
            }
        }
    }

    fn replace(&self, s: &str, rep: &str, all: bool) -> String {
        let cs: Vec<char> = s.chars().collect();
        let mut out = String::new();
        let (mut pos, mut from) = (0, 0);
        while from <= cs.len() {
            let Some(caps) = self.find_from(&cs, from) else { break };
            let (a, b) = caps[0].unwrap();
            out.extend(&cs[pos..a]);
            self.expand(&cs, &caps, rep, &mut out);
            pos = b;
            from = if b == a { b + 1 } else { b };
            if b == a && a < cs.len() {
                out.push(cs[a]);
                pos = a + 1;
            }
            if !all {
                break;
            }
        }
        if pos <= cs.len() {
            out.extend(&cs[pos..]);
        }
        out
    }

    /// `String.replaceAll` / `Matcher.replaceAll`, with Java's `$1` and `\$` in the replacement.
    pub fn replace_all(&self, s: &str, rep: &str) -> String {
        self.replace(s, rep, true)
    }

    pub fn replace_first(&self, s: &str, rep: &str) -> String {
        self.replace(s, rep, false)
    }

    /// `String.split(regex, limit)` / `Pattern.split`, with Java's rules for empty parts.
    pub fn split(&self, s: &str, limit: i32) -> Vec<String> {
        let cs: Vec<char> = s.chars().collect();
        let mut parts: Vec<String> = Vec::new();
        let (mut pos, mut from) = (0, 0);
        while from <= cs.len() {
            if limit > 0 && parts.len() as i32 == limit - 1 {
                break;
            }
            let Some(caps) = self.find_from(&cs, from) else { break };
            let (a, b) = caps[0].unwrap();
            if b == 0 {
                // A zero-width match at the start makes no leading empty part.
                from = 1;
                continue;
            }
            if a == b && a >= cs.len() {
                break;
            }
            parts.push(cs[pos..a].iter().collect());
            pos = b;
            from = if a == b { b + 1 } else { b };
        }
        parts.push(cs[pos.min(cs.len())..].iter().collect());
        if limit == 0 {
            while parts.len() > 1 && parts.last().is_some_and(|p| p.is_empty()) {
                parts.pop();
            }
            if parts.len() == 1 && parts[0].is_empty() && !s.is_empty() {
                parts.clear();
            }
        }
        parts
    }
}

/// A `Matcher`: a regex over one text, with the last match.
#[derive(Clone, Debug)]
pub struct JMatcher {
    re: JRegex,
    text: Vec<char>,
    from: usize,
    caps: Option<JCaps>,
}

impl JMatcher {
    pub fn new(re: &JRegex, s: &str) -> Self {
        JMatcher { re: re.clone(), text: s.chars().collect(), from: 0, caps: None }
    }
    pub fn find(&mut self) -> bool {
        if self.from > self.text.len() {
            self.caps = None;
            return false;
        }
        self.caps = self.re.find_from(&self.text, self.from);
        if let Some((a, b)) = self.caps.as_ref().and_then(|c| c[0]) {
            self.from = if a == b { b + 1 } else { b };
            true
        } else {
            self.from = self.text.len() + 1;
            false
        }
    }
    pub fn find_at(&mut self, start: i32) -> bool {
        self.from = start as usize;
        self.find()
    }
    pub fn matches(&mut self) -> bool {
        self.caps = self.re.at(&self.text, 0, true);
        self.caps.is_some()
    }
    pub fn looking_at(&mut self) -> bool {
        self.caps = self.re.at(&self.text, 0, false);
        self.caps.is_some()
    }
    fn span(&self, g: usize) -> Option<(usize, usize)> {
        let caps = self.caps.as_ref().expect("No match found");
        assert!(g < caps.len(), "No group {g}");
        caps[g]
    }
    /// `group(n)`: null (None) when that group took no part in the match.
    pub fn group(&self, g: i32) -> Option<String> {
        self.span(g as usize).map(|(a, b)| self.text[a..b].iter().collect())
    }
    pub fn group_named(&self, name: &str) -> Option<String> {
        let g = self.re.names.iter().find(|(n, _)| n == name).map(|(_, g)| *g).expect("No group with that name");
        self.group(g as i32)
    }
    pub fn start(&self, g: i32) -> i32 {
        self.span(g as usize).map_or(-1, |(a, _)| a as i32)
    }
    pub fn end(&self, g: i32) -> i32 {
        self.span(g as usize).map_or(-1, |(_, b)| b as i32)
    }
    pub fn group_count(&self) -> i32 {
        self.re.groups as i32
    }
    pub fn replace_all(&self, rep: &str) -> String {
        self.re.replace_all(&self.text.iter().collect::<String>(), rep)
    }
}

#[cfg(test)]
mod jregex_tests {
    use super::*;

    #[test]
    fn groups_and_lazy() {
        let re = JRegex::new("^(?:\\S )?(?:VERY SPECIAL|COMMON|RARE|EPIC)(?: DUNGEON)?(?: (.+?))?(?: \\S)?$");
        let mut m = JMatcher::new(&re, "EPIC DUNGEON SWORD");
        assert!(m.matches());
        assert_eq!(m.group(1).as_deref(), Some("SWORD"));
        let mut m = JMatcher::new(&re, "a RARE FISHING ROD b");
        assert!(m.matches());
        assert_eq!(m.group(1).as_deref(), Some("FISHING ROD"));
        let mut m = JMatcher::new(&re, "COMMON");
        assert!(m.matches());
        assert_eq!(m.group(1), None);
    }

    #[test]
    fn classes_counts_find() {
        let re = JRegex::new("server/[0-9A-Fa-f]{8}/(.+)");
        let mut m = JMatcher::new(&re, "server/0aF3b2c1/pack.zip");
        assert!(m.matches());
        assert_eq!(m.group(1).as_deref(), Some("pack.zip"));
        assert!(!JRegex::new("server/[0-9A-Fa-f]{8}/(.+)").matches("server/xyz/a"));
        let re = JRegex::new("\"id\"\\s*:\\s*\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"");
        let mut m = JMatcher::new(&re, "{\"id\": \"minecraft:item/a\", \"id\":\"x:y\"}");
        assert!(m.find());
        assert_eq!(m.group(1).as_deref(), Some("minecraft:item/a"));
        assert!(m.find());
        assert_eq!(m.group(1).as_deref(), Some("x:y"));
        assert!(!m.find());
    }

    #[test]
    fn split_and_replace() {
        assert_eq!(JRegex::new("\\s*,\\s*").split("a , b,c,,", 0), vec!["a", "b", "c"]);
        assert_eq!(JRegex::new(",").split("a,b,c", 2), vec!["a", "b,c"]);
        assert_eq!(JRegex::new("").split("abc", 0), vec!["a", "b", "c"]);
        assert_eq!(JRegex::new("(\\w+)@(\\w+)").replace_all("x a@b y c@d", "$2.$1"), "x b.a y d.c");
        assert_eq!(JRegex::new("a*").replace_all("baaac", "-"), "-b--c-");
        assert!(JRegex::with_flags("abc", 2).matches("AbC"));
    }
}
