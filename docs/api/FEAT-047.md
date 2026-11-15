# FEAT-047 全部账户合并查看（三）：按标的盈亏摘要、组合 Greeks 汇总接受 `ALL`

口径：FEAT-035 / FEAT-046 的全部（`X-Account-Id: ALL`、`AccountScope`、只读保护、Q53 / Q214–Q217 / Q222 / Q225 / Q226），以及 Q224、Q233–Q235（均按 PM 建议：**Q224 卡片 A 分开、Greeks B 合并**；Q233 A；Q234 A；Q235 B）。本节只写**新增或不同**的部分。示例账户（甲账户 id 1、乙账户 id 2）和标的（ZZA、ZZB）均为虚构，数字与需求场景 G 一致（今天 2026-11-23 星期一 10:00 美东拉取成功，标的价 ZZA 50.00、ZZB 40.00，multiplier 100）。

**兼容性一览**（纯新增：新的可接受请求头值、一个新的行字段对；没有新端点、没有数据库迁移、没有新错误码；单账户请求的已有字段、数字、排序、错误都**不变**）

| 接口 | 新增（兼容） | 行为变化 |
|------|-------------|---------|
| `GET /api/positions/underlying-summary`（FEAT-023） | 接受 `ALL`：每个"账户 + 标的"一组；`groups[]` 新字段 `accountId`、`accountName`（单账户也有值） | `ALL` 时不再返回 400 `ALL_ACCOUNTS_NOT_SUPPORTED` |
| `GET /api/positions/greeks-summary`（FEAT-019） | 接受 `ALL`：同一标的跨账户**合并为一组**；响应结构完全不变（不加账户字段，Q234 A） | 同上 |

接受 `ALL` 的 GET 从 11 个（FEAT-046）增加到 **13 个**。**仍不接受 `ALL`**（400 `ALL_ACCOUNTS_NOT_SUPPORTED`）：`GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}`（FEAT-046）、`GET /api/audit-log`（永久，Q225）。所有写请求仍 400 `ALL_ACCOUNTS_READ_ONLY`。

---

#### 设计

- **沿用 FEAT-035 的方式**：两个 controller 方法把参数从 `AccountRef` 改为 `AccountScope`，加进"接受 `ALL`"的列表。前端页面查询代码不变（请求头已由统一封装按当前账户加上），只是不再显示占位 / 灰字；query key 已含账户，切换即重查。
- **计算不变（Q53）**：每个账户的持仓行估值（FEAT-017）、行 Greeks（FEAT-018）、已实现（FEAT-040 按标的）都用与 `X-Account-Id: <id>` 完全相同的代码算出，然后才分组 / 相加。没有跨账户 FIFO、覆盖、担保。
- **同一次读取**：一个请求只取一次时钟（`asOfDate`）和一次行情（`QuoteBook`），所有账户共用；同一合约在两个账户的 mark / Greeks 每张原值相同，同一标的的标的价相同。
- **两个接口分组方式不同（Q224）**：
  - 按标的盈亏摘要 → **账户 + 标的** 一组（卡片 A），每组数字与切到该账户时同一标的那组**逐字相同**。
  - 组合 Greeks 汇总 → **标的** 一组（Greeks B），把全部账户该标的的行放在一起按 FEAT-019 规则计算。
- **只有一个账户时**：`ALL` 仍有效（摘要结果 = 该账户 + 账户字段；Greeks 结果 = 该账户，逐字相同）。**新增空账户**：不贡献任何组、行，两个接口结果不变（验收 10）。

---

#### `GET /api/positions/underlying-summary`（`ALL`；新字段 `accountId`、`accountName`）

无参数、无请求体（同 FEAT-023）。

- **分组集合**：对 scope 中每个账户，取该账户的 FEAT-023 分组（= 该账户 `option-positions` 与 `stock-positions` 的 `symbol` 并集）；`ALL` 时即全部账户 `option-positions`（`ALL`）+ `stock-positions`（`ALL`）的 `(accountId, symbol)` 并集——与前端用这两个接口按 `accountId + symbol` 分卡片的集合完全相同。
- **每组的字段**：FEAT-023 原有字段全部不变、按该账户单独计算：
  - `realized` = **该账户** `GET /api/pnl-summary`（`X-Account-Id: <accountId>`，不带日期）`bySymbol` 的该标的行（不是 `ALL` 下跨账户合并的那一行）；
  - `marketValue` / `unrealizedPnl` / `totalPnl` / `netPremium` / 计数 / `excludedLabel` 只来自该账户该标的的持仓行。
- **新字段**（单账户请求也有值，就是该账户；前端类型不用分两套）：

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | `groups[].accountId` | number | 该组所属账户 |
  | `groups[].accountName` | string | 该账户请求时的当前名称（改名后立即反映）。前端在 `ALL` 下用作卡片标题前缀 `<accountName> · <symbol>`；单账户下标题不显示账户名（需求验收 2） |

- **排序（规则 3、Q203 + Q214）**：`symbol` 升序，同一 `symbol` 按 `accountId` 升序。单账户下每个标的只有一组，顺序不变。
- **没有合计**（Q201）：响应不给跨组合计（与 FEAT-023 相同）；全部账户的合计看 `GET /api/positions/unrealized-summary`（`ALL`）与 `GET /api/pnl-summary`（`ALL`）。
- `asOfDate`：美东今天（同单账户）。所有账户都没有持仓 → `"groups": []`（页面 `暂无持仓`）。

示例（场景 G，验收 1、2）：
```
GET /api/positions/underlying-summary
X-Account-Id: ALL
```
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    {
      "accountId": 1,
      "accountName": "甲账户",
      "symbol": "ZZA",
      "realized": {
        "closedCount": 1,
        "realizedPnl": "39.70",
        "option": { "closedCount": 1, "realizedPnl": "39.70" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": "4940.00",
      "unrealizedPnl": "225.35",
      "totalPnl": "265.05",
      "netPremium": "85.35",
      "optionPositionCount": 1,
      "hasStockPosition": true,
      "includedCount": 2,
      "noQuoteCount": 0,
      "expiredPendingCount": 0,
      "excludedLabel": null
    },
    {
      "accountId": 2,
      "accountName": "乙账户",
      "symbol": "ZZA",
      "realized": {
        "closedCount": 1,
        "realizedPnl": "80.00",
        "option": { "closedCount": 1, "realizedPnl": "80.00" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": "-220.00",
      "unrealizedPnl": "10.00",
      "totalPnl": "90.00",
      "netPremium": "230.00",
      "optionPositionCount": 1,
      "hasStockPosition": false,
      "includedCount": 1,
      "noQuoteCount": 0,
      "expiredPendingCount": 0,
      "excludedLabel": null
    },
    {
      "accountId": 1,
      "accountName": "甲账户",
      "symbol": "ZZB",
      "realized": {
        "closedCount": 0,
        "realizedPnl": "0.00",
        "option": { "closedCount": 0, "realizedPnl": "0.00" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": "-110.00",
      "unrealizedPnl": "10.00",
      "totalPnl": "10.00",
      "netPremium": "120.00",
      "optionPositionCount": 1,
      "hasStockPosition": false,
      "includedCount": 1,
      "noQuoteCount": 0,
      "expiredPendingCount": 0,
      "excludedLabel": null
    },
    {
      "accountId": 2,
      "accountName": "乙账户",
      "symbol": "ZZB",
      "realized": {
        "closedCount": 0,
        "realizedPnl": "0.00",
        "option": { "closedCount": 0, "realizedPnl": "0.00" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": null,
      "unrealizedPnl": null,
      "totalPnl": "0.00",
      "netPremium": "50.00",
      "optionPositionCount": 1,
      "hasStockPosition": false,
      "includedCount": 0,
      "noQuoteCount": 0,
      "expiredPendingCount": 1,
      "excludedLabel": "1 个持仓已到期待处理，未计入"
    }
  ]
}
```
单账户 `X-Account-Id: 1`：只有上面 accountId 1 的两组（ZZA、ZZB），数字逐字相同，每组带 `"accountId": 1, "accountName": "甲账户"`。

验收 8（甲账户 BTC 1 张 P1 净额 60.65 后，`ALL` 下甲 ZZA 组；其它三组不变）：
```json
{
  "accountId": 1, "accountName": "甲账户", "symbol": "ZZA",
  "realized": { "closedCount": 2, "realizedPnl": "64.40",
                "option": { "closedCount": 2, "realizedPnl": "64.40" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
  "marketValue": "5000.00", "unrealizedPnl": "200.00", "totalPnl": "264.40", "netPremium": null,
  "optionPositionCount": 0, "hasStockPosition": true,
  "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null
}
```

**一致性（规则 4，由构造保证）**：对同一 scope，
- 各组非 `null` 的 `marketValue` / `unrealizedPnl` 之和 = `GET /api/positions/unrealized-summary` 的 `total.marketValue` / `total.unrealizedPnl`；各组 `includedCount` / `noQuoteCount` / `expiredPendingCount` 之和 = `total` 对应计数（场景 G：`4610.00` / `245.35`，已到期 1）；
- 同一 `symbol` 各组 `realized` 之和（笔数、金额、期权 / 股票拆分）= `GET /api/pnl-summary`（同一 scope，不带日期）`bySymbol` 该标的一行（场景 G：ZZA 2 笔 `119.70` = 39.70 + 80.00）。只有历史、当前无持仓的"账户 + 标的"不出组，所以该等式只对每个账户都仍有持仓的标的成立（同 FEAT-023 只含持仓标的的口径）；
- 每组 `netPremium` = `option-positions`（同一 scope）中 `accountId`、`symbol` 相同各行 `openingNetTotal` 之和。

**卡片 `已实现` 链接（Q235 B，前端）**：`ALL` 下点击某组的 `realized.realizedPnl`，前端先把当前账户切换为该组 `accountId`，再打开 `/closed?symbol=<symbol>`（与 FEAT-046 Q231 B"历史"的做法相同）；已平仓页随后以 `X-Account-Id: <accountId>` 请求 `GET /api/closed-trades?symbol=<symbol>`，合计与卡片数字一致。后端无改动。单账户下链接行为不变（FEAT-023）。卡片内其它链接（合约 → 交易列表、标的 → 股票交易）**留在 `ALL`**（规则 7，FEAT-046 已支持）。

---

#### `GET /api/positions/greeks-summary`（`ALL`；结构不变）

无参数、无请求体；响应结构、字段、格式、`null` 规则、Label 文字与 FEAT-019 **完全相同**，不加账户字段（Q234 A：不显示各账户构成，看单个账户用切换器）。

- **分组（规则 5）**：全部账户中有期权持仓（含已过期未处理、无报价）或股票持仓的每个 `symbol` 一组（不按账户分组），按 `symbol` 升序。
- **组内计算** = 把全部账户该标的的输入放在一起，走 FEAT-019 同一个 domain 计算：
  - 期权部分：全部账户该标的每个期权行的 FEAT-018 `greeks`（与 `option-positions`（`ALL`）行内同一方法）；`OK` 行的已取整持仓值精确相加；排除 / 缺值按行计数（全部账户合计）；`optionRowCount` / `includedCount` / `noQuoteCount` / `expiredPendingCount` / `*MissingCount` 都是全部账户的行数之和。
  - `stockDelta` = 全部账户该标的持股数之和 × 1（scale 2）。
  - `netDelta`、`exposureStatus`、`NO_DELTA` / `NO_UNDERLYING_PRICE` 判定、`underlyingPrice` / `underlyingPriceManual` / `manualPriceNote`：FEAT-019 原规则，作用于合并后的值。
- **美元敞口取整（Q233 A）**：`dollarExposure = 合并后的 netDelta × 标的价`，再 `setScale(2, HALF_UP)`——与 FEAT-019 / Q181 同一公式，**不是**各账户已取整敞口相加。因此合并行（及全组合）可能与各账户对应值之和差 0.01 级。例（FEAT-019 的数字）：甲 净 Delta 87.21、乙 190.63、标的价 52.40 → 合并 277.84 × 52.40 = 14,558.816 → `"14558.82"`；各账户之和为 4,569.80 + 9,989.01 = 14,558.81。Greeks 本身（Delta / Gamma / Theta / Vega）是已取整行值的精确和，与各账户之和完全相等。
- **全组合（规则 6）**：FEAT-019 原规则作用于合并后的组：`dollarExposure` = 各 `OK` 组敞口之和；`theta` / `vega` = 各组非 `null` 值之和；计数为各合并组之和。注意 `noUnderlyingPriceCount` 按**合并后的标的组**计（某标的无标的价在 `ALL` 下只算 1 个，即使两个账户都持有）。
- **合并后状态可能与单账户不同**：例如乙账户的 ZZB 只有已到期待处理的 P4 → 乙单账户 ZZB 组为 `NO_DELTA`；`ALL` 下 ZZB 合并了甲的 P2，`exposureStatus = OK`，P4 只计入 `expiredPendingCount`。这是按标的合并的正常结果。
- **手动 mark**（Q170）：不影响 Greeks 与敞口；**手动标的价**：只有一个标的价（全系统一份），合并行直接用它。

示例（场景 G，验收 5、6）：
```
GET /api/positions/greeks-summary
X-Account-Id: ALL
```
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    {
      "symbol": "ZZA",
      "optionDelta": "10.00",
      "stockDelta": "100.00",
      "netDelta": "110.00",
      "gamma": "-10.00",
      "theta": "8.00",
      "vega": "-16.00",
      "underlyingPrice": "50.00",
      "underlyingPriceManual": false,
      "underlyingPriceLabel": null,
      "dollarExposure": "5500.00",
      "exposureStatus": "OK",
      "exposureLabel": null,
      "manualPriceNote": null,
      "optionRowCount": 2,
      "includedCount": 2,
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
      "symbol": "ZZB",
      "optionDelta": "30.00",
      "stockDelta": "0.00",
      "netDelta": "30.00",
      "gamma": "-5.00",
      "theta": "4.00",
      "vega": "-4.50",
      "underlyingPrice": "40.00",
      "underlyingPriceManual": false,
      "underlyingPriceLabel": null,
      "dollarExposure": "1200.00",
      "exposureStatus": "OK",
      "exposureLabel": null,
      "manualPriceNote": null,
      "optionRowCount": 2,
      "includedCount": 1,
      "noQuoteCount": 0,
      "expiredPendingCount": 1,
      "deltaMissingCount": 0,
      "gammaMissingCount": 0,
      "thetaMissingCount": 0,
      "vegaMissingCount": 0,
      "excludedLabel": "1 个持仓已到期待处理，未计入",
      "missingLabel": null
    }
  ],
  "portfolio": {
    "dollarExposure": "6700.00",
    "theta": "12.00",
    "vega": "-20.50",
    "noQuoteCount": 0,
    "expiredPendingCount": 1,
    "noUnderlyingPriceCount": 0,
    "deltaMissingCount": 0,
    "thetaMissingCount": 0,
    "vegaMissingCount": 0,
    "excludedLabel": "1 个持仓已到期待处理，未计入",
    "noUnderlyingPriceLabel": null,
    "missingLabel": null
  }
}
```

单账户（验收 6，不变）：
- `X-Account-Id: 1`：ZZA `optionDelta` `"-20.00"`、`stockDelta` `"100.00"`、`netDelta` `"80.00"`、`dollarExposure` `"4000.00"`；ZZB `"30.00"` / `"1200.00"`；`portfolio` `"5200.00"` / `"7.00"` / `"-10.50"`。
- `X-Account-Id: 2`：ZZA `optionDelta` `"30.00"`、`stockDelta` `"0.00"`、`dollarExposure` `"1500.00"`；ZZB 全部 Greek 与 `netDelta` / `dollarExposure` `null`、`exposureStatus` `"NO_DELTA"`、`expiredPendingCount` 1；`portfolio` `"1500.00"` / `"5.00"` / `"-10.00"`、`expiredPendingCount` 1、`excludedLabel` `"1 个持仓已到期待处理，未计入"`。

验收 7（`ALL`，ZZA 标的价手动设为 52.00）：ZZA 组 `"underlyingPrice": "52.00"`、`"underlyingPriceManual": true`、`"dollarExposure": "5720.00"`、`"manualPriceNote": "按手动标的价"`，Greeks 与计数不变；`portfolio.dollarExposure` `"6920.00"`。清除后回到上面的响应。

验收 8（甲 BTC P1 后，`ALL`）：ZZA 组 `"optionDelta": "30.00"`、`"netDelta": "130.00"`、`"gamma": "-6.00"`、`"theta": "5.00"`、`"vega": "-10.00"`、`"dollarExposure": "6500.00"`、`"optionRowCount": 1`、`"includedCount": 1`；`portfolio` `"7700.00"` / `"9.00"` / `"-14.50"`。

没有任何账户有持仓：`"groups": []`，`portfolio` 三个数值 `"0.00"`、计数全 0、Label 全 `null`（同 FEAT-019）。

---

#### 错误

与单账户完全相同（FEAT-019 / FEAT-023：`ACCOUNT_REQUIRED`、`BAD_REQUEST`、`ACCOUNT_NOT_FOUND`、`METHOD_NOT_ALLOWED`），本需求没有新错误码。两个接口带 `ALL` 不再返回 `ALL_ACCOUNTS_NOT_SUPPORTED`。FEAT-035 的其它规则不变：

| 情况 | HTTP | code |
|------|------|------|
| `GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}`、`GET /api/audit-log` 带 `ALL` | 400 | `ALL_ACCOUNTS_NOT_SUPPORTED` |
| 任何要求账户的写请求带 `ALL`（如 `POST /api/option-trades`、`POST /api/option-trades/closes`） | 400 | `ALL_ACCOUNTS_READ_ONLY` |

行情手动覆盖 `PUT` / `DELETE /api/market-data/overrides/**` 不要求账户，带 `ALL` 照常成功（Q218，验收 7 在全部账户下设置手动标的价即走此路径）。

---

#### 验收标准的数字从哪里来

| 验收 | 页面上的数字 | 来源 |
|------|------------|------|
| 1 | 4 张卡片的顺序、标题、`期权 N 个持仓 · 股票 N 股` | `GET /api/positions/underlying-summary`（`ALL`）`groups[]` 顺序、`accountName`、`symbol`、`optionPositionCount`、`hasStockPosition`；股数来自 `GET /api/stock-positions`（`ALL`）中同 `accountId + symbol` 行的 `shares`；卡片内行来自 `option-positions` / `stock-positions`（`ALL`）按 `accountId + symbol` 分组 |
| 2 | 每张卡片摘要；乙 ZZA 45P 行；切到甲 | 同上 `realized` / `unrealizedPnl` / `totalPnl` / `marketValue` / `netPremium` / `excludedLabel`；45P 行 `option-positions`（`ALL`）的 `direction`、`contracts`、`openingNetTotal`、`marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`；切到甲后 `X-Account-Id: 1` |
| 3 | 合计行 `+4,610.00` / `+245.35`、ZZA `2 笔 +119.70` | `GET /api/positions/unrealized-summary`（`ALL`）、`GET /api/pnl-summary`（`ALL`）`bySymbol`（FEAT-035） |
| 4 | 合约链接 → 只有 P3；`+39.70` → 甲账户已平仓只有 H2 | `GET /api/option-trades?symbol=ZZA&expirationDate=2026-12-18&strike=45&optionType=PUT`（`ALL`）；切换后 `GET /api/closed-trades?symbol=ZZA`（`X-Account-Id: 1`） |
| 5 | Greeks 两行 | `GET /api/positions/greeks-summary`（`ALL`）`groups[]` |
| 6 | 全组合；切到甲 / 乙 | 同上 `portfolio`；`X-Account-Id: 1` / `2` |
| 7 | 手动标的价 52.00 | `PUT /api/market-data/overrides/...`（FEAT-016）后重读 `greeks-summary`（`ALL`）；卡片行与摘要不变（标的价不影响期权行市值） |
| 8 | 写操作后的卡片与 Greeks | 甲账户 `POST` 平仓（`X-Account-Id: 1`）后重读两接口（`ALL`） |
| 9 | 开关 / 无持仓 | 纯前端；无持仓时两接口 `groups: []` |
| 10 | 后端 | 两接口（`ALL`）数字同上、摘要每组带账户字段、不再 `ALL_ACCOUNTS_NOT_SUPPORTED`；新增空账户后不变；只有一个账户时 = 该账户；写请求带 `ALL` → `ALL_ACCOUNTS_READ_ONLY` |

**核对（按现有计算规则重算，全部对得上）**：
- 行 Greeks 持仓值（每张原值 × 张数 × 100，空头取反，FEAT-018）：P1 空 1：Delta −20.00、Gamma −4.00、Theta +3.00、Vega −6.00；P3 空 2：+30.00 / −6.00 / +5.00 / −10.00；P2 空 1：+30.00 / −5.00 / +4.00 / −4.50；P4 已到期待处理不计入。
- ZZA：期权 Delta −20 + 30 = +10.00，股票 +100.00，净 +110.00 × 50.00 = 5,500.00；Gamma −10.00、Theta +8.00、Vega −16.00。ZZB：+30.00 × 40.00 = 1,200.00；−5.00 / +4.00 / −4.50。全组合 6,700.00 / 12.00 / −20.50。
- 甲：ZZA 80 × 50 = 4,000.00；全组合 5,200.00 / 3 + 4 = 7.00 / −6 − 4.5 = −10.50。乙：ZZA 30 × 50 = 1,500.00；ZZB `NO_DELTA`；全组合 1,500.00 / 5.00 / −10.00。
- 手动 52.00：110 × 52 = 5,720.00；全组合 5,720 + 1,200 = 6,920.00。
- 甲 BTC P1（60.65）：已实现 85.35 − 60.65 = 24.70，ZZA 已实现 39.70 + 24.70 = 64.40；未实现只剩股票 5,000 − 4,800 = 200.00；合计 264.40；市值 5,000.00；净权利金 `null`。Greeks ZZA 期权 +30.00、净 130.00 × 50 = 6,500.00、Gamma −6.00、Theta 5.00、Vega −10.00；全组合 7,700.00 / 9.00 / −14.50。
- 卡片：甲 ZZA 未实现 P1 85.35 − 60.00 = 25.35 + 股票 200.00 = 225.35，市值 −60 + 5,000 = 4,940.00，合计 39.70 + 225.35 = 265.05；乙 ZZA P3 230.00 − 220.00 = 10.00，`10.00 / 230.00 = 4.347…` → `4.35`%；甲 ZZB 120.00 − 110.00 = 10.00；乙 ZZB 全部排除 → `null`，合计 = 已实现 0.00。总市值 4,940 − 220 − 110 = 4,610.00；总未实现 225.35 + 10 + 10 = 245.35。

---

#### 实现说明（backend）
- `GreeksSummaryController`、`UnderlyingSummaryController` 参数改为 `AccountScope`（接受 `ALL` 的 GET 变为 13 个）；`AccountRefArgumentResolver` 不改。
- **Greeks**：`UnrealizedValuationService.greeksSummary(List<Long> accountIds)`：`options.currentPositions(accountIds)`（一次 `Clock`）、各账户股票持仓、一次 `quotes.book()`；把全部账户的期权行（每行仍用同一个静态 `greeks(...)`）与"按标的累加的持股数"一起交给现有 domain `GreeksSummary`——即 Q233 A 的取整方式，domain 不改。单账户调用 `List.of(id)`，结果不变。
- **按标的摘要**：`UnderlyingSummaryService.summary(List<Member>)`：一次 `Clock` / `QuoteBook`，对每个账户走现有单账户计算（已实现取该账户 `pnl-summary` 不带日期的 `bySymbol`），每组附 `accountId` / `accountName`，拼接后按 `symbol` → `accountId` 稳定排序（`AccountRows`）。
- **测试**：每条验收至少一个测试（场景 G：数字、排序、账户字段、单账户不变、手动标的价、写后重读、空账户、只有一个账户、写请求带 `ALL` 被拒）；另测 Q233 A 的取整差（合并 `14558.82` vs 各账户之和 `14558.81`）、乙 ZZB 单账户 `NO_DELTA` 而 `ALL` 下 `OK`、一致性（摘要各组之和 = `unrealized-summary`（`ALL`）、同标的 `realized` 之和 = `pnl-summary`（`ALL`）`bySymbol`）。

#### 已知限制
- Greeks 合并行不显示各账户构成（Q234 A）；合并后股票 Delta 与另一账户的 call 相抵不代表被覆盖（覆盖检查仍只看同一账户，FEAT-039）。
- 合并美元敞口与各账户敞口之和可能差几分（Q233 A）。
- 摘要没有跨账户合并的同标的卡片（Q224 A）；整体按标的盈亏看盈亏汇总（`ALL`）。
