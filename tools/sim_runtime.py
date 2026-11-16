"""Runtime discovery and replay metrics for the Java simulation mode (engine 1.2.6)."""

from __future__ import annotations

import os
from pathlib import Path
import shutil

ENGINE_VERSION = "1.2.6"
OPEN_PACKAGES = ('jdk.internal.misc', 'jdk.internal.math', 'jdk.internal.util', 'jdk.internal.access', 'sun.security.action')

# Fixed rounds sampled from every replay. Strong opponents often end games
# before round 200, so the early checkpoints keep fixed-time comparisons
# possible when win/loss records do not move.
CHECKPOINTS = (50, 100, 150, 250, 500, 1000)

# Columns the reader adds after the original ones, cumulative from round 1 to
# the checkpoint unless noted. Counts come from engine replay actions, never
# from bot logs or indicator strings. The 1.2.6 engine records bites
# (RatAttack) but leaves per-hit DamageAction empty, so combat is counted in
# bites rather than health.
MARGIN_METRICS = (
    "FirstDeliveryRound",      # first round with delivered cheese; 0 = none yet
    "PeakBabyRats",            # most Baby Rats alive at once
    "PeakKings",               # most Rat Kings alive at once; >1 shows a promotion
    "RatsSpawned",             # Baby Rats created after the initial bodies
    "KingsFormed",             # UpgradeToRatKing actions by this team
    "BitesOnEnemyRats",
    "BitesOnEnemyKings",
    "BitesOnCats",
    "KingBitesTaken",          # enemy bites on this team's kings
    "RatsLost",
    "KingsLost",
    "StartingKingDeathRound",  # 0 = the original king is still alive
    "RatTraps",                # current count at the checkpoint, not cumulative
    "CatTraps",                # current count at the checkpoint, not cumulative
    "Dirt",                    # current count at the checkpoint, not cumulative
)

# Teams are identified by numeric side, independently of their Java package.
REPLAY_READER = r"""import battlecode.schema.*;
import com.google.flatbuffers.Table;
import java.lang.reflect.Field;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
public class SimulationReplayMetrics {
    static final String[] METRICS = {"FirstDeliveryRound", "PeakBabyRats", "PeakKings",
        "RatsSpawned", "KingsFormed", "BitesOnEnemyRats", "BitesOnEnemyKings", "BitesOnCats",
        "KingBitesTaken", "RatsLost", "KingsLost", "StartingKingDeathRound", "RatTraps",
        "CatTraps", "Dirt"};
    static final int FIRST_DELIVERY = 0, PEAK_RATS = 1, PEAK_KINGS = 2, SPAWNED = 3,
        KINGS_FORMED = 4, BITES_RATS = 5, BITES_KINGS = 6, BITES_CATS = 7, KING_BITES_TAKEN = 8,
        RATS_LOST = 9, KINGS_LOST = 10, START_KING_DEATH = 11, RAT_TRAPS = 12, CAT_TRAPS = 13,
        DIRT = 14;

    // Action payloads are structs. The generated Turn.actions(Table, int)
    // accessor only accepts tables, so read the vector offset directly.
    // Slot 26 is Turn's twelfth field (actions) in the 1.2.6 schema.
    static final class Actions extends Table {
        static final Field POSITION;
        static {
            try {
                POSITION = Table.class.getDeclaredField("bb_pos");
                POSITION.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        Actions(Turn turn) throws IllegalAccessException {
            __reset(POSITION.getInt(turn), turn.getByteBuffer());
        }
        int offset(int index) {
            return __indirect(__vector(__offset(26)) + index * 4);
        }
    }

    static final class Sample {
        final Round round;
        final boolean coop;
        final int[][] stats;
        Sample(Round round, boolean coop, int[][] stats) {
            this.round = round;
            this.coop = coop;
            this.stats = new int[3][];
            for (int team = 0; team < 3; team++) this.stats[team] = stats[team].clone();
        }
    }

    static void snapshot(PrintWriter out, String label, Sample sample, String[] packages,
                         MatchFooter footer, int seed, int finalRound, int[] lastKingAlive) {
        Round round = sample.round;
        for (int i = 0; i < round.teamIdsLength(); i++) {
            int id = round.teamIds(i), combined = round.teamAliveRatKings(i);
            StringBuilder extra = new StringBuilder();
            for (int value : sample.stats[id]) extra.append(',').append(value);
            out.printf(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,%s,%d,%d%s%n",
                label, round.roundId(), id == 1 ? "A" : "B", packages[id],
                seed, round.teamCheeseTransferred(i), combined / 10, combined % 10,
                round.teamAliveBabyRats(i), round.teamCatDamage(i), sample.coop,
                packages[footer.winner()], WinType.name(footer.winType()), finalRound,
                lastKingAlive[id], extra);
        }
    }

    static boolean isTeam(Integer team) {
        return team != null && (team == 1 || team == 2);
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
        int[] startingKing = {-1, -1, -1};
        int[][] stats = new int[3][METRICS.length];
        Map<Integer, Integer> teamOf = new HashMap<>(), typeOf = new HashMap<>();
        boolean coop = true;
        Map<Integer, Sample> samples = new LinkedHashMap<>();
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
                GameMap map = ((MatchHeader) event.e(new MatchHeader())).map();
                seed = map.randomSeed();
                InitialBodyTable initial = map.initialBodies();
                for (int j = 0; j < initial.spawnActionsLength(); j++) {
                    SpawnAction spawn = initial.spawnActions(j);
                    teamOf.put(spawn.id(), (int) spawn.team());
                    typeOf.put(spawn.id(), (int) spawn.robotType());
                    if (isTeam((int) spawn.team()) && spawn.robotType() == RobotType.RAT_KING
                            && startingKing[spawn.team()] < 0) {
                        startingKing[spawn.team()] = spawn.id();
                    }
                }
            } else if (event.eType() == Event.Round) {
                last = (Round) event.e(new Round());
                int roundId = last.roundId();
                for (int team = 0; team < last.teamIdsLength(); team++) {
                    int id = last.teamIds(team), kings = last.teamAliveRatKings(team) % 10;
                    int[] s = stats[id];
                    if (kings > 0) lastKingAlive[id] = roundId;
                    if (s[FIRST_DELIVERY] == 0 && last.teamCheeseTransferred(team) > 0) s[FIRST_DELIVERY] = roundId;
                    s[PEAK_RATS] = Math.max(s[PEAK_RATS], last.teamAliveBabyRats(team));
                    s[PEAK_KINGS] = Math.max(s[PEAK_KINGS], kings);
                    s[RAT_TRAPS] = last.teamRatTrapCount(team);
                    s[CAT_TRAPS] = last.teamCatTrapCount(team);
                    s[DIRT] = last.teamDirtAmounts(team);
                }
                for (int t = 0; t < last.turnsLength(); t++) {
                    Turn turn = last.turns(t);
                    ByteBuffer data = turn.getByteBuffer();
                    Actions actions = new Actions(turn);
                    int actor = turn.robotId();
                    Integer actorTeam = teamOf.get(actor);
                    for (int k = 0; k < turn.actionsLength(); k++) {
                        int offset = actions.offset(k);
                        switch (turn.actionsType(k)) {
                            case Action.SpawnAction -> {
                                SpawnAction spawn = new SpawnAction().__assign(offset, data);
                                teamOf.put(spawn.id(), (int) spawn.team());
                                typeOf.put(spawn.id(), (int) spawn.robotType());
                                if (isTeam((int) spawn.team()) && spawn.robotType() == RobotType.RAT)
                                    stats[spawn.team()][SPAWNED]++;
                            }
                            case Action.UpgradeToRatKing -> {
                                // The upgrading robot keeps its id, so later bites on it count as king bites.
                                typeOf.put(actor, (int) RobotType.RAT_KING);
                                if (isTeam(actorTeam)) stats[actorTeam][KINGS_FORMED]++;
                            }
                            case Action.RatAttack -> {
                                int target = new RatAttack().__assign(offset, data).id();
                                Integer targetTeam = teamOf.get(target);
                                int targetType = typeOf.getOrDefault(target, -1);
                                if (!isTeam(actorTeam)) break;
                                if (targetType == RobotType.CAT) {
                                    stats[actorTeam][BITES_CATS]++;
                                } else if (isTeam(targetTeam) && !targetTeam.equals(actorTeam)) {
                                    if (targetType == RobotType.RAT_KING) {
                                        stats[actorTeam][BITES_KINGS]++;
                                        stats[targetTeam][KING_BITES_TAKEN]++;
                                    } else {
                                        stats[actorTeam][BITES_RATS]++;
                                    }
                                }
                            }
                            case Action.DieAction -> {
                                int dead = new DieAction().__assign(offset, data).id();
                                Integer deadTeam = teamOf.get(dead);
                                if (!isTeam(deadTeam)) break;
                                if (typeOf.getOrDefault(dead, -1) == RobotType.RAT_KING) {
                                    stats[deadTeam][KINGS_LOST]++;
                                    if (dead == startingKing[deadTeam] && stats[deadTeam][START_KING_DEATH] == 0)
                                        stats[deadTeam][START_KING_DEATH] = roundId;
                                } else {
                                    stats[deadTeam][RATS_LOST]++;
                                }
                            }
                            default -> {}
                        }
                    }
                }
                if (last.turnsLength() > 0) coop = last.turns(last.turnsLength() - 1).isCooperation();
                if (wanted.contains(roundId)) samples.put(roundId, new Sample(last, coop, stats));
            } else if (event.eType() == Event.MatchFooter) {
                footer = (MatchFooter) event.e(new MatchFooter());
            }
        }
        if (last == null || footer == null) throw new IllegalStateException("Incomplete replay");
        Sample end = new Sample(last, coop, stats);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            out.println("Checkpoint,Round,Side,Team,Seed,DeliveredCheese,ReserveCheese,Kings,BabyRats,CatDamage,"
                        + "Cooperation,Winner,WinType,FinalRound,LastKingAliveRound," + String.join(",", METRICS));
            for (var entry : samples.entrySet())
                snapshot(out, "ROUND_" + entry.getKey(), entry.getValue(), packages, footer,
                         seed, last.roundId(), lastKingAlive);
            Sample common = samples.getOrDefault(1000, end);
            snapshot(out, "COMMON_1000", common, packages, footer, seed, last.roundId(), lastKingAlive);
            snapshot(out, "FINAL", end, packages, footer, seed, last.roundId(), lastKingAlive);
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
