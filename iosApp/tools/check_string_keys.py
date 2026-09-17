#!/usr/bin/env python3
"""Verifies that iOS's StringKey ordinal table matches the Kotlin source of truth.

`core/src/commonMain/kotlin/lg/core/scene/StringKey.kt` is the only place
`StringKey` is declared; `iosApp/LightweightGames/Strings.swift` mirrors its
declaration order (by ordinal) in `keysByOrdinal`, and
`iosApp/LightweightGames/Localizable.xcstrings` must carry a lower_snake_case
entry for every one of those keys. This script cross-checks all three and
exits non-zero on any mismatch -- run it after touching `StringKey.kt`,
`Strings.swift`, or `Localizable.xcstrings`.

Usage:
  iosApp/tools/check_string_keys.py
"""
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
KOTLIN_STRING_KEY = REPO_ROOT / "core/src/commonMain/kotlin/lg/core/scene/StringKey.kt"
SWIFT_STRINGS = REPO_ROOT / "iosApp/LightweightGames/Strings.swift"
XCSTRINGS = REPO_ROOT / "iosApp/LightweightGames/Localizable.xcstrings"


def camel_to_snake(name: str) -> str:
    """Kotlin SCREAMING_SNAKE_CASE enum case name -> lower_snake_case key."""
    return name.lower()


def parse_kotlin_enum(path: Path) -> list[str]:
    text = path.read_text()
    start = text.index("public enum class StringKey")
    body_start = text.index("{", start) + 1
    body_end = text.index("}", body_start)
    body = text[body_start:body_end]
    names: list[str] = []
    for line in body.splitlines():
        line = line.split("//", 1)[0].strip()
        if not line:
            continue
        for token in line.split(","):
            token = token.strip()
            if not token:
                continue
            if not re.fullmatch(r"[A-Z][A-Z0-9_]*", token):
                raise ValueError(f"Unexpected token in StringKey.kt enum body: {token!r}")
            names.append(token)
    return names


def parse_swift_keys_by_ordinal(path: Path) -> list[str]:
    text = path.read_text()
    anchor = "keysByOrdinal: [String] = ["
    start = text.index(anchor)
    body_start = start + len(anchor)
    body_end = text.index("]", body_start)
    body = text[body_start:body_end]
    keys: list[str] = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        m = re.match(r'"([a-z0-9_]+)"', line)
        if m:
            keys.append(m.group(1))
    return keys


def parse_xcstrings_keys(path: Path) -> set[str]:
    data = json.loads(path.read_text())
    return set(data["strings"].keys())


def main() -> int:
    kotlin_names = parse_kotlin_enum(KOTLIN_STRING_KEY)
    expected_snake = [camel_to_snake(n) for n in kotlin_names]
    swift_keys = parse_swift_keys_by_ordinal(SWIFT_STRINGS)
    xcstrings_keys = parse_xcstrings_keys(XCSTRINGS)

    errors: list[str] = []

    if swift_keys != expected_snake:
        errors.append(
            "Strings.swift keysByOrdinal does not match StringKey.kt ordinal order.\n"
            f"  Kotlin ({len(expected_snake)}): {expected_snake}\n"
            f"  Swift  ({len(swift_keys)}): {swift_keys}"
        )
    else:
        # Also check lengths lined up 1:1 even if some other bug hid a diff.
        if len(kotlin_names) != len(swift_keys):
            errors.append(
                f"Kotlin has {len(kotlin_names)} StringKey cases but Swift has "
                f"{len(swift_keys)} entries in keysByOrdinal."
            )

    missing_from_xcstrings = [k for k in expected_snake if k not in xcstrings_keys]
    if missing_from_xcstrings:
        errors.append(
            "Localizable.xcstrings is missing entries for: " + ", ".join(missing_from_xcstrings)
        )

    if errors:
        for e in errors:
            print("ERROR:", e, file=sys.stderr)
        print(
            f"\n{len(kotlin_names)} StringKey ordinals checked; {len(errors)} problem(s) found.",
            file=sys.stderr,
        )
        return 1

    print(f"OK: {len(kotlin_names)} StringKey ordinals match across StringKey.kt, "
          f"Strings.swift, and Localizable.xcstrings.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
