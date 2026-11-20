# Orca web

The browser UI for Orca: issues board, agent transcripts, inbox, agents, runtimes and the workflow
editor. Vite + React 18 + TypeScript (strict) + Ant Design 5 + React Flow (`@xyflow/react`) +
TanStack Query + React Router 6.

## Develop

```bash
cd orca/web
npm install
npm run dev          # http://localhost:5173
```

The dev server proxies `/api` (REST and the `/api/stream` server-sent events) to the Orca server at
`http://localhost:18080`. Point it elsewhere with `ORCA_API=http://host:port npm run dev`.

Sign in with an Orca member account (on a fresh server: the admin from `ORCA_ADMIN_EMAIL` /
`ORCA_ADMIN_PASSWORD`). The session is an HttpOnly `orca_session` cookie set by
`POST /api/auth/login`; every request is same-origin, so no tokens live in the browser.

```bash
npm run typecheck    # tsc, no emit
npm run build        # typecheck + production bundle in web/dist
npm run preview      # serve web/dist on :4173 with the same /api proxy
```

## How the server serves it

`npm run build` writes a static bundle to `web/dist`. Start the server with

```bash
ORCA_WEB_DIR=file:/absolute/path/to/orca/web/dist/ java -jar orca-server.jar
```

(note the trailing slash). The server serves the files and forwards client-side routes such as
`/issues/ORCA-1` to `index.html` (`SpaController`), so deep links work. Every top-level route the
router knows (`app/router.tsx`) must be listed there too.

## Layout

```
src/
  api/          typed client (client.ts), types mirroring Views.java (types.ts),
                query keys and shared hooks (queries.ts)
  app/          router, layout (sider + top bar), auth guard, login, realtime (SSE), i18n
  components/   Transcript, Markdown, StatusTag, AgentAvatar, SchemaForm, CodeBlock/DiffView, …
  features/
    issues/     kanban board (drag to change status), new-issue modal, issue detail + timeline
    tasks/      task card (timeline) and task page with the live transcript
    inbox/      approvals, agent questions, workflow inputs, alerts
    agents/     agent cards, detail with recent tasks, create/edit drawer
    runtimes/   machines, provider health, registration commands
    workflows/  list, editor (palette, canvas, inspector, variable helper), manual-run modal
    runs/       run list and read-only run canvas coloured by node status
    usage/      totals, runs/tokens per day (CSS bars), per-agent table (GET /api/stats)
    settings/   my account, members (owner/admin manage), projects (repo URL → branch orca/<issue-key>)
```

### Realtime

`app/realtime.tsx` opens one `EventSource('/api/stream')` for the signed-in app. Each named event
invalidates the matching TanStack queries (see `handle()`); `task.events` are merged straight into
the transcript cache (`['taskEvents', taskId]`, deduplicated by `seq`). The browser reconnects by
itself; when it gives up the provider retries with back-off, and after any reconnect it refetches
everything, since events may have been missed.

### Transcript

`components/transcript/model.ts` folds the normalised agent events (`AgentEvent.java`) into items:
`message.delta` streams into the message with the same `itemId` and is replaced by the final
`message`; `tool.started` / `tool.output` / `tool.completed` become one card per `itemId`; the turn's
latest `diff` is shown once before the `turn.completed` line; `usage` feeds the footer. The same
component is used in the issue timeline and on `/tasks/:id`.

### i18n

English by default, 中文 via the toggle in the top bar (stored in `localStorage['orca.lang']`).
Strings live in `app/messages.ts`; the Chinese dictionary is typed against the English keys, so a
missing translation fails `npm run typecheck`. Ant Design's locale and dayjs switch too. Times are
shown in the browser's time zone.
