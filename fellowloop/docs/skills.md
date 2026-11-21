# 技能（Skills）使用指南

English: [skills.en.md](skills.en.md)

技能是一段可复用的 Markdown 指令，例如团队的提交规范、某种语言的编码约定、业务领域规则。给 Agent 选用后，**每个任务**派发时都会把技能拼进 Agent 的指令，Codex 与 TRAE CLI 通用——团队的规则写一次，所有 Agent 都遵守。

技能不需要在运行时机器上安装任何东西：它存放在 Fellowloop 服务端，随任务下发。

## 1. 先用内置技能

Fellowloop 自带 9 个技能，空库首次启动时自动导入：

| 技能 | 内容 |
|---|---|
| `git-commits` | 在 Issue 分支上小步提交、写清提交说明、不提交密钥 |
| `testing-discipline` | 用测试证明改动，不削弱测试 |
| `code-review-checklist` | 评审者批准前检查的内容 |
| `security-basics` | 每次改动都要遵守的安全编码基线 |
| `java-spring-conventions` | Java 17+/21 与 Spring Boot 服务约定 |
| `python-conventions` | Python 服务与库的约定 |
| `typescript-react-conventions` | TypeScript 与 React 前端约定 |
| `wms-conventions` | 仓储规则：库位编码、整数数量、冻结库位、FIFO |
| `automotive-logistics` | 汽车零部件入厂与线边物流的领域知识 |

删掉了或想补回某个内置技能：**设置 → 技能 → 技能库**，点“添加”（或“添加全部未添加的”）。已存在的同名技能不会被覆盖，团队改过的版本会保留。

## 2. 新建自己的技能

1. 打开 **设置 → 技能 → 新建技能**。
2. 填写：
   - **名称**：建议用小写字母、数字和连字符，例如 `release-checklist`；名称不区分大小写，不能重复。
   - **描述**：一句话说明用途，显示在列表和 Agent 的技能选择里。
   - **指令（Markdown）**：Agent 会读到的正文，最长 50,000 字符。
3. 保存。

编写建议：

- **短而具体**：每个任务都会带上它，越长越占上下文。写“必须 / 不得”，少写背景介绍。
- **写可检查的规则**：例如“数量是整数件，库存不得为负”，而不是“注意数据正确性”。
- **一个技能一个主题**：提交规范、测试纪律、领域规则分开写，Agent 按需组合。
- **不要写密钥、口令或内部地址**：技能内容会发给模型。

模板（可直接复制）：

```markdown
# 发版清单

- 只在 `fellowloop/<issue-key>` 分支上工作，不要直接改 `main`。
- 改动公共接口时，同步更新 `docs/api.md` 与 `CHANGELOG.md`。
- 提交前运行 `./gradlew test`，全部通过后再提交。
- 新增配置项必须有默认值，并在 `deploy/.env.example` 中给出示例。
- 不确定的需求在 Issue 里提问，不要自行猜测。
```

## 3. 给 Agent 选用技能

1. 打开 **Agents**，新建或编辑一个 Agent。
2. 在 **技能** 一栏选择一个或多个技能。
3. 保存。

从下一个任务开始生效。派发任务时，技能按选择顺序拼在 Agent 指令之后：

```
<Agent 的指令>

## Skill: git-commits

<技能正文>

## Skill: wms-conventions

<技能正文>
```

Codex 通过 developer instructions、TRAE 通过系统提示词接收这段指令。

## 4. 修改技能后何时生效

- 修改后，**新开的会话**（新 Issue、重新指派、新的 Ask）立即使用新内容。
- 评论续聊、`--follow-up` 追问会**续接已有会话**，沿用该会话开始时的指令。要让修改立刻作用于某个 Issue，可以重新指派或新开 Issue。
- 删除技能后，选用它的 Agent 从下一个任务起不再收到它（技能列表中显示哪些 Agent 在用）。

## 5. 权限

- 登录网页的成员可以查看、新建、修改、删除技能，并给 Agent 选用技能。
- **个人访问令牌（CLI、脚本、CI）只能读取技能，不能修改**：技能决定了 Agent 被告知什么，所以修改必须由登录网页的人完成，令牌泄露也无法改动 Agent 的指令。
- Agent 选用技能的变更会记入审计日志（设置 → 审计日志）。

## 6. 在命令行中查看技能

```bash
fellowloop skills                 # 列出技能，以及哪些 Agent 在用
fellowloop skills --library       # 内置技能库，以及是否已添加
fellowloop skill show git-commits # 查看某个技能的完整内容
fellowloop skills --json          # 机器可读的输出
```

命令行只读；新建、修改与删除请在网页 **设置 → 技能** 中完成。也可以用 REST API：`GET /api/skills`、`GET /api/skills/library`（需要登录会话或令牌）。
