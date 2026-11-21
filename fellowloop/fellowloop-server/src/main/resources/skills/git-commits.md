---
name: git-commits
description: Small, well-described commits on the issue branch
---
# Commits

- Work on the branch you were given (`fellowloop/<issue-key>`); never commit to `main` or rewrite history.
- One logical change per commit. Run the tests before committing.
- Subject line: imperative mood, at most 72 characters, no trailing period
  (e.g. `Skip quality-hold bins during putaway`). Add a body when the reason is not obvious.
- Never commit secrets, credentials, `.env` files, build output or dependency folders.
- Do not push unless the issue asks you to; pushing is done by the team's workflow.
