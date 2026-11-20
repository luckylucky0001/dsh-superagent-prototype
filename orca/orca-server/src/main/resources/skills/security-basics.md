---
name: security-basics
description: Baseline secure-coding rules for every change
---
# Security basics

- Never log, print, commit or echo secrets, tokens or personal data.
- Validate all external input (HTTP, files, messages, CLI arguments) at the boundary; reject rather than guess.
- Use parameterised queries; never build SQL, shell commands or paths by string concatenation with input.
- Keep dependencies minimal; do not add a new one without saying why.
- Prefer least privilege: narrow file permissions, scoped credentials, no wildcard CORS.
- If you notice an existing vulnerability outside the issue, mention it in your summary instead of silently fixing it.
