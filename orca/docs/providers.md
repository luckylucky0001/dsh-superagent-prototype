# Codex 与 TRAE CLI 对接说明

本文记录两种 CLI 的对接方式、**已实测**的事实与**尚待验证**的点。实测环境：Linux x86_64 容器，codex-cli **0.171.0**（ChatGPT 登录），TRAE CLI **0.216.0**（2.x，未登录）。

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

启动参数（daemon 生成）：`traecli acp serve -c model="…" -c model_reasoning_effort="…" -c projects."<cwd>".trust_level="trusted" -c default_permissions=":workspace" [--allowed-tool Bash(cmd)]… [--yolo]`

| 用途 | 方法 / 字段 | 状态 |
|---|---|---|
| 版本 / 登录 | `traecli -V`、`traecli login status` | ✅ 实测 |
| 能力 | `initialize` → `loadSession`, `promptCapabilities.image`, `mcpCapabilities.http`（无 sse）, `sessionCapabilities{list,resume,fork,close,systemPrompt,additionalDirectories}`, `_meta.steering.supported`, `authMethods`（仅企业 SSO / PAT） | ✅ 实测 |
| 未登录 | `session/new` → `-32000 Authentication required` → `AUTH_REQUIRED` | ✅ 实测 |
| `acp serve` 参数 | `--yolo`、`--allowed-tool`、`--disallowed-tool`、`--disabled-tool`、`-c`、`--enable/--disable`、`-w` | ✅ 实测（`--help`） |
| 会话 | `session/new {cwd, mcpServers:[{type:"http",name,url,headers}]}`、`session/load`（回放历史，驱动会忽略）、`session/prompt`、`session/cancel` | 🟡 按 ACP 规范实现，假 CLI 测试通过，待登录后实测 |
| 流式 | `session/update`：agent_message_chunk / agent_thought_chunk / tool_call / tool_call_update / plan / usage_update | 🟡 同上 |
| 审批 | `session/request_permission`（选 allow_once / reject_once 等 optionId） | 🟡 同上 |
| 结构化输出 | ACP 无原生支持：提示词约束 + JSON 解析；后续可改用 `traecli exec --output-schema` | 🟡 |
| 插话 | 声明了 `steering.supported`，但 `_session/steer` 参数未验证 → 驱动暂不声明 steer，插话降级为“下一轮发送” | ⏳ |
| 模型列表 | `traecli models`（输出格式待登录后确认，驱动按行宽松解析） | ⏳ |

待登录后实测（需要 TRAE 企业版旗舰版账号或 PAT）：`session/new` 返回的模型/模式、`session/update` 字段细节、`systemPrompt` 参数形态（目前把 Agent 指令拼在首轮 prompt 前）、`_session/steer` 参数、elicitation 能力协商、`-c projects.…trust_level` 是否生效、`traecli models` 输出格式。

安装提示：官方脚本 `https://trae.cn/trae-cli/install_v2.sh` 按地区限制访问；登录：`traecli login`，或 `TRAECLI_PERSONAL_ACCESS_TOKEN` + `traecli login --with-trae-pat`，国际站/私有部署用 `TRAECLI_HOST`。

## 4. 统一事件

两种协议都归一到 `dev.orca.protocol.AgentEvent`：`session`、`user.message`、`turn.started/completed`、`message.delta`/`message`（`phase=final_answer` 为交付结果）、`reasoning(.delta)`、`tool.started/output/completed`（kind：command / file_change / mcp / web_search / other）、`plan`、`diff`、`usage`、`approval.requested/resolved`、`input.requested/resolved`、`notice`。错误统一为 `ErrorCodes`（AUTH_REQUIRED、USAGE_LIMIT、CONTEXT_EXCEEDED、SANDBOX、PROVIDER_UNAVAILABLE、SESSION_NOT_FOUND、TIMEOUT、RUNTIME_LOST…），工作流按错误码决定是否重试。

## 5. 录制报文

`DaemonConfig.recordDir`（或测试时 `ORCA_RECORD_DIR`）会把每个 Codex 会话的原始报文写成 JSONL，用于更新假 CLI 和契约测试。报文里可能含账号信息，提交前务必清理。
