#!/usr/bin/env python3
"""
scripts/check-i18n.py - Validation tool for Horizon Translator i18n assets.

Validates all 22 supported languages across:
1. Website JSON files (web/public/i18n/<code>.json)
2. Desktop properties bundles (desktop/src/horizonport/i18n/messages_<code>.properties)
3. Translated Markdown guides (web/public/i18n/making-mods.<code>.md)

Ensures exact key sets, placeholders, inline HTML tags and attributes, MessageFormat
quotes, Markdown structure (code blocks and tables), and absence of em dashes.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

ALL_LANGUAGES: list[str] = [
    "en",
    "zh-CN",
    "hi",
    "es",
    "ar",
    "fr",
    "bn",
    "pt-BR",
    "ru",
    "id",
    "ur",
    "de",
    "ja",
    "mr",
    "vi",
    "te",
    "tr",
    "ta",
    "ko",
    "sw",
    "fil",
    "fa",
]

RTL_LANGUAGES: set[str] = {"ar", "ur", "fa"}

KNOWN_HTML_TAGS: set[str] = {
    "a", "b", "br", "code", "em", "i", "strong", "span", "pre", "u", "p",
    "html", "body", "font", "small", "sub", "sup", "li", "ul", "ol", "div",
    "kbd", "mark", "wbr"
}

ATTR_REGEX = re.compile(
    r'([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|\'([^\']*)\'|([^\s>]+)))?'
)

PLACEHOLDER_REGEX = re.compile(r"\{[a-zA-Z0-9_]+\}")
MESSAGEFORMAT_ARG_REGEX = re.compile(r"\{\d+\}")

# ANSI Colors
COLOR_RESET = "\033[0m"
COLOR_RED = "\033[91m"
COLOR_GREEN = "\033[92m"
COLOR_YELLOW = "\033[93m"
COLOR_BLUE = "\033[94m"
COLOR_BOLD = "\033[1m"


def use_color() -> bool:
    """Return True if terminal supports ANSI colors."""
    return sys.stdout.isatty() and os.environ.get("NO_COLOR") is None


def colorize(text: str, color: str) -> str:
    return f"{color}{text}{COLOR_RESET}" if use_color() else text


# ==============================================================================
# Helper parsing functions
# ==============================================================================

def check_em_dash(text: str) -> bool:
    """Check if text contains the em dash character (U+2014, '—') or literal '\\u2014'."""
    return "\u2014" in text or "\\u2014" in text


def check_messageformat_unescaped_quotes(val: str) -> bool:
    """
    Check if a MessageFormat pattern string (containing {\\d+}) has unescaped single quotes.
    In MessageFormat, literal apostrophes must be doubled ('').
    A lone single quote unescaped breaks formatting or eats placeholders.
    """
    if not MESSAGEFORMAT_ARG_REGEX.search(val):
        return False
    # Strip doubled single quotes; any remaining single quote is unescaped
    without_doubles = val.replace("''", "")
    return "'" in without_doubles


def parse_tag_attributes(attr_str: str) -> tuple[tuple[str, str], ...]:
    """Extract and normalize HTML attributes into a sorted tuple of (name, value)."""
    attrs: list[tuple[str, str]] = []
    for match in ATTR_REGEX.finditer(attr_str):
        name = match.group(1).lower()
        val = (
            match.group(2)
            if match.group(2) is not None
            else (
                match.group(3)
                if match.group(3) is not None
                else (match.group(4) if match.group(4) is not None else "")
            )
        )
        attrs.append((name, val))
    return tuple(sorted(attrs))


def extract_html_tags(s: str) -> list[tuple[str, str, tuple[tuple[str, str], ...]]]:
    """
    Extract inline HTML tags from a string.
    Returns a list of tuples: (tag_kind, tag_name, sorted_attrs)
    where tag_kind is 'start' (includes self-closing) or 'end'.
    """
    tags: list[tuple[str, str, tuple[tuple[str, str], ...]]] = []
    tag_pattern = re.compile(r"<(/)?([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    for m in tag_pattern.finditer(s):
        is_end = bool(m.group(1))
        tag_name = m.group(2).lower()
        if tag_name not in KNOWN_HTML_TAGS:
            continue
        rest = m.group(3).strip()
        is_self_closing = rest.endswith("/")
        if is_self_closing:
            rest = rest[:-1].strip()

        if is_end:
            tags.append(("end", tag_name, ()))
        else:
            attrs = parse_tag_attributes(rest)
            tags.append(("start", tag_name, attrs))
    return tags


def unescape_java_properties(s: str) -> str:
    """Unescape Java .properties escape sequences according to Java specs."""
    out: list[str] = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n:
            nxt = s[i + 1]
            if nxt == "t":
                out.append("\t")
                i += 2
            elif nxt == "r":
                out.append("\r")
                i += 2
            elif nxt == "n":
                out.append("\n")
                i += 2
            elif nxt == "f":
                out.append("\f")
                i += 2
            elif nxt == "u" and i + 5 <= n:
                hex_code = s[i + 2 : i + 6]
                try:
                    out.append(chr(int(hex_code, 16)))
                    i += 6
                except ValueError:
                    out.append("\\")
                    i += 1
            else:
                out.append(nxt)
                i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def parse_properties_file(path: Path) -> tuple[dict[str, str], dict[str, int], list[str]]:
    """
    Parse a Java .properties file encoded in UTF-8.
    Returns:
      (properties_dict, key_to_line_dict, error_messages)
    """
    props: dict[str, str] = {}
    line_nums: dict[str, int] = {}
    errors: list[str] = []

    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as ex:
        errors.append(f"Invalid UTF-8 encoding: {ex}")
        return props, line_nums, errors
    except OSError as ex:
        errors.append(f"Cannot read file: {ex}")
        return props, line_nums, errors

    lines = content.splitlines()
    i = 0
    num_lines = len(lines)

    while i < num_lines:
        line = lines[i].strip()
        start_line_no = i + 1
        if not line or line.startswith(("#", "!")):
            i += 1
            continue

        raw_line = lines[i].lstrip()
        while True:
            bs_count = len(raw_line) - len(raw_line.rstrip("\\"))
            if bs_count % 2 == 1:
                raw_line = raw_line[:-1]
                i += 1
                if i < num_lines:
                    raw_line += lines[i].lstrip()
                else:
                    break
            else:
                break

        # Find key-value delimiter
        j = 0
        escaped = False
        sep_idx = -1
        sep_char = ""
        while j < len(raw_line):
            c = raw_line[j]
            if escaped:
                escaped = False
                j += 1
                continue
            if c == "\\":
                escaped = True
                j += 1
                continue
            if c in ("=", ":", " ", "\t"):
                sep_idx = j
                sep_char = c
                break
            j += 1

        if sep_idx == -1:
            key_raw = raw_line
            val_raw = ""
        else:
            key_raw = raw_line[:sep_idx]
            rest = raw_line[sep_idx:]
            if sep_char in (" ", "\t"):
                rest = rest.lstrip(" \t")
                if rest and rest[0] in ("=", ":"):
                    rest = rest[1:].lstrip(" \t")
            elif sep_char in ("=", ":"):
                rest = rest[1:].lstrip(" \t")
            val_raw = rest

        key = unescape_java_properties(key_raw.strip())
        val = unescape_java_properties(val_raw)

        if key in props:
            errors.append(f"Line {start_line_no}: Duplicate key '{key}'")
        props[key] = val
        line_nums[key] = start_line_no
        i += 1

    return props, line_nums, errors


def parse_json_file(path: Path) -> tuple[dict[str, str], list[str]]:
    """
    Parse a JSON file encoded in UTF-8.
    Verifies that it is a flat mapping of string keys to string values.
    Checks for duplicate keys.
    """
    errors: list[str] = []
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as ex:
        return {}, [f"Invalid UTF-8 encoding: {ex}"]
    except OSError as ex:
        return {}, [f"Cannot read file: {ex}"]

    duplicates: list[str] = []
    seen: set[str] = set()

    def pairs_hook(pairs: list[tuple[Any, Any]]) -> dict[str, Any]:
        d: dict[str, Any] = {}
        for k, v in pairs:
            if not isinstance(k, str):
                errors.append(f"Key is not a string: {k!r}")
            elif k in seen:
                duplicates.append(k)
            seen.add(k)
            d[k] = v
        return d

    try:
        data = json.loads(content, object_pairs_hook=pairs_hook)
    except json.JSONDecodeError as ex:
        return {}, [f"Invalid JSON syntax at line {ex.lineno}, col {ex.colno}: {ex.msg}"]

    if duplicates:
        errors.append(f"Duplicate keys found in JSON: {', '.join(duplicates[:5])}")

    if not isinstance(data, dict):
        return {}, [f"Root of JSON must be an object (got {type(data).__name__})"]

    for k, v in data.items():
        if not isinstance(v, str):
            errors.append(f"Key '{k}' must map to a string value (got {type(v).__name__})")

    return data, errors


def parse_markdown_file(path: Path) -> tuple[dict[str, Any], list[str]]:
    """
    Parse a Markdown file for code blocks and tables.
    Returns:
      (dict_of_data, error_messages)
    """
    errors: list[str] = []
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as ex:
        return {}, [f"Invalid UTF-8 encoding: {ex}"]
    except OSError as ex:
        return {}, [f"Cannot read file: {ex}"]

    if not content.strip():
        errors.append("File is empty")
        return {}, errors

    lines = content.splitlines()
    code_blocks: list[tuple[int, str]] = []
    tables: list[tuple[int, int]] = []

    in_code = False
    current_code: list[str] = []
    code_start_line = 0

    current_table_rows = 0
    table_start_line = 0

    for line_no, line in enumerate(lines, start=1):
        stripped = line.strip()

        # Em dash check in markdown
        if check_em_dash(line):
            errors.append(f"Line {line_no} contains em dash ('—' / \\u2014): {stripped[:60]}")

        # Code fences
        if stripped.startswith("```"):
            if not in_code:
                in_code = True
                code_start_line = line_no
                current_code = []
            else:
                in_code = False
                code_blocks.append((code_start_line, "\n".join(current_code)))
                current_code = []
            continue

        if in_code:
            current_code.append(line)
            continue

        # Markdown tables
        if stripped.startswith("|") and stripped.endswith("|"):
            if current_table_rows == 0:
                table_start_line = line_no
            current_table_rows += 1
        else:
            if current_table_rows > 0:
                tables.append((table_start_line, current_table_rows))
                current_table_rows = 0

    if in_code:
        errors.append(f"Unclosed code fence starting at line {code_start_line}")
    if current_table_rows > 0:
        tables.append((table_start_line, current_table_rows))

    data = {
        "code_blocks": code_blocks,
        "tables": tables,
    }
    return data, errors


# ==============================================================================
# Validation Logic
# ==============================================================================

class ValidationContext:
    def __init__(self, root: Path, verbose: bool = False):
        self.root = root
        self.verbose = verbose
        self.total_errors = 0
        self.total_warnings = 0
        self.lang_results: dict[str, list[str]] = collections.defaultdict(list)

    def add_error(self, lang: str, file_rel: str, msg: str):
        self.total_errors += 1
        formatted = f"  [{colorize('ERROR', COLOR_RED)}] {file_rel}: {msg}"
        self.lang_results[lang].append(formatted)

    def add_warning(self, lang: str, file_rel: str, msg: str):
        self.total_warnings += 1
        formatted = f"  [{colorize('WARN', COLOR_YELLOW)}] {file_rel}: {msg}"
        self.lang_results[lang].append(formatted)

    def add_info(self, lang: str, file_rel: str, msg: str):
        if self.verbose:
            formatted = f"  [{colorize('INFO', COLOR_BLUE)}] {file_rel}: {msg}"
            self.lang_results[lang].append(formatted)


def format_tag_repr(tag_item: tuple) -> str:
    kind, name, attrs = tag_item
    if kind == "end":
        return f"</{name}>"
    if not attrs:
        return f"<{name}>"
    attr_str = " ".join(f'{k}="{v}"' for k, v in attrs)
    return f"<{name} {attr_str}>"


def validate_json_strings(
    lang: str,
    en_dict: dict[str, str],
    target_dict: dict[str, str],
    file_rel: str,
    ctx: ValidationContext,
):
    en_keys = set(en_dict.keys())
    target_keys = set(target_dict.keys())

    missing = sorted(en_keys - target_keys)
    extra = sorted(target_keys - en_keys)

    if missing:
        ctx.add_error(
            lang,
            file_rel,
            f"Missing {len(missing)} key(s): {', '.join(missing[:8])}{' ...' if len(missing) > 8 else ''}",
        )
    if extra:
        ctx.add_error(
            lang,
            file_rel,
            f"Extra {len(extra)} key(s) not in English: {', '.join(extra[:8])}{' ...' if len(extra) > 8 else ''}",
        )

    # Compare shared keys
    common_keys = sorted(en_keys & target_keys)
    for key in common_keys:
        en_val = en_dict[key]
        target_val = target_dict[key]

        # Check empty values
        if en_val.strip() != "" and target_val.strip() == "":
            ctx.add_error(lang, file_rel, f"Key '{key}' has empty translation")

        # Em dash check
        if check_em_dash(target_val):
            ctx.add_error(lang, file_rel, f"Key '{key}' contains em dash ('—' / \\u2014)")

        # Placeholders check
        en_placeholders = re.findall(PLACEHOLDER_REGEX, en_val)
        target_placeholders = re.findall(PLACEHOLDER_REGEX, target_val)
        if collections.Counter(en_placeholders) != collections.Counter(target_placeholders):
            ctx.add_error(
                lang,
                file_rel,
                f"Key '{key}' placeholder mismatch: expected {sorted(en_placeholders)}, got {sorted(target_placeholders)}",
            )

        # Inline HTML tags and attributes check
        en_tags = extract_html_tags(en_val)
        target_tags = extract_html_tags(target_val)
        if collections.Counter(en_tags) != collections.Counter(target_tags):
            en_repr = [format_tag_repr(t) for t in en_tags]
            target_repr = [format_tag_repr(t) for t in target_tags]
            ctx.add_error(
                lang,
                file_rel,
                f"Key '{key}' inline HTML mismatch: expected {en_repr}, got {target_repr}",
            )


def validate_properties_strings(
    lang: str,
    en_props: dict[str, str],
    target_props: dict[str, str],
    target_lines: dict[str, int],
    file_rel: str,
    ctx: ValidationContext,
):
    en_keys = set(en_props.keys())
    target_keys = set(target_props.keys())

    missing = sorted(en_keys - target_keys)
    extra = sorted(target_keys - en_keys)

    if missing:
        ctx.add_error(
            lang,
            file_rel,
            f"Missing {len(missing)} key(s): {', '.join(missing[:8])}{' ...' if len(missing) > 8 else ''}",
        )
    if extra:
        ctx.add_error(
            lang,
            file_rel,
            f"Extra {len(extra)} key(s) not in English: {', '.join(extra[:8])}{' ...' if len(extra) > 8 else ''}",
        )

    common_keys = sorted(en_keys & target_keys)
    for key in common_keys:
        en_val = en_props[key]
        target_val = target_props[key]
        line_no = target_lines.get(key, 0)
        loc = f"Line {line_no} (key '{key}')" if line_no else f"Key '{key}'"

        # Check empty values
        if en_val.strip() != "" and target_val.strip() == "":
            ctx.add_error(lang, file_rel, f"{loc}: has empty translation")

        # Em dash check
        if check_em_dash(target_val):
            ctx.add_error(lang, file_rel, f"{loc}: contains em dash ('—' / \\u2014)")

        # Placeholders check
        en_placeholders = re.findall(PLACEHOLDER_REGEX, en_val)
        target_placeholders = re.findall(PLACEHOLDER_REGEX, target_val)
        if collections.Counter(en_placeholders) != collections.Counter(target_placeholders):
            ctx.add_error(
                lang,
                file_rel,
                f"{loc}: placeholder mismatch: expected {sorted(en_placeholders)}, got {sorted(target_placeholders)}",
            )

        # MessageFormat unescaped single quote check
        # If target or English has {\d+}, literal apostrophes must be doubled ('')
        if check_messageformat_unescaped_quotes(target_val):
            ctx.add_error(
                lang,
                file_rel,
                f"{loc}: unescaped single quote in MessageFormat pattern containing {{0}}. Double it as ''",
            )

        # Inline HTML tags check
        en_tags = extract_html_tags(en_val)
        target_tags = extract_html_tags(target_val)
        if collections.Counter(en_tags) != collections.Counter(target_tags):
            en_repr = [format_tag_repr(t) for t in en_tags]
            target_repr = [format_tag_repr(t) for t in target_tags]
            ctx.add_error(
                lang,
                file_rel,
                f"{loc}: inline HTML mismatch: expected {en_repr}, got {target_repr}",
            )


def validate_markdown_guide(
    lang: str,
    en_md_data: dict[str, Any],
    target_data: dict[str, Any],
    file_rel: str,
    ctx: ValidationContext,
):
    en_blocks = en_md_data["code_blocks"]
    target_blocks = target_data["code_blocks"]

    if len(en_blocks) != len(target_blocks):
        ctx.add_error(
            lang,
            file_rel,
            f"Code block count mismatch: expected {len(en_blocks)}, found {len(target_blocks)}",
        )
    else:
        for idx, ((en_line, en_code), (t_line, t_code)) in enumerate(zip(en_blocks, target_blocks)):
            en_norm = "\n".join(line.rstrip() for line in en_code.strip().splitlines())
            t_norm = "\n".join(line.rstrip() for line in t_code.strip().splitlines())
            if en_norm != t_norm:
                ctx.add_error(
                    lang,
                    file_rel,
                    f"Code block #{idx + 1} at line {t_line} content differs from English guide",
                )

    en_tables = en_md_data["tables"]
    target_tables = target_data["tables"]

    if len(en_tables) != len(target_tables):
        ctx.add_error(
            lang,
            file_rel,
            f"Table count mismatch: expected {len(en_tables)}, found {len(target_tables)}",
        )
    else:
        for idx, ((_, en_rows), (t_line, t_rows)) in enumerate(zip(en_tables, target_tables)):
            if en_rows != t_rows:
                ctx.add_error(
                    lang,
                    file_rel,
                    f"Table #{idx + 1} at line {t_line} row count mismatch: expected {en_rows} rows, found {t_rows} rows",
                )


# ==============================================================================
# Main Orchestration
# ==============================================================================

def get_properties_file_path(desktop_dir: Path, code: str) -> Optional[Path]:
    """Find properties file matching code (e.g. messages_zh-CN.properties or messages_zh_CN.properties)."""
    p1 = desktop_dir / f"messages_{code}.properties"
    if p1.exists():
        return p1
    p2 = desktop_dir / f"messages_{code.replace('-', '_')}.properties"
    if p2.exists():
        return p2
    return None


def run_checks(languages: list[str], root: Path, verbose: bool = False) -> int:
    ctx = ValidationContext(root, verbose=verbose)

    web_i18n_dir = root / "web" / "public" / "i18n"
    desktop_i18n_dir = root / "desktop" / "src" / "horizonport" / "i18n"
    en_md_path = root / "web" / "public" / "making-mods.md"

    # --------------------------------------------------------------------------
    # 1. Baseline English Checks
    # --------------------------------------------------------------------------
    en_json_path = web_i18n_dir / "en.json"
    en_json_rel = "web/public/i18n/en.json"
    en_json_dict: dict[str, str] = {}
    en_json_valid = False

    if not en_json_path.exists():
        ctx.add_error("en", en_json_rel, "File does not exist")
    else:
        data, errs = parse_json_file(en_json_path)
        for err in errs:
            ctx.add_error("en", en_json_rel, err)
        if not errs:
            if not data:
                ctx.add_error("en", en_json_rel, "JSON file has no keys")
            else:
                en_json_dict = data
                en_json_valid = True
                ctx.add_info("en", en_json_rel, f"Valid JSON with {len(en_json_dict)} keys")
                # Em dash and empty value check on English itself
                for k, v in en_json_dict.items():
                    if check_em_dash(v):
                        ctx.add_error("en", en_json_rel, f"Key '{k}' contains em dash ('—' / \\u2014)")
                    if not v.strip():
                        ctx.add_error("en", en_json_rel, f"Key '{k}' has empty value")

    en_props_path = desktop_i18n_dir / "messages_en.properties"
    en_props_rel = "desktop/src/horizonport/i18n/messages_en.properties"
    en_props_dict: dict[str, str] = {}
    en_props_lines: dict[str, int] = {}
    en_props_valid = False

    if not en_props_path.exists():
        ctx.add_error("en", en_props_rel, "File does not exist")
    else:
        props, lines_map, errs = parse_properties_file(en_props_path)
        for err in errs:
            ctx.add_error("en", en_props_rel, err)
        if not errs:
            if not props:
                ctx.add_error("en", en_props_rel, "Properties file has no keys")
            else:
                en_props_dict = props
                en_props_lines = lines_map
                en_props_valid = True
                ctx.add_info("en", en_props_rel, f"Valid properties with {len(en_props_dict)} keys")
                for k, v in en_props_dict.items():
                    loc = f"Line {en_props_lines.get(k, 0)} (key '{k}')"
                    if check_em_dash(v):
                        ctx.add_error("en", en_props_rel, f"{loc}: contains em dash ('—' / \\u2014)")
                    if not v.strip():
                        ctx.add_error("en", en_props_rel, f"{loc}: has empty value")
                    if check_messageformat_unescaped_quotes(v):
                        ctx.add_error(
                            "en",
                            en_props_rel,
                            f"{loc}: unescaped single quote in MessageFormat pattern. Double it as ''",
                        )

    en_md_rel = "web/public/making-mods.md"
    en_md_data: dict[str, Any] = {}
    en_md_valid = False

    if not en_md_path.exists():
        ctx.add_error("en", en_md_rel, "File does not exist")
    else:
        mdata, errs = parse_markdown_file(en_md_path)
        for err in errs:
            ctx.add_error("en", en_md_rel, err)
        if not errs:
            en_md_data = mdata
            en_md_valid = True
            ctx.add_info(
                "en",
                en_md_rel,
                f"Valid markdown ({len(en_md_data['code_blocks'])} code blocks, {len(en_md_data['tables'])} tables)",
            )

    # --------------------------------------------------------------------------
    # 2. Non-English Languages Checks
    # --------------------------------------------------------------------------
    non_en_languages = [lang for lang in languages if lang != "en"]

    for lang in non_en_languages:
        # Check website JSON
        target_json_path = web_i18n_dir / f"{lang}.json"
        target_json_rel = f"web/public/i18n/{lang}.json"
        if not target_json_path.exists():
            ctx.add_error(lang, target_json_rel, "File does not exist")
        else:
            t_data, t_errs = parse_json_file(target_json_path)
            for err in t_errs:
                ctx.add_error(lang, target_json_rel, err)
            if not t_errs:
                if not en_json_valid:
                    ctx.add_error(
                        lang,
                        target_json_rel,
                        "Cannot validate against English baseline (en.json is missing or invalid)",
                    )
                else:
                    validate_json_strings(lang, en_json_dict, t_data, target_json_rel, ctx)
                    ctx.add_info(lang, target_json_rel, f"Parsed {len(t_data)} keys")

        # Check desktop properties
        target_props_path = get_properties_file_path(desktop_i18n_dir, lang)
        target_props_rel = f"desktop/src/horizonport/i18n/messages_{lang}.properties"
        if target_props_path is None:
            ctx.add_error(lang, target_props_rel, "File does not exist")
        else:
            props, lines_map, p_errs = parse_properties_file(target_props_path)
            actual_rel = target_props_path.relative_to(root).as_posix()
            for err in p_errs:
                ctx.add_error(lang, actual_rel, err)
            if not p_errs:
                if not en_props_valid:
                    ctx.add_error(
                        lang,
                        actual_rel,
                        "Cannot validate against English baseline (messages_en.properties is missing or invalid)",
                    )
                else:
                    validate_properties_strings(lang, en_props_dict, props, lines_map, actual_rel, ctx)
                    ctx.add_info(lang, actual_rel, f"Parsed {len(props)} keys")

        # Check markdown guide
        target_md_path = web_i18n_dir / f"making-mods.{lang}.md"
        target_md_rel = f"web/public/i18n/making-mods.{lang}.md"
        if not target_md_path.exists():
            ctx.add_error(lang, target_md_rel, "File does not exist")
        else:
            m_data, m_errs = parse_markdown_file(target_md_path)
            for err in m_errs:
                ctx.add_error(lang, target_md_rel, err)
            if not m_errs:
                if not en_md_valid:
                    ctx.add_error(
                        lang,
                        target_md_rel,
                        "Cannot validate against English baseline (making-mods.md is missing or invalid)",
                    )
                else:
                    validate_markdown_guide(lang, en_md_data, m_data, target_md_rel, ctx)
                    ctx.add_info(
                        lang,
                        target_md_rel,
                        f"Parsed {len(m_data['code_blocks'])} code blocks, {len(m_data['tables'])} tables",
                    )

    # --------------------------------------------------------------------------
    # 3. Report Output
    # --------------------------------------------------------------------------
    print(colorize("=" * 72, COLOR_BOLD))
    print(colorize(f" Horizon Translator i18n Checker - Checking {len(languages)} language(s)", COLOR_BOLD))
    print(colorize("=" * 72, COLOR_BOLD))

    passed_count = 0
    failed_count = 0

    for lang in languages:
        entries = ctx.lang_results.get(lang, [])
        has_error = any("ERROR" in e for e in entries)
        if has_error:
            failed_count += 1
            status_tag = colorize("[FAIL]", COLOR_RED + COLOR_BOLD)
        else:
            passed_count += 1
            status_tag = colorize("[PASS]", COLOR_GREEN + COLOR_BOLD)

        print(f"\n{status_tag} Language: {colorize(lang, COLOR_BOLD)}")
        if entries:
            for entry in entries:
                print(entry)
        else:
            print(f"  All checks passed.")

    print("\n" + colorize("=" * 72, COLOR_BOLD))
    if ctx.total_errors == 0:
        summary_msg = f"SUCCESS: All {len(languages)} language(s) passed all checks! (0 errors, {ctx.total_warnings} warnings)"
        print(colorize(summary_msg, COLOR_GREEN + COLOR_BOLD))
        return 0
    else:
        summary_msg = f"FAILURE: {failed_count} language(s) failed with {ctx.total_errors} error(s) and {ctx.total_warnings} warning(s)."
        print(colorize(summary_msg, COLOR_RED + COLOR_BOLD))
        return 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate i18n translations across website JSON, desktop properties, and markdown guides.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Supported languages (22 total):\n  "
            + ", ".join(ALL_LANGUAGES)
            + "\n\nExamples:\n"
            "  python scripts/check-i18n.py               # Validate all 22 languages\n"
            "  python scripts/check-i18n.py --en-only     # Validate English baseline only\n"
            "  python scripts/check-i18n.py --lang fr     # Validate French (against English)\n"
            "  python scripts/check-i18n.py --lang es,de  # Validate Spanish and German\n"
        ),
    )
    parser.add_argument(
        "--lang",
        action="append",
        help="Specific language code to check. Can be repeated or comma-separated.",
    )
    parser.add_argument(
        "--en-only",
        action="store_true",
        help="Check only the English source files (web/public/i18n/en.json, messages_en.properties, making-mods.md).",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="Root directory of Horizon Translator repository. Defaults to parent of scripts/ directory.",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Display informational progress messages.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    # Determine root directory
    if args.root:
        root = args.root.resolve()
    else:
        # Default: scripts/..
        root = Path(__file__).resolve().parent.parent

    # Determine languages to check
    if args.en_only:
        target_languages = ["en"]
    elif args.lang:
        raw_langs: list[str] = []
        for item in args.lang:
            for part in item.split(","):
                cleaned = part.strip()
                if cleaned:
                    raw_langs.append(cleaned)

        # Map case-insensitively to canonical ALL_LANGUAGES codes
        code_map = {c.lower(): c for c in ALL_LANGUAGES}
        code_map["zh_cn"] = "zh-CN"
        code_map["pt_br"] = "pt-BR"

        target_languages = []
        for code in raw_langs:
            normalized = code_map.get(code.lower())
            if not normalized:
                print(
                    colorize(f"Error: Unknown language code '{code}'.", COLOR_RED),
                    file=sys.stderr,
                )
                print(f"Supported languages: {', '.join(ALL_LANGUAGES)}", file=sys.stderr)
                sys.exit(2)
            if normalized not in target_languages:
                target_languages.append(normalized)
    else:
        target_languages = list(ALL_LANGUAGES)

    sys.exit(run_checks(target_languages, root, verbose=args.verbose))


if __name__ == "__main__":
    main()
