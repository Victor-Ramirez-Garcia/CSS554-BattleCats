"""Run saved local Java changes against main, with side-swapped matches and replays.

Use ./gradlew sim (Windows: .\\gradlew.bat sim) to resolve the engine and JDK.
Direct use: python tools/simulate.py --help. No Python packages are required.
"""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

from sim_runtime import OPEN_PACKAGES, REPLAY_READER, locate_engine, locate_jdk
from sim_report import print_summary, write_report


ROOT = Path(__file__).resolve().parent.parent
PRESETS = {
    "quick": ["DefaultSmall", "DefaultMedium"],
    "full": ["DefaultSmall", "DefaultMedium", "DefaultLarge", "cheesefarm",
             "minimaze", "safelycontained", "pipes"],
}

READER = REPLAY_READER


def command(argv: list[str], *, timeout: int | None = None,
            log_path: Path | None = None) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(argv, cwd=ROOT, capture_output=True, timeout=timeout)
    except FileNotFoundError as error:
        raise RuntimeError(f"Command unavailable: {argv[0]}") from error
    if log_path is not None:
        log_path.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        detail = (result.stdout + result.stderr).decode("utf-8", errors="replace")[-6000:]
        raise RuntimeError(f"{Path(argv[0]).name} failed:\n{detail}")
    return result


def git(*args: str) -> bytes:
    return command(["git", *args]).stdout


def load_sources(ref: str | None = None) -> dict[str, str]:
    """Snapshot all Java sources, including helpers outside the selected package."""
    if ref is None:
        paths = git("ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "src")
    else:
        paths = git("ls-tree", "-r", "-z", "--name-only", ref, "--", "src")
    sources = {}
    for raw in sorted(set(paths.split(b"\0")) - {b""}):
        name = raw.decode("utf-8")
        if not name.startswith("src/") or not name.endswith(".java"):
            continue
        if ref is None:
            source = ROOT / name
            if not source.is_file():
                continue  # A locally deleted tracked source is absent from the snapshot.
            data = source.read_bytes()
        else:
            data = git("show", f"{ref}:{name}")
        sources[name[4:]] = data.decode("utf-8")
    if not sources:
        raise ValueError("No Java sources found under src/.")
    return sources


def source_hash(sources: dict[str, str]) -> str:
    return hashlib.sha256(json.dumps(sources, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def validate_package(sources: dict[str, str], package: str) -> None:
    if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*", package):
        raise ValueError(f"Invalid Java package: {package!r}")
    entry = package.replace(".", "/") + "/RobotPlayer.java"
    if entry not in sources:
        raise ValueError(f"No src/{entry} found. Select the bot with --package or --baseline-package.")


def validate_maps(maps: list[str]) -> list[str]:
    if not maps or any(not re.fullmatch(r"[A-Za-z0-9_-]+", name) for name in maps):
        raise ValueError("Choose at least one map; names may contain letters, digits, underscores, or hyphens.")
    return list(dict.fromkeys(maps))


def sha_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_manifest(output: Path, manifest: dict) -> None:
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def baseline_revision(ref: str | None, offline: bool) -> tuple[str, str]:
    remotes = git("remote").decode().split()
    if ref is None:
        remote = next((name for name in ("upstream", "origin") if name in remotes), None)
        if remote is None:
            raise ValueError("No main remote found. Add origin/upstream or pass --baseline-ref main --offline.")
        ref = remote + "/main"
    else:
        remote = next((name for name in remotes if ref.startswith(name + "/")), None)
    if remote and not offline:
        print(f"Fetching {remote} for the baseline...", flush=True)
        command(["git", "fetch", remote, "--prune"])
    try:
        commit = git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
    except RuntimeError as error:
        raise ValueError(f"Cannot resolve baseline {ref!r}. Fetch main or choose --baseline-ref.") from error
    return ref, commit


def compile_bot(output: Path, role: str, sources: dict[str, str], package: str,
                jdk: Path, engine: Path) -> Path:
    source_dir, classes = output / role / "src", output / role / "classes"
    classes.mkdir(parents=True)
    filenames = []
    for name, source in sources.items():
        destination = source_dir / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(source.encode("utf-8"))
        filenames.append(str(destination))
    suffix = ".exe" if os.name == "nt" else ""
    arguments = ["--release", "21", "-encoding", "UTF-8", "-cp", str(engine), "-d", str(classes), *filenames]
    argfile = output / role / "javac.args"
    argfile.write_text("\n".join('"' + arg.replace("\\", "/") + '"' for arg in arguments) + "\n", encoding="utf-8")
    command([str(jdk / "bin" / ("javac" + suffix)), "@" + str(argfile)])
    jar = output / (role + ".jar")
    command([str(jdk / "bin" / ("jar" + suffix)), "--create", "--file", str(jar), "-C", str(classes), "."])
    command([str(jdk / "bin" / ("java" + suffix)), "-cp", str(engine),
             "battlecode.instrumenter.Verifier", package, str(jar)],
            log_path=output / role / "verify.log")
    return jar


def compile_reader(output: Path, jdk: Path, engine: Path) -> Path:
    source = output / "SimulationReplayMetrics.java"
    source.write_text(READER, encoding="utf-8")
    classes = output / "reader-classes"
    classes.mkdir()
    suffix = ".exe" if os.name == "nt" else ""
    command([str(jdk / "bin" / ("javac" + suffix)), "--release", "21", "-cp", str(engine),
             "-d", str(classes), str(source)])
    return classes


def match_command(java: Path, engine: Path, output: Path, case: dict, jars: dict,
                  packages: dict) -> list[str]:
    properties = {
        "bc.server.wait-for-client": "false", "bc.server.mode": "headless",
        "bc.server.map-path": str(output / "maps"),
        "bc.server.robot-player-to-system-out": "true", "bc.server.debug": "false",
        "bc.engine.debug-methods": "false", "bc.engine.enable-profiler": "false",
        "bc.engine.show-indicators": "true", "bc.game.maps": case["map"],
        "bc.server.validate-maps": "true", "bc.server.alternate-order": "false",
        "bc.server.save-file": str(output / case["replay"]),
    }
    for side in ("A", "B"):
        role = "candidate" if side == case["side"] else "baseline"
        prefix = "bc.game.team-" + side.lower()
        properties.update({prefix: "sim" + role, prefix + ".language": "java",
                           prefix + ".package": packages[role], prefix + ".url": str(jars[role])})
    return [str(java), *["--add-opens=java.base/" + name + "=ALL-UNNAMED" for name in OPEN_PACKAGES],
            *["-D" + key + "=" + value for key, value in properties.items()],
            "-cp", str(engine), "battlecode.server.Main", "-c=-"]


def read_match(output: Path, case: dict, java: Path, engine: Path, reader: Path,
               packages: dict) -> list[dict]:
    log = (output / case["log"]).read_text(encoding="utf-8", errors="replace")
    if re.search(r"Error instrumenting|InstrumentationException|NoClassDefFoundError|Could not find or load|Exception in thread|\[server:warning\]", log):
        raise RuntimeError(f"Engine/runtime error; inspect {output / case['log']}")
    if re.search(r"(?m)^\[[AB]: #[^\]]+\]\s+(?:[\w$]+\.)*[\w$]*(?:Exception|Error)(?::|$)", log):
        raise RuntimeError(f"Robot exception; inspect {output / case['log']}")
    metrics = output / (case["case"] + "-engine.csv")
    command([str(java), "-cp", os.pathsep.join([str(reader), str(engine)]),
             "SimulationReplayMetrics", str(output / case["replay"]), str(metrics),
             "100,250,500,1000", case["side"]])
    with metrics.open(encoding="utf-8", newline="") as handle:
        snapshots = list(csv.DictReader(handle))
    finals = [row for row in snapshots if row["Checkpoint"] == "FINAL"]
    if len(finals) != 2 or {row["Side"] for row in finals} != {"A", "B"}:
        raise RuntimeError(f"Incomplete replay metrics: {case['case']}")
    final_round = int(finals[0]["Round"])
    for checkpoint in (100, 250, 500, 1000):
        if checkpoint <= final_round:
            group = [row for row in snapshots if row["Checkpoint"] == f"ROUND_{checkpoint}"]
            if len(group) != 2 or {row["Side"] for row in group} != {"A", "B"}:
                raise RuntimeError(f"Replay lacks a complete round {checkpoint}: {case['case']}")
    rows = []
    for snapshot in snapshots:
        role = "candidate" if snapshot["Side"] == case["side"] else "baseline"
        if snapshot["Team"] != role or snapshot["Winner"] not in ("candidate", "baseline"):
            raise RuntimeError("Replay team identity mismatch")
        rows.append(dict(Case=case["case"], Map=case["map"], CandidateSide=case["side"],
                         Role=role, JavaPackage=packages[role], Replay=case["replay"], Log=case["log"], **snapshot))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preset", choices=PRESETS, default="quick")
    parser.add_argument("--maps", nargs="+", help="Override preset maps; both starting sides always run")
    parser.add_argument("--package", default="battlecats", help="Candidate Java bot package")
    parser.add_argument("--baseline-package", default="battlecats")
    parser.add_argument("--baseline-ref", help="Default: upstream/main, or origin/main without upstream")
    parser.add_argument("--candidate-ref", help="Compare a committed ref; default snapshots saved local Java files")
    parser.add_argument("--offline", action="store_true", help="Use the cached baseline without fetching")
    parser.add_argument("--java-home")
    parser.add_argument("--engine-jar")
    parser.add_argument("--output", type=Path, help="Fresh directory; default creates matches/sim-<time>-<id>")
    parser.add_argument("--timeout", type=int, default=180, help="Maximum seconds per match")
    parser.add_argument("--prepare-only", action="store_true", help="Snapshot, compile and verify without matches")
    args = parser.parse_args()
    maps = validate_maps(args.maps or PRESETS[args.preset])
    if args.timeout <= 0:
        parser.error("timeout must be positive")
    if (ROOT / "engine_version.txt").read_text().strip() != "1.2.6":
        raise ValueError("This replay reader supports engine 1.2.6; update it before changing engine versions.")
    baseline_ref, baseline_commit = baseline_revision(args.baseline_ref, args.offline)
    candidate_commit = git("rev-parse", "--verify", args.candidate_ref + "^{commit}").decode().strip() if args.candidate_ref else None
    for commit in filter(None, (baseline_commit, candidate_commit)):
        if git("show", commit + ":engine_version.txt").decode().strip() != "1.2.6":
            raise ValueError(f"Revision {commit[:12]} uses a different engine version.")
    sources = {"baseline": load_sources(baseline_commit), "candidate": load_sources(candidate_commit)}
    packages = {"candidate": args.package, "baseline": args.baseline_package}
    for role in sources:
        validate_package(sources[role], packages[role])
    jdk = locate_jdk(args.java_home)
    engine = locate_engine(args.engine_jar)
    tag = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    output = (args.output or ROOT / "matches" / ("sim-" + tag)).resolve()
    if output.exists():
        raise ValueError(f"Output already exists: {output}. Choose a fresh directory to preserve earlier results.")
    output.mkdir(parents=True)
    (output / "maps").mkdir()
    cases = [dict(case=f"{name}-{side}", map=name, side=side,
                  replay=f"{name}-{side}.bc26", log=f"{name}-{side}.log")
             for name in maps for side in ("A", "B")]
    manifest = {
        "status": "RUNNING", "created_utc": datetime.now(timezone.utc).isoformat(),
        "baseline_ref": baseline_ref, "baseline_commit": baseline_commit,
        "candidate_ref": args.candidate_ref or "WORKTREE", "candidate_commit": candidate_commit,
        "candidate_head": git("rev-parse", "HEAD").decode().strip(),
        "working_tree_sha256": source_hash(sources["candidate"]) if candidate_commit is None else None,
        "source_hashes": {role: source_hash(files) for role, files in sources.items()},
        "source_files": {role: {name: hashlib.sha256(text.encode()).hexdigest() for name, text in files.items()}
                         for role, files in sources.items()},
        "packages": packages, "preset": args.preset if args.maps is None else "custom",
        "maps": maps, "cases": cases, "engine_version": "1.2.6", "engine_sha256": sha_file(engine),
        "engine_jar": str(engine), "java_home": str(jdk), "offline": args.offline,
        "java_version": command([str(jdk / "bin" / ("java.exe" if os.name == "nt" else "java")), "-version"]).stderr.decode(errors="replace").strip(),
        "map_hashes": {}, "runner_hashes": {},
    }
    rows = []
    try:
        runner = output / "runner"
        runner.mkdir()
        for filename in ("simulate.py", "sim_report.py", "sim_runtime.py"):
            original = Path(__file__).parent / filename
            shutil.copyfile(original, runner / filename)
            manifest["runner_hashes"][filename] = sha_file(original)
        for name in maps:
            custom = ROOT / "maps" / (name + ".map26")
            if custom.is_file():
                destination = output / "maps" / custom.name
                shutil.copyfile(custom, destination)
                manifest["map_hashes"][name] = sha_file(destination)
            else:
                manifest["map_hashes"][name] = "bundled_in_engine"
        save_manifest(output, manifest)
        print(f"Baseline: {baseline_ref} ({baseline_commit[:12]})", flush=True)
        candidate_label = candidate_commit[:12] if candidate_commit else "saved local files " + manifest["working_tree_sha256"][:12]
        print(f"Candidate: {candidate_label}; {len(cases)} matches; output: {output}", flush=True)
        jars = {role: compile_bot(output, role, sources[role], packages[role], jdk, engine)
                for role in ("baseline", "candidate")}
        manifest["jar_hashes"] = {role: sha_file(path) for role, path in jars.items()}
        if args.prepare_only:
            manifest["status"] = "PREPARED"
            save_manifest(output, manifest)
            print("Both snapshots compiled and passed the bot verifier. No matches requested.")
            return 0
        reader = compile_reader(output, jdk, engine)
        java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        for index, case in enumerate(cases, 1):
            argv = match_command(java, engine, output, case, jars, packages)
            (output / (case["case"] + "-command.json")).write_text(json.dumps(argv, indent=2) + "\n", encoding="utf-8")
            started = time.monotonic()
            with (output / case["log"]).open("wb") as log:
                result = subprocess.run(argv, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
            if result.returncode:
                raise RuntimeError(f"Match failed: {case['case']}; inspect {output / case['log']}")
            case_rows = read_match(output, case, java, engine, reader, packages)
            rows.extend(case_rows)
            case.update(log_sha256=sha_file(output / case["log"]), replay_sha256=sha_file(output / case["replay"]))
            final = {row["Role"]: row for row in case_rows if row["Checkpoint"] == "FINAL"}
            ours, main_bot = final["candidate"], final["baseline"]
            print(f"[{index}/{len(cases)}] {case['case']}: delivered {ours['DeliveredCheese']} / {main_bot['DeliveredCheese']}; "
                  f"winner {ours['Winner']} ({ours['WinType']}); round {ours['Round']}; {time.monotonic() - started:.1f}s", flush=True)
            save_manifest(output, manifest)
            write_report(output, rows, manifest, len(cases))
        manifest["status"] = "COMPLETE"
        save_manifest(output, manifest)
        write_report(output, rows, manifest, len(cases))
        print_summary(rows, len(cases))
        print(f"Report: {output / 'summary.md'}\nReplays: {output} (*.bc26)", flush=True)
        return 0
    except (Exception, KeyboardInterrupt) as error:
        manifest["status"] = "CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAILED"
        manifest["error"] = str(error) or "Interrupted"
        save_manifest(output, manifest)
        write_report(output, rows, manifest, len(cases))
        raise


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Simulation interrupted; completed matches and failure status were saved.", file=sys.stderr)
        raise SystemExit(130)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Simulation stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
