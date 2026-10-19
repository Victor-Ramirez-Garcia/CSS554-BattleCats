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

    def pr_report(self, rows, expected, manifest=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_report(output, rows, manifest or {}, expected)
            return (output / "pr-summary.md").read_text(encoding="utf-8")

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

    def test_pr_note_records_pinned_versions_and_requires_rationale_and_review(self):
        manifest = dict(
            baseline_ref="upstream/main", baseline_commit="a" * 40,
            candidate_ref="origin/codex/collector", candidate_commit="b" * 40,
            versions=dict(baseline=dict(subject="Fix king food", date="2026-10-18"),
                          candidate=dict(subject="Collect more cheese", date="2026-10-19")),
            maps=["farm"], preset="custom", engine_version="1.2.6", label="Carry threshold 80",
            cases=[dict(case="farm-A", side="A"), dict(case="farm-B", side="B")],
            jar_hashes=dict(baseline="c" * 64, candidate="d" * 64))
        note = self.pr_report(pair(), 2, manifest)
        self.assertIn("Baseline: upstream/main; commit " + "a" * 40, note)
        self.assertIn("Candidate: origin/codex/collector; commit " + "b" * 40, note)
        self.assertIn("Fix king food; 2026-10-18", note)
        self.assertIn("Collect more cheese; 2026-10-19", note)
        self.assertNotIn("UNCOMMITTED", note)
        self.assertIn("**INCOMPLETE: 1/2 complete match pairs.**", note)
        self.assertIn("candidate starting sides: A, B", note)
        self.assertIn("Experiment: Carry threshold 80", note)
        self.assertIn("both snapshots compiled and passed the bot verifier", note)
        self.assertIn("Unit-test status is not recorded", note)
        self.assertIn("Pending: describe the intended change", note)
        self.assertIn("Pending: independent review", note)
        summary, _ = self.report(pair(), 2, manifest)
        self.assertIn("Experiment: Carry threshold 80", summary)
        self.assertIn("Baseline version: Fix king food; 2026-10-18", summary)

    def test_pr_note_worktree_snapshot_does_not_treat_head_as_candidate_commit(self):
        manifest = dict(baseline_ref="upstream/main", baseline_commit="a" * 40,
                        candidate_ref="WORKTREE", candidate_head="b" * 40,
                        working_tree_sha256="c" * 64,
                        versions=dict(candidate=dict(kind="worktree", subject="Saved local files")))
        note = self.pr_report(pair(), 1, manifest)
        self.assertIn("**UNCOMMITTED snapshot — exploratory, not a final PR comparison**", note)
        self.assertIn("WORKTREE; source snapshot SHA-256 " + "c" * 64, note)
        self.assertNotIn("commit " + "b" * 40, note)
        self.assertIn("Saved local files", note)
        self.assertIn("compilation/verifier completion is not recorded for both snapshots", note)

    def test_pr_note_failed_or_cancelled_last_match_is_not_a_complete_run(self):
        for status in ("FAILED", "CANCELLED"):
            with self.subTest(status=status):
                note = self.pr_report(pair(), 1, dict(status=status, error="stopped | <details>"))
                self.assertIn(f"**{status}: 1/1 complete match pairs.**", note)
                self.assertIn("not a completed comparison", note)
                self.assertIn("partial totals", note.lower())
                self.assertIn("stopped &#124; &lt;details&gt;", note)
                self.assertNotIn("both snapshots compiled and passed", note)

    def test_pr_note_does_not_hide_map_regression_under_overall_gain_or_coin_flip(self):
        rows = pair(case="good-A", delivered=(500, 100), reason="MORE_POINTS")
        rows += pair(case="farm-A", delivered=(40, 100), reason="COIN_FLIP")
        rows += pair(case="good-A", checkpoint="ROUND_1000", delivered=(120, 100))
        rows += pair(case="farm-A", checkpoint="COMMON_1000", delivered=(30, 80))
        note = self.pr_report(rows, 2, dict(preset="custom", maps=["good", "farm"]))
        self.assertIn("| Final | 2/2 | 540 | 200 | +170.0% |", note)
        self.assertIn("| Fixed round 1000 | 1/2 | 120 | 100 | +20.0% |", note)
        self.assertIn("| farm | 1 | 40 | 100 | -60.0% |", note)
        self.assertIn("MORE_POINTS: 1/0/0", note)
        self.assertIn("COIN_FLIP: 1/0/0", note)
        self.assertIn("Coin-flip outcomes: **1**", note)
        self.assertIn("Delivered cheese is not the engine's match points", note)
        self.assertIn("All reported checkpoints were in cooperation", note)
        self.assertIn("does not establish behavior after a PvP transition", note)
        self.assertIn("[summary.md](summary.md)", note)
        self.assertIn("[results.csv](results.csv)", note)

    def test_pr_note_zero_baseline_and_empty_results_do_not_claim_gain(self):
        note = self.pr_report(pair(delivered=(10, 0)), 1)
        self.assertIn("| Final | 1/1 | 10 | 0 | n/a (baseline 0) |", note)
        self.assertNotIn("inf%", note)
        empty = self.pr_report([], 2)
        self.assertIn("**INCOMPLETE: 0/2 complete match pairs.**", empty)
        self.assertIn("no full-match or PvP coverage is assumed", empty)
        self.assertNotIn("All reported checkpoints were in cooperation", empty)

    def test_pr_note_build_claim_requires_two_real_jar_hashes(self):
        for hashes in (True, ["candidate", "baseline"], {"candidate": "a" * 64},
                       {"candidate": "pass", "baseline": "pass"}):
            with self.subTest(hashes=hashes):
                note = self.pr_report(pair(), 1, dict(jar_hashes=hashes))
                self.assertNotIn("both snapshots compiled and passed", note)

    def test_round_1000_pair_after_match_end_is_excluded(self):
        rows = pair(end=300) + pair(end=300, checkpoint="ROUND_1000")
        note = self.pr_report(rows, 1)
        self.assertIn("| Fixed round 1000 | 0/1 | 0 | 0 | n/a (baseline 0) |", note)

    def test_running_status_is_not_promoted_before_runner_finishes(self):
        note = self.pr_report(pair(), 1, dict(status="RUNNING"))
        self.assertIn("**RUNNING: 1/1 complete match pairs.**", note)
        self.assertIn("not a completed comparison", note)


if __name__ == "__main__":
    unittest.main()
