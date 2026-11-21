中文版: [operations.md](operations.md)

# Orca Operations Guide

For the operations colleagues who deploy and maintain Orca: where the data is, how to back it up and restore it, how to upgrade, how to manage tokens, and what to check first when something goes wrong.

## 1. Where the data is

| Data | Location | In Docker Compose | Needs backup |
|---|---|---|---|
| Business data (issues, comments, agents, workflows, run records, inbox, token hashes) | PostgreSQL (`ORCA_DB_URL`) | Volume `orca-db` | **Yes** |
| Uploaded attachments (screenshots, etc.) | `ORCA_STORAGE_DIR` (default `~/.orca/server/files`) | Volume `orca-files` (`/var/lib/orca/files`) | **Yes**, from the same point in time as the database |
| Daemon configuration and runtime token | `~/.orca/daemon.json` on the runtime machine (or `ORCA_HOME`), permissions 600 | — | No: if lost, just register again |
| Agent working directories | `~/.orca/work` on the runtime machine: `issues/<KEY>` (kept), `runs/`, `adhoc/` (cleaned up automatically after 7 days unused) | — | No: the results are on Git branches; branches that have not been pushed exist only on this machine |
| Command-line configuration | `~/.orca/cli.json` on the user's computer (directory 700, file 600) | — | No |

The server itself is stateless, so its container can be rebuilt at any time; as long as the database and the attachment directory are intact, no data is lost.

## 2. Backup and restore

**Backup** (daily recommended, keep at least 14 days):

```bash
# Database (custom format, allows selective restore)
docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U orca -Fc orca > orca-$(date +%F).dump
# Attachments
docker run --rm -v deploy_orca-files:/data -v "$PWD":/backup alpine tar czf /backup/orca-files-$(date +%F).tgz -C /data .
```

The volume name prefix depends on the Compose project name (by default the directory name `deploy`, giving `deploy_orca-files`); check it with `docker volume ls`. For a managed database (such as RDS), use its snapshot feature directly and back up the attachment directory separately.

**Restore**:

```bash
docker compose -f deploy/docker-compose.yml stop server
docker compose -f deploy/docker-compose.yml exec -T postgres pg_restore -U orca -d orca --clean --if-exists < orca-2026-11-20.dump
docker run --rm -v deploy_orca-files:/data -v "$PWD":/backup alpine sh -c "rm -rf /data/* && tar xzf /backup/orca-files-2026-11-20.tgz -C /data"
docker compose -f deploy/docker-compose.yml start server
```

After a restore, changes made after the backup was taken are lost; runtimes, agents and tokens return to their state at backup time. Tasks that were running at backup time and can no longer continue are marked as failed (`RUNTIME_LOST`) once the daemon reconnects and reconciles; they can be re-run from the UI.

## 3. Upgrade

1. **Back up first** (see above). Database migrations (Flyway) only run forward; rolling back to an earlier version requires restoring the pre-upgrade backup.
2. **Upgrade the server first**: replace `orca-server.jar` or rebuild the image (`docker compose ... up -d --build`). The database is migrated automatically at startup; the upgrade is complete when `curl https://orca.example.com/api/health` returns the new version number. Restarting the server does not interrupt running tasks: daemons reconnect automatically, on reconnect the server immediately renews the leases on those tasks, and events and results produced in the meantime are re-sent after reconnecting (tested: the server was restarted while a task was running, and the task completed normally).
3. **Then upgrade each daemon**: re-run the one-line install command (it overwrites the jar under `~/.orca/bin` and keeps the registration), or replace `orca-daemon.jar` and restart the service (`systemctl --user restart orca-daemon`). Restarting a daemon makes the tasks it is running fail (they can be re-run), so it is best to upgrade when the machine is idle (the Runtimes page shows the current number of tasks).
4. Run `orca-daemon doctor` on each machine: it shows the server version and, if the daemon is newer than the server, tells you to upgrade the server first.

The protocol is backward compatible: an old daemon can connect to a new server, but cannot use features that need a newer daemon (for example, daemons below 0.2.0 cannot run review tasks and daemons below 0.3.0 cannot push branches; the server does not dispatch such tasks to old daemons).

## 4. Tokens and access

| Action | How |
|---|---|
| Someone leaves | Remove them in Settings → Members; their sessions and personal access tokens become invalid with it |
| A personal access token may have leaked | The owner deletes it in Settings → API tokens, or changes their password (after the change, all of their other sessions and tokens become invalid). A personal token cannot change the password, issue tokens, manage members, runtimes or projects, or change agents, skills or workflow definitions (it can still ask agents, start workflow runs and answer the inbox), so even a leaked token cannot be used to take over the account or widen what agents may do. In the Audit log, audited actions performed with a token carry the `via: token` tag and can be filtered by actor (day-to-day actions such as issues, comments and questions are not recorded in the Audit log) |
| An admin resets someone else's password | Change it in Settings → Members; all of that member's sessions and tokens become invalid |
| Retire a runtime machine | Delete the runtime on the Runtimes page: the runtime token is revoked immediately, and the daemon reports that it needs to register again |
| Replace a runtime machine | Connect the new machine with a new registration code, then point the agent to the new runtime in the agent's settings |
| Sign out of the command line | `orca logout`: revokes the token on the server and deletes the local configuration |

Only SHA-256 hashes of tokens are stored in the database; registration codes are valid for 1 hour and can be used only once.

Who may do what:

- Registration codes, runtime probes and deleting runtimes: owners and admins.
- Widening an agent (the `full` tier, allowed commands, extra CLI arguments, network access): owners and admins. Members can create and edit agents within those limits.
- Changing roles: members cannot change an owner, and only the owner changes an admin.
- An Ask is the asker's conversation: only they continue it; only they or an owner/admin steer or cancel it. Issue and workflow tasks belong to the team.
- Browser writes must come from Orca's own origin (`Origin` equal to the Host header or to `ORCA_PUBLIC_URL`). Behind a proxy, keep the Host header (`proxy_set_header Host $host`) or set `ORCA_PUBLIC_URL` to the address people open.
- Webhooks: the secret goes in `X-Orca-Secret` (preferred over `?secret=`, which ends up in proxy logs). A redelivery with the same `Idempotency-Key`, `X-GitHub-Delivery` or `X-Gitlab-Event-UUID` returns the run it started the first time.

## 5. Runtime machines

```bash
systemctl --user status orca-daemon        # Service status
journalctl --user -u orca-daemon -f        # Live logs
systemctl --user restart orca-daemon       # Restart (running tasks are marked RUNTIME_LOST when the daemon reconnects and can be re-run; questions can be continued with a follow-up in the original session)
sudo loginctl enable-linger $USER          # Keep running after logout
~/.orca/bin/orca-daemon doctor             # Item-by-item self-check, with fix suggestions
```

- **Concurrency**: `--capacity N` (default 2). Each task is a separate Codex / TRAE process; set it according to the machine's CPU and memory.
- **Disk**: each issue gets one repository checkout, kept long-term; directories in `runs/` and `adhoc/` unused for 7 days are cleaned up automatically. Reserve enough space for large repositories; `doctor` warns when free space falls below 2 GB.
- **Network**: the agent sandbox has no network access by default; when dependencies need to be downloaded, start the daemon with `--allow-network` and turn on "Network in the sandbox" on the agent.
- **Full access**: the `full` tier only takes effect on machines started with `--isolated`, and should only be used on disposable VMs or containers.
- **Credentials**: agents use the Codex / TRAE accounts and git credentials already logged in on this machine; pushing branches also uses this machine's git credentials. Use dedicated accounts for build machines; do not put personal tokens on them.
- **Command allowlist**: approved commands run outside the sandbox with the daemon user's permissions. Entries are prefixes (any arguments may follow), so make them specific to a task (`./gradlew test`, `npm test`); do not add entry points that can run arbitrary code, such as `./gradlew *`, `bash` or `python3`. A `git push` entry only allows pushing `orca/*` branches to `origin`. For pushing, the `repo.push` node in a workflow is preferred; the daemon performs the push with a fixed refspec.

### Logging in to TRAE Enterprise on a machine without a browser

`traecli login` opens a browser on the local machine and waits for a callback to `127.0.0.1:<port>`. Build machines usually have no browser; you can complete the login as follows:

1. On the build machine, run `BROWSER=echo traecli login`: instead of opening a browser, it prints the login URL and keeps waiting for the callback.
2. Open that URL in the browser on your own computer and complete SSO with your enterprise account.
3. At the end, the browser is redirected to `http://127.0.0.1:<port>/authorize?...` and shows that the page cannot be reached (because that port is on the build machine). Copy this full address and paste it at traecli's prompt "Paste callback URL to verify automatically" on the build machine (or run `curl "<that address>"` in another terminal). The login is then complete, and `traecli login status` shows that you are logged in.

**Upgrading from an earlier version**: daemons before 1.0, when "Allow for this task" was chosen, had TRAE save a "don't ask again" rule and marked each task directory as trusted. `orca-daemon doctor` checks for saved rules that point to working directories and reports them; please remove these leftovers on every runtime machine: the `prefix_rule` entries in `~/.trae/cli/rules/default.rules` that point to Orca working directories, and the `[projects."…"]` entries for `~/.orca/work/...` in `~/.trae/traecli.toml`. TRAE and Codex also record directories they have run tasks in as trusted projects themselves (`~/.trae/traecli.toml`, `~/.codex/config.toml`); when cleaning up `~/.orca/work`, you can delete the corresponding entries at the same time.

Note: this callback address contains a login token. Use it only on the build machine; do not post it in chat groups or tickets. Alternatively, use a personal access token: `TRAECLI_PERSONAL_ACCESS_TOKEN=... traecli login --with-trae-pat`.

## 6. Agent configuration recommendations (Codex and TRAE)

| Item | Recommendation | Reason |
|---|---|---|
| Permission tier | `workspace` for implementers, `read_only` for reviewers; `full` only for disposable machines started with `--isolated` | Review tasks run read-only even if configured as workspace; `full` does not go through the sandbox |
| Command allowlist | Make entries specific to a task: `python3 -m unittest`, `npm test`, `./gradlew test`; do not add `bash`, `python3`, `./gradlew *` | Entries are prefixes; with arguments they can load arbitrary scripts, and they run outside the sandbox |
| Pushing | Use the workflow's `repo.push` node; if the agent really must push, add `git push` to the allowlist (it can only push `orca/*` to `origin`) | The daemon pushes with a fixed refspec and does not overwrite the main branch |
| Ask a person when blocked | Turn it on only for agents that really do need to go beyond their limits occasionally; in the Inbox, prefer "Allow once" | "Allow for this task" only applies to exactly the same command and directory |
| Network access in the sandbox | When dependencies must be downloaded: turn on "Network in the sandbox" on the agent and start the daemon with `--allow-network` | It only takes effect when both are on; network is off by default |
| Codex model | Use the same model for implementation and review (the review model is automatically set to the agent's model); start with `effort` at `medium` | Native review (`review/start`) does not report token usage |
| TRAE model | Doubao-Seed-Code (id `Doubao_1_6`) for implementation, GLM-5.3-Flash for review; reasoning effort is only low / high / xhigh | You can write either the display name or the id; an unsupported effort level shows a notice and falls back |
| Review | Cross-review (TRAE implements and Codex reviews, or the other way round) with the "implement ⇄ review loop" template, `maxRounds` 3 | If the review still finds problems, a list of the problems is published; it does not loop forever |
| Instructions and skills | Put them in the agent instructions or the skill library; TRAE receives them through the system prompt, Codex through developer instructions | Both take effect at the start of the session and are kept when the session is resumed |

## 7. Troubleshooting

| Symptom | Check first | Fix |
|---|---|---|
| Runtime shows as offline | `orca-daemon doctor` on the machine | Server unreachable: check that the proxy / firewall allows outbound HTTPS and WebSocket; token rejected: register again |
| Runtime is online but tasks stay queued | CLI status on the Runtimes page | The CLI is not logged in (`codex login --device-auth`), the agent points to a different runtime, or capacity is full |
| Task fails: not logged in / 401 | The task transcript and `doctor` | Log in to Codex / TRAE again on that machine |
| Task stuck at "awaiting approval" | Inbox | The agent has "Ask a person when blocked" turned on; someone needs to approve or reject; the timeout clock is paused while waiting |
| Installing dependencies fails (DNS / network errors) | Whether the agent has "Network in the sandbox" turned on, and whether the daemon was started with `--allow-network` | Turn both on and retry |
| Pushing a branch fails | Output of the `repo.push` node in the run record | That machine's git credentials do not have permission to push; only `orca/*` branches may be pushed |
| Live updates do not refresh in the browser, or the daemon keeps disconnecting | Reverse proxy configuration | `/api/stream` (SSE) needs buffering turned off, `/api/daemon/ws` needs WebSocket upgrade, and both need a relaxed read timeout; see `deploy/nginx.conf.example` (tested on Nginx 1.24 with TLS, SSE and long-lived daemon connections) |
| Attachments are lost after the container is rebuilt | Whether `ORCA_STORAGE_DIR` is on a persistent volume | Use the 1.0 compose file (the `orca-files` volume is already mounted) |

## 8. Audit

Settings → Audit log (owner / admin only) lists who did what, when and from which IP: successful and failed logins and lockouts; personal token issuance and revocation; adding and removing members, role and password changes; agent creation, archiving and permission changes (permission tier, command allowlist, extra arguments, skills, network access, approval escalation; for instructions only "modified" is recorded, not the content); runtime registration code issuance, registration and removal; approval decisions (including the command that was approved or rejected); creating, editing and deleting workflows, enabling and disabling them, and manual runs; project creation and repository changes (including the old value).

Audit records are append-only and cannot be modified in the UI; `GET /api/audit.csv?from=&to=` (millisecond timestamps, last 90 days by default) exports CSV as a stream in batches, for archiving to a SIEM or compliance storage. The audit table `audit_event` is backed up with the database and kept for 365 days by default (`ORCA_AUDIT_RETENTION_DAYS`, 0 means keep forever), with automatic daily cleanup. Credentials in repository URLs and tokens and passwords in commands are masked before being written; for failed logins, the username that was entered is not used as the actor.

**Source IP**: the server only trusts `X-Forwarded-For` sent by the proxies listed in `ORCA_TRUSTED_PROXIES` (a regular expression matched against the peer address); by default only the local machine (127.x) is trusted, and Compose by default trusts the Docker bridge (172.16/12, i.e. a reverse proxy on the host). When other sources connect directly, the header is ignored, so clients cannot forge the address used for login rate limiting and auditing. The reverse proxy should overwrite the header rather than append to it (`proxy_set_header X-Forwarded-For $remote_addr;` in the reference configuration), and the server port should be open only to the proxy (set `ORCA_HTTP_BIND=127.0.0.1` in Compose). If the proxy is on another machine, add its address to `ORCA_TRUSTED_PROXIES`, for example `10\.0\.0\.5`.

Failed logins are counted per "email + address"; after 10 consecutive failures for the same combination, it is locked for 15 minutes (the lockout itself is recorded as one audit entry). Globally, at most 600 failed logins are recorded per minute; anything beyond that is recorded in the following minute as `auth.audit_throttled`, with the number of omitted entries.

## 9. Monitoring

- `GET /api/health`: no login needed, returns `{"ok":true,"version":"…"}`; suitable for load balancer and availability probes.
- Runtimes page: online status, CLI login status and capacity in use for each machine.
- Usage page: number of runs, success rate and token usage, per agent and per day.
- The Inbox can be pushed to Slack / Teams / Feishu / DingTalk / WeCom (`ORCA_NOTIFY_WEBHOOK_URL`).
