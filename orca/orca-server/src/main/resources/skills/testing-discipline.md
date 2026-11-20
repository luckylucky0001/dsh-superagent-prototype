---
name: testing-discipline
description: Prove changes with tests; never weaken the suite
---
# Testing

- Find out how the project runs its tests (README, build files, CI config) and run them before you finish.
- Every bug fix gets a regression test that fails without the fix.
- Every new rule in the issue gets at least one test, including edge cases: empty input, zero, limits,
  invalid values, concurrency where state is shared.
- Never delete, skip or loosen an existing test to make the suite pass. If a test is wrong, say why in your summary.
- Report what you ran and the result (e.g. "12 tests passed with `python3 -m unittest`").
