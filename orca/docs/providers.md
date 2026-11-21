# Codex 与 TRAE CLI 对接说明

本文记录两种 CLI 的对接方式、**已实测**的事实与**尚待验证**的点。实测环境：Linux x86_64 容器，codex-cli **0.171.0**（ChatGPT 登录），TRAE CLI **0.216.0**（2.x，企业版 SSO 登录）。

## 1. 总体

| | Codex | TRAE CLI 2.x |
|---|---|---|
| 协议 | `codex app-server --listen stdio://`（JSON-RPC，消息不带 `jsonrpc` 字段） | `traecli acp serve`（ACP，JSON-RPC 2.0） |
| 驱动 | `orca-agent` 的 `codex.CodexSession` | `trae.TraeSession` |
| 版本 | 无硬下限（实测 0.171.0） | ≥ 0.200.0（2.x）；1.x 拒绝 |
| 同源 | — | TRAE CLI 2.0 基于 Codex 代码构建（二进制内含 `codex_core`、`codex_app_server_protocol` 等模块），ACP 层架在其 app-server 之上（`initialize` 返回 `_meta.trae.acpBackend = "app-server"`） |

两者的权限模型（沙箱 + 审批）、`-c key=value` 配置覆盖、TOML 配置与 AGENTS.md 约定同源，所以 `Permission` 档位能一一映射。待 TRAE 的 app-server 正式开放后，可以评估用同一个 app-server 驱动接两者。

## 2. Codex（app-server）

握手：`initialize {clientInfo:{name:"orca",...}}` → `initialized` 通知。

| 用途 | 方法 / 字段 | 状态 |
|---|---|---|
| 探测登录 | `account/read` → `account.type`（`chatgpt`/`apikey`…）、`planType` | ✅ 实测 |
| 模型列表 | `model/list` → `data[].id/displayName/description/isDefault/supportedReasoningEfforts` | ✅ 实测（10 个模型） |
| 新会话 | `thread/start {cwd, model, sandbox, approvalPolicy, developerInstructions, config, serviceName}` | ✅ 实测 |
| 按会话注入 MCP | `thread/start.config.mcp_servers.orca = {url, bearer_token_env_var}`，token 走环境变量 | ✅ 实测：Agent 通过 Orca 的 MCP 工具读 Issue、发进展评论 |
| MCP 工具确认 | Codex 调用 MCP 工具前发 `mcpServer/elicitation/request`；对 `serverName == "orca"` 自动同意，其他 MCP 服务器转人工 | ✅ 实测 |
| 续接 | `thread/resume {threadId, excludeTurns:true}` | ✅ 实测（续接后记得之前的文件） |
| 一轮 | `turn/start {threadId, input:[text/localImage], effort, outputSchema}` | ✅ 实测 |
| 结构化输出 | `turn/start.outputSchema`，最终消息即 JSON | ✅ 实测 |
| 代码评审 | `review/start {threadId, delivery:"inline", target:{type:"baseBranch", branch}}` → `enteredReviewMode` / `exitedReviewMode`（结论在 `review` 字段） | ✅ 实测（该模式不上报 token 用量） |
| 流式 | `item/started|completed`（agentMessage/reasoning/commandExecution/fileChange/mcpToolCall/webSearch…）、`item/agentMessage/delta`、`item/commandExecution/outputDelta`、`turn/plan/updated`、`turn/diff/updated`、`thread/tokenUsage/updated`、`turn/completed` | ✅ 实测（已录制报文） |
| 交付结果 | `agentMessage.phase == "final_answer"` | ✅ 实测 |
| 中断 | `turn/interrupt` → `turn/completed.status = interrupted` | ✅ 实测 |
| 插话 | `turn/steer {threadId, expectedTurnId, input}` | ✅ 实测（运行中改指令，最终回复按新指令） |
| 审批 | `item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`item/permissions/requestApproval`（决策 accept/acceptForSession/decline/cancel） | ✅ 实测：写工作区外（如 `$HOME`）触发命令审批，策略拒绝后 Agent 报告受阻；注意 workspace-write 默认允许写 `/tmp` |
| 向人提问 | `item/tool/requestUserInput`、`mcpServer/elicitation/request` | 🟡 按 schema 实现 |
| 错误分类 | `turn.error.codexErrorInfo`：unauthorized / usageLimitExceeded / contextWindowExceeded / sandboxError / httpConnectionFailed… | 🟡 按文档实现 |

实测发现：
- 只读沙箱 + `approvalPolicy=never` 时，小模型（gpt-6-luna）会以为自己“没有工具”。因此 `read_only` 档位使用 `on-request`，由 daemon 拒绝所有升级请求。
- 每轮固定开销约 1.6 万输入 token（多数命中缓存），尽量续接会话而不是新开。
- 推荐测试模型：协议/驱动测试 `gpt-6-luna`（low），端到端 `gpt-6.2-sol`（low）。不要用 astra 跑测试。

## 3. TRAE CLI 2.x（ACP）

实测环境：TRAE CLI **0.216.0**，企业版 SSO 登录（`console.enterprise.trae.cn`），模型 GLM-5.3-Flash。以下全部为**实机验证**结果，`TraeLiveTest`（4 个用例，`ORCA_TRAE_BIN=$(which traecli) mvn test -pl orca-agent -Dgroups=live -Dorca.excludedGroups=none`）覆盖其中关键行为。

启动参数（daemon 生成）：`traecli acp serve -c model="…" [-c model_reasoning_effort="…"] -c projects."<cwd>".trust_level="trusted"`，再按权限档位追加：

| 档位 | 参数 | 实测效果 |
|---|---|---|
| `read_only` | `-c default_permissions=":read-only"` | 工作区内外均不可写，网络不可用 ✅ |
| `workspace` | `-c default_permissions="orca_workspace" -c permissions.orca_workspace.extends=":workspace" -c permissions.orca_workspace.network.enabled=<false\|true>` | 工作区与临时目录可写，其他位置被沙箱拒绝；网络默认关闭，Agent 开启“沙箱内联网”且 daemon 以 `--allow-network` 启动时才打开 ✅ |
| `full` | `--yolo` | 仅在 `--isolated` 运行时生效 |

注意：TRAE 内置的 `:workspace` 档位**默认允许联网**（与 Codex 不同），且 `-c sandbox_workspace_write.network_access=false` 对它无效（实测仍可直连外网）。Orca 因此定义自己的 `orca_workspace` 档位继承 `:workspace` 并显式关闭网络，使两种 CLI 的“工作区”语义一致。

| 用途 | 方法 / 字段 | 状态 |
|---|---|---|
| 版本 / 登录 | `traecli -V`、`traecli login status`；企业 SSO 登录需浏览器回调到本机端口，无浏览器的机器可在别处完成授权后把回调地址交给本机（见运维手册） | ✅ |
| 能力 | `initialize` → `loadSession`、`promptCapabilities.image`、`mcpCapabilities.http`、`sessionCapabilities{list,resume,fork,close,systemPrompt,additionalDirectories}`、`authMethods`（企业 SSO / PAT） | ✅ |
| 模型 | `session/new` 返回 `configOptions[id=model]`：`value` 是模型 id（如 `Doubao_1_6`），`name` 是显示名（“Doubao-Seed-Code”），另有描述与上下文窗口。探测时开一个空会话读取后立即 `session/close`（不发 prompt、不耗 token，30 分钟缓存）。`-c model` 接受 id 或显示名；会话中用 `session/set_config_option {configId:"model", value}` 切换；未知模型在 `session/new` 报 “is unavailable” → `MODEL_NOT_FOUND` | ✅ |
| 模式 | `modes`：`default`（Workspace Edit）、`plan`、`auto`、`bypass_permissions`；Orca 以权限档位控制，不切换模式 | ✅ |
| 系统提示词 | `session/new {systemPrompt: {mode: "append", content: [{type:"text", text}]}}`；Agent 指令与技能通过它下发（此前拼在首轮 prompt 前）。`workspace` 档位还会先附一段说明：`.git` 在沙箱内只读，需要提交时先执行 git 命令，被沙箱拦截后申请在沙箱外执行同一条命令（实测 Doubao-Seed-Code 不加说明时常常直接放弃提交）。评审轮次只发 `/review`，指令留到下一个普通轮次 | ✅ |
| 会话 | `session/new {cwd, mcpServers:[{type:"http", name, url, headers:[{name,value}]}]}`、`session/load`（新进程中恢复，回放历史 17 条，驱动忽略回放并保留上下文）、`session/prompt`、`session/cancel` | ✅ |
| 流式 | `session/update`：`agent_message_chunk`、`agent_thought_chunk`、`tool_call`（含 `rawInput.command`）、`tool_call_update`、`plan`、`usage_update`（上下文占用 `used/size`）、`available_commands_update` | ✅ |
| 用量 | `session/prompt` 结果的 `usage` 与 `_meta._trae/tokenUsage{turn, sessionTotal, contextWindow}`；驱动换算成与 Codex 相同的字段，`taskTokens` 只计本任务的轮次 | ✅ |
| 套餐额度 | ACP 无对应方法；实验性的 `traecli app-server` 有 `account/rateLimits/read`，但企业 SSO 账号返回 “browser account authentication required to read rate limits”，因此运行时页面只显示 Codex 的额度窗口。TRAE 开放后可用与 Codex 相同的 `UsageWindow` 接入 | ✅ 实测（0.216.0） |
| 图片 | prompt 中 `{type:"image", mimeType, data(base64)}` | ✅ |
| 审批 | 沙箱拦截后模型可申请提权：`session/request_permission`，`toolCall.rawInput` 带 `command`、`cwd`、`reason`；选项固定三个：`approved`（allow_once）、`approved-execpolicy-amendment`（allow_always）、`abort`（reject_once）。**选 abort 会结束整轮（stopReason=cancelled）**，与 Codex 拒绝后继续工作不同；驱动在这种情况下自动在同一会话追加一轮“该请求已被拒绝，请换一种方式完成或说明原因”（最多 3 次，与原请求共用同一个超时；用尽后任务以失败而不是“已取消”结束），使两者行为一致。daemon 批准时只选 `approved`（仅这一次），不选“不再询问” | ✅ |
| 命令白名单 | 不使用 `--allowed-tool Bash(...)`：实测与条目完全相同的命令会被 TRAE 直接在沙箱外执行、不经过审批，绕过 daemon 对工作目录与嵌套仓库的检查。白名单命令改由 daemon 的审批策略在提权请求时逐条判断 | ✅ |
| `.git` 保护 | `:workspace` 档位下工作区根目录的 `.git` 为只读（写 `.git/config`、`.git/hooks` 被沙箱拒绝），但可以在子目录 `git init` 嵌套仓库；daemon 因此在工作区存在嵌套仓库时不批准任何 git 命令 | ✅ |
| 插话 | `_meta.steering.supported=true`，但 ACP 下没有对应方法：`_session/steer` 返回 Method not found，进行中再发 `session/prompt` 被拒（“an ACP prompt is already in progress”）。驱动不声明 steer 能力，插话排到下一轮发送 | ✅（结论：不支持） |
| 代码评审 | `available_commands` 中的 `review` 命令：prompt 发 `/review <目标说明>`（分支评审附 `git diff main...HEAD` 提示），返回与 Codex 相同格式的 `[P0..P3] 文件:行` 结论；TRAE 会把总体结论作为第二条消息重复一次，驱动去重 | ✅ |
| 编辑差异 | 文件编辑的 `diff`（`oldText`/`newText`）随 `tool_call` 下发，完成事件只有 `{"success":true}`；驱动保存起始事件中的 diff 并生成统一 diff 展示 | ✅ |
| 结构化输出 | ACP 无原生支持：提示词约束 + JSON 解析 | ✅ |

## 4. 统一事件

两种协议都归一到 `dev.orca.protocol.AgentEvent`：`session`、`user.message`、`turn.started/completed`、`message.delta`/`message`（`phase=final_answer` 为交付结果）、`reasoning(.delta)`、`tool.started/output/completed`（kind：command / file_change / mcp / web_search / other）、`plan`、`diff`、`usage`、`approval.requested/resolved`、`input.requested/resolved`、`notice`。错误统一为 `ErrorCodes`（AUTH_REQUIRED、USAGE_LIMIT、CONTEXT_EXCEEDED、SANDBOX、PROVIDER_UNAVAILABLE、SESSION_NOT_FOUND、TIMEOUT、RUNTIME_LOST…），工作流按错误码决定是否重试。

## 5. 录制报文

`DaemonConfig.recordDir`（或测试时 `ORCA_RECORD_DIR`）会把每个 Codex 会话的原始报文写成 JSONL，用于更新假 CLI 和契约测试。报文里可能含账号信息，提交前务必清理。
