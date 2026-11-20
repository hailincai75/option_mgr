# FEAT-017 未实现盈亏与市值（只读，实时计算，不存库）

> **FEAT-035 修订**：`GET /api/positions/unrealized-summary` 接受 `X-Account-Id: ALL`（结构不变，全部账户之和）。见 [FEAT-035.md](FEAT-035.md)。
>
> **FEAT-048 修订**：期权 / 股票持仓的每个批次（`lots[]`）也带同名 5 个字段，用行的同一个 mark / 现价；行上的值、合计接口都不变，且各批次市值 / 未实现之和始终等于行（最后一个批次取余数，Q238 A）。见 [FEAT-048.md](FEAT-048.md)。
>
> **FEAT-052 修订**：计算规则与字段不变。`optionQuote.mark` 可来自 Yahoo（`markBasis` `MID`，或从未有过有效报价时的 `LAST` = `lastPrice`）；Yahoo 收盘后 0 / 缺失的 bid / ask 不覆盖已存的有效报价，所以收盘后市值 / 未实现按盘中最后一次有效报价（Q265）。股票行 `quote.source` 可为 `YAHOO`。见 [FEAT-052.md](FEAT-052.md)。

口径全部已定：Q152–Q161（以及引用的 Q6 / Q8 / Q21 / Q22 / Q67 / Q69 / Q87 / Q150 / Q151）。行情字段沿用 [FEAT-016.md](FEAT-016.md)，本节只写**新增**的部分。示例标的均为虚构（QRTV、IXQZ），数字与需求验收一致（今天 2026-11-23，10:00 拉取成功）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/option-positions` | `positions[]` 新字段 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel`（纯新增） | — |
| `GET /api/stock-positions` | `positions[]` 同上 5 个字段（纯新增） | — |
| `POST /api/option-trades/expiration-adjustments`（FEAT-043） | 响应里的 `position`（与持仓行同一结构）也带上面 5 个字段（纯新增） | — |
| `GET /api/positions/unrealized-summary` | 新接口（账户合计，要求 `X-Account-Id`） | — |
| 其它所有接口（含 `/api/market-data/**`、首页 `GET /api/dashboard`、盈亏汇总） | — | — （首页不改，Q159） |

已有字段、排序、错误都不变；旧客户端忽略新字段即可。没有新的写操作，没有数据库迁移：每次请求按当时的持仓和 FEAT-016 显示的 mark / 现价实时计算。

---

#### 概念
- **使用的价格**：期权行用 `optionQuote.mark`（FEAT-016 的**显示用 mark**：含手动覆盖、拉取失败时保留的上次报价、`MISSING_FROM_SOURCE` 的最后报价、`markBasis = THEO` 的 theo）；股票行用 `quote.price`（含 Finnhub 来源、手动覆盖、失败保留）。与同一响应里返回的行情字段是**同一次读取**的值，所以行内一定对得上。
- **可计算状态** `unrealizedStatus`（每行一个，判定顺序如下，先命中的为准）：
  1. `EXPIRED_PENDING`（仅期权）：`expirationDate` < `asOfDate`（美东今天），即 FEAT-003 `expirationStatus = EXPIRED`（Q150 / Q156）。**即使有 mark（库里最后报价或手动覆盖）也不计算**。到期日当天（`EXPIRES_TODAY`）正常计算。
  2. `NO_QUOTE`：期权 `optionQuote.mark = null`（`NEVER_QUOTED`、指数标的、bid 为 0 且无 theo 的情况都会落在这里，FEAT-016 已把它们表示为 `mark = null`，本需求不再单独判断指数或状态）；股票 `quote.price = null`。
  3. `OK`：其余情况，计算市值、未实现盈亏、百分比。
- **标签** `unrealizedLabel`：`EXPIRED_PENDING` → `已到期，待处理`；`NO_QUOTE` → `无报价`；`OK` → `null`。页面"市值""未实现盈亏"两列都原样显示这段文字（灰色），不显示百分比。
- **金额规则**（Q152–Q155）：
  - 期权市值 = `mark × contracts × multiplier`，HALF_UP 四舍五入到分；多头为正、空头为负（先对正数取整再加符号，因 HALF_UP 对称，结果相同）。
  - 期权未实现盈亏 = `openingNetTotal`（FEAT-003，空头 +、多头 −）+ 取整后的市值。不预估平仓手续费（Q153）。
  - 股票市值 = `quote.price × shares`，HALF_UP 到分；股票未实现盈亏 = 市值 − `totalCost`（FEAT-011，已是剩余成本，Q8 / Q69 / Q87；被 covered call 覆盖的股数照常计入）。
  - 百分比 = 未实现盈亏 ÷ 分母 × 100，HALF_UP 到 2 位小数；期权分母 = `|openingNetTotal|`，股票分母 = `totalCost`。分母为 `0.00` → `unrealizedPnlPercent = null`（页面显示 `—`）。
- **合计**（Q157 / Q158 / Q160）：只算 `X-Account-Id` 账户的**全部**当前持仓（不受持仓页 FEAT-010 到期过滤影响——过滤本来就只在前端做，合计接口没有过滤参数）；只合计 `OK` 的行；被排除的行按原因计数。

---

#### `GET /api/option-positions`（FEAT-003，新增字段）
`positions[]` 每行新增：

| 字段 | 类型 | 说明 |
|------|------|------|
| `marketValue` | string \| null | 期权市值，scale 2，带方向（多头正、空头负，如 `"370.00"`、`"-125.00"`）。`unrealizedStatus ≠ OK` 时为 `null` |
| `unrealizedPnl` | string \| null | 未实现盈亏 = `openingNetTotal + marketValue`，scale 2，带符号。`≠ OK` 时为 `null` |
| `unrealizedPnlPercent` | string \| null | 百分数的数值部分（已 ×100），scale 2，带符号但**不带 `+` 和 `%`**（如 `"-5.31"`、`"22.80"`、`"0.00"`）。`≠ OK` 时或 `openingNetTotal = 0.00` 时为 `null` |
| `unrealizedStatus` | string | `OK` / `NO_QUOTE` / `EXPIRED_PENDING`（见概念） |
| `unrealizedLabel` | string \| null | `无报价` / `已到期，待处理`；`OK` 时 `null`。前端原样显示，不按状态自己拼 |

前端显示约定（格式化，不是计算）：期权市值带显式符号（`+370.00` / `−125.00`）；盈亏正绿负红、带显式符号；百分比加 `+`/`−` 和 `%`；`unrealizedStatus = OK` 且 `unrealizedPnlPercent = null` 时显示 `—`。

示例（验收 1、3 的账户 A；只列相关字段，`"…"` 表示其它字段不变）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 4,
  "frozenTotal": "9750.00",
  "positions": [
    {
      "symbol": "IXQZ", "expirationDate": "2026-12-18", "strike": "65", "optionType": "CALL",
      "contractLabel": "IXQZ 2026-12-18 65 C", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "84.35", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "0.80", "ask": "0.90", "mark": "0.85", "contractStatus": "QUOTED", "…": "…" },
      "marketValue": "-85.00",
      "unrealizedPnl": "-0.65",
      "unrealizedPnlPercent": "-0.77",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "47.5", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 47.5 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "45.00", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": null, "ask": null, "mark": null, "contractStatus": "NEVER_QUOTED", "…": "…" },
      "marketValue": null,
      "unrealizedPnl": null,
      "unrealizedPnlPercent": null,
      "unrealizedStatus": "NO_QUOTE",
      "unrealizedLabel": "无报价"
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "118.70", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "1.20", "ask": "1.30", "mark": "1.25", "contractStatus": "QUOTED", "…": "…" },
      "marketValue": "-125.00",
      "unrealizedPnl": "-6.30",
      "unrealizedPnlPercent": "-5.31",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "55", "optionType": "CALL",
      "contractLabel": "QRTV 2026-12-18 55 C", "direction": "LONG", "contracts": 2, "multiplier": 100,
      "openingNetTotal": "-301.30", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "1.80", "ask": "1.90", "mark": "1.85", "contractStatus": "QUOTED", "…": "…" },
      "marketValue": "370.00",
      "unrealizedPnl": "68.70",
      "unrealizedPnlPercent": "22.80",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null
    }
  ]
}
```
（`frozenTotal` 只是示意，与本需求无关。）

其它状态的行片段：
```json
{ "contractLabel": "QRTV 2026-11-20 45 P", "direction": "SHORT", "openingNetTotal": "60.00",
  "expirationStatus": "EXPIRED", "daysPastExpiration": 3,
  "optionQuote": { "mark": "0.05", "contractStatus": "MISSING_FROM_SOURCE",
                   "contractStatusLabel": "已到期（最后报价：11-20 16:00）", "…": "…" },
  "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
  "unrealizedStatus": "EXPIRED_PENDING", "unrealizedLabel": "已到期，待处理" }
```
（验收 8：已过期未处理，即使有最后报价 0.05 也不计算）
```json
{ "contractLabel": "QRTV 2026-12-18 60 C", "direction": "LONG", "contracts": 1, "openingNetTotal": "-15.65",
  "optionQuote": { "bid": "0.10", "ask": "0.15", "mark": "0.125", "markBasis": "MID", "…": "…" },
  "marketValue": "12.50", "unrealizedPnl": "-3.15", "unrealizedPnlPercent": "-20.13",
  "unrealizedStatus": "OK", "unrealizedLabel": null }
```
（验收 6 的 P6；10:15 变为 bid 0 / theo 0.03 后：`mark` `"0.03"`、`markBasis` `"THEO"`、`marketValue` `"3.00"`、`unrealizedPnl` `"-12.65"`、`unrealizedPnlPercent` `"-80.83"`）

---

#### `GET /api/stock-positions`（FEAT-011，新增字段）
`positions[]` 每行新增同名 5 个字段，含义：

| 字段 | 类型 | 说明 |
|------|------|------|
| `marketValue` | string \| null | `quote.price × shares`，HALF_UP 到分，非负。`NO_QUOTE` 时 `null` |
| `unrealizedPnl` | string \| null | `marketValue − totalCost`，scale 2 |
| `unrealizedPnlPercent` | string \| null | `unrealizedPnl ÷ totalCost × 100`，HALF_UP 2 位；`totalCost = 0.00`（如 0 成本转入）或 `NO_QUOTE` 时 `null` |
| `unrealizedStatus` | string | `OK` / `NO_QUOTE`（股票没有 `EXPIRED_PENDING`） |
| `unrealizedLabel` | string \| null | `无报价` 或 `null` |

示例（验收 2）：
```json
{
  "positionCount": 1,
  "positions": [
    {
      "symbol": "IXQZ", "shares": 100, "totalCost": "5880.00", "averageCost": "58.8000", "…": "…",
      "quote": { "symbol": "IXQZ", "price": "61.10", "source": "CBOE", "manual": false, "…": "…" },
      "marketValue": "6110.00",
      "unrealizedPnl": "230.00",
      "unrealizedPnlPercent": "3.91",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null
    }
  ]
}
```

---

#### `GET /api/positions/unrealized-summary`（新）
当前账户（`X-Account-Id`）的未实现盈亏合计。无参数、无请求体。不随任何过滤变化（Q158）。

**为什么单独一个接口**：合计横跨期权和股票两个持仓接口，放进任一个都不对称（放进期权接口，股票部分要在那里重复计算；两边都放又会出现两份"总计"）；单独接口语义清楚，也天然不受前端到期过滤影响。代价是页面多一个请求：前端在任何会让持仓重新查询的时机（切换账户、立即刷新、设置 / 清除手动值、录入 / 平仓 / 到期处理等）同时让它重新查询。它与两个持仓接口用同一套计算代码，数据未变时，`option` / `stock` 的合计 = 对应持仓接口各 `OK` 行的 `marketValue` / `unrealizedPnl` 之和。

响应 `200 OK`（验收 3：账户 A）：
```json
{
  "asOfDate": "2026-11-23",
  "option": {
    "marketValue": "160.00",
    "unrealizedPnl": "61.75",
    "includedCount": 3,
    "noQuoteCount": 1,
    "expiredPendingCount": 0
  },
  "stock": {
    "marketValue": "6110.00",
    "unrealizedPnl": "230.00",
    "includedCount": 1,
    "noQuoteCount": 0,
    "expiredPendingCount": 0
  },
  "total": {
    "marketValue": "6270.00",
    "unrealizedPnl": "291.75",
    "includedCount": 4,
    "noQuoteCount": 1,
    "expiredPendingCount": 0
  },
  "excludedLabel": "1 个持仓无报价，未计入"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `asOfDate` | string | 美东今天，判断 `EXPIRED_PENDING` 用的日期（与 `GET /api/option-positions` 的 `asOfDate` 同义） |
| `option` / `stock` / `total` | object | 三组合计，结构相同，永不为 `null` |
| `*.marketValue` | string | `OK` 行市值之和，scale 2；没有可计入的行为`"0.00"`。期权组带符号（空头为负），股票组非负 |
| `*.unrealizedPnl` | string | `OK` 行未实现盈亏之和，scale 2；没有为 `"0.00"` |
| `*.includedCount` | number | 计入合计的行数 |
| `*.noQuoteCount` | number | `NO_QUOTE` 的行数 |
| `*.expiredPendingCount` | number | `EXPIRED_PENDING` 的行数；`stock` 组恒为 `0` |
| `total.*` | — | `option` 与 `stock` 对应字段相加 |
| `excludedLabel` | string \| null | 后端拼好的整句，前端原样显示（灰字）。只有无报价：`N 个持仓无报价，未计入`；只有已到期：`M 个持仓已到期待处理，未计入`；都有：`N 个持仓无报价、M 个持仓已到期待处理，未计入`（N = `total.noQuoteCount`，M = `total.expiredPendingCount`）。都为 0 时 `null`（不显示提示） |

不显示合计百分比（需求未要求）。

其它示例：

验收 4（账户 B，只有 P5）：
```json
{
  "asOfDate": "2026-11-23",
  "option": { "marketValue": "-125.00", "unrealizedPnl": "-5.00", "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0 },
  "stock":  { "marketValue": "0.00", "unrealizedPnl": "0.00", "includedCount": 0, "noQuoteCount": 0, "expiredPendingCount": 0 },
  "total":  { "marketValue": "-125.00", "unrealizedPnl": "-5.00", "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0 },
  "excludedLabel": null
}
```

验收 8（账户 A 另有已过期未处理的 QRTV 2026-11-20 45P）：`option` 与验收 3 相同但 `expiredPendingCount: 1`，`total.expiredPendingCount: 1`，金额不变；`excludedLabel`：`"1 个持仓无报价、1 个持仓已到期待处理，未计入"`。

验收 9（STC P2 后）：`option.marketValue` `"-210.00"`、`option.unrealizedPnl` `"-6.95"`、`total.marketValue` `"5900.00"`、`total.unrealizedPnl` `"223.05"`。

验收 10（没有任何持仓的账户）：三组都是 `"0.00"` / `"0.00"` / `0` / `0` / `0`，`excludedLabel` `null`。

错误：

| 情况 | HTTP | code |
|------|------|------|
| 没带 `X-Account-Id` | 400 | `ACCOUNT_REQUIRED`（FEAT-033） |
| `X-Account-Id` 不是整数 | 400 | `BAD_REQUEST` |
| 账户不存在 | 404 | `ACCOUNT_NOT_FOUND` |
| 非 `GET` 方法 | 405 | `METHOD_NOT_ALLOWED` |

没有本需求专有的错误码。行情缺失不是错误（按 `NO_QUOTE` 处理）。

---

#### 实现说明（backend）
- **分层**：
  - `domain`（纯 Java，不依赖 Spring）：新功能包 `com.optionmgr.valuation.domain`。`UnrealizedValuation.option(direction, contracts, multiplier, openingNetTotal, expirationDate, today, mark)`、`.stock(shares, totalCost, price)` 返回 `RowValuation`（status、marketValue、unrealizedPnl、percent）；`UnrealizedSummary`（累加 `RowValuation`，产出三组合计与 `excludedLabel`）。全部规则（判定顺序、取整、百分比、标签文字）都在这里，单元测试覆盖验收 1–10 的数字。
  - `service`：`valuation.service.UnrealizedValuationService` 读取账户持仓（`OptionPositionService` / `StockPositionService` 现有方法）和一个 `QuoteBook`（FEAT-016 `QuoteQueryService`，与持仓接口相同），逐行调用 domain。`OptionPositionController` / `StockPositionController` 在组装行时用同一个 `RowValuation` 填新字段，保证行与合计同一套代码。
  - `web`：`valuation.web.UnrealizedSummaryController`（`GET /api/positions/unrealized-summary`，取 `AccountRef`）。
- **取整（Q154）**：市值 `setScale(2, HALF_UP)`（mark 可有 3 位以上小数，如 0.125 × 100 = 12.500 → 12.50；0.0125 × 100 这类会真正取整）；未实现盈亏 = 净额 / 成本（已是 scale 2）与取整后市值的加减，精确，`Money` 校验 scale 2 无需取整；合计为各行 scale-2 值之和，不重新取整。
- **百分比**：`pnl.multiply(100).divide(denominator, 2, HALF_UP)`；分母 `compareTo(ZERO) == 0` → `null`。期权分母取 `openingNetTotal.abs()`。HALF_UP 对负数按绝对值进位（−4.1666… → −4.17），与 Q22 一致。
- **判定复用 FEAT-016**：不另查合约状态。`NO_QUOTE` 只看 `optionQuote.mark == null` / `quote.price == null`——FEAT-016 已把 `NEVER_QUOTED`、指数标的（Q149，不拉取）、`automaticMark = null`（bid 0 且无 theo）且无手动覆盖的情况都表示成 `mark = null`；`fetchFailed`、`MISSING_FROM_SOURCE`、`manual` 都不影响计算，只要 mark 非空就用。`EXPIRED_PENDING` 用 FEAT-003 的 `expirationDate < asOfDate`（`asOfDate` 由注入 `Clock` 的美东日期得出，与 FEAT-016 的 Q150 冻结判断同一个"今天"），优先于 `NO_QUOTE`。
- **时钟**：同一请求只取一次 `Clock`（`asOfDate`），E2E 用 FEAT-009 的假时钟。
- **测试支持**：无新增接口；场景用 FEAT-016 的假数据源 + `POST /api/market-data/refresh` / tick + 假时钟搭建。
