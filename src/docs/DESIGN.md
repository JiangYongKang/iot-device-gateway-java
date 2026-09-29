# 物联网设备接入与设备管理网关（本地可复现）

本工程在 Spring Boot 3.4 / Java 21 骨架上实现了一套**不依赖真实硬件、外部服务或真实凭据**
的设备接入与设备管理流程。所有设备行为由本地模拟器（`sim` 包）以同步、可复现的步骤驱动，
消息交互走进程内投递通道（`InProcessDeliverySink`）。

## 1. 总体结构

```
registry   设备登记/激活/停用，未登记/未激活/停用给出可区分拒绝
session    会话生命周期、幂等重入、确定性接管（generation 单调）
command    离线指令缓冲、TTL 时效、FIFO 投递、显式 EXPIRED/FAILED
telemetry  去重、乱序/时钟回拨判定、字段级校验、有界历史
state      期望/上报状态版本化（CAS）、收敛视图与设备拒绝
firmware   固件发布、兼容区间判定、幂等/单飞、失败与失联回滚、分批
rule       权威数据规则判定、结论绑定规则版本、背压丢弃与毒消息隔离；
           候选规则历史试跑、原子切换/回退、历史版本复看对账与审计留痕
web        REST 接口与统一错误分类
sim        模拟设备、投递通道、场景装配
concurrency 按设备 id 分片的锁（64 stripe），设备粒度“读-判-写”串行化
```

### 并发模型

同一设备的会话接管、遥测写入、指令下发、状态更新、升级状态迁移全部在
`DeviceLockProvider.forDevice(deviceId)` 返回的同一把锁内完成，
配合不可变 record 的“整体替换”，保证：

- 同一设备任意时刻至多一个生效会话；
- 指令状态机迁移不会半更新、不会重复投递；
- 期望状态 CAS 冲突有明确错误码，不发生丢失更新；
- 同一设备任意时刻至多一个进行中的升级。

## 2. 接入协议（HTTP）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/devices` | 登记设备（REGISTERED） |
| POST | `/api/admin/devices/{id}/activate` | 激活设备 |
| POST | `/api/admin/devices/{id}/disable` | 停用设备 |
| POST | `/api/devices/{id}/connect` | 接入，body `{"sessionId":"..."}` |
| POST | `/api/devices/{id}/disconnect` | 断开（Header `X-Session-Id`） |
| POST | `/api/devices/{id}/telemetry` | 遥测上报（`X-Session-Id`） |
| POST | `/api/devices/{id}/commands` | 下发指令（在线直投/离线缓冲） |
| GET  | `/api/devices/{id}/commands` | 查询指令及状态 |
| POST | `/api/devices/{id}/commands/ack` | 设备确认 |
| POST | `/api/devices/{id}/state/desired` | 期望状态（乐观版本号） |
| POST | `/api/devices/{id}/state/reported` | 上报状态/拒绝回报 |
| GET  | `/api/devices/{id}/state` | 状态版本快照 |
| GET  | `/api/devices/{id}/state/convergence` | 收敛结论（逐字段原因） |
| POST | `/api/firmware/publish` | 发布固件及兼容区间 |
| POST | `/api/firmware/devices/{id}/upgrade` | 单台升级（支持幂等键） |
| POST | `/api/firmware/batch-upgrade` | 分批升级 |
| POST | `/api/firmware/devices/{id}/progress` | 设备升级进度/结果回报 |
| GET  | `/api/firmware/devices/{id}/history` | 可追溯升级记录 |
| POST | `/api/rules` | 新增/更新规则并发布激活（版本自动递增、原子切换） |
| POST | `/api/rules/candidates?activate=&reason=` | 发布候选版本；`activate=false` 只留档不影响线上 |
| POST | `/api/rules/trial` | 候选规则历史试跑：按当前规则与候选规则分别判定样本并对比 |
| POST | `/api/rules/activate` | 正式切换到某已发布版本（body 指定 ruleId/version/reason） |
| POST | `/api/rules/rollback` | 回退到任意已发布历史版本（之后事件按该版本处理） |
| GET  | `/api/rules/{ruleId}/versions` | 某规则的已发布历史版本列表 |
| POST | `/api/rules/events` | 提交权威事件（实时处理） |
| GET  | `/api/rules/events/{eventId}/verdicts` | 查询线上结论（含规则版本） |
| POST | `/api/rules/events/replay?ruleVersion=` | 用指定历史版本确定性重算一条事件 |
| POST | `/api/rules/events/review?ruleVersion=&ruleId=&reason=` | 历史复看：返回当时真实结论(LIVE)或按历史版本重算(REPLAY) |
| GET  | `/api/rules/audit` | 试跑/切换/回退/复看/拒绝的操作留痕 |
| GET  | `/api/rules/stats` | 队列深度/提交/处理/背压丢弃/毒消息指标 |

会话协议要点：

- 请求体带 `sessionId`；同一 `sessionId` 重复 `connect` **幂等**返回同一会话与 generation；
- 不同 `sessionId` 的 `connect` 构成**接管**：generation 单调递增，旧会话立即失效，
  旧会话之后的任何操作返回 `SESSION_STALE`；
- 设备的写操作（遥测、ACK、状态回报、升级进度）必须带当前有效 `sessionId`。

## 3. 状态机

### 会话
```
OFFLINE --connect--> ONLINE(generation=g)
ONLINE  --同 sessionId 重入--> ONLINE(g)（幂等）
ONLINE  --新 sessionId 接入--> ONLINE(g+1)（接管，旧会话立即失效）
ONLINE  --disconnect(当前 sessionId)--> OFFLINE
```

### 指令 CommandStatus
```
PENDING ──投递成功──> DELIVERED ──ack──> ACKED
   │                    │
   ├──超过 expireAt──> EXPIRED（显式标记、保留可查）
   └──投递抛异常────> FAILED（保留原因）
```
- 离线期间进入**每设备 FIFO 缓冲**；上线（或被接管后新会话上线）在设备锁内扫描缓冲，
  逐条做“未过期 + 会话仍有效”判定后投递；中途会话再变化则停止，剩余指令留给新会话。
- 过期与失败**绝不静默丢弃**：状态显式落库（内存中保留最近窗口的终态记录）。
- `InProcessDeliverySink` 以 commandId 去重，任何指令不可能投递给两个会话。

### 遥测判定
1. `(deviceId, seq)` 精确去重 → 重复返回 `TELEMETRY_DUPLICATE`；
2. `ts` 早于已接受最大时间戳（乱序/时钟回拨）→ `TELEMETRY_STALE`，不覆盖新数据；
3. 字段校验（先校验后写入，整批原子）：
   - 必填缺失 `MISSING_FIELD`
   - 类型不符 / 未定义字段 `TYPE_MISMATCH`
   - 越界 `OUT_OF_RANGE`
   对应 HTTP 422 `TELEMETRY_VALIDATION_FAILED`，拒绝记录可追溯。
4. 只有成功写入的遥测才经 `EventBridge` 派生事件进入规则引擎——规则只基于权威数据。

### 期望/上报收敛
- `updateDesired(expectedVersion, desired)` 为乐观锁 CAS：
  版本不匹配返回 `STATE_VERSION_CONFLICT`（409），调用方读最新版本重试；
- 整批字段原子校验（未定义/只读/越界），任一非法整批拒绝 `DESIRED_REJECTED`（422），
  不产生半更新、不留越权（只读）状态；
- `convergence` 逐字段给出：
  - `MATCHED`：期望与上报一致；
  - `PENDING`：设备未回报或值不一致（附期望值/上报值）；
  - `REJECTED`：设备通过 reported 接口回报无法满足（附设备给的原因）。

### 固件升级 UpgradeStatus
```
PENDING -> DOWNLOADING -> INSTALLING -> SUCCEEDED（此时才切换设备版本）
     \---------- FAILED ----------/ -> ROLLED_BACK（回到 fromVersion）
升级进行中设备失联(offline) ----------------> ROLLED_BACK
```
- 兼容判定全部通过才建单：型号匹配、固件未撤回(yanked)、
  当前版本 ∈ `[fromMinInclusive, fromMaxExclusive)`、目标 ≠ 当前；
- 幂等：同 `idempotencyKey` 返回原单；同版本重复触发 → `FIRMWARE_DUPLICATE_REQUEST`；
- 单飞：已有进行中升级且目标不同 → `FIRMWARE_ALREADY_IN_PROGRESS`；
- 分批：逐设备独立加锁判定，单台不兼容/未登记只进入 `rejected`，不影响其他设备；
- 回滚后保留完整 `UpgradeRecord`（from/target/状态/原因/起止时间）可追溯。

## 4. 规则版本治理：试跑、切换、回退与历史对账

规则以 `ruleId` 版本化，每次发布 `version` 单调递增。版本状态、切换、审计统一由
`RuleVersionStore` 管理；实时判定（`RuleEngineService`）与治理操作
（`RuleGovernanceService`）共用同一把切换锁；判定逻辑收敛在纯函数 `RuleEvaluator`，
实时处理、历史试跑、历史复看三方共用同一份判定，保证结论一致、可复现。

### 4.1 候选规则历史试跑（只读对比，不碰线上）

`POST /api/rules/trial`，请求体包含候选规则内容（threshold/action/enabled）、圈定的
一批历史事件或样本、试跑原因。引擎对**同一批样本分别按当前生效规则与候选规则判定**，
逐条返回 `RuleTrialItem`（两侧 `RuleEvaluation` 与 `changed` 标记）和汇总
`RuleTrialResult`（总数、结论变化数、稳定 `trialId`）。

- 试跑**只读**：不发布、不激活、不进入实时处理队列、不写任何线上结论；正在进行的实时
  处理不受影响（见测试断言试跑后既有结论版本/条数不变）。
- **确定性、可重复**：判定是纯函数，结论时间取事件的 `occurredAt` 而非当前时钟；
  同一批样本 + 同一组规则重复试跑，`trialId` 相同，逐条结论与变化统计逐字段一致。
- 容量保护：单次样本数超过 `max-trial-events`、或并发试跑数达到 `max-concurrent-trials`
  时立即拒绝（`RULE_TRIAL_LIMIT` / 429），不排队、不降级，并写 REJECTED 审计。
- 每条试跑都留痕（时间、原因、基线版本、样本数、变化数、候选阈值）。

### 4.2 正式启用与切换边界（与实时处理并存，不串版本）

发布即激活（`POST /api/rules`）或先发布候选再 `POST /api/rules/activate`。
切换在 `RuleVersionStore` 的同一把锁内完成“校验 → 切换前钩子 → 替换活动版本引用”，
是一次原子引用切换。实时事件在处理前取一次**活动规则快照**，整条事件只使用这一份快照：

- **一条事件只属于一个规则版本**：不会重复判定、不会遗漏，也不会一部分按旧规则、
  一部分按新规则；结论 `RuleVerdict` 精确绑定当时的 `ruleId + version`。
- 同字段存在多个生效规则时，按“最近激活优先”确定性选取（激活序号单调）。
- **失败不留半生效状态**：切换在替换引用前的任何异常（如注入的写入失败）都会被捕获，
  活动版本保持旧版本不变、继续稳定服务，并返回 `RULE_SWITCH_FAILED`(409) + REJECTED 留痕。
- **并发切换有明确闸门**：同一时刻至多一个切换进入临界区，其余立即返回
  `RULE_SWITCH_IN_PROGRESS`(429)，旧版本继续服务；拒绝路径只读活动版本的 volatile 镜像，
  不与持锁切换互斥。
- 容量/配额打满时同样是“明确拒绝 + 旧版本稳定服务”，绝不留下半生效状态。

### 4.3 回退到任意历史版本

每个 `ruleId` 的全部已发布版本都保留（有界，上限 `max-rule-versions-per-rule`，
当前生效版本不会被裁剪）。`POST /api/rules/rollback` 可回退到其中**任意一个历史版本**：

- 回退是一次普通的原子激活，审计动作记为 `ROLLBACK`；回退完成后，**之后的事件一律按
  回退后的版本处理**。
- 目标版本不存在（未发布或已被裁剪）返回 `RULE_VERSION_NOT_FOUND`(404)。

### 4.4 历史复看与对账（结论必须与当时真实执行一致）

`POST /api/rules/events/review?ruleVersion=N&ruleId=...&reason=...` 指定历史版本复看一条
过去的事件，返回 `{rule, evaluation, verdict, source}`：

- `source=LIVE`：该事件确实在该 `ruleId + version` 下在线上执行过，返回的就是当时
  **真实落地的结论**，版本、命中、动作与历史一致；
- `source=REPLAY`：该事件并未在所请求版本下执行过（例如该版本当时尚未生效），则使用
  **该历史版本自己的规则内容**做确定性重算，并显式标注来源为重算；
- 无论哪种来源，结论都严格绑定请求的历史版本：**不会把新规则算出来的结果挂到旧版本
  名下**，也不会用新版本结论冒充旧版本。重复复看同一事件同一版本，结果逐字段一致。
- 旧入口 `/api/rules/events/replay?ruleVersion=` 保留（兼容），语义等价于按指定历史
  版本的确定性重算。

### 4.5 留痕审计

`GET /api/rules/audit` 返回有界（`max-rule-audit-entries`）的 `RuleAuditEntry` 列表，
覆盖动作类型（`TRIAL/PUBLISH/ACTIVATE/ROLLBACK/REVIEW/REJECTED`）、时间、规则与版本、
原因、结论摘要（如 `v1 -> v2`、样本数/变化数、复看来源与命中）。切换被拒绝时记录被拒时
仍在线上的版本号，可据此核对“拒绝未造成版本变更”。

### 4.6 实时处理的背压与毒消息隔离（保持原有行为）

- 处理队列为有界队列（`rule-queue-capacity`）。模拟积压时满队按**丢弃最旧**背压，
  丢弃数计入 `droppedBackpressure`，`GET /api/rules/stats` 可观测：
  `queueCapacity / queueDepth / submitted / processed / droppedBackpressure / failedPoison`；
- 单条处理异常（毒消息）被捕获计数为 `failedPoison`，后续事件继续处理，链路不被拖垮。

### 4.7 兼容范围与约束

- 所有版本、结论、审计均为**进程内内存存储、单节点**：重启后不保留；不提供跨实例的
  版本复制或分布式切换。这与本工程“不依赖外部服务、本地可复现”的定位一致。
- 旧接口 `POST /api/rules`、`/events`、`/events/replay`、`/stats` 与旧错误码保持不变；
  试跑/切换/回退/复看为新增能力，复用既有统一错误响应体。
- 实时判定阈值语义保持 `value > threshold`（严格大于）。

## 5. 配置项（`application.properties`，前缀 `iot.gateway`）

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `max-commands-per-device` | 100 | 每设备 PENDING 指令缓冲上限，满 → `COMMAND_QUEUE_FULL`(429) |
| `max-telemetry-per-device` | 1000 | 每设备遥测历史上限，满 → `CAPACITY_EXCEEDED`(429) |
| `rule-queue-capacity` | 200 | 规则处理队列容量，满 → 丢弃最旧并计数 |
| `max-events-per-device` | 500 | 每设备规则结论留存上限 |
| `max-rule-versions-per-rule` | 50 | 每条规则已发布历史版本上限（生效版本不裁剪），支撑回退/对账 |
| `max-rule-audit-entries` | 1000 | 试跑/切换/回退/复看/拒绝审计留痕上限，满 → 丢最旧 |
| `max-trial-events` | 5000 | 单次历史试跑最大样本数，超 → `RULE_TRIAL_LIMIT`(429) |
| `max-concurrent-trials` | 2 | 并发试跑上限，占满 → `RULE_TRIAL_LIMIT`(429) |
| `command-ttl-seconds` | 300 | 离线指令默认有效期 |
| `max-devices` | 1000 | 设备登记总数上限，满 → `QUOTA_EXCEEDED`(429) |

所有内存结构均有界（历史/结论/终态指令/去重窗口都按配置裁剪），超限按既定策略
拒绝或降级，不留部分写入、不泄漏资源。

## 6. 错误分类（稳定，不透出底层异常）

`ErrorCode` → HTTP 状态：

| code | HTTP | 触发场景 |
| --- | --- | --- |
| DEVICE_NOT_FOUND | 404 | 设备未登记 |
| DEVICE_NOT_ACTIVATED | 403 | 已登记未激活 / 已停用 |
| DEVICE_ALREADY_ONLINE / SESSION_TAKEN_OVER / SESSION_STALE | 409 | 会话冲突、旧会话操作 |
| COMMAND_EXPIRED | 410 | 指令超过有效期 |
| COMMAND_QUEUE_FULL / CAPACITY_EXCEEDED / QUOTA_EXCEEDED | 429 | 各类容量配额超限 |
| TELEMETRY_DUPLICATE / TELEMETRY_STALE | 409 | 重复上报 / 时间戳回退 |
| TELEMETRY_VALIDATION_FAILED | 422 | 缺失/类型/范围（body 内含细分原因） |
| DESIRED_REJECTED | 422 | 期望字段不支持/只读/越界 |
| STATE_VERSION_CONFLICT | 409 | 期望版本 CAS 冲突 |
| FIRMWARE_INCOMPATIBLE | 422 | 型号/撤回/区间/同版本不兼容 |
| FIRMWARE_ALREADY_IN_PROGRESS / FIRMWARE_DUPLICATE_REQUEST / FIRMWARE_NO_ACTIVE_UPGRADE | 409 | 单飞、幂等、状态不符 |
| RULE_NOT_FOUND | 404 | 规则不存在 |
| RULE_VERSION_NOT_FOUND | 404 | 切换/回退/复看指定的版本未发布或已被裁剪 |
| RULE_SWITCH_FAILED | 409 | 切换在替换引用前失败，已保持旧版本稳定服务 |
| RULE_SWITCH_IN_PROGRESS | 429 | 已有切换在临界区，并发切换被立即拒绝 |
| RULE_TRIAL_LIMIT | 429 | 试跑样本数超限或并发试跑数占满 |
| INVALID_REQUEST | 400 | 参数与请求体非法（含 JSON 解析错误归一化） |
| INTERNAL_ERROR | 500 | 所有未预期底层异常的唯一对外出口 |

统一响应体：`{"code": "...", "message": "...", "time": "..."}`。

## 7. 验证方法

保持既有启动方式：

```bash
mvn spring-boot:run          # 启动（与原骨架一致）
mvn -o test                  # 离线运行全部测试（不依赖外部服务）
```

测试位于 `src/test/.../tests`，共 30 个用例，关键输入与版本选择均通过
SLF4J 打印（日志前缀 `[关键输入]` / `[判定依据]` / `[版本选择]`）：

- `SessionLifecycleTest`：未登记/未激活区分、幂等重入、接管失效、32 线程并发接入唯一会话；
- `CommandLifecycleTest`：离线缓冲上线投递、过期显式标记、接管不重复投递、失败标记；
- `TelemetryConsistencyTest`：重复上报、乱序/时钟回拨、三类字段拒绝原因、20 线程并发去重；
- `StateConvergenceTest`：整批拒绝无半更新、MATCHED/PENDING/REJECTED 收敛、并发 CAS；
- `FirmwareUpgradeTest`：兼容区间拒绝、幂等/单飞、成功切版本、失败与失联回滚、分批隔离；
- `RuleEngineTest`：规则版本绑定与更新后历史可区分、背压丢弃计数、毒消息隔离；
- `RuleGovernanceTest`：候选试跑逐条差异/只读/确定性重复一致；切换窗口内事件版本归属唯一
  （不重不漏不串）；切换失败、并发切换、试跑并发/样本超限均明确拒绝且旧版本稳定服务；
  回退历史版本后事件按回退版本处理；LIVE/REPLAY 历史复看结论与当时执行一致且可复现；
  试跑/切换/回退/复看/拒绝全部有审计留痕；
- `RuleGovernanceHttpTest`：试跑/候选/切换/回退/版本列表/复看/审计的 REST 全链路，
  以及 `RULE_VERSION_NOT_FOUND`(404)、`INVALID_REQUEST`(400) 等错误码-状态分类；
- `CapacityQuotaTest`：指令/遥测/设备数三类上限拒绝且无残留；
- `HttpApiTest`：REST 全链路与错误码-HTTP 状态分类稳定性（底层解析异常不透出）。

### 本地手工验证规则治理流程

```bash
# 1) 发布 v1（阈值30）并真实处理一条 35 的事件（命中）
curl -s -XPOST localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","metricField":"temperature","threshold":30,"action":"alarm","enabled":true}'
curl -s -XPOST localhost:8080/api/rules/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","deviceId":"d1","telemetrySeq":1,"metricField":"temperature","value":35}'

# 2) 候选（阈值40）对历史样本试跑：看到 changedCount 与逐条差异，线上不变
curl -s -XPOST localhost:8080/api/rules/trial -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","metricField":"temperature","threshold":40,"action":"alarm","enabled":true,
       "reason":"灰度评估","events":[{"eventId":"e1","deviceId":"d1","telemetrySeq":1,
       "metricField":"temperature","value":35}]}'

# 3) 发布候选 v2（暂不激活）-> 正式切换
curl -s -XPOST 'localhost:8080/api/rules/candidates?activate=false&reason=留档' \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","metricField":"temperature","threshold":40,"action":"alarm","enabled":true}'
curl -s -XPOST localhost:8080/api/rules/activate -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","version":2,"reason":"正式上调阈值"}'

# 4) 效果不对则回退到 v1，之后事件重新按 v1 命中
curl -s -XPOST localhost:8080/api/rules/rollback -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","version":1,"reason":"误报过多回退"}'

# 5) 指定历史版本复看：v1 复看 e1 -> LIVE；v2 复看 e1 -> REPLAY（按 v2 规则不命中）
curl -s -XPOST 'localhost:8080/api/rules/events/review?ruleVersion=1&ruleId=hot&reason=对账' \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","deviceId":"d1","telemetrySeq":1,"metricField":"temperature","value":35}'

# 6) 查看版本历史与操作留痕
curl -s localhost:8080/api/rules/hot/versions
curl -s localhost:8080/api/rules/audit
```

> 测试共享 Spring 上下文，用例间使用独立设备 ID 前缀隔离状态。
