English: [operations.en.md](operations.en.md)

# Fellowloop 运维手册

面向部署和维护 Fellowloop 的运维同事：数据在哪里、怎么备份与恢复、怎么升级、令牌怎么管、出了问题先看什么。

## 1. 数据在哪里

| 数据 | 位置 | Docker Compose 中 | 是否需要备份 |
|---|---|---|---|
| 业务数据（Issue、评论、Agent、工作流、运行记录、收件箱、令牌哈希） | PostgreSQL（`FELLOWLOOP_DB_URL`） | 卷 `fellowloop-db` | **是** |
| 上传的附件（截图等） | `FELLOWLOOP_STORAGE_DIR`（默认 `~/.fellowloop/server/files`） | 卷 `fellowloop-files`（`/var/lib/fellowloop/files`） | **是**，与数据库同一时间点 |
| daemon 配置与运行时令牌 | 运行时机器 `~/.fellowloop/daemon.json`（或 `FELLOWLOOP_HOME`），权限 600 | — | 否：丢失后重新注册即可 |
| Agent 工作目录 | 运行时机器 `~/.fellowloop/work`：`issues/<KEY>`（保留）、`runs/`、`adhoc/`（7 天未用自动清理） | — | 否：成果在 Git 分支上；未推送的分支只在这台机器上 |
| 命令行配置 | 使用者电脑 `~/.fellowloop/cli.json`（目录 700、文件 600） | — | 否 |

server 本身无状态，可以随时重建容器；只要数据库和附件目录在，数据就不会丢。

## 2. 备份与恢复

**备份**（建议每日，保留 14 天以上）：

```bash
# 数据库（自定义格式，可选择性恢复）
docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U fellowloop -Fc fellowloop > fellowloop-$(date +%F).dump
# 附件
docker run --rm -v deploy_fellowloop-files:/data -v "$PWD":/backup alpine tar czf /backup/fellowloop-files-$(date +%F).tgz -C /data .
```

卷名前缀取决于 Compose 项目名（默认为目录名 `deploy`，即 `deploy_fellowloop-files`），可用 `docker volume ls` 确认。托管数据库（如 RDS）直接使用其快照功能，附件目录另行备份。

**恢复**：

```bash
docker compose -f deploy/docker-compose.yml stop server
docker compose -f deploy/docker-compose.yml exec -T postgres pg_restore -U fellowloop -d fellowloop --clean --if-exists < fellowloop-2026-11-20.dump
docker run --rm -v deploy_fellowloop-files:/data -v "$PWD":/backup alpine sh -c "rm -rf /data/* && tar xzf /backup/fellowloop-files-2026-11-20.tgz -C /data"
docker compose -f deploy/docker-compose.yml start server
```

恢复后，备份时刻之后的变化会丢失；运行时、Agent 与令牌回到备份时的状态。备份时正在运行的任务若已无法继续，会在 daemon 重连对账后标记为失败（`RUNTIME_LOST`），可在界面上重新运行。

## 3. 升级

1. **先备份**（见上）。数据库迁移（Flyway）只向前执行，回退版本需要恢复升级前的备份。
2. **先升级 server**：替换 `fellowloop-server.jar` 或重新构建镜像（`docker compose ... up -d --build`）。启动时自动迁移数据库；`curl https://fellowloop.example.com/api/health` 返回新版本号即完成。重启 server 不会中断正在运行的任务：daemon 自动重连，重连时 server 立即续租这些任务，期间产生的事件与结果在重连后补发（已实测：任务运行中重启 server，任务照常完成）。
3. **再升级各台 daemon**：重新执行一键安装命令（会覆盖 `~/.fellowloop/bin` 下的 jar，保留注册信息），或替换 `fellowloop-daemon.jar` 后重启服务（`systemctl --user restart fellowloop-daemon`）。重启 daemon 会使正在执行的任务失败（可重新运行），所以最好在机器空闲时升级（运行时页面显示当前任务数）。
4. 在每台机器上运行 `fellowloop-daemon doctor`：它会显示服务器版本，并在 daemon 比 server 新时提示先升级 server。

协议向后兼容：旧 daemon 可以连接新 server，只是用不到需要新版本 daemon 的功能（例如 0.2.0 以下不能执行评审任务、0.3.0 以下不能推送分支，server 不会把这类任务派给旧 daemon）。

## 4. 令牌与访问

| 操作 | 方法 |
|---|---|
| 某人离职 | 设置 → 成员 中移除；其会话和个人访问令牌随之失效 |
| 个人访问令牌疑似泄露 | 本人在 设置 → API tokens 中删除；或修改密码（修改后本人的其他会话与令牌全部失效）。个人令牌无法修改密码、签发令牌、管理成员、运行时或项目，也不能修改 Agent、Skill 与工作流定义（仍可提问、发起工作流运行、回答收件箱），因此泄露也无法接管账号或放宽 Agent 的权限；审计日志中用令牌执行的受审计操作带 `via: token` 标记，可按操作人筛选查看（Issue、评论、提问等日常操作不进审计日志） |
| 管理员重置他人密码 | 设置 → 成员 中修改；该成员的会话与令牌全部失效 |
| 停用一台运行时机器 | 运行时 页面删除该运行时：运行时令牌立即吊销，daemon 会提示需要重新注册 |
| 更换运行时机器 | 新机器用新的注册码接入，再在 Agent 设置里把 Agent 指向新运行时 |
| 命令行退出 | `fellowloop logout`：在服务端吊销令牌并删除本地配置 |

所有令牌在库中只存 SHA-256 哈希；注册码 1 小时有效且只能使用一次。

谁能做什么：

- 注册码、运行时探测与删除运行时：所有者与管理员。
- 放宽 Agent（`full` 档位、允许命令、额外 CLI 参数、网络访问）：所有者与管理员；成员可在此范围内创建、修改 Agent。
- 修改角色：成员不能修改所有者；只有所有者能修改管理员。
- 提问（Ask）属于提问人：只有本人能追问；本人或所有者/管理员能插话、取消；Issue 与工作流的任务属于团队。
- 浏览器发起的写请求必须来自 Fellowloop 自身的源（`Origin` 等于 Host 头或 `FELLOWLOOP_PUBLIC_URL`）。放在反向代理后面时保留 Host 头（`proxy_set_header Host $host`）或把 `FELLOWLOOP_PUBLIC_URL` 设为用户访问的地址。
- Webhook：密钥放在 `X-Fellowloop-Secret` 头里（`?secret=` 会出现在代理日志中）；带相同 `Idempotency-Key`、`X-GitHub-Delivery` 或 `X-Gitlab-Event-UUID` 的重发返回第一次启动的运行，不会重复执行。

## 5. 运行时机器

```bash
systemctl --user status fellowloop-daemon        # 服务状态
journalctl --user -u fellowloop-daemon -f        # 实时日志
systemctl --user restart fellowloop-daemon       # 重启（运行中的任务在 daemon 重连时标记为 RUNTIME_LOST，可重新运行；提问可在原会话中追问继续）
sudo loginctl enable-linger $USER          # 注销后仍保持运行
~/.fellowloop/bin/fellowloop-daemon doctor             # 逐项自检，附修复建议
```

- **并发**：`--capacity N`（默认 2）。每个任务是一个独立的 Codex / TRAE 进程，按机器的 CPU 与内存设置。
- **磁盘**：每个 Issue 一个仓库检出，长期保留；`runs/` 与 `adhoc/` 中 7 天未用的目录自动清理。大型仓库请预留足够空间，`doctor` 在可用空间低于 2 GB 时告警。
- **网络**：Agent 沙箱默认不能联网；需要下载依赖时以 `--allow-network` 启动 daemon，并在 Agent 上开启“沙箱内联网”。
- **完全访问权限**：`full` 档位只在以 `--isolated` 启动的机器上生效，只应用于一次性虚拟机或容器。
- **凭证**：Agent 使用这台机器上已登录的 Codex / TRAE 账号和 git 凭证；推送分支同样使用本机 git 凭证。请为构建机使用专用账号，不要放个人令牌。
- **命令白名单**：被批准的命令在沙箱外、以 daemon 用户的权限执行。条目是前缀（后面可跟任意参数），请写到具体任务（`./gradlew test`、`npm test`），不要写 `./gradlew *`、`bash`、`python3` 这类可执行任意代码的入口；`git push` 条目只允许把 `fellowloop/*` 分支推到 `origin`。推送更推荐用工作流里的 `repo.push` 节点，由 daemon 以固定的 refspec 完成。

### 在没有浏览器的机器上登录 TRAE 企业版

`traecli login` 会在本机打开浏览器并等待回调到 `127.0.0.1:<端口>`。构建机通常没有浏览器，可以这样完成：

1. 在构建机上运行 `BROWSER=echo traecli login`：它不会打开浏览器，而是把登录网址打印出来，并继续等待回调。
2. 在自己电脑的浏览器里打开该网址，用企业账号完成 SSO。
3. 浏览器最后会跳到 `http://127.0.0.1:<端口>/authorize?...` 并显示无法访问（因为那个端口在构建机上）。复制这个完整地址，粘贴到构建机上 traecli 的提示“Paste callback URL to verify automatically”处（或在另一个终端执行 `curl "<该地址>"`），登录即完成，`traecli login status` 显示已登录。

**升级自较早版本**：1.0 之前的 daemon 在“本任务内允许”时会让 TRAE 保存“不再询问”规则，并把每个任务目录标为受信任。`fellowloop-daemon doctor` 会检查并提示指向工作目录的已保存规则；请在每台运行时机器上删除这些残留：`~/.trae/cli/rules/default.rules` 中指向 Fellowloop 工作目录的 `prefix_rule`，以及 `~/.trae/traecli.toml` 中 `~/.fellowloop/work/...` 的 `[projects."…"]` 条目。TRAE 与 Codex 自己也会把执行过任务的目录记为受信任项目（`~/.trae/traecli.toml`、`~/.codex/config.toml`），清理 `~/.fellowloop/work` 时可一并删除对应条目。

注意：这个回调地址里带有登录令牌，只在构建机上使用，不要发到聊天群或工单里。也可以改用个人访问令牌：`TRAECLI_PERSONAL_ACCESS_TOKEN=... traecli login --with-trae-pat`。

## 6. Agent 配置建议（Codex 与 TRAE）

| 项目 | 建议 | 原因 |
|---|---|---|
| 权限档位 | 实现者用 `workspace`，评审者用 `read_only`；`full` 只给以 `--isolated` 启动的一次性机器 | 评审任务即使配置为 workspace 也按只读执行；`full` 不经沙箱 |
| 命令白名单 | 写到具体任务：`python3 -m unittest`、`npm test`、`./gradlew test`；不写 `bash`、`python3`、`./gradlew *` | 条目是前缀，带参数可加载任意脚本，且在沙箱外执行 |
| 推送 | 用工作流的 `repo.push` 节点；确需让 Agent 推送时白名单写 `git push`（只能推 `fellowloop/*` 到 `origin`） | daemon 用固定 refspec 推送，不会覆盖主干 |
| 受阻时询问人 | 只为确实需要偶尔越界的 Agent 打开；收件箱里优先选“允许一次” | “本任务内允许”只对完全相同的命令与目录生效 |
| 沙箱联网 | 需要下载依赖时：Agent 开启“沙箱内联网”，daemon 以 `--allow-network` 启动 | 两处都开启才生效，默认断网 |
| Codex 模型 | 实现与评审用同一模型（评审模型自动设为 Agent 的模型）；`effort` 用 `medium` 起步 | 原生评审（`review/start`）不上报 token 用量 |
| TRAE 模型 | 实现用 Doubao-Seed-Code（id `Doubao_1_6`），评审用 GLM-5.3-Flash；推理强度只有 low / high / xhigh | 可写显示名或 id；不支持的强度会提示并回退 |
| 评审 | 交叉评审（TRAE 实现、Codex 评审或反之）配合“实现 ⇄ 评审循环”模板，`maxRounds` 3 | 评审仍有问题时发布问题清单，不会无限循环 |
| 指令与技能 | 写进 Agent 指令或技能库；TRAE 通过系统提示词下发，Codex 通过 developer instructions 下发 | 两者都在会话开始时生效，续接会话保持 |

## 7. 故障排查

| 现象 | 先看 | 处理 |
|---|---|---|
| 运行时显示离线 | 机器上 `fellowloop-daemon doctor` | 服务器不可达：检查代理 / 防火墙是否允许出站 HTTPS 与 WebSocket；令牌被拒：重新注册 |
| 运行时在线但任务一直排队 | 运行时页面的 CLI 状态 | CLI 未登录（`codex login --device-auth`）、Agent 指向了别的运行时，或并发已满 |
| 任务失败：not logged in / 401 | 任务转录与 `doctor` | 在该机器上重新登录 Codex / TRAE |
| 任务卡在“等待审批” | 收件箱 | Agent 开启了“升级审批给人”，需要有人批准或拒绝；超时计时在等待期间暂停 |
| 安装依赖失败（DNS / 网络错误） | Agent 是否开启“沙箱内联网”、daemon 是否带 `--allow-network` | 两处都开启后重试 |
| 推送分支失败 | 运行记录中 `repo.push` 节点的输出 | 该机器的 git 凭证无权推送；只允许推送 `fellowloop/*` 分支 |
| 浏览器中实时更新不刷新，或 daemon 反复断线 | 反向代理配置 | `/api/stream`（SSE）需要关闭缓冲，`/api/daemon/ws` 需要 WebSocket 升级，两者都要放宽读超时；参考 `deploy/nginx.conf.example`（已在 Nginx 1.24 上实测 TLS、SSE 与 daemon 长连接） |
| 附件在容器重建后丢失 | `FELLOWLOOP_STORAGE_DIR` 是否在持久卷上 | 使用 1.0 的 compose 文件（已挂载 `fellowloop-files` 卷） |

## 8. 审计

设置 → 审计日志（仅 owner / admin）列出谁在何时、从哪个 IP 做了什么：登录成功、失败与锁定，个人令牌签发与吊销，成员增删、角色与密码变更，Agent 的创建、归档与权限变更（权限档位、命令白名单、附加参数、技能、联网、审批升级；指令只记录“已修改”，不记录内容），运行时注册码签发、注册与移除，审批决定（含被批准或拒绝的命令），工作流的增删改、启停与手动运行，项目的创建与仓库变更（含旧值）。

审计记录只追加、不可在界面上修改；`GET /api/audit.csv?from=&to=`（毫秒时间戳，默认近 90 天）分批流式导出 CSV，便于归档到 SIEM 或合规存储。审计表 `audit_event` 随数据库一起备份，默认保留 365 天（`FELLOWLOOP_AUDIT_RETENTION_DAYS`，0 表示永久保留），每日自动清理。仓库地址中的凭证、命令中的令牌与密码在写入前脱敏；失败登录不把输入的用户名当作操作人。

**来源 IP**：server 只相信 `FELLOWLOOP_TRUSTED_PROXIES`（对端地址的正则表达式）所列代理发来的 `X-Forwarded-For`；默认只信任本机（127.x），Compose 默认信任 Docker 网桥（172.16/12，即宿主机上的反向代理）。其他来源直接连接时，该头被忽略，客户端无法伪造登录限流与审计使用的地址。反向代理应覆盖而不是追加该头（参考配置中 `proxy_set_header X-Forwarded-For $remote_addr;`），并让 server 端口只对代理开放（Compose 中设 `FELLOWLOOP_HTTP_BIND=127.0.0.1`）。代理在其他机器上时，把它的地址写进 `FELLOWLOOP_TRUSTED_PROXIES`，例如 `10\.0\.0\.5`。

失败登录按“邮箱 + 地址”计数，同一组合连续失败 10 次后锁定 15 分钟（锁定本身记一条审计）；全局每分钟最多记录 600 条失败登录，超出部分在下一分钟以 `auth.audit_throttled` 记录被省略的条数。

## 9. 监控

- `GET /api/health`：无需登录，返回 `{"ok":true,"version":"…"}`，适合负载均衡与可用性探测。
- 运行时页面：每台机器的在线状态、CLI 登录状态、并发占用。
- 用量页面：按 Agent、按天统计运行次数、成功率与 token 用量。
- 收件箱可推送到 Slack / Teams / 飞书 / 钉钉 / 企业微信（`FELLOWLOOP_NOTIFY_WEBHOOK_URL`）。

## 10. 依赖安全检查

发版前运行（需要能访问 `api.osv.dev` 与 npm 仓库）：

```bash
python3 scripts/dependency-audit.py
```

它把 server、daemon、CLI 与 Agent 驱动的全部 Java 运行时依赖提交到 [OSV](https://osv.dev)（包含 GitHub Advisory Database）查询已知漏洞，并对前端运行 `npm audit`；有发现时退出码为 1。

最近一次结果（2026-11-21）：Java 90 个运行时依赖、npm 310 个包，**均无已知漏洞**。本次审计中处理的事项：

- 前端 React Router 由 6.30.6 升级到 7.18.6，修复 GHSA-wrjc-x8rr-h8h6（`<Link>` / `useNavigate` 中反斜杠导致的开放重定向）；另一项 GHSA-337j-9hxr-rhxg 只影响服务端渲染，本产品不使用。
- 登录后的返回地址只接受站内路径（拒绝 `//…`、反斜杠与控制字符），钓鱼链接无法借登录把用户带到外部站点。
- 内嵌 Tomcat 固定为 11.0.29（Spring Boot 4.1.3 自带 11.0.27），对外服务的组件保持在最新补丁。

另外建议：Docker 镜像基于浮动标签（`eclipse-temurin:21-jre` 等），每次重建都会带上操作系统补丁；正式发布前可用 Trivy 等工具扫描镜像，并为发布版本固定镜像摘要。Codex CLI 与 TRAE CLI 由各运行时机器自行安装，请按厂商公告及时升级。
