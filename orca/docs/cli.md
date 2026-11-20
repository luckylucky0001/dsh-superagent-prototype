# `orca` 命令行参考

`orca` 是 Orca 的命令行客户端（Java 21，单个 jar），适合在终端、脚本和 CI 中使用。它只调用公开的 REST API，权限与网页端一致。

## 安装

- **随运行时安装**：`install-runtime.sh` 会把 `orca` 一并装到 `~/.orca/bin`。
- **单独下载**：`curl -fsSLO https://orca.example.com/downloads/orca-cli.jar`，再用 `java -jar orca-cli.jar ...`，或使用发布包 `cli/` 下的 `orca`（Linux/macOS）/ `orca.cmd`（Windows）启动脚本。

## 登录

| 方式 | 命令 | 说明 |
|---|---|---|
| 个人访问令牌（推荐） | `orca login --server URL --token orca_p_...` | 在 **设置 → API tokens** 创建；可随时吊销；令牌以创建者身份执行 |
| 密码 | `orca login --server URL --email EMAIL` | 交互输入密码，换取 90 天有效的令牌 |
| 环境变量 | `ORCA_SERVER=... ORCA_TOKEN=... orca issues` | CI / 脚本；优先于配置文件 |

配置保存在 `~/.orca/cli.json`（目录 700、文件 600，原子写入），可用 `ORCA_CLI_CONFIG` 指向别处。`orca whoami` 查看当前身份，`orca logout` 在服务端吊销令牌并删除本地配置。

令牌以创建者身份执行日常工作（Issue、提问、任务、工作流、收件箱），但**不能**修改密码、签发或吊销令牌、管理成员、接入或移除运行时——这些操作需要在网页上登录。修改密码时须输入当前密码，修改后本人的其他会话与令牌全部失效。请不要把个人令牌放在共享的构建机上。

## 命令

| 命令 | 作用 |
|---|---|
| `orca issues [--project KEY] [--status STATUS]` | 列出 Issue |
| `orca issue show KEY` | Issue 详情与评论 |
| `orca issue create --title T [--project KEY] [--description D \| --description-file F] [--priority P] [--label L]... [--assign AGENT]` | 新建 Issue，可直接指派给 Agent（触发「指派即执行」） |
| `orca issue comment KEY "text"` | 评论；写 `@Agent` 让 Agent 在同一会话中继续 |
| `orca issue assign KEY AGENT` / `orca issue status KEY STATUS` | 指派 / 改状态 |
| `orca agents` / `orca runtimes` | Agent 与运行时列表 |
| `orca ask AGENT "prompt" [--project KEY] [--follow-up TASK] [--no-wait]` | 直接提问；默认实时输出 Agent 的消息与命令，结束后返回；`--project` 让 Agent 在该项目仓库的全新检出中回答（否则是空的临时目录）；`--follow-up` 在上一个任务的会话与目录里追问 |
| `orca task show ID` / `follow ID` / `cancel ID` / `steer ID "text"` | 查看、跟随、取消任务，或在运行中插话 |
| `orca workflows` / `orca runs [--limit N]` | 工作流与运行记录 |
| `orca run WORKFLOW [--input key=value]... [--wait]` | 按名称或 id 手动运行工作流；`--wait` 等到结束，成功退出码 0 |
| `orca inbox [--all]` | 收件箱（默认只看待处理） |
| `orca approve ID [--comment C]` / `orca reject ID [--comment C]` | 处理审批（含 Agent 的命令 / 文件审批请求） |
| `orca answer ID key=value...` | 回答 Agent 的提问或工作流表单 |
| `orca version` | 客户端版本 |

所有命令都支持 `--json`，输出原始 JSON，便于 `jq` 处理。退出码：`0` 成功，`1` 失败（含任务/运行失败），`2` 用法错误。

## 示例

```bash
# 让 Codex 修一个 Issue（指派即触发「Assigned issue → agent」工作流），之后在评论里继续
orca issue create --project WMS --title "Reject putaway into blocked bins" --assign Codex
orca issue comment WMS-5 "@Codex also cover bins that are blocked for cycle counting"

# 直接提问并实时查看输出；再在同一会话里追问
orca ask Codex --project WMS "Where is FIFO enforced for line-side picks?"
orca ask Codex "Add a test for that" --follow-up task_...

# CI：每晚跑健康检查，失败时让流水线变红
ORCA_SERVER=https://orca.example.com ORCA_TOKEN=$ORCA_TOKEN orca run "Nightly WMS health check" --wait

# 批量处理收件箱中的审批
orca inbox --json | jq -r '.[] | select(.kind=="approval") | .id' | xargs -n1 orca approve
```
