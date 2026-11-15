# FEAT-023 按标的盈亏（只读，实时计算，不存库）

> **FEAT-035 修订**：~~`X-Account-Id: ALL` → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`（待 PM 确认，见 [FEAT-035.md](FEAT-035.md) 需求问题 1）。~~
>
> **FEAT-047 修订**：接受 `X-Account-Id: ALL`：每个"账户 + 标的"一组（Q224 A），每组数字与切到该账户时逐字相同（`realized` 为该账户自己的 `pnl-summary` `bySymbol` 行）；`groups[]` 新增 `accountId`、`accountName`（**单账户请求也有值**，就是该账户，纯新增）；排序 `symbol` → `accountId`。`ALL` 下卡片"已实现"链接先切到该组账户再打开已平仓（Q235 B，纯前端）。见 [FEAT-047.md](FEAT-047.md)。

口径全部已定：Q195–Q203（以及引用的 Q6 / Q8 / Q21 / Q53 / Q64 / Q72 / Q91 / Q97 / Q111 / Q152–Q162 / Q183–Q189 / Q193）。已实现沿用 [FEAT-008.md](FEAT-008.md) + [FEAT-040.md](FEAT-040.md) 的 `GET /api/pnl-summary`，行上市值 / 未实现沿用 [FEAT-017.md](FEAT-017.md)，卡片集合与顺序沿用 [FEAT-021.md](FEAT-021.md)。示例标的均为虚构（QRTV、IXQZ、ZWLK、QFZ），数字与需求验收一致（今天 2026-11-23 星期一，10:00 拉取成功，multiplier 100）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/positions/underlying-summary` | 新接口（按账户，要求 `X-Account-Id`） | — |
| 其它所有接口（含 `GET /api/option-positions`、`GET /api/stock-positions`、`GET /api/positions/unrealized-summary`、`GET /api/positions/greeks-summary`、`GET /api/pnl-summary`、`GET /api/closed-trades`、`/api/market-data/**`、测试支持） | — | — |

没有写操作、没有数据库迁移、没有新的测试支持接口、没有新的错误码：每次请求按当时的全部历史（已实现）、当前持仓和 FEAT-016 显示的 mark / 现价实时计算。

---

## 设计：为什么新增一个接口

- **不扩展 `GET /api/pnl-summary`**：它按日期范围、包含只有历史的标的（如 QFZ），而卡片只要有持仓的标的、只要全部时间；把未实现 / 净权利金放进去会让"带日期的汇总"里出现与日期无关的数字。
- **不扩展两个持仓接口**：卡片数字横跨期权和股票，放进任一个都不对称（理由同 FEAT-017 `unrealized-summary`）。
- **不扩展 `unrealized-summary` / `greeks-summary`**：前者是账户合计（没有按标的），后者是 Greeks（Q202 卡片不显示，且受持仓页"显示 Greeks"开关控制）；混入已实现会让两个接口的语义变模糊。
- 新接口与 FEAT-017 `unrealized-summary`、FEAT-019 `greeks-summary` 同一模式：放在 `/api/positions/` 下、按账户、无参数、不受到期过滤、后端拼好提示文字；内部**复用同一套计算代码**（行估值用 FEAT-017 的 `RowValuation`，已实现用 FEAT-040 盈亏汇总的按标的分组），因此一致性（规则 2、8）由构造保证，前端不做任何相加。
- 行上的市值 / 未实现（规则 3）**不在本接口里**：卡片的期权行、股票行继续用 `GET /api/option-positions` / `GET /api/stock-positions` 每行已有的 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel`（FEAT-017，FEAT-021 当时列为"不使用"，本需求起使用）。

---

## `GET /api/positions/underlying-summary`（新）

当前账户（`X-Account-Id`）每个有持仓的标的一组摘要。无参数、无请求体、不分页。

### 概念

- **分组集合**（规则 1，Q196）：账户**全部**当前持仓中出现的标的 = `GET /api/option-positions` 的 `positions[].symbol`（含已过期未处理、无报价的行）与 `GET /api/stock-positions` 的 `positions[].symbol` 的并集——与 FEAT-021 卡片集合完全相同（同一组 service 方法）。只有历史记录的标的（如 QFZ）不出现。不受持仓页到期过滤影响（没有过滤参数，Q158）。没有任何持仓 → `groups: []`（页面 `暂无持仓`）。
- **顺序**（Q203）：`symbol` 按字符编码升序（标的已规范化为大写，即字母升序），与 FEAT-021、`pnl-summary.bySymbol` 相同。
- **已实现 `realized`**（规则 2，Q195）：`GET /api/pnl-summary`（不带 `startDate` / `endDate`）中该标的那一行的 `closedCount`、`realizedPnl`、`option`、`stock`，**同一段代码**取出，数值逐字相同。包括期权 BTC / STC、作废 `EXP`、被指派 / 行权的 0.00 记录（Q8 / Q97 / Q111，计 1 笔）、股票卖出（含指派 / 行权交出股票，FEAT-040 / 041 / 014 已定的口径）。该标的没有任何记录 → `{ "closedCount": 0, "realizedPnl": "0.00", "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }`（永不为 `null`）。页面：`realizedPnl` 总是显示数字（`0.00` 也显示）；`option` / `stock` 的 `closedCount` 为 0 时显示 `—`（同盈亏汇总页，Q72 / Q91）。
- **未实现部分**（规则 4，Q154 / Q160 / Q199）：对该标的每个期权行、股票行取 FEAT-017 的行估值（与持仓接口行内**同一个计算方法、同一次行情读取方式**）：
  - `unrealizedStatus = OK` 的行计入（`includedCount`），它的 `marketValue`、`unrealizedPnl`（已取整到分）直接相加，不再取整；
  - `NO_QUOTE` / `EXPIRED_PENDING` 的行不计入，分别计入 `noQuoteCount` / `expiredPendingCount`；
  - `includedCount = 0`（该标的全部行都不能计算）→ `marketValue`、`unrealizedPnl` 为 `null`（页面 `—`）；否则为合计（可以是 `"0.00"`）。
  - `marketValue` 带方向（期权空头为负、多头为正，股票非负），所以卡片市值可以为负（如 IXQZ `"-250.00"`）。
- **提示 `excludedLabel`**：文字规则与 FEAT-017 `unrealized-summary.excludedLabel` 完全相同，只是 N / M 取本组的计数：只有无报价 `N 个持仓无报价，未计入`；只有已到期 `M 个持仓已到期待处理，未计入`；都有 `N 个持仓无报价、M 个持仓已到期待处理，未计入`；都为 0 → `null`。前端原样灰字显示。
- **合计盈亏 `totalPnl`**（规则 5，Q200）= `realized.realizedPnl` + `unrealizedPnl`（`unrealizedPnl` 为 `null` 时按 0，即等于已实现）。永不为 `null`。
- **净权利金 `netPremium`**（规则 6，Q197）= 该标的**全部**当前期权持仓行（含已过期未处理、无报价）的 `openingNetTotal`（FEAT-003，空头 +、多头 −）之和，即卡片期权表"开仓净额"列之和；不需要报价、与 `unrealizedStatus` 无关。没有期权持仓（`optionPositionCount = 0`）→ `null`（页面 `—`）。
- **数值格式**：所有金额为字符串，scale 2，负数带 `-`，正数**不带 `+`**，0 为 `"0.00"`（无负零）。显式 `+` / `−`、千分位、正绿负红由前端加（同 FEAT-017 / 019；市值显式符号 Q162）。
- **一致性**（规则 8）：数据未变时，
  - 各组非 `null` 的 `marketValue` 之和 = `unrealized-summary.total.marketValue`；`unrealizedPnl` 同理；各组 `includedCount` / `noQuoteCount` / `expiredPendingCount` 之和 = `total` 对应计数；
  - 每组 `realized` = `pnl-summary`（不带日期）`bySymbol` 中同一 `symbol` 的行；
  - 每组 `netPremium` = `option-positions` 该标的各行 `openingNetTotal` 之和。

### 响应 `200 OK`（验收 1：账户 A）
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    {
      "symbol": "IXQZ",
      "realized": {
        "closedCount": 1,
        "realizedPnl": "80.00",
        "option": { "closedCount": 1, "realizedPnl": "80.00" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": "-250.00",
      "unrealizedPnl": "-50.00",
      "totalPnl": "30.00",
      "netPremium": "290.00",
      "optionPositionCount": 2,
      "hasStockPosition": false,
      "includedCount": 1,
      "noQuoteCount": 0,
      "expiredPendingCount": 1,
      "excludedLabel": "1 个持仓已到期待处理，未计入"
    },
    {
      "symbol": "QRTV",
      "realized": {
        "closedCount": 1,
        "realizedPnl": "39.70",
        "option": { "closedCount": 1, "realizedPnl": "39.70" },
        "stock": { "closedCount": 0, "realizedPnl": "0.00" }
      },
      "marketValue": "4860.00",
      "unrealizedPnl": "225.05",
      "totalPnl": "264.75",
      "netPremium": "165.05",
      "optionPositionCount": 3,
      "hasStockPosition": true,
      "includedCount": 4,
      "noQuoteCount": 0,
      "expiredPendingCount": 0,
      "excludedLabel": null
    },
    {
      "symbol": "ZWLK",
      "realized": {
        "closedCount": 1,
        "realizedPnl": "100.00",
        "option": { "closedCount": 0, "realizedPnl": "0.00" },
        "stock": { "closedCount": 1, "realizedPnl": "100.00" }
      },
      "marketValue": null,
      "unrealizedPnl": null,
      "totalPnl": "100.00",
      "netPremium": null,
      "optionPositionCount": 0,
      "hasStockPosition": true,
      "includedCount": 0,
      "noQuoteCount": 1,
      "expiredPendingCount": 0,
      "excludedLabel": "1 个持仓无报价，未计入"
    }
  ]
}
```

### 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `asOfDate` | string | 美东今天，判断 `EXPIRED_PENDING` 用的日期（与 `option-positions.asOfDate` 同义） |
| `groups[]` | array | 每个有持仓的标的一组，按 `symbol` 升序；没有持仓为 `[]` |
| `groups[].symbol` | string | 标的（大写）。前端按它与两个持仓接口的分组对应 |
| `groups[].realized` | object | 已实现，结构同 `pnl-summary.bySymbol[]` 的一行（去掉 `symbol`）：`closedCount`（number）、`realizedPnl`（string）、`option` / `stock`（`{ closedCount, realizedPnl }`）。恒有 `option + stock = 合计`（笔数和金额）。永不为 `null` |
| `groups[].marketValue` | string \| null | 本组 `OK` 行市值之和，带方向；`includedCount = 0` 时 `null` |
| `groups[].unrealizedPnl` | string \| null | 本组 `OK` 行未实现盈亏之和；`includedCount = 0` 时 `null` |
| `groups[].totalPnl` | string | `realized.realizedPnl + (unrealizedPnl ?? 0)`；永不为 `null` |
| `groups[].netPremium` | string \| null | 本组全部期权持仓行 `openingNetTotal` 之和；`optionPositionCount = 0` 时 `null` |
| `groups[].optionPositionCount` | number | 本组期权持仓行数（含已过期未处理、无报价），= FEAT-021 标题 `期权 N 个持仓` 的 N |
| `groups[].hasStockPosition` | boolean | 本组是否有股票持仓（持股 > 0） |
| `groups[].includedCount` | number | 计入市值 / 未实现的行数（期权 + 股票） |
| `groups[].noQuoteCount` | number | `NO_QUOTE` 行数（期权 + 股票） |
| `groups[].expiredPendingCount` | number | `EXPIRED_PENDING` 行数（只可能是期权） |
| `groups[].excludedLabel` | string \| null | 后端拼好的整句（见概念），都为 0 时 `null` |

`includedCount + noQuoteCount + expiredPendingCount = optionPositionCount + (hasStockPosition ? 1 : 0)`。

### 显示映射（给 frontend，格式化不是计算）

| 摘要项 | 字段 | `null` / 0 笔时 |
|------|------|------|
| `已实现` | `realized.realizedPnl` | 不会为 `null`；无记录显示 `0.00`（不着色） |
| 小字 `期权 … · 股票 …` | `realized.option.realizedPnl` / `realized.stock.realizedPnl` | 对应 `closedCount = 0` → `—` |
| `未实现` | `unrealizedPnl` | `null` → `—`（灰） |
| `合计` | `totalPnl` | 不会为 `null` |
| `市值` | `marketValue`（显式 `+` / `−`，Q162） | `null` → `—` |
| `净权利金` | `netPremium`（显式 `+` / `−`） | `null` → `—` |
| 摘要行下灰字 | `excludedLabel` | `null` → 不显示 |

- 点击 `已实现` 数字 → 已平仓页 `/closed?symbol=<symbol>`，不带 `type`、日期。**已存在**：前端 `closedUrlForSymbol(symbol, EMPTY_PNL_RANGE)`（`components/pnl/pnlSummarySearch.ts`，盈亏汇总页标的链接所用）即生成 `/closed?symbol=QRTV`；已平仓页据此调用 `GET /api/closed-trades?symbol=QRTV`（不带 `type` = 期权与股票两类，FEAT-040），后端无需改动。
- 卡片的集合与顺序仍按 FEAT-021 由两个持仓接口决定；摘要按 `symbol` 从本接口取。三个请求先后返回可能短暂不一致（某张卡片在本接口里暂时没有对应组），此时该卡片摘要按加载中处理，失效后重新查询即一致（与 FEAT-017 合计行相同的特性）。
- **刷新**：凡是会让持仓或盈亏汇总重新查询的时机都同时让本接口重新查询——期权 / 股票交易的录入、修改、删除，平仓、作废、批量作废、指派、行权、调整到期日，行情拉取（立即刷新 / 定时），设置 / 清除手动 mark 或现价，切换账户（query key 含账户 id）。

### 其它示例

验收 5（10:30 BTC 1 张 QRTV 2026-12-18 45P，净额 60.65，账户 A 的 QRTV 组）：
```json
{
  "symbol": "QRTV",
  "realized": { "closedCount": 2, "realizedPnl": "99.40",
                "option": { "closedCount": 2, "realizedPnl": "99.40" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
  "marketValue": "4970.00", "unrealizedPnl": "214.70", "totalPnl": "314.10", "netPremium": "44.70",
  "optionPositionCount": 2, "hasStockPosition": true,
  "includedCount": 3, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null
}
```

验收 6（IXQZ 2026-11-20 58P 作废后的 IXQZ 组）：`realized` `{ closedCount: 2, realizedPnl: "170.00", option: { 2, "170.00" }, stock: { 0, "0.00" } }`，`marketValue` `"-250.00"`，`unrealizedPnl` `"-50.00"`，`totalPnl` `"120.00"`，`netPremium` `"200.00"`，`optionPositionCount` 1，`expiredPendingCount` 0，`excludedLabel` `null`。删除该作废记录后恢复上面验收 1 的 IXQZ 组。

验收 7（ZWLK 组）：
- 手动现价 32.00：`marketValue` `"960.00"`、`unrealizedPnl` `"60.00"`、`totalPnl` `"160.00"`、`includedCount` 1、`noQuoteCount` 0、`excludedLabel` `null`；
- 再把 H7 实收改为 650.00：`realized` `{ 1, "50.00", option: { 0, "0.00" }, stock: { 1, "50.00" } }`、`totalPnl` `"110.00"`；
- 再清除手动现价：`marketValue` / `unrealizedPnl` `null`、`totalPnl` `"50.00"`、`noQuoteCount` 1、`excludedLabel` `"1 个持仓无报价，未计入"`。

验收 8（账户 B）：
```json
{
  "asOfDate": "2026-11-23",
  "groups": [
    {
      "symbol": "QRTV",
      "realized": { "closedCount": 0, "realizedPnl": "0.00",
                    "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
      "marketValue": "-110.00", "unrealizedPnl": "5.00", "totalPnl": "5.00", "netPremium": "115.00",
      "optionPositionCount": 1, "hasStockPosition": false,
      "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null
    }
  ]
}
```
没有任何持仓的账户：`{ "asOfDate": "2026-11-23", "groups": [] }`。

### 错误

| 情况 | HTTP | code |
|------|------|------|
| 没带 `X-Account-Id` | 400 | `ACCOUNT_REQUIRED`（FEAT-033） |
| `X-Account-Id` 不是整数 | 400 | `BAD_REQUEST` |
| 账户不存在 | 404 | `ACCOUNT_NOT_FOUND` |
| 非 `GET` 方法 | 405 | `METHOD_NOT_ALLOWED` |

没有本需求专有的错误码。行情缺失、过期未处理都不是错误（按行状态计数）。多余的 query 参数忽略。

---

## 验收数字来源核对（账户 A，场景见需求）

**已实现**（`realized`，= `pnl-summary` 不带日期的 `bySymbol` 行；FEAT-006 / 012 / 040 口径）

| 标的 | 记录 | 计算 | 结果 |
|------|------|------|------|
| IXQZ | H4 EXP（H3 STO 80.00） | 80.00 − 0 | 期权 1 笔 +80.00，股票 0 笔 |
| QRTV | H2 BTC（H1 STO 60.35） | 60.35 − 20.65 | 期权 1 笔 +39.70，股票 0 笔（S1 是转入，不计，Q71） |
| ZWLK | H7 卖出 20 股 | 700.00 − 1,500.00 × 20 / 50 = 700.00 − 600.00 | 期权 0 笔，股票 1 笔 +100.00 |
| QFZ | H6 BTC（H5 STO 50.00） | 50.00 − 10.65 | +39.35，**无持仓 → 不在 `groups`**，只在盈亏汇总页（验收 3） |

**行估值**（FEAT-017：期权市值 = mark × 张数 × 100，空头负；未实现 = 开仓净额 + 市值；百分比 ÷ |开仓净额| 或 ÷ 总成本，HALF_UP 2 位）

| 行 | 开仓净额 / 成本 | 价格 | 市值 | 未实现 | 百分比 |
|----|------|------|------|------|------|
| QRTV 55C 空 1 | +85.35 | 0.60 | −60.00 | +25.35 | 25.35 / 85.35 = 29.701… → +29.70% |
| QRTV 45P 空 1 | +120.35 | 1.10 | −110.00 | +10.35 | 10.35 / 120.35 = 8.600… → +8.60% |
| QRTV 40P 多 1 | −40.65 | 0.30 | +30.00 | −10.65 | −10.65 / 40.65 = −26.199… → −26.20% |
| QRTV 股票 100 | 4,800.00 | 50.00 | +5,000.00 | +200.00 | 200 / 4800 = 4.166… → +4.17% |
| IXQZ 58P 空 1（到期 11-20 < 11-23） | +90.00 | — | `已到期，待处理` | | |
| IXQZ 60P 空 1 | +200.00 | 2.50 | −250.00 | −50.00 | −25.00%（验收未列） |
| ZWLK 股票 30 | 900.00 | 无 | `无报价` | | |

**卡片**

| 标的 | 市值 | 未实现 | 合计 | 净权利金 | 提示 |
|------|------|------|------|------|------|
| IXQZ | −250.00 | −50.00 | 80.00 − 50.00 = +30.00 | 90.00 + 200.00 = +290.00 | 1 个已到期 |
| QRTV | −60 − 110 + 30 + 5,000 = +4,860.00 | 25.35 + 10.35 − 10.65 + 200 = +225.05 | 39.70 + 225.05 = +264.75 | 85.35 + 120.35 − 40.65 = +165.05 | 无 |
| ZWLK | `—` | `—` | +100.00（= 已实现） | `—`（无期权） | 1 个无报价 |

**验收 3**：持仓页合计 市值 4,860.00 − 250.00 = 4,610.00、未实现 225.05 − 50.00 = 175.05、无报价 1 + 已到期 1，与 FEAT-017 合计接口一致（规则 8）。

**验收 5**：45P 平仓已实现 120.35 − 60.65 = 59.70 → QRTV 已实现 39.70 + 59.70 = 99.40（期权 2 笔）；未实现 225.05 − 10.35 = 214.70；合计 314.10；市值 4,860.00 + 110.00 = 4,970.00；净权利金 165.05 − 120.35 = 44.70。

**验收 6**：58P 作废 +90.00 → 已实现 170.00；未实现、市值不变（−50.00 / −250.00）；合计 120.00；净权利金 200.00；`expiredPendingCount` 0 → 提示消失。

**验收 7**：30 × 32.00 = 960.00，未实现 960.00 − 900.00 = +60.00，60 / 900 = 6.666… → +6.67%；合计 100.00 + 60.00 = 160.00。实收改 650.00 → 已实现 650.00 − 600.00 = 50.00，合计 110.00（剩余成本仍为 900.00）。清除现价 → 合计 50.00。

**验收 8**：账户 B 45P 空 1，mark 1.10（行情全系统一份）→ 市值 −110.00，未实现 115.00 − 110.00 = +5.00；已实现 0 笔 `0.00`；净权利金 +115.00。

**结论：验收 1–8 中所有数字都与现有计算口径一致，没有发现错误的数字。** 验收 4 的跳转与 H2 一行 `+39.70` 一致（QRTV 没有股票卖出）；验收 9 是纯前端（本接口不返回 Greeks / 最大盈利等字段）。

---

## 实现说明（backend，实现阶段参考）

- **分层**：
  - `domain`（`com.optionmgr.valuation.domain`，纯 Java）：`UnderlyingSummary`（按标的累加 `RowValuation`、开仓净额，合入已实现 `PnlSummary.Breakdown`（FEAT-040 的同一个类型），产出 `marketValue` / `unrealizedPnl` / `totalPnl` / `netPremium` / 计数 / `excludedLabel`）。`excludedLabel` 文字与 `UnrealizedSummary` 共用同一个拼接函数。
  - `service`：`valuation.service.UnderlyingSummaryService`：读取账户期权 / 股票持仓并用 FEAT-017 `UnrealizedValuationService` 的行估值（同一个 `QuoteBook`、同一次 `Clock` 读取）；已实现调用盈亏汇总服务的按标的分组（不带日期），只取持仓标的。
  - `web`：`valuation.web.UnderlyingSummaryController`（`GET /api/positions/underlying-summary`，取 `AccountRef`）。
- **取整**：全部是已取整 scale-2 值的加减，不再取整。
- **测试**：domain 单元测试覆盖验收 1、5–8 的数字与 `null` 规则；Web 层测试覆盖错误、空账户、账户隔离、与 `unrealized-summary` / `pnl-summary` 的一致性。
