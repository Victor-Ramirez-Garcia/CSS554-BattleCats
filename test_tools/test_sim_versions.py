"""Read-only version discovery and interactive choices in temporary Git repos."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import sim_versions


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "--quiet", "-b", "main")
        self.git("config", "user.email", "simulator-tests@example.invalid")
        self.git("config", "user.name", "Simulator Tests")
        self.source = self.root / "bot.txt"
        self.source.write_text("first version", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "First: gather | deliver")
        self.first = self.git("rev-parse", "HEAD").strip()
        self.git("tag", "iteration/one")
        self.source.write_text("second version", encoding="utf-8")
        self.git("commit", "--quiet", "-a", "-m", "Second version", "-m", "A longer body is not the subject.")
        self.second = self.git("rev-parse", "HEAD").strip()
        self.git("branch", "codex/collector-test")
        self.git("tag", "-a", "iteration/two", "-m", "Annotated tag message")
        self.git("update-ref", "refs/remotes/upstream/main", self.first)
        self.git("symbolic-ref", "refs/remotes/upstream/HEAD", "refs/remotes/upstream/main")

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.root, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, encoding="utf-8").stdout

    def test_catalog_distinguishes_branch_tag_remote_and_exact_commit(self):
        entries = sim_versions.version_catalog(self.root)
        by_ref = {entry["ref"]: entry for entry in entries}

        self.assertEqual(len(entries), len(by_ref))
        self.assertEqual("local branch", by_ref["refs/heads/codex/collector-test"]["kind"])
        self.assertEqual("remote branch", by_ref["refs/remotes/upstream/main"]["kind"])
        self.assertEqual(self.first, by_ref["refs/remotes/upstream/main"]["commit"])
        self.assertEqual(self.second, by_ref["refs/tags/iteration/two"]["commit"])
        self.assertEqual("Second version", by_ref["refs/tags/iteration/two"]["subject"])
        self.assertEqual("tag", by_ref["refs/tags/iteration/one"]["kind"])
        self.assertEqual("commit", by_ref[self.first]["kind"])
        self.assertEqual("First: gather | deliver", by_ref[self.first]["subject"])
        self.assertRegex(by_ref[self.first]["date"], r"^\d{4}-\d{2}-\d{2}$")
        self.assertNotIn("refs/remotes/upstream/HEAD", by_ref)

    def test_catalog_is_stable_and_does_not_modify_checkout(self):
        self.source.write_text("unsaved to Git, saved to disk", encoding="utf-8")
        before = self.git("status", "--porcelain=v1")

        first = sim_versions.version_catalog(self.root)
        second = sim_versions.version_catalog(self.root)

        self.assertEqual(first, second)
        self.assertEqual(before, self.git("status", "--porcelain=v1"))
        self.assertEqual("main", self.git("branch", "--show-current").strip())
        self.assertEqual("unsaved to Git, saved to disk", self.source.read_text(encoding="utf-8"))

    def test_recent_commit_limit_and_refs_only(self):
        limited = sim_versions.version_catalog(self.root, limit=1)
        self.assertEqual(1, sum(entry["kind"] == "commit" for entry in limited))
        self.assertEqual(self.second, next(entry["ref"] for entry in limited if entry["kind"] == "commit"))
        self.assertFalse(any(entry["kind"] == "commit" for entry in sim_versions.version_catalog(self.root, limit=0)))
        with self.assertRaisesRegex(ValueError, "zero or greater"):
            sim_versions.version_catalog(self.root, limit=-1)

    def test_noncommit_tags_and_unreadable_refs_are_skipped(self):
        blob = self.git("rev-parse", "HEAD:bot.txt").strip()
        self.git("tag", "blob-only", blob)
        missing = self.root / ".git" / "refs" / "heads" / "missing-object"
        missing.write_text("f" * 40 + "\n", encoding="utf-8")

        refs = {entry["ref"] for entry in sim_versions.version_catalog(self.root)}

        self.assertNotIn("refs/tags/blob-only", refs)
        self.assertNotIn("refs/heads/missing-object", refs)
        self.assertIn("refs/heads/main", refs)

    def test_detached_head_is_a_recent_commit(self):
        self.git("checkout", "--quiet", "--detach", self.first)
        self.source.write_text("detached version", encoding="utf-8")
        self.git("commit", "--quiet", "-a", "-m", "Detached experiment")
        detached = self.git("rev-parse", "HEAD").strip()

        entries = sim_versions.version_catalog(self.root)

        self.assertIn(detached, [entry["ref"] for entry in entries if entry["kind"] == "commit"])


class ChoiceTests(unittest.TestCase):
    def setUp(self):
        self.entries = [
            {"ref": "refs/heads/codex/collector", "commit": "a" * 40,
             "date": "2026-10-18", "subject": "Collect cheese", "kind": "local branch"},
            {"ref": "refs/remotes/upstream/main", "commit": "b" * 40,
             "date": "2026-10-17", "subject": "Team main", "kind": "remote branch"},
            {"ref": "c" * 40, "commit": "c" * 40, "date": "2026-10-16",
             "subject": "Earlier iteration", "kind": "commit"},
        ]
        self.output = []
        self.prompts = []

    def choose(self, answers, default="upstream/main"):
        replies = iter(answers)

        def input_fn(prompt):
            self.prompts.append(prompt)
            answer = next(replies)
            if isinstance(answer, BaseException):
                raise answer
            return answer

        return sim_versions.choose_versions(self.entries, default, input_fn, self.output.append)

    def test_blank_accepts_worktree_and_remote_main(self):
        self.assertEqual((None, "refs/remotes/upstream/main"), self.choose(["", ""]))
        self.assertIn("0: WORKTREE", self.prompts[0])
        self.assertIn("2: upstream/main", self.prompts[1])

    def test_numeric_choices_return_unambiguous_refs(self):
        self.assertEqual(("refs/heads/codex/collector", "c" * 40), self.choose(["1", "3"]))

    def test_invalid_numbers_and_text_are_reprompted(self):
        self.assertEqual(("c" * 40, "refs/remotes/upstream/main"),
                         self.choose(["banana", "-1", "4", "3", "0", "",]))
        self.assertEqual(6, len(self.prompts))
        self.assertTrue(any("number from 0 to 3" in line for line in self.output))
        self.assertTrue(any("number from 1 to 3" in line for line in self.output))

    def test_missing_default_requires_explicit_baseline(self):
        self.assertEqual((None, "refs/heads/codex/collector"), self.choose(["", "", "1"], "missing/main"))
        self.assertTrue(any("unavailable" in line for line in self.output))

    def test_origin_main_can_be_default(self):
        self.entries[1]["ref"] = "refs/remotes/origin/main"
        self.assertEqual((None, "refs/remotes/origin/main"), self.choose(["", ""], "origin/main"))

    def test_full_ref_default_disambiguates_local_and_remote_names(self):
        self.entries[0]["ref"] = "refs/heads/upstream/main"
        self.assertEqual((None, "refs/remotes/upstream/main"), self.choose(["", ""]))
        self.assertEqual((None, "refs/heads/upstream/main"), self.choose(["", ""], "refs/heads/upstream/main"))

    def test_eof_and_interrupt_cancel_without_accepting_defaults(self):
        for answer in (EOFError(), KeyboardInterrupt()):
            with self.subTest(answer=type(answer).__name__):
                with self.assertRaisesRegex(ValueError, "cancelled; no simulation"):
                    self.choose([answer])
                with self.assertRaisesRegex(ValueError, "cancelled; no simulation"):
                    self.choose(["", answer])

    def test_empty_catalog_cannot_offer_a_baseline(self):
        with self.assertRaisesRegex(ValueError, "No committed baseline"):
            sim_versions.choose_versions([], "upstream/main")

    def test_printed_choices_identify_kind_revision_date_and_subject(self):
        self.entries[0]["subject"] = "Hello\x1b\n fake choice"
        sim_versions.print_versions(self.entries, self.output.append)

        self.assertIn("[local branch] codex/collector", self.output[0])
        self.assertIn("aaaaaaaaaaaa | 2026-10-18 | Hello fake choice", self.output[0])
        self.assertNotIn("\x1b", self.output[0])
        self.assertEqual(3, len(self.output))


if __name__ == "__main__":
    unittest.main()
