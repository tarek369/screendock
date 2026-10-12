#!/usr/bin/env python3
"""Validate Google Play listing drafts (<locale>.md) in this directory.

Stdlib only. Exit code 0 when every locale passes, 1 otherwise.
Usage: python3 validate.py
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

LOCALES = ["en-US", "de-DE", "fr-FR", "es-ES", "ja-JP", "pt-BR"]

TITLE_MAX = 30
SHORT_MAX = 80
FULL_MAX = 4000
FULL_MIN = 800
MAX_AIRPLAY_MENTIONS = 10

REQUIRED_SECTIONS = ("Title", "Short description", "Full description")

# Disclosures every locale must carry in the full description (case-insensitive).
DISCLOSURES = {
    "en-US": ["DRM", "not affiliated with Apple", "trademark of Apple Inc."],
    "de-DE": ["DRM", "nicht mit Apple", "Marke der Apple Inc."],
    "fr-FR": ["DRM", "non affilié à Apple", "marque d'Apple Inc."],
    "es-ES": ["DRM", "no está afiliado a Apple", "marca comercial de Apple Inc."],
    "ja-JP": ["DRM", "提携", "Apple Inc.の商標"],
    "pt-BR": ["DRM", "não é afiliado à Apple", "marca registrada da Apple Inc."],
}

# Claims that must never appear, in any locale.
FORBIDDEN_PATTERNS = {
    r"\b120\b": "outdated 120 fps claim",
    r"touch": "touch forwarding claim (feature does not work)",
    r"\b(tactile|tactil|táctil|tátil|berühr\w*|toque)\b|タッチ": "touch forwarding claim (translated)",
    r"airscreen|airmirror|airreceiver|air receiver|ionitech|softmedia|jqssun|airplay receiver & server": "competitor name",
}

EMOJI_RE = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿️]")
FPS_RE = re.compile(r"\b60\s?fps\b", re.IGNORECASE)
AIRPLAY_RE = re.compile(r"airplay", re.IGNORECASE)
HEADING_RE = re.compile(r"^##\s+(.+?)\s*$")


def parse_sections(text):
    """Return {heading: body} for each '## ' heading in the markdown text."""
    sections = {}
    current = None
    buf = []
    for line in text.splitlines():
        match = HEADING_RE.match(line)
        if match:
            if current is not None:
                sections[current] = "\n".join(buf).strip()
            current = match.group(1)
            buf = []
        elif current is not None:
            buf.append(line)
    if current is not None:
        sections[current] = "\n".join(buf).strip()
    return sections


def check_locale(locale, path):
    errors = []
    if not path.is_file():
        return [f"missing file {path.name}"]

    sections = parse_sections(path.read_text(encoding="utf-8"))
    for name in REQUIRED_SECTIONS:
        if name not in sections or not sections[name]:
            errors.append(f"missing or empty section '## {name}'")
    if errors:
        return errors

    title = sections["Title"]
    short = sections["Short description"]
    full = sections["Full description"]

    if "\n" in title:
        errors.append("title must be one line")
    if len(title) > TITLE_MAX:
        errors.append(f"title {len(title)} chars > {TITLE_MAX}")
    if not AIRPLAY_RE.search(title):
        errors.append("title must contain 'AirPlay'")

    if "\n" in short:
        errors.append("short description must be one line")
    if len(short) > SHORT_MAX:
        errors.append(f"short description {len(short)} chars > {SHORT_MAX}")
    if not AIRPLAY_RE.search(short):
        errors.append("short description must contain 'AirPlay'")

    if len(full) > FULL_MAX:
        errors.append(f"full description {len(full)} chars > {FULL_MAX}")
    if len(full) < FULL_MIN:
        errors.append(f"full description {len(full)} chars < {FULL_MIN}")
    if not FPS_RE.search(full):
        errors.append("full description must state '60 fps'")

    for disclosure in DISCLOSURES[locale]:
        if disclosure.lower() not in full.lower():
            errors.append(f"missing disclosure: {disclosure!r}")

    mentions = len(AIRPLAY_RE.findall(full))
    if mentions > MAX_AIRPLAY_MENTIONS:
        errors.append(f"'AirPlay' appears {mentions}x in full description (> {MAX_AIRPLAY_MENTIONS}, keyword stuffing)")

    for label, text in (("title", title), ("short", short), ("full", full)):
        for pattern, reason in FORBIDDEN_PATTERNS.items():
            if re.search(pattern, text, re.IGNORECASE):
                errors.append(f"{label}: forbidden {reason} (/{pattern}/)")
        if EMOJI_RE.search(text):
            errors.append(f"{label}: emoji not allowed")

    return errors


def main():
    failed = False
    for locale in LOCALES:
        errors = check_locale(locale, HERE / f"{locale}.md")
        if errors:
            failed = True
            print(f"FAIL {locale}")
            for err in errors:
                print(f"  - {err}")
        else:
            print(f"OK   {locale}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
