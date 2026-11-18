"""Compare two simulation runs that played the same opponent.

Use it when wins against a strong opponent do not move: run the earlier and the
later candidate against the same baseline ref, then compare the margin metrics
of the two runs case by case.

    python tools/sim_compare.py matches/<earlier run> matches/<later run>

Only cases completed in both runs with the same map, starting side and seed
are compared. Both runs must record the same baseline commit, baseline package
and engine version; anything else would compare two different opponents.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import re
import sys

from sim_report import (
    MARGIN_METRICS, _escape, _pairs, fixed_checkpoint, format_metric, margin_values)


class RunMismatch(ValueError):
    """The two runs did not play the same opponent under the same conditions."""


def load_run(folder: Path) -> dict:
    folder = Path(folder)
    try:
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
        with (folder / "results.csv").open(newline="", encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
    except FileNotFoundError as error:
        raise ValueError(f"{folder} is not a simulation run folder: missing {Path(error.filename).name}") from error
    return dict(folder=folder, manifest=manifest, rows=rows)


def _opponent(manifest: dict) -> tuple:
    packages = manifest.get("packages") if isinstance(manifest.get("packages"), dict) else {}
    return (manifest.get("baseline_commit"), packages.get("baseline"), manifest.get("engine_version"))


def _candidate(manifest: dict) -> str:
    commit = manifest.get("candidate_commit")
    if commit:
        return f"{manifest.get('candidate_ref') or 'ref'} ({str(commit)[:12]})"
    snapshot = manifest.get("working_tree_sha256") or ""
    return f"saved local files (snapshot {snapshot[:12] or 'not recorded'})"


def _key(row: dict) -> tuple:
    return tuple(str(row.get(name)) for name in ("Map", "CandidateSide", "Seed"))


def matched_cases(before: dict, after: dict) -> tuple[list[str], list[str]]:
    """Cases completed in both runs under the same conditions, and those left out."""
    finals = [{str(pair[0]["Case"]): pair for pair in _pairs(run["rows"], "FINAL")}
              for run in (before, after)]
    shared = sorted(case for case in finals[0].keys() & finals[1].keys()
                    if _key(finals[0][case][0]) == _key(finals[1][case][0]))
    planned = {str(row.get("Case")) for run in (before, after) for row in run["rows"]}
    return shared, sorted(planned - set(shared))


def _window(run: dict, checkpoint: str, cases: list[str]) -> list[tuple[dict, dict]]:
    by_case = {str(pair[0]["Case"]): pair for pair in _pairs(run["rows"], checkpoint)}
    return [by_case[case] for case in cases]


def _common_fixed(before: dict, after: dict, cases: list[str]) -> str | None:
    rounds = []
    for run in (before, after):
        final = _window(run, "FINAL", cases)
        checkpoint = fixed_checkpoint(run["rows"], final)
        if checkpoint is None:
            return None
        rounds.append(int(checkpoint[6:]))
    checkpoint = f"ROUND_{min(rounds)}"
    # Runs written by an older reader may sample different rounds, so confirm
    # the earlier of the two fixed rounds exists for every case in both runs.
    for run in (before, after):
        if {str(pair[0]["Case"]) for pair in _pairs(run["rows"], checkpoint)} < set(cases):
            return None
    return checkpoint


def _change(kind: str, before, after) -> str:
    if before is None or after is None:
        return "n/a"
    if kind == "share":
        return f"{(after - before) * 100:+.1f} pts"
    difference = after - before
    return f"{difference:+,.1f}" if isinstance(difference, float) else f"{difference:+,}"


def _metric_table(before_pairs, after_pairs) -> list[str]:
    lines = ["| Measurement | Before: candidate | After: candidate | Change | Before: opponent | After: opponent |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    # Runs from older readers lack some columns, so match rows by label, never by position.
    before = {label: (kind, cand, opp) for label, kind, cand, opp in margin_values(before_pairs)}
    after = {label: (kind, cand, opp) for label, kind, cand, opp in margin_values(after_pairs)}
    for label, _, kind in MARGIN_METRICS:
        if label not in before and label not in after:
            continue
        cand_before, opp_before = before.get(label, (kind, None, None))[1:]
        cand_after, opp_after = after.get(label, (kind, None, None))[1:]
        lines.append(f"| {label} | {format_metric(kind, cand_before)} | {format_metric(kind, cand_after)} | "
                     f"{_change(kind, cand_before, cand_after)} | {format_metric(kind, opp_before)} | "
                     f"{format_metric(kind, opp_after)} |")
    return lines


def _share(pair) -> str:
    ours, theirs = (int(row["DeliveredCheese"]) for row in pair)
    return f"{ours / (ours + theirs) * 100:.0f}%" if ours + theirs else "n/a"


def _survival(pair) -> str:
    value = pair[0].get("StartingKingDeathRound", "")
    if str(value).strip() == "":
        return "n/a"
    return str(int(value) or f"alive ({pair[0]['Round']})")


def _wins(pairs) -> int:
    return sum(str(pair[0].get("Winner")) == "candidate" for pair in pairs)


def compare(before: dict, after: dict) -> tuple[str, dict]:
    """Markdown comparison and a short numeric summary. Raises RunMismatch."""
    if _opponent(before["manifest"]) != _opponent(after["manifest"]):
        raise RunMismatch("The runs played different opponents or engines: "
                          f"{_opponent(before['manifest'])} vs {_opponent(after['manifest'])}. "
                          "Rerun one of them with the same -PsimBaseline and -PsimBaselinePackage.")
    cases, excluded = matched_cases(before, after)
    if not cases:
        raise RunMismatch("No case completed in both runs with the same map, starting side and seed.")
    commit, package, engine = _opponent(before["manifest"])
    windows = [("Final", "FINAL")]
    fixed = _common_fixed(before, after, cases)
    if fixed:
        windows.append((f"Round {fixed[6:]} (equal playing time)", fixed))
    lines = ["# Candidate comparison against one opponent", "",
             f"Before: {_escape(_candidate(before['manifest']))} — `{_escape(before['folder'].name)}`", "",
             f"After: {_escape(_candidate(after['manifest']))} — `{_escape(after['folder'].name)}`", "",
             f"Opponent: {_escape(before['manifest'].get('baseline_ref') or 'baseline')} "
             f"(commit {_escape(str(commit)[:12])}, package {_escape(package)}); engine {_escape(engine)}", "",
             f"Compared cases: **{len(cases)}** completed in both runs with the same map, side and seed."]
    if excluded:
        lines.append(f"Excluded (missing, incomplete or different conditions): {_escape(', '.join(excluded))}.")
    before_final, after_final = (_window(run, "FINAL", cases) for run in (before, after))
    lines.extend(["", f"Candidate wins: before **{_wins(before_final)}/{len(cases)}**, "
                  f"after **{_wins(after_final)}/{len(cases)}**.", ""])
    for title, checkpoint in windows:
        lines.extend([f"## {title}", ""])
        lines.extend(_metric_table(_window(before, checkpoint, cases), _window(after, checkpoint, cases)))
        lines.append("")
    lines.extend(["## Cases", "",
                  "| Case | End round | Winner | Candidate cheese share | Starting king died |",
                  "| --- | --- | --- | --- | --- |"])
    for case, old, new in zip(cases, before_final, after_final):
        lines.append(f"| {_escape(case)} | {old[0]['FinalRound']} → {new[0]['FinalRound']} | "
                     f"{_escape(old[0]['Winner'])} → {_escape(new[0]['Winner'])} | "
                     f"{_share(old)} → {_share(new)} | {_survival(old)} → {_survival(new)} |")
    lines.extend(["", "Lower is better for losses and bites taken; higher is better for deliveries, share, "
                  "survival and bites dealt. The engine is seeded per map, but any difference between the "
                  "two candidates, including unrelated changes, can move these numbers. Treat one run per "
                  "side as evidence about these maps, not a general result.", ""])
    share = [margin_values(pairs) for pairs in (before_final, after_final)]
    summary = dict(cases=len(cases), wins=(_wins(before_final), _wins(after_final)),
                   share=tuple(next((value[2] for value in table if value[1] == "share"), None)
                               for table in share))
    return "\n".join(lines), summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("before", type=Path, help="Run folder of the earlier candidate")
    parser.add_argument("after", type=Path, help="Run folder of the later candidate")
    parser.add_argument("--output", type=Path,
                        help="Markdown file to write; default: compare-vs-<before>.md in the later run folder")
    args = parser.parse_args(argv)
    before, after = load_run(args.before), load_run(args.after)
    markdown, summary = compare(before, after)
    name = re.sub(r"[^A-Za-z0-9_.-]", "_", before["folder"].resolve().name)
    output = args.output or after["folder"] / f"compare-vs-{name}.md"
    output.write_text(markdown, encoding="utf-8", newline="\n")
    # ASCII arrows: a redirected Windows console may not encode Unicode.
    shares = " -> ".join(format_metric("share", value) for value in summary["share"])
    print(f"{summary['cases']} cases; candidate wins {summary['wins'][0]} -> {summary['wins'][1]}; "
          f"cheese share {shares}.\nReport: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"Comparison stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
