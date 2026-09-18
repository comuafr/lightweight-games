#!/usr/bin/env python3
"""
new_game.py — scaffold a new game from the templates into the monorepo.

Usage:
    python3 new_game.py <slug> "<App Name>" "<package_suffix>"

Example:
    python3 new_game.py snake "Snake" snake
    python3 new_game.py mine-sweeper "Mine Sweeper" minesweeper

This copies templates/ into games/<slug>/ and replaces the placeholders. The
game is a self-contained Gradle project; it shares the repo-root Gradle wrapper
and the root .github/workflows CI (which auto-discovers games/*). The agent then
fills in the TODOs (game logic, rendering, tests, sounds, icon, docs) per
WORKFLOW.md.
"""
import os, sys, shutil, datetime, re

PLACEHOLDERS_HELP = """
Remaining text placeholders the agent must fill in the generated files:
  {{ONE_LINE_PITCH}} {{FEATURES_LIST}} {{CONTROLS_LIST}} {{SIZE}}
  {{SHORT_DESC}} {{FULL_DESC}} {{CATEGORY}} {{AGENT_FLAGS}}
"""

def main():
    if len(sys.argv) != 4:
        print(__doc__); sys.exit(1)
    slug, app_name, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
    if not re.fullmatch(r"[a-z0-9-]+", slug):
        print("slug must be lowercase letters, digits, hyphens"); sys.exit(1)
    if not re.fullmatch(r"[a-z0-9]+", pkg):
        print("package suffix must be lowercase letters/digits only"); sys.exit(1)

    here = os.path.dirname(os.path.abspath(__file__))
    src = os.path.join(here, "templates")
    dst = os.path.join(here, "games", slug)
    if os.path.exists(dst):
        print(f"{dst} already exists"); sys.exit(1)
    os.makedirs(os.path.join(here, "games"), exist_ok=True)

    shutil.copytree(src, dst)

    # Rename the package source dirs SLUG -> pkg
    for base in ["app/src/main/java/com/lightweight",
                 "app/src/test/java/com/lightweight"]:
        old = os.path.join(dst, base, "SLUG")
        new = os.path.join(dst, base, pkg)
        if os.path.isdir(old):
            os.rename(old, new)

    repl = {
        "{{SLUG}}": slug,
        "{{SLUG_PKG}}": pkg,
        "{{APP_NAME}}": app_name,
        "{{YEAR}}": str(datetime.date.today().year),
    }
    for root, _, files in os.walk(dst):
        for fn in files:
            p = os.path.join(root, fn)
            try:
                with open(p, "r", encoding="utf-8") as f:
                    txt = f.read()
            except (UnicodeDecodeError, IsADirectoryError):
                continue
            new = txt
            for k, v in repl.items():
                new = new.replace(k, v)
            if new != txt:
                with open(p, "w", encoding="utf-8") as f:
                    f.write(new)

    print(f"Scaffolded ./games/{slug}/  (package com.lightweight.{pkg})")
    print(f"Build it with:  ./gradlew -p games/{slug} test")
    print(PLACEHOLDERS_HELP)

if __name__ == "__main__":
    main()
