"""Checks for comparing two candidates that played the same opponent."""

import contextlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from sim_compare import RunMismatch, compare, load_run, main
from sim_report import write_report
from sim_runtime import CHECKPOINTS, MARGIN_METRICS, REPLAY_READER
from test_sim_report import margin_pair


OPPONENT = dict(baseline_ref="opponent-pool/champion-sprint2", baseline_commit="c" * 40,
                packages={"candidate": "battlecats", "baseline": "awubot"}, engine_version="1.2.6")


def games(share_lost, king_death, final_rounds=(137, 180), seeds=7):
    rows = []
    for (case, side), end in zip((("small-A", "A"), ("small-B", "B")), final_rounds):
        for checkpoint, delivered in (("ROUND_100", (60, 140)), ("FINAL", (120 + share_lost, 480))):
            if checkpoint.startswith("ROUND_") and int(checkpoint[6:]) > end:
                continue
            for row in margin_pair(case, side, end, checkpoint, delivered,
                                   dict(StartingKingDeathRound=king_death, RatsLost=share_lost // 10), {}):
                row["Seed"] = seeds
                rows.append(row)
    return rows


class SimCompareTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def run_folder(self, name, rows, **manifest):
        folder = self.root / name
        write_report(folder, rows, {**OPPONENT, **manifest}, 2)
        (folder / "manifest.json").write_text(json.dumps({**OPPONENT, **manifest}), encoding="utf-8")
        return folder

    def test_reports_candidate_change_against_unchanged_opponent(self):
        before = self.run_folder("before", games(0, 137), candidate_commit="a" * 40, candidate_ref="HEAD~1")
        after = self.run_folder("after", games(120, 0), candidate_commit="b" * 40, candidate_ref="HEAD")
        markdown, summary = compare(load_run(before), load_run(after))
        self.assertEqual(2, summary["cases"])
        self.assertEqual((0, 0), summary["wins"])
        self.assertIn("| Delivered cheese | 240 | 480 | +240 | 960 | 960 |", markdown)
        self.assertIn("| Share of cheese delivered | 20.0% | 33.3% | +13.3 pts | 80.0% | 66.7% |", markdown)
        self.assertIn("## Round 100 (equal playing time)", markdown)
        self.assertIn("| small-A | 137 → 137 | baseline → baseline | 20% → 33% | 137 → alive (137) |", markdown)
        self.assertIn("Opponent: opponent-pool/champion-sprint2 (commit cccccccccccc, package awubot)", markdown)

    def test_different_opponents_are_refused(self):
        before = self.run_folder("before", games(0, 137))
        after = self.run_folder("after", games(0, 137), baseline_commit="d" * 40)
        with self.assertRaises(RunMismatch):
            compare(load_run(before), load_run(after))

    def test_cases_with_different_seeds_are_excluded(self):
        before = self.run_folder("before", games(0, 137))
        after = self.run_folder("after", games(0, 137, seeds=9))
        with self.assertRaisesRegex(RunMismatch, "No case completed in both runs"):
            compare(load_run(before), load_run(after))

    def test_fixed_window_needs_a_round_every_game_reached_in_both_runs(self):
        before = self.run_folder("before", games(0, 137, final_rounds=(90, 180)))
        after = self.run_folder("after", games(0, 137))
        markdown, _ = compare(load_run(before), load_run(after))
        self.assertNotIn("equal playing time", markdown)

    def test_command_writes_report_in_later_run(self):
        before = self.run_folder("before", games(0, 137))
        after = self.run_folder("after", games(120, 0))
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, main([str(before), str(after)]))
        self.assertTrue((after / "compare-vs-before.md").is_file())
        self.assertIn("cheese share 20.0% -> 33.3%", out.getvalue())

    def test_missing_run_files_have_a_clear_error(self):
        with self.assertRaisesRegex(ValueError, "missing manifest.json"):
            load_run(self.root)


class ReaderContractTests(unittest.TestCase):
    """The Java reader and the Python column list must not drift apart."""

    def test_java_metric_names_match_python_columns(self):
        names = re.search(r"METRICS = \{(.*?)\};", REPLAY_READER, re.S).group(1)
        self.assertEqual(list(MARGIN_METRICS), re.findall(r'"(\w+)"', names))

    def test_java_metric_indexes_follow_column_order(self):
        constants = re.search(r"static final int (FIRST_DELIVERY.*?);", REPLAY_READER, re.S).group(1)
        indexes = [int(value) for value in re.findall(r"= (\d+)", constants)]
        self.assertEqual(list(range(len(MARGIN_METRICS))), indexes)

    def test_checkpoints_are_increasing_and_include_round_1000(self):
        self.assertEqual(sorted(set(CHECKPOINTS)), list(CHECKPOINTS))
        self.assertIn(1000, CHECKPOINTS)


if __name__ == "__main__":
    unittest.main()
