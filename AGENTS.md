# Battle Cats development workflow

- Before editing, read [CONTRIBUTING.md](CONTRIBUTING.md). Its Java style,
  readability, validation and review conventions apply to agent contributions
  as well as human contributions. Keep shared coding conventions there rather
  than maintaining separate copies for different agents.
- The public team repository is
  `https://github.com/Victor-Ramirez-Garcia/CSS554-BattleCats`. Its latest fetched
  `main` is the reference for development and comparisons.
- Develop and test agent changes on local `codex/` branches. Push working
  branches only to the contributor's personal fork. Inspect `git remote -v`
  before pushing: the recommended names are `origin` for the personal fork and
  `upstream` for the team repository, but names alone do not establish ownership.
  Do not create working branches in the public team repository; keep that
  remote fetch-only, even when a checkout calls it `origin`.
- Fetch the identified team remote (normally `upstream`) before starting a
  change and before the final comparison. Start from its current `main`, or
  update an existing branch while preserving local work. Review the proposed
  diff against that main revision.
- Record the exact baseline and candidate identities for behavioral comparisons.
  `gradlew sim` supports exploratory testing of saved local Java files, recording
  a source snapshot hash without requiring a commit. For a final PR comparison,
  commit candidate changes and use `gradlew sim -PsimCandidate=HEAD` so both
  sides resolve to exact commits. Label older results as historical when main
  changes.

## Review expectations

- Use a separate reviewer before proposing a PR. Challenge scope and strategy
  assumptions as well as correctness; the implementing agent remains responsible.
- Explain each policy constant and distinguish game rules, test controls, and
  experimental choices. Test controls should not become production strategy.
- Evaluate collector changes independently of king strategy where possible.
  Describe measured benefits and limitations against the recorded baseline.
- Choose validation appropriate to the change as described in CONTRIBUTING.md.
  Documentation-only changes need accurate links, commands and a reviewed diff;
  they do not need game simulations. Do not claim unrun tests or simulations.
