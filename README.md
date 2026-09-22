# IoT 设备网关（本地可复现版）

一个**不依赖真实硬件、外部服务或真实凭据**即可在本机完整复现的物联网设备接入与设备管理系统。
真实设备由进程内的 `DeviceSimulator` 模拟，消息交互由 Spring Boot HTTP API + 本地内存存储承担。
核心目标：在**异常输入与高并发**下，会话、遥测、期望状态与固件升级的结论始终**一致、确定、可解释**。

- Java 21 / Spring Boot 3.4，仅使用常规开源依赖（spring-boot-starter-web / starter-test）。
- 存储全部为进程内内存结构，时间源可替换（`SettableClock`），测试无需真实 sleep、无随机抖动。

---

## 1. 构建与启动

```bash
# 构建（保持骨架原有启动方式）
mvn clean package

# 启动 Web 服务
mvn spring-boot:bootrun
# 或 java -jar target/iot-device-gateway-java-1.0-SNAPSHOT.jar
```

### 运行全部测试（23+ 用例，覆盖需求列出的全部异常/并发场景）

```bash
mvn test
```

测试日志会打印每个判定的**关键输入与判定依据**（设备 ID、序列号、时间戳、版本号、错误码、原因）。
所有测试使用 `SettableClock` 手动推进时间，不依赖外部服务与真实时间。

---

## 2. 模块总览

| 包 | 组件 | 职责 |
|---|---|---|
| `core` | `Clock`/`SystemClock`/`SettableClock`、`GatewayProperties`、`StripedLocks` | 时间、配置、按设备分条锁 |
| `error` | `ErrorCode`、`GatewayException`、`GlobalExceptionHandler` | 稳定错误分类与异常归一化 |
| `domain` | 设备/会话/指令/遥测/状态/固件/规则的值对象与枚举 | 不可变领域模型 |
| `session` | `DeviceRegistry`、`SessionManager`、`DeviceGateway` | 登记生命周期、会话接管、接入门面 |
| `command` | `CommandQueueService` | 离线缓冲、TTL 时效、恰好一次投递 |
| `telemetry` | `TelemetryService` | schema 校验、去重、乱序/回拨判定 |
| `state` | `StateService` | 版本化期望、乐观锁、收敛状态机 |
| `firmware` | `FirmwareService` | 目录、兼容判定、幂等升级、失败/失联回滚 |
| `rules` | `RuleEngineService`、`RuleEvaluator` | 版本化规则、有界积压、异常隔离、结论复现 |
| `capacity` | `CapacityMetrics` | 拒绝/背压/丢弃/过期等可观测计数 |
| `simulator` | `DeviceSimulator` | 本地模拟设备（代替真实硬件） |
| `web` | 各 `*Controller` | HTTP 接入协议 |

---

## 3. 接入协议（HTTP/JSON）

所有响应均为 JSON。错误统一为：

```json
{ "errorCode": "DEVICE_NOT_ACTIVATED", "message": "设备未激活: dev-1", "traceId": "..." }
```

底层异常（堆栈/SQL/NPE）绝不透出，仅返回稳定错误码与可解释信息。

### 3.1 设备生命周期与会话

| 方法 路径 | 说明 |
|---|---|
| `POST /api/devices` | 登记设备 `{deviceId, model}`；重复登记 `DEVICE_ALREADY_REGISTERED` |
| `POST /api/devices/{id}/activate` | 激活（REGISTERED→ACTIVE） |
| `POST /api/devices/{id}/disable` | 停用（→DISABLED） |
| `GET  /api/devices/{id}` | 查询档案；不存在 `DEVICE_NOT_FOUND` |
| `POST /api/devices/{id}/connect` | 接入，body 可带 `{sessionId}`（缺省服务端生成） |
| `POST /api/devices/{id}/disconnect` | 断开，必须带 `{sessionId}` |
| `POST /api/devices/{id}/commands` | 下发指令 `{type, payload, ttlMillis?}` |
| `GET  /api/devices/{id}/commands` | 查询该设备全部指令（含终态与原因） |
| `POST /api/devices/{id}/commands/{cid}/ack` | 回执 `{sessionId, success, reason}` |
| `POST /api/devices/{id}/commands/sweep-expired` | 显式扫描并标记过期指令 |

接入拒绝原因可区分：

- 设备未登记 → **404 `DEVICE_NOT_FOUND`**
- 已登记未激活 → **403 `DEVICE_NOT_ACTIVATED`**（message 指明"未激活"）
- 已停用 → **403 `DEVICE_NOT_ACTIVATED`**（message 指明"已停用"）
- 在线总数超上限 → **507 `CAPACITY_EXCEEDED`**

### 3.2 遥测

| 方法 路径 | 说明 |
|---|---|
| `POST /api/telemetry/schemas` | 注册字段 `{name, type(NUMBER/BOOLEAN/STRING), required, min, max}` |
| `POST /api/telemetry` | 上报 `{deviceId, sequence?, timestamp, metrics}` |
| `GET  /api/telemetry/{id}/latest` | 最新值 |

拒绝码：`TELEMETRY_FIELD_MISSING`(422，含未知字段) / `TELEMETRY_FIELD_TYPE_MISMATCH`(422)
/ `TELEMETRY_FIELD_OUT_OF_RANGE`(422) / `TELEMETRY_DUPLICATE`(409)
/ `TELEMETRY_STALE_TIMESTAMP`(409) / `TELEMETRY_CLOCK_ROLLBACK`(422)。

### 3.3 期望状态

| 方法 路径 | 说明 |
|---|---|
| `POST /api/state/{id}/desired` | 版本化下发 `{desired:{k:v}, expectedRevision, note?}` |
| `POST /api/state/{id}/report` | 设备回报 `{reported:{k:v}, rejectedKeys?, rejectionReasons?}` |
| `GET  /api/state/{id}/convergence` | 查询每键期望/回报/收敛结论 |

版本冲突 → **409 `STATE_VERSION_CONFLICT`**；设备无法满足 → 该键 `REJECTED`（带设备原因）。

### 3.4 固件

| 方法 路径 | 说明 |
|---|---|
| `POST /api/firmware` | 维护目录 `{version, model, compatibleFrom?, blocked?}` |
| `GET  /api/firmware/{model}` | 列出版本 |
| `POST /api/firmware/devices/{id}/upgrades` | 单设备升级 `{targetVersion, idempotencyKey?}` |
| `POST /api/firmware/batch-upgrades` | 分批升级 `{deviceIds, targetVersion, idempotencyKey?}`，逐设备独立判定 |
| `POST /api/firmware/upgrades/{uid}/result` | 设备回报 `?success=&reason=` 或 body |
| `POST /api/firmware/upgrades/sweep-timeouts` | 显式扫描失联升级并按策略回滚 |
| `GET  /api/firmware/devices/{id}/upgrades` | 升级履历（追加写，不删除） |

### 3.5 规则引擎

| 方法 路径 | 说明 |
|---|---|
| `POST /api/rules` | 新建/更新规则 `{ruleId, version(0=自动), field, operator, threshold, decision}` |
| `GET  /api/rules/{ruleId}` | 查询当前版本 |
| `POST /api/rules/events` | 提交事件进异步积压 |
| `POST /api/rules/events/process-now` | 同步立即求值 |
| `POST /api/rules/events/{eventId}/replay?ruleVersionOverride=` | 复现历史/指定版本结论 |
| `GET  /api/admin/metrics` | 容量、在线数、积压深度、拒绝/丢弃/过期/规则错误计数 |

---

## 4. 关键状态机

### 4.1 设备与会话

```
REGISTERED ──activate──▶ ACTIVE ──disable──▶ DISABLED
   │  接入被拒(NOT_ACTIVATED)    │  接入被拒(NOT_ACTIVATED)
   └────────(未登记)────────── DEVICE_NOT_FOUND

会话： 无 ──connect(sid)──▶ [sid 生效]
        connect(相同 sid)         → 幂等返回，不产生第二个会话
        connect(新 sid)           → 确定性接管；旧会话立刻失效
        disconnect(旧 sid)       → 空操作（不能踢掉接管方）
        disconnect(当前 sid)     → 回到无会话
不变量：任意时刻同一设备最多一个生效会话；在线计数与实际严格一致。
```

### 4.2 指令

```
QUEUED ─ 上线/在线投递且会话仍生效 ─▶ DELIVERED ─ ack(success=true) ─▶ ACKED
   │                                   │
   │超过 expireAt                      └─ ack(success=false) ─▶ FAILED(reason)
   ▼
EXPIRED(reason)            # 投递与回执都校验会话身份，旧会话操作 SESSION_CONFLICT
```

- 在线下发：直接对**当前生效会话** `DELIVERED`。
- 离线下发：进入每设备 FIFO（容量 `queue-capacity-per-device`）；满则 **503 `COMMAND_QUEUE_FULL`** 且不残留登记。
- （重新）上线：FIFO 逐条处理，未过期投递一次，过期置 `EXPIRED`；重复刷新不会重复投递。
- 回执必须携带投递时的 `sessionId`；会话已被接管则 **409 `SESSION_CONFLICT`**。重复回执幂等。

### 4.3 遥测判定顺序（整帧先校验、后提交，杜绝部分写入）

1. 设备存在且 ACTIVE；
2. schema 校验：必填缺失/未知字段 → MISSING；类型不符 → TYPE_MISMATCH；越界（含 NaN/无穷）→ OUT_OF_RANGE；
3. 序列号：相同 → `TELEMETRY_DUPLICATE`；倒退 → STALE；
4. 时间戳：相对历史最大值小幅倒退（≤`clock-rollback-tolerance-millis`）→ `TELEMETRY_STALE_TIMESTAMP`；
   大幅倒退 → `TELEMETRY_CLOCK_ROLLBACK`。旧帧绝不覆盖新值。

### 4.4 期望状态收敛

```
期望键：PENDING ──回报值==期望值──▶ SYNCED
            │──回报 rejectedKeys 含该键──▶ REJECTED(设备原因)
            └──该键被更新版本覆盖──▶ SUPERSEDED（历史保留）
已 SYNCED 的值若设备回报偏离 → 回到 PENDING（结论变化可解释）
```

- 下发采用 **expectedRevision 乐观锁**：基于旧版本的并发下发只有一个成功，其余 `STATE_VERSION_CONFLICT`，
  竞争失败者不写入任何键（零半更新）。
- 下发条目先在临时集合构建成功，再一次性提交。

### 4.5 固件升级

```
启动前校验链（有序，错误码稳定）：
设备存在→ACTIVE→目标版本非空→无进行中升级→目标固件存在→目标≠当前
   →目标未 blocked(FIRMWARE_VERSION_NOT_ALLOWED)
   →当前版本∈compatibleFrom(FIRMWARE_INCOMPATIBLE)

IN_PROGRESS ──成功回报──▶ SUCCESS（档案版本更新）
            ──失败回报──▶ FAILED（rollback-on-failure=true 时档案回滚到 fromVersion）
            ──超时未回报──▶ TIMED_OUT（按策略回滚）；迟到回报不得翻案
```

- `idempotencyKey` 幂等：重复触发返回**同一条**升级记录。
- 同一设备同时最多一条 `IN_PROGRESS`（分条锁 + 状态判定，已由 12 线程并发测试验证）。
- 分批升级逐设备独立判定，返回每台设备的记录或稳定失败分类。

### 4.6 规则版本与积压

- 规则保存全部版本；事件结论固化其**判定时的规则版本**，规则更新后历史结论不变（`replay(eventId,null)`）；
  指定 `ruleVersionOverride` 可按旧/新版本复算。
- 有界积压：`DISCARD_OLDEST`（淘汰队首，计 `eventBacklogDiscards`）、
  `DISCARD_NEWEST`（拒绝新事件，`BACKLOG_DISCARDED`）、`BLOCK`（`EVENT_QUEUE_FULL`）。
- 单条规则求值异常按事件计数（`ruleErrorCount` / `eventRuleErrors`），不影响其他规则，不杀死工作线程。

---

## 5. 兼容规则（固件）

`FirmwareInfo(model, version, compatibleFrom, blocked)`：

- `blocked=true`：任何来源版本均禁止下发 → `FIRMWARE_VERSION_NOT_ALLOWED`；
- `compatibleFrom=null`：允许任意旧版本升级；
- `compatibleFrom=["1.0",...]`：仅当设备当前版本在集合内才允许，否则 `FIRMWARE_INCOMPATIBLE`；
- 目标版本不存在 `FIRMWARE_NOT_FOUND`；目标版本等于当前版本按 `VALIDATION_FAILED` 拒绝（无意义升级）。

---

## 6. 配置项一览

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `iot.gateway.session.max-connections` | 10000 | 全局在线会话上限 |
| `iot.gateway.command.queue-capacity-per-device` | 100 | 单设备离线指令队列容量 |
| `iot.gateway.command.default-ttl-millis` | 300000 | 指令默认有效期 |
| `iot.gateway.telemetry.clock-rollback-tolerance-millis` | 60000 | 乱序/回拨判定窗口 |
| `iot.gateway.telemetry.history-per-device` | 200 | 遥测历史保留条数 |
| `iot.gateway.state.retained-revisions-per-device` | 50 | 期望版本保留数量 |
| `iot.gateway.firmware.upgrade-timeout-millis` | 120000 | 升级失联超时 |
| `iot.gateway.firmware.rollback-on-failure` | true | 失败/失联是否回滚 |
| `iot.gateway.rules.backlog-capacity` | 2000 | 规则事件积压容量 |
| `iot.gateway.rules.backlog-policy` | DISCARD_OLDEST | 满队列策略 |
| `iot.gateway.rules.worker-threads` | 1 | 规则处理线程数 |
| `iot.gateway.capacity.max-registered-devices` | 100000 | 登记设备总量上限 |

可观测计数（`GET /api/admin/metrics`）：
`registeredDevices, onlineSessions, ruleBacklogDepth, capacityRejections,
commandQueueRejections, eventBacklogDiscards, eventBacklogRejections,
eventRuleErrors, eventsProcessed, commandsExpired`。

---

## 7. 验证方法（测试与场景对照）

| 需求场景 | 测试类 |
|---|---|
| 未登记/未激活/停用可区分拒绝、重复接入幂等、接管确定 | `SessionCommandTest` |
| 离线缓冲、上线 FIFO 刷新、过期显式标记、恰好一次、旧会话拒认、队列超限无残留 | `SessionCommandTest` |
| 重复上报、乱序、时钟回拨、缺失/类型/范围拒绝、禁止以旧覆盖新 | `TelemetryTest` |
| 版本冲突、并发下发不丢更新、收敛、无法满足可解释、下发/回报交叉无半更新 | `StateServiceTest` |
| 兼容/禁止/未知/同版本、幂等、并发单进行中、失败回滚、失联超时、分批独立 | `FirmwareServiceTest` |
| 历史结论版本化可复现、规则更新冲突、DISCARD_OLDEST/NEWEST 背压、单规则异常隔离 | `RuleEngineTest` |
| 多线程接管单会话、指令+接管交叉无重复、全局设备容量 | `ConcurrencyIntegrationTest` |
| HTTP 错误分类稳定、底层异常不透出、诊断计数 | `WebApiTest` |
| 模拟设备全链路（离线指令→接管→回执→遥测→状态收敛→失败回滚→升级成功） | `SimulatorEndToEndTest` |

### 手工快速复现（启动服务后）

```bash
curl -s -XPOST localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"d1","model":"sensor-1"}'
curl -s -XPOST localhost:8080/api/devices/d1/activate
curl -s -XPOST localhost:8080/api/devices/d1/connect -H 'Content-Type: application/json' -d '{"sessionId":"s1"}'
curl -s -XPOST localhost:8080/api/devices/d1/commands -H 'Content-Type: application/json' \
  -d '{"type":"PING","payload":"hi","ttlMillis":10000}'
curl -s localhost:8080/api/devices/d1/commands
curl -s localhost:8080/api/admin/metrics
```

---

## 8. 并发与故障设计说明

- **锁模型**：每设备 256 道分条锁（`StripedLocks`），同设备操作串行、跨设备并行；
  会话替换同时依赖 `ConcurrentHashMap` 原子操作，保证"检查—替换"在设备锁内确定。
- **容量原子性**：在线/登记计数与入表在同一临界区，超限路径回滚已做登记，杜绝残留与越权状态。
- **无部分写入**：遥测整帧校验通过后才提交；期望状态先构建临时集合再一次性提交；指令入队失败会移除预登记记录。
- **资源不泄漏**：规则工作线程为守护线程并由 `@PreDestroy` 关闭；不使用无界队列/线程。
- **确定性**：所有时间判定走 `Clock`；超时扫描、指令过期扫描均为显式触发（不依赖调度器），测试可精确复现。
