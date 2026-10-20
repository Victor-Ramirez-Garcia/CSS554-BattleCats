# Battle Cats development workflow

- The public team repository is `upstream`; its latest fetched `main` is the
  reference for development and comparisons.
- Develop and test on local `codex/` branches. Push working branches only to
  `origin`, the user's personal repository. Do not create working branches in
  the public team repository; keep `upstream` fetch-only.
- Fetch `upstream` before starting a change and before the final comparison.
  Start from its current `main`, or update an existing branch while preserving
  local work. Review the proposed diff against that main revision.
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
