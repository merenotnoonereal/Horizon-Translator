
/// java.net.http's `HttpClient`, `HttpRequest` and `HttpResponse<String>`. The request itself goes through
/// J_HTTP, which the game side sets to Horizon's own HTTPS client (ureq); unset (in this file's tests), a send fails.
#[derive(Clone, Debug, Default)]
pub struct JHttpClient {
    pub connect_timeout: Option<std::time::Duration>,
}

#[derive(Clone, Debug)]
pub struct JHttpRequest {
    pub url: String,
    pub method: String,
    pub timeout: Option<std::time::Duration>,
    pub headers: Vec<(String, String)>,
}

#[derive(Clone, Debug)]
pub struct JHttpResponse {
    pub status: i32,
    pub body: String,
}

/// The game side's HTTP client: (request, connect timeout) to the response or an error.
pub static J_HTTP: std::sync::OnceLock<fn(&JHttpRequest, Option<std::time::Duration>) -> std::io::Result<JHttpResponse>> =
    std::sync::OnceLock::new();

impl JHttpClient {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_connect_timeout(mut self, d: std::time::Duration) -> Self {
        self.connect_timeout = Some(d);
        self
    }

    /// `send(request, BodyHandlers.ofString())`.
    pub fn send(&self, r: &JHttpRequest) -> std::io::Result<JHttpResponse> {
        match J_HTTP.get() {
            Some(f) => f(r, self.connect_timeout),
            None => Err(std::io::Error::other("no HTTP client here")),
        }
    }
}

impl JHttpRequest {
    pub fn new(url: &str) -> Self {
        JHttpRequest { url: url.to_string(), method: "GET".into(), timeout: None, headers: Vec::new() }
    }

    pub fn with_timeout(mut self, d: std::time::Duration) -> Self {
        self.timeout = Some(d);
        self
    }

    pub fn with_header(mut self, k: &str, v: &str) -> Self {
        self.headers.push((k.to_string(), v.to_string()));
        self
    }

    pub fn with_method(mut self, m: &str) -> Self {
        self.method = m.to_string();
        self
    }
}

/// `URLEncoder.encode(s, UTF_8)`: letters, digits and `.-*_` stay, a space is `+`, the rest `%XX` per byte.
pub fn j_url_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod jhttp_tests {
    use super::*;

    #[test]
    fn url_encoding_like_java() {
        assert_eq!(j_url_encode("[\"26.3\"]"), "%5B%2226.3%22%5D");
        assert_eq!(j_url_encode("a b~é"), "a+b%7E%C3%A9");
    }

    #[test]
    fn no_client_no_request() {
        assert!(JHttpClient::new().send(&JHttpRequest::new("https://example.com")).is_err());
    }
}
