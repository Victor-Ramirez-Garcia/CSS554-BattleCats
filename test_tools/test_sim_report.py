"""Reporting checks that guard against misleading simulation comparisons."""

import contextlib
import csv
import io
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from sim_report import print_summary, write_report


def pair(case="farm-A", side="A", end=2000, checkpoint="FINAL", delivered=(80, 100),
         winner="candidatebot", reason="MORE_POINTS"):
    round_number = (min(1000, end) if checkpoint == "COMMON_1000" else
                    int(checkpoint[6:]) if checkpoint.startswith("ROUND_") else end)
    rows = []
    for index, role in enumerate(("candidate", "baseline")):
        rows.append(dict(Case=case, Map=case.rsplit("-", 1)[0], CandidateSide=side,
                         Role=role, Checkpoint=checkpoint, Round=round_number,
                         Side=side if index == 0 else ("B" if side == "A" else "A"),
                         Package="candidatebot" if index == 0 else "baselinebot", Seed=7,
                         DeliveredCheese=delivered[index], ReserveCheese=100, Kings=1,
                         BabyRats=4, CatDamage=0, Cooperation="true", Winner=winner,
                         WinType=reason, FinalRound=end, LastKingAliveRound=end))
    return rows


class SimReportTests(unittest.TestCase):
    def report(self, rows, expected, manifest=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_report(output, rows, manifest or {}, expected)
            markdown = (output / "summary.md").read_text(encoding="utf-8")
            with (output / "results.csv").open(newline="", encoding="utf-8") as handle:
                saved_rows = list(csv.DictReader(handle))
        return markdown, saved_rows

    def test_early_end_is_not_counted_as_strict_round_1000(self):
        rows = pair(end=300) + pair(end=300, checkpoint="COMMON_1000")
        rows += pair(case="farm-B", side="B", end=2000, delivered=(200, 100))
        rows += pair(case="farm-B", side="B", end=2000, checkpoint="ROUND_1000", delivered=(120, 100))
        rows += pair(case="farm-B", side="B", end=2000, checkpoint="COMMON_1000", delivered=(120, 100))
        report, saved = self.report(rows, 2)
        self.assertIn("| Final | 2/2 | 280 | 200 | +40.0% |", report)
        self.assertIn("| Strict round 1000 | 1/2 | 120 | 100 | +20.0% |", report)
        self.assertIn("| Common checkpoint: min(1000, end) | 2/2 | 200 | 200 | +0.0% |", report)
        self.assertIn("mixes times and is not a fixed-round comparison", report)
        self.assertIn("**1** ended before round 1000", report)
        self.assertEqual(len(rows), len(saved))

    def test_partial_pairs_remain_visible_but_do_not_inflate_totals(self):
        rows = pair() + pair(case="farm-B", side="B")[:1]
        report, saved = self.report(rows, 2)
        self.assertIn("**INCOMPLETE: 1/2 complete match pairs.**", report)
        self.assertIn("| Final | 1/2 | 80 | 100 | -20.0% |", report)
        self.assertIn("partial totals do not establish an overall improvement", report)
        self.assertEqual(3, len(saved))

    def test_duplicate_or_inconsistent_pairs_are_excluded(self):
        rows = pair()
        rows[1]["Seed"] = 9
        rows += pair(case="other-A") + pair(case="other-A")[:1]
        report, saved = self.report(rows, 2)
        self.assertIn("**INCOMPLETE: 0/2 complete match pairs.**", report)
        self.assertEqual(5, len(saved))

    def test_zero_denominators_never_claim_percentage_gain(self):
        report, _ = self.report(pair(delivered=(0, 0)), 1)
        self.assertIn("| Final | 1/1 | 0 | 0 | n/a (baseline 0) |", report)
        report, _ = self.report(pair(delivered=(10, 0)), 1)
        self.assertIn("| Final | 1/1 | 10 | 0 | n/a (baseline 0) |", report)
        self.assertNotIn("inf%", report)

    def test_winners_and_regressions_follow_role_after_side_swap(self):
        rows = pair(winner="candidatebot", reason="COIN_FLIP")
        rows += pair(case="farm-B", side="B", winner="baselinebot", delivered=(20, 100), reason="RATKING_DESTROYED")
        report, _ = self.report(rows, 2)
        self.assertIn("Candidate wins: **1/2**; baseline wins: **1/2**", report)
        self.assertIn("| COIN_FLIP | 1 | 0 | 0 |", report)
        self.assertIn("| RATKING_DESTROYED | 0 | 1 | 0 |", report)
        self.assertIn("| farm | 2 | A, B | 100 | 200 | -50.0% | 1/2 |", report)
        self.assertIn("Coin-flip outcomes: **1**", report)
        self.assertIn("| farm-B | B | 2000 | baseline |", report)

    def test_identity_snapshot_and_relative_artifact_links(self):
        manifest = dict(baseline_ref="upstream/main", baseline_commit="abc123",
                        candidate_ref="working-tree", working_tree_sha256="def456", preset="quick",
                        cases=[dict(case="farm-A", replay="replays/farm A.bc26", log="logs/farm A.log")])
        report, _ = self.report(pair(), 1, manifest)
        self.assertIn("Baseline: upstream/main; commit abc123", report)
        self.assertIn("Candidate: working-tree; source snapshot SHA-256 def456", report)
        self.assertIn("Preset: quick", report)
        self.assertIn("[replay](replays/farm%20A.bc26) / [log](logs/farm%20A.log)", report)

    def test_empty_run_has_headers_and_incomplete_status(self):
        report, saved = self.report([], 2)
        self.assertIn("**INCOMPLETE: 0/2 complete match pairs.**", report)
        self.assertNotIn("All reported replay checkpoints were in cooperation", report)
        self.assertEqual([], saved)
        terminal = io.StringIO()
        with contextlib.redirect_stdout(terminal):
            print_summary([], 2)
        self.assertIn("INCOMPLETE: 0/2", terminal.getvalue())

    def test_cooperation_claim_is_limited_to_observed_samples(self):
        rows = pair()
        report, _ = self.report(rows, 1)
        self.assertIn("All reported replay checkpoints were in cooperation", report)
        self.assertIn("do not establish behavior after a PvP transition", report)
        rows[0]["Cooperation"] = False
        report, _ = self.report(rows, 1)
        self.assertNotIn("All reported replay checkpoints were in cooperation", report)

    def test_same_java_packages_use_engine_role_winner(self):
        rows = pair(side="B", winner="baseline")
        for row in rows:
            del row["Package"]
            row["JavaPackage"] = "battlecats"
            row["Team"] = row["Role"]
        report, _ = self.report(rows, 1)
        self.assertIn("Candidate wins: **0/1**; baseline wins: **1/1**", report)
        self.assertIn("Candidate simulation package: battlecats", report)
        self.assertIn("Baseline simulation package: battlecats", report)

    def test_failed_run_never_looks_complete_and_escapes_error(self):
        report, _ = self.report(pair(), 1, dict(status="FAILED", error="failure | <details>"))
        self.assertIn("**FAILED: 1/1 complete match pairs.**", report)
        self.assertIn("Run error: failure &#124; &lt;details&gt;", report)

    def test_cancelled_status_is_preserved_even_after_last_match(self):
        report, _ = self.report(pair(), 1, dict(status="CANCELLED", error="Interrupted"))
        self.assertIn("**CANCELLED: 1/1 complete match pairs.**", report)
        self.assertNotIn("**Complete:", report)
        self.assertIn("Run error: Interrupted", report)

    def test_manifest_packages_are_visible_before_any_match_completes(self):
        report, _ = self.report([], 2, dict(packages=dict(candidate="mybot", baseline="battlecats")))
        self.assertIn("Candidate simulation package: mybot", report)
        self.assertIn("Baseline simulation package: battlecats", report)


if __name__ == "__main__":
    unittest.main()
