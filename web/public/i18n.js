// Internationalization (i18n) system for Horizon Translator
// Supports 22 languages, RTL layouts, safe inline HTML formatting, and live language switching.
(function () {
  "use strict";

  const LANGUAGES = [
    { code: "en", name: "English" },
    { code: "zh-CN", name: "简体中文" },
    { code: "hi", name: "हिन्दी" },
    { code: "es", name: "Español" },
    { code: "ar", name: "العربية", rtl: true },
    { code: "fr", name: "Français" },
    { code: "bn", name: "বাংলা" },
    { code: "pt-BR", name: "Português (Brasil)" },
    { code: "ru", name: "Русский" },
    { code: "id", name: "Bahasa Indonesia" },
    { code: "ur", name: "اردو", rtl: true },
    { code: "de", name: "Deutsch" },
    { code: "ja", name: "日本語" },
    { code: "mr", name: "मराठी" },
    { code: "vi", name: "Tiếng Việt" },
    { code: "te", name: "తెలుగు" },
    { code: "tr", name: "Türkçe" },
    { code: "ta", name: "தமிழ்" },
    { code: "ko", name: "한국어" },
    { code: "sw", name: "Kiswahili" },
    { code: "fil", name: "Filipino" },
    { code: "fa", name: "فارسی", rtl: true }
  ];

  const LANG_MAP = new Map(LANGUAGES.map((l) => [l.code.toLowerCase(), l]));
  const RTL_CODES = new Set(LANGUAGES.filter((l) => l.rtl).map((l) => l.code));

  // In-memory cache of loaded language bundles: code -> { key: string }
  const bundles = {};
  const changeListeners = [];

  let currentLang = "en";
  let initialized = false;

  function isRtl(code) {
    return RTL_CODES.has(code);
  }

  // Matches a locale tag against our supported languages
  function matchSupportedLang(tag) {
    if (!tag || typeof tag !== "string") return null;
    const clean = tag.trim().toLowerCase();

    // Direct match
    if (LANG_MAP.has(clean)) return LANG_MAP.get(clean).code;

    // Common locale aliases
    if (clean === "zh-hans" || clean === "zh-cn" || clean === "zh-sg" || clean === "zh") return "zh-CN";
    if (clean === "pt-br" || clean === "pt") return "pt-BR";
    if (clean === "tl" || clean === "fil") return "fil";
    if (clean === "ur-pk" || clean === "ur-in") return "ur";
    if (clean === "fa-ir") return "fa";

    // Primary subtag match (e.g., "es-MX" -> "es", "de-AT" -> "de", "ar-EG" -> "ar")
    const primary = clean.split(/[-_]/)[0];
    for (const l of LANGUAGES) {
      if (l.code.toLowerCase() === primary) return l.code;
    }
    return null;
  }

  // Detect language by priority:
  // 1. ?lang=<code> in URL
  // 2. localStorage "horizon_lang"
  // 3. navigator.languages / navigator.language
  // 4. Default: "en"
  function detectLanguage() {
    try {
      const params = new URLSearchParams(window.location.search);
      const queryLang = matchSupportedLang(params.get("lang"));
      if (queryLang) return queryLang;
    } catch {}

    try {
      const saved = matchSupportedLang(localStorage.getItem("horizon_lang"));
      if (saved) return saved;
    } catch {}

    const navLangs = navigator.languages || [navigator.language || navigator.userLanguage];
    if (navLangs) {
      for (const raw of navLangs) {
        const m = matchSupportedLang(raw);
        if (m) return m;
      }
    }

    return "en";
  }

  // Fetch a language JSON bundle from i18n/<code>.json
  async function fetchBundle(code) {
    if (bundles[code]) return bundles[code];
    try {
      const res = await fetch(`i18n/${code}.json`, { cache: "no-cache" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      bundles[code] = data;
      return data;
    } catch (e) {
      console.warn(`Could not load i18n bundle for "${code}":`, e.message || e);
      bundles[code] = bundles[code] || {};
      return bundles[code];
    }
  }

  // Numeric argument placeholder substitution {0}, {1}...
  // Leaves literal {x}, {facing}, {fps} completely untouched!
  function formatString(pattern, args) {
    if (!args || !args.length) return pattern;
    return pattern.replace(/\{(\d+)\}/g, (match, idx) => {
      const val = args[+idx];
      return val !== undefined ? String(val) : match;
    });
  }

  // Lookup translation with fallback to English, then to key itself
  function t(key, ...args) {
    let str = bundles[currentLang]?.[key];
    if (str === undefined && bundles["en"]) {
      str = bundles["en"][key];
    }
    if (str === undefined) {
      str = key;
    }
    return formatString(str, args);
  }

  function has(key) {
    return (bundles[currentLang] && bundles[currentLang][key] !== undefined) ||
           (bundles["en"] && bundles["en"][key] !== undefined);
  }

  // Sanitized DOM builder for translation strings with safe inline HTML
  // Allowed tags: <a>, <code>, <strong>, <em>, <br>, <span>, <pre>
  // Allowed attrs: href, class, rel, target, hidden
  const ALLOWED_TAGS = new Set(["A", "CODE", "STRONG", "EM", "BR", "SPAN", "PRE"]);
  const ALLOWED_ATTRS = new Set(["href", "class", "rel", "target", "hidden"]);

  function isSafeHref(val) {
    if (!val) return false;
    const v = val.trim();
    if (/^(https?:\/\/|#|\.\/|\/|terms\.html)/i.test(v)) return true;
    if (!/^[a-z0-9+.-]+:/i.test(v)) return true;
    return false;
  }

  function setSafeHtml(el, str) {
    el.textContent = "";
    if (!str) return;

    const parser = new DOMParser();
    const doc = parser.parseFromString("<!doctype html><body>" + str, "text/html");

    function sanitize(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        return document.createTextNode(node.nodeValue);
      }
      if (node.nodeType === Node.ELEMENT_NODE) {
        const tag = node.tagName.toUpperCase();
        if (!ALLOWED_TAGS.has(tag)) {
          const frag = document.createDocumentFragment();
          for (const child of Array.from(node.childNodes)) {
            frag.appendChild(sanitize(child));
          }
          return frag;
        }

        const safeEl = document.createElement(tag.toLowerCase());
        for (const attr of Array.from(node.attributes)) {
          const name = attr.name.toLowerCase();
          if (ALLOWED_ATTRS.has(name)) {
            if (name === "href") {
              if (isSafeHref(attr.value)) {
                safeEl.setAttribute("href", attr.value);
              }
            } else {
              safeEl.setAttribute(name, attr.value);
            }
          }
        }
        for (const child of Array.from(node.childNodes)) {
          safeEl.appendChild(sanitize(child));
        }
        return safeEl;
      }
      return document.createTextNode("");
    }

    for (const child of Array.from(doc.body.childNodes)) {
      el.appendChild(sanitize(child));
    }
  }

  // Populate or update header language <select class="lang-select">
  function updateLanguageSelectors() {
    const selects = document.querySelectorAll(".lang-select");
    for (const select of selects) {
      if (!select.dataset.populated) {
        select.innerHTML = "";
        for (const l of LANGUAGES) {
          const opt = document.createElement("option");
          opt.value = l.code;
          opt.textContent = l.name;
          select.appendChild(opt);
        }
        select.dataset.populated = "true";
        select.addEventListener("change", (e) => {
          setLang(e.target.value);
        });
      }
      select.value = currentLang;
    }
  }

  // Apply translations to data-i18n and data-i18n-attr elements
  function applyTranslations(root = document) {
    // Document attributes
    document.documentElement.lang = currentLang;
    document.documentElement.dir = isRtl(currentLang) ? "rtl" : "ltr";

    // Text & inline html
    const i18nElements = root.querySelectorAll("[data-i18n]");
    for (const el of i18nElements) {
      const key = el.getAttribute("data-i18n");
      if (!key) continue;
      const text = t(key);
      if (text.includes("<")) {
        setSafeHtml(el, text);
      } else {
        el.textContent = text;
      }
    }

    // Attributes (e.g. data-i18n-attr="title:key1,placeholder:key2")
    const attrElements = root.querySelectorAll("[data-i18n-attr]");
    for (const el of attrElements) {
      const spec = el.getAttribute("data-i18n-attr");
      if (!spec) continue;
      for (const pair of spec.split(",")) {
        const [attr, key] = pair.split(":").map((s) => s.trim());
        if (attr && key) {
          el.setAttribute(attr, t(key));
        }
      }
    }

    // Unofficial translation notice on terms.html
    const termsUnofficial = root.querySelector("#terms-unofficial");
    if (termsUnofficial) {
      termsUnofficial.hidden = currentLang === "en";
    }

    // Update dropdowns
    updateLanguageSelectors();

    // Rebind dynamic links (repo links, verify app trigger, make mod tab)
    const repoIssues = "https://github.com/merenotnoonereal/Horizon-Translator/issues/new/choose";
    const repoUrl = "https://github.com/merenotnoonereal/Horizon-Translator";
    for (const a of root.querySelectorAll(".repo-issues")) a.href = repoIssues;
    for (const a of root.querySelectorAll(".repo-link")) a.href = repoUrl;
    for (const a of root.querySelectorAll(".repo-ideas")) a.href = repoUrl + "/issues/new?template=feature_request.md";
    for (const a of root.querySelectorAll(".repo-releases")) a.href = repoUrl + "/releases";
    for (const a of root.querySelectorAll(".open-verify")) {
      a.onclick = (e) => {
        e.preventDefault();
        const box = document.getElementById("verify-app");
        if (box) {
          box.open = true;
          box.scrollIntoView({ behavior: "smooth" });
        }
      };
    }
  }

  function notifyChange() {
    for (const fn of changeListeners) {
      try {
        fn(currentLang);
      } catch (e) {
        console.error("i18n change listener error:", e);
      }
    }
  }

  async function setLang(code) {
    const matched = matchSupportedLang(code) || "en";
    currentLang = matched;

    try {
      localStorage.setItem("horizon_lang", currentLang);
    } catch {}

    try {
      const url = new URL(window.location);
      if (url.searchParams.get("lang") !== currentLang) {
        url.searchParams.set("lang", currentLang);
        window.history.replaceState(null, "", url.toString());
      }
    } catch {}

    // Load bundle if not already loaded
    if (currentLang !== "en" && !bundles[currentLang]) {
      await fetchBundle(currentLang);
    }

    applyTranslations();
    notifyChange();
  }

  function onChange(fn) {
    if (typeof fn === "function") {
      changeListeners.push(fn);
      // Run once immediately if already initialized
      if (initialized) {
        try { fn(currentLang); } catch {}
      }
    }
  }

  let readyResolve;
  const readyPromise = new Promise((resolve) => { readyResolve = resolve; });

  async function init() {
    const lang = detectLanguage();
    currentLang = lang;

    // Load English bundle first (required for fallbacks)
    await fetchBundle("en");

    // If non-English chosen, load that bundle too
    if (currentLang !== "en") {
      await fetchBundle(currentLang);
    }

    applyTranslations();
    initialized = true;
    notifyChange();
    readyResolve(currentLang);
  }

  // Initialize as soon as DOM is ready
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }

  window.I18n = {
    init,
    t,
    has,
    lang: () => currentLang,
    setLang,
    isRtl,
    languages: () => [...LANGUAGES],
    applyTranslations,
    setSafeHtml,
    onChange,
    ready: readyPromise
  };
})();
