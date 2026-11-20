# Orca 设计说明

本文描述已实现的设计。对接两种 CLI 的协议细节与实测记录见 [providers.md](providers.md)。

## 1. 目标与边界

- 把 AI 编码智能体当作团队成员：Issue 指派给 Agent → Agent 在客户自己的机器上工作 → 结果回到 Issue，交给人审。
- 同时支持 **OpenAI Codex** 与 **TRAE CLI 2.x**，内部统一为一套会话接口与事件模型。
- 所有自动化都是**组件化、可编排的工作流**；“指派即执行”“评论续聊”只是两条内置工作流。
- 代码与 CLI 凭证只留在运行时机器上；server 不接触仓库凭证和模型账号。
- 面向新加坡客户自托管（单租户），界面英文为主、可切中文，默认时区 Asia/Singapore。
- 参考 Multica 的产品概念，净室实现，不使用其代码、UI 与素材。

## 2. 组件

```
┌──────────── Web ────────────┐
│ 看板 / Issue / 收件箱 / 编排 │
└──────┬──────────────▲───────┘
   REST │          SSE │（事务提交后推送）
┌──────▼──────────────┴──────────────────────────────┐
│ orca-server                                         │
│  Issue/Comment/Project · Agent · Runtime · Inbox    │
│  WorkflowService（引擎宿主）· TriggerService        │
│  TaskService（队列、路由、租约、上下文）             │
│  DaemonGateway（WebSocket）                          │
└──────▲─────────────────────────────────────────────┘
       │ WebSocket /api/daemon/ws（daemon 主动连，Bearer 运行时 token）
┌──────┴─────────────────────────────────────────────┐
│ orca-daemon：探测 · 工作目录 · 审批策略 · 事件缓冲  │
│   CodexProvider → codex app-server (stdio)          │
│   TraeProvider  → traecli acp serve (stdio)         │
└────────────────────────────────────────────────────┘
```

## 3. 领域模型

| 实体 | 要点 |
|---|---|
| Member / ApiToken | 角色 owner / admin / member（成员管理在 `/api/members`）；只存 token 的 SHA-256：会话 token（Web）、运行时 token（daemon）、一次性注册码、任务 MCP token |
| Runtime | 一台跑 daemon 的机器；`providers[]` 记录每个 CLI 的版本、登录、套餐、模型、能力、错误 |
| Agent | 名称（即 @ 提及名）、provider、绑定运行时、模型/推理强度、指令、技能、权限档位、命令白名单、并发、超时 |
| Skill | 可复用的 Markdown 指令；派发任务时按 Agent 的选择拼入其指令（`## Skill: <name>`），与 CLI 无关 |
| Project / Issue / Comment | Issue key 形如 `ORCA-12`；状态 backlog → todo → in_progress → in_review → done（可 cancelled） |
| Task | 一次 Agent 执行：queued → dispatched → running ⇄ waiting_input → succeeded / failed / cancelled / timed_out；记录 provider 会话（Codex thread / ACP session）、工作目录键、结果、结构化输出、错误码、用量 |
| TaskEvent | `(task_id, seq)` 唯一的统一事件，重放幂等 |
| Workflow / WorkflowVersion / WorkflowRun | 定义每次修改生成不可变版本；运行锁定启动时的版本；运行状态以 JSON 快照持久化 |
| InboxItem | 需要人处理的事：approval（审批节点）、input（问人节点）、question（Agent 提问）、alert（运行失败） |

## 4. 工作流

### 4.1 定义与组件

- 定义是 JSON：节点 `{id, type, name, config, retries, timeoutSeconds, onError, ui}`，连线 `{id, source, sourcePort, target, condition}`。
- 组件 SPI：`descriptor()`（类型、分类、端口、配置字段、输出声明 → 前端自动生成表单和变量提示）、`execute()` → Completed / Waiting / Failed、`onSignal()`（异步恢复）、`cancel()`。内置组件是 Spring Bean，外部组件通过 `ServiceLoader` 加载。
- 配置值支持 `{{ trigger.issue.title }}`、`{{ nodes.review.output.structured.verdict }}`；整段只有一个占位符时保留原始类型。表达式语言自研、无副作用（路径、比较、逻辑、三元、少量函数），不能调用 Java 方法。

### 4.2 执行语义

- 图必须是 DAG（保存时校验：环、端口、必填项、未连接节点、表达式语法；问题定位到节点/连线）。
- 节点在所有入边都有结果、且至少一条被激活时执行；全部未激活则跳过并向下传播（死路径消除），条件分支能正确汇合。
- 异步节点（Agent、审批、问人）返回 Waiting + 等待键，状态落库；任务结束或人回答后用 `signal(runId, nodeId, waitKey, payload)` 恢复，等待键不匹配的旧信号被忽略。
- 节点级 `retries`（按错误码判断是否可重试）、`timeoutSeconds`、`onError`（FAIL 终止运行 / CONTINUE 走 `error` 端口）；运行可取消（连带取消等待中的任务）、失败节点可重跑。
- 引擎无状态、不依赖 Spring；server 对同一运行串行化（单实例内锁 + 事务），扩容时换成行锁。

### 4.3 内置组件

| 分类 | 类型 |
|---|---|
| 触发 | `trigger.manual`（带输入）、`trigger.issue`（assigned / commented / created / status_changed，可按 Agent、标签、状态、表达式过滤；默认忽略 Agent/工作流自己造成的变化，避免循环）、`trigger.schedule`（cron + 时区）、`trigger.webhook`（密钥校验） |
| Agent | `agent.run`：Agent（可为模板）、Prompt、Issue、会话 continue/new、输出 JSON Schema、超时；评审模式（`mode=review`）下改为代码评审：Codex 用内置评审器 `review/start`，其他 CLI 用等价提示词<br>`agent.review_loop`：实现者与评审者在一个节点内轮转（实现者续接会话，评审者每轮新会话并给出结构化结论），通过走 `passed`，超过轮数走 `failed`——在 DAG 里表达“改到评审通过为止” |
| 逻辑 | `logic.condition`（true/false）、`logic.set`、`logic.join` |
| 人工 | `human.approval`（approved/rejected）、`human.input` |
| 动作 | `issue.comment`（以 Agent 或系统身份）、`issue.update`、`issue.create`、`repo.push`（工作过的机器推送 Issue 分支，只推 `orca/*`、不强推）、`http.request`（主机白名单）、`notify.webhook`（飞书/钉钉/企微/Slack/Teams） |

内置工作流：
1. **Assigned issue → agent**：标记进行中 → Agent 执行（续接会话，失败重试 1 次）→ 以 Agent 身份发结果评论 → 移到审核中；失败走 error 分支发失败说明。
2. **Comment → agent**：有人评论 Agent 负责的 Issue 或 `@Agent` 时，Agent 在同一会话继续并回复。
3. **Template: implement → cross-review → approve**（手动运行）：实现 Agent → 评审 Agent 按 JSON Schema 给出 verdict → 条件判断 → 人工审批 → 完成 / 打回。可以 Codex 实现、TRAE 评审。
6. **Template: nightly health check of a project**（定时或手动）：Agent 在项目仓库的全新检出、运行专属分支上检查默认分支，以 JSON 给出结论；不健康时自动开 Issue 列出发现。
5. **Template: review an issue branch**（手动运行）：评审 Agent 以评审模式审查 Issue 分支相对默认分支的改动，并把结论发到 Issue。
4. **Template: implement ⇄ review loop → approve**（手动运行）：用 `agent.review_loop` 最多 3 轮，通过后人工审批并完成，未通过则把遗留问题发到 Issue。

## 5. 任务调度

- **路由**：按 Agent 绑定的运行时派发，要求运行时在线、对应 CLI 已安装且已登录；不满足时任务留在队列并写明原因（“codex is not logged in on dev-box”）。
- **并发**：运行时容量、Agent 并发上限、**同一工作目录同一时间只有一个任务**。
- **工作目录**：同一 Issue 的所有 Agent 共享 `issues/<KEY>`（评审能看到实现的改动）；无 Issue 的运行共享 `runs/<runId>`，Ask 使用 `adhoc/<agent>-<随机>`；指定项目时，检出放在同级目录 `runs/<runId>-<KEY>` / `adhoc/…-<KEY>`，分支为 `orca/<目录键>`。一次性目录 7 天未使用才清理，每次使用都会刷新目录时间。项目配置了仓库时，首次克隆并建分支 `orca/<key>`，常见构建缓存写入 `.git/info/exclude`。Agent 被要求自己提交；Codex 沙箱把 `.git` 设为只读，提交以审批请求出现，工作区档位内置放行 `git add/commit/status/diff/log`。任务成功后仍有未提交改动时，daemon 以 `<KEY>: <总结首行>` 自动提交，保证工作留在分支上（推送和 PR 仍由人或明确要求时由 Agent 完成）。
- **会话续接**：按 (Agent, Issue) 找上次成功任务的 provider 会话并续接；续接失败（会话丢失、换了机器/CLI）则开新会话，并把 Issue 上下文（标题、描述、人和 Agent 的评论，不含系统通知）作为前情提要。新会话的 prompt 若没提到该 Issue，也会自动补上 Issue 上下文。
- **租约**：派发和事件/心跳会续租；租约过期：未开始的任务重新排队，已开始的判 `RUNTIME_LOST`。daemon 重连时上报在跑任务，server 对账。
- **控制**：取消（中断 → 宽限 → 杀进程树）、插话（支持则 steer，否则排到下一轮）、Agent 提问 → 收件箱 → 回答回送。

## 6. server ↔ daemon 协议

WebSocket，帧为 `{type, data}`（`dev.orca.protocol.Wire`）：

- daemon → server：`hello`（机器信息、各 CLI 探测结果、在跑任务及最后 seq）、`heartbeat`、`probe.result`、`task.accepted`、`task.started`、`task.events`（带 seq 的批量事件，增量合并）、`task.completed`
- server → daemon：`welcome`、`task.assign`、`task.cancel`、`task.steer`、`task.input`、`probe`、`ack`

接入：Web 上生成一次性注册码（1 小时有效）→ `orca-daemon register` 换取长期运行时 token。daemon 断线指数退避重连，未确认的事件重放，server 按 seq 去重。

## 7. 安全

- Web 会话 cookie（HttpOnly、SameSite=Lax）或 Bearer；daemon 用运行时 token；webhook 用节点密钥（常量时间比较）。
- 权限档位映射到 CLI 自己的沙箱和审批；daemon 的审批策略默认拒绝（沙箱内联网须 Agent 请求且 daemon 以 `--allow-network` 启动；Agent 可选“受阻时询问人”：策略将拒绝时改为送到收件箱，由人允许一次 / 本任务内允许 / 拒绝），只放行工作区内文件改动和白名单命令（禁止 `&&`、`;`、`|`、反引号、`$(`）。`full` 只在标记为隔离环境的运行时生效。
- 工作目录键严格校验，不能逃出工作根目录。
- `http.request` 只允许 `ORCA_HTTP_ALLOWED_HOSTS` 中的主机，不跟随重定向；通知组件额外允许常见 IM webhook 主机。
- MCP 回写 token 通过环境变量传给 CLI，不出现在命令行参数里。

## 8. MCP 回写

`POST /api/mcp` 是一个精简的 MCP 服务器（Streamable HTTP，JSON 响应）。派发任务时为每个关联了 Issue 的任务签发只在任务期间有效的 token（任务结束即吊销），daemon 通过环境变量把它交给 CLI。工具：`orca_get_issue`、`orca_post_comment`（以 Agent 身份发进展，不触发工作流）、`orca_set_issue_status`。最终回复仍由工作流节点发出，避免重复。可用 `orca.mcp.enabled=false` 关闭。

## 9. 前端

React + TypeScript + Ant Design + React Flow + TanStack Query。页面：看板、Issue 详情（评论与执行记录时间线、实时转录、插话/取消）、收件箱、Agents、运行时（每个 CLI 的健康状态、接入命令）、工作流列表与画布编辑器（组件面板、自动表单、变量助手、校验定位）、运行记录（图上叠加节点状态）。一条 SSE 连接驱动缓存失效与转录追加。英文为主、可切中文。

## 10. 用量

`/api/stats?days=N` 按 Agent 和按天汇总运行次数、成功/失败、token 与忙碌时间。Codex 上报的是会话累计 token，续接的会话会带上之前任务的用量；驱动在会话打开后的第一次上报记下基线，事件里的 `taskTokens` 只计本任务。

## 11. 交付与运维

- **运行时接入**：`install-runtime.sh`（服务端 `/downloads/` 下发）检查 Java / git，引导安装并登录 Codex（`codex login --device-auth` 适合 SSH），下载 daemon 与 CLI，注册（带上运行时名称），运行自检，可选 systemd 用户服务。脚本只用 POSIX sh，非交互（`--yes`）时不做任何安装。
- **自检**：`orca-daemon doctor` 逐项检查 Java ≥ 21、git 与身份、工作目录可写与磁盘、注册信息、服务器可达（`/api/health`）、运行时令牌（`GET /api/daemon/whoami`，普通 HTTP，不会挤掉正在运行的 daemon 连接）、每个 CLI 的安装与登录（没有任何可用 CLI 视为阻断问题）、网络与 full 档位设置、是否已有 daemon 持有锁；每项给出修复命令，有阻断问题时退出码 1。终端不支持 UTF-8 时自动改用 ASCII 标记。
- **命令行**：`orca` 只调用公开 REST API；认证用个人访问令牌（`orca_p_`，库里只存哈希，可在设置页吊销）或密码换取的 90 天令牌；令牌不能修改密码、签发令牌、管理成员、运行时或项目（AuthFilter 统一拦截），修改密码须验证当前密码并使其他会话与令牌失效；`logout` 在服务端吊销令牌；`--json` 与稳定退出码便于脚本与 CI。
- **技能库**：内置技能以带 front matter 的 Markdown 随服务端发布（`classpath:skills/*.md`），空库首次启动时导入；之后可在界面上从内置库补回缺少的技能；已存在的同名技能（可能已被团队修改）不会被覆盖。

- **审计**：`AuditService` 记录安全相关操作（登录、令牌、成员、Agent 权限、运行时、审批、工作流、项目），只追加；不写入令牌、密码、提示词与指令正文；管理员可查看与导出 CSV（CSV 单元格对 `= + - @` 开头做防公式注入处理）。

## 12. 后续

- GitHub/GitLab/Gitee 的合并请求 API 集成（目前 `repo.push` 推送分支并回传托管平台打印的创建链接）、有轮数上限的循环子流程。
- 多实例（行锁、跨实例推送）、OIDC/SSO、更细的 RBAC。
- TRAE CLI 登录后的实机联调（见 providers.md 待验证清单）；评估以 app-server 统一两种 CLI。
