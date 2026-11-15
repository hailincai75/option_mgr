# FEAT-019 组合 Greeks 汇总（只读，实时计算，不存库）

口径全部已定：Q174–Q182（以及引用的 Q154 / Q157 / Q158 / Q160 / Q161 / Q163–Q173）。行情字段沿用 [FEAT-016.md](FEAT-016.md)，行 Greeks 沿用 [FEAT-018.md](FEAT-018.md)，"未计入"提示文字沿用 [FEAT-017.md](FEAT-017.md)。示例标的均为虚构（QRTV、IXQZ），数字与需求验收一致（今天 2026-11-23 星期一，10:00 拉取成功，标的价 QRTV 52.40、IXQZ 61.10，multiplier 100）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/positions/greeks-summary` | 新接口（按账户，要求 `X-Account-Id`） | — |
| 其它所有接口（含 `GET /api/option-positions`、`GET /api/stock-positions`、`GET /api/positions/unrealized-summary`、`/api/market-data/**`、测试支持） | — | — |

没有写操作、没有数据库迁移、没有新的测试支持接口：每次请求按当时的持仓、FEAT-016 显示的标的价和 FEAT-018 的行 Greeks 实时计算。

---

#### 概念
- **分组**（规则 1，Q174）：`X-Account-Id` 账户**全部**当前持仓中，有期权持仓（含已过期未处理、无报价的行）或股票持仓的每个标的一组。只有股票、只有期权都算。不受持仓页到期过滤影响（接口没有过滤参数，Q158）。没有持仓的标的不出现（验收 9：平掉全部期权并卖出全部股票后该组消失）。
- **期权部分**（规则 2 / 6，Q176 / Q180）：对该标的每个期权行取 FEAT-018 的 `greeks`（与 `GET /api/option-positions` 行内**同一个计算方法**）：
  - `status = NO_QUOTE` / `EXPIRED_PENDING` 的行不计入，按原因计数（`noQuoteCount` / `expiredPendingCount`）。
  - `status = OK` 的行计入（`includedCount`）；它的 `deltaPosition` / `gammaPosition` / `thetaPosition` / `vegaPosition`（已取整到 2 位）分别相加。某个持仓值为 `null`（Q172）→ 只在该 Greek 上不计入，该 Greek 的缺值计数 +1（`deltaMissingCount` 等）。
  - 某个 Greek 没有任何一行贡献值（该标的没有期权行、期权行全部被排除、或计入的行该字段都缺失）→ 该 Greek 合计为 `null`（页面 `—`），不是 `"0.00"`。
- **股票 Delta**（规则 3）= 该标的当前持股数 × 1，scale 2；没有股票时为 `"0.00"`（永不为 `null`）。不需要报价，股价为空也照常。被 covered call 锁定的股数、指派 / 行权生成的股票照常计入（就是 FEAT-011 当前持股数）。
- **净 Delta**（规则 4）= 期权 Delta 合计 + 股票 Delta。期权 Delta 为 `null` 时：有股票 → 净 Delta = 股票 Delta（规则 6 第 3 点）；没有股票 → 净 Delta 为 `null`（边界"只剩已到期待处理期权、没有股票的标的"，页面 `—`）。
- **标的价**（规则 5，Q178）：FEAT-016 `QuoteBook` 中该标的的**显示用标的价**（与持仓行 `underlyingQuote.price`、股票行 `quote.price` 同一个值：含手动覆盖、拉取失败保留的上次价、只剩已过期合约时的最后标的价 Q151）；`underlyingPriceManual` 即该对象的 `manual`。
- **Delta 美元敞口**（规则 5 / 8，Q179 / Q181）：`exposureStatus` 判定顺序（先命中为准）：
  1. `NO_DELTA`：净 Delta 为 `null` → 敞口 `null`，页面显示 `—`；不计入全组合，也**不算**"无标的价"（它的原因已由 `excludedLabel` 说明）。
  2. `NO_UNDERLYING_PRICE`：标的价为 `null` → 敞口 `null`，`exposureLabel = 无标的价`；不计入全组合，计入全组合的 `noUnderlyingPriceCount`。
  3. `OK`：敞口 = 净 Delta（scale 2）× 标的价，`setScale(2, HALF_UP)`（负数按绝对值进位）。净 Delta 为 `0.00` → `"0.00"`。标的价为手动值时 `manualPriceNote = 按手动标的价`。
- **全组合**（规则 7，Q177）：只给 Delta 美元敞口、Theta、Vega 三个合计（不给跨标的 Delta 股数和 Gamma）。敞口 = 各 `exposureStatus = OK` 组的 `dollarExposure` 之和；Theta / Vega = 各组非 `null` 的 `theta` / `vega` 之和。都是已取整的 scale-2 值直接相加，不再取整（Q181，同 Q154）。没有可计入的值时为 `"0.00"`（永不为 `null`，验收 8）。
- **数值格式**：所有数值字段为字符串，scale 2，负数带 `-`，正数**不带 `+`**（同 FEAT-017 / 018 约定；显式 `+` / `−` 和千分位由前端加），0 为 `"0.00"`（`toPlainString()`，无负零）。`underlyingPrice` 沿用 FEAT-016 价格格式（去尾零后至少 2 位小数，如 `"52.40"`、`"60.00"`、`"52.125"`）。
- **提示文字**：全部由后端拼好，前端原样灰字显示，不按计数自己拼（见下方字段表）。

---

#### `GET /api/positions/greeks-summary`（新）
当前账户（`X-Account-Id`）的按标的 Greeks 汇总与全组合合计。无参数、无请求体。

**为什么单独一个接口**：汇总横跨期权和股票两个持仓接口，理由同 FEAT-017 的 `unrealized-summary`；也天然不受前端到期过滤影响。前端在任何会让持仓重新查询的时机同时让它重新查询（切换账户、立即刷新、设置 / 清除手动值、录入 / 平仓 / 到期处理 / 调整到期日等）。

响应 `200 OK`（验收 1–3：账户 A）：
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    {
      "symbol": "IXQZ",
      "optionDelta": "-18.35",
      "stockDelta": "100.00",
      "netDelta": "81.65",
      "gamma": "-3.13",
      "theta": "2.47",
      "vega": "-5.03",
      "underlyingPrice": "61.10",
      "underlyingPriceManual": false,
      "underlyingPriceLabel": null,
      "dollarExposure": "4988.82",
      "exposureStatus": "OK",
      "exposureLabel": null,
      "manualPriceNote": null,
      "optionRowCount": 1,
      "includedCount": 1,
      "noQuoteCount": 0,
      "expiredPendingCount": 0,
      "deltaMissingCount": 0,
      "gammaMissingCount": 0,
      "thetaMissingCount": 0,
      "vegaMissingCount": 0,
      "excludedLabel": null,
      "missingLabel": null
    },
    {
      "symbol": "QRTV",
      "optionDelta": "87.21",
      "stockDelta": "0.00",
      "netDelta": "87.21",
      "gamma": "4.48",
      "theta": "-3.24",
      "vega": "5.89",
      "underlyingPrice": "52.40",
      "underlyingPriceManual": false,
      "underlyingPriceLabel": null,
      "dollarExposure": "4569.80",
      "exposureStatus": "OK",
      "exposureLabel": null,
      "manualPriceNote": null,
      "optionRowCount": 3,
      "includedCount": 2,
      "noQuoteCount": 1,
      "expiredPendingCount": 0,
      "deltaMissingCount": 0,
      "gammaMissingCount": 0,
      "thetaMissingCount": 0,
      "vegaMissingCount": 0,
      "excludedLabel": "1 个持仓无报价，未计入",
      "missingLabel": null
    }
  ],
  "portfolio": {
    "dollarExposure": "9558.62",
    "theta": "-0.77",
    "vega": "0.86",
    "noQuoteCount": 1,
    "expiredPendingCount": 0,
    "noUnderlyingPriceCount": 0,
    "deltaMissingCount": 0,
    "thetaMissingCount": 0,
    "vegaMissingCount": 0,
    "excludedLabel": "1 个持仓无报价，未计入",
    "noUnderlyingPriceLabel": null,
    "missingLabel": null
  }
}
```
（核对：QRTV 期权 Delta 30.21 + 57.00 = 87.21，× 52.40 = 4569.804 → `4569.80`；Gamma −5.12 + 9.60 = 4.48；Theta 4.56 − 7.80 = −3.24；Vega −7.11 + 13.00 = 5.89。IXQZ 净 Delta −18.35 + 100 = 81.65，× 61.10 = 4988.815 → `4988.82`。全组合 4569.80 + 4988.82 = 9558.62；Theta −3.24 + 2.47 = −0.77；Vega 5.89 − 5.03 = 0.86。）

**顶层字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `asOfDate` | string | 美东今天（判断 `EXPIRED_PENDING` 用，与 `GET /api/option-positions` 的 `asOfDate` 同义） |
| `groups` | array | 每个标的一组，按 `symbol` 字母升序；没有持仓为 `[]` |
| `portfolio` | object | 全组合合计，永不为 `null` |

**`groups[]`**

| 字段 | 类型 | 说明 |
|------|------|------|
| `symbol` | string | 标的 |
| `optionDelta` / `gamma` / `theta` / `vega` | string \| null | 期权部分合计（计入行的持仓值之和），scale 2。没有任何行贡献该 Greek 时 `null`（页面 `—`）。Gamma / Theta / Vega 只来自期权（股票没有） |
| `stockDelta` | string | 持股数 × 1，scale 2；无股票 `"0.00"` |
| `netDelta` | string \| null | `optionDelta`（`null` 按 0）+ `stockDelta`；`optionDelta` 为 `null` 且无股票时 `null` |
| `underlyingPrice` | string \| null | 显示用标的价（同 `underlyingQuote.price`）；`null` = 无标的价 |
| `underlyingPriceManual` | boolean | 标的价是否手动覆盖（同 `underlyingQuote.manual`） |
| `underlyingPriceLabel` | string \| null | `underlyingPrice = null` 时 `无标的价`，否则 `null`。标的价列原样显示 |
| `dollarExposure` | string \| null | Delta 美元敞口，scale 2；`exposureStatus ≠ OK` 时 `null` |
| `exposureStatus` | string | `OK` / `NO_UNDERLYING_PRICE` / `NO_DELTA`（见概念） |
| `exposureLabel` | string \| null | `NO_UNDERLYING_PRICE` → `无标的价`；其它 `null`（`NO_DELTA` 页面显示 `—`） |
| `manualPriceNote` | string \| null | `exposureStatus = OK` 且标的价为手动值时 `按手动标的价`，否则 `null` |
| `optionRowCount` | number | 该标的期权行数（含被排除的） |
| `includedCount` | number | `greeks.status = OK` 的期权行数 |
| `noQuoteCount` / `expiredPendingCount` | number | `NO_QUOTE` / `EXPIRED_PENDING` 的期权行数 |
| `deltaMissingCount` / `gammaMissingCount` / `thetaMissingCount` / `vegaMissingCount` | number | `OK` 行中该持仓值为 `null` 的行数 |
| `excludedLabel` | string \| null | 格式同 FEAT-017：`N 个持仓无报价，未计入` / `M 个持仓已到期待处理，未计入` / `N 个持仓无报价、M 个持仓已到期待处理，未计入`；都为 0 时 `null` |
| `missingLabel` | string \| null | 按 Delta、Gamma、Theta、Vega 顺序，缺值个数相同的 Greek 用 `、` 合并为一段 `<名称>：<个数> 个持仓缺值`，不同个数的段之间用 `；` 连接，末尾加 `，未计入`。例：`Gamma、Vega：1 个持仓缺值，未计入`；`Gamma：1 个持仓缺值；Vega：2 个持仓缺值，未计入`。全为 0 时 `null` |

**`portfolio`**

| 字段 | 类型 | 说明 |
|------|------|------|
| `dollarExposure` | string | `exposureStatus = OK` 各组 `dollarExposure` 之和，scale 2；无则 `"0.00"` |
| `theta` / `vega` | string | 各组非 `null` 的 `theta` / `vega` 之和，scale 2；无则 `"0.00"` |
| `noQuoteCount` / `expiredPendingCount` | number | 各组之和 |
| `noUnderlyingPriceCount` | number | `exposureStatus = NO_UNDERLYING_PRICE` 的组数 |
| `deltaMissingCount` / `thetaMissingCount` / `vegaMissingCount` | number | 各组之和（Gamma 不在全组合显示，不汇总） |
| `excludedLabel` | string \| null | 同组的格式，用全组合计数 |
| `noUnderlyingPriceLabel` | string \| null | `N 个标的无标的价，美元敞口未计入`；0 时 `null` |
| `missingLabel` | string \| null | 同组的格式，只含 Delta、Theta、Vega（Delta 缺值影响美元敞口）；全为 0 时 `null` |

前端显示约定（格式化，不是计算）：
- 所有数值带显式符号（> 0 加 `+`，< 0 用 `−`，`0.00` 无符号），美元敞口带千分位（`+4,569.80`）；`null` → `—`。标的价原样显示（不加符号）；`underlyingPrice = null` 显示 `underlyingPriceLabel`。
- `exposureStatus = NO_UNDERLYING_PRICE` 敞口列显示 `exposureLabel`；`NO_DELTA` 显示 `—`。
- 每组的 `manualPriceNote`、`excludedLabel`、`missingLabel` 非 `null` 时以灰色小字显示在该行（各一行，按此顺序）。
- 合计行 `全组合`：只填 `Delta 美元敞口`、`Theta`、`Vega`；下方依次灰字显示非 `null` 的 `excludedLabel`、`noUnderlyingPriceLabel`、`missingLabel`。
- 计数字段供测试与排查；页面只用 Label 文字。

##### 其它示例

验收 4（账户 B，只有 P5 和 QRTV 100 股）：
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    { "symbol": "QRTV", "optionDelta": "90.63", "stockDelta": "100.00", "netDelta": "190.63",
      "gamma": "-15.36", "theta": "13.68", "vega": "-21.33",
      "underlyingPrice": "52.40", "underlyingPriceManual": false, "underlyingPriceLabel": null,
      "dollarExposure": "9989.01", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
      "optionRowCount": 1, "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0,
      "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
      "excludedLabel": null, "missingLabel": null }
  ],
  "portfolio": { "dollarExposure": "9989.01", "theta": "13.68", "vega": "-21.33",
    "noQuoteCount": 0, "expiredPendingCount": 0, "noUnderlyingPriceCount": 0,
    "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
    "excludedLabel": null, "noUnderlyingPriceLabel": null, "missingLabel": null }
}
```
（190.63 × 52.40 = 9989.012 → `9989.01`）

验收 5（10:05 QRTV 标的价手动设为 52.00；10:15 拉取失败后不变）：账户 A 的 QRTV 组 `"underlyingPrice": "52.00"`、`"underlyingPriceManual": true`、`"dollarExposure": "4534.92"`（87.21 × 52 = 4534.92）、`"manualPriceNote": "按手动标的价"`，Greeks 与计数同上；`portfolio.dollarExposure` `"9523.74"`。账户 B 的 QRTV `"dollarExposure": "9912.76"`（190.63 × 52）、`manualPriceNote` 同上，`portfolio.dollarExposure` `"9912.76"`。清除手动值后回到上面的响应。

验收 6（10:15 拉取成功，P2 的 gamma、vega 为 `null`；账户 A 的 QRTV 组）：
```json
{ "symbol": "QRTV", "optionDelta": "87.21", "stockDelta": "0.00", "netDelta": "87.21",
  "gamma": "-5.12", "theta": "-3.24", "vega": "-7.11",
  "underlyingPrice": "52.40", "underlyingPriceManual": false, "underlyingPriceLabel": null,
  "dollarExposure": "4569.80", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
  "optionRowCount": 3, "includedCount": 2, "noQuoteCount": 1, "expiredPendingCount": 0,
  "deltaMissingCount": 0, "gammaMissingCount": 1, "thetaMissingCount": 0, "vegaMissingCount": 1,
  "excludedLabel": "1 个持仓无报价，未计入",
  "missingLabel": "Gamma、Vega：1 个持仓缺值，未计入" }
```
`portfolio`：`"dollarExposure": "9558.62"`、`"theta": "-0.77"`、`"vega": "-12.14"`（−7.11 − 5.03）、`"vegaMissingCount": 1`、`"excludedLabel": "1 个持仓无报价，未计入"`、`"missingLabel": "Vega：1 个持仓缺值，未计入"`。

验收 7（账户 A 另有 P6 已过期未处理、P7 今天到期 52P：持仓值 Delta +45.00 / Gamma −12.00 / Theta +8.00 / Vega −1.50）：
```json
{ "symbol": "QRTV", "optionDelta": "132.21", "stockDelta": "0.00", "netDelta": "132.21",
  "gamma": "-7.52", "theta": "4.76", "vega": "4.39",
  "underlyingPrice": "52.40", "underlyingPriceManual": false, "underlyingPriceLabel": null,
  "dollarExposure": "6927.80", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
  "optionRowCount": 5, "includedCount": 3, "noQuoteCount": 1, "expiredPendingCount": 1,
  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
  "excludedLabel": "1 个持仓无报价、1 个持仓已到期待处理，未计入", "missingLabel": null }
```
（132.21 × 52.40 = 6927.804 → `6927.80`）`portfolio`：`"dollarExposure": "11916.62"`、`"theta": "7.23"`、`"vega": "-0.64"`、`"noQuoteCount": 1`、`"expiredPendingCount": 1`、`"excludedLabel": "1 个持仓无报价、1 个持仓已到期待处理，未计入"`。

验收 8（行情库为空，账户 C 只持 IXQZ 100 股）：
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    { "symbol": "IXQZ", "optionDelta": null, "stockDelta": "100.00", "netDelta": "100.00",
      "gamma": null, "theta": null, "vega": null,
      "underlyingPrice": null, "underlyingPriceManual": false, "underlyingPriceLabel": "无标的价",
      "dollarExposure": null, "exposureStatus": "NO_UNDERLYING_PRICE", "exposureLabel": "无标的价", "manualPriceNote": null,
      "optionRowCount": 0, "includedCount": 0, "noQuoteCount": 0, "expiredPendingCount": 0,
      "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
      "excludedLabel": null, "missingLabel": null }
  ],
  "portfolio": { "dollarExposure": "0.00", "theta": "0.00", "vega": "0.00",
    "noQuoteCount": 0, "expiredPendingCount": 0, "noUnderlyingPriceCount": 1,
    "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
    "excludedLabel": null, "noUnderlyingPriceLabel": "1 个标的无标的价，美元敞口未计入", "missingLabel": null }
}
```
手动设 IXQZ 标的价 60.00 后：`"underlyingPrice": "60.00"`、`"underlyingPriceManual": true`、`"underlyingPriceLabel": null`、`"dollarExposure": "6000.00"`、`"exposureStatus": "OK"`、`"exposureLabel": null`、`"manualPriceNote": "按手动标的价"`；`portfolio.dollarExposure` `"6000.00"`、`noUnderlyingPriceCount` `0`、`noUnderlyingPriceLabel` `null`。

验收 9（账户 A STC 2 张 P2 后）：QRTV 组 `"optionDelta": "30.21"`、`"netDelta": "30.21"`、`"dollarExposure": "1583.00"`（30.21 × 52.40 = 1583.004）、`"gamma": "-5.12"`、`"theta": "4.56"`、`"vega": "-7.11"`、`"optionRowCount": 2`、`"includedCount": 1`；`portfolio` `"6571.82"` / `"7.03"` / `"-12.14"`。再 BTC P3 并卖出全部 IXQZ 股票后，`groups` 中没有 IXQZ。

只剩已到期待处理期权、没有股票的标的（边界）：`optionDelta` / `gamma` / `theta` / `vega` / `netDelta` / `dollarExposure` 均 `null`，`exposureStatus` `"NO_DELTA"`，`exposureLabel` `null`，`excludedLabel` `"1 个持仓已到期待处理，未计入"`；该组不计入 `noUnderlyingPriceCount`。

没有任何持仓的账户：`"groups": []`，`portfolio` 三个数值 `"0.00"`、计数全 0、三个 Label `null`。

错误：

| 情况 | HTTP | code |
|------|------|------|
| 没带 `X-Account-Id` | 400 | `ACCOUNT_REQUIRED`（FEAT-033） |
| `X-Account-Id` 不是整数 | 400 | `BAD_REQUEST` |
| 账户不存在 | 404 | `ACCOUNT_NOT_FOUND` |
| 非 `GET` 方法 | 405 | `METHOD_NOT_ALLOWED` |

没有本需求专有的错误码。行情 / Greeks 缺失不是错误（按状态和 `null` 处理）。

---

#### 实现说明（backend）
- **分层**：
  - `domain`（纯 Java，不依赖 Spring）：`com.optionmgr.valuation.domain.GreeksSummary`。输入：每个期权行的 `symbol` + FEAT-018 `RowGreeks`、每个标的的持股数、每个标的的显示用标的价与 `manual` 标志；输出各组与全组合（含全部计数和 Label 文字）。分组、排序、`null` 规则、`exposureStatus` 判定顺序、取整、文字拼接全在这里。`excludedLabel` 的拼法与 `UnrealizedSummary` 相同，抽成共用的小方法（不复制文字）。单元测试 `valuation/domain/GreeksSummaryTest` 覆盖验收 1–9 的数字和边界（全部排除、只剩已到期无股票 → `NO_DELTA`、净 Delta 0 → `"0.00"`、负敞口 HALF_UP、不同个数的 `missingLabel`）。
  - `service`：在 `UnrealizedValuationService` 加 `greeksSummary(accountId)`（或同包新类）：`options.currentPositions(accountId)`（拿到 `asOfDate` 和全部期权行）、`stocks.currentPositions(accountId)`、**一次** `quotes.book()`；每个期权行调用现有的静态 `UnrealizedValuationService.greeks(p, asOfDate, book.option(c), book.stored(c))`——与 `OptionPositionController.toResponse` 完全同一个方法，所以组内合计 = 持仓接口同一时刻各 `OK` 行持仓值之和。标的价取 `book.underlying(symbol)`（与持仓行 `underlyingQuote`、股票行 `quote` 同一来源，含手动、失败保留、Q151 最后标的价、指数标的为 `null` 除非手动）。`@Transactional(readOnly = true)`。
  - `web`：`valuation.web.GreeksSummaryController`（`GET /api/positions/greeks-summary`，取 `AccountRef`），响应 record 用 `BigDecimal` 字段（全局序列化成字符串）。
- **取整（Q181）**：不重新从原值计算。组内各 Greek = 行 `*Position`（已 `setScale(2, HALF_UP)`）精确相加；`stockDelta = BigDecimal.valueOf(shares).setScale(2)`；`netDelta` 精确相加；`dollarExposure = netDelta.multiply(price).setScale(2, RoundingMode.HALF_UP)`（标的价可有 3–4 位小数，如手动 52.125）；全组合为各组 scale-2 值精确相加。输出 `toPlainString()`。
- **判定复用 FEAT-018**：排除与缺值只看 `RowGreeks.status()` 和各 `Greek.position()` 是否为 `null`，不另判断指数、歧义、手动 mark（Q170：手动 mark 不影响 Greeks 与敞口）。`asOfDate` 由注入 `Clock` 的美东日期得出，同一请求只取一次（到期日当天正常计入，Q171）。
- **一致性**：汇总与 `GET /api/option-positions` 是两个请求；数据未变时数值对得上，若两次请求之间恰好发生行情刷新则可能短暂不同（前端在同一批失效里同时重新查询，与 FEAT-017 合计相同）。
- **分组键**：期权行 `contract().symbol()` 与股票 `StockPosition.symbol()` 都是已规范化的大写标的，直接按字符串分组；`groups` 按 `symbol` 自然序（`String.compareTo`）排序，与股票持仓的 `DISPLAY_ORDER` 一致。
- **multiplier**：行持仓值已用字段值（FEAT-018），本需求不再乘 multiplier；股票 Delta 每股 1，与 multiplier 无关。
- **测试**：`valuation/domain/GreeksSummaryTest`；`valuation/web/GreeksSummaryControllerTest`（in-memory 假数据源 + 可移动时钟覆盖验收 1–9；验收 10 的到期过滤只在前端，后端用"接口无参数、结果同验收 1–3"覆盖）。测试支持无新增接口：场景用 FEAT-016 的 `PUT /api/test-support/market-data/sources` + refresh / tick + 假时钟搭建。

#### 已知限制
- 同一标的多个账户各算各的；跨账户合并在 FEAT-035。
- 不做 Gamma 美元化、情景分析、Beta 加权（Q182）；股票的 Gamma / Theta / Vega 恒为 0，不出现。
- 美元敞口是"取整后的净 Delta × 标的价"（Q181），与用未取整原值计算可能差 1 分以内。
