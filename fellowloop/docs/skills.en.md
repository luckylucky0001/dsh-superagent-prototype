# Skills guide

中文：[skills.md](skills.md)

A skill is a reusable piece of Markdown instructions: your team's commit rules, the conventions of a language, the rules of a business domain. Give it to an agent and it is added to that agent's instructions on **every task**, on Codex and TRAE CLI alike — the team writes a rule once and every agent follows it.

Nothing is installed on the runtime machines: skills live on the Fellowloop server and travel with each task.

## 1. Start with the built-in skills

Fellowloop ships nine skills, imported on first start into an empty library:

| Skill | What it covers |
|---|---|
| `git-commits` | Small, well-described commits on the issue branch; no secrets |
| `testing-discipline` | Prove changes with tests; never weaken the suite |
| `code-review-checklist` | What a reviewer checks before approving |
| `security-basics` | Baseline secure-coding rules for every change |
| `java-spring-conventions` | Java 17+/21 and Spring Boot services |
| `python-conventions` | Python services and libraries |
| `typescript-react-conventions` | TypeScript and React front ends |
| `wms-conventions` | Warehouse rules: bin codes, whole quantities, quality holds, FIFO |
| `automotive-logistics` | Domain notes for automotive inbound and line-side logistics |

To bring back a built-in skill you deleted: **Settings → Skills → Library**, then **Add** (or **Add all missing**). A skill with the same name is never overwritten, so your team's edits are kept.

## 2. Write your own skill

1. Open **Settings → Skills → New skill**.
2. Fill in:
   - **Name**: lower-case letters, digits and hyphens are recommended, e.g. `release-checklist`. Names are case-insensitive and unique.
   - **Description**: one line on what it is for; shown in the list and when choosing skills for an agent.
   - **Instructions (Markdown)**: what the agent reads, up to 50,000 characters.
3. Save.

Tips:

- **Short and concrete.** It is sent with every task and takes up context. Write "must / never", not background essays.
- **Checkable rules.** "Quantities are whole pieces; stock never goes negative" rather than "take care with data".
- **One topic per skill.** Commit rules, test discipline and domain rules as separate skills that agents combine.
- **No secrets, passwords or internal addresses.** Skill text is sent to the model.

Template to copy:

```markdown
# Release checklist

- Work only on the `fellowloop/<issue-key>` branch; never change `main` directly.
- When a public interface changes, update `docs/api.md` and `CHANGELOG.md` in the same commit.
- Run `./gradlew test` before committing; commit only when it passes.
- Every new setting needs a default and an example in `deploy/.env.example`.
- Ask in the issue when a requirement is unclear; do not guess.
```

## 3. Give skills to an agent

1. Open **Agents** and create or edit an agent.
2. Choose one or more skills in **Skills**.
3. Save.

It applies from the next task. When a task is dispatched, the skills are appended to the agent's instructions in the order chosen:

```
<the agent's instructions>

## Skill: git-commits

<skill text>

## Skill: wms-conventions

<skill text>
```

Codex receives this as developer instructions, TRAE as part of the system prompt.

## 4. When changes take effect

- After an edit, every **new session** (a new issue, a reassignment, a new Ask) uses the new text at once.
- Comment follow-ups and `--follow-up` questions **continue an existing session** and keep the instructions it started with. To apply a change to an issue right away, reassign it or open a new issue.
- When a skill is deleted, agents that used it stop receiving it from their next task (the skills list shows which agents use each skill).

## 5. Who can change skills

- Members signed in to the web app can view, create, edit and delete skills and choose them for agents.
- **Personal access tokens (CLI, scripts, CI) can read skills but not change them.** Skills decide what agents are told, so changes need a person signed in to the web app; a leaked token cannot alter agents' instructions.
- Changes to which skills an agent uses are recorded in the audit log (Settings → Audit log).

## 6. Skills on the command line

```bash
fellowloop skills                 # skills, and which agents use them
fellowloop skills --library       # the built-in library, and what is already added
fellowloop skill show git-commits # the full text of one skill
fellowloop skills --json          # machine-readable output
```

The CLI is read-only; create, edit and delete skills in the web app under **Settings → Skills**. The REST API offers `GET /api/skills` and `GET /api/skills/library` (with a web session or a token).
