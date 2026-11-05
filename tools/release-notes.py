#!/usr/bin/env python3
"""Writes a draft release's notes from the previous release's notes and the pull requests since.

    release-notes.py REPO TAG SHA256 > notes.md

REPO is owner/name and TAG the new release's tag (vX.Y.Z). It reads, with the GitHub CLI, the
latest published release's notes and the commits from that release's tag to TAG. Commits named
"... (#N)" are squash-merged pull requests into dev; "Merge pull request #N from <owner>/dev" are
release pull requests into master and are skipped; another "Merge pull request #N" is a pull
request merged with a merge commit.

The notes keep the previous release's sections (Install, Privacy, Verify the download, Good to
know) with the version in the heading and every SHA-256 set to this installer's. Their "What's
new" sections are replaced by one for this version, made from each pull request's "Release note"
section, and a link to the earlier releases. A note of "none", or a pull request from Dependabot,
adds nothing.

A reminder block goes on top when the notes need a maintainer's attention before publishing: a
pull request without a release note, one that ticks the box for a change to what the app sends or
to a privacy setting, a commit that didn't come from a pull request, or no notes at all.
"""

import json
import re
import subprocess
import sys

RELEASES_URL = "https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases"
PRIVACY_BOX = re.compile(r"^\s*-\s*\[[xX]\]\s*Changes what the app sends", re.MULTILINE)
SECTION = re.compile(r"^##\s+Release note\s*$(.*?)(?=^##\s|\Z)", re.MULTILINE | re.DOTALL)
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def release_note(body):
    """The text of a pull request's "Release note" section, or None if it has none."""
    m = SECTION.search(body or "")
    if not m:
        return None
    text = re.sub(r"^\s*-\s*\[[ xX]\].*$", "", COMMENT.sub("", m.group(1)), flags=re.MULTILINE)
    text = text.strip()
    return text


def as_bullets(text):
    """A note as list items: kept as written if it is already a list, otherwise one item."""
    if text.lstrip().startswith(("- ", "* ")):
        return text
    return "- " + " ".join(text.split())


def pull_requests(commits):
    """The pull request numbers in these commits, in order, and the commits that came from none.

    Each commit is (sha, first line of its message)."""
    numbers, loose = [], []
    for sha, subject in commits:
        squashed = re.search(r"\(#(\d+)\)$", subject)
        merged = re.match(r"Merge pull request #(\d+) from [^/\s]+/(\S+)", subject)
        if squashed:
            numbers.append(int(squashed.group(1)))
        elif merged and merged.group(2) != "dev":
            numbers.append(int(merged.group(1)))
        elif not merged:
            loose.append(f"{sha[:7]} {subject}")
    return list(dict.fromkeys(numbers)), loose


def whats_new(version, prs, loose=()):
    """The "What's new" section and the reminder lines for these pull requests."""
    items, reminders = [], []
    for commit in loose:
        reminders.append(f"Commit {commit} isn't from a pull request: add a note if people would "
                         "notice it.")
    for pr in prs:
        if pr.get("author", {}).get("login", "").startswith(("dependabot", "app/dependabot")):
            continue
        ref = f"#{pr['number']} ({pr['title']})"
        note = release_note(pr.get("body"))
        if PRIVACY_BOX.search(pr.get("body") or ""):
            reminders.append(f"{ref} changes what the app sends or a privacy setting: check Privacy "
                             "and say what happens to people who update.")
        if note is None or note == "":
            reminders.append(f"{ref} has no release note.")
        elif note.lower().rstrip(".") != "none":
            items.append(as_bullets(note))
    if not items:
        reminders.append("No pull request has a release note: write the What's new section.")
    section = [f"### What's new in {version}", ""]
    section += items + [""] if items else []
    section += [f"Changes in earlier versions are in their [release notes]({RELEASES_URL}).", ""]
    return "\n".join(section) + "\n", reminders


def notes(version, sha256, previous, prs, loose=()):
    lines = previous.replace("\r\n", "\n").split("\n")
    # Anything above the title (a reminder block on an unpublished draft) is dropped.
    start = next((i for i, line in enumerate(lines) if line.startswith("## ")), 0)
    body = "\n".join(lines[start:])
    body = re.sub(r"^(## .*?)\d+\.\d+\.\d+", lambda m: m.group(1) + version, body, count=1,
                  flags=re.MULTILINE)
    body = re.sub(r"\b[0-9a-f]{64}\b", sha256, body)

    section, reminders = whats_new(version, prs, loose)
    # Every "What's new" section, through to the next section that isn't one, becomes the new one.
    old = re.compile(r"^### What's new in .*?(?=^### (?!What's new)|\Z)", re.MULTILINE | re.DOTALL)
    first = old.search(body)
    if first:
        body = body[:first.start()] + section + old.sub("", body[first.start():]).lstrip("\n")
    else:
        at = body.find("\n### ")
        at = len(body) if at < 0 else at + 1
        body = body[:at] + section + "\n" + body[at:]

    if reminders:
        block = ["> **Draft: check these before publishing, then delete this block.**", ">"]
        block += [f"> - {r}" for r in reminders]
        body = "\n".join(block) + "\n\n" + body
    return body.rstrip("\n") + "\n"


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def main(argv):
    if len(argv) != 4:
        sys.exit(__doc__)
    repo, tag, sha256 = argv[1:]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        sys.exit(f"not a version tag: {tag}")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        sys.exit(f"not a SHA-256: {sha256}")
    latest = json.loads(gh("release", "view", "--repo", repo, "--json", "tagName,body"))
    compare = json.loads(gh("api", f"repos/{repo}/compare/{latest['tagName']}...{tag}"))
    commits = [(c["sha"], c["commit"]["message"].split("\n", 1)[0]) for c in compare["commits"]]
    numbers, loose = pull_requests(commits)
    prs = [json.loads(gh("pr", "view", str(n), "--repo", repo, "--json", "number,title,body,author"))
           for n in numbers]
    sys.stdout.write(notes(tag[1:], sha256, latest["body"], prs, loose))


if __name__ == "__main__":
    main(sys.argv)
