#!/usr/bin/env python3
"""Release notes from the bodies of the pull requests merged between two commits.

usage: release_notes.py BASE HEAD OUT_DIR LOCALE
  OUT_DIR/notes.md             every PR with its notes (for the job summary)
  OUT_DIR/whatsnew/whatsnew-LOCALE   the Play "What's new" text (Play allows 500 characters)

A PR body can have a section "Release notes" (or "What's new" / "Wat is er nieuw"); that section is used. Without one
the whole body is used. Checklists, headings, "Closes #1" lines and the generated-by footers are left out.
Needs GITHUB_REPOSITORY and an authenticated `gh`.
"""
import json
import os
import re
import subprocess
import sys

PLAY_LIMIT = 500
FALLBACK = "Bug fixes and improvements."
SECTION = re.compile(r"^#{1,6}[ \t]*(release notes|what'?s new|wat is er nieuw)[ \t]*:?[ \t]*$(.*?)(?=^#{1,6}[ \t]|\Z)", re.S | re.I | re.M)
SKIP = re.compile(r"^(#|[-*+][ \t]*\[[ xX]\]|[-*_]{3,}$|>|\|)|(closes|fixes|resolves)[ \t]+#\d+|generated with|claude\.ai|co-authored-by", re.I)


def sh(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def merged_prs(base, head):
    """The PRs whose commits are in base..head, oldest first. Merges of development into main are not feature PRs."""
    repo = os.environ["GITHUB_REPOSITORY"]
    found = {}
    for sha in sh("git", "rev-list", "--reverse", f"{base}..{head}").split():
        for pr in json.loads(sh("gh", "api", f"repos/{repo}/commits/{sha}/pulls")):
            if pr.get("merged_at") and pr["head"]["ref"] not in ("development", "main"):
                found.setdefault(pr["number"], pr)
    return sorted(found.values(), key=lambda p: p["merged_at"])


def bullets(pr):
    body = re.sub(r"<!--.*?-->", "", pr.get("body") or "", flags=re.S)
    m = SECTION.search(body)
    if m:
        body = m.group(2)
    out = []
    for line in body.splitlines():
        line = line.strip()
        if not line or SKIP.search(line):
            continue
        line = re.sub(r"^([-*+]|\d+[.)])[ \t]+", "", line)
        line = re.sub(r"\*\*|__|`", "", line)  # bold and code marks; single * and _ stay (ACCESS_LOCAL_NETWORK, *.local)
        line = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", line)
        line = re.sub(r"\s+", " ", line).strip()
        if line and line not in out:
            out.append(line)
    if not out:  # an empty body: the title says what it is
        title = re.sub(r"^\w+(\([^)]*\))?!?:\s*", "", pr["title"]).strip()
        out.append(title[:1].upper() + title[1:])
    return out


def short(item, limit=140):
    return item if len(item) <= limit else item[: limit - 1].rsplit(" ", 1)[0] + "…"


def whatsnew(per_pr):
    """Fits the Play limit by giving every PR its first line, then every PR its second line, and so on."""
    chosen = [[] for _ in per_pr]
    used = 0
    for depth in range(max((len(p) for p in per_pr), default=0)):
        for i, items in enumerate(per_pr):
            if depth >= len(items):
                continue
            line = "• " + short(items[depth])
            cost = len(line) + (1 if used else 0)
            if used + cost <= PLAY_LIMIT and items[depth] not in sum(chosen, []):
                chosen[i].append(line)
                used += cost
    text = "\n".join(l for lines in chosen for l in lines)
    return text or FALLBACK


def main():
    base, head, out_dir, locale = sys.argv[1:5]
    prs = merged_prs(base, head)
    sections, per_pr = [], []
    for pr in prs:
        items = bullets(pr)
        per_pr.append(items)
        sections.append(f"### {pr['title']} (#{pr['number']})\n" + "\n".join(f"- {i}" for i in items))
    os.makedirs(os.path.join(out_dir, "whatsnew"), exist_ok=True)
    notes = whatsnew(per_pr)
    with open(os.path.join(out_dir, "notes.md"), "w") as f:
        f.write(f"{len(prs)} pull request(s) in `{base}..{head}`\n\n" + "\n\n".join(sections) if prs else "No pull requests found.\n")
    with open(os.path.join(out_dir, "whatsnew", f"whatsnew-{locale}"), "w") as f:
        f.write(notes)
    total = sum(len(p) for p in per_pr)
    print(f"{len(prs)} PR(s), {total} line(s); Play text {len(notes)}/{PLAY_LIMIT} characters, {len(notes.splitlines())} of {total} lines")


if __name__ == "__main__":
    main()
