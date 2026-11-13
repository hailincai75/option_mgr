# FEAT-008 盈亏汇总与首页概览

只读。已实现盈亏全部来自 FEAT-006 的已平仓记录（同一套 FIFO 重放与 Q21 整笔计算），不含入金、出金（Q26）。

> **FEAT-040 起**：盈亏汇总与首页的已实现盈亏包含股票卖出，汇总另给 `option` / `stock` 拆分，首页另给 `stockPositions.positionCount`；见文末「FEAT-040」。

**归属规则**：一笔平仓的盈亏归属于其**平仓成交时间的美东日期**及该日期所在的**美东自然月**（例：美东 2026-10-31 23:30 = `2026-11-01T03:30:00Z` 属于 2026-10）。"今天 / 本月" = 服务器当前时间的美东日期 / 美东自然月。

**一致性**：同一日期范围内，`GET /api/pnl-summary` 的 `realizedPnlTotal` = `GET /api/closed-trades` 的 `filteredRealizedPnlTotal`；`bySymbol`、`byMonth` 各自的合计都等于总计。首页的数字与 `GET /api/cash/summary`、`GET /api/option-positions`、`GET /api/closed-trades` 一致。

#### `GET /api/dashboard`
首页概览（Q48）。无参数。

响应 `200 OK`：
```json
{
  "asOfDate": "2026-11-12",
  "currentMonth": "2026-11",
  "cash": {
    "totalCash": "101043.05",
    "frozenCash": "44000.00",
    "availableCash": "57043.05"
  },
  "realizedPnl": { "closedCount": 6, "total": "703.05" },
  "currentMonthRealizedPnl": { "closedCount": 4, "total": "633.05" },
  "positions": {
    "positionCount": 3,
    "frozenTotal": "44000.00",
    "expiringWithin7DaysCount": 1,
    "expiredUnprocessedCount": 1
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `asOfDate` | string | 今天的美东日期 `YYYY-MM-DD`（页面"截至 …（美东）"） |
| `currentMonth` | string | 本月（美东）`YYYY-MM` |
| `cash` | object | 同 `GET /api/cash/summary`：`totalCash`、`frozenCash`、`availableCash` |
| `realizedPnl` | object | 全部平仓：`closedCount` 笔数、`total` 已实现盈亏合计（scale 2，带符号） |
| `currentMonthRealizedPnl` | object | 平仓美东日期在本月（本月 1 日至今）的笔数与合计；没有时 `0` / `"0.00"`。可用 `GET /api/closed-trades?startDate=<本月 1 日>&endDate=<asOfDate>` 看明细 |
| `positions.positionCount` / `frozenTotal` | number / string | 同 `GET /api/option-positions` 的 `positionCount` / `frozenTotal` |
| `positions.expiringWithin7DaysCount` | number | 0 ≤ DTE ≤ 7 的持仓个数（含今天到期） |
| `positions.expiredUnprocessedCount` | number | DTE < 0 的持仓个数（已过期未处理） |

错误：只有通用错误。

#### `GET /api/pnl-summary?startDate=&endDate=`
盈亏汇总（Q49、Q50）。不分页。

| query 参数 | 规则 |
|------------|------|
| `startDate` / `endDate` | 可选，平仓成交时间的**美东日期**，两端包含，可只填一端，都不填 = 全部时间。格式、校验与错误同 FEAT-002/006：格式不对或日期不存在 → 400 `BAD_REQUEST`（`日期格式不正确（YYYY-MM-DD）`）；开始晚于结束 → 400 `VALIDATION_ERROR`，field `endDate`，`开始日期不能晚于结束日期` |

响应 `200 OK`：
```json
{
  "startDate": null,
  "endDate": null,
  "closedCount": 6,
  "realizedPnlTotal": "703.05",
  "bySymbol": [
    { "symbol": "AAPL", "closedCount": 3, "realizedPnl": "248.70" },
    { "symbol": "MSFT", "closedCount": 1, "realizedPnl": "350.00" },
    { "symbol": "TSLA", "closedCount": 1, "realizedPnl": "120.00" },
    { "symbol": "XYZ", "closedCount": 1, "realizedPnl": "-15.65" }
  ],
  "byMonth": [
    { "month": "2026-11", "closedCount": 4, "realizedPnl": "633.05" },
    { "month": "2026-10", "closedCount": 2, "realizedPnl": "70.00" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `startDate` / `endDate` | string \| null | 本次使用的范围（原样回显，未填为 `null`） |
| `closedCount` / `realizedPnlTotal` | number / string | 范围内平仓笔数 / 已实现盈亏合计（= `GET /api/closed-trades` 相同范围的 `filteredCount` / `filteredRealizedPnlTotal`） |
| `bySymbol[]` | array | 每个有平仓的标的一行：`symbol`、`closedCount`、`realizedPnl`。按标的升序（字符编码序，与持仓排序相同）。点击跳转：`GET /api/closed-trades?symbol=<symbol>&startDate=…&endDate=…` |
| `byMonth[]` | array | 每个有平仓的美东自然月一行：`month`（`YYYY-MM`）、`closedCount`、`realizedPnl`。按月份**倒序**；没有平仓的月份不出现 |

范围内没有平仓：`closedCount` 0、`realizedPnlTotal` `"0.00"`，`bySymbol`、`byMonth` 都是 `[]`。前端的"合计"行直接用 `closedCount` / `realizedPnlTotal`。

