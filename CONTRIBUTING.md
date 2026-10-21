# Contributing to Battle Cats

These conventions apply to everyone contributing code, whether written by hand
or with an agent. Contributors are responsible for understanding, reviewing and
validating the changes they submit.

## Shared instructions

- This file is the shared coding and contribution guide.
- [AGENTS.md](AGENTS.md) adds the agent development and review workflow.
- [CLAUDE.md](CLAUDE.md) imports both files for Claude Code. Keep rules in their
  shared files instead of copying them into each agent's entry point.
- [README.md](README.md) covers setup, available commands and simulation output.

## Java style

Use [Google Java Style](https://google.github.io/styleguide/javaguide.html) for
new and changed team Java code. In particular:

- Use two-space indentation, braces for control-flow bodies and consistent
  spacing. Wrap lines at 100 columns, subject to the guide's exceptions.
- Use `UpperCamelCase` for classes, `lowerCamelCase` for methods and variables,
  and `UPPER_SNAKE_CASE` for constants that meet the guide's constant definition.
- Use explicit imports rather than wildcard imports.
- Give class members a logical order that a teammate can follow.

Limit formatting changes to the code being worked on. Put broader formatting
cleanup in its own commit or PR so reviewers can distinguish it from behavior
changes and teammates can avoid unnecessary merge conflicts.

The repository does not yet configure an automatic formatter or a formatting
CI check. `spotlessApply` and `spotlessCheck` are not currently available tasks.
Adding a pinned formatter and a GitHub check can be proposed separately; until
then, review these conventions manually. Formatting does not establish that
the strategy or implementation is correct.

## Readable bot code

- Use names that explain a value's role and units. Keep methods focused on a
  coherent responsibility; do not split them just to satisfy a line-count rule.
- Keep unit dispatch, Baby Rat behavior, Rat King behavior and navigation in
  their respective components. Avoid adding wrappers or layers without a
  concrete reuse or testing need.
- Explain why a strategy choice exists. For each new threshold or policy
  constant, distinguish an engine rule from a test control or an experimental
  choice, and describe the evidence or tradeoff behind the chosen value.
- Write comments about constraints, state transitions and non-obvious decisions.
  Keep them accurate when behavior changes.
- Make state ownership clear. Pass inputs and dependencies explicitly where
  useful, and keep code testable without introducing unnecessary allocations or
  abstractions into turn execution.
- Measure bytecode costs before optimizing. Document performance-driven
  departures from a clearer implementation and retain the comparison evidence.

## Branches and review

Develop from the latest fetched team main. In the recommended fork setup,
`upstream` is the public team repository and `origin` is your personal fork.
Check the remote URLs before pushing; working branches belong on your own fork,
regardless of what the remotes are named. Agents use local `codex/` branches;
human contributors can use descriptive feature branch names.

Keep commits focused and explain the reason for each change. Separate formatting
and unrelated cleanup from strategy changes. For agent contributions, have a
separate reviewer inspect scope, readability, correctness and any reported
results before proposing a PR. Human contributors can open a PR to seek teammate
review before merge. An agent's output or generated simulation report does not
replace that review.

## Validation

Use JDK 21 and the checked-in Gradle wrapper. On Windows, the existing bot checks
are:

```powershell
.\gradlew.bat test verify
```

For simulation-tool changes, run the Python 3.10+ tooling tests and an engine
smoke test when relevant:

```powershell
python -m unittest discover -s test_tools
```

For bot behavior changes, test the relevant behavior and compare the committed
candidate against current team main. A final seven-map comparison is:

```powershell
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=HEAD
```

Record exact baseline and candidate identities, maps, starting sides, completion
status, benefits and regressions. Keep unrelated strategy changes out of the
comparison where possible. State coverage limits and label results historical
when team main advances. See README.md for map selection and exploratory tests.

For documentation-only changes, check links, command accuracy and the diff;
compilation and game simulations are unnecessary. State which checks actually
ran, and disclose limitations rather than claiming unmeasured improvements.

On macOS/Linux, use `./gradlew` instead of `.\gradlew.bat`.
