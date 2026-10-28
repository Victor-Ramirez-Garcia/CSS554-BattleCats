# Tests and coverage

Use JDK 21 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat clean test jacocoTestReport jacocoTestCoverageVerification verify
```

On macOS/Linux use `./gradlew`. The first run downloads dependencies; later
runs can reuse the cache. Use `clean` for a recorded coverage result, especially
after changing branches, so obsolete compiled classes do not enter the report.

`test` runs JUnit tests and generates a JaCoCo report. The explicit report task
makes that output visible in the command; Gradle runs it only once. The coverage
gate and report both include **every compiled class in `battlecats`**, including
the unexecuted entry-point loop. Examples, engine dependencies and test code are
outside the submitted team's denominator.

The course requires line coverage strictly above 20%. The build requires at
least **21%** across the complete package. This is a test control, not a strategy
setting. Branch coverage is reported without a percentage gate. JaCoCo is pinned
to 0.8.12 for Java 21 support and reproducibility.

| Report | Default path |
| --- | --- |
| Test results | `build/reports/tests/test/index.html` |
| Coverage HTML | `build/reports/jacoco/test/html/index.html` |
| Coverage counters | `build/reports/jacoco/test/jacocoTestReport.xml` |

If using `-PlocalBuildDir=...`, substitute that build directory. These reports
are generated files; retain them for the demo rather than committing them.
`check` runs tests, coverage and the gate. `verify` separately checks the engine
compatibility of the compiled bot. Neither replaces match comparisons.

Tests use pure inputs and fake controllers to exercise collection, delivery,
king accounting, sighting freshness, action legality, combat, construction and
navigation. Cover the failure paths and threshold boundaries when changing a
policy. A regression test should fail under the old behavior and pass under the
fix. Keep tests deterministic and avoid repeating a production calculation as
the expected answer.

After committing a behavior change, compare it against freshly fetched team
main on both starting sides:

```powershell
.\gradlew.bat sim -PsimPreset=full -PsimCandidate=HEAD
```

The existing simulator needs Python 3.10+; use `-PsimPython=C:\path\to\python.exe`
if discovery fails. It records exact source identities, maps, seeds and results
under a new `matches/` directory. Failed games do not count as valid results.
Engine matches run separately and do not contribute to JUnit coverage. See
[the teammate guide](SPRINT_2.md) and [validation](SPRINT_2_RESULTS.md).
