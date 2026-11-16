"""Readable summaries of paired simulation metrics from authoritative replays.

Only complete candidate/baseline pairs contribute to totals. Keep every input
row in results.csv so an interrupted run can still be inspected.
"""

from __future__ import annotations

from collections import Counter, defaultdict
import csv
import os
from pathlib import Path
import re
from urllib.parse import quote


FIELDS = [
    "Case", "Map", "CandidateSide", "Role", "Checkpoint", "Round", "Side",
    "Team", "JavaPackage", "Package", "Seed", "DeliveredCheese", "ReserveCheese", "Kings", "BabyRats",
    "CatDamage", "Cooperation", "Winner", "WinType", "FinalRound",
    "LastKingAliveRound",
]


def _number(row: dict, key: str) -> int:
    return int(row[key])


def _escape(value) -> str:
    return (str(value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("|", "&#124;")
            .replace("[", "&#91;").replace("]", "&#93;")
            .replace("\r", " ").replace("\n", " ").replace("`", "&#96;"))


def _pairs(rows: list[dict], checkpoint: str) -> list[tuple[dict, dict]]:
    cases = defaultdict(lambda: defaultdict(list))
    for row in rows:
        if row.get("Checkpoint") == checkpoint and row.get("Role") in ("candidate", "baseline"):
            cases[str(row["Case"])][row["Role"]].append(row)
    result = []
    for case in sorted(cases):
        roles = cases[case]
        if len(roles["candidate"]) != 1 or len(roles["baseline"]) != 1:
            continue
        candidate, baseline = roles["candidate"][0], roles["baseline"][0]
        # A pair must describe one match at the same actual checkpoint.
        if any(str(candidate.get(key)) != str(baseline.get(key))
               for key in ("Map", "CandidateSide", "Round", "Seed", "FinalRound", "Winner", "WinType")):
            continue
        round_number = _number(candidate, "Round")
        final_round = _number(candidate, "FinalRound")
        if checkpoint == "FINAL" and round_number != final_round:
            continue
        if checkpoint.startswith("ROUND_") and (round_number != int(checkpoint[6:])
                                                or final_round < round_number):
            continue
        if checkpoint == "COMMON_1000" and round_number != min(1000, final_round):
            continue
        result.append((candidate, baseline))
    return result


def fixed_checkpoint(rows: list[dict], final: list[tuple[dict, dict]]) -> str | None:
    """Latest ROUND_n checkpoint recorded for every completed pair, if any.

    Strong opponents can end games early, so final totals mix durations. The
    fixed round compares all completed games at the same time.
    """
    if not final:
        return None
    cases = {str(pair[0]["Case"]) for pair in final}
    best = None
    for checkpoint in {str(row.get("Checkpoint")) for row in rows}:
        if not re.fullmatch(r"ROUND_\d+", checkpoint):
            continue
        if {str(pair[0]["Case"]) for pair in _pairs(rows, checkpoint)} >= cases:
            if best is None or int(checkpoint[6:]) > int(best[6:]):
                best = checkpoint
    return best


# (label, column, aggregate). "sum" totals all games; "mean" averages per game.
# Lower is better for losses and bites taken; the report states values, not verdicts.
MARGIN_METRICS = [
    ("Delivered cheese", "DeliveredCheese", "sum"),
    ("Share of cheese delivered", "DeliveredCheese", "share"),
    # LastKingAliveRound is a whole-match value even in ROUND_n rows, so it
    # would mislabel fixed-round windows; survival here is per checkpoint.
    ("Starting king survival (rounds, mean)", "StartingKingDeathRound", "survival"),
    ("First delivery round (mean of games with one)", "FirstDeliveryRound", "first"),
    ("Baby Rats spawned", "RatsSpawned", "sum"),
    ("Peak Baby Rats (mean)", "PeakBabyRats", "mean"),
    ("Kings formed by promotion", "KingsFormed", "sum"),
    ("Peak kings (max)", "PeakKings", "max"),
    ("Bites on enemy rats", "BitesOnEnemyRats", "sum"),
    ("Bites on enemy kings", "BitesOnEnemyKings", "sum"),
    ("Bites on cats", "BitesOnCats", "sum"),
    ("Cat damage (engine)", "CatDamage", "sum"),
    ("Bites taken by own kings", "KingBitesTaken", "sum"),
    ("Baby Rats lost", "RatsLost", "sum"),
    ("Kings lost", "KingsLost", "sum"),
    ("Rat traps on map", "RatTraps", "sum"),
    ("Cat traps on map", "CatTraps", "sum"),
    ("Dirt on map", "Dirt", "sum"),
]


def _has(rows: list[dict], column: str) -> bool:
    return bool(rows) and all(str(row.get(column, "")).strip() != "" for row in rows)


def _aggregate(rows: list[dict], opponents: list[dict], column: str, kind: str):
    """One side's value for a metric; None when it is undefined for these games."""
    if kind == "share":
        ours = sum(_number(row, column) for row in rows)
        total = ours + sum(_number(row, column) for row in opponents)
        return ours / total if total else None
    values = [_number(row, column) for row in rows]
    if kind == "survival":
        # 0 means the starting king outlived the replay; count the whole match.
        values = [value or _number(row, "Round") for value, row in zip(values, rows)]
    if kind == "first":
        values = [value for value in values if value]
    if not values:
        return None
    if kind == "sum":
        return sum(values)
    if kind == "max":
        return max(values)
    return sum(values) / len(values)


def margin_values(pairs: list[tuple[dict, dict]]) -> list[tuple[str, str, object, object]]:
    """(label, kind, candidate, baseline) for each metric present in every row."""
    candidates, baselines = [pair[0] for pair in pairs], [pair[1] for pair in pairs]
    result = []
    for label, column, kind in MARGIN_METRICS:
        if not _has(candidates + baselines, column) or (
                kind == "survival" and not _has(candidates + baselines, "Round")):
            continue
        result.append((label, kind, _aggregate(candidates, baselines, column, kind),
                       _aggregate(baselines, candidates, column, kind)))
    return result


def format_metric(kind: str, value) -> str:
    if value is None:
        return "n/a"
    if kind == "share":
        return f"{value * 100:.1f}%"
    if isinstance(value, float):
        return f"{value:,.1f}"
    return f"{value:,}"


def _margin_lines(rows: list[dict], final: list[tuple[dict, dict]]) -> list[str]:
    # Results from the reader before margin metrics only have deliveries and
    # cat damage, which the sections above already report.
    if not final or not _has([row for pair in final for row in pair], "RatsSpawned"):
        return []
    fixed = fixed_checkpoint(rows, final)
    windows = [("Final", final)]
    if fixed:
        windows.append((f"Round {fixed[6:]}", _pairs(rows, fixed)))
    header = "| Measurement | " + " | ".join(
        f"{name} candidate | {name} baseline" for name, _ in windows) + " |"
    lines = ["## Margin metrics", "",
             "These show how far apart the bots are when wins do not move, for example against a much "
             "stronger opponent. Counts come from engine replay actions and sum every completed game "
             "unless the label says mean or max. "
             + (f"Round {fixed[6:]} is the latest fixed round every completed game reached, so that "
                "column compares equal playing time. " if fixed else "")
             + "Cheese share is the candidate's part of both teams' deliveries.", "",
             header, "| --- |" + " ---: |" * (2 * len(windows))]
    tables = [margin_values(pairs) for _, pairs in windows]
    for index, (label, kind, _, _) in enumerate(tables[0]):
        cells = []
        for table in tables:
            _, _, candidate, baseline = table[index]
            cells += [format_metric(kind, candidate), format_metric(kind, baseline)]
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    lines.extend(["", "To compare two candidate versions against the same opponent, run both and use "
                  "`python tools/sim_compare.py <earlier run> <later run>`.", ""])
    return lines


def _totals(pairs: list[tuple[dict, dict]]) -> tuple[int, int]:
    return tuple(sum(_number(pair[index], "DeliveredCheese") for pair in pairs)
                 for index in (0, 1))


def _change(candidate: int, baseline: int) -> str:
    # Neither 0/0 nor an increase from zero establishes a finite percentage gain.
    return f"{(candidate / baseline - 1) * 100:+.1f}%" if baseline else "n/a (baseline 0)"


def _winner(pair: tuple[dict, dict]) -> str:
    winner = str(pair[0].get("Winner", ""))
    if winner in ("candidate", "baseline"):
        return winner
    # Legacy replay readers used unique team package labels. Never resolve a
    # winner through shared Java package names: both jars may use battlecats.
    packages = {str(row.get("Package", "")) for row in pair}
    for role, row in zip(("candidate", "baseline"), pair):
        if len(packages) == 2 and winner == str(row.get("Package", "")):
            return role
    for role, row in zip(("candidate", "baseline"), pair):
        if winner == str(row.get("Side", "")):
            return role
    return "unknown"


def _wins(pairs: list[tuple[dict, dict]]) -> Counter:
    return Counter(_winner(pair) for pair in pairs)


def _version(manifest: dict, role: str) -> dict:
    nested = manifest.get(role, {})
    if not isinstance(nested, dict):
        nested = {}
    versions = manifest.get("versions", {})
    version = versions.get(role, {}) if isinstance(versions, dict) else {}
    return {**nested, **version} if isinstance(version, dict) else nested


def _identity(manifest: dict, role: str) -> str:
    nested = _version(manifest, role)
    ref = manifest.get(role + "_ref") or nested.get("ref")
    commit = manifest.get(role + "_commit") or nested.get("commit") or nested.get("sha")
    snapshot = (manifest.get(role + "_snapshot_sha256") or nested.get("snapshot_sha256")
                or (manifest.get("working_tree_sha256") if role == "candidate" else None)
                or (manifest.get("candidate_source_sha256") if role == "candidate" else None))
    parts = []
    if ref:
        parts.append(str(ref))
    if commit:
        parts.append("commit " + str(commit))
    if snapshot:
        parts.append("source snapshot SHA-256 " + str(snapshot))
    return _escape("; ".join(parts) if parts else "not recorded")


def _version_label(manifest: dict, role: str) -> str:
    version = _version(manifest, role)
    return _escape("; ".join(str(version[key]) for key in ("subject", "date")
                             if version.get(key)))


def _run_status(summary: dict, manifest: dict) -> str:
    status = str(manifest.get("status", "")).upper()
    return status if status in ("FAILED", "CANCELLED", "RUNNING", "PREPARED") else (
        "Complete" if summary["complete"] else "INCOMPLETE")


def _pr_identity(manifest: dict, role: str) -> str:
    version = _version(manifest, role)
    commit = manifest.get(role + "_commit") or version.get("commit") or version.get("sha")
    identity = _identity(manifest, role)
    if role == "candidate" and not commit:
        identity = "**UNCOMMITTED snapshot — exploratory, not a final PR comparison**; " + identity
    label = _version_label(manifest, role)
    return identity + (f"; {label}" if label else "")


def _metadata(manifest: dict, case: str) -> dict:
    cases = manifest.get("cases", {})
    if isinstance(cases, dict):
        value = cases.get(case, {})
        return value if isinstance(value, dict) else {}
    if isinstance(cases, list):
        for value in cases:
            if isinstance(value, dict) and str(value.get("case", value.get("Case", value.get("id")))) == case:
                return value
    return {}


def _file_link(output: Path, value, label: str) -> str:
    if not value:
        return "n/a"
    path = Path(str(value))
    if path.is_absolute():
        try:
            path = Path(os.path.relpath(path, output))
        except ValueError:  # Different Windows drive: no relative link possible.
            return _escape(value)
    return f"[{label}]({quote(path.as_posix(), safe='/.-_')})"


def _case_links(output: Path, manifest: dict, row: dict) -> str:
    case = str(row["Case"])
    metadata = _metadata(manifest, case)
    links = []
    for suffix, key in (("bc26", "replay"), ("log", "log")):
        value = (row.get(key.title()) or metadata.get(key) or metadata.get(key + "_path"))
        if not value and (output / (case + "." + suffix)).is_file():
            value = case + "." + suffix
        links.append(_file_link(output, value, key))
    return " / ".join(links)


def _summary(rows: list[dict], expected_cases: int) -> dict:
    final = _pairs(rows, "FINAL")
    strict = _pairs(rows, "ROUND_1000")
    common = _pairs(rows, "COMMON_1000")
    wins = _wins(final)
    return dict(final=final, strict=strict, common=common, wins=wins,
                complete=len(final) == expected_cases, expected=expected_cases)


def print_summary(rows: list[dict], expected_cases: int) -> None:
    """Print a short terminal summary; early endings never stand in for round 1000."""
    summary = _summary(rows, expected_cases)
    status = "Complete" if summary["complete"] else "INCOMPLETE"
    print(f"{status}: {len(summary['final'])}/{expected_cases} complete match pairs.")
    for label, key in (("Final", "final"), ("Strict round 1000", "strict"),
                       ("Common checkpoint min(1000, end)", "common")):
        pairs = summary[key]
        candidate, baseline = _totals(pairs)
        print(f"{label}: {len(pairs)} pairs; delivered candidate {candidate:,}, "
              f"baseline {baseline:,}; change {_change(candidate, baseline)}.")
    wins = summary["wins"]
    print(f"Wins: candidate {wins['candidate']}, baseline {wins['baseline']}, "
          f"unknown {wins['unknown']}. See summary.md for end rounds and win reasons.")


def _write_pr_summary(output: Path, rows: list[dict], manifest: dict, summary: dict) -> None:
    """Draft evidence for a PR; strategy rationale and review remain human decisions."""
    final, strict = summary["final"], summary["strict"]
    expected, complete = summary["expected"], len(final)
    status = _run_status(summary, manifest)
    maps = manifest.get("maps") or sorted({str(row["Map"]) for row in rows})
    cases = manifest.get("cases", [])
    planned_sides = {str(case["side"]) for case in cases
                     if isinstance(case, dict) and case.get("side")} if isinstance(cases, list) else set()
    sides = sorted(planned_sides or {str(row["CandidateSide"]) for row in rows})
    lines = ["# Simulation evidence for PR", "",
             f"**{status}: {complete}/{expected} complete match pairs.**", "",
             f"Baseline: {_pr_identity(manifest, 'baseline')}", "",
             f"Candidate: {_pr_identity(manifest, 'candidate')}", "",
             f"Preset: {_escape(manifest.get('preset', 'not recorded'))}; "
             f"engine: {_escape(manifest.get('engine_version', 'not recorded'))}; "
             f"candidate starting sides: {_escape(', '.join(sides) or 'not recorded')}", "",
             f"Maps: {_escape(', '.join(str(name) for name in maps) or 'not recorded')}", ""]
    if manifest.get("label"):
        lines[2:2] = [f"Experiment: {_escape(manifest['label'])}", ""]
    if manifest.get("error"):
        lines.extend([f"Run error: {_escape(manifest['error'])}", ""])
    if status != "Complete":
        lines.extend(["This run is not a completed comparison. Partial totals do not establish an overall improvement; "
                      "missing, duplicate, or inconsistent pairs are excluded.", ""])
    lines.extend(["| Delivered cheese | Complete pairs | Candidate | Baseline | Change |",
                  "| --- | ---: | ---: | ---: | ---: |"])
    for label, pairs in (("Final", final), ("Fixed round 1000", strict)):
        candidate, baseline = _totals(pairs)
        lines.append(f"| {label} | {len(pairs)}/{expected} | {candidate:,} | {baseline:,} | {_change(candidate, baseline)} |")
    lines.extend(["", "Delivered cheese is not the engine's match points. Final totals use actual end rounds; "
                  "fixed round 1000 excludes earlier endings. A zero baseline has no defined percentage change.", ""])
    wins = summary["wins"]
    reasons = defaultdict(Counter)
    for pair in final:
        reasons[str(pair[0].get("WinType", "unknown"))][_winner(pair)] += 1
    reason_labels = []
    for reason in sorted(reasons):
        counts = reasons[reason]
        reason_labels.append(f"{_escape(reason)}: {counts['candidate']}/{counts['baseline']}/{counts['unknown']}")
    coin_flips = sum(sum(counts.values()) for reason, counts in reasons.items() if "COIN" in reason.upper())
    lines.extend([f"Wins (candidate/baseline/unknown): **{wins['candidate']}/{wins['baseline']}/{wins['unknown']}** "
                  f"across {complete} completed matches. Reasons (same order): "
                  + ("; ".join(reason_labels) or "none recorded") + ".", "",
                  f"Coin-flip outcomes: **{coin_flips}**; included in wins and do not demonstrate a strategy advantage.", ""])
    lines.extend(_margin_lines(rows, final))
    by_map = defaultdict(list)
    for pair in final:
        by_map[str(pair[0]["Map"])].append(pair)
    regressions = [(name, pairs, *_totals(pairs)) for name, pairs in sorted(by_map.items())
                   if _totals(pairs)[0] < _totals(pairs)[1]]
    lines.extend(["## Per-map regressions", ""])
    if regressions:
        lines.extend(["| Map | Complete pairs | Candidate | Baseline | Change |",
                      "| --- | ---: | ---: | ---: | ---: |"])
        for name, pairs, candidate, baseline in regressions:
            lines.append(f"| {_escape(name)} | {len(pairs)} | {candidate:,} | {baseline:,} | {_change(candidate, baseline)} |")
    else:
        lines.append("No lower final-delivery total was observed among completed map pairs. This is not proof of general improvement.")
    lines.extend(["", "Full per-map results, starting-side coverage, end rounds, replays and logs: "
                  "[summary.md](summary.md); all recorded checkpoints: [results.csv](results.csv).", ""])
    hashes = manifest.get("jar_hashes")
    verified = isinstance(hashes, dict) and all(
        isinstance(hashes.get(role), str) and re.fullmatch(r"[0-9a-fA-F]{64}", hashes[role])
        for role in ("candidate", "baseline"))
    lines.append("Build evidence: both snapshots compiled and passed the bot verifier (both JAR hashes recorded). "
                 "Unit-test status is not recorded here." if verified else
                 "Build evidence: compilation/verifier completion is not recorded for both snapshots. "
                 "Unit-test status is not recorded here.")
    phases = {str(row.get("Cooperation", "")).lower() for row in rows}
    phase_note = ("All reported checkpoints were in cooperation; this does not establish behavior after a PvP transition."
                  if phases == {"true"} else
                  "Cooperation flags are checkpoint samples in results.csv; no full-match or PvP coverage is assumed.")
    lines.extend(["", f"Limits: these selected maps, sides and one matchup do not establish an optimal strategy. {phase_note}", "",
                  "## Change and rationale", "", "Pending: describe the intended change and explain any strategy constants.", "",
                  "## Code review", "", "Pending: independent review of scope, code and these results; this generated note is not approval.", ""])
    (output / "pr-summary.md").write_text("\n".join(lines), encoding="utf-8", newline="\n")


def write_report(output: Path, rows: list[dict], manifest: dict, expected_cases: int) -> None:
    """Write results.csv, summary.md and pr-summary.md without changing sources or saved replays."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    fields = list(dict.fromkeys(FIELDS + [key for row in rows for key in row]))
    with (output / "results.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    summary = _summary(rows, expected_cases)
    final = summary["final"]
    complete = len(final)
    status = _run_status(summary, manifest)
    lines = ["# Simulation comparison", "", f"**{status}: {complete}/{expected_cases} complete match pairs.**", "",
             f"Baseline: {_identity(manifest, 'baseline')}", "",
             f"Candidate: {_identity(manifest, 'candidate')}", ""]
    for role in ("baseline", "candidate"):
        label = _version_label(manifest, role)
        if label:
            lines.extend([f"{role.title()} version: {label}", ""])
    for key, label in (("label", "Experiment"), ("preset", "Preset"), ("engine_version", "Engine")):
        if manifest.get(key) is not None:
            lines.extend([f"{label}: {_escape(manifest[key])}", ""])
    for role in ("candidate", "baseline"):
        packages = sorted({str(row.get("JavaPackage") or row.get("Package"))
                           for row in rows if row.get("Role") == role
                           and (row.get("JavaPackage") or row.get("Package"))})
        manifest_packages = manifest.get("packages", {})
        if not isinstance(manifest_packages, dict):
            manifest_packages = {}
        package = (", ".join(packages) or str(manifest_packages.get(role)
                   or manifest.get(role + "_package", "not recorded")))
        lines.extend([f"{role.title()} simulation package: {_escape(package)}", ""])
    if manifest.get("error"):
        lines.extend([f"Run error: {_escape(manifest['error'])}", ""])
    if not summary["complete"]:
        lines.extend(["Results are partial. Missing, duplicate, or inconsistent team pairs are excluded from totals. "
                      "Inspect run logs for unfinished or failed cases; partial totals do not establish an overall improvement.", ""])

    lines.extend(["## Delivered cheese", "",
                  "Engine replays supply the delivery totals and match outcomes. No collection totals are inferred from bot logs.", "",
                  "| Measurement | Complete pairs | Candidate | Baseline | Change |",
                  "| --- | ---: | ---: | ---: | ---: |"])
    for label, key in (("Final", "final"), ("Strict round 1000", "strict"),
                       ("Common checkpoint: min(1000, end)", "common")):
        pairs = summary[key]
        candidate, baseline = _totals(pairs)
        lines.append(f"| {label} | {len(pairs)}/{expected_cases} | {candidate:,} | {baseline:,} | {_change(candidate, baseline)} |")
    lines.extend(["", "Final totals use each match's actual end round; match durations can differ. "
                  "Strict round 1000 includes only matches with a recorded round 1000. "
                  "The common checkpoint uses round 1000 or the earlier match end, so it mixes times and is not a fixed-round comparison. "
                  "A zero baseline has no defined percentage change, including when both totals are zero.", ""])

    wins = summary["wins"]
    candidate_wins = f"{wins['candidate']}/{complete}" if complete else "n/a (no completed pairs)"
    baseline_wins = f"{wins['baseline']}/{complete}" if complete else "n/a (no completed pairs)"
    lines.extend(["## Match outcomes", "",
                  f"Candidate wins: **{candidate_wins}**; baseline wins: **{baseline_wins}**; "
                  f"unresolved winner labels: **{wins['unknown']}**.", "",
                  "| Engine win reason | Candidate wins | Baseline wins | Unknown |",
                  "| --- | ---: | ---: | ---: |"])
    reasons = defaultdict(Counter)
    for pair in final:
        reasons[str(pair[0].get("WinType", "unknown"))][_winner(pair)] += 1
    for reason in sorted(reasons):
        counts = reasons[reason]
        lines.append(f"| {_escape(reason)} | {counts['candidate']} | {counts['baseline']} | {counts['unknown']} |")
    coin_flips = sum(sum(counts.values()) for reason, counts in reasons.items()
                     if "COIN" in reason.upper())
    lines.extend(["", f"Coin-flip outcomes: **{coin_flips}**. They are included in the win count and do not demonstrate a strategy advantage.", ""])
    if final:
        rounds = [_number(pair[0], "FinalRound") for pair in final]
        lines.extend([f"Completed matches ended between rounds **{min(rounds)} and {max(rounds)}**; "
                      f"**{sum(number < 1000 for number in rounds)}** ended before round 1000.", ""])
    observed_phases = {str(row.get("Cooperation", "")).lower() for row in rows}
    if observed_phases == {"true"}:
        lines.extend(["All reported replay checkpoints were in cooperation. These samples do not establish behavior after a PvP transition.", ""])
    elif observed_phases:
        lines.extend(["Cooperation flags for individual replay checkpoints are recorded in results.csv; "
                      "aggregate results combine the reported phases.", ""])

    lines.extend(_margin_lines(rows, final))
    lines.extend(["## Final deliveries by map", "",
                  "Maps with fewer than two starting sides are not a complete side-swapped comparison.", "",
                  "| Map | Complete pairs | Candidate starting sides | Candidate | Baseline | Change | Candidate wins |",
                  "| --- | ---: | --- | ---: | ---: | ---: | ---: |"])
    maps = defaultdict(list)
    for pair in final:
        maps[str(pair[0]["Map"])].append(pair)
    for map_name in sorted(maps):
        pairs = maps[map_name]
        candidate, baseline = _totals(pairs)
        sides = ", ".join(sorted({str(pair[0]["CandidateSide"]) for pair in pairs}))
        lines.append(f"| {_escape(map_name)} | {len(pairs)} | {_escape(sides)} | {candidate:,} | {baseline:,} | "
                     f"{_change(candidate, baseline)} | {_wins(pairs)['candidate']}/{len(pairs)} |")

    lines.extend(["", "## Completed cases", "",
                  "| Case | Candidate side | End round | Winner | Win reason | Delivered candidate / baseline | Replay / log |",
                  "| --- | --- | ---: | --- | --- | ---: | --- |"])
    for pair in final:
        row = pair[0]
        candidate, baseline = _totals([pair])
        lines.append(f"| {_escape(row['Case'])} | {_escape(row['CandidateSide'])} | {_number(row, 'FinalRound')} | "
                     f"{_winner(pair)} | {_escape(row.get('WinType', 'unknown'))} | {candidate:,} / {baseline:,} | "
                     f"{_case_links(output, manifest, row)} |")
    lines.extend(["", "Use the saved source identities and the same maps, seeds, sides, and engine version when comparing runs. "
                  "Differences in strategy outside the intended change can affect both deliveries and wins.", ""])
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8", newline="\n")
    _write_pr_summary(output, rows, manifest, summary)
