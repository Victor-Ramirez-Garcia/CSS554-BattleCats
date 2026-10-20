"""Read-only Git version catalog and numbered choices for local simulations.

Branches and tags retain their full ref names so similarly named versions are
never ambiguous. The simulator resolves a selected ref to a commit before it
snapshots sources; this module never checks out, fetches, or modifies anything.
"""

from __future__ import annotations

from pathlib import Path
import subprocess


def _git(root: Path, *arguments: str, required: bool = True) -> str | None:
    try:
        result = subprocess.run(
            ["git", *arguments], cwd=root, capture_output=True,
            text=True, encoding="utf-8", errors="replace",
        )
    except OSError as error:
        raise ValueError(f"Cannot read Git versions: {error}") from error
    if result.returncode:
        if not required:
            return None
        raise ValueError("Cannot read Git versions: " + result.stderr.strip())
    return result.stdout


def _commit(root: Path, ref: str) -> dict | None:
    output = _git(root, "log", "-1", "--format=%H%x00%cs%x00%s", ref, "--", required=False)
    if not output:
        return None
    fields = output.rstrip("\n").split("\0", 2)
    if len(fields) != 3:
        return None
    commit, date, subject = fields
    return {"commit": commit, "subject": subject, "date": date}


def version_catalog(root: Path, limit: int = 12) -> list[dict]:
    """List branches, commit tags, and up to ``limit`` recent reachable commits.

    All branch/tag names appear even when they point at the same commit. Commit
    entries are also useful for selecting a fixed iteration rather than a moving
    branch. Symbolic refs (including remote HEAD aliases), non-commit tags, and
    refs whose objects cannot be read are omitted. A zero limit lists refs only.
    """
    if limit < 0:
        raise ValueError("The recent commit limit must be zero or greater.")
    output = _git(root, "for-each-ref", "--sort=refname", "--format=%(refname)%00%(symref)",
                  "refs/heads/", "refs/remotes/", "refs/tags/")
    entries = []
    tips = set()
    for line in (output or "").splitlines():
        ref, _, symbolic_target = line.partition("\0")
        if symbolic_target:
            continue
        metadata = _commit(root, ref)
        if metadata is None:
            continue
        kind = ("local branch" if ref.startswith("refs/heads/") else
                "remote branch" if ref.startswith("refs/remotes/") else "tag")
        entries.append({"ref": ref, **metadata, "kind": kind})
        tips.add(metadata["commit"])

    # Include a detached HEAD without requiring it to have a branch or tag.
    head = _commit(root, "HEAD")
    if head is not None:
        tips.add(head["commit"])
    if limit and tips:
        recent = _git(root, "log", f"--max-count={limit}", "--date-order",
                      "--format=%H%x00%cs%x00%s", *sorted(tips), "--")
        for line in (recent or "").splitlines():
            commit, date, subject = line.split("\0", 2)
            entries.append({"ref": commit, "commit": commit, "subject": subject,
                            "date": date, "kind": "commit"})
    return entries


def _label(ref: str) -> str:
    for prefix in ("refs/heads/", "refs/remotes/", "refs/tags/"):
        if ref.startswith(prefix):
            return ref[len(prefix):]
    return ref


def _display(value: str) -> str:
    # Git subjects are single lines, but control characters should not affect a
    # terminal or make one numbered choice appear to be multiple choices.
    return " ".join("".join(char if char.isprintable() else " " for char in value).split())


def print_versions(entries: list[dict], output_fn=print) -> None:
    """Print stable one-based numbers shared by the two selection prompts."""
    if not entries:
        output_fn("No committed versions found. Fetch a remote or create a commit first.")
        return
    for number, entry in enumerate(entries, 1):
        output_fn(f"{number:>3}. [{entry['kind']}] {_display(_label(entry['ref']))} | "
                  f"{entry['commit'][:12]} | {entry['date']} | {_display(entry['subject'])}")


def choose_versions(entries: list[dict], default_baseline: str,
                    input_fn=input, output_fn=print) -> tuple[str | None, str]:
    """Choose a candidate (None means saved files) and a committed baseline.

    Blank candidate input selects WORKTREE. Blank baseline input selects the
    supplied default when it exists in the catalog. Invalid choices prompt
    again; EOF and Ctrl+C cancel explicitly rather than accepting defaults.
    """
    if not entries:
        raise ValueError("No committed baseline is available. Fetch main or create a commit first.")
    default = next((number for number, entry in enumerate(entries, 1)
                    if entry["ref"] == default_baseline), None)
    if default is None:
        # Prefer the remote alias for an abbreviated default such as upstream/main.
        default = next((number for number, entry in enumerate(entries, 1)
                        if entry["ref"] == "refs/remotes/" + default_baseline), None)
    if default is None:
        default = next((number for number, entry in enumerate(entries, 1)
                        if _label(entry["ref"]) == default_baseline), None)

    output_fn("Choose the code versions to compare; no checkout is needed.")
    output_fn("Candidate: the bot changes to evaluate. Baseline: the reference bot to compare against.")
    output_fn("WORKTREE uses saved local files for an exploratory test; choose a commit for a reproducible comparison.")
    output_fn("  0. WORKTREE - saved local Java files (candidate only)")
    print_versions(entries, output_fn)

    def read_choice(role: str, default_number: int | None, minimum: int) -> int:
        if default_number is None:
            prompt = f"{role} [enter a number]: "
        else:
            label = "WORKTREE" if default_number == 0 else _label(entries[default_number - 1]["ref"])
            prompt = f"{role} [{default_number}: {label}; Enter accepts]: "
        while True:
            try:
                answer = input_fn(prompt).strip()
            except (EOFError, KeyboardInterrupt) as error:
                raise ValueError("Version selection cancelled; no simulation was started. "
                                 "Use explicit candidate/baseline refs if terminal input is unavailable.") from error
            if not answer and default_number is not None:
                return default_number
            if answer.isascii() and answer.isdigit() and minimum <= int(answer) <= len(entries):
                return int(answer)
            output_fn(f"Choose a number from {minimum} to {len(entries)}" +
                      (", or press Enter for the displayed default." if default_number is not None else "."))
            if minimum == 0:
                output_fn("  0. WORKTREE - saved local Java files (candidate only)")
            print_versions(entries, output_fn)

    candidate = read_choice("Candidate", 0, 0)
    if default is None:
        output_fn(f"Default baseline {_display(default_baseline)!r} is unavailable; choose a listed version.")
    baseline = read_choice("Baseline", default, 1)
    return (None if candidate == 0 else entries[candidate - 1]["ref"], entries[baseline - 1]["ref"])
