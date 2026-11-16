# FEAT-048 持仓批次的未实现盈亏（期权 / 股票批次新增市值、未实现盈亏、百分比）

口径：FEAT-017 的全部（Q152–Q156、Q161、Q162），Q21（期权批次无部分平仓，剩余净额 = 原净额）、Q69 / Q87（股票批次部分卖出、剩余成本 HALF_UP 到分）、Q214 / Q215（全部账户按账户分行），以及本需求的 Q237–Q241（均按 PM 建议：**Q237 股票批次也做**；**Q238 A**：除最后一个批次外各自四舍五入，最后一个取余数；**Q239** 完全跟随持仓行；**Q240** 多头空头同一分母；**Q241** 持仓行不加提示）。本节只写**新增或不同**的部分。示例标的 ZZQ、账户（甲账户 id 1、乙账户 id 2）均为虚构，数字与需求场景一致（今天 2026-11-16 星期一 10:00 美东拉取成功，multiplier 100）。

**兼容性一览**（纯新增：批次对象多 5 个字段；没有新端点、没有新错误码、没有数据库迁移；已有字段、数字、排序、错误都**不变**——持仓行的 FEAT-017 字段、`unrealized-summary`、`underlying-summary`、`greeks-summary`、首页都不变）

| 接口 | 新增（兼容） | 行为变化 |
|------|-------------|---------|
| `GET /api/option-positions`（FEAT-003 / FEAT-017） | `positions[].lots[]` 新字段 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel` | — |
| `GET /api/stock-positions`（FEAT-011 / FEAT-017） | `positions[].lots[]` 同上 5 个字段 | — |
| `POST /api/option-trades/expiration-adjustments`（FEAT-043） | 响应 `position.lots[]`（与持仓行同一结构）同上 5 个字段 | — |
| 两个持仓接口带 `X-Account-Id: ALL`（FEAT-035） | 同上；每行的批次值与单账户请求逐字相同 | — |

字段名、类型、格式与 FEAT-017 持仓行上的同名字段**完全相同**，前端可以复用同一套格式化与渲染。

---

#### 概念

- **价格**：批次用所属持仓行的同一个 mark（期权 `optionQuote.mark`）/ 现价（股票 `quote.price`），同一次读取。含手动覆盖、`markBasis = THEO`、拉取失败保留的上次报价、`MISSING_FROM_SOURCE` 的最后报价——持仓行能算，批次就用同一个值算（Q239，规则 6）。
- **状态与标签（Q239，规则 6）**：每个批次的 `unrealizedStatus` / `unrealizedLabel` **等于所属持仓行的值**（不单独判定）。`EXPIRED_PENDING` / `NO_QUOTE` 时批次三个数值都是 `null`，标签 `已到期，待处理` / `无报价`。
- **批次的"大小"**：
  - 期权：`remainingContracts × multiplier`，方向 = 持仓 `direction`（多头正、空头负，Q152）。分子用的开仓净额 = `remainingNetAmount`（空头 +、多头 −；Q21 下 = `originalNetAmount`）。
  - 股票：`remainingShares`；成本 = `remainingCost`（部分卖出后已按 Q69 / Q87 扣减）。
- **市值取整与对齐（Q238 A，规则 3）**：设持仓行市值为 `V`（FEAT-017，已 HALF_UP 到分），批次按 FIFO 顺序为 `lots[0..n-1]`：
  - `i < n-1`：`marketValue_i = price × 批次大小`，`setScale(2, HALF_UP)`，期权再带方向符号（先对正数取整再加符号，与 FEAT-017 相同）；
  - 最后一个（`lots[n-1]`，FIFO 最晚的那个）：`marketValue_{n-1} = V − Σ_{i<n-1} marketValue_i`（精确减法，scale 2，不再取整）。
  - 因此 **Σ 批次 `marketValue` = 行 `marketValue`、Σ 批次 `unrealizedPnl` = 行 `unrealizedPnl`，始终逐分相等**。只有 1 个批次时批次值 = 行值。`price × 大小` 本来就是整分时（如 multiplier 100 且 mark 不超过 4 位小数，或 2 位小数的现价），余数结果与各自取整相同。
- **未实现盈亏（规则 4）**：期权 `remainingNetAmount + marketValue`；股票 `marketValue − remainingCost`。都是 scale 2 精确加减。
- **百分比（Q240，规则 5）**：`unrealizedPnl × 100 ÷ 分母`，HALF_UP 2 位、带符号（与 FEAT-017 一样不带 `+` / `%`）；期权分母 `|remainingNetAmount|`，股票分母 `remainingCost`；分母 `0.00`（如 0 成本转入批次）→ `null`（页面 `—`）。多头、空头同一公式。
- **全部账户（FEAT-035）**：每行仍只属于一个账户，批次计算只用该行自己的批次和该行的 `V`，与切到该账户时逐字相同。
- 不存库；任何写操作、重放、行情刷新、手动覆盖之后重新查询即得到新值。

---

#### `GET /api/option-positions`（`lots[]` 新增字段）

请求不变。`positions[].lots[]` 每个批次新增：

| 字段 | 类型 | 说明 |
|------|------|------|
| `marketValue` | string \| null | 批次市值，scale 2，带方向（多头正、空头负）；按上面的取整与对齐规则。`unrealizedStatus ≠ OK` 时 `null` |
| `unrealizedPnl` | string \| null | `remainingNetAmount + marketValue`，scale 2，带符号。`≠ OK` 时 `null` |
| `unrealizedPnlPercent` | string \| null | 百分数数值部分（已 ×100），scale 2，带负号但不带 `+` / `%`。`≠ OK` 或 `remainingNetAmount = 0.00` 时 `null` |
| `unrealizedStatus` | string | = 所属持仓行的 `unrealizedStatus`（`OK` / `NO_QUOTE` / `EXPIRED_PENDING`） |
| `unrealizedLabel` | string \| null | = 所属持仓行的 `unrealizedLabel`（`无报价` / `已到期，待处理` / `null`），前端原样显示 |

批次已有字段（FEAT-003、FEAT-039 的 `coverage` 等）全部不变。

示例（验收 1、2：甲账户，只列相关字段，`"…"` 表示其它字段不变）：
```
GET /api/option-positions
X-Account-Id: 1
```
```json
{
  "asOfDate": "2026-11-16",
  "positionCount": 2,
  "frozenTotal": "0.00",
  "positions": [
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "ZZQ", "expirationDate": "2026-12-18", "strike": "8", "optionType": "PUT",
      "contractLabel": "ZZQ 2026-12-18 8 P", "direction": "LONG", "contracts": 3, "multiplier": 100,
      "openingNetTotal": "-66.95", "averageNetPerContract": "-22.32", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "0.15", "ask": "0.19", "mark": "0.17", "markBasis": "MID", "…": "…" },
      "marketValue": "51.00",
      "unrealizedPnl": "-15.95",
      "unrealizedPnlPercent": "-23.82",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null,
      "lotCount": 2,
      "lots": [
        {
          "tradeId": 3, "action": "BTO",
          "tradedAt": "2026-11-12T15:00:00Z", "tradedAtLocal": "2026-11-12T10:00:00",
          "originalContracts": 1, "remainingContracts": 1,
          "originalNetAmount": "-25.65", "remainingNetAmount": "-25.65",
          "originalFrozen": "0.00", "remainingFrozen": "0.00", "note": null, "…": "…",
          "marketValue": "17.00",
          "unrealizedPnl": "-8.65",
          "unrealizedPnlPercent": "-33.72",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        },
        {
          "tradeId": 4, "action": "BTO",
          "tradedAt": "2026-11-13T15:00:00Z", "tradedAtLocal": "2026-11-13T10:00:00",
          "originalContracts": 2, "remainingContracts": 2,
          "originalNetAmount": "-41.30", "remainingNetAmount": "-41.30",
          "originalFrozen": "0.00", "remainingFrozen": "0.00", "note": null, "…": "…",
          "marketValue": "34.00",
          "unrealizedPnl": "-7.30",
          "unrealizedPnlPercent": "-17.68",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        }
      ]
    },
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "ZZQ", "expirationDate": "2026-12-18", "strike": "9", "optionType": "CALL",
      "contractLabel": "ZZQ 2026-12-18 9 C", "direction": "SHORT", "contracts": 2, "multiplier": 100,
      "openingNetTotal": "55.68", "averageNetPerContract": "27.84", "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "0.10", "ask": "0.30", "mark": "0.20", "markBasis": "MID", "…": "…" },
      "marketValue": "-40.00",
      "unrealizedPnl": "15.68",
      "unrealizedPnlPercent": "28.16",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null,
      "lotCount": 2,
      "lots": [
        {
          "tradeId": 1, "action": "STO",
          "tradedAt": "2026-11-10T15:00:00Z", "tradedAtLocal": "2026-11-10T10:00:00",
          "originalContracts": 1, "remainingContracts": 1,
          "originalNetAmount": "18.34", "remainingNetAmount": "18.34",
          "originalFrozen": "0.00", "remainingFrozen": "0.00", "note": null, "coverage": "CHECKED", "…": "…",
          "marketValue": "-20.00",
          "unrealizedPnl": "-1.66",
          "unrealizedPnlPercent": "-9.05",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        },
        {
          "tradeId": 2, "action": "STO",
          "tradedAt": "2026-11-11T15:00:00Z", "tradedAtLocal": "2026-11-11T10:00:00",
          "originalContracts": 1, "remainingContracts": 1,
          "originalNetAmount": "37.34", "remainingNetAmount": "37.34",
          "originalFrozen": "0.00", "remainingFrozen": "0.00", "note": null, "coverage": "CHECKED", "…": "…",
          "marketValue": "-20.00",
          "unrealizedPnl": "17.34",
          "unrealizedPnlPercent": "46.44",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        }
      ]
    }
  ]
}
```
（`tradeId` 只是示意；排序按 FEAT-003：同一到期日、同一标的按 `strike` 升序，8P 在 9C 前。）

**验收 4**（甲账户 `POST /api/option-trades/closes` BTC 1 张 9C、净额 20.65 后）：已平仓记录 `realizedPnl` `"-2.31"`（18.34 − 20.65，平掉批次 A，FEAT-004 / FEAT-006 不变）；9C 行 `contracts` 1、`openingNetTotal` `"37.34"`、`marketValue` `"-20.00"`、`unrealizedPnl` `"17.34"`、`unrealizedPnlPercent` `"46.44"`；`lots` 只剩批次 B，字段同上（`"-20.00"` / `"17.34"` / `"46.44"`）。

**验收 5**（`PUT /api/market-data/overrides/...` 把 9C 的 mark 设为 0.35 后，`optionQuote.mark` `"0.35"`、`manual` `true`）：行 `"-70.00"` / `"-14.32"` / `"-25.72"`；批次 A `"-35.00"` / `"-16.66"` / `"-90.84"`；批次 B `"-35.00"` / `"2.34"` / `"6.27"`。清除覆盖后回到上面的响应。

**验收 6**（无法计算与旧报价）：甲账户另有两行，各一个批次：
```json
{ "contractLabel": "ZZQ 2026-12-18 7 P", "direction": "SHORT", "contracts": 1, "openingNetTotal": "12.00",
  "optionQuote": { "mark": null, "contractStatus": "NEVER_QUOTED", "…": "…" },
  "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
  "unrealizedStatus": "NO_QUOTE", "unrealizedLabel": "无报价",
  "lots": [ { "action": "STO", "remainingContracts": 1, "remainingNetAmount": "12.00", "…": "…",
              "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
              "unrealizedStatus": "NO_QUOTE", "unrealizedLabel": "无报价" } ] }
```
```json
{ "contractLabel": "ZZQ 2026-11-13 8 P", "direction": "SHORT", "contracts": 1, "openingNetTotal": "15.00",
  "expirationStatus": "EXPIRED", "daysPastExpiration": 3,
  "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
  "unrealizedStatus": "EXPIRED_PENDING", "unrealizedLabel": "已到期，待处理",
  "lots": [ { "action": "STO", "remainingContracts": 1, "remainingNetAmount": "15.00", "…": "…",
              "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
              "unrealizedStatus": "EXPIRED_PENDING", "unrealizedLabel": "已到期，待处理" } ] }
```
10:15 起 ZZQ 拉取失败：9C 行 `optionQuote.fetchFailed` `true`、`staleLabel` `"行情未更新（上次：11-16 10:00）"`（FEAT-016），`optionQuote.mark` 仍为上次的 `"0.20"`；行与批次 A、B 的数字与上面完全相同（`"-1.66"`、`"17.34"`），`unrealizedStatus` 仍为 `OK`。

---

#### `GET /api/stock-positions`（`lots[]` 新增字段）

请求不变。`positions[].lots[]` 每个批次新增同名 5 个字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `marketValue` | string \| null | `quote.price × remainingShares`，按取整与对齐规则（最后一个批次 = 行 `marketValue` − 其它批次之和），scale 2，非负。`NO_QUOTE` 时 `null` |
| `unrealizedPnl` | string \| null | `marketValue − remainingCost`，scale 2 |
| `unrealizedPnlPercent` | string \| null | `unrealizedPnl × 100 ÷ remainingCost`，HALF_UP 2 位；`remainingCost = 0.00` 或 `NO_QUOTE` 时 `null` |
| `unrealizedStatus` | string | = 所属持仓行（`OK` / `NO_QUOTE`） |
| `unrealizedLabel` | string \| null | = 所属持仓行（`无报价` / `null`） |

所有批次类型（`BUY`、`TRANSFER_IN`、`ASSIGN_BUY`、`EXERCISE_BUY`）同样计算；被 covered call 覆盖与否不影响（FEAT-039 的 `coveredShares` 只在行上）。

示例（验收 3：甲账户，现价 8.65）：
```
GET /api/stock-positions
X-Account-Id: 1
```
```json
{
  "positionCount": 1,
  "positions": [
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "ZZQ", "shares": 200, "coveredShares": 200, "availableShares": 0,
      "totalCost": "1730.65", "averageCost": "8.6533", "…": "…",
      "quote": { "symbol": "ZZQ", "price": "8.65", "manual": false, "…": "…" },
      "marketValue": "1730.00",
      "unrealizedPnl": "-0.65",
      "unrealizedPnlPercent": "-0.04",
      "unrealizedStatus": "OK",
      "unrealizedLabel": null,
      "lotCount": 2,
      "lots": [
        {
          "tradeId": 1, "type": "TRANSFER_IN",
          "tradedAt": "2026-10-01T04:00:00Z", "tradedAtLocal": "2026-10-01T00:00:00", "tradeDate": "2026-10-01",
          "originalShares": 100, "remainingShares": 100,
          "originalCost": "950.00", "remainingCost": "950.00", "note": null,
          "marketValue": "865.00",
          "unrealizedPnl": "-85.00",
          "unrealizedPnlPercent": "-8.95",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        },
        {
          "tradeId": 2, "type": "BUY",
          "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00", "tradeDate": null,
          "originalShares": 100, "remainingShares": 100,
          "originalCost": "780.65", "remainingCost": "780.65", "note": null,
          "marketValue": "865.00",
          "unrealizedPnl": "84.35",
          "unrealizedPnlPercent": "10.81",
          "unrealizedStatus": "OK",
          "unrealizedLabel": null
        }
      ]
    }
  ]
}
```

**验收 7**（现价 8.655，乙账户 `X-Account-Id: 2`）——取整对齐的例子：
```json
{
  "accountId": 2, "accountName": "乙账户",
  "symbol": "ZZQ", "shares": 200, "totalCost": "1740.00", "…": "…",
  "quote": { "symbol": "ZZQ", "price": "8.655", "…": "…" },
  "marketValue": "1731.00", "unrealizedPnl": "-9.00", "unrealizedPnlPercent": "-0.52",
  "unrealizedStatus": "OK", "unrealizedLabel": null,
  "lotCount": 2,
  "lots": [
    { "type": "BUY", "tradedAtLocal": "2026-11-04T10:00:00", "remainingShares": 101, "remainingCost": "880.00", "…": "…",
      "marketValue": "874.16", "unrealizedPnl": "-5.84", "unrealizedPnlPercent": "-0.66",
      "unrealizedStatus": "OK", "unrealizedLabel": null },
    { "type": "BUY", "tradedAtLocal": "2026-11-05T10:00:00", "remainingShares": 99, "remainingCost": "860.00", "…": "…",
      "marketValue": "856.84", "unrealizedPnl": "-3.16", "unrealizedPnlPercent": "-0.37",
      "unrealizedStatus": "OK", "unrealizedLabel": null }
  ]
}
```
S3 = 8.655 × 101 = 874.155 → `874.16`（HALF_UP）；S4 = 1,731.00 − 874.16 = `856.84`（取余数，而不是 8.655 × 99 = 856.845 → 856.85）。两批次之和 1,731.00 = 行市值。

**验收 8**（验收 4 之后，甲账户 `POST /api/stock-trades` `SELL` 50 股、金额 440.00；现价 8.65）：行 `shares` 150、`totalCost` `"1255.65"`、`marketValue` `"1297.50"`、`unrealizedPnl` `"41.85"`、`unrealizedPnlPercent` `"3.33"`；S1 `remainingShares` 50、`remainingCost` `"475.00"`、`marketValue` `"432.50"`、`unrealizedPnl` `"-42.50"`、`unrealizedPnlPercent` `"-8.95"`；S2 `"865.00"` / `"84.35"` / `"10.81"`（不变）。

**`NO_QUOTE` 行**：所有批次 `marketValue` / `unrealizedPnl` / `unrealizedPnlPercent` 为 `null`，`unrealizedStatus` `"NO_QUOTE"`，`unrealizedLabel` `"无报价"`。

---

#### 全部账户（`X-Account-Id: ALL`，验收 9）

两个持仓接口照 FEAT-035 返回全部账户的行；每行的 `lots[]` 新字段与该账户单独请求时逐字相同。现价 8.65 时 `stock-positions`（`ALL`）乙账户 ZZQ 行：
```json
{
  "accountId": 2, "accountName": "乙账户",
  "symbol": "ZZQ", "shares": 200, "totalCost": "1740.00", "…": "…",
  "marketValue": "1730.00", "unrealizedPnl": "-10.00", "unrealizedPnlPercent": "-0.57",
  "unrealizedStatus": "OK", "unrealizedLabel": null,
  "lots": [
    { "remainingShares": 101, "remainingCost": "880.00", "…": "…",
      "marketValue": "873.65", "unrealizedPnl": "-6.35", "unrealizedPnlPercent": "-0.72",
      "unrealizedStatus": "OK", "unrealizedLabel": null },
    { "remainingShares": 99, "remainingCost": "860.00", "…": "…",
      "marketValue": "856.35", "unrealizedPnl": "-3.65", "unrealizedPnlPercent": "-0.42",
      "unrealizedStatus": "OK", "unrealizedLabel": null }
  ]
}
```
`option-positions`（`ALL`）甲账户 9C 行的批次 A、B 同验收 1。写请求带 `ALL` 仍 400 `ALL_ACCOUNTS_READ_ONLY`（FEAT-035，不变）。

---

#### `POST /api/option-trades/expiration-adjustments`（FEAT-043）

响应中的 `position` 与 `GET /api/option-positions` 的 `positions[]` 同一结构，所以其 `lots[]` 也带上述 5 个字段，规则相同。请求、错误不变。

---

#### 错误

没有新错误码。两个持仓接口的错误与原来相同（FEAT-033 / FEAT-035：`ACCOUNT_REQUIRED`、`BAD_REQUEST`、`ACCOUNT_NOT_FOUND`、`METHOD_NOT_ALLOWED`）。行情缺失、已过期不是错误（批次按 `NO_QUOTE` / `EXPIRED_PENDING` 表示）。

---

#### 验收标准的数字从哪里来

| 验收 | 页面上的数字 | 来源 |
|------|------------|------|
| 1、2 | C / P 行与批次 A、B、L1、L2 | `GET /api/option-positions` 行与 `lots[]` 的 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent` |
| 3、7、8 | 股票行与批次 S1–S4 | `GET /api/stock-positions` 行与 `lots[]` 同名字段 |
| 4 | 已平仓 `−2.31`；C 行与唯一批次 B | `POST /api/option-trades/closes` 后重读 `GET /api/closed-trades`（FEAT-006）与 `option-positions` |
| 5 | 手动 mark 0.35 | `PUT` / `DELETE /api/market-data/overrides/...`（FEAT-016）后重读 `option-positions` |
| 6 | `无报价`、`已到期，待处理`、旧报价仍计算 | 批次 `unrealizedLabel`（= 行）；拉取失败后行 `optionQuote.fetchFailed`，批次数字不变 |
| 9 | 全部账户 | 两个持仓接口带 `X-Account-Id: ALL` |

**核对（按现有规则重算，全部对得上）**：
- C（空头，mark 0.20）：行 −0.20×200 = −40.00，55.68 − 40.00 = 15.68，15.68 / 55.68 = 28.16%。A：−20.00，18.34 − 20.00 = −1.66，−1.66 / 18.34 = −9.051… → −9.05%；B（余数）= −40.00 − (−20.00) = −20.00，37.34 − 20.00 = 17.34，17.34 / 37.34 = 46.438… → 46.44%。
- P（多头，mark 0.17）：行 0.17×300 = 51.00，−66.95 + 51.00 = −15.95，−15.95 / 66.95 = −23.823… → −23.82%。L1：17.00，−25.65 + 17.00 = −8.65，−33.723… → −33.72%；L2（余数）= 51.00 − 17.00 = 34.00，−41.30 + 34.00 = −7.30，−17.675… → −17.68%。
- 股票甲（8.65）：行 1,730.00 − 1,730.65 = −0.65，−0.037… → −0.04%。S1 865.00 − 950.00 = −85.00，−8.947… → −8.95%；S2（余数）1,730.00 − 865.00 = 865.00，865.00 − 780.65 = 84.35，10.805… → 10.81%。
- 验收 4：A 平仓 18.34 − 20.65 = −2.31；只剩 B，行 = B。
- 验收 5（0.35）：行 −70.00，55.68 − 70.00 = −14.32，−25.718… → −25.72%；A −35.00，−16.66，−90.839… → −90.84%；B −35.00，2.34，6.266… → 6.27%。
- 验收 7（8.655，乙）：行 8.655×200 = 1,731.00，1,731.00 − 1,740.00 = −9.00，−0.517… → −0.52%；S3 874.155 → 874.16，−5.84，−0.663… → −0.66%；S4 856.84，−3.16，−0.367… → −0.37%。
- 验收 8：卖 50 股从 S1 扣，扣除成本 950.00 × 50 / 100 = 475.00，S1 剩 475.00；行 8.65×150 = 1,297.50，成本 475.00 + 780.65 = 1,255.65，41.85，3.332… → 3.33%；S1 432.50 − 475.00 = −42.50，−8.947… → −8.95%；S2（余数）1,297.50 − 432.50 = 865.00，84.35。覆盖：C 剩 1 张需 100 股 ≤ 150 股，卖出不被 FEAT-039 拒绝。
- 验收 9（8.65，乙）：行 1,730.00 − 1,740.00 = −10.00，−0.574… → −0.57%；S3 8.65×101 = 873.65，−6.35，−0.721… → −0.72%；S4（余数）856.35，−3.65，−0.424… → −0.42%。

---

#### 实现说明（backend）
- **domain**（`com.optionmgr.valuation.domain`，纯 Java）：新增 `LotValuation`（或在 `UnrealizedValuation` 中加静态方法）`optionLots(RowValuation row, boolean longPosition, int multiplier, BigDecimal mark, List<(remainingContracts, remainingNetAmount)>)`、`stockLots(RowValuation row, BigDecimal price, List<(remainingShares, remainingCost)>)` → `List<RowValuation>`：行不是 `OK` 时每个批次 `RowValuation.unavailable(row.status())`；否则前 n−1 个各自取整、最后一个取 `row.marketValue() − Σ`，盈亏与百分比复用 `UnrealizedValuation.percent`。规则全部在这里，单元测试覆盖验收 1–9 的数字、只有 1 个批次、0 成本批次（`null`）、Q238 余数（8.655 例）。
- **web**：`OptionPositionController.toResponse` / `LotResponse`、`StockPositionController.toLot` / `StockTradeDtos.StockLotResponse` 新增 5 个字段，用同一行的 `RowValuation` 和同一个 mark / price 计算；FEAT-043 调整响应复用同一组装代码，因此自动带上。
- 不改 service 的查询、不改 `unrealized-summary` / `underlying-summary` / `greeks-summary`，无迁移、无测试支持接口。

#### 已知限制
- 批次值依赖所在持仓：最后一个批次的市值是余数，单看它可能与 `价格 × 数量` 各自取整差几分（Q238 A）。
- 不预估平仓手续费（Q153）；没有按批次的 Greeks、持有天数、年化（需求 Out of Scope）。
