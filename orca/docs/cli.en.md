中文版: [cli.md](cli.md)

# `orca` command-line reference

`orca` is Orca's command-line client (Java 21, a single jar), for use in terminals, scripts and CI. It only calls the public REST API, and its permissions are the same as on the web.

## Installation

- **Installed with the runtime**: `install-runtime.sh` also installs `orca` into `~/.orca/bin`.
- **Separate download**: `curl -fsSLO https://orca.example.com/downloads/orca-cli.jar`, then run `java -jar orca-cli.jar ...`, or use the `orca` (Linux/macOS) / `orca.cmd` (Windows) launcher scripts under `cli/` in the release package.

## Login

| Method | Command | Notes |
|---|---|---|
| Personal access token (recommended) | `orca login --server URL --token orca_p_...` | Create it in **Settings → API tokens**; can be revoked at any time; the token acts as its creator |
| Password | `orca login --server URL --email EMAIL` | Enter the password interactively, in exchange for a token valid for 90 days |
| Environment variables | `ORCA_SERVER=... ORCA_TOKEN=... orca issues` | CI / scripts; take precedence over the configuration file |

The configuration is saved in `~/.orca/cli.json` (directory 700, file 600, written atomically); use `ORCA_CLI_CONFIG` to point elsewhere. `orca whoami` shows the current identity; `orca logout` revokes the token on the server and deletes the local configuration.

A token does day-to-day work as its creator (issues, questions, tasks, workflows, inbox), but **cannot** change the password, issue or revoke tokens, manage members, connect or remove runtimes, or create or modify projects; these actions require signing in on the web. Changing the password requires entering the current password, and after the change all of your other sessions and tokens become invalid. Do not put personal tokens on shared build machines.

## Commands

| Command | Purpose |
|---|---|
| `orca issues [--project KEY] [--status STATUS]` | List issues |
| `orca issue show KEY` | Issue details and comments |
| `orca issue create --title T [--project KEY] [--description D \| --description-file F] [--priority P] [--label L]... [--assign AGENT]` | Create an issue; it can be assigned directly to an agent (triggers "assign to run") |
| `orca issue comment KEY "text"` | Comment; write `@Agent` to have the agent continue in the same session |
| `orca issue assign KEY AGENT` / `orca issue status KEY STATUS` | Assign / change status |
| `orca agents` / `orca runtimes` | List agents and runtimes |
| `orca ask AGENT "prompt" [--project KEY] [--follow-up TASK] [--no-wait]` | Ask directly; by default streams the agent's messages and commands live and returns when it finishes; `--project` makes the agent answer in a fresh checkout of that project's repository (otherwise in an empty temporary directory); `--follow-up` asks a follow-up in the previous task's session and directory (this also works after the previous task failed, timed out or was cancelled, to continue from where it stopped) |
| `orca task show ID` / `follow ID` / `cancel ID` / `steer ID "text"` | View, follow or cancel a task, or send it a message while it is running |
| `orca workflows` / `orca runs [--limit N]` | Workflows and run records |
| `orca run WORKFLOW [--input key=value]... [--wait]` | Run a workflow manually by name or id; `--wait` waits until it finishes, exit code 0 on success |
| `orca inbox [--all]` | Inbox (by default only pending items) |
| `orca approve ID [--comment C]` / `orca reject ID [--comment C]` | Handle approvals (including the agent's command / file approval requests: approve means "Allow once"; for "Allow for this task", choose it in the Inbox on the web) |
| `orca answer ID key=value...` | Answer an agent's question or a workflow form |
| `orca version` | Client version |

All commands support `--json`, which prints the raw JSON for processing with `jq`. Exit codes: `0` success, `1` failure (including task/run failures), `2` usage error.

## Examples

```bash
# Have Codex fix an issue (assigning triggers the "Assigned issue → agent" workflow), then continue in the comments
orca issue create --project WMS --title "Reject putaway into blocked bins" --assign Codex
orca issue comment WMS-5 "@Codex also cover bins that are blocked for cycle counting"

# Ask directly and watch the output live; then ask a follow-up in the same session
orca ask Codex --project WMS "Where is FIFO enforced for line-side picks?"
orca ask Codex "Add a test for that" --follow-up task_...

# CI: run the health check every night and turn the pipeline red if it fails
ORCA_SERVER=https://orca.example.com ORCA_TOKEN=$ORCA_TOKEN orca run "Nightly WMS health check" --wait

# Process approvals in the inbox in bulk
orca inbox --json | jq -r '.[] | select(.kind=="approval") | .id' | xargs -n1 orca approve
```
