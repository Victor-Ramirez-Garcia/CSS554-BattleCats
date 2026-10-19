"""Run repeatable, side-swapped foraging matches without changing production bots.

Requires Python 3.10+, a local JDK 21, and the cached Battlecode 1.2.6 engine JAR.
Example (PowerShell, from the repository root):
  python tools/benchmark_foraging.py --java-home C:/path/to/jdk-21

The default pilot runs 30 matches: five collection thresholds, three maps, and
both starting sides. Every candidate faces the original upstream collector.
Output, generated sources, logs, and replays stay under ignored matches/.
Rerunning resumes completed cases only when all source hashes still match.
Use a new --output directory to compare a subsequent source revision.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone


ROOT = next(path for path in Path(__file__).resolve().parents
            if (path / "build.gradle").is_file() and (path / "src").is_dir())
ENGINE_VERSION = "1.2.6"
BASELINE_COMMIT = "6612d97ee3d540be42d78767accdba6221eb0eee"
BASE_PACKAGE = "foragebaseline"
OPEN_PACKAGES = (
    "jdk.internal.misc", "jdk.internal.math", "jdk.internal.util",
    "jdk.internal.access", "sun.security.action",
)

ROBOT_TEMPLATE = """package PACKAGE;
import battlecode.common.*;
public class RobotPlayer {
    public static void run(RobotController rc) {
        BabyRat baby = rc.getType() == UnitType.BABY_RAT ? CONSTRUCTOR : null;
        RatKing king = rc.getType() == UnitType.RAT_KING ? new RatKing() : null;
        while (true) {
            try {
                if (baby != null) baby.takeTurn(rc);
                else if (king != null) king.takeTurn(rc);
            } catch (Exception e) {
                System.out.println("BENCH_EXCEPTION caught");
                e.printStackTrace();
            } finally { Clock.yield(); }
        }
    }
}
"""

CAPPED_KING = """package PACKAGE;
import battlecode.common.*;
/** Experimental population control, not the production spawning policy. */
public class RatKing {
    private int spawned;
    public void takeTurn(RobotController rc) throws GameActionException {
        SharedKingLocations.publish(rc);
        if (spawned >= 4 || rc.getAllCheese() < rc.getCurrentRatCost() + 100) return;
        for (MapLocation location : rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8)) {
            if (rc.canBuildRat(location)) {
                rc.buildRat(location);
                spawned++;
                System.out.println("BENCH_SPAWN count=" + spawned);
                break;
            }
        }
    }
}
"""

# The engine replay is authoritative for delivered-cheese totals. Pickup logs
# supplement it because the round-level schema does not expose collected totals.
REPLAY_READER = """import battlecode.schema.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
public class ForagingReplayMetrics {
    static void snapshot(PrintWriter out, String label, Round round, String[] packages,
                         MatchFooter footer, boolean coop, int seed) {
        for (int i = 0; i < round.teamIdsLength(); i++) {
            int id = round.teamIds(i), combined = round.teamAliveRatKings(i);
            out.printf(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,%s%n",
                label, round.roundId(), id == 1 ? "A" : "B", packages[id],
                seed, round.teamCheeseTransferred(i), combined / 10, combined % 10,
                round.teamAliveBabyRats(i), round.teamCatDamage(i), coop,
                packages[footer.winner()], WinType.name(footer.winType()));
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(Path.of(args[0])))) {
            bytes = input.readAllBytes();
        }
        GameWrapper game = GameWrapper.getRootAsGameWrapper(ByteBuffer.wrap(bytes));
        String[] packages = new String[3];
        MatchFooter footer = null;
        int seed = 0;
        Round last = null;
        boolean coop = true;
        Map<Integer, Round> rounds = new LinkedHashMap<>();
        Map<Integer, Boolean> phases = new HashMap<>();
        Set<Integer> wanted = new HashSet<>();
        for (String token : args[2].split(",")) wanted.add(Integer.parseInt(token));
        for (int i = 0; i < game.eventsLength(); i++) {
            EventWrapper event = game.events(i);
            if (event.eType() == Event.GameHeader) {
                GameHeader header = (GameHeader) event.e(new GameHeader());
                for (int t = 0; t < header.teamsLength(); t++) {
                    TeamData team = header.teams(t);
                    packages[team.teamId()] = team.packageName();
                }
            } else if (event.eType() == Event.MatchHeader) {
                seed = ((MatchHeader) event.e(new MatchHeader())).map().randomSeed();
            } else if (event.eType() == Event.Round) {
                last = (Round) event.e(new Round());
                if (last.turnsLength() > 0) coop = last.turns(last.turnsLength() - 1).isCooperation();
                if (wanted.contains(last.roundId())) {
                    rounds.put(last.roundId(), last);
                    phases.put(last.roundId(), coop);
                }
            } else if (event.eType() == Event.MatchFooter) {
                footer = (MatchFooter) event.e(new MatchFooter());
            }
        }
        if (last == null || footer == null) throw new IllegalStateException("Incomplete replay");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            out.println("Checkpoint,Round,Side,Package,Seed,DeliveredCheese,ReserveCheese,Kings,BabyRats,CatDamage,Cooperation,Winner,WinType");
            for (var entry : rounds.entrySet())
                snapshot(out, "ROUND_" + entry.getKey(), entry.getValue(), packages, footer, phases.get(entry.getKey()), seed);
            snapshot(out, "FINAL", last, packages, footer, coop, seed);
        }
    }
}
"""


def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=ROOT, check=True, **kwargs)


def git_text(*args: str) -> str:
    return run(["git", *args], capture_output=True, text=True, encoding="utf-8").stdout


def sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha_file(path: Path) -> str:
    return sha_bytes(path.read_bytes())


def write_json(path: Path, value) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def locate_jdk(value: str | None) -> Path:
    candidates = [Path(value)] if value else []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if shutil.which("javac"):
        candidates.append(Path(shutil.which("javac")).resolve().parent.parent)
    if os.environ.get("LOCALAPPDATA"):
        candidates.extend(sorted((Path(os.environ["LOCALAPPDATA"]) / "Temp" / "battlecats-toolchain").glob("jdk-21*")))
    suffix = ".exe" if os.name == "nt" else ""
    for candidate in candidates:
        if (candidate / "bin" / ("java" + suffix)).is_file() and (candidate / "bin" / ("javac" + suffix)).is_file():
            return candidate.resolve()
    raise RuntimeError("JDK not found. Pass --java-home with the JDK 21 installation directory.")


def locate_engine(value: str | None) -> Path:
    if value:
        return Path(value).resolve(strict=True)
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    matches = sorted((cache / "caches" / "modules-2" / "files-2.1" / "org.battlecode" /
                      "battlecode26-java" / ENGINE_VERSION).glob("*/*.jar"))
    if len(matches) != 1:
        raise RuntimeError("Engine JAR not found unambiguously. Run the project setup or pass --engine-jar.")
    return matches[0].resolve()


def rename_package(source: str, package: str) -> str:
    return source.replace("package battlecats;", f"package {package};", 1)


def instrument_pickups(source: str) -> str:
    needle = "rc.pickUpCheese(cheeseTarget);"
    if source.count(needle) != 1:
        raise RuntimeError("Expected one cheese pickup call; update harness instrumentation explicitly.")
    return source.replace(needle, """int benchmarkRawBefore = rc.getRawCheese();
            rc.pickUpCheese(cheeseTarget);
            System.out.println("BENCH_PICKUP cheese=" + (rc.getRawCheese() - benchmarkRawBefore));""")


def prepare(args, output: Path, java: Path, javac: Path, engine: Path) -> dict:
    baseline_sha = git_text("rev-parse", args.baseline_ref).strip()
    originals = {name: git_text("show", f"{baseline_sha}:src/battlecats/{name}")
                 for name in ("BabyRat.java", "Navigation.java")}
    current = {name: (ROOT / "src" / "battlecats" / name).read_text(encoding="utf-8")
               for name in ("BabyRat.java", "Navigation.java", "SharedKingLocations.java", "RatKing.java")}
    if "homeScans" not in originals["BabyRat.java"]:
        raise RuntimeError("Baseline does not contain the expected original home-scan collector.")
    if "int returnThreshold, int maxForageTurns" not in current["BabyRat.java"]:
        raise RuntimeError("Current BabyRat lacks the configurable threshold/foraging constructor.")
    generated = {}
    baseline_sources = originals if args.baseline_kind == "original" else current
    baseline_constructor = ("new BabyRat(rc.getID())" if args.baseline_kind == "original"
                            else f"new BabyRat(rc.getID(), 1, {args.forage_turns})")
    packages = [(BASE_PACKAGE, baseline_sources, baseline_constructor)]
    packages.extend((f"forage{threshold}", current,
                     f"new BabyRat(rc.getID(), {threshold}, {args.forage_turns})")
                    for threshold in args.thresholds)
    for package, sources, constructor in packages:
        generated[f"{package}/BabyRat.java"] = instrument_pickups(rename_package(sources["BabyRat.java"], package))
        generated[f"{package}/Navigation.java"] = rename_package(sources["Navigation.java"], package)
        generated[f"{package}/SharedKingLocations.java"] = rename_package(current["SharedKingLocations.java"], package)
        policy = args.baseline_king_policy if package == BASE_PACKAGE else args.king_policy
        king = CAPPED_KING if policy == "capped" else current["RatKing.java"]
        generated[f"{package}/RatKing.java"] = rename_package(king.replace("PACKAGE", package), package)
        generated[f"{package}/RobotPlayer.java"] = ROBOT_TEMPLATE.replace("PACKAGE", package).replace("CONSTRUCTOR", constructor)
    generated["ForagingReplayMetrics.java"] = REPLAY_READER
    identity = {
        "engine_version": ENGINE_VERSION, "engine_sha256": sha_file(engine),
        "baseline_commit": baseline_sha, "baseline_ref": args.baseline_ref,
        "baseline_kind": args.baseline_kind,
        "thresholds": args.thresholds, "max_forage_turns": args.forage_turns,
        "maps": args.maps, "sides": args.sides, "checkpoints": args.checkpoints,
        "local_map_sha256": {name: sha_file(ROOT / "maps" / (name + ".map26"))
                             if (ROOT / "maps" / (name + ".map26")).is_file()
                             else "bundled_in_engine" for name in args.maps},
        "king_policy": args.king_policy, "baseline_king_policy": args.baseline_king_policy,
        "harness_sha256": sha_file(Path(__file__)),
        "baseline_sources": {name: sha_bytes(text.encode()) for name, text in originals.items()},
        "current_sources": {name: sha_bytes(text.encode()) for name, text in current.items()},
        "generated_sources": {name: sha_bytes(text.encode()) for name, text in generated.items()},
    }
    fingerprint = sha_bytes(json.dumps(identity, sort_keys=True).encode())
    manifest_path = output / "manifest.json"
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        if previous["fingerprint"] != fingerprint:
            raise RuntimeError("Source/settings changed since this output directory was prepared. Choose a new --output directory.")
        manifest = previous
    else:
        manifest = dict(identity, fingerprint=fingerprint,
                        created_utc=datetime.now(timezone.utc).isoformat(),
                        current_commit=git_text("rev-parse", "HEAD").strip(),
                        java_version=run([str(java), "-version"], capture_output=True, text=True).stderr.strip(),
                        java_home=str(java.parent.parent), engine_jar=str(engine),
                        controls="King policy is explicit per side. Capped: four babies per king, reserve 100, stationary, SharedKingLocations.publish before spawning. Production: current source spawning policy. Original baseline retains home scanning and returns after any full-pile pickup. Shared baseline uses current source at threshold 1. Thresholds are soft triggers, not pickup limits. Fixed map RNG seeds; configurable starting sides.")
        write_json(manifest_path, manifest)
    # Save the exact runner alongside its manifest so later harness edits cannot
    # erase the means to reproduce this immutable source snapshot.
    if Path(__file__).resolve() != (output / "benchmark_foraging.py").resolve():
        shutil.copyfile(Path(__file__), output / "benchmark_foraging.py")
    source_root = output / "src"
    for name, source in generated.items():
        destination = source_root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(source, encoding="utf-8", newline="\n")
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    compile_args = ["--release", "21", "-encoding", "UTF-8", "-cp", str(engine), "-d", str(classes)]
    compile_args.extend(str(source_root / name) for name in generated)
    # javac argument files avoid Windows command-line length limits.
    arg_file = output / "javac.args"
    arg_file.write_text("\n".join('"' + arg.replace("\\", "/") + '"' for arg in compile_args) + "\n", encoding="utf-8")
    run([str(javac), "@" + str(arg_file)])
    return manifest


EVENT_PATTERN = re.compile(r"\[([AB]): #\d+@(\d+)\] (?:DELIVER rat=\d+ cheese=(\d+)|BENCH_PICKUP cheese=(\d+)|BENCH_EXCEPTION (.*))")


def server_command(java: Path, engine: Path, output: Path, map_name: str,
                   side: str, threshold: int) -> list[str]:
    candidate = f"forage{threshold}"
    teams = {"A": candidate if side == "A" else BASE_PACKAGE,
             "B": candidate if side == "B" else BASE_PACKAGE}
    stem = f"{map_name}-threshold{threshold}-{side}"
    properties = {
        "bc.server.wait-for-client": "false", "bc.server.mode": "headless",
        "bc.server.map-path": str(ROOT / "maps"),
        "bc.server.robot-player-to-system-out": "true", "bc.server.debug": "false",
        "bc.engine.debug-methods": "false", "bc.engine.enable-profiler": "false",
        "bc.engine.show-indicators": "true", "bc.game.maps": map_name,
        "bc.server.validate-maps": "true", "bc.server.alternate-order": "false",
        "bc.server.save-file": str(output / (stem + ".bc26")),
    }
    for letter in ("A", "B"):
        key = "bc.game.team-" + letter.lower()
        properties.update({key: teams[letter], key + ".language": "java",
                           key + ".url": str(output / "classes"), key + ".package": teams[letter]})
    return [str(java), *["--add-opens=java.base/" + package + "=ALL-UNNAMED" for package in OPEN_PACKAGES],
            *["-D" + name + "=" + value for name, value in properties.items()],
            "-cp", str(engine), "battlecode.server.Main", "-c=-"]


def parse_case(output: Path, stem: str, map_name: str, side: str,
               threshold: int, checkpoints: list[int], java: Path, engine: Path) -> dict:
    log = (output / (stem + ".log")).read_text(encoding="utf-8", errors="replace")
    if re.search(r"Error instrumenting|\bILLEGAL\b|Exception in thread|NoClassDefFoundError|Could not find or load", log):
        raise RuntimeError(f"Engine/instrumentation failure in {stem}.log")
    if not re.search(r"wins \(round \d+\)", log):
        raise RuntimeError(f"No completed match in {stem}.log")
    events = []
    for match in EVENT_PATTERN.finditer(log):
        actor, round_number, delivery, pickup, error = match.groups()
        events.append({"side": actor, "round": int(round_number),
                       "kind": "exception" if error else "delivered" if delivery else "collected",
                       "amount": 1 if error else int(delivery or pickup), "error": error})
    engine_csv = output / (stem + "-engine.csv")
    run([str(java), "-cp", os.pathsep.join([str(output / "classes"), str(engine)]),
         "ForagingReplayMetrics", str(output / (stem + ".bc26")), str(engine_csv),
         ",".join(str(number) for number in checkpoints)], capture_output=True, text=True)
    with engine_csv.open(encoding="utf-8", newline="") as handle:
        snapshots = list(csv.DictReader(handle))
    final_round = max(int(snapshot["Round"]) for snapshot in snapshots)
    for checkpoint in checkpoints:
        if checkpoint <= final_round and not any(snapshot["Checkpoint"] == f"ROUND_{checkpoint}" for snapshot in snapshots):
            raise RuntimeError(f"Replay unexpectedly lacks round {checkpoint}: {stem}")
    rows = []
    for snapshot in snapshots:
        actor = snapshot["Side"]
        round_number = int(snapshot["Round"])
        actor_events = [event for event in events if event["side"] == actor and event["round"] <= round_number]
        delivery_events = [event for event in actor_events if event["kind"] == "delivered"]
        pickups = [event for event in actor_events if event["kind"] == "collected"]
        exceptions = [event for event in actor_events if event["kind"] == "exception"]
        logged_delivery = sum(event["amount"] for event in delivery_events)
        actual_delivery = int(snapshot["DeliveredCheese"])
        if logged_delivery != actual_delivery:
            raise RuntimeError(f"{stem} {actor} round {round_number}: delivery log {logged_delivery} differs from replay {actual_delivery}")
        rows.append(dict(Case=stem, Map=map_name, Threshold=threshold, CandidateSide=side,
                         Role="candidate" if actor == side else "baseline", **snapshot,
                         CollectedCheese=sum(event["amount"] for event in pickups),
                         DeliveryEvents=len(delivery_events), PickupEvents=len(pickups),
                         ExceptionEvents=len(exceptions),
                         LoggedDeliveredCheese=logged_delivery, ReplayVerified=True))
    return {"case": stem, "rows": rows, "events": events,
            "log_sha256": sha_file(output / (stem + ".log")),
            "replay_sha256": sha_file(output / (stem + ".bc26"))}


def export_results(output: Path, results: list[dict]) -> None:
    write_json(output / "results.json", results)
    rows = [row for result in results for row in result["rows"]]
    write_csv(output / "comparison.csv", rows)
    summary = []
    for threshold in sorted({row["Threshold"] for row in rows}):
        for checkpoint in sorted({row["Checkpoint"] for row in rows}):
            group = [row for row in rows if row["Threshold"] == threshold and row["Checkpoint"] == checkpoint]
            candidates = [row for row in group if row["Role"] == "candidate"]
            baselines = [row for row in group if row["Role"] == "baseline"]
            if not candidates:
                continue
            delivered = sum(int(row["DeliveredCheese"]) for row in candidates)
            baseline_delivery = sum(int(row["DeliveredCheese"]) for row in baselines)
            summary.append(dict(Threshold=threshold, Checkpoint=checkpoint, Matches=len(candidates),
                                CandidateDelivered=delivered, BaselineDelivered=baseline_delivery,
                                DeliveryRatio=delivered / baseline_delivery if baseline_delivery else None,
                                CandidateCollected=sum(row["CollectedCheese"] for row in candidates),
                                BaselineCollected=sum(row["CollectedCheese"] for row in baselines),
                                CandidateDeliveryEvents=sum(row["DeliveryEvents"] for row in candidates),
                                BaselineDeliveryEvents=sum(row["DeliveryEvents"] for row in baselines),
                                CandidateExceptions=sum(row["ExceptionEvents"] for row in candidates),
                                BaselineExceptions=sum(row["ExceptionEvents"] for row in baselines)))
    write_csv(output / "summary.csv", summary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java-home")
    parser.add_argument("--engine-jar")
    parser.add_argument("--baseline-ref", default=BASELINE_COMMIT,
                        help="Pinned original collector revision; moving upstream/main may no longer contain it")
    parser.add_argument("--baseline-kind", choices=["original", "shared"], default="original",
                        help="shared isolates batching against current source with threshold 1")
    parser.add_argument("--output", type=Path, default=ROOT / "matches" / "foraging-refactor")
    parser.add_argument("--maps", nargs="+", default=["DefaultSmall", "DefaultMedium", "cheesefarm"])
    parser.add_argument("--thresholds", type=int, nargs="+", default=[1, 10, 40, 80, 120])
    parser.add_argument("--sides", nargs="+", choices=["A", "B"], default=["A", "B"])
    parser.add_argument("--forage-turns", type=int, default=60)
    parser.add_argument("--checkpoints", type=int, nargs="+", default=[250, 500, 1000])
    parser.add_argument("--king-policy", choices=["capped", "production"], default="capped")
    parser.add_argument("--baseline-king-policy", choices=["capped", "production"],
                        help="Default: same as candidate --king-policy; use capped for full refactor comparison")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=180, help="Maximum seconds per match")
    parser.add_argument("--limit", type=int, help="Run at most this many unfinished cases")
    args = parser.parse_args()
    if args.baseline_king_policy is None:
        args.baseline_king_policy = args.king_policy
    if min([*args.thresholds, args.forage_turns, *args.checkpoints]) <= 0:
        parser.error("thresholds, forage-turns, and checkpoints must be positive")
    if any(not re.fullmatch(r"[A-Za-z0-9_-]+", name) for name in args.maps):
        parser.error("map names must contain only letters, digits, underscore, or hyphen")
    args.thresholds = list(dict.fromkeys(args.thresholds))
    args.maps = list(dict.fromkeys(args.maps))
    args.sides = list(dict.fromkeys(args.sides))
    args.checkpoints = list(dict.fromkeys(args.checkpoints))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jdk = locate_jdk(args.java_home)
    suffix = ".exe" if os.name == "nt" else ""
    java, javac = (jdk / "bin" / (name + suffix) for name in ("java", "javac"))
    engine = locate_engine(args.engine_jar)
    manifest = prepare(args, output, java, javac, engine)
    print(f"Prepared source snapshot {manifest['fingerprint'][:12]} in {output}", flush=True)
    if args.prepare_only:
        return 0
    result_path = output / "results.json"
    results = json.loads(result_path.read_text(encoding="utf-8")) if result_path.exists() else []
    done = {result["case"] for result in results}
    cases = [(map_name, threshold, side) for threshold in args.thresholds for map_name in args.maps for side in args.sides]
    write_json(output / "cases.json", [{"map": name, "threshold": value, "side": side,
                                       "case": f"{name}-threshold{value}-{side}"}
                                      for name, value, side in cases])
    completed_now = 0
    for map_name, threshold, side in cases:
        stem = f"{map_name}-threshold{threshold}-{side}"
        if stem in done:
            continue
        if args.limit is not None and completed_now >= args.limit:
            break
        started = time.monotonic()
        command = server_command(java, engine, output, map_name, side, threshold)
        write_json(output / (stem + "-command.json"), command)
        with (output / (stem + ".log")).open("w", encoding="utf-8") as log:
            run(command, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        result = parse_case(output, stem, map_name, side, threshold, args.checkpoints, java, engine)
        result["elapsed_seconds"] = round(time.monotonic() - started, 2)
        results.append(result)
        export_results(output, results)
        final = {row["Role"]: row for row in result["rows"] if row["Checkpoint"] == "FINAL"}
        print(f"{stem}: round {final['candidate']['Round']}; delivered "
              f"{final['candidate']['DeliveredCheese']}/{final['baseline']['DeliveredCheese']}; "
              f"collected {final['candidate']['CollectedCheese']}/{final['baseline']['CollectedCheese']}; "
              f"exceptions {final['candidate']['ExceptionEvents']}/{final['baseline']['ExceptionEvents']}; "
              f"{result['elapsed_seconds']} seconds", flush=True)
        completed_now += 1
    print(f"Completed {len(results)}/{len(cases)} cases. Replay-verified results: {output / 'comparison.csv'}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.SubprocessError) as error:
        print(f"Benchmark stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
