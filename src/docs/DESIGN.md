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
rule       权威数据规则判定、结论绑定规则版本、背压丢弃与毒消息隔离
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
| POST | `/api/rules` | 新增/更新规则（版本自动递增） |
| POST | `/api/rules/events` | 提交权威事件 |
| GET  | `/api/rules/events/{eventId}/verdicts` | 查询结论（含规则版本） |
| POST | `/api/rules/events/replay?ruleVersion=` | 指定版本重放 |
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

## 4. 规则处理与可复现

- 规则以 `ruleId` 版本化，每次更新 version 自增；
- 每条结论 `RuleVerdict` 记录**判定时的规则版本**，规则更新后历史结论保持旧版本、
  仍可查询区分，`/replay` 提供按版本重放入口；
- 处理队列为有界队列（配置容量）。模拟积压时满队按**丢弃最旧**背压，
  丢弃数计入 `droppedBackpressure`，`GET /api/rules/stats` 可观测：
  `queueCapacity / queueDepth / submitted / processed / droppedBackpressure / failedPoison`；
- 单条处理异常（毒消息）被捕获计数为 `failedPoison`，后续事件继续处理，链路不被拖垮。

## 5. 配置项（`application.properties`，前缀 `iot.gateway`）

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `max-commands-per-device` | 100 | 每设备 PENDING 指令缓冲上限，满 → `COMMAND_QUEUE_FULL`(429) |
| `max-telemetry-per-device` | 1000 | 每设备遥测历史上限，满 → `CAPACITY_EXCEEDED`(429) |
| `rule-queue-capacity` | 200 | 规则处理队列容量，满 → 丢弃最旧并计数 |
| `max-events-per-device` | 500 | 每设备规则结论留存上限 |
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
| RULE_NOT_FOUND | 404 | 规则/版本不存在 |
| INVALID_REQUEST | 400 | 参数与请求体非法（含 JSON 解析错误归一化） |
| INTERNAL_ERROR | 500 | 所有未预期底层异常的唯一对外出口 |

统一响应体：`{"code": "...", "message": "...", "time": "..."}`。

## 7. 验证方法

保持既有启动方式：

```bash
mvn spring-boot:run          # 启动（与原骨架一致）
mvn -o test                  # 离线运行全部测试（不依赖外部服务）
```

测试位于 `src/test/.../tests`，共 24 个用例，关键输入与判定依据均通过
SLF4J 打印（日志前缀 `[关键输入]` / `[判定依据]`）：

- `SessionLifecycleTest`：未登记/未激活区分、幂等重入、接管失效、32 线程并发接入唯一会话；
- `CommandLifecycleTest`：离线缓冲上线投递、过期显式标记、接管不重复投递、失败标记；
- `TelemetryConsistencyTest`：重复上报、乱序/时钟回拨、三类字段拒绝原因、20 线程并发去重；
- `StateConvergenceTest`：整批拒绝无半更新、MATCHED/PENDING/REJECTED 收敛、并发 CAS；
- `FirmwareUpgradeTest`：兼容区间拒绝、幂等/单飞、成功切版本、失败与失联回滚、分批隔离；
- `RuleEngineTest`：规则版本绑定与更新后历史可区分、背压丢弃计数、毒消息隔离；
- `CapacityQuotaTest`：指令/遥测/设备数三类上限拒绝且无残留；
- `HttpApiTest`：REST 全链路与错误码-HTTP 状态分类稳定性（底层解析异常不透出）。

> 测试共享 Spring 上下文，用例间使用独立设备 ID 前缀隔离状态。
