# FEAT-039 covered call 股票覆盖检查与锁定

Q77–Q79、Q101–Q103 均已定（A）。本节只写**新增或不同**的部分；所有现有接口、字段、文案不变，除非下表标为"行为变化"。示例标的均为虚构。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades`（STO call） | — | 新拒绝 `COVERAGE_INSUFFICIENT`（E1、E3"补录"）。FEAT-038 的"不检查股票覆盖"取消 |
| `PUT /api/option-trades/{id}` | — | STO call 改张数 / 时间 / multiplier → E1、E3"修改"；BTC 改时间、开仓（任意）改动导致历史不满足 → E3"修改"。旧批次通过检查后变为受检 |
| `DELETE /api/option-trades/{id}` | — | 删除 BTC、`EXP`、put `ASG` → 可能 E3"删除"。删除 STO call、call `ASG` 不会因覆盖被拒 |
| `POST /api/option-trades/assignments` | — | call 被指派涉及旧批次 → 可能 E3"被指派" |
| `POST /api/option-trades/closes`、`.../expirations` | — | 无（只会释放股票）。**FEAT-051**：BTC 释放的是它所选批次中受检批次的股数（`LEGACY` 批次释放 0，Q79）；修改平仓的所选批次（`PUT` 带 `lotTradeIds`）会改变释放的股数，可能 E3"修改"。见 [FEAT-051.md](FEAT-051.md) |
| `POST` / `PUT` / `DELETE /api/stock-trades/**` | — | `SELL` 新增 / 修改 → E2；任何新增 / 修改 / 删除 → 可能 E3 |
| `GET /api/option-positions` | 空头 call 行、批次的覆盖字段 | — |
| `GET /api/stock-positions` | `coveredShares`、`availableShares` | — |
| 审计 `sideEffects[]` | `field` 新取值 `coverage` | — |
| 测试支持（`e2e` profile） | `POST /api/test-support/legacy-option-trades` | — |
| 现金、交易列表、已平仓、盈亏汇总、首页、流水 | — | — |

录入页 STO + Call 提示（Q61 新文案）和持仓页标签 / 悬停文字（Q62 新文案）由前端按需求规则 7 拼出，后端不返回这些文字。

#### 概念（按账户、按标的；时点按 `(时间, 录入顺序)`，Q19）
- **覆盖状态**：每个 STO call 批次（期权交易）有覆盖状态 `CHECKED`（受检）或 `LEGACY`（旧批次）。FEAT-039 之后经 `POST` 录入的 STO call 都是 `CHECKED`；上线迁移时已有的 STO call 都标 `LEGACY` 再立即评估一次（见"自动纳入"）。其它交易无此状态。`LEGACY` 变 `CHECKED` 是单向的。
- **需要股数**（批次）= 张数 × multiplier（整批）。
- **持股**(t)：该标的股票记录（`BUY` / `TRANSFER_IN` / `ASSIGN_BUY` 加，`SELL` / `ASSIGN_SELL` 减）重放到 t。转入记在当天 00:00（Q86）。
- **被覆盖股数**(t) = t 时仍未平的 `CHECKED` 批次需要股数之和。批次在其 BTC 时间、`EXP` 时间（到期日 16:00）、`ASG` 时间起不再需要股票；过期未处理的仍需要（Q34）。
- **可用股数**(t) = 持股(t) − 被覆盖股数(t)。`LEGACY` 批次不占用股票。
- **不变式**：每个时点 持股 ≥ 被覆盖股数。
- 价差 / iron condor 的空头 call（Q102）、指数期权 call（Q103）同样要求股票，没有特殊处理。

#### 检查与拒绝
所有拒绝都是 `422 COVERAGE_INSUFFICIENT`，`details` 为 `[]`，`message` 中文可直接显示：股数为不带千分位的整数，时间为美东 `YYYY-MM-DD HH:mm`。被拒时什么都不保存、不写审计。前端按 `code` 处理时只需显示 `message`。

| 文案 | 格式（`{C} = 0` 时去掉 `，其中 … call`） | 何时 |
|------|------|------|
| **E1** | `需要 {N} 股 {标的}，可用 {A} 股（持有 {H} 股，其中 {C} 股已覆盖其它 call）` | 新增 STO call；修改 STO call 的 `contracts` / `tradedAtLocal` / `multiplier`（含 `LEGACY` 批次）。在本笔成交时点：`A` = 可用股数（不含本笔），`N` = 本笔需要股数，`H` = 持股，`C` = 被覆盖股数（不含本笔）。要求 `A ≥ N` |
| **E2** | `卖出 {N} 股超过可用股数 {A} 股（持有 {H} 股，其中 {C} 股被 call 覆盖），请先平掉对应的 call` | 新增 / 修改股票 `SELL`。在本笔成交时点（本笔之前）：要求 `N ≤ A` |
| **E3** | `{补录\|修改\|删除\|被指派}后 {时间} 起 {标的} 不足以覆盖卖出的 call：需要 {R} 股，持有 {H} 股` | 写操作之后从受影响时点起重放，第一个 持股 < 被覆盖股数 的时点；`R` = 当时被覆盖股数，`H` = 当时持股。前缀：新增记录 = `补录`，`PUT` = `修改`，`DELETE` = `删除`，`POST /assignments` = `被指派` |

E1 / E2 只检查本笔时点；本笔时点通过后，再用 E3 检查之后的时点。例：在场景 D 上补录 `SELL` 100 股（2026-11-03 12:00）：本笔时点可用 100（C2 还没开）→ E2 通过；2026-11-04 10:00 起 需要 200 / 持有 100 → `补录后 2026-11-04 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股`。

**在各接口检查顺序中的位置**（插在现有步骤之间；"持股"指 FEAT-011 / FEAT-041 的 `SHARES_INSUFFICIENT`）：

| 接口 | 顺序 |
|------|------|
| `POST /api/option-trades`（STO call） | 字段 → Q7 → Q23 → **E1 → E3（补录）** → 现金 |
| `PUT /api/option-trades/{id}` | 现有（字段 → 404 → 没有修改 → 合约重放 → 股票成本 / 卖出实收 → 持股）→ **E1（仅 STO call 改了张数 / 时间 / multiplier）→ E3（修改）** → 现金。只改 `netAmount` / `note` 时不做 E1；E3 仍跑但不会失败 |
| `DELETE /api/option-trades/{id}` | 现有 → **E3（删除）** → 现金 |
| `POST /api/option-trades/assignments` | 现有 1–8（含 call 的 Q75 持股）→ **E3（被指派）** → 现金 |
| `POST /api/stock-trades` | 字段 → 持股（本笔、之后的卖出）→ **E2（`SELL`）→ E3（补录）** → 现金 |
| `PUT /api/stock-trades/{id}` | … → 没有修改 → 持股 → **E2（被修改的是 `SELL`）→ E3（修改）** → 现金 |
| `DELETE /api/stock-trades/{id}` | 持股 → **E3（删除）** → 现金 |

示例：
```json
{
  "code": "COVERAGE_INSUFFICIENT",
  "message": "需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）",
  "status": 422,
  "path": "/api/option-trades",
  "timestamp": "2026-11-13T15:00:00Z",
  "details": []
}
```
其它文案示例：`需要 100 股 IXQZ，可用 0 股（持有 0 股）`；`卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call`；`删除后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股`；`被指派后 2026-11-20 16:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股`。

#### 旧批次与自动纳入（Q79、Q101）
- `LEGACY` 批次不参与 E1 / E2 / E3 的被覆盖股数，所以不挡与它无关的操作。
- `PUT` 改 `LEGACY` 批次的张数 / 时间 / multiplier：把它当 `CHECKED` 做 E1、E3；通过则保存为 `CHECKED`。只改 `netAmount` / `note`：不检查，仍为 `LEGACY`（之后的自动纳入照常评估）。
- **自动纳入**：应用启动时（对每个账户），以及每次写操作成功后（对该账户、同一事务内），按 `(开仓时间, 交易 id)` 逐个评估**当前仍未平**的 `LEGACY` 批次：把它（连同之前已纳入的）当 `CHECKED` 后，若从它开仓起每个时点都满足不变式 → 改为 `CHECKED`；否则保持 `LEGACY`。已平掉（BTC / `EXP` / `ASG`）的 `LEGACY` 批次永不纳入。
- 审计：由 `PUT` / `DELETE` 引起的纳入（包括被修改的那笔自身由 `LEGACY` 变 `CHECKED`）写进该次审计的 `sideEffects`，每项 `tradeId`（STO id）、`action` `"STO"`、`contractLabel`、`field` **`coverage`**、`before` `"未覆盖"`、`after` `"已覆盖"`、`text` `STO #5 纳入覆盖检查`。新增记录（不写审计，Q42）和启动时的纳入不写审计。
- 不改纳入批次的 `hasAuditLog`。

#### `GET /api/option-positions`（新增字段，兼容）
`positions[]` 新字段（空头 call 行有值，其它行全部 `null`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `coveredShares` | number \| null | 该行 `CHECKED` 批次需要股数之和（标签 `已覆盖 {N} 股`）；没有受检批次时 `0` |
| `uncoveredContracts` | number \| null | 该行 `LEGACY` 批次剩余张数之和（标签 `未覆盖`，有受检批次同时存在时 `未覆盖 {k} 张`）；没有时 `0` |
| `uncoveredShares` | number \| null | `LEGACY` 批次需要股数之和（悬停 `需要 {R} 股`） |
| `availableShares` | number \| null | 该标的**当前**可用股数（悬停 `当前可用 {A} 股`；可为 0） |
| `uncoveredShortfallAt` / `uncoveredShortfallAtLocal` | string \| null | 该行各 `LEGACY` 批次中最早的不满足时点（UTC Instant / 美东本地 `YYYY-MM-DDTHH:mm:ss`）；没有 `LEGACY` 批次时 `null` |
| `uncoveredShortfallHeldShares` | number \| null | 上述时点的持股（悬停 `{时间} 时持有 {H} 股`） |

`lots[]` 新字段（空头 call 批次有值，其它 `null`）：`coverage`（`"CHECKED"` / `"LEGACY"`）、`requiredShares`（= `remainingContracts × multiplier`）、`shortfallAt` / `shortfallAtLocal` / `shortfallHeldShares`（仅 `LEGACY`：按自动纳入规则评估时的第一个不满足时点和当时持股；`CHECKED` 为 `null`）。

现有字段不变：空头 call 的 `frozenCash` 仍为 `"0.00"`、`underlyingShares` 仍为当前持股；`frozenTotal` 不变。前端不再显示"未冻结"。

例（场景 L 第 6 条之后，时钟 2026-11-13，10 C 行，只列相关字段）：
```json
{
  "contractLabel": "IXQZ 2026-12-18 10 C", "direction": "SHORT", "contracts": 2, "multiplier": 100,
  "frozenCash": "0.00", "underlyingShares": 100,
  "coveredShares": 0, "uncoveredContracts": 2, "uncoveredShares": 200, "availableShares": 0,
  "uncoveredShortfallAt": "2026-11-03T15:00:00Z", "uncoveredShortfallAtLocal": "2026-11-03T10:00:00",
  "uncoveredShortfallHeldShares": 0,
  "lots": [
    { "tradeId": 1, "action": "STO", "remainingContracts": 2, "coverage": "LEGACY", "requiredShares": 200,
      "shortfallAt": "2026-11-03T15:00:00Z", "shortfallAtLocal": "2026-11-03T10:00:00", "shortfallHeldShares": 0 }
  ]
}
```
场景 D 的 C1 行：`"coveredShares": 100, "uncoveredContracts": 0, "uncoveredShares": 0, "availableShares": 0, "uncoveredShortfallAt": null, …`，批次 `"coverage": "CHECKED", "requiredShares": 100, "shortfallAt": null`。

#### `GET /api/stock-positions`（新增字段，兼容）
`positions[]` 新字段：`coveredShares`（number，当前被覆盖股数）、`availableShares`（number，= `shares − coveredShares`，≥ 0）。仍只列持股 > 0 的标的。

```json
{ "symbol": "IXQZ", "shares": 200, "coveredShares": 200, "availableShares": 0, "totalCost": "1600.00", "averageCost": "8.0000", "lotCount": 1, "lots": [ "…不变…" ] }
```

#### 测试支持：`POST /api/test-support/legacy-option-trades`（仅 `e2e` profile）
模拟"上线前录入"的 STO call（场景 L）。与业务接口一样需要请求头 `X-Account-Id`（写入该账户）。请求体、表示同 `POST /api/option-trades`，要求 `action = STO`、`optionType = CALL`（否则 400 `VALIDATION_ERROR`，field `action`）；做该接口除 E1 / E3 外的全部检查，保存为 `LEGACY`，然后按"自动纳入"评估一次（同上线时）。响应 `201 Created`，同 `POST /api/option-trades`。其它 profile → 404。

#### 数据库迁移 `V11__call_coverage.sql`
- `option_trade` 新列 `coverage_status VARCHAR(8) NULL`，约束 `ck_option_trade_coverage`：`action = 'STO' AND option_type = 'CALL'` ⇔ 值 ∈ (`'CHECKED'`, `'LEGACY'`)，否则 `NULL`。
- `option_trade`、`stock_trade` 新列 `entry_seq BIGINT NOT NULL`（默认取序列 `entry_seq_gen` 的下一个值）：两张表共用的录入顺序，用于同一时间的股票记录与期权记录按录入顺序重放（Q19）。已有行按 `(created_at, 期权在前, id)` 回填为负数（排在所有新记录之前）。不出现在任何 API 里。
- 迁移把已有 STO call 行设为 `'LEGACY'`；不改其它任何数据。应用启动后（迁移之后）对每个账户执行一次自动纳入，不写审计。迁移前按 Q60 自动备份（已有机制）。
- 测试资源 `backend/src/test/resources/db/failing-migration/V11__simulated_failure.sql` 改名为 `V12__simulated_failure.sql`（并改 `MigrationFromM1Test` 中的引用）。

#### 已知限制
- 不能指定哪批股票覆盖、哪批交出（交出仍按股票 FIFO，FEAT-041）。
- 价差保护、指数期权不特殊处理（Q102、Q103），以后在 M4 / Q11 重新讨论。
- 主动行权 put 要检查可用股数（Q113 取代此前的设想），见 FEAT-014 第 9–10 步。
- 拆股见 FEAT-028。
