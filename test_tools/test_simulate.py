"""Regression tests for source snapshots used by the local simulator.

These use tiny temporary Git repositories and do not run Battlecode or change
the checkout containing the runner.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("simulate_under_test", TOOLS / "simulate.py")
assert SPEC is not None and SPEC.loader is not None
simulate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = simulate
SPEC.loader.exec_module(simulate)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root_patch = patch.object(simulate, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.git("init", "--quiet")
        self.git("config", "user.email", "simulator-tests@example.invalid")
        self.git("config", "user.name", "Simulator Tests")
        self.write(".gitignore", "src/ignored/\n")
        self.write("src/battlecats/RobotPlayer.java", "package battlecats;\npublic class RobotPlayer {}\n")
        self.write("src/battlecats/extra/Helper.java", "package battlecats.extra;\npublic class Helper {}\n")
        self.write("src/battlecatsextra/Other.java", "package battlecatsextra;\npublic class Other {}\n")
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "Baseline bot")
        self.baseline = self.git("rev-parse", "HEAD").strip()

    def git(self, *arguments):
        return subprocess.run(
            ["git", *arguments], cwd=self.root, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            encoding="utf-8",
        ).stdout

    def write(self, name, contents):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(contents.encode("utf-8"))

    def test_saved_worktree_changes_are_included_by_default(self):
        changed = "package battlecats;\npublic class RobotPlayer { int localChange = 7; }\n"
        self.write("src/battlecats/RobotPlayer.java", changed)

        sources = simulate.load_sources()

        self.assertEqual(changed, sources["battlecats/RobotPlayer.java"])

    def test_saved_source_is_preserved_without_instrumentation_or_line_ending_changes(self):
        source = (
            "package battlecats;\r\n"
            "// Do not rewrite battlecats.RobotPlayer references in comments.\r\n"
            'class RobotPlayer { String label = "battlecats.RobotPlayer — 猫"; }\r\n'
        )
        self.write("src/battlecats/RobotPlayer.java", source)

        sources = simulate.load_sources()

        self.assertEqual(source, sources["battlecats/RobotPlayer.java"])

    def test_committed_baseline_excludes_worktree_changes_and_new_files(self):
        self.write("src/battlecats/RobotPlayer.java", "package battlecats;\nclass RobotPlayer { int dirty; }\n")
        self.write("src/battlecats/NewHelper.java", "package battlecats;\nclass NewHelper {}\n")

        sources = simulate.load_sources(self.baseline)

        self.assertEqual("package battlecats;\npublic class RobotPlayer {}\n", sources["battlecats/RobotPlayer.java"])
        self.assertNotIn("battlecats/NewHelper.java", sources)

    def test_new_helpers_and_nested_sources_are_snapshotted(self):
        helper = "package battlecats.navigation.deep;\nclass Search {}\n"
        self.write("src/battlecats/navigation/deep/Search.java", helper)
        self.write("src/shared/navigation/Path.java", "package shared.navigation;\nclass Path {}\n")
        self.write("src/battlecats/navigation/readme.txt", "Do not compile this")
        self.write("src/ignored/NotSource.java", "Not intended for compilation")

        sources = simulate.load_sources()

        self.assertEqual(helper, sources["battlecats/navigation/deep/Search.java"])
        self.assertIn("battlecats/extra/Helper.java", sources)
        self.assertIn("shared/navigation/Path.java", sources)
        self.assertIn("battlecatsextra/Other.java", sources)
        self.assertNotIn("battlecats/navigation/readme.txt", sources)
        self.assertNotIn("ignored/NotSource.java", sources)

    def test_candidate_snapshot_does_not_change_baseline_or_saved_files(self):
        baseline = simulate.load_sources(self.baseline)
        before = dict(baseline)
        candidate_source = "package battlecats;\npublic class RobotPlayer { int changed = 1; }\n"
        self.write("src/battlecats/RobotPlayer.java", candidate_source)

        candidate = simulate.load_sources()

        self.assertEqual(before, baseline)
        self.assertNotEqual(baseline, candidate)
        self.assertEqual(candidate_source, (self.root / "src/battlecats/RobotPlayer.java").read_text(encoding="utf-8"))

    def test_deleted_saved_source_is_excluded_but_retained_in_committed_baseline(self):
        (self.root / "src/battlecats/extra/Helper.java").unlink()

        candidate = simulate.load_sources()
        baseline = simulate.load_sources(self.baseline)

        self.assertNotIn("battlecats/extra/Helper.java", candidate)
        self.assertIn("battlecats/extra/Helper.java", baseline)


class SourceValidationTests(unittest.TestCase):
    def test_valid_package_accepts_helpers_and_external_packages(self):
        sources = {
            "battlecats/RobotPlayer.java": (
                "package battlecats;\n"
                "import shared.navigation.Path;\n"
                "class RobotPlayer { shared.navigation.Path path; }\n"
            ),
            "shared/navigation/Path.java": "package shared.navigation;\nclass Path {}\n",
        }

        simulate.validate_package(sources, "battlecats")

    def test_package_may_have_multiple_components(self):
        simulate.validate_package(
            {"team/rats/RobotPlayer.java": "package team.rats;\nclass RobotPlayer {}\n"},
            "team.rats",
        )

    def test_missing_robot_player_has_a_clear_error(self):
        with self.assertRaisesRegex((ValueError, RuntimeError), "(?i)(package|RobotPlayer)"):
            simulate.validate_package({"battlecats/Helper.java": "package battlecats;\nclass Helper {}\n"}, "battlecats")

    def test_invalid_package_names_are_rejected(self):
        for name in ("", "../battlecats", "battlecats/extra", "battlecats..extra", "battlecats;", "two words", "2battlecats"):
            with self.subTest(name=name):
                with self.assertRaisesRegex((ValueError, RuntimeError), "(?i)package"):
                    simulate.validate_package({}, name)


class SourceHashTests(unittest.TestCase):
    def test_hash_is_stable_when_file_order_changes(self):
        sources = {"battlecats/Z.java": "class Z {}", "battlecats/A.java": "class A {}"}

        self.assertEqual(simulate.source_hash(sources), simulate.source_hash(dict(reversed(list(sources.items())))))

    def test_content_and_path_both_contribute_to_hash(self):
        original = {"battlecats/RobotPlayer.java": "package battlecats;\nclass RobotPlayer {}\n"}
        content_change = {"battlecats/RobotPlayer.java": "package battlecats;\nclass RobotPlayer { int changed; }\n"}
        path_change = {"other/RobotPlayer.java": original["battlecats/RobotPlayer.java"]}

        self.assertNotEqual(simulate.source_hash(original), simulate.source_hash(content_change))
        self.assertNotEqual(simulate.source_hash(original), simulate.source_hash(path_change))


class MapValidationTests(unittest.TestCase):
    def test_accepts_map_names_and_deduplicates_in_order(self):
        self.assertEqual(
            ["DefaultSmall", "cheesefarm", "minimaze"],
            simulate.validate_maps(["DefaultSmall", "cheesefarm", "DefaultSmall", "minimaze"]),
        )

    def test_invalid_map_names_are_rejected(self):
        for name in ("", "../DefaultSmall", "maps/DefaultSmall", "maps\\DefaultSmall", "two words", "DefaultSmall,DefaultLarge", "DefaultSmall;exit"):
            with self.subTest(name=name):
                with self.assertRaisesRegex((ValueError, RuntimeError), "(?i)map"):
                    simulate.validate_maps([name])

    def test_empty_map_selection_has_a_clear_error(self):
        with self.assertRaisesRegex((ValueError, RuntimeError), "(?i)map"):
            simulate.validate_maps([])


class BaselineSelectionTests(unittest.TestCase):
    def assert_baseline(self, remotes, expected_ref, offline=False):
        commit = "abc123" * 6 + "abcd"
        with patch.object(simulate, "git", side_effect=[remotes.encode(), commit.encode()]) as git:
            with patch.object(simulate, "command") as command:
                result = simulate.baseline_revision(None, offline)

        self.assertEqual((expected_ref, commit), result)
        self.assertEqual(("rev-parse", "--verify", expected_ref + "^{commit}"), git.call_args.args)
        if offline:
            command.assert_not_called()
        else:
            command.assert_called_once_with(["git", "fetch", expected_ref.split("/")[0], "--prune"])

    def test_personal_fork_fetches_team_upstream_by_default(self):
        self.assert_baseline("origin\nupstream\n", "upstream/main")

    def test_direct_team_clone_uses_origin_when_no_upstream_exists(self):
        self.assert_baseline("origin\n", "origin/main")

    def test_offline_mode_uses_existing_reference_without_network(self):
        self.assert_baseline("origin\nupstream\n", "upstream/main", offline=True)

    def test_no_remote_requires_explicit_baseline(self):
        with patch.object(simulate, "git", return_value=b""):
            with self.assertRaisesRegex((ValueError, RuntimeError), "(?i)(remote|baseline)"):
                simulate.baseline_revision(None, False)


if __name__ == "__main__":
    unittest.main()
