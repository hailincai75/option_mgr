# FEAT-014 主动行权（多头期权，`EXR`）

沿用 FEAT-013 / FEAT-041 的全部做法（记录形式"期权记录 + 生成的股票记录 + 一条现金流水，同生同灭"、日期规则、整笔批次、合约 / 股票 / 现金重放、不可修改、删除、按账户隔离、`hasAuditLog` 恒为 `false`），把"被指派"换成"行权"、"空头"换成"多头"。本节只写**新增或不同**的部分。示例标的均为虚构。

**兼容性一览**（所有接口仍要求 `X-Account-Id`，只读写该账户；用另一账户的 id → 404 `NOT_FOUND`）：

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades/exercises` | 新接口 | — |
| 期权交易表示（`GET /api/option-trades`、`GET /api/option-trades/{id}`、`POST` / `PUT` 的响应） | `action` 新取值 `EXR`；新字段 `exercise`（object \| null） | `EXR` 行 `netAmount` `"0.00"`，`cashChange` 为 call −(买股金额 + 行权费) / put +(行权金额 − 行权费) |
| `GET /api/option-trades` | `action` 筛选接受 `EXR` | `action` 无法解析时文案改为 `方向只能是 BTO、STO、BTC、STC、EXP、ASG、EXR` |
| `PUT /api/option-trades/{id}` | — | 对 `EXR` → 422 `EXERCISE_NOT_EDITABLE` |
| `DELETE /api/option-trades/{id}` | — | 对 `EXR`：连带删除生成的股票记录和流水，做合约 / 股票 / 覆盖 / 现金重放 |
| 补录 / 修改 / 删除期权交易与股票记录 | 审计 `sideEffects[]` 的 `action` 可为 `"EXR"`，`field` 可为 `stockCost` / `proceeds` / `soldCost` | `EXR` 参与合约重放（多头一侧）、生成的股票记录参与股票与覆盖重放；新的拒绝文案（见"与其它记录的交互"） |
| `POST /api/option-trades`、`.../closes`、`.../assignments`、`.../expirations` | — | `action: "EXR"` 能解析，但开仓 / 平仓接口 400 `VALIDATION_ERROR`（原有文案）；行权只能用 `POST /api/option-trades/exercises` |
| `GET /api/option-positions` | `positions[]` 新字段 `exercisable`、`exerciseChoices`、`exerciseDateMax`、`exerciseDateDefault`、`availableCash` | `underlyingShares`、`availableShares` 在**多头 put 行**也有值（原来只有空头 call 行有值） |
| `GET /api/closed-trades` | `action` 筛选接受 `EXR`；行新字段 `exercise`（object \| null）、`exerciseTradeId` | 不带 `action` 时包含 `EXR` 行与 `EXERCISE_SELL` 股票行；`EXR` 行 `realizedPnl` 恒为 `"0.00"`（不等于 `openingNetTotal + closeNetAmount`）；`action` 文案改为 `方向只能是 BTC、STC、EXP、ASG、EXR` |
| `GET /api/pnl-summary`、`GET /api/dashboard` | — | call 行权计 1 笔（期权 0.00）；put 行权计 2 笔（期权 0.00 + 股票 `EXERCISE_SELL`）（Q114） |
| 股票记录（`/api/stock-trades/**`） | `type` 新取值 `EXERCISE_BUY`、`EXERCISE_SELL`；新字段 `exerciseTradeId` | 两种新类型不能 `POST`（400）/ `PUT` / `DELETE`（422 `STOCK_TRADE_FROM_EXERCISE`）；列表 `type` 文案改为 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL` |
| `GET /api/stock-positions`、各处 `soldLots[]` | 批次 / 被卖出批次的 `type` 可为 `EXERCISE_BUY` | — |
| `GET /api/cash/ledger` | `sourceType` 新取值 `OPTION_EXERCISE` | — |
| `GET /api/audit-log` | — | `OPTION_TRADE` 的删除审计可以是行权（`before.action` `"EXR"`，`before` 多出行权字段） |

前端遇到不认识的枚举值应原样显示（已有约定）。

**数据库迁移（预计）`V12__exercise.sql`**（只放宽约束、加可空列、加宽一列，不改任何已有数据）：
- `option_trade`：`ck_option_trade_action` 增加 `'EXR'`（`VARCHAR(3)`）；`ck_option_trade_net` 改为 `EXP` / `ASG` / `EXR` 的 `net_amount = 0`、其它 > 0；行权费**沿用已有列 `assignment_fee`**（"有股票一侧的记录的费用"），`ck_option_trade_assignment_fee` 改为"`ASG` / `EXR` ⇔ 非空且 ≥ 0"（实现时改定，不新增 `exercise_fee` 列；不影响 API）。行权日期不单独存（= `traded_at` 的美东日期）。
- `stock_trade`：`type` 列由 `VARCHAR(12)` 加宽为 `VARCHAR(16)`（`EXERCISE_SELL` 13 个字符）；`ck_stock_trade_type` 增加 `'EXERCISE_BUY'`、`'EXERCISE_SELL'`；`ck_stock_trade_option_trade` 改为"`type IN ('ASSIGN_BUY','ASSIGN_SELL','EXERCISE_BUY','EXERCISE_SELL')` ⇔ `option_trade_id` 非空"。生成行的 `amount` 存**当前**股票成本 / 卖出实收，重放使它变化时同一事务改正（同 `ASSIGN_BUY` / `ASSIGN_SELL`）。
- 流水 `source_type` 无约束，`OPTION_EXERCISE` 不改表。审计对象类型不变（`OPTION_TRADE`）。
- 测试资源 `backend/src/test/resources/db/failing-migration/V12__simulated_failure.sql` 改名为 `V13__simulated_failure.sql`（并改 `MigrationFromM1Test` 中的引用）。

#### 概念
- **行权记录**：一条 `action = "EXR"` 的期权记录，与交易同表、共用 id。只能是**多头** call / put。保存后 `contracts`、`multiplier`、时间、行权费、备注都不变；不能修改，可以删除。
- **时间（Q81，同 Q76 / Q94）**：用户填美东**行权日期** `exerciseDate`，记录时间 = 该日美东 16:00:00。要求 `exerciseDate ≤ expirationDate`、该时间点 ≤ 服务器当前时间（16:00:00 整允许）。到期日之前任何日期都可以（提前行权）。不检查交易日 / 假日。
- **合约重放**：`EXR` 在合约重放里就是"一笔平掉 N 张多头的记录"（同 `STC` / 多头 `EXP`）：必须有多头持仓、不超过持仓、正好是最早 1 笔或连续多笔整批次（Q21 / Q73 / Q112）、multiplier 与持仓相同（Q23）。被行权批次（`coveredLots`）在读取时按 FIFO 重放得出。
- **一次行权 = 三条记录同生同灭**：`EXR` 期权记录 + 生成的股票记录（call：`EXERCISE_BUY`；put：`EXERCISE_SELL`）+ 一条 `OPTION_EXERCISE` 流水。

#### 金额（Q111、Q96、Q99；读取时按重放计算，scale 2）
- 股数 `shares` = `contracts × multiplier`（保存后不变）
- 权利金 `premium` = 被行权批次开仓净额之和，取**正数**（多头批次的 `coveredLots[].netAmount` 为负，`premium` = −Σ）
- 期权已实现盈亏 `realizedPnl` 恒为 `"0.00"`；`frozenChange` 恒为 `"0.00"`（多头不冻结）
- **call**：
  - 买股金额 `purchaseAmount` = 行权价 × 股数，**向上取整到分**（Q96）。例：30 × 300 = `9000.00`；12.345 × 1 = `12.35`。保存后不变
  - 股票成本 `stockCost` = `purchaseAmount + premium + exerciseFee`（恒 > 0）
  - 每股成本 `stockCostPerShare` = `stockCost ÷ shares`，HALF_UP 4 位小数，仅显示
  - `cashChange` = −(`purchaseAmount + exerciseFee`)
- **put**：
  - 行权金额 `saleAmount` = 行权价 × 股数，**向下取整到分**（Q99）。例：45 × 100 = `4500.00`；12.345 × 1 = `12.34`。保存后不变
  - 卖出实收 `proceeds` = `saleAmount − premium − exerciseFee`，必须 > 0
  - 交出成本 `soldCost` = 该标的股票 FIFO 交出的成本（可跨批次，部分批次按 Q69 / Q87 分摊，同 `SELL`）
  - 股票已实现盈亏 `stockRealizedPnl` = `proceeds − soldCost`（可为负）
  - `cashChange` = +(`saleAmount − exerciseFee`)

#### `POST /api/option-trades/exercises`
记录一次行权。

请求体：
```json
{
  "symbol": "QRTV",
  "expirationDate": "2026-11-20",
  "strike": "30",
  "optionType": "CALL",
  "contracts": 3,
  "exerciseDate": "2026-11-20",
  "exerciseFee": "0.00",
  "note": null
}
```

| 字段 | 类型 | 必填 | 规则与 `VALIDATION_ERROR` 文案（`details[].field` = 字段名） |
|------|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | | 是 | 合约，规则与文案同 FEAT-001 |
| `contracts` | number | 是 | 从持仓的 `exerciseChoices` 中选。正整数，≤ 100000：`张数不能为空`、`张数必须是正整数`（0、负数、`1.5`）、`张数过大` |
| `exerciseDate` | string | 是 | 美东日期 `YYYY-MM-DD`：`行权日期不能为空`。格式不对或日期不存在 → 400 `BAD_REQUEST`（`details[].field = "exerciseDate"`）。与到期日、当前时间的关系在第 3 步检查（422） |
| `exerciseFee` | string | 否 | 行权费。缺省、`null`、空串 = `0.00`。≥ 0，最多 2 位小数，≤ `9999999999999.99`：`行权费不能为负数`、`行权费最多 2 位小数`、`行权费过大`。不是数字 → 400 `BAD_REQUEST` |
| `note` | string | 否 | 最多 500 个字符：`备注最多 500 个字符`；`null`、空串、全空白保存为 `null` |
| 其它字段 | — | — | `action`、`netAmount`、`multiplier`、`tradedAtLocal` 等带了一律**忽略** |

检查顺序（任一失败即整笔拒绝，什么都不保存、不写审计；422 的 `details` 为 `[]`，`message` 中文可直接显示；时间为美东 `YYYY-MM-DD HH:mm`，金额千分位 2 位小数，负数 `−`(U+2212)，股数不带千分位）：
1. **字段** → 400 `VALIDATION_ERROR` / `BAD_REQUEST`
2. **对象**：行权时点（`exerciseDate` 16:00，同一时间已有的记录排在本次之前）该合约为**空头** → 422 `EXERCISE_SHORT_POSITION`：`空头持仓不能行权（被行权请用"被指派"）`（引号为 ASCII `"`，JSON 中转义为 `\"`）
3. **日期** → 422 `EXERCISE_DATE_INVALID`，按顺序取第一条：
   - `exerciseDate` > `expirationDate`：`行权日期不能晚于到期日（2026-11-20）`
   - `exerciseDate` > 今天（服务器当前时间的美东日期）：`行权日期不能晚于今天`
   - `exerciseDate` = 今天且当前时间 < 今天美东 16:00：`行权日期为今天时，美东 16:00 之后才能记录`
4. **有持仓**：行权时点有多头持仓 → 否则 422 `NO_POSITION_TO_EXERCISE`：`2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权`。持仓是被之前（或同一时间、更早录入）的 `EXP` 清零的，末尾加 `（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）`
5. **整笔批次（Q112）**：
   - 超过持仓 → 422 `CLOSE_EXCEEDS_POSITION`：`行权 4 张超过当时持仓 3 张`
   - 不是最早 1 笔或连续多笔整批次之和 → 422 `CLOSE_NOT_WHOLE_LOTS`：`必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张`（列法同 FEAT-004）
6. **之后的同合约记录**：插入后重新检查 → 422，code 与文案同 FEAT-013 第 6 步（`<改动>` = `补录`），如 `补录后该合约（QRTV 2026-11-20 30 C）在 2026-11-20 16:00 的到期作废 1 张将超过当时持仓 0 张，请先删除该到期作废`
7. **put：卖出实收 > 0** → 422 `EXERCISE_PROCEEDS_NOT_POSITIVE`：`行权后股票卖出实收将为 −5.00，请检查行权费和被行权批次的开仓净额`（为 0 时 `将为 0.00`）。call 的股票成本恒 > 0，不检查
8. **put：持股（Q15）** → 422 `SHARES_INSUFFICIENT`。生成的 `EXERCISE_SELL` 按 `(行权时间, 录入顺序)` 插入该标的股票历史（同一时间已有的股票记录排在它之前）：
   - 行权时点持股 < 股数：`持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）`
   - 之后已有的卖出不成立（FEAT-011 原文案）：`补录后 2026-11-24 10:00 的卖出 XYZ 100 股将超过当时持股 0 股`
   - 之后已有的 call 被指派 / put 行权不成立：`补录后 2026-11-27 16:00 的被指派 1 张 XYZ 2026-11-27 50 C 需要 100 股，当时持股 0 股，请先删除该被指派`；`补录后 2026-11-27 16:00 的行权 1 张 XYZ 2026-11-27 45 P 需要 100 股，当时持股 0 股，请先删除该行权`
9. **put：可用股数（Q113，FEAT-039 E2 式）** → 422 `COVERAGE_INSUFFICIENT`：行权时点（本笔之前）要求 股数 ≤ 可用股数，否则 `行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call`（`其中 0 股` 时去掉 `，其中 … 覆盖`）
10. **覆盖不变式（FEAT-039 E3）** → 422 `COVERAGE_INSUFFICIENT`：`行权后 2026-11-27 10:00 起 XYZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股`。call 行权只增加股票，不会失败
11. **现金（Q68、Q19）** → 422 `CASH_INSUFFICIENT`：插入流水后从行权时间起每个时点总现金、可用现金 ≥ 0，否则 `可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00`。"需要" = call：`purchaseAmount + exerciseFee`；时点为第一个变负的时点（可能是之后某时点）。put 的 `cashChange` 恒 > 0（第 7 步保证），不会失败

响应 `201 Created`：行权记录，表示同 `GET /api/option-trades/{id}`。

**call 例**（验收第 2 条：场景 E 从空库录入，入金 id 1 / 流水 1；T1 股票记录 id 1 / 流水 2；L1、L2、L3、S1 交易 id 1–4 / 流水 3–6；时钟 2026-11-23 10:00）：
```json
{
  "id": 5,
  "symbol": "QRTV",
  "expirationDate": "2026-11-20",
  "strike": "30",
  "optionType": "CALL",
  "action": "EXR",
  "contracts": 3,
  "multiplier": 100,
  "netAmount": "0.00",
  "tradedAt": "2026-11-20T21:00:00Z",
  "tradedAtLocal": "2026-11-20T16:00:00",
  "cashChange": "-9000.00",
  "frozenChange": "0.00",
  "contractLabel": "QRTV 2026-11-20 30 C",
  "note": null,
  "ledgerEntryId": 7,
  "createdAt": "2026-11-23T15:00:00Z",
  "coveredLots": [
    { "tradeId": 1, "action": "BTO", "tradedAt": "2026-11-04T15:00:00Z", "tradedAtLocal": "2026-11-04T10:00:00",
      "contracts": 2, "netAmount": "-300.00", "frozen": "0.00" },
    { "tradeId": 2, "action": "BTO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 1, "netAmount": "-160.00", "frozen": "0.00" }
  ],
  "realizedPnl": "0.00",
  "hasAuditLog": false,
  "expiredDirection": null,
  "assignment": null,
  "exercise": {
    "exerciseDate": "2026-11-20",
    "exerciseFee": "0.00",
    "premium": "460.00",
    "shares": 300,
    "purchaseAmount": "9000.00",
    "stockCost": "9460.00",
    "stockCostPerShare": "31.5333",
    "saleAmount": null,
    "proceeds": null,
    "soldCost": null,
    "stockRealizedPnl": null,
    "soldLots": null,
    "stockTradeId": 2
  }
}
```
提前行权 + 行权费（验收第 4 条，2 张、日期 2026-11-18、费 5.00）：`tradedAt` `"2026-11-18T21:00:00Z"`、`cashChange` `"-6005.00"`、`coveredLots` 只有交易 1、`exercise`：`exerciseFee` `"5.00"`、`premium` `"300.00"`、`shares` 200、`purchaseAmount` `"6000.00"`、`stockCost` `"6305.00"`、`stockCostPerShare` `"31.5250"`。

**put 例**（验收第 3 条：场景 E 之后直接行权 XYZ 45 P，ids 同上）：
```json
{
  "id": 5,
  "symbol": "XYZ",
  "expirationDate": "2026-11-20",
  "strike": "45",
  "optionType": "PUT",
  "action": "EXR",
  "contracts": 1,
  "multiplier": 100,
  "netAmount": "0.00",
  "tradedAt": "2026-11-20T21:00:00Z",
  "tradedAtLocal": "2026-11-20T16:00:00",
  "cashChange": "4500.00",
  "frozenChange": "0.00",
  "contractLabel": "XYZ 2026-11-20 45 P",
  "note": null,
  "ledgerEntryId": 7,
  "createdAt": "2026-11-23T15:00:00Z",
  "coveredLots": [
    { "tradeId": 3, "action": "BTO", "tradedAt": "2026-11-05T16:00:00Z", "tradedAtLocal": "2026-11-05T11:00:00",
      "contracts": 1, "netAmount": "-120.00", "frozen": "0.00" }
  ],
  "realizedPnl": "0.00",
  "hasAuditLog": false,
  "expiredDirection": null,
  "assignment": null,
  "exercise": {
    "exerciseDate": "2026-11-20",
    "exerciseFee": "0.00",
    "premium": "120.00",
    "shares": 100,
    "purchaseAmount": null,
    "stockCost": null,
    "stockCostPerShare": null,
    "saleAmount": "4500.00",
    "proceeds": "4380.00",
    "soldCost": "4000.00",
    "stockRealizedPnl": "380.00",
    "soldLots": [
      { "tradeId": 1, "type": "BUY", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
        "tradeDate": null, "shares": 100, "cost": "4000.00" }
    ],
    "stockTradeId": 2
  }
}
```
multiplier 1（验收第 9 条）：call `purchaseAmount` `"12.35"`、`stockCost` `"12.85"`、`cashChange` `"-12.35"`；put `saleAmount` `"12.34"`、`proceeds` `"11.94"`、`soldLots` 为转入 1 股 `"10.00"`（`type` `TRANSFER_IN`、`tradeDate` `"2026-11-02"`）、`stockRealizedPnl` `"1.94"`、`cashChange` `"12.34"`。

本次产生：
- 现金流水：`occurredAt` = 行权时间，`sourceType` `"OPTION_EXERCISE"`，`sourceId` = 行权记录 `id`，`amount` = `cashChange`，`frozenChange` `"0.00"`，`remark` = `note`，`description` = `行权 <张数> 张 <contractLabel>，买入|卖出 <股数> 股 <标的>`，multiplier ≠ 100 时合约后加 `（multiplier <m>）`：`行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV`、`行权 1 张 XYZ 2026-11-20 45 P，卖出 100 股 XYZ`、`行权 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），买入 1 股 QFZ`。
- 股票记录 `EXERCISE_BUY` / `EXERCISE_SELL`，`id` = `exercise.stockTradeId`。
- 不写审计（新增，Q42）。

错误汇总：

| code | HTTP | `message` |
|------|------|-----------|
| `VALIDATION_ERROR` | 400 | 字段规则（见上表） |
| `BAD_REQUEST` | 400 | 同 FEAT-001；`exerciseDate` 格式不对 |
| `EXERCISE_SHORT_POSITION` | 422 | `空头持仓不能行权（被行权请用"被指派"）` |
| `EXERCISE_DATE_INVALID` | 422 | `行权日期不能晚于到期日（2026-11-20）` / `行权日期不能晚于今天` / `行权日期为今天时，美东 16:00 之后才能记录` |
| `NO_POSITION_TO_EXERCISE` | 422 | `2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权`［+ `（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）`］ |
| `CLOSE_EXCEEDS_POSITION` | 422 | `行权 4 张超过当时持仓 3 张`；或之后某条记录超量（第 6 步） |
| `CLOSE_NOT_WHOLE_LOTS` | 422 | `必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张`；或之后某条记录不再是整笔批次 |
| `NO_POSITION_TO_CLOSE` / `MULTIPLIER_MISMATCH` / `POSITION_DIRECTION_CONFLICT` | 422 | 之后某条记录违规（第 6 步） |
| `EXERCISE_PROCEEDS_NOT_POSITIVE` | 422 | `行权后股票卖出实收将为 −5.00，请检查行权费和被行权批次的开仓净额` |
| `SHARES_INSUFFICIENT` | 422 | `持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）`；或之后某条卖出 / 被指派 / 行权不成立 |
| `COVERAGE_INSUFFICIENT` | 422 | `行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call`；`行权后 … 起 XYZ 不足以覆盖卖出的 call：需要 R 股，持有 H 股` |
| `CASH_INSUFFICIENT` | 422 | `可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00` |

示例（422）：
```json
{
  "code": "EXERCISE_SHORT_POSITION",
  "message": "空头持仓不能行权（被行权请用\"被指派\"）",
  "status": 422,
  "path": "/api/option-trades/exercises",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": []
}
```
```json
{
  "code": "CASH_INSUFFICIENT",
  "message": "可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00",
  "status": 422,
  "path": "/api/option-trades/exercises",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": []
}
```
```json
{
  "code": "COVERAGE_INSUFFICIENT",
  "message": "行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call",
  "status": 422,
  "path": "/api/option-trades/exercises",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": []
}
```
示例（400）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/option-trades/exercises",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [
    { "field": "contracts", "message": "张数必须是正整数" },
    { "field": "exerciseDate", "message": "行权日期不能为空" },
    { "field": "exerciseFee", "message": "行权费不能为负数" },
    { "field": "note", "message": "备注最多 500 个字符" }
  ]
}
```

#### 交易表示的变化
- `action` 新增取值 `EXR`（行权）。
- 新增字段 **`exercise`**（object \| null）：只有 `EXR` 记录有值，其它记录为 `null`（`EXR` 记录的 `assignment` 为 `null`）。

| 字段 | 类型 | call | put |
|------|------|------|-----|
| `exerciseDate` | string | 行权日期（美东，= `tradedAtLocal` 的日期） | 同左 |
| `exerciseFee` | string | 行权费，scale 2 | 同左 |
| `premium` | string | 被行权批次开仓净额之和（正数，按当前重放） | 同左 |
| `shares` | number | `contracts × multiplier` | 同左 |
| `purchaseAmount` | string \| null | 买股金额（向上取整） | `null` |
| `stockCost` | string \| null | 股票成本（按当前重放） | `null` |
| `stockCostPerShare` | string \| null | 每股成本，HALF_UP 4 位 | `null` |
| `saleAmount` | string \| null | `null` | 行权金额（向下取整） |
| `proceeds` | string \| null | `null` | 卖出实收（按当前重放） |
| `soldCost` | string \| null | `null` | 交出成本（按当前重放） |
| `stockRealizedPnl` | string \| null | `null` | 股票已实现盈亏（可为负） |
| `soldLots` | array \| null | `null` | 交出批次，FIFO 顺序，项同 FEAT-011 `soldLots[]`（`type` 可为 `BUY` / `TRANSFER_IN` / `ASSIGN_BUY` / `EXERCISE_BUY`） |
| `stockTradeId` | number | `EXERCISE_BUY` 的 id | `EXERCISE_SELL` 的 id |

- `EXR` 记录其它字段：`netAmount` `"0.00"`；`frozenChange` `"0.00"`；`ledgerEntryId` = `OPTION_EXERCISE` 流水 id；`coveredLots` 同平仓（多头批次 `netAmount` 为负、`frozen` `"0.00"`）；`realizedPnl` `"0.00"`；`expiredDirection` `null`；`hasAuditLog` 恒为 `false`。
- 交易列表小字：`买入 300 股 QRTV，现金 −9,000.00` / `卖出 100 股 XYZ，现金 +4,500.00`（行权费 > 0 时加 `，行权费 5.00`），取 `exercise.shares`、`symbol`、`cashChange`、`exercise.exerciseFee`；冻结列 `—`。
- 可改 / 可删：前端按 `action` 判断——`EXR` 同 `EXP` / `ASG`，只能删除。
- `GET /api/option-trades?action=EXR` 可筛选（可多选）。不认识的值 → 400 `BAD_REQUEST`，`方向只能是 BTO、STO、BTC、STC、EXP、ASG、EXR`。
- `POST /api/option-trades` 收到 `EXR` → 400 `VALIDATION_ERROR`（`本功能只支持开仓（BTO / STO），平仓请使用平仓功能`）；`POST /api/option-trades/closes` 收到 `EXR` → 400 `VALIDATION_ERROR`（`本功能只支持平仓（BTC / STC），开仓请使用开仓功能`）。

#### `PUT /api/option-trades/{id}` 对 `EXR`
422 `EXERCISE_NOT_EDITABLE`，`message`：`行权不能修改，记错请删除后重新记录`；什么都不改、不写审计（判断位置同 FEAT-013 的 `ASSIGNMENT_NOT_EDITABLE`）。

#### `DELETE /api/option-trades/{id}` 对 `EXR`
按顺序检查，任一失败即 422、什么都不改、不写审计：
1. **合约重放**：被行权批次恢复为持仓；之后同合约的记录按新历史重新检查（文案同 FEAT-012 / 013 删除）。
2. **股票重放（Q15）**：call 生成的 `EXERCISE_BUY` 消失 → 之后的卖出 / call 被指派 / put 行权不成立 → `SHARES_INSUFFICIENT`，如 `删除后 2026-11-24 10:00 的卖出 QRTV 100 股将超过当时持股 0 股`。put 删除只会恢复股票，不会失败。
3. **覆盖（FEAT-039 E3）** → `COVERAGE_INSUFFICIENT`：`删除后 2026-11-24 10:00 起 QRTV 不足以覆盖卖出的 call：需要 100 股，持有 0 股`（call 行权的股票已用于 covered call 时）。
4. **现金重放（Q19）** → `CASH_INSUFFICIENT`：`可用现金不足：删除后 2026-11-24 10:00 时可用现金将变为 −0.01`（put 删除少了收入时）。

成功 `204 No Content`：行权记录、生成的股票记录、流水一起删除；写**一条**审计：`operation` `DELETE`，`objectType` `OPTION_TRADE`，`objectId` = 行权记录 id，`summary` = `行权 <contractLabel> <张数> 张（买入|卖出 <股数> 股 <标的>）`，如 `行权 QRTV 2026-11-20 30 C 3 张（买入 300 股 QRTV）`、`行权 XYZ 2026-11-20 45 P 1 张（卖出 100 股 XYZ）`；`after` `null`；`changes` `[]`；`sideEffects` 同 FEAT-005 / 013 / 041。生成的股票记录不另写审计。删除后可以重新记录（新 id，`hasAuditLog` `false`）。

审计 `before`：期权交易的全部字段 + 平铺的行权字段：`exerciseDate`、`exerciseFee`、`shares`、`premium`、`stockTradeId`，call 另有 `purchaseAmount`、`stockCost`，put 另有 `saleAmount`、`proceeds`、`soldCost`、`stockRealizedPnl`。
```json
{
  "id": 5, "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "30", "optionType": "CALL",
  "contractLabel": "QRTV 2026-11-20 30 C", "action": "EXR", "contracts": 3, "multiplier": 100,
  "netAmount": "0.00", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "note": null, "cashChange": "-9000.00", "frozenChange": "0.00",
  "exerciseDate": "2026-11-20", "exerciseFee": "0.00", "shares": 300, "premium": "460.00",
  "purchaseAmount": "9000.00", "stockCost": "9460.00", "stockTradeId": 2
}
```

#### 与其它记录的交互（重放）
`EXR` 作为"一笔平掉 N 张多头的记录"参与合约重放，生成的股票记录参与该标的股票 / 覆盖重放，流水参与现金重放。检查顺序：合约 → 股票成本 / 卖出实收 → 持股 → 覆盖 → 现金。
- **合约**拒绝文案同 FEAT-013，"被指派"换成"行权"（code 同 FEAT-012）：
  - 被修改 / 删除的开仓正是被行权批次之一：`该开仓被 2026-11-20 16:00 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权`；括号内其它原因：`<改动>后行权将没有持仓可行权`、`<改动>后行权将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张）`、`<改动>后行权将与当时持仓的 multiplier 10 不一致`
  - 其它：`<改动>后该合约（QRTV 2026-11-20 30 C）在 2026-11-20 16:00 的行权 3 张将超过当时持仓 1 张，请先删除该行权`；`… 将没有持仓可行权，…`；`… 将不是整笔批次（…），…`；`… 的行权 3 张将遇到空头持仓（1 张），请先删除该行权`（`POSITION_DIRECTION_CONFLICT`）；multiplier 同 FEAT-013 写法
- **put 卖出实收 ≤ 0**（修改被行权批次净额）→ 422 `EXERCISE_PROCEEDS_NOT_POSITIVE`：`<改动>后行权 #5 的股票卖出实收将为 −1.00，请先删除该行权`
- **持股**（补录 / 修改 / 删除股票记录、删除 put `ASG` / call `EXR`）使某条 put `EXR` 行权时点持股不足 → 422 `SHARES_INSUFFICIENT`：`<改动>后 2026-11-20 16:00 的行权 1 张 XYZ 2026-11-20 45 P 需要 100 股，当时持股 0 股，请先删除该行权`
- **覆盖**：`EXERCISE_BUY` 在 FEAT-039 "持股"中计为加、`EXERCISE_SELL` 计为减；E3 照常（前缀 `补录` / `修改` / `删除` / `被指派` / `行权`）。
- **允许的改动**：修改被行权批次开仓净额 → `premium`、`stockCost`（call，即 `EXERCISE_BUY` 批次成本，及之后用到它的卖出的交出成本 / 盈亏）或 `proceeds` / `stockRealizedPnl`（put）随之变化；股票改动使 put 交出批次重新 FIFO 匹配 → `soldCost` 变化。
- **连带变化（审计 `sideEffects`，Q42）**：一次修改 / 删除使某条 `EXR` 的股票成本、卖出实收或交出成本变化时写入（不变不写），每项 `tradeId`（`EXR` id）、`action` `"EXR"`、`contractLabel`、`field` `stockCost` / `proceeds` / `soldCost`、`before` / `after`（显示用金额，千分位，同 FEAT-013，如 `"9,460.00"`）、`text`：`行权 #5 股票成本 9,460.00 → 9,470.00`、`行权 #6 股票卖出实收 4,380.00 → 4,370.00`、`行权 #6 交出成本 4,000.00 → 3,900.00`。股票盈亏变化不单独写。被连带改正的 `EXR` 与生成的股票记录 `hasAuditLog` 不变（`false`）。
- **同一时间（16:00）按录入顺序**：在 `EXR` 之后录入、成交时间正好 16:00 的同标的股票卖出可以用到 `EXERCISE_BUY` 批次；之前录入的不能。

#### `GET /api/option-positions` 的变化

| 字段 | 类型 | 说明 |
|------|------|------|
| `positions[].exercisable` | boolean | "行权"按钮按它显示：`direction = LONG`（call 或 put）时**任何时刻**为 `true`（到期前、`expiryPending`、已过期，Q81）；空头为 `false`。与 `closable` / `expiryPending` / `expirable` 独立 |
| `positions[].exerciseChoices` | array | 可行权张数选项，规则同 `assignChoices`（当前 FIFO 最早 1 笔、2 笔……整批次累计），升序，每项 `{ "contracts": number, "shares": number }`。`exercisable` 为 `false` 时 `[]`。对话框默认选最后一项 |
| `positions[].exerciseDateMax` | string \| null | 行权日期最晚可选 = min(到期日, 当前时间 ≥ 今天美东 16:00 ? 今天 : 昨天)。`exercisable` 为 `false` 时 `null` |
| `positions[].exerciseDateDefault` | string \| null | = `exerciseDateMax`。`exercisable` 为 `false` 时 `null` |
| `positions[].availableCash` | string \| null | 多头 call 行：该账户**当前**可用现金（= `GET /api/cash/summary` 的 `availableCash`），对话框"可用现金 X"；其它行 `null`。仅显示 |
| `positions[].underlyingShares` | number \| null | **扩展**：多头 put 行也有值 = 该标的当前持股（对话框"当前持股 X 股"）；空头 call 行不变；其它行 `null` |
| `positions[].availableShares` | number \| null | **扩展**：多头 put 行也有值 = 该标的当前可用股数（持股 − 被覆盖，FEAT-039；对话框"可用 Y 股"）；空头 call 行不变；其它行 `null`。多头 put 行的其它覆盖字段（`coveredShares` 等）仍为 `null` |

`availableCash` / `underlyingShares` / `availableShares` 都是**当前**值；行权日期较早时以提交时第 8–11 步检查为准。

例（场景 E，时钟 2026-11-23 10:00，只列相关字段）：
```json
[
  {
    "contractLabel": "QRTV 2026-11-20 30 C", "direction": "LONG", "contracts": 3, "multiplier": 100,
    "closable": false, "expiryPending": false, "expirable": true,
    "assignable": false, "assignChoices": [], "assignmentDateMax": null, "assignmentDateDefault": null,
    "exercisable": true,
    "exerciseChoices": [ { "contracts": 2, "shares": 200 }, { "contracts": 3, "shares": 300 } ],
    "exerciseDateMax": "2026-11-20", "exerciseDateDefault": "2026-11-20",
    "availableCash": "11510.00", "underlyingShares": null, "availableShares": null
  },
  {
    "contractLabel": "XYZ 2026-11-20 45 P", "direction": "LONG", "contracts": 1, "multiplier": 100,
    "exercisable": true,
    "exerciseChoices": [ { "contracts": 1, "shares": 100 } ],
    "exerciseDateMax": "2026-11-20", "exerciseDateDefault": "2026-11-20",
    "availableCash": null, "underlyingShares": 200, "availableShares": 100
  },
  {
    "contractLabel": "XYZ 2026-12-18 50 C", "direction": "SHORT", "contracts": 1, "multiplier": 100,
    "assignable": true,
    "exercisable": false, "exerciseChoices": [], "exerciseDateMax": null, "exerciseDateDefault": null,
    "availableCash": null, "underlyingShares": 200, "availableShares": 100
  }
]
```
时钟 2026-11-17 10:00：QRTV 行 `closable` `true`、`exercisable` `true`、`exerciseDateMax` `"2026-11-16"`；2026-11-20 16:30：`expiryPending` `true`、`exercisable` `true`（唯一按钮）。

#### `GET /api/closed-trades` 的变化（Q114）
- `EXR` 期权行（`type` `OPTION`）：`action` `"EXR"`，`closedDirection` `"LONG"`，`tradedAt` = 行权时间，`openingNetTotal` = 被行权批次开仓净额之和（多头为负，如 `"-460.00"`）、`openingNetPerContract`（HALF_UP，如 `"-153.33"`），`coveredLots` 同平仓，`closeNetAmount` / `closeNetPerContract` `"0.00"`，**`realizedPnl` `"0.00"`**（例外），`note` = 备注，`hasAuditLog` `false`，`assignment` `null`。
- 新增行字段 **`exercise`**（object \| null）：`EXR` 行为 `{ "shares": 300, "stockCost": "9460.00", "proceeds": null, "stockTradeId": 2 }`（call；页面小字 `权利金已计入股票成本（300 股 QRTV，成本 9,460.00）`）或 `{ "shares": 100, "stockCost": null, "proceeds": "4380.00", "stockTradeId": 2 }`（put；`权利金已从股票卖出实收中扣除（100 股 XYZ，实收 4,380.00）`）；其它行 `null`。
- put 行权另有股票行（`type` `STOCK`，**`action` `EXERCISE_SELL`**）：字段同 `SELL` 行（`closeNetAmount` = 卖出实收，`openingNetTotal` = −交出成本，`realizedPnl` = 股票盈亏，`soldLots`）；新增行字段 **`exerciseTradeId`** = 行权 id（页面 `来自行权 #5`），其它行 `null`。call 行权的 `EXERCISE_BUY` 不出现（买入不出现，Q71）。
- 计入 `totalItems` / `filteredCount` / `filteredRealizedPnlTotal`。排序同 FEAT-040（同一时间股票行在期权行之上）。`action` 筛选接受 `BTC` / `STC` / `EXP` / `ASG` / `EXR`；`EXR` 为期权专用条件（Q90，传了 `action` 股票行不出现）。`type=STOCK` 包含 `EXERCISE_SELL`。
- 验收第 3 条后：2 行（`EXERCISE_SELL` `"380.00"` 在上、`EXR` `"0.00"` 在下），`totalItems` 2，`filteredRealizedPnlTotal` `"380.00"`。

`EXR` 行示例（call）：
```json
{
  "type": "OPTION", "id": 5, "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "30", "optionType": "CALL",
  "contractLabel": "QRTV 2026-11-20 30 C", "action": "EXR", "closedDirection": "LONG",
  "contracts": 3, "multiplier": 100, "shares": null,
  "openingNetTotal": "-460.00", "openingNetPerContract": "-153.33", "costPerShare": null,
  "closeNetAmount": "0.00", "closeNetPerContract": "0.00", "pricePerShare": null,
  "realizedPnl": "0.00", "note": null,
  "coveredLots": [
    { "tradeId": 1, "action": "BTO", "tradedAt": "2026-11-04T15:00:00Z", "tradedAtLocal": "2026-11-04T10:00:00",
      "contracts": 2, "netAmount": "-300.00", "frozen": "0.00" },
    { "tradeId": 2, "action": "BTO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 1, "netAmount": "-160.00", "frozen": "0.00" }
  ],
  "soldLots": null,
  "assignment": null,
  "assignmentTradeId": null,
  "exercise": { "shares": 300, "stockCost": "9460.00", "proceeds": null, "stockTradeId": 2 },
  "exerciseTradeId": null,
  "hasAuditLog": false
}
```
`EXERCISE_SELL` 行示例（put）：
```json
{
  "type": "STOCK", "id": 2, "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "symbol": "XYZ", "expirationDate": null, "strike": null, "optionType": null,
  "contractLabel": null, "action": "EXERCISE_SELL", "closedDirection": null,
  "contracts": null, "multiplier": null, "shares": 100,
  "openingNetTotal": "-4000.00", "openingNetPerContract": null, "costPerShare": "40.0000",
  "closeNetAmount": "4380.00", "closeNetPerContract": null, "pricePerShare": "43.8000",
  "realizedPnl": "380.00", "note": null, "coveredLots": null,
  "soldLots": [
    { "tradeId": 1, "type": "BUY", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
      "tradeDate": null, "shares": 100, "netAmount": "-4000.00" }
  ],
  "assignment": null,
  "assignmentTradeId": null,
  "exercise": null,
  "exerciseTradeId": 5,
  "hasAuditLog": false
}
```

#### `GET /api/pnl-summary`、`GET /api/dashboard`
没有新字段。`EXR` 计入 `option` 1 笔 0.00；`EXERCISE_SELL` 与 `SELL` 一样计入 `stock`（笔数、盈亏），都按行权时间的美东日期 / 月份。验收第 3 条后（只有 put 行权）：`closedCount` 2、`realizedPnlTotal` `"380.00"`；XYZ 行 `option` `{ "closedCount": 1, "realizedPnl": "0.00" }`、`stock` `{ "closedCount": 1, "realizedPnl": "380.00" }`；首页 `"realizedPnl": { "closedCount": 2, "total": "380.00" }`。只有 call 行权时 `closedCount` 1、`"0.00"`。首页 `positions.expiredUnprocessedCount` 在过期持仓被全部行权后随之减少。

#### 股票记录（FEAT-011）的变化
- `type` 新增 **`EXERCISE_BUY`**（行权买入，call 生成）、**`EXERCISE_SELL`**（行权卖出，put 生成）。
- 新字段 **`exerciseTradeId`**（number \| null，所有类型都有）：两种行权类型 = 生成它的行权记录 id（页面 `来自行权 #5`）；其它 `null`。`editable` / `deletable` 对两种行权类型为 `false`。
- `EXERCISE_BUY`：同 `ASSIGN_BUY`——`amount` = 当前股票成本（= `exercise.stockCost`）、`cashChange` `"0.00"`、`pricePerShare` = 每股成本（4 位）、时间 = 行权时间、`tradeDate` / `note` / `ledgerEntryId` `null`、`soldCost` / `realizedPnl` / `soldLots` `null`、`assignmentTradeId` `null`。按 `(tradedAt, 录入顺序)` 排进 FIFO，之后的卖出可以用它。
- `EXERCISE_SELL`：同 `ASSIGN_SELL`——`amount` = 当前卖出实收、`soldCost` / `realizedPnl` / `soldLots` = 行权的 `soldCost` / `stockRealizedPnl` / `soldLots`。
```json
{
  "id": 2, "type": "EXERCISE_BUY", "symbol": "QRTV", "shares": 300, "amount": "9460.00", "cashChange": "0.00",
  "pricePerShare": "31.5333", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "tradeDate": null, "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
  "hasAuditLog": false, "soldCost": null, "realizedPnl": null, "soldLots": null,
  "assignmentTradeId": null, "exerciseTradeId": 5, "editable": false, "deletable": false
}
```
```json
{
  "id": 2, "type": "EXERCISE_SELL", "symbol": "XYZ", "shares": 100, "amount": "4380.00", "cashChange": "0.00",
  "pricePerShare": "43.8000", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "tradeDate": null, "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
  "hasAuditLog": false, "soldCost": "4000.00", "realizedPnl": "380.00",
  "soldLots": [
    { "tradeId": 1, "type": "BUY", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
      "tradeDate": null, "shares": 100, "cost": "4000.00" }
  ],
  "assignmentTradeId": null, "exerciseTradeId": 5, "editable": false, "deletable": false
}
```
- `POST /api/stock-trades` 带 `type: "EXERCISE_BUY"` → 400 `VALIDATION_ERROR`，field `type`，`行权买入由行权生成，不能直接录入`；`EXERCISE_SELL` → `行权卖出由行权生成，不能直接录入`。
- `PUT` / `DELETE /api/stock-trades/{id}` 对两种行权类型 → 422 **`STOCK_TRADE_FROM_EXERCISE`**，`message`：`该股票记录由行权生成，请在交易列表删除对应的行权记录`；什么都不改、不写审计（判断位置同 `STOCK_TRADE_FROM_ASSIGNMENT`）。
- `GET /api/stock-trades?type=EXERCISE_BUY&type=EXERCISE_SELL` 可筛选；不认识的值 → 400 `BAD_REQUEST`，`类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL`。
- `GET /api/stock-positions` 的 `lots[].type` 可为 `EXERCISE_BUY`。验收第 2 条后：QRTV `shares` 300、`coveredShares` 0、`availableShares` 300、`totalCost` `"9460.00"`、`averageCost` `"31.5333"`，一个批次 `type` `EXERCISE_BUY`。验收第 3 条后 XYZ `shares` 100、`coveredShares` 100、`availableShares` 0。

#### 现金（FEAT-007）
- `sourceType` 新增 **`OPTION_EXERCISE`**（行权），`sourceId` = 行权记录 id。`amount` = `cashChange`，`frozenChange` `"0.00"`，`description` 见上，`remark` = 备注，`hasAuditLog` 恒为 `false`。没有修改 / 删除接口（到交易列表删除行权）。
- 验收第 2 条后现金汇总 `{"totalCash": "2510.00", "frozenCash": "0.00", "availableCash": "2510.00"}`；第 3 条后（单独）`16010.00 / 0.00 / 16010.00`。
```json
{
  "id": 7, "occurredAt": "2026-11-20T21:00:00Z", "sourceType": "OPTION_EXERCISE", "sourceId": 5,
  "amount": "-9000.00", "balanceAfter": "2510.00", "frozenChange": "0.00",
  "description": "行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV", "remark": null, "hasAuditLog": false
}
```

#### `hasAuditLog` 汇总
`EXR` 交易行、`EXR` 已平仓行、`EXERCISE_SELL` 已平仓行、`OPTION_EXERCISE` 流水行、`EXERCISE_BUY` / `EXERCISE_SELL` 股票记录恒为 `false`。

#### 已知限制
- 盈亏口径是 Q111 方案；第一次真实行权后对照券商报表复核。
- 不自动判断价内 / 自动行权（FEAT-015）；不能指定交出哪批股票（put 按股票 FIFO 交出）。
- `exerciseChoices`、`availableCash`、`underlyingShares`、`availableShares` 是当前值；行权日期较早时以提交时检查为准。
- 拆股见 FEAT-028。
