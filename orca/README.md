# Orca

> 代号 Orca（可替换）。把 AI 编码智能体当作团队成员：Issue 指派给 Agent，Agent 在你自己的机器上用 **OpenAI Codex** 或 **TRAE CLI** 干活，边做边汇报，做完交给人审；所有自动化都是**可编排的工作流**。

- 后端：Java 21 · Spring Boot 4.1 · PostgreSQL（开发用 H2）
- 运行时：`orca-daemon`，跑在开发机 / 构建机上，驱动本机已登录的 Codex（app-server 协议）或 TRAE CLI 2.x（ACP 协议）
- 前端：React · TypeScript · Ant Design · React Flow（见 [web/README.md](web/README.md)）

设计说明见 [docs/design.md](docs/design.md)，两种 CLI 的对接细节与实测记录见 [docs/providers.md](docs/providers.md)。

## 架构

```
Web (React) ──REST + SSE──> orca-server (Spring Boot) <──WebSocket(daemon 主动连)── orca-daemon
                                │  工作流引擎 / 任务调度 / 收件箱              │
                                └── PostgreSQL                               ├─ codex app-server   (JSON-RPC/stdio)
                                                                              └─ traecli acp serve  (ACP/stdio)
```

| 模块 | 说明 |
|---|---|
| `orca-workflow` | 无依赖的 DAG 工作流引擎：定义、组件 SPI、安全表达式与模板、校验、无状态执行器（异步等待/恢复、重试、onError、死路径消除、取消、重跑） |
| `orca-protocol` | 统一的 Agent 事件模型（`AgentEvent`）与 server↔daemon 线协议（`Wire`） |
| `orca-agent` | 与 CLI 无关的会话接口 + Codex 驱动 + TRAE CLI 驱动 + 通用 JSON-RPC 连接 |
| `orca-daemon` | 运行时：注册、长连、探测、工作目录、审批策略、事件合并与断线重放 |
| `orca-server` | 业务（Issue/评论/项目/Agent/运行时/任务/收件箱）、工作流宿主与触发器、内置组件、REST/SSE/WebSocket |
| `web` | 前端 |

## 快速开始（本机开发）

前置：JDK 21、Maven 3.9、Node 22；至少一台机器装好并登录 `codex`（或 TRAE CLI 2.x）。

```bash
cd orca
mvn -q install -DskipTests                     # 构建全部模块
ORCA_ADMIN_PASSWORD=dev-pass java -jar orca-server/target/orca-server.jar   # http://localhost:8080
(cd web && npm ci && npm run dev)              # 前端开发服务器 http://localhost:5173（代理 /api）
```

1. 用 `admin@orca.local` / `dev-pass` 登录。
2. **运行时 → 添加运行时**，复制弹窗里的两条命令到要跑 Agent 的机器上执行：
   ```bash
   java -jar orca-daemon/target/orca-daemon-exec.jar register --server http://localhost:8080 --code orca_r_...
   java -jar orca-daemon/target/orca-daemon-exec.jar start
   ```
   daemon 会探测本机的 Codex / TRAE CLI（版本、登录状态、模型、能力）并上报。`orca-daemon probe` 可单独查看。
3. **Agents → 新建**：选 provider（codex / trae）、运行时、模型、权限档位。
4. 新建 Issue，负责人选这个 Agent。内置工作流「Assigned issue → agent」会把 Issue 改成进行中、让 Agent 干活、把结果发成评论、移到审核中。之后在 Issue 下评论（或 `@AgentName`），Agent 会在**同一个会话**里继续。

## 生产部署

**发布包**：`scripts/package.sh` 生成 `dist/orca-<version>.tar.gz`，内含自带前端的 `orca-server.jar`、`daemon/orca-daemon.jar` 与启动脚本、`deploy/`。不用 Docker 时：

```bash
ORCA_DB_URL=jdbc:postgresql://db:5432/orca ORCA_DB_USER=orca ORCA_DB_PASSWORD=... \
ORCA_ADMIN_PASSWORD=... ORCA_PUBLIC_URL=https://orca.example.com ORCA_DOWNLOADS_DIR=./daemon \
java -jar orca-server.jar
```

**Docker Compose**：

```bash
cp deploy/.env.example deploy/.env          # 改密码和 ORCA_PUBLIC_URL
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

镜像里自带前端；daemon 安装包可从 `{ORCA_PUBLIC_URL}/downloads/orca-daemon.jar` 下载。daemon 需要 Java 21，可用 `dist-scripts/` 下的启动脚本和 systemd unit。

主要配置（环境变量）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `ORCA_DB_URL` / `ORCA_DB_USER` / `ORCA_DB_PASSWORD` | H2 文件库 | 生产用 PostgreSQL |
| `ORCA_ADMIN_EMAIL` / `ORCA_ADMIN_PASSWORD` | `admin@orca.local` / 随机（打印在日志） | 首次启动创建的管理员 |
| `ORCA_PUBLIC_URL` | `http://localhost:8080` | 用于生成 daemon 接入命令 |
| `ORCA_TIMEZONE` | `Asia/Singapore` | 定时触发器默认时区 |
| `ORCA_HTTP_ALLOWED_HOSTS` | 空 | `http.request` 节点可访问的主机白名单（防 SSRF） |
| `ORCA_NOTIFY_WEBHOOK_URL` / `ORCA_NOTIFY_FORMAT` | 空 / `slack` | 收件箱新事项推送到群（`feishu`、`dingtalk`、`wecom`、`slack`、`teams`） |
| `ORCA_WEB_DIR` | `classpath:/static/` | 前端静态文件目录，如 `file:/opt/orca/web/` |

## 功能一览

- **看板与 Issue**：多项目、Issue key（如 `APP-12`）、评论（支持 `@Agent`）、指派给 Agent 或成员。
- **直接提问**：在 Agent 页面发起不关联 Issue 的一次性任务（临时目录），实时查看、插话或取消。
- **截图与附件**：评论框可粘贴/拖入/上传图片，Agent 在下一个任务以图片输入收到（daemon 用任务 token 下载）。
- **执行记录**：实时转录（消息、思考摘要、命令与输出、文件 diff、计划、用量），运行中可**取消**或**插话**（Codex 原生 steer；不支持时排到下一轮）。
- **工作流**：画布编排、组件面板、自动表单、变量助手、版本历史；内置「指派即执行」「评论续聊」，模板「实现 → 交叉评审 → 审批」「实现 ⇄ 评审循环 → 审批」。
- **收件箱**：审批、Agent 提问、工作流表单、失败提醒。
- **MCP 回写**：Agent 运行中可通过 Orca 的 MCP 工具读 Issue、发进展、改状态（每个任务一个短期 token）。
- **Git**：项目配置仓库后，每个 Issue 一个分支 `orca/<key>`，同一 Issue 的 Agent 共享工作目录；遗留改动自动提交。
- **技能（Skills）**：可复用的 Markdown 指令库，Agent 选用后每个任务自动注入（Codex 与 TRAE 通用）。
- **成员与用量**：成员/角色管理，按 Agent、按天的运行与 token 统计。

## Agent 权限档位

| 档位 | Codex | TRAE CLI | daemon 对审批请求 |
|---|---|---|---|
| `read_only` | `sandbox=read-only` | `default_permissions=":read-only"` | 全部拒绝 |
| `workspace`（默认） | `sandbox=workspace-write`, `approvalPolicy=on-request` | `default_permissions=":workspace"`，白名单命令转 `--allowed-tool Bash(...)` | 工作区内改文件放行；白名单命令放行；其余拒绝 |
| `full` | `danger-full-access` | `--yolo` | 仅在标记为 `--isolated` 的运行时生效，否则降级为 `workspace` |

工作区档位还内置放行 `git add/commit/status/diff/log`（Codex 沙箱把 `.git` 设为只读，提交会以审批请求出现）。Agent 配置里的附加 CLI 参数默认被 daemon 忽略，机器主人用 `orca-daemon start --allow-extra-args` 才会生效，且沙箱/审批相关参数始终被丢弃——运行时机器的主人始终掌握最终权限。

## 测试

```bash
mvn test                         # 全部离线测试（引擎、驱动对假 CLI、daemon、server 端到端）
ORCA_CODEX_BIN=$(which codex) mvn test -pl orca-agent -Dgroups=live -Dorca.excludedGroups=none   # 真实 Codex（默认 gpt-6-luna）
```

离线测试用 `FakeCodexAppServer` / `FakeTraeAcp`（按真实报文字段构造的脚本化假 CLI）；`EndToEndTest` 在一个 JVM 里起 server + 真实 daemon + 假 Codex，覆盖指派→执行→评论→审核、评论续接会话、审批节点、校验报错。

## 许可与合规说明

- 参考了 Multica 的产品概念，**未使用其代码、UI 或素材**（Multica 许可证限制托管/嵌入式商用与 UI 品牌）。
- Codex CLI 与 TRAE CLI 由用户自行安装、登录，本项目不分发它们。产品名不使用 "Codex"/"Trae"。
- Codex app-server 要求在 `clientInfo.name` 标识客户端（本项目为 `orca`）；面向企业正式分发前应联系 OpenAI 加入已知客户端名单。
- TRAE CLI 2.0 仅对 TRAE 企业版旗舰版开放，客户需自备账号。
- 依赖均为宽松许可（Apache-2.0 / MIT / BSD / EPL/MPL 的 H2 仅用于开发）。
