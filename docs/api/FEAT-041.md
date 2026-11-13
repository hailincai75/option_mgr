# FEAT-041 卖出 call 被指派（`ASG` 扩展到空头 call）

沿用 FEAT-013 的全部做法（同一接口 `POST /api/option-trades/assignments`、`ASG` 记录、时间 / 日期规则、整笔批次、不可修改、删除、重放、按账户隔离、`hasAuditLog` 恒为 `false`）。本节只写**新增或不同**的部分；put 被指派的数字、文案全部不变。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades/assignments` | — | 空头 call 不再被拒（`ASSIGN_CALL_NOT_SUPPORTED` 不再返回，code 保留为已废弃，前端可删除对它的特殊处理）；新的拒绝 `SHARES_INSUFFICIENT`、`ASSIGNMENT_PROCEEDS_NOT_POSITIVE` |
| 期权交易表示的 `assignment` | 新字段 `saleAmount`、`proceeds`、`soldCost`、`stockRealizedPnl`、`soldLots` | put 专用字段 `purchaseAmount`、`stockCost`、`stockCostPerShare` 变为 string \| null（call 为 `null`）；call 的 `ASG` 行 `cashChange` 为**正**（+行权金额 − 指派费），`frozenChange` `"0.00"` |
| 股票记录（`/api/stock-trades/**`） | `type` 新取值 `ASSIGN_SELL` | `ASSIGN_SELL` 不能 `POST` / `PUT` / `DELETE`（同 `ASSIGN_BUY`）；列表 `type` 文案改为 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL`；`soldCost` / `realizedPnl` / `soldLots` 对 `SELL` **和 `ASSIGN_SELL`** 有值 |
| 股票买卖与股票重放（FEAT-011） | — | `ASSIGN_SELL` 和 `SELL` 一样参与持股检查；新的 `SHARES_INSUFFICIENT` 文案（之后的被指派不成立时） |
| 审计 `sideEffects[]` | `field` 可为 `proceeds`、`soldCost` | `STOCK_TRADE` 的修改 / 删除审计 `sideEffects` **不再恒为 `[]`**（使某条 call `ASG` 的交出成本变化时有项） |
| `GET /api/option-positions` | `positions[].underlyingShares` | 空头 call 的 `assignable` 改为 `true`（`assignChoices`、日期字段随之有值） |
| `GET /api/closed-trades` | 行字段 `assignmentTradeId`；`assignment.proceeds` | 股票行 `action` 可为 `ASSIGN_SELL`；call 的 `ASG` 行 `assignment.stockCost` 为 `null`；一次 call 被指派 = **2 行**（Q100） |
| `GET /api/pnl-summary`、`GET /api/dashboard` | — | `ASSIGN_SELL` 计入股票笔数和股票盈亏；一次 call 被指派计 **2 笔**（期权 1 笔 0.00 + 股票 1 笔） |
| `GET /api/cash/ledger` | — | `OPTION_ASSIGN` 的 `amount` 可为正，`description` 有"卖出"写法 |

**数据库迁移（预计）`V10__call_assignment.sql`**（只放宽约束，不改任何已有数据）：
- `stock_trade`：`ck_stock_trade_type` 增加 `'ASSIGN_SELL'`（11 个字符，`VARCHAR(12)` 够用）；`ck_stock_trade_option_trade` 改为"`type IN ('ASSIGN_BUY','ASSIGN_SELL')` ⇔ `option_trade_id` 非空"。`ASSIGN_SELL` 行的 `amount` 存**当前**卖出实收，重放使它变化时同一事务改正（同 `ASSIGN_BUY`）；`amount > 0` 约束不变（由 `ASSIGNMENT_PROCEEDS_NOT_POSITIVE` 保证）。
- `option_trade`、流水、审计表不变（`ASG`、`assignment_fee` 已在 V9）。
- 测试资源 `backend/src/test/resources/db/failing-migration/V10__simulated_failure.sql` 改名为 `V11__simulated_failure.sql`（并改 `MigrationFromM1Test` 中的引用）。

#### 金额（Q8 暂定、Q99；读取时按重放计算，scale 2）
- 股数 `shares` = `contracts × multiplier`（保存后不变）
- 行权金额 `saleAmount` = 行权价 × 股数，**向下取整到分**（`RoundingMode.DOWN`，Q99；put 的 `purchaseAmount` 仍向上取整）。例：9 × 300 = `2700.00`；12.345 × 1 = `12.34`。保存后不变
- 权利金 `premium` = 被指派批次开仓净额之和（同 FEAT-013）
- 卖出实收 `proceeds` = `saleAmount + premium − assignmentFee`，必须 > 0
- 交出成本 `soldCost` = 该账户该标的股票 FIFO 交出的成本（可跨批次；部分卖出一个批次按 Q69 / Q87 比例分摊，同 FEAT-011 `SELL`）
- 股票已实现盈亏 `stockRealizedPnl` = `proceeds − soldCost`（可为负）；期权 `realizedPnl` 恒为 `"0.00"`
- `cashChange` = +(`saleAmount − assignmentFee`)；`frozenChange` = `"0.00"`（空头 call 不冻结，FEAT-038）

**一次 call 被指派 = 三条记录同生同灭**：`ASG` 期权记录 + 生成的股票记录 `type = "ASSIGN_SELL"` + 一条 `OPTION_ASSIGN` 流水。

#### `POST /api/option-trades/assignments` 的差异
请求体、字段规则不变（`optionType` 可以是 `CALL`）。检查顺序（FEAT-013 的 1–6 不变，第 2 步只剩多头拒绝；之后 put 与 call 分开）：
1. 字段 → 400
2. 多头（call 或 put）→ 422 `ASSIGN_LONG_POSITION`（文案不变）
3. 日期 → 422 `ASSIGNMENT_DATE_INVALID`
4. 有持仓 → 422 `NO_POSITION_TO_ASSIGN`（文案中的合约如 `IXQZ 2026-11-20 9 C`）
5. 整笔批次 → 422 `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS`
6. 之后的同合约记录 → 422（同 FEAT-013）
7. put：股票成本 > 0（`STOCK_COST_NOT_POSITIVE`）。**call：卖出实收 > 0** → 422 `ASSIGNMENT_PROCEEDS_NOT_POSITIVE`：`被指派后股票卖出实收将为 −5.00，请检查指派费`（为 0 时 `将为 0.00`）
8. **call：持股（Q75、Q15）** → 422 `SHARES_INSUFFICIENT`。把生成的 `ASSIGN_SELL` 按 `(指派时间, 新股票记录 id)` 插入该标的股票历史（同一时间已有的股票记录排在它之前；同一时间但之后录入的不算）：
   - 指派时点持股 < 股数：`持股不足：被指派需要 300 股 IXQZ，2026-11-20 16:00 时持有 150 股。请先录入股票（转入或更早的买入）`
   - 之后已有的卖出不成立（FEAT-011 原文案）：`补录后 2026-11-24 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股`
   - 之后已有的 call 被指派不成立：`补录后 2026-11-27 16:00 的被指派 1 张 IXQZ 2026-11-27 10 C 需要 100 股，当时持股 0 股，请先删除该被指派`
9. 现金（Q19）→ 422 `CASH_INSUFFICIENT`（同 FEAT-013 文案）。call 只在指派费 > 行权金额时可能发生；"需要" = 指派费 − 行权金额

响应 `201 Created`。例（验收第 2 条：场景 C 从空库录入，入金 id 1、流水 1；S1、S2 股票记录 id 1–2、流水 2–3；C1、C2、L1 交易 id 1–3、流水 4–6；时钟 2026-11-23 10:00）：
```json
{
  "id": 4, "symbol": "IXQZ", "expirationDate": "2026-11-20", "strike": "9", "optionType": "CALL",
  "action": "ASG", "contracts": 3, "multiplier": 100, "netAmount": "0.00",
  "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "cashChange": "2700.00", "frozenChange": "0.00",
  "contractLabel": "IXQZ 2026-11-20 9 C", "note": null, "ledgerEntryId": 7,
  "createdAt": "2026-11-23T15:00:00Z",
  "coveredLots": [
    { "tradeId": 1, "action": "STO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 2, "netAmount": "60.00", "frozen": "0.00" },
    { "tradeId": 2, "action": "STO", "tradedAt": "2026-11-06T15:00:00Z", "tradedAtLocal": "2026-11-06T10:00:00",
      "contracts": 1, "netAmount": "25.00", "frozen": "0.00" }
  ],
  "realizedPnl": "0.00", "hasAuditLog": false, "expiredDirection": null,
  "assignment": {
    "assignmentDate": "2026-11-20", "assignmentFee": "0.00", "premium": "85.00", "shares": 300,
    "purchaseAmount": null, "stockCost": null, "stockCostPerShare": null,
    "saleAmount": "2700.00", "proceeds": "2785.00", "soldCost": "2550.00", "stockRealizedPnl": "235.00",
    "soldLots": [
      { "tradeId": 1, "type": "BUY", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
        "tradeDate": null, "shares": 150, "cost": "1200.00" },
      { "tradeId": 2, "type": "BUY", "tradedAt": "2026-11-04T15:00:00Z", "tradedAtLocal": "2026-11-04T10:00:00",
        "tradeDate": null, "shares": 150, "cost": "1350.00" }
    ],
    "stockTradeId": 3
  }
}
```
验收第 3 条（2 张、2026-11-18、费 5.00）：`cashChange` `"1795.00"`、`premium` `"60.00"`、`saleAmount` `"1800.00"`、`proceeds` `"1855.00"`、`soldLots` S1 150 股 `1200.00` + S2 50 股 `450.00`、`soldCost` `"1650.00"`、`stockRealizedPnl` `"205.00"`。

#### `assignment` 对象（期权交易表示）新增字段

| 字段 | 类型 | put | call |
|------|------|-----|------|
| `purchaseAmount` / `stockCost` / `stockCostPerShare` | string \| null | 同 FEAT-013 | `null` |
| `saleAmount` | string \| null | `null` | 行权金额（向下取整到分） |
| `proceeds` | string \| null | `null` | 卖出实收（按当前重放） |
| `soldCost` | string \| null | `null` | 交出成本（按当前重放） |
| `stockRealizedPnl` | string \| null | `null` | 股票已实现盈亏（按当前重放，可为负） |
| `soldLots` | array \| null | `null` | 交出的股票批次，FIFO 顺序，项同 FEAT-011 `soldLots[]`（`type` 可为 `BUY` / `TRANSFER_IN` / `ASSIGN_BUY`） |
| `stockTradeId` | number | `ASSIGN_BUY` 的 id | `ASSIGN_SELL` 的 id |

前端按 `optionType` 区分。交易列表小字 `卖出 300 股 IXQZ，现金 +2,700.00`（指派费 > 0 时加 `，指派费 5.00`）取 `assignment.shares`、`symbol`、`cashChange`、`assignmentFee`；冻结列 `—`。

#### 股票记录 `ASSIGN_SELL`
- 只能由 call 被指派生成；`POST /api/stock-trades` 带 `type: "ASSIGN_SELL"` → 400 `VALIDATION_ERROR`，field `type`，`指派卖出由被指派生成，不能直接录入`。`PUT` / `DELETE` → 422 `STOCK_TRADE_FROM_ASSIGNMENT`（文案同 FEAT-013）。
- 字段：`amount` = 当前卖出实收；`cashChange` `"0.00"`；`pricePerShare` = `amount ÷ shares` HALF_UP 4 位；时间 = 指派时间；`tradeDate` / `note` / `ledgerEntryId` `null`；`hasAuditLog` `false`；`soldCost` / `realizedPnl` / `soldLots` 同 `SELL`（= 被指派的 `soldCost` / `stockRealizedPnl` / `soldLots`）；`assignmentTradeId` = 被指派 id；`editable` / `deletable` `false`。
- 按 `(tradedAt, 股票记录 id)` 参与该标的 FIFO 与持股重放，与 `SELL` 相同。`GET /api/stock-trades?type=ASSIGN_SELL` 可筛选。

```json
{
  "id": 3, "type": "ASSIGN_SELL", "symbol": "IXQZ", "shares": 300, "amount": "2785.00", "cashChange": "0.00",
  "pricePerShare": "9.2833", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "tradeDate": null, "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
  "hasAuditLog": false, "soldCost": "2550.00", "realizedPnl": "235.00",
  "soldLots": [ "…同上 assignment.soldLots…" ],
  "assignmentTradeId": 4, "editable": false, "deletable": false
}
```

#### 现金流水
`OPTION_ASSIGN`：`amount` = +(行权金额 − 指派费)，`frozenChange` `"0.00"`，`description` = `被指派 <张数> 张 <contractLabel>，卖出 <股数> 股 <标的>`，multiplier ≠ 100 时合约后加 `（multiplier <m>）`：`被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ`、`被指派 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），卖出 1 股 QFZ`。验收第 2 条后现金汇总 `10220.00 / 0.00 / 10220.00`。

#### 删除（`DELETE /api/option-trades/{id}` 对 call `ASG`）
检查顺序同 FEAT-013（合约 → 股票 → 现金）：删除后交出的股票恢复为持仓，之后的卖出 / 被指派按新 FIFO 重新匹配（持股只会增加，股票一步不会失败）；现金可能因少了这笔收入而 `CASH_INSUFFICIENT`。成功 204，三条记录一起删除；一条审计 `summary` `被指派 IXQZ 2026-11-20 9 C 3 张（卖出 300 股 IXQZ）`；`before` = 期权交易字段 + 平铺的 `assignmentDate`、`assignmentFee`、`shares`、`premium`、`saleAmount`、`proceeds`、`soldCost`、`stockRealizedPnl`、`stockTradeId`（call 没有 `purchaseAmount` / `stockCost`）。

#### 与其它记录的交互（重放）
- **期权**：修改被指派批次净额 → `premium`、`proceeds`、`stockRealizedPnl` 变化；合约重放拒绝文案同 FEAT-013（合约写 `… 9 C`）。改动使某条 call `ASG` 的卖出实收 ≤ 0 → 422 `ASSIGNMENT_PROCEEDS_NOT_POSITIVE`：`<改动>后被指派 #4 的股票卖出实收将为 −1.00，请先删除该被指派`。
- **股票**（补录 / 修改 / 删除股票记录，删除 put `ASG` 使 `ASSIGN_BUY` 消失）：按新 FIFO 重新匹配交出批次；使某条 call `ASG` 指派时点持股不足 → 422 `SHARES_INSUFFICIENT`：`<改动>后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 150 股，请先删除该被指派`（`<改动>` = `补录` / `修改` / `删除`）。被拒的什么都不改、不写审计。
- 检查顺序：合约 → 股票成本 / 卖出实收 → 持股 → 现金。
- **连带变化（审计 `sideEffects`）**：一次修改 / 删除（期权交易或股票记录）使某条 call `ASG` 的卖出实收或交出成本变化时写入（不变不写；补录不写审计，所以也没有）。每项 `tradeId`（`ASG` id）、`action` `"ASG"`、`contractLabel`、`field` `proceeds` 或 `soldCost`、`before` / `after`、`text`：`被指派 #4 股票卖出实收 2,785.00 → 2,790.00`、`被指派 #4 交出成本 2,550.00 → 2,350.00`。股票盈亏的变化不单独写。

#### `GET /api/option-positions`
- `assignable`：`direction = SHORT` 时（put 或 call）任何时刻为 `true`；多头为 `false`。`assignChoices`、`assignmentDateMax`、`assignmentDateDefault` 规则同 FEAT-013，空头 call 也有值。
- 新字段 **`underlyingShares`**（number \| null）：空头 call 行 = 该账户该标的**当前**持股（可为 0）；其它行 `null`。仅显示，提交时以第 8 步为准。

例（时钟 2026-11-23，9 C 行，只列相关字段）：`"direction": "SHORT", "frozenCash": "0.00", "assignable": true, "assignChoices": [ { "contracts": 2, "shares": 200 }, { "contracts": 3, "shares": 300 } ], "assignmentDateMax": "2026-11-20", "assignmentDateDefault": "2026-11-20", "underlyingShares": 300`。

#### `GET /api/closed-trades`（Q100：一次 call 被指派 = 2 行、2 笔）
- 期权行（`type` `OPTION`，`action` `ASG`）：同 FEAT-013，`realizedPnl` `"0.00"`，`openingNetTotal` = 权利金；`assignment` = `{ "shares": 300, "stockCost": null, "proceeds": "2785.00", "stockTradeId": 3 }`（put 行 `proceeds` 为 `null`）。页面小字 `权利金已计入股票卖出（300 股 IXQZ，实收 2,785.00）`。
- 股票行（`type` `STOCK`，**`action` `ASSIGN_SELL`**）：字段同 `SELL` 行（FEAT-040）：`closeNetAmount` = 卖出实收，`openingNetTotal` = −交出成本，`realizedPnl` = 股票盈亏，`soldLots`；新行字段 **`assignmentTradeId`** = 被指派 id（页面 `来自被指派 #4`），其它行 `null`。
- 同一 `tradedAt`，股票行在期权行之上（FEAT-040 排序）。`type=STOCK` 包含 `ASSIGN_SELL`；传 `action`（期权专用）时股票行不出现，`action` 仍不接受 `ASSIGN_SELL` / `SELL`。
- 验收第 2 条后：2 行（`ASSIGN_SELL` `235.00` 在上、`ASG` `0.00` 在下），`totalItems` 2，`filteredRealizedPnlTotal` `"235.00"`。

#### `GET /api/pnl-summary`、`GET /api/dashboard`
没有新字段。`ASSIGN_SELL` 与 `SELL` 一样计入 `stock`（笔数、盈亏，按指派时间的美东日期 / 月份）；`ASG` 计入 `option` 1 笔 0.00。验收第 2 条后 `closedCount` 2、`realizedPnlTotal` `"235.00"`；IXQZ 行 `option` `{ "closedCount": 1, "realizedPnl": "0.00" }`、`stock` `{ "closedCount": 1, "realizedPnl": "235.00" }`；首页 `"realizedPnl": { "closedCount": 2, "total": "235.00" }`。

#### 错误汇总（新增 / 变化）

| code | HTTP | `message` |
|------|------|-----------|
| `ASSIGN_CALL_NOT_SUPPORTED` | — | 不再返回（废弃） |
| `SHARES_INSUFFICIENT` | 422 | `持股不足：被指派需要 300 股 IXQZ，2026-11-20 16:00 时持有 150 股。请先录入股票（转入或更早的买入）`；`<改动>后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 150 股，请先删除该被指派`；FEAT-011 卖出文案 |
| `ASSIGNMENT_PROCEEDS_NOT_POSITIVE` | 422 | `被指派后股票卖出实收将为 −5.00，请检查指派费`；`<改动>后被指派 #4 的股票卖出实收将为 −1.00，请先删除该被指派` |
| `STOCK_TRADE_FROM_ASSIGNMENT` | 422 | 也用于 `ASSIGN_SELL`（文案不变） |

```json
{
  "code": "SHARES_INSUFFICIENT",
  "message": "持股不足：被指派需要 100 股 IXQZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）",
  "status": 422,
  "path": "/api/option-trades/assignments",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": []
}
```

#### 已知限制
- 盈亏口径是 Q8 暂定方案；第一次真实 call 被指派后对照券商报表复核。
- 只按股票 FIFO 交出，不看覆盖（FEAT-039）；不能指定交出批次。
- `underlyingShares` 是当前持股，指派日期较早时实际持股可能不同，以提交时检查为准。
