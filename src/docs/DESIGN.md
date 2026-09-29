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
rule       权威数据规则判定、结论绑定规则版本、候选历史试跑、原子切换/回退、历史版本复看与留痕、背压与毒消息隔离
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
| POST | `/api/rules` | 新增/更新规则（版本自动递增，putRule 后立即生效，兼容旧用法） |
| POST | `/api/rules/candidates` | 准备候选版本（只登记不启用，不影响实时判定） |
| POST | `/api/rules/trials` | 候选规则历史试跑对比（当前版本 vs 候选，逐事件差异+汇总） |
| POST | `/api/rules/activate` | 正式启用指定已发布版本（切换与实时判定互斥） |
| POST | `/api/rules/rollback` | 回退到任意已发布历史版本 |
| POST | `/api/rules/events` | 提交权威事件（同 eventId 重复提交幂等） |
| POST | `/api/rules/events/review?ruleId=&ruleVersion=` | 按指定历史版本复看事件（现场重算） |
| GET  | `/api/rules/events/{eventId}/verdicts` | 查询落地结论（含规则版本） |
| POST | `/api/rules/events/replay?ruleVersion=` | 指定版本重放（用历史版本定义重算，不落库） |
| GET  | `/api/rules/{ruleId}/versions` | 某规则全部已发布版本（含候选，按版本号升序） |
| GET  | `/api/rules/audit?ruleId=` | 治理留痕：试跑/切换/回退/复看（可按规则过滤） |
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

## 4. 规则试跑、版本切换、回退与历史对账

规则以 `ruleId` 版本化：每个版本定义（字段/阈值/动作/启停）**不可变**且全量有界保留，
实时判定只认“当前生效版本”的引用快照。在此之上提供四套互相独立、各自留痕的能力。

### 4.1 候选规则历史试跑（只对比，零副作用）

1. `POST /api/rules/candidates` 准备候选版本：只登记为“待启用”，版本号在该规则序列上
   单调递增，**不改变当前生效版本**；候选的 `metricField` 必须与已发布规则一致。
2. `POST /api/rules/trials` 圈定样本试跑，两种样本来源二选一：
   - `eventIds`：圈选一段**已留存的历史事件**（实时处理时按有界窗口留存）；
   - `samples`：直接内联一批样本事件。
   每条样本分别用**当前生效版本**与**候选版本**做纯函数判定，逐条给出
   `baselineFired / candidateFired / changed / reason`（新增命中/不再命中/命中动作变化/结论一致），
   汇总 `sampleCount / changedCount`，发布前即可看清整体影响面。
3. 试跑是**纯函数对比**：不写结论、不入处理队列、不改变 `stats()` 任何指标、
   不影响正在进行的实时处理；结果不含 `evaluatedAt` 等不稳定字段，
   **同一批样本配同一组规则重复试跑，结果逐字段一致**（内联样本与等价历史事件结果也一致）。
4. 候选版本也可指定为任意已发布版本号（`candidateVersion`）做“版本间对比”。

### 4.2 正式切换与实时事件处理并存（边界清晰、失败不留半生效）

- `POST /api/rules/activate` 整体替换该规则的“生效版本”引用。切换（activate/rollback）
  与单条事件的“取版本快照 + 判定”在同一把**切换锁**上互斥：
  一条事件只可能整体按旧版本、或整体按新版本判定，**不会重复、漏掉或跨版本半截判定**；
- 事件提交以 `eventId` 幂等去重，重复提交直接返回首次权威结论，不重复计数；
- **并发切换的三种结果，语义互斥、各自留痕，调用方按结果即可解释全过程**：
  - **被拒绝（REJECTED，可重试，不等于整体失败）**：切换请求到达时切换锁正被一次实时
    判定或另一次切换持有，`tryLock` 立即失败 → 返回 `RULE_SWITCH_CONFLICT`(409)。
    拒绝路径**不触碰任何版本状态**：生效引用、已落地结论、处理计数全部不变，系统继续按
    原版本稳定服务；允许调用方按约定退避重试。一次或多次合法拒绝**不计入任何失败指标**，
    也不影响后续重试成功后的事件版本归属。
  - **重试成功（SUCCESS）**：重试在锁空闲时进入临界区，锁内重新读取当前生效版本后整体替换
    引用。成功只有一次，重试期间无论撞车多少次，成功切换的迁移路径固定为
    “拒绝时真正生效的版本 → 目标版本”，不存在被拒后拿旧引用盲目覆盖新引用的半生效窗口。
  - **目标即当前版本（REJECTED，无操作）**：典型场景是并发的另一次切换已经切到该版本，
    本次请求或重试落到同版本——幂等拒绝，不移动引用、不追加成功记录。
- 拒绝与成功的留痕分开可追溯（见 4.5），留痕的 `detail` 带稳定机读前缀，不随措辞/时序变化：
  `REJECT_BUSY`（繁忙拒绝、可重试）、`REJECT_SAME`（目标即当前版本）、
  `REJECT_MISSING`（目标版本不存在）；拒绝记录的 `fromVersion` 是**拒绝那一刻真正生效**
  的版本（在拒绝现场重新读取，而非请求开始时的旧值）。
- 其它明确拒绝、继续按旧版本稳定服务的情形（均不产生半生效状态）：
  - 目标版本不存在 → `RULE_NOT_FOUND`(404)；
  - 容量上限打满（规则总数 / 单规则历史版本数）→ `QUOTA_EXCEEDED` / `CAPACITY_EXCEEDED`(429)，
    拒绝登记新版本并回滚内部版本号分配，不留半成品，已有规则照常服务；
  - 既有有界队列背压策略不变：队列满丢弃最旧并计数，与版本切换正交。

### 4.3 回退到任意历史版本

`POST /api/rules/rollback` 可切回**任意一个已发布过的历史版本**（不是只能回退一版），
回退不新建版本、只移动生效引用；成功后实时事件即按回退后版本处理。回退与启用共用同一套
互斥与拒绝语义，同样全程留痕。

### 4.4 按历史版本复看（结论必须与当时真实执行一致）

`POST /api/rules/events/review?ruleId=&ruleVersion=` 用**指定版本的不可变定义现场重算**
事件，返回：

- `replayed`：该版本下的结论（fired/action）；
- `matched` / `basis`：字段是否适用，以及判定依据（字段、阈值、事件值、比较式、启停）；
- `original`：该事件**实际落地**的历史结论（绑定其当时版本），不会被复看篡改。

因此旧事件在新版本下复看不命中、在旧版本下复看命中都能如实区分，
**绝不会把新规则算出的结果挂到旧版本名下**；重复复看结论与依据完全一致。
`/replay` 同样改为取该历史版本定义重算（不写库）。

### 4.5 留痕（可审计）

每次试跑、切换、回退、复看都产生一条 `RuleAuditEntry`（有界、全局有序）：
`seq / action(TRIAL|ACTIVATE|ROLLBACK|REVIEW) / ruleId / fromVersion / toVersion /
reason / outcome(SUCCESS|REJECTED) / detail / at`，经 `GET /api/rules/audit` 查询，
被拒绝的操作同样留痕，可查清时间、原因、版本与结果。

**成功记录与拒绝记录必须分开追溯**：按 `outcome` 过滤即可把“真正生效的一次切换”与
“被拒/无操作的尝试”分开，对账时只以 SUCCESS 记录确认真实迁移路径，REJECTED 记录逐条
解释并发时序；拒绝 `detail` 的稳定前缀 `REJECT_BUSY / REJECT_SAME / REJECT_MISSING`
可直接用于机读分类与按拒绝原因分组统计，拒绝条数本身随并发时序变化属正常现象，
不参与最终生效版本与事件归属的判定。

### 4.6 兼容范围

- `POST /api/rules`（putRule）语义保持：立即生效、版本递增，旧调用方与旧用例无需改动；
- 候选/切换/回退/复看/审计均为**新增端点**；`RuleVerdict`、`EventRecord` 结构不变；
- 首版本必须仍由 `POST /api/rules` 创建；之后才允许准备候选；
- 同一 `metricField` 同时只允许一个生效规则的既有约束保持不变，候选不得改字段名；
- 实时处理仍为有界队列串行模型，毒消息隔离、丢弃最旧背压、按设备有界留存均不变。

处理队列为有界队列（配置容量）。模拟积压时满队按**丢弃最旧**背压，
丢弃数计入 `droppedBackpressure`，`GET /api/rules/stats` 可观测：
`queueCapacity / queueDepth / submitted / processed / droppedBackpressure / failedPoison`；
单条处理异常（毒消息）被捕获计数为 `failedPoison`，后续事件继续处理，链路不被拖垮。

## 5. 配置项（`application.properties`，前缀 `iot.gateway`）

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `max-commands-per-device` | 100 | 每设备 PENDING 指令缓冲上限，满 → `COMMAND_QUEUE_FULL`(429) |
| `max-telemetry-per-device` | 1000 | 每设备遥测历史上限，满 → `CAPACITY_EXCEEDED`(429) |
| `rule-queue-capacity` | 200 | 规则处理队列容量，满 → 丢弃最旧并计数 |
| `max-events-per-device` | 500 | 每设备规则结论留存上限 |
| `command-ttl-seconds` | 300 | 离线指令默认有效期 |
| `max-devices` | 1000 | 设备登记总数上限，满 → `QUOTA_EXCEEDED`(429) |
| `max-rule-versions-per-rule` | 50 | 单规则历史版本（含候选）上限，满 → `CAPACITY_EXCEEDED`(429) |
| `max-rules` | 200 | 规则总数上限，新增规则满 → `QUOTA_EXCEEDED`(429) |
| `max-audit-entries` | 2000 | 治理审计留痕条数上限，超出淘汰最旧 |
| `max-stored-events` | 5000 | 实时事件留存上限（试跑圈选/复看取数），超出淘汰最旧 |

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
| RULE_NOT_FOUND | 404 | 规则/历史版本/留存历史事件不存在 |
| RULE_SWITCH_CONFLICT | 409 | 切换繁忙被拒（REJECT_BUSY，旧版本继续服务、可重试）、目标即当前版本（REJECT_SAME，无操作） |
| INVALID_REQUEST | 400 | 参数与请求体非法（含 JSON 解析错误归一化） |
| INTERNAL_ERROR | 500 | 所有未预期底层异常的唯一对外出口 |

统一响应体：`{"code": "...", "message": "...", "time": "..."}`。

## 7. 验证方法

保持既有启动方式：

```bash
mvn spring-boot:run          # 启动（与原骨架一致）
mvn -o test                  # 离线运行全部测试（不依赖外部服务）
```

**并发切换反复验证（本地复现“偶发失败已消除”）**：

```bash
# 单轮：实时处理与两次切换并发 + 被拒后重试成功的确定性交织
mvn -o test -Dtest='RuleGovernanceTest#concurrentSwitchAssignsEveryEventToExactlyOneVersionWithoutSplitOrLoss+rejectedSwitchKeepsOldVersionAndRetryAfterBusyPeriodSucceedsDeterministically'
# 连跑 25 轮，全部必须稳定通过（拒绝条数随时序可变，但对账不变量逐轮成立）
for i in $(seq 1 25); do
  mvn -q -o test \
    -Dtest='RuleGovernanceTest#concurrentSwitchAssignsEveryEventToExactlyOneVersionWithoutSplitOrLoss+rejectedSwitchKeepsOldVersionAndRetryAfterBusyPeriodSucceedsDeterministically'
done
```

判读方式：运行期日志可见 `REJECT_BUSY` 的繁忙拒绝与重试，**拒绝出现与否、出现多少次都不影响
最终结果**；每轮结束后的对账（事件唯一归属、最终生效 v3、SUCCESS 恰好两次且迁移路径
1→2、2→3、统计差值 submitted=processed）必须逐轮一致。

测试位于 `src/test/.../tests`，共 29 个用例，关键输入与判定依据均通过
SLF4J 打印（日志前缀 `[关键输入]` / `[判定依据]` / `[版本选择]`）：

- `SessionLifecycleTest`：未登记/未激活区分、幂等重入、接管失效、32 线程并发接入唯一会话；
- `CommandLifecycleTest`：离线缓冲上线投递、过期显式标记、接管不重复投递、失败标记；
- `TelemetryConsistencyTest`：重复上报、乱序/时钟回拨、三类字段拒绝原因、20 线程并发去重；
- `StateConvergenceTest`：整批拒绝无半更新、MATCHED/PENDING/REJECTED 收敛、并发 CAS；
- `FirmwareUpgradeTest`：兼容区间拒绝、幂等/单飞、成功切版本、失败与失联回滚、分批隔离；
- `RuleEngineTest`：规则版本绑定与更新后历史可区分、背压丢弃计数、毒消息隔离；
- `RuleGovernanceTest`（共 5 用例）：
  - 候选 vs 当前逐条试跑对比、同批同版本重复试跑结果一致、试跑零副作用（计数/生效版本不变）、
    内联样本与历史事件圈选等价；
  - 12 线程并发提交与两次版本切换并行，480 条事件逐条对账“恰好一次、归属版本与结论自洽、
    不漏不串”；该用例以“阶段闸门 + 判定点计数”编排：先确认确有事件在对应版本的切换临界区
    内开始判定再发起切换（真实制造切换撞车、可能被 BUSY 拒绝并重试），再在切换成功后开闸
    放行下一阶段事件，最终对账只断言对**任意并发交织都成立**的不变量（逐条结论自洽、
    成功切换恰好 1→2、2→3 两次、拒绝全部 REJECT_BUSY 且版本/时序可解释、统计 submitted=
    processed=480 无丢弃无毒消息），不依赖“某线程恰好跑到某个序号”这类随负载漂移的偶然条件，
    因此同一组用例连跑多轮结果稳定；
  - 判定占用切换锁时切换被明确拒绝（`RULE_SWITCH_CONFLICT`）、旧版本继续服务、
    不存在版本/重复切换拒绝、历史版本容量打满拒绝且无半成品、拒绝操作留痕；
  - **确定性交织用例（本次新增）**：用屏障精确编排“实时判定占锁 → 两个切换尝试并发被拒
    （REJECT_BUSY，且拒后生效版本仍是 v1）→ 判定结束 → 重试 v2 成功、事件归 v2 → 再切 v3
    成功、事件归 v3 → 重复切换当前版本被 REJECT_SAME 拒绝”全过程，不依赖 tryLock 撞运气；
    逐事件断言唯一归属与 fired 自洽、成功/繁忙拒绝/同版本拒绝三类留痕的条数版本对与先后顺序
    全部确定、拒绝不污染统计（3 提交 3 处理）；
  - 回退到 v1 后新事件按 v1 判定，按 v1/v2 交叉复看旧事件，结论严格等于对应版本当时执行、
    落地结论不被篡改，回退/复看留痕可查；
- `CapacityQuotaTest`：指令/遥测/设备数三类上限拒绝且无残留；
- `HttpApiTest`：REST 全链路与错误码-HTTP 状态分类稳定性（底层解析异常不透出）。

本地手工验证（无需真实硬件/外部服务）：

```bash
# 1) 创建首版本（立即生效，阈值 30）
curl -s -XPOST localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","metricField":"temperature","threshold":30,"action":"alarm"}'
# 2) 准备候选版本（阈值 40，不影响线上）
curl -s -XPOST localhost:8080/api/rules/candidates -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","metricField":"temperature","threshold":40,"action":"alarm","reason":"候选v2"}'
# 3) 用内联样本（或 eventIds 圈选历史事件）试跑对比
curl -s -XPOST localhost:8080/api/rules/trials -H 'Content-Type: application/json' \
  -d '{"ruleId":"hot","samples":[{"eventId":"s1","deviceId":"d","telemetrySeq":1,"metricField":"temperature","value":35}],"reason":"发布前评估"}'
# 4) 正式启用 / 回退
curl -s -XPOST localhost:8080/api/rules/activate  -H 'Content-Type: application/json' -d '{"ruleId":"hot","version":2,"reason":"试跑通过"}'
curl -s -XPOST localhost:8080/api/rules/rollback  -H 'Content-Type: application/json' -d '{"ruleId":"hot","version":1,"reason":"回退"}'
# 5) 按历史版本复看旧事件；6) 查版本与留痕
curl -s -XPOST 'localhost:8080/api/rules/events/review?ruleId=hot&ruleVersion=1' -H 'Content-Type: application/json' \
  -d '{"eventId":"s1","deviceId":"d","telemetrySeq":1,"metricField":"temperature","value":35}'
curl -s localhost:8080/api/rules/hot/versions
curl -s 'localhost:8080/api/rules/audit?ruleId=hot'
```

> 测试共享 Spring 上下文，用例间使用独立设备 ID 前缀隔离状态。
