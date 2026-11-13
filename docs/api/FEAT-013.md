# FEAT-013 卖出 put 被指派（`ASG`）

**兼容性一览**（所有接口仍要求 `X-Account-Id`，只读写该账户；用另一账户的 id → 404 `NOT_FOUND`）：

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades/assignments` | 新接口 | — |
| 期权交易表示（`GET /api/option-trades`、`GET /api/option-trades/{id}`、`POST` / `PUT` 的响应） | `action` 新取值 `ASG`；新字段 `assignment`（object \| null） | `ASG` 行 `netAmount` 为 `"0.00"`，但 `cashChange` 为 −(买股金额 + 指派费)（不再是"净额带符号"） |
| `GET /api/option-trades` | `action` 筛选接受 `ASG` | `action` 无法解析时的文案改为 `方向只能是 BTO、STO、BTC、STC、EXP、ASG` |
| `PUT /api/option-trades/{id}` | — | 对 `ASG` → 422 `ASSIGNMENT_NOT_EDITABLE` |
| `DELETE /api/option-trades/{id}` | — | 对 `ASG`：连带删除生成的股票记录和流水，并做股票重放（见下） |
| 补录 / 修改 / 删除期权交易（FEAT-001 / 004 / 005 / 012） | 审计 `sideEffects[]` 的 `action` 可为 `"ASG"`、`field` 可为 `"stockCost"` | `ASG` 参与合约重放（新的拒绝文案）；修改开仓净额会改变 `ASG` 的股票成本（即生成的股票批次成本） |
| `POST /api/option-trades`、`POST /api/option-trades/closes` | — | `action: "ASG"` 能解析，但 400 `VALIDATION_ERROR`（原有"只支持开仓 / 平仓"文案） |
| `GET /api/option-positions` | `positions[]` 新字段 `assignable`、`assignChoices`、`assignmentDateMax`、`assignmentDateDefault` | — |
| `GET /api/closed-trades` | `action` 筛选接受 `ASG`；行新字段 `assignment`（object \| null） | 不带 `action` 时**包含 `ASG` 行**（计入 `totalItems` / `filteredCount`，Q97；金额 0.00 不影响合计）；`ASG` 行 `realizedPnl` **不等于** `openingNetTotal + closeNetAmount`（恒为 `"0.00"`）；`action` 文案改为 `方向只能是 BTC、STC、EXP、ASG` |
| `GET /api/pnl-summary`、`GET /api/dashboard` | — | 笔数（`closedCount`、`option.closedCount`、首页 `closedCount`）**计入 `ASG`**（Q97），金额 0.00 |
| 股票记录表示（`/api/stock-trades/**`） | `type` 新取值 `ASSIGN_BUY`；新字段 `assignmentTradeId`、`editable`、`deletable` | `PUT` / `DELETE` 对 `ASSIGN_BUY` → 422 `STOCK_TRADE_FROM_ASSIGNMENT`；`POST` 的 `type: "ASSIGN_BUY"` → 400；列表 `type` 文案改为 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY` |
| `GET /api/stock-positions`、`soldLots[]`（股票记录与已平仓） | 批次 / 被卖出批次的 `type` 可为 `ASSIGN_BUY` | — |
| `GET /api/cash/ledger` | `sourceType` 新取值 `OPTION_ASSIGN` | — |
| `GET /api/audit-log` | — | `OPTION_TRADE` 的删除审计可以是被指派（`before.action` `"ASG"`，`before` 多出被指派字段，见下） |

前端遇到不认识的枚举值应原样显示（已有约定）。

**数据库迁移（预计）`V9__put_assignment.sql`**（只放宽约束、加可空列，不改任何已有数据）：
- `option_trade`：`ck_option_trade_action` 增加 `'ASG'`；`ck_option_trade_net` 改为 `EXP` / `ASG` 的 `net_amount = 0`、其它 > 0；新增可空列 `assignment_fee NUMERIC(19,2)`（只有 `ASG` 有值，≥ 0，检查约束保证"`ASG` ⇔ 非空"）。指派日期不单独存（= `traded_at` 的美东日期）。`action` 列为 `VARCHAR(3)`，所以取值是 `ASG`。
- `stock_trade`：`ck_stock_trade_type` 增加 `'ASSIGN_BUY'`（`VARCHAR(12)` 够用）；新增可空列 `option_trade_id BIGINT`（唯一，FK → `option_trade.id`，检查约束保证"`ASSIGN_BUY` ⇔ 非空"）。`ASSIGN_BUY` 行的 `amount` 存**当前**股票成本：重放使它变化时在同一事务里改正（同 `OPTION_EXPIRE` 流水 `frozen_change` 的做法），所以 `amount > 0` 约束不变。
- 流水 `source_type` 没有约束，`OPTION_ASSIGN` 不需要改表。审计对象类型不变（仍是 `OPTION_TRADE`）。
- 测试资源 `backend/src/test/resources/db/failing-migration/V9__simulated_failure.sql` 必须改名为 `V10__simulated_failure.sql`（并改 `MigrationFromM1Test` 中的引用），否则与新的 V9 冲突。

#### 概念
- **被指派记录**：一条 `action = "ASG"` 的期权记录，与交易同表、**共用 id**（id 即录入顺序）。只能是**空头 put**（空头 call 在 FEAT-041，多头主动行权在 FEAT-014）。保存后 `contracts`、`multiplier`（取指派时点持仓的）、时间、指派费、备注都不变；**不能修改**（同 Q66 的做法），可以删除。
- **时间（Q76、Q94）**：用户填美东**指派日期** `assignmentDate`，记录时间 = 该日美东 16:00:00（按当天时区换算：`2026-11-20` → `2026-11-20T21:00:00Z`；`2027-03-19` → `2027-03-19T20:00:00Z`）。要求 `assignmentDate ≤ expirationDate`，且该时间点 ≤ 服务器当前时间（16:00:00 整允许）。不检查交易日 / 假日。
- **一次被指派 = 三条记录同生同灭**：`ASG` 期权记录 + 一条生成的股票记录（`type = "ASSIGN_BUY"`）+ 一条现金流水（`sourceType = "OPTION_ASSIGN"`）。
- **重放（同 FEAT-012 的 `EXP`）**：`ASG` 在合约重放里就是"一笔平掉 N 张空头的记录"：必须有空头持仓、不超过持仓、正好是最早 1 笔或连续多笔整批次（Q21 / Q73）、multiplier 与持仓相同（Q23）。被指派批次（`coveredLots`）按 FIFO 在读取时重放得出；补录 / 修改 / 删除后自动按新匹配。
- **由重放得出的数（读取时计算，scale 2）**：
  - 股数 `shares` = `contracts × multiplier`（保存后不变）
  - 买股金额 `purchaseAmount` = 行权价 × 股数，**向上取整到分**（Q96）。例：45 × 300 = `13500.00`；12.345 × 2 = `24.69`；12.345 × 1 = `12.35`。保存后不变
  - 释放冻结 = 被指派批次 `frozen` 之和（每批已按 Q28 取整，不重算）
  - 权利金 `premium` = 被指派批次开仓净额（`netAmount`，空头为正）之和
  - 股票成本 `stockCost` = 买股金额 − 权利金 + 指派费；必须 > 0
  - 每股成本 `stockCostPerShare` = 股票成本 ÷ 股数，HALF_UP **4 位小数**（Q88），仅显示
  - `cashChange` = −(买股金额 + 指派费)；`frozenChange` = −释放冻结；可用现金变化 = 释放冻结 − 买股金额 − 指派费
  - 期权已实现盈亏 `realizedPnl` 恒为 `"0.00"`（Q8：权利金进入股票成本，股票卖出时体现）
- **生成的股票记录 `ASSIGN_BUY`**：一个普通股票批次（标的 = 合约标的，股数，成本 = 股票成本，时间 = 指派时间），有自己的股票记录 id（股票 id 序列，录入顺序 = 被指派保存的时刻），按 `(tradedAt, 股票记录 id)` 排进该标的 FIFO（不是排在最前），之后的卖出可以用它、部分卖出按 Q69 / Q87 分摊。没有自己的流水（`cashChange` `"0.00"`、`ledgerEntryId` `null`），不能单独修改 / 删除，`hasAuditLog` 恒为 `false`。成本随被指派记录重新计算。

#### `POST /api/option-trades/assignments`
记录一次被指派。

请求体：
```json
{
  "symbol": "XYZ",
  "expirationDate": "2026-11-20",
  "strike": "45",
  "optionType": "PUT",
  "contracts": 3,
  "assignmentDate": "2026-11-20",
  "assignmentFee": "0.00",
  "note": null
}
```

| 字段 | 类型 | 必填 | 规则与 `VALIDATION_ERROR` 文案（`details[].field` = 字段名） |
|------|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | | 是 | 合约，规则与文案同 FEAT-001（标的规范化、行权价按数值比较）。`optionType` 为 `CALL` 在第 2 步拒绝（不是字段错误） |
| `contracts` | number | 是 | 用户选的张数（从持仓的 `assignChoices` 中选）。正整数，≤ 100000：`张数不能为空`、`张数必须是正整数`（0、负数、`1.5`）、`张数过大` |
| `assignmentDate` | string | 是 | 美东日期 `YYYY-MM-DD`：`指派日期不能为空`。格式不对或日期不存在 → 400 `BAD_REQUEST`（同 FEAT-001 请求体字段：`details[].field = "assignmentDate"`）。与到期日、当前时间的关系在第 3 步检查（422） |
| `assignmentFee` | string | 否 | 指派费。缺省、`null`、空串 = `0.00`。≥ 0，最多 2 位小数，≤ `9999999999999.99`：`指派费不能为负数`、`指派费最多 2 位小数`、`指派费过大`。不是数字 → 400 `BAD_REQUEST`（同 FEAT-001 金额字段） |
| `note` | string | 否 | 最多 500 个字符（Q98）：`备注最多 500 个字符`；`null`、空串、全空白保存为 `null` |
| 其它字段 | — | — | `action`、`netAmount`、`multiplier`、`tradedAtLocal` 等带了一律**忽略** |

检查顺序（任一失败即整笔拒绝，什么都不保存；422 的 `details` 为 `[]`，`message` 中文可直接显示；时间为美东 `YYYY-MM-DD HH:mm`，金额千分位 2 位小数，负数 `−`(U+2212)）：
1. **字段** → 400 `VALIDATION_ERROR` / `BAD_REQUEST`
2. **只能是空头 put**：
   - 指派时点（`assignmentDate` 16:00，同一时间已有的记录排在本次之前）该合约为**多头**（call 或 put）→ 422 `ASSIGN_LONG_POSITION`：`多头持仓不能被指派（主动行权见 FEAT-014）`（先于下一条检查：多头 call 得到这一条，验收第 1 条）
   - 否则 `optionType = CALL`（空头 call 或没有持仓）→ 422 `ASSIGN_CALL_NOT_SUPPORTED`：`卖出 call 的被指派暂不支持（FEAT-041）`（**FEAT-041 起不再返回**，空头 call 见「FEAT-041」）
3. **日期** → 422 `ASSIGNMENT_DATE_INVALID`，按以下顺序取第一条：
   - `assignmentDate` > `expirationDate`：`指派日期不能晚于到期日（2026-11-20）`
   - `assignmentDate` > 今天（服务器当前时间的美东日期）：`指派日期不能晚于今天`
   - `assignmentDate` = 今天且当前时间 < 今天美东 16:00（Q94）：`指派日期为今天时，美东 16:00 之后才能记录`
4. **有持仓**：把本次按 `(指派时间, 新 id)` 插入该合约历史，指派时点有空头持仓 → 否则 422 `NO_POSITION_TO_ASSIGN`：`2026-11-02 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派`。如果持仓在指派时点之前（或同一时间、更早录入）是被一条 `EXP` 清零的，末尾加 `（已于 2026-11-20 16:00 到期作废；如果实际是被指派，请先删除该到期作废）`
5. **整笔批次（Q73 / Q21）**：
   - 超过持仓 → 422 `CLOSE_EXCEEDS_POSITION`：`被指派 4 张超过当时持仓 3 张`
   - 不是最早 1 笔或连续多笔整批次之和 → 422 `CLOSE_NOT_WHOLE_LOTS`：`必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张`（可选张数列法同 FEAT-004：`可为 1 张`、`可为 2 张或 3 张`、`可为 1 张、3 张或 4 张`）
6. **之后的同合约记录**：插入后之后的平仓、`EXP`、`ASG`、开仓按新 FIFO 重新检查 → 422，code 按违规种类（`NO_POSITION_TO_CLOSE` / `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS` / `MULTIPLIER_MISMATCH` / `POSITION_DIRECTION_CONFLICT`），文案见下「与补录 / 修改 / 删除其它交易的交互」，如 `补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废`
7. **股票成本 > 0** → 422 `STOCK_COST_NOT_POSITIVE`：`被指派后股票成本将为 −12.00，请检查被指派批次的开仓净额`（为 0 时 `将为 0.00`）
8. **现金（Q19）**：插入流水后从指派时间起每个时点总现金、可用现金 ≥ 0 → 否则 422 `CASH_INSUFFICIENT`：`可用现金不足：需要 5.00；2026-11-20 16:00 时可用现金将变为 −5.00`。"需要" = 买股金额 + 指派费 − 释放冻结（即本次使可用现金减少的数），时点为第一个变负的时点（可能是之后某时点）。只可能因指派费或取整差异发生

响应 `201 Created`：被指派记录，表示与 `GET /api/option-trades/{id}` 相同（见下"交易表示的变化"）。例（验收第 2 条：场景 P 从空库录入，入金 id 1、流水 1；P1–P5 交易 id 1–5、流水 2–6；时钟 2026-11-23 10:00）：
```json
{
  "id": 6,
  "symbol": "XYZ",
  "expirationDate": "2026-11-20",
  "strike": "45",
  "optionType": "PUT",
  "action": "ASG",
  "contracts": 3,
  "multiplier": 100,
  "netAmount": "0.00",
  "tradedAt": "2026-11-20T21:00:00Z",
  "tradedAtLocal": "2026-11-20T16:00:00",
  "cashChange": "-13500.00",
  "frozenChange": "-13500.00",
  "contractLabel": "XYZ 2026-11-20 45 P",
  "note": null,
  "ledgerEntryId": 7,
  "createdAt": "2026-11-23T15:00:00Z",
  "coveredLots": [
    { "tradeId": 1, "action": "STO", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
      "contracts": 2, "netAmount": "240.00", "frozen": "9000.00" },
    { "tradeId": 2, "action": "STO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 1, "netAmount": "130.00", "frozen": "4500.00" }
  ],
  "realizedPnl": "0.00",
  "hasAuditLog": false,
  "expiredDirection": null,
  "assignment": {
    "assignmentDate": "2026-11-20",
    "assignmentFee": "0.00",
    "premium": "370.00",
    "shares": 300,
    "purchaseAmount": "13500.00",
    "stockCost": "13130.00",
    "stockCostPerShare": "43.7667",
    "stockTradeId": 1
  }
}
```
提前指派 + 指派费（验收第 4 条，2 张、日期 2026-11-18、费 5.00）：`tradedAt` `"2026-11-18T21:00:00Z"`、`cashChange` `"-9005.00"`、`frozenChange` `"-9000.00"`、`coveredLots` 只有交易 1、`assignment`：`assignmentFee` `"5.00"`、`premium` `"240.00"`、`shares` 200、`purchaseAmount` `"9000.00"`、`stockCost` `"8765.00"`、`stockCostPerShare` `"43.8250"`。

本次产生：
- 现金流水：`occurredAt` = 指派时间，`sourceType` `"OPTION_ASSIGN"`，`sourceId` = 被指派记录 `id`，`amount` = `cashChange`，`frozenChange` = 本记录 `frozenChange`，`remark` = `note`，`description` = `被指派 <张数> 张 <contractLabel>，买入 <股数> 股 <标的>`，multiplier ≠ 100 时在合约后加 `（multiplier <m>）`：`被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ`、`被指派 2 张 QFZ 2026-11-20 12.345 P（multiplier 1），买入 2 股 QFZ`。
- 股票记录 `ASSIGN_BUY`（见下"股票记录的变化"），`id` = `assignment.stockTradeId`。
- 不写审计（新增，Q42），`hasAuditLog` 为 `false`。

错误汇总：

| code | HTTP | `message` |
|------|------|-----------|
| `VALIDATION_ERROR` | 400 | 字段规则（见上表） |
| `BAD_REQUEST` | 400 | 同 FEAT-001；`assignmentDate` 格式不对 |
| `ASSIGN_CALL_NOT_SUPPORTED` | 422 | `卖出 call 的被指派暂不支持（FEAT-041）`（FEAT-041 起不再返回） |
| `ASSIGN_LONG_POSITION` | 422 | `多头持仓不能被指派（主动行权见 FEAT-014）` |
| `ASSIGNMENT_DATE_INVALID` | 422 | `指派日期不能晚于到期日（2026-11-20）` / `指派日期不能晚于今天` / `指派日期为今天时，美东 16:00 之后才能记录` |
| `NO_POSITION_TO_ASSIGN` | 422 | `2026-11-02 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派`［+ `（已于 2026-11-20 16:00 到期作废；如果实际是被指派，请先删除该到期作废）`］ |
| `CLOSE_EXCEEDS_POSITION` | 422 | `被指派 4 张超过当时持仓 3 张`；或之后某条记录超量（第 6 步） |
| `CLOSE_NOT_WHOLE_LOTS` | 422 | `必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张`；或之后某条记录不再是整笔批次 |
| `NO_POSITION_TO_CLOSE` / `MULTIPLIER_MISMATCH` / `POSITION_DIRECTION_CONFLICT` | 422 | 之后某条记录违规（第 6 步） |
| `STOCK_COST_NOT_POSITIVE` | 422 | `被指派后股票成本将为 −12.00，请检查被指派批次的开仓净额` |
| `CASH_INSUFFICIENT` | 422 | `可用现金不足：需要 5.00；2026-11-20 16:00 时可用现金将变为 −5.00` |

示例（422）：
```json
{
  "code": "CLOSE_NOT_WHOLE_LOTS",
  "message": "必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张",
  "status": 422,
  "path": "/api/option-trades/assignments",
  "timestamp": "2026-11-19T15:00:00Z",
  "details": []
}
```
```json
{
  "code": "NO_POSITION_TO_ASSIGN",
  "message": "2026-11-20 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派（已于 2026-11-20 16:00 到期作废；如果实际是被指派，请先删除该到期作废）",
  "status": 422,
  "path": "/api/option-trades/assignments",
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
  "path": "/api/option-trades/assignments",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [
    { "field": "assignmentFee", "message": "指派费最多 2 位小数" },
    { "field": "contracts", "message": "张数必须是正整数" }
  ]
}
```

#### 交易表示的变化（`GET /api/option-trades`、`GET /api/option-trades/{id}`、`POST`、`PUT` 的响应）
- `action` 新增取值 `ASG`（被指派）。
- 新增字段 **`assignment`**（object \| null）：只有 `ASG` 记录有值，其它记录为 `null`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `assignment.assignmentDate` | string | 指派日期（美东 `YYYY-MM-DD`，= `tradedAtLocal` 的日期） |
| `assignment.assignmentFee` | string | 指派费，scale 2（没填为 `"0.00"`） |
| `assignment.premium` | string | 权利金 = 被指派批次开仓净额之和（按当前重放），scale 2 |
| `assignment.shares` | number | 买入股数 = `contracts × multiplier` |
| `assignment.purchaseAmount` | string | 买股金额 = 行权价 × 股数，向上取整到分（Q96） |
| `assignment.stockCost` | string | 股票成本 = `purchaseAmount − premium + assignmentFee`（按当前重放） |
| `assignment.stockCostPerShare` | string | `stockCost ÷ shares`，HALF_UP 4 位小数，仅显示 |
| `assignment.stockTradeId` | number | 生成的 `ASSIGN_BUY` 股票记录 id（`GET /api/stock-trades/{id}`） |

- `ASG` 记录的其它字段：`contracts` = 被指派张数；`multiplier` = 持仓的；`netAmount` = `"0.00"`（期权本身没有收付）；`cashChange` = −(`purchaseAmount` + `assignmentFee`)；`frozenChange` = −释放冻结；`tradedAt` = 指派日期美东 16:00（`tradedAtLocal` 如 `"2026-11-20T16:00:00"`）；`ledgerEntryId` = `OPTION_ASSIGN` 流水 id（总是有）；`note` = 备注；`coveredLots` 同平仓（按当前 FIFO）；`realizedPnl` = `"0.00"`；`expiredDirection` = `null`；`hasAuditLog` 恒为 `false`。
- 页面上 `ASG` 行的小字 `买入 300 股 XYZ，现金 −13,500.00`（指派费 > 0 时加 `，指派费 5.00`）分别取 `assignment.shares`、`symbol`、`cashChange`、`assignment.assignmentFee`。
- **可改 / 可删**：前端按 `action` 判断——`ASG` 与 `EXP` 一样只能删除，没有"修改"；"历史"按 `hasAuditLog`（`ASG` 行不显示）。
- `GET /api/option-trades?action=ASG`：只看被指派；可与其它方向多选。排序不变（时间倒序，同一时间 id 大的在上）。不认识的值 → 400 `BAD_REQUEST`，`方向只能是 BTO、STO、BTC、STC、EXP、ASG`。
- `POST /api/option-trades` 收到 `action: "ASG"` → 400 `VALIDATION_ERROR`（`本功能只支持开仓（BTO / STO），平仓请使用平仓功能`）；`POST /api/option-trades/closes` 收到 `ASG` → 400 `VALIDATION_ERROR`（`本功能只支持平仓（BTC / STC），开仓请使用开仓功能`）。被指派只能用 `POST /api/option-trades/assignments`。

#### `PUT /api/option-trades/{id}` 对 `ASG`
422 `ASSIGNMENT_NOT_EDITABLE`，`message`：`被指派不能修改，记错请删除后重新记录`；什么都不改、不写审计。（在找到记录之后、其它业务检查之前判断；请求体本身不合法时先返回 400。其它账户的或不存在的 id 仍是 404。）

#### `DELETE /api/option-trades/{id}` 对 `ASG`
把删除应用到全部历史上重放，按顺序检查，任一失败即 422、什么都不改、不写审计：
1. **合约重放**：被指派批次恢复为持仓；之后同合约的记录（如之后的 `EXP`、另一条 `ASG`、平仓）按新历史重新检查，code 按违规种类，文案同 FEAT-012 删除，如 `删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张），请先删除该到期作废`。
2. **股票重放（Q15）**：生成的批次消失，该标的之后每一笔卖出仍不超过当时持股 → 否则 `SHARES_INSUFFICIENT`，FEAT-011 删除文案：`删除后 2026-11-20 16:00 的卖出 XYZ 100 股将超过当时持股 0 股`。
3. **现金重放（Q19）**：现金、冻结从指派时间起恢复，之后每个时点总现金、可用现金 ≥ 0 → 否则 `CASH_INSUFFICIENT`：`可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −0.01`。

成功 `204 No Content`：被指派记录、生成的股票记录、流水一起删除；写**一条**审计：`operation` `DELETE`，`objectType` `OPTION_TRADE`，`objectId` = 被指派记录 id；`summary` = `被指派 <contractLabel> <张数> 张（买入 <股数> 股 <标的>）`，如 `被指派 XYZ 2026-11-20 45 P 3 张（买入 300 股 XYZ）`；`after` `null`；`changes` `[]`；`sideEffects` 同 FEAT-005（删除引起其它平仓 / `EXP` / `ASG` 的冻结变动或股票成本改正时）。生成的股票记录**不**另写 `STOCK_TRADE` 审计。删除后可以重新记录（新记录、新 id、新股票记录 id，`hasAuditLog` `false`）。

审计 `before`：期权交易的全部字段（FEAT-005）+ 被指派专有的平铺字段（只有 `action` 为 `ASG` 时出现）：
```json
{
  "id": 6, "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
  "contractLabel": "XYZ 2026-11-20 45 P", "action": "ASG", "contracts": 3, "multiplier": 100,
  "netAmount": "0.00", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "note": null, "cashChange": "-13500.00", "frozenChange": "-13500.00",
  "assignmentDate": "2026-11-20", "assignmentFee": "0.00", "shares": 300,
  "purchaseAmount": "13500.00", "stockCost": "13130.00", "stockTradeId": 1
}
```

#### 与补录 / 修改 / 删除其它交易的交互（FEAT-001 / 004 / 005 / 012）
`ASG` 作为"一笔平掉 N 张空头的记录"参与合约重放（同 `EXP`），它生成的股票批次参与该标的的股票重放，它的流水参与现金重放。检查顺序：合约 → 股票成本 → 现金。

拒绝文案（`<改动>` 为 `补录` / `修改` / `删除`，时间为美东 `YYYY-MM-DD HH:mm`；code 同 FEAT-012：`NO_POSITION_TO_CLOSE` / `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS` / `MULTIPLIER_MISMATCH` / `POSITION_DIRECTION_CONFLICT`）：
- 被修改 / 删除的开仓正是该 `ASG` 的被指派批次之一：`该开仓被 2026-11-20 16:00 的被指派 3 张覆盖（<改动>后被指派将超过当时持仓 2 张），请先删除该被指派`；括号内其它原因：`<改动>后被指派将没有持仓可被指派`、`<改动>后被指派将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张）`、`<改动>后被指派将与当时持仓的 multiplier 10 不一致`
- 其它情况：`<改动>后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的被指派 3 张将超过当时持仓 1 张，请先删除该被指派`；`… 的被指派 3 张将没有持仓可被指派，请先删除该被指派`；`… 的被指派 3 张将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张），请先删除该被指派`；`… 的被指派 3 张将遇到多头持仓（1 张），请先删除该被指派`（`POSITION_DIRECTION_CONFLICT`）；multiplier：`<改动>后该合约（…）在 2026-11-20 16:00 的被指派 multiplier 为 100，与当时持仓的 multiplier 10 不一致，请先删除该被指派`
- 改动使某条 `ASG` 的股票成本 ≤ 0 → 422 `STOCK_COST_NOT_POSITIVE`：`<改动>后被指派 #6 的股票成本将为 −12.00，请先删除该被指派`
- 已有的 `EXP` 文案（FEAT-012）、平仓文案（FEAT-004 / 005）不变；记录被指派本身的第 6 步也用这些文案，`<改动>` = `补录`。

允许的改动（被指派仍成立）：修改被指派批次的开仓净额 → `premium`、`stockCost`、生成的股票批次成本、之后用到该批次的股票卖出的 `soldCost` / `realizedPnl` 都随之变化；修改开仓张数但 `ASG` 仍是整笔批次 → 按新 FIFO 重新匹配（`shares`、`purchaseAmount` 不变，`premium`、释放冻结、`stockCost` 变化，流水 `frozenChange` 改正）。

**连带变化（Q42 延伸）**：一次修改 / 删除使某条 `ASG` 的冻结变动或股票成本改变时，写进那次审计的 `sideEffects`（不变不写），每项：`tradeId`（`ASG` id）、`action` `"ASG"`、`contractLabel`、`field`（`frozenChange` 或 **`stockCost`**——`sideEffects[].field` 不再"总是 `frozenChange`"）、`before` / `after`（显示用金额）、`text`：`被指派 #6 冻结变动 −13,500.00 → −9,000.00`、`被指派 #6 股票成本 13,130.00 → 13,125.00`。股票卖出盈亏的变化不写 `sideEffects`。被连带改正的 `ASG`、`ASSIGN_BUY` 的 `hasAuditLog` 不变（仍为 `false`）。

同一时间（指派日期 16:00）按录入顺序：在 `ASG` 之后录入、成交时间正好 16:00 的同合约平仓若因此没有持仓 → `NO_POSITION_TO_CLOSE`（FEAT-004 原文案）；同标的的股票卖出在 `ASG` 之后录入（股票记录 id 更大）时可以用到该批次，之前录入的则不能。

#### `GET /api/option-positions` 的变化（新增字段，兼容）

| 字段 | 类型 | 说明 |
|------|------|------|
| `positions[].assignable` | boolean | "被指派"按钮按它显示：`direction = SHORT` 且 `optionType = PUT` 时**任何时刻**为 `true`（到期前、到期日各时段含 `expiryPending`、已过期，Q95）；多头、空头 call 为 `false` |
| `positions[].assignChoices` | array | 可被指派的张数选项（当前 FIFO 最早 1 笔、2 笔……整批次的累计张数，张数与 `allowedCloseContracts` 相同），升序，每项 `{ "contracts": number, "shares": number }`（`shares` = 张数 × multiplier）。`assignable` 为 `false` 时 `[]`。对话框默认选最后一项（全部） |
| `positions[].assignmentDateMax` | string \| null | 指派日期最晚可选（美东 `YYYY-MM-DD`）= min(到期日, 当前时间 ≥ 今天美东 16:00 ? 今天 : 昨天)。`assignable` 为 `false` 时 `null` |
| `positions[].assignmentDateDefault` | string \| null | 指派日期默认值 = `assignmentDateMax`（通常就是到期日）。`assignable` 为 `false` 时 `null` |

已有的 `closable`、`expiryPending`、`expirable` 口径不变；`assignable` 与它们独立（到期日 16:00 前空头 put 同时 `closable` 与 `assignable`；16:00–17:30 只有 `assignable`；17:30 起 `expirable` 与 `assignable`）。

示例（时钟 2026-11-23 10:00 美东，场景 P 的 XYZ）：
```json
{
  "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
  "contractLabel": "XYZ 2026-11-20 45 P", "direction": "SHORT", "contracts": 3, "multiplier": 100,
  "openingNetTotal": "370.00", "averageNetPerContract": "123.33", "frozenCash": "13500.00",
  "dte": -3, "expirationStatus": "EXPIRED", "daysPastExpiration": 3, "lotCount": 2, "lots": [],
  "closeAction": "BTC", "allowedCloseContracts": [2, 3], "closable": false,
  "expiryPending": false, "expirable": true, "expiryContracts": 3,
  "expiryAt": "2026-11-20T21:00:00Z", "expiryAtLocal": "2026-11-20T16:00:00",
  "assignable": true,
  "assignChoices": [ { "contracts": 2, "shares": 200 }, { "contracts": 3, "shares": 300 } ],
  "assignmentDateMax": "2026-11-20",
  "assignmentDateDefault": "2026-11-20"
}
```
（`lots` 省略。）时钟 2026-11-19 10:00 时同一行：`"assignmentDateMax": "2026-11-18"`、`"assignmentDateDefault": "2026-11-18"`；2026-11-19 16:00 起为 `"2026-11-19"`。多头 / 空头 call 行：`"assignable": false, "assignChoices": [], "assignmentDateMax": null, "assignmentDateDefault": null`。

#### `GET /api/closed-trades` 的变化
- `ASG` 记录作为一行（`type` `"OPTION"`）：`action` `"ASG"`，`closedDirection` `"SHORT"`，`tradedAt` = 指派时间，`contracts` / `multiplier`，`openingNetTotal` = 权利金、`openingNetPerContract`（HALF_UP 到分）、`coveredLots` 同平仓，`closeNetAmount` / `closeNetPerContract` = `"0.00"`，**`realizedPnl` = `"0.00"`**（例外：不等于 `openingNetTotal + closeNetAmount`），`note` = 备注，`hasAuditLog` `false`，`shares` / `costPerShare` / `pricePerShare` / `soldLots` = `null`（股票专用字段不变）。
- 新增行字段 **`assignment`**（object \| null）：`ASG` 行为 `{ "shares": 300, "stockCost": "13130.00", "stockTradeId": 1 }`（页面小字 `权利金已计入股票成本（300 股 XYZ，成本 13,130.00）`）；其它行（含股票行）为 `null`。
- 计入 `totalItems` / `filteredCount`（Q97）与 `filteredRealizedPnlTotal`（加 0.00）。
- `action` 筛选接受 `BTC` / `STC` / `EXP` / `ASG`（可多选）；不传 = 全部。`ASG` 是期权专用条件（Q90：传了方向，股票行就不出现）。其它值 → 400 `BAD_REQUEST`，`方向只能是 BTC、STC、EXP、ASG`。
- `startDate` / `endDate` 按指派时间的美东日期。排序规则不变（同一时间股票行在期权行之上，同类 id 大的在上）。

`ASG` 行示例：
```json
{
  "type": "OPTION", "id": 6, "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
  "contractLabel": "XYZ 2026-11-20 45 P", "action": "ASG", "closedDirection": "SHORT",
  "contracts": 3, "multiplier": 100, "shares": null,
  "openingNetTotal": "370.00", "openingNetPerContract": "123.33", "costPerShare": null,
  "closeNetAmount": "0.00", "closeNetPerContract": "0.00", "pricePerShare": null,
  "realizedPnl": "0.00", "note": null,
  "coveredLots": [
    { "tradeId": 1, "action": "STO", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
      "contracts": 2, "netAmount": "240.00", "frozen": "9000.00" },
    { "tradeId": 2, "action": "STO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 1, "netAmount": "130.00", "frozen": "4500.00" }
  ],
  "soldLots": null,
  "assignment": { "shares": 300, "stockCost": "13130.00", "stockTradeId": 1 },
  "hasAuditLog": false
}
```

#### `GET /api/pnl-summary`、`GET /api/dashboard`
没有新字段。`ASG` 按指派时间的美东日期 / 月份归属，计入笔数（`closedCount`、`option.closedCount`、首页 `realizedPnl.closedCount` / `currentMonthRealizedPnl.closedCount`），金额 `0.00`（Q97）。例：只有验收第 2 条的被指派时，`closedCount` 1、`realizedPnlTotal` `"0.00"`，`bySymbol` 中 XYZ 一行 `option` `{ "closedCount": 1, "realizedPnl": "0.00" }`、`stock` `{ "closedCount": 0, "realizedPnl": "0.00" }`。之后卖出这些股票的盈亏按卖出时间计入 `stock`。首页 `positions.expiredUnprocessedCount` 在过期持仓被全部指派后随之减少。

#### 股票记录（FEAT-011）的变化
- `type` 新增 **`ASSIGN_BUY`**（指派买入），只能由被指派生成。
- 股票记录表示新增字段（所有类型都有）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `assignmentTradeId` | number \| null | `ASSIGN_BUY`：生成它的被指派记录 id（`GET /api/option-trades/{id}`，页面 `来自被指派 #6`）；其它为 `null` |
| `editable` | boolean | 能否 `PUT`：`ASSIGN_BUY` 为 `false`，其它 `true` |
| `deletable` | boolean | 能否 `DELETE`：`ASSIGN_BUY` 为 `false`，其它 `true` |

- `ASSIGN_BUY` 的其它字段：`amount` = 当前股票成本（= 被指派的 `assignment.stockCost`，随重放变化）；`cashChange` `"0.00"`（现金在被指派的流水里）；`pricePerShare` = `amount ÷ shares` HALF_UP 4 位（即每股成本）；`tradedAt` / `tradedAtLocal` = 指派时间；`tradeDate` `null`；`note` `null`（备注在被指派记录上）；`ledgerEntryId` `null`；`hasAuditLog` `false`；`soldCost` / `realizedPnl` / `soldLots` `null`。

```json
{
  "id": 1, "type": "ASSIGN_BUY", "symbol": "XYZ", "shares": 300, "amount": "13130.00", "cashChange": "0.00",
  "pricePerShare": "43.7667", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "tradeDate": null, "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
  "hasAuditLog": false, "soldCost": null, "realizedPnl": null, "soldLots": null,
  "assignmentTradeId": 6, "editable": false, "deletable": false
}
```
- `POST /api/stock-trades` 带 `type: "ASSIGN_BUY"` → 400 `VALIDATION_ERROR`，field `type`，`指派买入由被指派生成，不能直接录入`。
- `PUT` / `DELETE /api/stock-trades/{id}` 对 `ASSIGN_BUY` → 422 `STOCK_TRADE_FROM_ASSIGNMENT`，`message`：`该股票记录由被指派生成，请在交易列表删除对应的被指派记录`；什么都不改、不写审计（在找到记录之后、其它业务检查之前判断；`PUT` 请求体本身不合法时先返回 400；其它账户 / 不存在 → 404）。
- `GET /api/stock-trades?type=ASSIGN_BUY` 可筛选（可与其它类型多选）；不认识的值 → 400 `BAD_REQUEST`，`类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY`。日期筛选按指派时间的美东日期。
- `GET /api/stock-positions` 的 `lots[].type`、股票记录与已平仓的 `soldLots[].type` 可为 `ASSIGN_BUY`（`lots[].note`、`tradeDate` 为 `null`）。验收第 2 条之后：XYZ `shares` 300、`totalCost` `"13130.00"`、`averageCost` `"43.7667"`，一个批次 `type` `ASSIGN_BUY`、`tradedAtLocal` `"2026-11-20T16:00:00"`。
- 股票买卖的持股检查、现金检查（FEAT-011）把 `ASSIGN_BUY` 当作普通批次（持股 +股数，不动现金）。

#### 现金（FEAT-007）
- `sourceType` 新增 **`OPTION_ASSIGN`**（被指派），`sourceId` = 被指派记录 id（`GET /api/option-trades/{id}`）。`amount` = −(买股金额 + 指派费)，`frozenChange` = −释放冻结（可能被重放改正，同 `OPTION_EXPIRE`），`description` 见上，`remark` = 备注，`hasAuditLog` 恒为 `false`。没有修改 / 删除接口（到交易列表删除被指派，`sourceId` 即其 id）。
- 现金汇总按流水重放：验收第 2 条后 `{"totalCash": "6937.00", "frozenCash": "3000.00", "availableCash": "3937.00"}`。

流水行示例：
```json
{
  "id": 7, "occurredAt": "2026-11-20T21:00:00Z", "sourceType": "OPTION_ASSIGN", "sourceId": 6,
  "amount": "-13500.00", "balanceAfter": "6937.00", "frozenChange": "-13500.00",
  "description": "被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ", "remark": null, "hasAuditLog": false
}
```

#### `hasAuditLog` 汇总
`ASG` 交易行、`ASG` 已平仓行、`OPTION_ASSIGN` 流水行、`ASSIGN_BUY` 股票记录恒为 `false`（都不能修改；删除后行不存在）。

#### 已知限制
- `assignChoices` 是**当前**持仓的选项；指派日期较早时实际持仓可能不同（例如之后又开了新批次），以提交时第 4–5 步的检查为准。
- 今天 16:00 之前刚开的仓：`assignmentDateMax` 是昨天，此时没有持仓 → 提交得到 `NO_POSITION_TO_ASSIGN`（今天 16:00 起可选今天）。
- 拆股（FEAT-028）、call 被指派（FEAT-041）、主动行权（FEAT-014）不在本节。

