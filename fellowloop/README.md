# Fellowloop

**AI coding agents as teammates.** Assign an issue to an agent and it works on your own machine with **OpenAI Codex** or **TRAE CLI 2.x**: it reads and changes code, runs tests, commits to a branch, reports back on the issue and waits for review. Comment to continue in the same session. Every automation is a composable, visually edited workflow. Self-hosted; English UI with a Chinese toggle; Asia/Singapore by default.

**Ships with:** the server (web UI built in), the runtime daemon with a one-line installer and `fellowloop-daemon doctor`, the `fellowloop` command-line client with personal access tokens, nine built-in agent skills (engineering discipline, language conventions, warehouse and automotive-logistics rules), Docker Compose files and a release package.

📘 **[Technical design & Codex / TRAE walkthrough (PDF, 中文)](docs/Fellowloop-Technical-Design-and-Codex-Walkthrough.pdf)** — architecture, component philosophy, protocols, security model, and a live demo with real Codex and TRAE CLI runs on an automotive parts-warehouse (WMS) codebase: putaway, FIFO line-side picking with a three-round review loop, Codex's built-in reviewer, approval escalation, and a cycle-count feature implemented by TRAE and reviewed by Codex until approved.

**English docs:** [Operations guide](docs/operations.en.md) · [CLI reference](docs/cli.en.md) · [Changelog (中文)](CHANGELOG.md)

---

> 代号 Fellowloop（可替换）。把 AI 编码智能体当作团队成员：Issue 指派给 Agent，Agent 在你自己的机器上用 **OpenAI Codex** 或 **TRAE CLI** 干活，边做边汇报，做完交给人审；所有自动化都是**可编排的工作流**。

- 后端：Java 21 · Spring Boot 4.1 · PostgreSQL（开发用 H2）
- 运行时：`fellowloop-daemon`，跑在开发机 / 构建机上，驱动本机已登录的 Codex（app-server 协议）或 TRAE CLI 2.x（ACP 协议）
- 前端：React · TypeScript · Ant Design · React Flow（见 [web/README.md](web/README.md)）

完整的技术方案与 Codex 实操手册见 [docs/Fellowloop-Technical-Design-and-Codex-Walkthrough.pdf](docs/Fellowloop-Technical-Design-and-Codex-Walkthrough.pdf)；设计说明见 [docs/design.md](docs/design.md)，两种 CLI 的对接细节与实测记录见 [docs/providers.md](docs/providers.md)。

## 架构

```
Web (React) ──REST + SSE──> fellowloop-server (Spring Boot) <──WebSocket(daemon 主动连)── fellowloop-daemon
                                │  工作流引擎 / 任务调度 / 收件箱              │
                                └── PostgreSQL                               ├─ codex app-server   (JSON-RPC/stdio)
                                                                              └─ traecli acp serve  (ACP/stdio)
```

| 模块 | 说明 |
|---|---|
| `fellowloop-workflow` | 无依赖的 DAG 工作流引擎：定义、组件 SPI、安全表达式与模板、校验、无状态执行器（异步等待/恢复、重试、onError、死路径消除、取消、重跑） |
| `fellowloop-protocol` | 统一的 Agent 事件模型（`AgentEvent`）与 server↔daemon 线协议（`Wire`） |
| `fellowloop-agent` | 与 CLI 无关的会话接口 + Codex 驱动 + TRAE CLI 驱动 + 通用 JSON-RPC 连接 |
| `fellowloop-daemon` | 运行时：注册、长连、探测、工作目录、审批策略、事件合并与断线重放 |
| `fellowloop-server` | 业务（Issue/评论/项目/Agent/运行时/任务/收件箱）、工作流宿主与触发器、内置组件、REST/SSE/WebSocket |
| `web` | 前端 |

## 快速开始（本机开发）

前置：JDK 21、Maven 3.9、Node 22；至少一台机器装好并登录 `codex`（或 TRAE CLI 2.x）。

```bash
cd fellowloop
mvn -q install -DskipTests                     # 构建全部模块
FELLOWLOOP_ADMIN_PASSWORD=dev-pass java -jar fellowloop-server/target/fellowloop-server.jar   # http://localhost:8080
(cd web && npm ci && npm run dev)              # 前端开发服务器 http://localhost:5173（代理 /api）
```

1. 用 `admin@fellowloop.local` / `dev-pass` 登录。
2. **运行时 → 添加运行时**，复制弹窗里的命令到要跑 Agent 的机器上执行：
   ```bash
   java -jar fellowloop-daemon/target/fellowloop-daemon-exec.jar register --server http://localhost:8080 --code fellowloop_r_...
   java -jar fellowloop-daemon/target/fellowloop-daemon-exec.jar doctor     # 自检：Java、git、服务器、令牌、Codex / TRAE 安装与登录
   java -jar fellowloop-daemon/target/fellowloop-daemon-exec.jar start
   ```
   daemon 会探测本机的 Codex / TRAE CLI（版本、登录状态、模型、能力）并上报。`fellowloop-daemon probe` 输出原始探测结果（JSON）。
   服务器配置了 `FELLOWLOOP_DOWNLOADS_DIR` 时（Docker 镜像与发布包默认如此），弹窗会给出**一键安装**命令，见下文「接入运行时」。
3. **Agents → 新建**：选 provider（codex / trae）、运行时、模型、权限档位。
4. 新建 Issue，负责人选这个 Agent。内置工作流「Assigned issue → agent」会把 Issue 改成进行中、让 Agent 干活、把结果发成评论、移到审核中。之后在 Issue 下评论（或 `@AgentName`），Agent 会在**同一个会话**里继续。

## 生产部署

**发布包**：`scripts/package.sh` 生成 `dist/fellowloop-<version>.tar.gz`：

| 目录 | 内容 |
|---|---|
| `fellowloop-server.jar` | 服务端（自带前端） |
| `daemon/` | `fellowloop-daemon.jar`、启动脚本（`fellowloop-daemon` / `.cmd`）、systemd unit、`install-runtime.sh` |
| `cli/` | 命令行客户端 `fellowloop-cli.jar` 与启动脚本（`fellowloop` / `fellowloop.cmd`） |
| `downloads/` | 服务端在 `/downloads/` 下发的文件（daemon、CLI、安装脚本），把 `FELLOWLOOP_DOWNLOADS_DIR` 指向它 |
| `deploy/` | Docker Compose 与 `.env` 样例 |

`scripts/package-daemon.sh` 另可为当前平台打一个**自带 Java 运行时**（jlink）的 daemon 包，目标机无需安装 Java。不用 Docker 时：

```bash
FELLOWLOOP_DB_URL=jdbc:postgresql://db:5432/fellowloop FELLOWLOOP_DB_USER=fellowloop FELLOWLOOP_DB_PASSWORD=... \
FELLOWLOOP_ADMIN_PASSWORD=... FELLOWLOOP_PUBLIC_URL=https://fellowloop.example.com FELLOWLOOP_DOWNLOADS_DIR=./downloads \
java -jar fellowloop-server.jar
```

**Docker Compose**（反向代理参考 `deploy/nginx.conf.example`：SSE 关闭缓冲、daemon WebSocket 升级）：

```bash
cp deploy/.env.example deploy/.env          # 改密码和 FELLOWLOOP_PUBLIC_URL
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

镜像里自带前端，并在 `{FELLOWLOOP_PUBLIC_URL}/downloads/` 下发 `fellowloop-daemon.jar`、`fellowloop-cli.jar` 与 `install-runtime.sh`。

主要配置（环境变量）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `FELLOWLOOP_DB_URL` / `FELLOWLOOP_DB_USER` / `FELLOWLOOP_DB_PASSWORD` | H2 文件库 | 生产用 PostgreSQL |
| `FELLOWLOOP_ADMIN_EMAIL` / `FELLOWLOOP_ADMIN_PASSWORD` | `admin@fellowloop.local` / 随机（打印在日志） | 首次启动创建的管理员 |
| `FELLOWLOOP_PUBLIC_URL` | `http://localhost:8080` | 用于生成 daemon 接入命令 |
| `FELLOWLOOP_TIMEZONE` | `Asia/Singapore` | 定时触发器默认时区 |
| `FELLOWLOOP_HTTP_ALLOWED_HOSTS` | 空 | `http.request` 节点可访问的主机白名单（防 SSRF） |
| `FELLOWLOOP_NOTIFY_WEBHOOK_URL` / `FELLOWLOOP_NOTIFY_FORMAT` | 空 / `slack` | 收件箱新事项推送到群（`feishu`、`dingtalk`、`wecom`、`slack`、`teams`） |
| `FELLOWLOOP_WEB_DIR` | `classpath:/static/` | 前端静态文件目录，如 `file:/opt/fellowloop/web/` |
| `FELLOWLOOP_AUDIT_RETENTION_DAYS` | `365` | 审计日志保留天数，0 为永久 |
| `FELLOWLOOP_HTTP_BIND`（Compose） | `0.0.0.0` | 前面有反向代理时设为 `127.0.0.1`，防止绕过代理 |
| `FELLOWLOOP_TRUSTED_PROXIES` | 本机（Compose：Docker 网桥） | 允许其 `X-Forwarded-For` 生效的反向代理地址（正则） |
| `FELLOWLOOP_STORAGE_DIR` | `~/.fellowloop/server/files`（镜像内为 `/var/lib/fellowloop/files`，挂载卷 `fellowloop-files`） | 上传附件的存放目录，需与数据库一起备份 |
| `FELLOWLOOP_DOWNLOADS_DIR` | 空（镜像内为 `/opt/fellowloop/downloads`） | 在 `/downloads/` 下发 daemon、CLI 与安装脚本；有安装脚本时「添加运行时」显示一键安装命令 |

### 接入运行时（构建机 / 开发机）

在「运行时 → 添加运行时」复制一键安装命令（Linux / macOS）：

```bash
curl -fsSL https://fellowloop.example.com/downloads/install-runtime.sh | sh -s -- \
    --server https://fellowloop.example.com --code fellowloop_r_... [--name sg-build-01] [--service] [--allow-network]
```

脚本会：检查 Java 21 与 git → 未安装 Codex 时可用 npm 安装（`--install-codex`），未登录时引导 `codex login --device-auth`（SSH 环境可用；也支持 `printenv OPENAI_API_KEY | codex login --with-api-key`）→ 下载 daemon 与 `fellowloop` 命令行到 `~/.fellowloop/bin` → 注册 → 运行 `fellowloop-daemon doctor` → 可选安装 systemd 用户服务（`--service`）。TRAE CLI 需按 TRAE 官方方式安装后执行 `traecli login`（或 `TRAECLI_PERSONAL_ACCESS_TOKEN=... traecli login --with-trae-pat`）。

`fellowloop-daemon doctor` 随时可运行，逐项给出 ✓ / ! / ✗ 和修复建议，有阻断问题时退出码为 1，便于放进运维脚本。Windows 上用 `daemon/fellowloop-daemon.cmd register ...` 手动接入。

### 命令行 `fellowloop`

在 **设置 → API tokens** 创建个人访问令牌（`fellowloop_p_` 前缀，可随时吊销；令牌只能做日常工作，不能改密码、签发令牌、管理成员、接入运行时或修改项目仓库，泄露也无法接管账号），然后：

```bash
fellowloop login --server https://fellowloop.example.com --token fellowloop_p_...
fellowloop issues --project WMS
fellowloop issue create --project WMS --title "Reject putaway into blocked bins" --assign Codex
fellowloop ask Codex --project WMS "Where is FIFO enforced for line-side picks?"
fellowloop run "Nightly WMS health check" --wait        # 按名称或 id 运行一个已启用的工作流
fellowloop inbox && fellowloop approve inb_... --comment "ok"
```

所有命令都支持 `--json`；CI 中可直接用 `FELLOWLOOP_SERVER` / `FELLOWLOOP_TOKEN` 环境变量。完整参考见 [docs/cli.md](docs/cli.md)。

备份、恢复、升级、令牌管理与故障排查见 **[运维手册 docs/operations.md](docs/operations.md)**。

版本变化见 **[CHANGELOG.md](CHANGELOG.md)**。

## 功能一览

- **看板与 Issue**：多项目、Issue key（如 `APP-12`）、评论（支持 `@Agent`）、指派给 Agent 或成员。
- **直接提问**：在 Agent 页面或 `fellowloop ask` 发起不关联 Issue 的一次性任务（可选项目：在其仓库的全新检出中回答；否则为临时目录），实时查看、插话或取消；完成后可在同一会话中**追问**。
- **截图与附件**：评论框可粘贴/拖入/上传图片，Agent 在下一个任务以图片输入收到（daemon 用任务 token 下载）。
- **执行记录**：实时转录（消息、思考摘要、命令与输出、文件 diff、计划、用量），运行中可**取消**或**插话**（Codex 原生 steer；不支持时排到下一轮）。
- **工作流**：画布编排、组件面板、自动表单、变量助手、版本历史；内置「指派即执行」「评论续聊」，模板「实现 → 交叉评审 → 审批」「实现 ⇄ 评审循环 → 审批」「审查 Issue 分支」（Codex 内置评审器 `review/start`）「夜间健康检查」（定时在项目仓库的全新检出中体检，发现问题自动开 Issue）「推送 Issue 分支」（`repo.push`：由工作过的机器用本机 git 凭证推送 `fellowloop/<key>`，返回合并请求链接）。
- **收件箱**：审批、Agent 提问、工作流表单、失败提醒。
- **MCP 回写**：Agent 运行中可通过 Fellowloop 的 MCP 工具读 Issue、发进展、改状态（每个任务一个短期 token）。
- **Git**：项目配置仓库后，每个 Issue 一个分支 `fellowloop/<key>`，同一 Issue 的 Agent 共享工作目录；遗留改动自动提交。
- **两种 CLI 同等支持**：Codex（app-server）与 TRAE CLI 2.x（ACP）都经过实机验证；驱动抹平两者在模型命名、权限语义（TRAE 工作区默认联网 → Fellowloop 自定义档位关闭）、拒绝审批后的行为、插话能力与用量口径上的差异，同一条工作流可以让 TRAE 实现、Codex 评审，或反过来。
- **技能（Skills）**：可复用的 Markdown 指令库，Agent 选用后每个任务自动注入（Codex 与 TRAE 通用）。内置 9 个经过整理的技能（git 提交规范、测试纪律、代码评审清单、安全基线、Java/Spring、Python、TypeScript/React、仓储 WMS 约定、汽车物流领域知识），首次启动自动导入，之后可在「技能 → 从内置库添加」补回缺少的技能（已存在的同名技能不会被覆盖）。
- **审计日志**：登录（含失败与锁定）、令牌、成员与角色、Agent 权限变更（档位、命令白名单、联网、审批升级）、运行时接入与移除、审批决定（含被批准的命令）、工作流与项目变更，记录操作人、时间与来源 IP；设置 → 审计日志 查看，可导出 CSV。
- **命令行与令牌**：`fellowloop` CLI 覆盖 Issue、Agent、提问、任务、工作流、收件箱；个人访问令牌可在设置页创建与吊销。
- **成员与用量**：成员/角色管理，按 Agent、按天的运行与 token 统计。

## Agent 权限档位

| 档位 | Codex | TRAE CLI | daemon 对审批请求 |
|---|---|---|---|
| `read_only` | `sandbox=read-only` | `default_permissions=":read-only"` | 全部拒绝 |
| `workspace`（默认） | `sandbox=workspace-write`, `approvalPolicy=on-request` | `default_permissions=":workspace"`，白名单命令转 `--allowed-tool Bash(...)` | 工作区内改文件放行；白名单命令放行；其余拒绝 |
| `full` | `danger-full-access` | `--yolo` | 仅在标记为 `--isolated` 的运行时生效，否则降级为 `workspace` |

工作区档位还内置放行 `git add/commit/status/diff/log`（Codex 沙箱把 `.git` 设为只读，提交会以审批请求出现）。Agent 配置里的附加 CLI 参数默认被 daemon 忽略，机器主人用 `fellowloop-daemon start --allow-extra-args` 才会生效，且沙箱/审批相关参数始终被丢弃——运行时机器的主人始终掌握最终权限。

Codex 的工作区沙箱默认禁止联网；Agent 可开启 **Network in the sandbox**（下载 Maven / npm / pip 依赖），仅当 daemon 以 `--allow-network` 启动时生效。

Agent 可开启 **Ask a person when blocked**（受阻时询问人）：策略将拒绝某个命令或改动时，不直接拒绝，而是送到收件箱，由人选择“允许一次 / 本任务内允许 / 拒绝”，Agent 等待回答。只读档位不参与。

## 测试

```bash
mvn test                         # 全部离线测试（引擎、驱动对假 CLI、daemon、server 端到端）
FELLOWLOOP_CODEX_BIN=$(which codex) mvn test -pl fellowloop-agent -Dgroups=live -Dfellowloop.excludedGroups=none   # 真实 Codex（默认 gpt-6-luna）
FELLOWLOOP_TEST_DB_URL=jdbc:postgresql://localhost:5432/fellowloop_e2e FELLOWLOOP_TEST_DB_USER=fellowloop FELLOWLOOP_TEST_DB_PASSWORD=... \
  mvn test -pl fellowloop-server        # 端到端测试跑在 PostgreSQL 上（CI 每次提交都跑）
```

离线测试用 `FakeCodexAppServer` / `FakeTraeAcp`（按真实报文字段构造的脚本化假 CLI）；`EndToEndTest` 在一个 JVM 里起 server + 真实 daemon + 假 Codex，覆盖指派→执行→评论→审核、评论续接会话、审批节点、校验报错。

## 许可与合规说明

- 第三方开源组件及其许可证见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)（`python3 scripts/third-party-notices.py` 重新生成）。
- Fellowloop 以 [MIT 许可证](LICENSE) 发布；全部代码、界面与素材均为独立设计与实现，不包含第三方同类产品的代码、UI 或素材。
- Codex CLI 与 TRAE CLI 由用户自行安装、登录，本项目不分发它们。产品名不使用 "Codex"/"Trae"。
- Codex app-server 要求在 `clientInfo.name` 标识客户端（本项目为 `fellowloop`）；面向企业正式分发前应联系 OpenAI 加入已知客户端名单。
- TRAE CLI 2.0 仅对 TRAE 企业版旗舰版开放，客户需自备账号。
- 依赖均为宽松许可（Apache-2.0 / MIT / BSD / EPL/MPL 的 H2 仅用于开发）。
