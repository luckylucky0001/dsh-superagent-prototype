---
name: code-review-checklist
description: What a reviewer checks before approving
---
# Reviewing a change

Compare the issue branch with the default branch (`git diff main...HEAD`) and read the touched code in context.

1. Does it do what the issue asks — every acceptance criterion?
2. Correctness: edge cases, error handling, off-by-one, time zones, rounding, null/empty input.
3. Data integrity and concurrency: can shared state be read and written by two callers at once?
4. Tests: do they cover the new behaviour and the edge cases, and do they pass?
5. Security: input validation, injection, secrets, permissions.
6. Compatibility: public APIs, data formats, migrations, supported runtime versions.

Report findings ordered by severity, each with file and line, the concrete failure, and a fix.
Approve only when nothing important is left. Do not change files while reviewing.
