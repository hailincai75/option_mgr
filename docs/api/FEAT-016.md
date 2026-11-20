# FEAT-016 自动行情（只读：CBOE 延迟报价 + Finnhub 股价兜底，手动覆盖）

> **FEAT-052 修订（行为变化）**：期权数据源改为配置项 `optionmgr.quotes.option-source`，**默认 `yahoo`**（Yahoo Finance：会话握手 + 按 标的 + 到期日 请求，Greeks 由后端 Black-Scholes 计算）；下文 CBOE 的请求、解析与 Q139 mark 只在 `option-source=cboe` 时生效（`test` / `e2e` profile 默认 `cboe`）。两种数据源共同的变化：定时时刻改为交易日 10:00–16:00 每整点 + 16:20（Q270，下文"每 15 分钟 / 27 次"不再成立，测试支持 `tick` 同步改）；顶部提示阈值 30 → 90 分钟。字段变化：`underlyingQuote.source` / `quote.source` / `results[].underlyingPriceSource` 新值 `YAHOO`；`optionQuote.source` 可为 `YAHOO`、`markBasis` 新值 `LAST`（mark 规则按已存报价的来源）；`status` 新增 `optionSource`、`riskFreeRatePercent`；`results[]` 新增 `optionSource`、`yahooResult`，`cboeResult` 在 Yahoo 下为 `null`。收盘后 0 / 缺失报价不覆盖已有有效报价（Q265），手动 mark 只在 Yahoo 有效报价替换时删除。测试支持新增 `PUT /api/test-support/market-data/settings`、`sources.yahoo`，请求记录新增 `kind` / `expirationDate`，`stored` 新增 `source` / `lastYahooResult`。迁移 V19（`price_source` 允许 `YAHOO`、`option_quote` / `option_quote_close` 新列 `quote_source`；failing-migration fixture 改 V20）。见 [FEAT-052.md](FEAT-052.md)。

Q135–Q144 已定；契约阶段提出的 12 个细节已由用户确认，记入 decisions.md（Q145 起；第 11 条改为"指数标的不拉取"，第 12 条 CBOE `timestamp` 已实测）；交易日 / 时段沿用 FEAT-009（含 FEAT-042 临时休市，它们本来就是 Finnhub 全天休市日）。本节只写**新增或不同**的部分；示例标的均为虚构（QRTV、IXQZ）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/option-positions` | `positions[].underlyingQuote`、`positions[].optionQuote`（纯新增字段） | — |
| `GET /api/stock-positions` | `positions[].quote`（纯新增字段） | — |
| `GET /api/market-data/status` | 新接口（全系统一份） | — |
| `POST /api/market-data/refresh` | 新接口（"立即刷新"） | — |
| `PUT` / `DELETE /api/market-data/overrides/underlyings/{symbol}` | 新接口（手动标的价） | — |
| `PUT` / `DELETE /api/market-data/overrides/options` | 新接口（手动合约 mark） | — |
| 测试支持（`e2e` profile） | `PUT /api/test-support/market-data/sources`、`GET` / `DELETE /api/test-support/market-data/requests`、`POST /api/test-support/market-data/tick`、`GET /api/test-support/market-data/stored` | `POST /api/test-support/reset` 也清空行情数据、假数据源配置、请求记录和刷新冷却 |
| 录入 / 平仓 / 到期 / 指派 / 行权 / 修改 / 删除、现金、交易列表、已平仓、盈亏汇总、首页、交易日历 | — | — （规则 9：行情失败不影响记账） |

持仓接口的已有字段、排序、错误都不变；旧客户端忽略新字段即可。

**账户请求头**：行情全系统一份（需求规则 1：跨账户共用行情和手动覆盖），`/api/market-data/**` **不要求** `X-Account-Id`（与 `/api/trading-calendar/**` 一样列为例外，带了也忽略）。持仓接口仍按账户返回，但同一标的 / 合约在不同账户里看到的行情字段完全相同。

---

#### 概念
- **跟踪标的**（Q150）：所有账户中**有未过期期权持仓**（FEAT-003：合约净张数 ≠ 0，且 `expirationDate` ≥ 美东今天；到期日当天仍跟踪）**或有股票持仓**（FEAT-011：持股 > 0）的 `symbol`，去重。每次拉取开始时按当时的时钟计算。已无持仓、或只剩已过期未处理期权持仓的标的不再拉取（已存数据保留）。**指数标的除外**（见下）。
- **已过期未处理的合约**（Q150：`expirationDate` < 美东今天，仍是持仓）：不再拉取、不更新，照常显示库里最后的报价。曾有报价 → `contractStatus = MISSING_FROM_SOURCE`（**不新增枚举值**），但 `contractStatusLabel` 为 `已到期（最后报价：<quoteTime 美东 MM-DD HH:mm>）`（Q151，不再用"数据源已无该合约"；即使最后一次拉取时链中还有它，或过期前已从链中消失）；从未有报价 → `NEVER_QUOTED`（`无报价`）。`fetchFailed = false`、`staleLabel = null`。手动 mark 仍可设置 / 清除，因为不再拉取，覆盖一直保留到用户清除。该行的 `underlyingQuote`：标的仍被跟踪（还有未过期合约或股票）时照常；不再跟踪时显示库里最后的标的价，`fetchFailed = false`、`staleLabel = null`。
- **指数标的**（`optionmgr.quotes.index-symbols`，默认 `SPX, SPXW, NDX, NDXP, RUT, RUTW, VIX, VIXW, XSP, DJX, OEX, XEO`，大小写不敏感，可配置）：FEAT-016 中**不拉取**——不发 CBOE / Finnhub 请求、不算跟踪标的（不计入 `trackedSymbolCount`、不出现在 `results[]`、不触发顶部提示）。其期权一律 `contractStatus = NEVER_QUOTED`（`mark = null`，显示 `无报价`），标的价也为 `null`（无报价），`fetchFailed = false`、`staleLabel = null`。标的价和合约 mark 都可手动覆盖（覆盖规则相同；因为不拉取，覆盖一直保留到用户清除）。支持指数行情以后另立需求。
- **跟踪合约**：跟踪标的下所有账户**未过期**的当前期权持仓合约（标的 + 到期日 + 行权价 + Call/Put），去重。只存这些合约的报价。
- **一次拉取（run）**：对每个跟踪标的依次（或有限并发）请求 CBOE，必要时 Finnhub；单个标的失败不影响其它标的。三种触发：定时（`SCHEDULED`）、16:20 收盘（`CLOSE`）、立即刷新（`REFRESH`）。同一时间只有一次 run，后到的等前一次结束再执行。
- **标的的结果**（每次 run 每个标的一个）：
  - `cboeResult`：`SUCCESS` / `NETWORK_ERROR`（连接失败、超时）/ `HTTP_ERROR`（非 200）/ `INVALID_RESPONSE`（不是 JSON、没有 `data` 对象、没有 `data.options` 数组、`timestamp` 缺失或无法解析）。
  - 需要 Finnhub 的情况：`cboeResult ≠ SUCCESS`，或 CBOE 成功但 `data.current_price` 缺失 / `null` / ≤ 0。此时请求 Finnhub quote；`finnhubResult`：`SUCCESS` / `NO_KEY`（不发请求）/ `NETWORK_ERROR` / `HTTP_ERROR` / `INVALID_RESPONSE`（含 `c` 缺失或 ≤ 0，Finnhub 对未知代码返回全 0）。不需要时 `finnhubResult = null`。
  - **标的价成功**：CBOE `current_price` > 0，或 Finnhub `SUCCESS`。
  - **期权链成功**：`cboeResult = SUCCESS`。
- **run 成功**（用于顶部提示和 `lastSuccessAt`）：跟踪标的为空，或至少一个标的期权链成功。只有 Finnhub 兜底成功不算（期权报价仍然没更新）。
- **Mark（Q139）**：后端计算。`bid > 0` 且 `ask > 0` → `(bid + ask) / 2`（精确值，不四舍五入；最多比输入多一位小数）；否则 `theo > 0` → `theo`；否则 `null`（页面显示 `无报价`）。
- **合约状态（Q143）** `contractStatus`：
  - `QUOTED`：该合约最近一次期权链成功的响应里有它；
  - `MISSING_FROM_SOURCE`：曾经有过报价，但最近一次期权链成功的响应里没有它 → 保留最后报价，`contractStatusLabel` = `数据源已无该合约（最后报价：<quoteTime 美东 MM-DD HH:mm>）`；
  - `NEVER_QUOTED`：从未有过报价（还没拉过、标的从未成功、或链里从来没有，如调整合约；匹配有歧义的合约；指数标的的全部合约；过期前从未有过报价的已过期合约）。
  - 已过期未处理、曾有报价的合约一律 `MISSING_FROM_SOURCE`（Q150），标签为 `已到期（最后报价：…）`（Q151）。
  - 期权链失败时状态不变（失败不改变任何已存报价）。
- **失败保留（Q141 / Q144）**：任何失败都不清空、不修改已存的标的价和合约报价。
- **手动覆盖（Q140）**：
  - 标的价覆盖：该标的下一次**标的价成功**（CBOE 或 Finnhub）时自动删除，显示自动值。
  - 合约 mark 覆盖：该合约下一次出现在成功的期权链里**且自动 mark 不为 `null`** 时自动删除。期权链失败、链中无该合约、或自动 mark 为 `null`（如 bid 0 且无 theo）时继续保留。
  - 用户可随时清除。覆盖全系统一份，所有账户的同一标的 / 合约都显示同一覆盖值。
- **显示格式**：价格类字段（标的价、bid、ask、last、theo、mark）为字符串，去尾零后**至少 2 位小数**（`1.2` → `"1.20"`，`0.035` → `"0.035"`，`1.255` → `"1.255"`）。前端原样显示，不再四舍五入。时间字段同时给 Instant 和美东本地 `YYYY-MM-DDTHH:mm:ss`；页面显示 `MM-DD HH:mm`。后端拼好的整句文字（`staleLabel`、`contractStatusLabel`、`warning`）前端原样显示。

#### 刷新时刻（Q138，需求规则 5）
- **定时**：在 FEAT-009 交易日（周一至五、非全天休市日）的美东 09:30、09:45、…、15:45、16:00（共 27 次，`SCHEDULED`）以及 16:20（`CLOSE`）。非交易日（周末、感恩节等、临时休市日）一次都不拉。提前收盘日（如 13:00 收盘）仍按上面的时刻拉（已知限制）。
- **启动时**：后端启动后，如果当前是交易日且美东时间在 09:30–16:20 之间，后台立即做一次 `SCHEDULED` run；否则不拉。启动不等待拉取完成，拉取失败不影响启动（规则 9）。
- **16:20 收盘快照（Q142）**：`CLOSE` run 结束后，把**本次成功更新的**标的价（标的价成功的标的）和合约报价（本次链中出现的跟踪合约）另存为当天（美东日期）的收盘快照；同一天再次 `CLOSE` run 覆盖当天快照。`REFRESH` 不产生快照。快照目前没有查询接口（Out of Scope），测试通过测试支持接口查看。
- **立即刷新**：任何时间都可以（含非交易日）。距上一次**被接受的**立即刷新不满 60 秒的请求不拉取（见下）。定时拉取不受冷却影响，也不重置冷却。

#### 顶部提示（Q141）
`stale = true` 的条件（在请求时用注入 `Clock` 实时计算，不存库）：
1. 有跟踪标的，且**从未有过成功 run** → 任何时间都提示 `行情未更新（从未成功获取）`（验收 10）；
2. 否则，当前是交易日且美东时间在 09:30–16:00（含）之间，且 `now − max(lastSuccessAt, 今天 09:30) > 30 分钟` → 提示 `行情未更新（上次：<lastSuccessAt 美东 YYYY-MM-DD HH:mm> 美东）`；
3. 其余情况（非交易日、16:00 以后、09:30 以前、没有跟踪标的）不提示。

例：10:00 成功，10:15、10:30 失败 → 10:30 时 `30 分钟` 不超过 → 不提示；10:31 提示 `行情未更新（上次：2026-11-23 10:00 美东）`；下一次成功 run 后消失。

---

#### `GET /api/option-positions`（FEAT-003，新增字段）
`positions[]` 每行新增两个对象（永不为 `null`；没有数据时内部字段为 `null`）：

- `underlyingQuote`：该持仓标的的标的价，结构见下方「标的行情对象」（与股票持仓的 `quote` 相同）。
- `optionQuote`：该合约的报价。

`optionQuote` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `bid` / `ask` | string \| null | 最后一次自动报价的 bid / ask（CBOE 原值，`0` 原样给 `"0.00"`）；从未有过报价为 `null`。手动覆盖不改它们 |
| `mark` | string \| null | **显示用 mark**：有手动覆盖时为覆盖值，否则为 `automaticMark`。`null` → 页面显示 `无报价` |
| `automaticMark` | string \| null | 按 Q139 由最后一次自动报价算出的 mark；无法计算或从未有过报价为 `null` |
| `markBasis` | string \| null | `automaticMark` 的依据：`MID`（bid/ask 中间价）/ `THEO`；`automaticMark = null` 时为 `null` |
| `source` | string \| null | 自动报价来源，目前只有 `CBOE`；从未有过报价为 `null` |
| `quoteTime` / `quoteTimeLocal` | string \| null | 最后一次自动报价的报价时间（CBOE 响应顶层 `timestamp` = CBOE 快照时间，不是成交时间，也不是拉取时间）。页面"报价时间"列显示它 |
| `contractStatus` | string | `QUOTED` / `MISSING_FROM_SOURCE` / `NEVER_QUOTED`（见概念） |
| `contractStatusLabel` | string \| null | `MISSING_FROM_SOURCE` 时：未过期合约为 `数据源已无该合约（最后报价：11-23 09:45）`，已过期未处理合约（Q150 / Q151）为 `已到期（最后报价：11-23 09:45）`；前端原样显示，不按状态自己拼。其它状态为 `null`（`无报价` 由前端按 `mark = null` 显示） |
| `manual` | boolean | 是否有手动 mark 覆盖 |
| `manualEnteredAt` / `manualEnteredAtLocal` | string \| null | 覆盖录入时间；页面标签 `手动 MM-DD HH:mm` |
| `fetchFailed` | boolean | 该标的**最近一次被拉取**时期权链失败（`cboeResult ≠ SUCCESS`）。还没拉过为 `false`；已过期未处理的合约（Q150）和指数标的合约恒为 `false` |
| `lastSuccessAt` / `lastSuccessAtLocal` | string \| null | 该标的最近一次期权链成功的**拉取时间**（run 时间，不是报价时间）；从未成功为 `null`；指数标的为 `null` |
| `staleLabel` | string \| null | `fetchFailed` 时为该行要显示的文字：`行情未更新（上次：11-23 10:00）`（`lastSuccessAt` 美东 `MM-DD HH:mm`），从未成功过为 `行情未更新（从未成功）`；否则 `null` |

last / theo / volume / open interest / rho 只存库不在此返回；IV / Greeks 由 FEAT-018 在同一行的 `greeks` 对象中返回（见 [FEAT-018.md](FEAT-018.md)）。原值测试通过 `GET /api/test-support/market-data/stored` 查看。

示例（验收 8：10:00 拉取成功，10:05 把 P1 mark 设为 1.40、QRTV 标的价设为 52.00；账户 A 和 B 看到的 QRTV 50P 行情相同）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 1,
  "frozenTotal": "5000.00",
  "positions": [
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 50 P", "direction": "SHORT", "contracts": 1,
      "…": "其它字段不变",
      "underlyingQuote": {
        "symbol": "QRTV",
        "price": "52.00",
        "automaticPrice": "52.40",
        "source": "CBOE",
        "quoteTime": "2026-11-23T14:45:00Z",
        "quoteTimeLocal": "2026-11-23T09:45:00",
        "manual": true,
        "manualEnteredAt": "2026-11-23T15:05:00Z",
        "manualEnteredAtLocal": "2026-11-23T10:05:00",
        "fetchFailed": false,
        "lastSuccessAt": "2026-11-23T15:00:00Z",
        "lastSuccessAtLocal": "2026-11-23T10:00:00",
        "staleLabel": null
      },
      "optionQuote": {
        "bid": "1.20",
        "ask": "1.30",
        "mark": "1.40",
        "automaticMark": "1.25",
        "markBasis": "MID",
        "source": "CBOE",
        "quoteTime": "2026-11-23T14:45:00Z",
        "quoteTimeLocal": "2026-11-23T09:45:00",
        "contractStatus": "QUOTED",
        "contractStatusLabel": null,
        "manual": true,
        "manualEnteredAt": "2026-11-23T15:05:00Z",
        "manualEnteredAtLocal": "2026-11-23T10:05:00",
        "fetchFailed": false,
        "lastSuccessAt": "2026-11-23T15:00:00Z",
        "lastSuccessAtLocal": "2026-11-23T10:00:00",
        "staleLabel": null
      }
    }
  ]
}
```

其它状态的 `optionQuote` 片段：
```json
{ "bid": null, "ask": null, "mark": null, "automaticMark": null, "markBasis": null, "source": null,
  "quoteTime": null, "quoteTimeLocal": null, "contractStatus": "NEVER_QUOTED", "contractStatusLabel": null,
  "manual": false, "manualEnteredAt": null, "manualEnteredAtLocal": null,
  "fetchFailed": false, "lastSuccessAt": "2026-11-23T15:15:00Z", "lastSuccessAtLocal": "2026-11-23T10:15:00", "staleLabel": null }
```
（验收 5 的 P4：链中从无该合约 → `无报价`）
```json
{ "bid": "1.20", "ask": "1.30", "mark": "1.25", "automaticMark": "1.25", "markBasis": "MID", "source": "CBOE",
  "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00",
  "contractStatus": "MISSING_FROM_SOURCE", "contractStatusLabel": "数据源已无该合约（最后报价：11-23 09:45）",
  "manual": false, "manualEnteredAt": null, "manualEnteredAtLocal": null,
  "fetchFailed": false, "lastSuccessAt": "2026-11-23T15:15:00Z", "lastSuccessAtLocal": "2026-11-23T10:15:00", "staleLabel": null }
```
（验收 5 的 P1）
```json
{ "bid": "2.10", "ask": "2.30", "mark": "2.20", "automaticMark": "2.20", "markBasis": "MID", "source": "CBOE",
  "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00",
  "contractStatus": "QUOTED", "contractStatusLabel": null,
  "manual": false, "manualEnteredAt": null, "manualEnteredAtLocal": null,
  "fetchFailed": true, "lastSuccessAt": "2026-11-23T15:00:00Z", "lastSuccessAtLocal": "2026-11-23T10:00:00",
  "staleLabel": "行情未更新（上次：11-23 10:00）" }
```
（验收 6 的 P2：10:15 起 IXQZ 失败，保留 10:00 的值）

---

#### `GET /api/stock-positions`（FEAT-011，新增字段）
`positions[]` 每行新增 `quote`（「标的行情对象」，永不为 `null`）。页面"现价"列显示 `quote.price`（`null` 显示 `无报价`），"报价时间"列显示 `quote.quoteTime`。

##### 标的行情对象（`underlyingQuote` / `quote`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `symbol` | string | 标的 |
| `price` | string \| null | **显示用价格**：有手动覆盖时为覆盖值，否则为 `automaticPrice`；都没有为 `null` |
| `automaticPrice` | string \| null | 最后一次成功取得的自动标的价（CBOE `current_price` 或 Finnhub `c`）；从未成功为 `null` |
| `source` | string \| null | `automaticPrice` 的来源：`CBOE` / `FINNHUB`；`null` 同上。页面在 `FINNHUB` 时悬停显示 `来源 Finnhub`；`manual = true` 时显示 `手动` 标签而不是来源 |
| `quoteTime` / `quoteTimeLocal` | string \| null | `automaticPrice` 的报价时间：CBOE 为响应顶层 `timestamp`（快照时间）；Finnhub 为 `t`（Unix 秒；缺失或 0 时用拉取时间） |
| `manual` | boolean | 是否有手动标的价覆盖 |
| `manualEnteredAt` / `manualEnteredAtLocal` | string \| null | 覆盖录入时间 |
| `fetchFailed` | boolean | 该标的最近一次被拉取时**标的价失败**（CBOE 与 Finnhub 都没拿到价格）。还没拉过为 `false`；标的已不再跟踪（只剩已过期合约，Q150）或是指数标的时恒为 `false` |
| `lastSuccessAt` / `lastSuccessAtLocal` | string \| null | 最近一次标的价成功的**拉取时间** |
| `staleLabel` | string \| null | `fetchFailed` 时：`行情未更新（上次：11-23 10:00）` 或 `行情未更新（从未成功）`；否则 `null` |

注意期权行的 `optionQuote.fetchFailed` 看**期权链**，`underlyingQuote.fetchFailed` / 股票行看**标的价**：CBOE 失败而 Finnhub 成功时（验收 4），IXQZ 股票行正常（`source = FINNHUB`），P2 的 `optionQuote.fetchFailed = true`。

示例（验收 4：首次拉取，IXQZ CBOE 500，Finnhub 61.05）：
```json
{
  "positionCount": 1,
  "positions": [
    {
      "symbol": "IXQZ", "shares": 100, "totalCost": "6000.00", "averageCost": "60.0000", "lotCount": 1,
      "lots": [ { "…": "不变" } ],
      "quote": {
        "symbol": "IXQZ",
        "price": "61.05",
        "automaticPrice": "61.05",
        "source": "FINNHUB",
        "quoteTime": "2026-11-23T14:59:58Z",
        "quoteTimeLocal": "2026-11-23T09:59:58",
        "manual": false,
        "manualEnteredAt": null,
        "manualEnteredAtLocal": null,
        "fetchFailed": false,
        "lastSuccessAt": "2026-11-23T15:00:00Z",
        "lastSuccessAtLocal": "2026-11-23T10:00:00",
        "staleLabel": null
      }
    }
  ]
}
```

---

#### `GET /api/market-data/status`
行情状态，供持仓页顶部提示与"立即刷新"按钮。无参数，不要求 `X-Account-Id`。

响应 `200 OK`（验收 6，10:31）：
```json
{
  "stale": true,
  "warning": "行情未更新（上次：2026-11-23 10:00 美东）",
  "lastSuccessAt": "2026-11-23T15:00:00Z",
  "lastSuccessAtLocal": "2026-11-23T10:00:00",
  "lastAttemptAt": "2026-11-23T15:30:00Z",
  "lastAttemptAtLocal": "2026-11-23T10:30:00",
  "lastRefreshAt": "2026-11-23T15:02:00Z",
  "lastRefreshAtLocal": "2026-11-23T10:02:00",
  "refreshAvailableAt": "2026-11-23T15:03:00Z",
  "refreshAllowed": true,
  "trackedSymbolCount": 2
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `stale` | boolean | 是否显示顶部提示（规则见「顶部提示」） |
| `warning` | string \| null | `stale` 时的完整提示文字，否则 `null`。前端原样显示 |
| `lastSuccessAt` / `lastSuccessAtLocal` | string \| null | 最近一次成功 run 的时间（run 开始时的时钟时间）；从未成功为 `null` |
| `lastAttemptAt` / `lastAttemptAtLocal` | string \| null | 最近一次 run（无论成败）的时间；从未拉过为 `null` |
| `lastRefreshAt` / `lastRefreshAtLocal` | string \| null | 最近一次**被接受的**立即刷新的时间；从未为 `null` |
| `refreshAvailableAt` | string \| null | `lastRefreshAt + 60 秒`；`lastRefreshAt = null` 时为 `null` |
| `refreshAllowed` | boolean | 当前时钟 ≥ `refreshAvailableAt`（或从未刷新）。前端据此禁用按钮，并可用 `refreshAvailableAt` 计时解禁；刷新进行中也禁用（前端自己管） |
| `trackedSymbolCount` | number | 当前跟踪标的数 |

状态（`lastSuccessAt`、`lastAttemptAt`、`lastRefreshAt`）存数据库，重启后保留；`stale` / `warning` 每次请求实时计算，所以前端应定时重新查询（建议 60 秒）。

错误：只有通用错误。

---

#### `POST /api/market-data/refresh`
立即刷新（需求规则 5）。无请求体，不要求 `X-Account-Id`。**同步**：拉取完成后才返回（每个请求连接超时 5 秒、读取超时 10 秒；有其它 run 正在进行时先等它结束）。完成后前端重新查询持仓和状态。

- 冷却：如果上一次被接受的刷新距现在（注入时钟）不满 60 秒 → **不拉取**、不发任何 CBOE / Finnhub 请求，返回 `200`，`performed = false`，`reason = "COOLDOWN"`；`lastRefreshAt` 不变。
- 否则：`lastRefreshAt` = 现在，执行一次 `REFRESH` run，`performed = true`。

响应 `200 OK`（验收 9，10:02）：
```json
{
  "performed": true,
  "reason": null,
  "results": [
    { "symbol": "IXQZ", "result": "SUCCESS", "cboeResult": "SUCCESS", "finnhubResult": null, "underlyingPriceSource": "CBOE", "failureDetail": null },
    { "symbol": "QRTV", "result": "SUCCESS", "cboeResult": "SUCCESS", "finnhubResult": null, "underlyingPriceSource": "CBOE", "failureDetail": null }
  ],
  "status": {
    "stale": false, "warning": null,
    "lastSuccessAt": "2026-11-23T15:02:00Z", "lastSuccessAtLocal": "2026-11-23T10:02:00",
    "lastAttemptAt": "2026-11-23T15:02:00Z", "lastAttemptAtLocal": "2026-11-23T10:02:00",
    "lastRefreshAt": "2026-11-23T15:02:00Z", "lastRefreshAtLocal": "2026-11-23T10:02:00",
    "refreshAvailableAt": "2026-11-23T15:03:00Z", "refreshAllowed": false,
    "trackedSymbolCount": 2
  }
}
```

冷却中（10:02:30）：
```json
{ "performed": false, "reason": "COOLDOWN", "results": [], "status": { "…": "同上，refreshAllowed false" } }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `performed` | boolean | 是否真的拉取了 |
| `reason` | string \| null | `performed = false` 时为 `COOLDOWN`；否则 `null` |
| `results` | array | 每个跟踪标的一项，按 `symbol` 升序；没有跟踪标的或未拉取时 `[]` |
| `results[].result` | string | `SUCCESS`（期权链成功）/ `FALLBACK`（期权链失败，Finnhub 拿到了标的价）/ `FAILED`（期权链和标的价都失败） |
| `results[].cboeResult` | string | 见概念 |
| `results[].finnhubResult` | string \| null | 见概念；没调用 Finnhub 为 `null` |
| `results[].underlyingPriceSource` | string \| null | 本次标的价来源 `CBOE` / `FINNHUB`；没拿到为 `null` |
| `results[].failureDetail` | string \| null | 简短排查信息（如 `CBOE HTTP 500; Finnhub NO_KEY`）；不含 key、URL 查询串、响应全文 |
| `status` | object | 本次处理后的 `GET /api/market-data/status` |

页面上失败只看持仓行的 `staleLabel` 和顶部 `warning`；`results` 仅供提示 / 排查。

错误：只有通用错误（拉取失败**不是**错误，返回 200 + `FAILED`）。

---

#### `PUT /api/market-data/overrides/underlyings/{symbol}`
设置（或替换）手动标的价（规则 8）。不要求 `X-Account-Id`。`symbol` 按持仓中的写法（大写）。

请求：
```json
{ "price": "52.00" }
```
响应 `200 OK`：该标的的「标的行情对象」（与持仓中的相同，`manual = true`，`manualEnteredAt` = 当前时钟）。

#### `DELETE /api/market-data/overrides/underlyings/{symbol}`
清除手动标的价。没有覆盖也返回 `204`（幂等）。不检查是否持有。

#### `PUT /api/market-data/overrides/options`
设置（或替换）手动合约 mark。

请求：
```json
{ "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT", "mark": "1.40" }
```
响应 `200 OK`：该合约的 `optionQuote` 对象（`manual = true`，`mark = "1.40"`）。

#### `DELETE /api/market-data/overrides/options?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT`
清除手动合约 mark。四个 query 参数都必填（缺少 → 400 `BAD_REQUEST`）。没有覆盖也返回 `204`。`strike` 按数值比较（`50` 与 `50.0` 相同）。

覆盖接口的错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | `price` / `mark`：缺少 → `请输入价格`；≤ 0 → `价格必须大于 0`；超过 4 位小数 → `价格最多 4 位小数`；≥ 1000000 → `价格过大`。合约覆盖另有：`symbol` 缺少或空白 → `请填写标的`；`expirationDate` 缺少 → `请填写到期日`；`strike` 缺少 → `请填写行权价`、≤ 0 → `行权价必须大于 0`；`optionType` 缺少 → `请选择 Call / Put`。所有问题一起列出，按 `field` 排序 |
| `BAD_REQUEST` | 400 | 无法解析（价格不是数字、日期格式不对、`optionType` 不是 `CALL` / `PUT`、缺 query 参数） |
| `QUOTE_TARGET_NOT_HELD` | 422 | `PUT` 时任何账户都没有该标的（期权或股票）/ 该合约的持仓。`message`：`当前没有 QRTV 的持仓，不能设置手动价格` / `当前没有 QRTV 2026-12-18 50 P 的持仓，不能设置手动价格`；`details` `[]` |

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/market-data/overrides/options",
  "timestamp": "2026-11-23T15:05:00.123Z",
  "details": [ { "field": "mark", "message": "价格必须大于 0" } ]
}
```

---

#### 测试支持（仅 `e2e` profile；其它 profile 404）
`e2e` profile 下**不访问真实 CBOE / Finnhub**：没有定时拉取、没有启动拉取（`optionmgr.quotes.fetch-enabled=false`），数据源换成内存假数据源（`optionmgr.quotes.transport=in-memory`）。假数据源返回的是"HTTP 状态 + 响应体文本"，之后的解析、匹配、存储、覆盖替换、快照与真实拉取**完全相同**。`POST /api/market-data/refresh` 在 e2e 中照常可用（走假数据源）。时钟用 FEAT-009 的 `PUT /api/test-support/clock`。以下接口不要求 `X-Account-Id`。

##### `PUT /api/test-support/market-data/sources`
整体替换假数据源配置（之前的配置全部丢弃）。之后的每次 run 按此返回。

```json
{
  "finnhubKeyPresent": true,
  "cboe": {
    "QRTV": { "httpStatus": 200, "body": {
      "timestamp": "2026-11-23 09:45:00",
      "data": { "symbol": "QRTV", "current_price": 52.40, "bid": 52.38, "ask": 52.42, "prev_day_close": 51.90, "iv30": 31.2,
        "options": [
          { "option": "QRTV261218P00050000", "bid": 1.20, "ask": 1.30, "theo": 1.24, "iv": 0.3512, "delta": -0.3021,
            "gamma": 0.0512, "theta": -0.0456, "vega": 0.0711, "rho": -0.0123, "last_trade_price": 1.22,
            "last_trade_time": "2026-11-23T09:44:10", "volume": 120, "open_interest": 3400 }
        ] } } },
    "IXQZ": { "httpStatus": 500, "body": null }
  },
  "finnhub": {
    "IXQZ": { "httpStatus": 200, "body": { "c": 61.05, "pc": 60.80, "t": 1795445998 } }
  }
}
```
- 指数标的（`index-symbols`）即使配置了也不会被请求。
- `cboe` / `finnhub`：按标的配置一个响应：`{ "httpStatus": <int>, "body": <任意 JSON 或 null> }`、`{ "httpStatus": 200, "rawBody": "<非 JSON 文本>" }`（测 `INVALID_RESPONSE`），或 `{ "unreachable": true }`（`NETWORK_ERROR`）。没配置的标的 = `unreachable`。
- `finnhubKeyPresent: false` → Finnhub 一律 `NO_KEY`（不记录请求）。缺省 `true`。
- 验收 2 的 3,400 个合约由测试在 JS 中生成后放进 `options`（请求体大小不受限制）。
- 响应 `204`。错误：请求体格式不对 → 400 `BAD_REQUEST`。

##### `GET /api/test-support/market-data/requests`、`DELETE /api/test-support/market-data/requests`
假数据源收到的请求记录（验收 2、9 "只请求 / 不请求"），按时间顺序：
```json
{ "requests": [
  { "source": "CBOE", "symbol": "IXQZ", "at": "2026-11-23T15:00:00Z" },
  { "source": "CBOE", "symbol": "QRTV", "at": "2026-11-23T15:00:00Z" },
  { "source": "FINNHUB", "symbol": "IXQZ", "at": "2026-11-23T15:00:00Z" }
] }
```
`DELETE` 清空记录 → `204`。

##### `POST /api/test-support/market-data/tick`
模拟定时器在**当前时钟**触发：判断此刻是否是定时时刻（美东时间截到分钟后为 09:30–16:00 中的 :00/:15/:30/:45，或 16:20）且是交易日，是则执行与定时拉取完全相同的 run（16:20 为 `CLOSE`，含快照），否则什么都不做。无请求体。

```json
{ "ran": true, "trigger": "CLOSE", "skipReason": null, "results": [ { "…": "同 refresh 的 results[]" } ], "status": { "…": "同 /status" } }
```
```json
{ "ran": false, "trigger": null, "skipReason": "NON_TRADING_DAY", "results": [], "status": { "…": "…" } }
```
`skipReason`：`NON_TRADING_DAY`（周末 / 全天休市日）/ `NOT_A_SLOT`（不是定时时刻）。

##### `GET /api/test-support/market-data/stored`
数据库里的原始行情（验收 2 的 IV / Greeks、验收 7 的收盘快照）。所有数值为字符串（未补零的原值），时间为 Instant。
```json
{
  "underlyings": [
    { "symbol": "QRTV", "price": "52.4", "bid": "52.38", "ask": "52.42", "prevClose": "51.9", "source": "CBOE",
      "quoteTime": "2026-11-23T14:45:00Z", "lastPriceSuccessAt": "2026-11-23T15:00:00Z", "lastChainSuccessAt": "2026-11-23T15:00:00Z",
      "lastAttemptAt": "2026-11-23T15:00:00Z", "lastCboeResult": "SUCCESS", "lastFinnhubResult": null,
      "manualPrice": null, "manualEnteredAt": null }
  ],
  "contracts": [
    { "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT", "occCode": "QRTV261218P00050000",
      "bid": "1.2", "ask": "1.3", "last": "1.22", "theo": "1.24", "iv": "0.3512", "delta": "-0.3021", "gamma": "0.0512",
      "theta": "-0.0456", "vega": "0.0711", "rho": "-0.0123", "volume": 120, "openInterest": 3400,
      "quoteTime": "2026-11-23T14:45:00Z", "fetchedAt": "2026-11-23T15:00:00Z", "missingSince": null,
      "manualMark": null, "manualEnteredAt": null }
  ],
  "closeSnapshots": [
    { "date": "2026-11-23", "underlyings": [ { "symbol": "QRTV", "price": "52.4", "…": "同上报价列" } ],
      "contracts": [ { "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT", "…": "同上报价列" } ] }
  ]
}
```
`underlyings` / `contracts` 含已不再持有的历史行（不删除）；`contracts` 只会有曾经是跟踪合约的行。

##### `POST /api/test-support/reset`
另外：清空全部行情表（最新、快照、状态单行恢复为新库值）、恢复假数据源为空配置（`finnhubKeyPresent: true`，全部 `unreachable`）、清空请求记录；冷却随 `lastRefreshAt` 一起清空。

---

#### 实现说明（backend 自用，qa 参考）

##### 数据源
- **CBOE**：`GET <optionmgr.quotes.cboe-base-url>/api/global/delayed_quotes/options/{SYMBOL}.json`（默认 base `https://cdn-api.cboe.com`；`cdn.cboe.com` 会 307 到这里，直接用 `cdn-api`；客户端跟随重定向）。无 key。`SYMBOL` 为持仓标的原样（大写，URL 编码）。非官方接口、约 15 分钟延迟（Q144 已接受）。
  - 解析用 `BigDecimal`（Jackson `USE_BIG_DECIMAL_FOR_FLOATS`，绝不经过 `double`）。
  - 顶层 `timestamp`：格式 `yyyy-MM-dd HH:mm:ss`，**美东本地时间**（用户 2026-11-14 实测确认）。含义是 **CBOE 生成这份快照的时间**，不是成交时间（周六请求也会是周六的时间）。本需求的"报价时间"（标的与合约的 `quoteTime`）一律取它。缺失或解析失败 → `INVALID_RESPONSE`。`data.last_trade_time` 和合约的 `last_trade_time` 是不带时区的 ISO 本地时间（美东），本需求不使用。
  - 标的：`data.current_price`（> 0 才算有价）、`data.bid`、`data.ask`、`data.prev_day_close`；`iv30` 等其它字段忽略。
  - 期权：`data.options[]`，按 `option` 代码匹配。**root 不一定等于标的**（实测：SPY 链里同时有 `SPY…` 和 `B1SPY…`，如 `B1SPY261113C00710000`；SPX 有 SPX / SPXW / SPXO）。解析：代码**末尾 15 位**为 `yymmdd`（6）+ `C`/`P`（1）+ 行权价 × 1000（8 位），其余前缀为 root；末尾 15 位不符合该格式的代码忽略。匹配条件：root **等于**持仓 `symbol` 或**以它结尾**（如 `B1SPY` 对 `SPY`），且到期日 = 20YYMMDD、C/P 相同、行权价 `compareTo` 相等。
  - **歧义**：同一合约四要素匹配到多个**不同代码**（如 `SPY261113C00710000` 和 `B1SPY261113C00710000`）→ 不猜，本次视为该合约**不在链中**（不更新报价、不替换手动覆盖）；从未有过报价的保持 `NEVER_QUOTED`（`无报价`），曾有报价的按 `MISSING_FROM_SOURCE` 处理。歧义写入日志（列出代码），并在 `failureDetail` 中注明（如 `ambiguous: SPY261113C00710000,B1SPY261113C00710000`），但不算该标的失败。
  - 其它前缀的代码（调整合约等）只要不和持仓四要素同时匹配就无影响。字段 `bid`、`ask`、`theo`、`iv`、`delta`、`gamma`、`theta`、`vega`、`rho`、`last_trade_price`、`volume`、`open_interest`；缺失为 `null`；单个合约字段格式错误 → 该合约按缺失字段处理，不影响整链。只保存跟踪合约（验收 2：3,400 个合约只存 2 个）。
  - 指数标的：在 `optionmgr.quotes.index-symbols` 中的标的**不请求**（不会去试 `SPX.json` 或 `_SPX.json`），见概念「指数标的」。
  - 顶层 `data` 的 `bid`、`ask`、`prev_day_close` 存入 `underlying_quote`；`close`、`last_trade_time`、`iv30` 等其它字段本需求不用。
- **Finnhub 股价兜底**：`GET <optionmgr.finnhub.base-url>/api/v1/quote?symbol=XXX`，key 只放请求头 `X-Finnhub-Token`。复用 FEAT-009 的 `FinnhubKeyResolver`（`optionmgr.finnhub.api-key` > 环境变量 `FINNHUB_API_KEY`（受 `read-system-env` 控制）> `.env` 文件 `optionmgr.finnhub.env-files`）。无 key → `NO_KEY`，不发请求。响应 `{c, pc, t, …}`：`c` > 0 为价格，`pc` 存为前收，`t` 为报价时间。免费档 option-chain 是 403，**不使用**。key 不出现在 URL、日志、异常、库、响应中。
- 超时：连接 5 秒、读取 10 秒（与 FEAT-009 相同），可配。任何异常都收敛为该标的的失败结果，run 不中断（规则 6、边界"响应格式变化"）。

##### 配置（`optionmgr.quotes.*`）
| 属性 | 默认 | `test` / `e2e` | 说明 |
|------|------|---------------|------|
| `optionmgr.quotes.fetch-enabled` | `true` | `false` | 定时 + 启动拉取。`false` 时没有任何自动拉取；`POST /api/market-data/refresh` 仍可用 |
| `optionmgr.quotes.transport` | `http` | `test`: `http`；`e2e`: `in-memory` | `in-memory` = 测试支持配置的假数据源（`InMemoryQuoteTransport`）；只在 `e2e` / `test` profile 允许，其它 profile 设了也拒绝启动 |
| `optionmgr.quotes.cboe-base-url` | `https://cdn-api.cboe.com` | `http://127.0.0.1:9`（不可达） | 后端测试指向本地假 HTTP 服务（如 `HttpQuoteTransportTest`） |
| `optionmgr.quotes.connect-timeout-seconds` / `read-timeout-seconds` | `5` / `10` | 同 | 整数秒（与 `optionmgr.finnhub.*` 写法一致） |
| `optionmgr.quotes.index-symbols` | `SPX, SPXW, NDX, NDXP, RUT, RUTW, VIX, VIXW, XSP, DJX, OEX, XEO` | 同 | 不拉取的指数标的（大小写不敏感） |
| Finnhub 相关 | 沿用 `optionmgr.finnhub.*` | 沿用（`test`/`e2e` 已不读真实 key、base-url 不可达） | |

验收 10（`bootRun`、无 key、CBOE 不可达）的手工 / qa 做法：`gradlew.bat bootRun --args="--optionmgr.quotes.cboe-base-url=http://127.0.0.1:9"`，并确保环境变量和 `.env` 都没有 `FINNHUB_API_KEY`（或另加 `--optionmgr.finnhub.env-files=build/none/.env --optionmgr.finnhub.read-system-env=false`）。

##### 分层（已实现）
- `com.optionmgr.quote.domain`（不依赖 Spring）：`QuotePrices`（Q139 mark、显示格式）、`OccCode` + `ChainMatcher`（末尾 15 位解析、root 后缀匹配、歧义）、`QuoteMerge`（成功 / 失败 / 链中缺失 / 覆盖替换）、`FetchSchedule`（定时时刻、启动窗口）、`QuoteLabels`（顶部提示与行标签文字）、`UnderlyingState` / `OptionState` / `SymbolFetch`。
- `quote.service`：`QuoteParsers`（CBOE / Finnhub 文本 → 领域对象，精确 `BigDecimal`）、`QuoteTransport`（`HttpQuoteTransport` / `InMemoryQuoteTransport`）、`HeldPositionsService`（所有账户的持仓与 Q150 跟踪集合）、`QuoteFetchService`（run、锁、冷却、`tick`、启动窗口、收盘快照）、`QuoteScheduler`（cron `0 */5 9-16 * * MON-FRI` 美东每 5 分钟唤醒，由 `tick` 判断时刻与交易日；`fetch-enabled=false` 时不注册）、`QuoteQueryService`（`QuoteBook` 一次读出全部行情给持仓组装，状态实时计算）、`QuoteOverrideService`。
- `quote.web.MarketDataController`（不取 `AccountRef`，所以不要求账户头）；测试支持 `testsupport.web.MarketDataTestSupportController`（仅 e2e）。
- 后端测试：`quote/domain/QuoteDomainTest`、`quote/service/QuoteParsersTest`、`quote/service/HttpQuoteTransportTest`（本地假 HTTP 服务）、`quote/web/MarketDataControllerTest`（in-memory 假数据源 + 可设时钟，覆盖验收 1–10、Q149 指数、Q150 / Q151 已过期合约、歧义）。

##### 数据库迁移 `V16__market_data.sql`（已实现）
- `underlying_quote`（每个标的最新一份）：`symbol VARCHAR(20)` 主键；`price DECIMAL(19,6) NULL`、`bid`、`ask`、`prev_close`（同类型，NULL）、`price_source VARCHAR(10) NULL`（`CBOE`/`FINNHUB`）、`quote_time TIMESTAMP NULL`；`last_price_success_at TIMESTAMP NULL`、`last_chain_success_at TIMESTAMP NULL`、`last_attempt_at TIMESTAMP NULL`、`last_price_failed BOOLEAN NOT NULL`、`last_chain_failed BOOLEAN NOT NULL`、`last_cboe_result VARCHAR(20) NULL`、`last_finnhub_result VARCHAR(20) NULL`、`last_failure_detail VARCHAR(500) NULL`；`manual_price DECIMAL(19,6) NULL`、`manual_entered_at TIMESTAMP NULL`。
- `option_quote`（每个合约最新一份）：`id BIGINT` 自增主键；`symbol VARCHAR(20)`、`expiration_date DATE`、`strike DECIMAL(19,6)`、`option_type VARCHAR(4)`，唯一约束 `(symbol, expiration_date, strike, option_type)`（strike 统一 `stripTrailingZeros` 后存，避免 `50` / `50.0` 重复）；`occ_code VARCHAR(40) NULL`；`bid`、`ask`、`last_price`、`theo` `DECIMAL(19,6) NULL`；`iv`、`delta`、`gamma`、`theta`、`vega`、`rho` `DECIMAL(19,8) NULL`（超过 8 位小数 HALF_UP）；`volume BIGINT NULL`、`open_interest BIGINT NULL`；`quote_time TIMESTAMP NULL`（`NULL` = 从未有过报价）、`fetched_at TIMESTAMP NULL`、`missing_since TIMESTAMP NULL`（非空 = `MISSING_FROM_SOURCE`）；`manual_mark DECIMAL(19,6) NULL`、`manual_entered_at TIMESTAMP NULL`。从未报价但有手动覆盖的合约也是一行（报价列为 `NULL`）。
- `underlying_quote_close`：自增 `id` 主键 + 唯一约束 `(snapshot_date, symbol)`；报价列同 `underlying_quote`（不含状态 / 覆盖列）+ `captured_at TIMESTAMP NOT NULL`。
- `option_quote_close`：自增 `id` 主键 + 唯一约束 `(snapshot_date, symbol, expiration_date, strike, option_type)`；报价列同 `option_quote`（含 IV / Greeks，不含覆盖）+ `captured_at`。
- `market_data_status`：单行（`id = 1`，迁移插入）；`last_attempt_at`、`last_success_at`、`last_refresh_at` `TIMESTAMP NULL`。
- 不改任何已有表和数据。`backend/src/test/resources/db/failing-migration/V16__simulated_failure.sql` 改名为 `V17__simulated_failure.sql`，并更新 `MigrationFromM1Test`（升级备份名 `-backup-before-V16-`，失败用例 `faildb-backup-before-V17-`）。
- 新表由测试支持重置快照机制自动纳入（`market_data_status` 的初始行随快照恢复）。

#### 已知限制
- CBOE 为非官方接口（Q144）；格式变化时该标的按失败处理，另立需求修复。
- 指数标的（配置列表内）不拉取，期权与标的价都 `无报价`，只能手动覆盖；不在列表里的指数（配置漏了）会按普通标的请求，通常失败并显示 `行情未更新`，把它加进配置即可。
- 调整合约（非 100 股）通常匹配不到 → `NEVER_QUOTED`；四要素匹配到多个代码时按歧义处理为不在链中。
- 提前收盘日仍拉到 16:00 / 16:20；顶部提示窗口也按 09:30–16:00。
- 立即刷新是同步请求，标的多且网络慢时可能需要数十秒。
- 收盘快照只有存储，没有查询页面（以后另立）。
- 未实现盈亏 / 市值（FEAT-017）、IV / Greeks 显示（FEAT-018/019）不在本需求。
