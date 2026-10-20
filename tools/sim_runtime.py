"""Runtime discovery and replay metrics for the Java simulation mode (engine 1.2.6)."""

from __future__ import annotations

import os
from pathlib import Path
import shutil

ENGINE_VERSION = "1.2.6"
OPEN_PACKAGES = ('jdk.internal.misc', 'jdk.internal.math', 'jdk.internal.util', 'jdk.internal.access', 'sun.security.action')

# Teams are identified by numeric side, independently of their Java package.
REPLAY_READER = r"""import battlecode.schema.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
public class SimulationReplayMetrics {
    static void snapshot(PrintWriter out, String label, Round round, String[] packages,
                         MatchFooter footer, boolean coop, int seed,
                         int finalRound, int[] lastKingAlive) {
        for (int i = 0; i < round.teamIdsLength(); i++) {
            int id = round.teamIds(i), combined = round.teamAliveRatKings(i);
            out.printf(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,%s,%d,%d%n",
                label, round.roundId(), id == 1 ? "A" : "B", packages[id],
                seed, round.teamCheeseTransferred(i), combined / 10, combined % 10,
                round.teamAliveBabyRats(i), round.teamCatDamage(i), coop,
                packages[footer.winner()], WinType.name(footer.winType()), finalRound, lastKingAlive[id]);
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
        int[] lastKingAlive = new int[3];
        boolean coop = true;
        Map<Integer, Round> rounds = new LinkedHashMap<>();
        Map<Integer, Boolean> phases = new HashMap<>();
        Set<Integer> wanted = new HashSet<>();
        for (String token : args[2].split(",")) wanted.add(Integer.parseInt(token));
        wanted.add(1000);
        for (int i = 0; i < game.eventsLength(); i++) {
            EventWrapper event = game.events(i);
            if (event.eType() == Event.GameHeader) {
                GameHeader header = (GameHeader) event.e(new GameHeader());
                for (int t = 0; t < header.teamsLength(); t++) {
                    TeamData team = header.teams(t);
                    packages[team.teamId()] = ((team.teamId() == 1) == args[3].equals("A")) ? "candidate" : "baseline";
                }
            } else if (event.eType() == Event.MatchHeader) {
                seed = ((MatchHeader) event.e(new MatchHeader())).map().randomSeed();
            } else if (event.eType() == Event.Round) {
                last = (Round) event.e(new Round());
                for (int team = 0; team < last.teamIdsLength(); team++)
                    if (last.teamAliveRatKings(team) % 10 > 0)
                        lastKingAlive[last.teamIds(team)] = last.roundId();
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
            out.println("Checkpoint,Round,Side,Team,Seed,DeliveredCheese,ReserveCheese,Kings,BabyRats,CatDamage,Cooperation,Winner,WinType,FinalRound,LastKingAliveRound");
            for (var entry : rounds.entrySet())
                snapshot(out, "ROUND_" + entry.getKey(), entry.getValue(), packages, footer,
                         phases.get(entry.getKey()), seed, last.roundId(), lastKingAlive);
            Round common = rounds.containsKey(1000) ? rounds.get(1000) : last;
            snapshot(out, "COMMON_1000", common, packages, footer,
                     phases.getOrDefault(common.roundId(), coop), seed, last.roundId(), lastKingAlive);
            snapshot(out, "FINAL", last, packages, footer, coop, seed, last.roundId(), lastKingAlive);
        }
    }
}
"""


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
