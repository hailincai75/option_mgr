# FEAT-040 股票已实现盈亏并入已平仓、盈亏汇总、首页

只读，不新增接口、不改任何数据，**没有数据库迁移**。股票卖出的被卖出批次、被卖出成本、已实现盈亏与 FEAT-011 完全相同（读取时按 FIFO 重放，Q69、Q87），这里只是把它们并入三个已有接口。所有规则按账户（`X-Account-Id`）独立。

#### 兼容性一览

| 接口 | 新增（兼容） | **行为变化（不兼容）** |
|------|-------------|---------------------|
| `GET /api/closed-trades` | 行字段 `type`、`shares`、`costPerShare`、`pricePerShare`、`soldLots`；query 参数 `type` | ① 不带 `type` 时结果、`totalItems` / `totalPages` / `filteredCount` / `filteredRealizedPnlTotal` **包含股票卖出**（没有股票卖出的账户数字不变）；② 股票行上期权专用字段为 `null`：`expirationDate`、`strike`、`optionType`、`contractLabel`、`closedDirection`、`contracts`、`multiplier`、`openingNetPerContract`、`closeNetPerContract`、`coveredLots`（因此 `contracts`、`multiplier` 的类型从 number 变为 number \| null，`coveredLots` 从 array 变为 array \| null）；③ `action` 新增取值 `SELL`（只出现在股票行）；④ `id` 不再在行之间唯一，行的唯一标识是 **`type` + `id`** |
| `GET /api/pnl-summary` | 总计、`bySymbol[]`、`byMonth[]` 上的 `option`、`stock` 拆分（Q91） | 已有字段名不变，但 `closedCount`、`realizedPnlTotal`、每行的 `closedCount` / `realizedPnl` **包含股票卖出**；只有股票卖出的标的 / 月份也会出现 |
| `GET /api/dashboard` | `stockPositions.positionCount`（Q89） | `realizedPnl`、`currentMonthRealizedPnl` **包含股票卖出**（笔数和金额）。`positions`（期权持仓）的字段和数字都不变 |

其它接口（期权交易列表、期权持仓、股票交易列表、股票持仓、资金、审计）都不变。

#### `GET /api/closed-trades`（FEAT-006 扩展）

**范围**：两类记录（`type`）：
- `OPTION`：期权平仓 BTC / STC 和到期作废 EXP，与 FEAT-006 / FEAT-012 完全相同；
- `STOCK`：每一笔股票**卖出**（`SELL`）一条。股票买入、转入不出现，也不计入笔数（Q71）。

期权交易和股票记录各有自己的 id 序列（两类都可能有 `id: 4`），所以**一行由 `type` + `id` 唯一确定**（前端行 key、"历史"链接、跳转都要同时用两者）。

**排序（固定，Q92）**：`tradedAt` 倒序；同一时间（同一 `tradedAt`）**`STOCK` 行在 `OPTION` 行之上**；同类之间 `id` 大（后录入）的在上。期权行和股票行在**同一个排序里分页**（不是各自分页再拼接）。

**query 参数**：FEAT-006 的全部参数不变（`symbol`、`expirationDate`、`strike`、`optionType`、`action`、`startDate`、`endDate`、`page`、`size`，规则、错误码、文案不变），另加：

| 参数 | 规则 |
|------|------|
| `type`（新） | 可选，单值：`OPTION` 或 `STOCK`；不传、空串或全空白 = 两类都要。其它值（含小写、`OPTION,STOCK` 这样的多值）→ 400 `BAD_REQUEST`，`details[].field = "type"`，`message` 为 `类型只能是 OPTION 或 STOCK`（与其它无法解析的参数一起报告，规则同 FEAT-002） |

各条件对两类记录的作用（之间为"并且"）：

| 条件 | `OPTION` 行 | `STOCK` 行 |
|------|-------------|-----------|
| `type` | `OPTION` 或不传时出现 | `STOCK` 或不传时出现 |
| `symbol` | 按标的（规范化后精确匹配，Q31） | 按标的 |
| `startDate` / `endDate` | 平仓时间（EXP 为 cutoff）的美东日期，两端包含 | 卖出成交时间的美东日期，两端包含 |
| `expirationDate`、`strike`、`optionType` | 按合约 | **期权专用（Q90）**：传了其中任何一个（非空），股票行就不出现 |
| `action`（`BTC` / `STC` / `EXP`） | 按方向 | **期权专用（Q90）**：传了方向，股票行就不出现 |

- `type=STOCK` 同时带期权专用条件：不报错，按"并且"得到空结果（`items: []`，`filteredCount` 0，`filteredRealizedPnlTotal` `"0.00"`）。
- `action` 仍只接受 `BTC` / `STC` / `EXP`（`SELL` 不是可选的筛选值，传 `SELL` → 400 `方向只能是 BTC、STC、EXP`）。要只看股票请用 `type=STOCK`。
- 参数校验顺序与 FEAT-002 相同：有 `BAD_REQUEST` 问题时先报它（含 `type`），不再报 `VALIDATION_ERROR`。

**分页与合计**：`page` / `size` 规则不变。`totalItems` = `filteredCount` = 符合条件的**全部**行数（期权 + 股票，一笔股票卖出算 1 笔）；`filteredRealizedPnlTotal` = 全部这些行的 `realizedPnl` 之和（Q46）。

示例（FEAT-040 场景 M，从空库录入：期权交易 O1–O4 的 id 为 1–4，股票记录 S1–S7 的 id 为 1–7；`size=3`）：
```
GET /api/closed-trades?page=1&size=3
```
响应 `200 OK`：
```json
{
  "items": [
    {
      "type": "OPTION",
      "id": 4,
      "tradedAt": "2026-11-09T15:00:00Z",
      "tradedAtLocal": "2026-11-09T10:00:00",
      "symbol": "XYZ",
      "expirationDate": "2026-12-18",
      "strike": "3",
      "optionType": "CALL",
      "contractLabel": "XYZ 2026-12-18 3 C",
      "action": "BTC",
      "closedDirection": "SHORT",
      "contracts": 1,
      "multiplier": 100,
      "shares": null,
      "openingNetTotal": "25.00",
      "openingNetPerContract": "25.00",
      "costPerShare": null,
      "closeNetAmount": "-40.00",
      "closeNetPerContract": "-40.00",
      "pricePerShare": null,
      "realizedPnl": "-15.00",
      "note": null,
      "coveredLots": [
        {
          "tradeId": 3,
          "action": "STO",
          "tradedAt": "2026-11-04T16:00:00Z",
          "tradedAtLocal": "2026-11-04T11:00:00",
          "contracts": 1,
          "netAmount": "25.00",
          "frozen": "0.00"
        }
      ],
      "soldLots": null,
      "hasAuditLog": false
    },
    {
      "type": "STOCK",
      "id": 7,
      "tradedAt": "2026-11-06T15:00:00Z",
      "tradedAtLocal": "2026-11-06T10:00:00",
      "symbol": "XYZ",
      "expirationDate": null,
      "strike": null,
      "optionType": null,
      "contractLabel": null,
      "action": "SELL",
      "closedDirection": null,
      "contracts": null,
      "multiplier": null,
      "shares": 100,
      "openingNetTotal": "-333.33",
      "openingNetPerContract": null,
      "costPerShare": "3.3333",
      "closeNetAmount": "400.00",
      "closeNetPerContract": null,
      "pricePerShare": "4.0000",
      "realizedPnl": "66.67",
      "note": null,
      "coveredLots": null,
      "soldLots": [
        {
          "tradeId": 5,
          "type": "BUY",
          "tradedAt": "2026-11-04T15:00:00Z",
          "tradedAtLocal": "2026-11-04T10:00:00",
          "tradeDate": null,
          "shares": 100,
          "netAmount": "-333.33"
        }
      ],
      "hasAuditLog": false
    },
    {
      "type": "STOCK",
      "id": 6,
      "tradedAt": "2026-11-05T15:00:00Z",
      "tradedAtLocal": "2026-11-05T10:00:00",
      "symbol": "IXQZ",
      "expirationDate": null,
      "strike": null,
      "optionType": null,
      "contractLabel": null,
      "action": "SELL",
      "closedDirection": null,
      "contracts": null,
      "multiplier": null,
      "shares": 150,
      "openingNetTotal": "-1125.16",
      "openingNetPerContract": null,
      "costPerShare": "7.5011",
      "closeNetAmount": "1200.00",
      "closeNetPerContract": null,
      "pricePerShare": "8.0000",
      "realizedPnl": "74.84",
      "note": null,
      "coveredLots": null,
      "soldLots": [
        {
          "tradeId": 1,
          "type": "TRANSFER_IN",
          "tradedAt": "2026-10-01T04:00:00Z",
          "tradedAtLocal": "2026-10-01T00:00:00",
          "tradeDate": "2026-10-01",
          "shares": 100,
          "netAmount": "-750.00"
        },
        {
          "tradeId": 4,
          "type": "BUY",
          "tradedAt": "2026-11-03T15:00:00Z",
          "tradedAtLocal": "2026-11-03T10:00:00",
          "tradeDate": null,
          "shares": 50,
          "netAmount": "-375.16"
        }
      ],
      "hasAuditLog": false
    }
  ],
  "page": 1,
  "size": 3,
  "totalItems": 5,
  "totalPages": 2,
  "filteredCount": 5,
  "filteredRealizedPnlTotal": "141.51"
}
```

字段（`OPTION` 行与 FEAT-006 / FEAT-012 完全相同，只多出下表中的新字段，且新字段在期权行上为 `null`）：

| 字段 | `OPTION` 行 | `STOCK` 行 |
|------|-------------|-----------|
| `type`（新） | `"OPTION"` | `"STOCK"` |
| `id` | 期权交易 id（`GET /api/option-trades/{id}`；审计 `objectType=OPTION_TRADE&objectId=<id>`） | 股票记录 id（`GET /api/stock-trades/{id}`；审计 `objectType=STOCK_TRADE&objectId=<id>`） |
| `tradedAt` / `tradedAtLocal` | 平仓时间（EXP 为 cutoff） | 卖出成交时间（UTC / 美东本地） |
| `symbol` | 标的 | 标的（可原样作为 `GET /api/stock-trades?symbol=`） |
| `expirationDate` / `strike` / `optionType` / `contractLabel` | 合约 | `null` |
| `action` | `BTC` / `STC` / `EXP` | `"SELL"` |
| `closedDirection` | `SHORT` / `LONG` | `null`（股票没有空头 / 多头） |
| `contracts` / `multiplier` | 张数 / multiplier | `null` |
| `shares`（新） | `null` | 卖出股数（JSON 整数） |
| `openingNetTotal` | 被平批次开仓净额之和（带符号） | **−被卖出成本**（= −FEAT-011 的 `soldCost`），scale 2，为负（与多头期权"开仓为实付、为负"一致；成本为 0 的批次不会出现，所以总是 < 0） |
| `openingNetPerContract` | 每张平均，HALF_UP 到分 | `null` |
| `costPerShare`（新） | `null` | 每股成本 = 被卖出成本 ÷ 股数，HALF_UP **4 位小数**（Q88），**正数**，仅显示。例：1125.16 ÷ 150 → `"7.5011"` |
| `closeNetAmount` | 平仓净额（带符号） | **+卖出实收**（= 股票记录的 `cashChange`），scale 2 |
| `closeNetPerContract` | 每张平均，HALF_UP 到分 | `null` |
| `pricePerShare`（新） | `null` | 每股价格 = 卖出实收 ÷ 股数，HALF_UP 4 位小数，正数（= 股票记录的 `pricePerShare`） |
| `realizedPnl` | `openingNetTotal + closeNetAmount` | 同一公式 = 卖出实收 − 被卖出成本（= 股票记录的 `realizedPnl`），scale 2 |
| `note` | 平仓交易的备注 | 卖出记录的备注 |
| `coveredLots` | 被平批次（FEAT-006） | `null` |
| `soldLots`（新） | `null` | 被卖出批次，FIFO 顺序（最早在前），与 FEAT-011 股票记录的 `soldLots` 一一对应，但金额字段是**带现金方向符号**的 `netAmount`（= −FEAT-011 的 `cost`，为负）：`tradeId`（来源记录 id）、`type`（`BUY` / `TRANSFER_IN`）、`tradedAt` / `tradedAtLocal`（转入为该日美东 00:00）、`tradeDate`（转入的日期，否则 `null`）、`shares`（扣除股数）、`netAmount`（scale 2）。各批次 `netAmount` 之和 = `openingNetTotal` |
| `hasAuditLog` | 平仓交易本身被修改过 | **卖出记录本身**被修改过（股票记录上的标记）；只修改过它用到的买入 / 转入不算 |

没有记录：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0, "filteredCount": 0, "filteredRealizedPnlTotal": "0.00"}`（不变）。

错误示例（400，类型不合法）：
```json
{
  "code": "BAD_REQUEST",
  "message": "Invalid parameter value",
  "status": 400,
  "path": "/api/closed-trades",
  "timestamp": "2026-11-13T15:00:00Z",
  "details": [ { "field": "type", "message": "类型只能是 OPTION 或 STOCK" } ]
}
```

#### `GET /api/pnl-summary`（FEAT-008 扩展）

**范围**：期权平仓、到期作废（不变）+ 股票卖出。股票卖出归属于**卖出成交时间的美东日期**及该日期所在的美东自然月（例：美东 2026-10-31 23:30 卖出 = `2026-11-01T03:30:00Z`，归 2026-10）；`startDate` / `endDate` 同样按此筛选。参数、校验、错误都不变。

**拆分（Q91，新增字段）**：总计、`bySymbol[]` 每行、`byMonth[]` 每行都多两个对象 `option`、`stock`，结构 `{ "closedCount": number, "realizedPnl": string }`：
- `option` = 该范围 / 该行中 `OPTION` 类记录（BTC、STC、EXP）的笔数与已实现盈亏合计；
- `stock` = `STOCK` 类记录（股票卖出）的笔数与合计；
- 某类 0 笔时为 `{ "closedCount": 0, "realizedPnl": "0.00" }`（不省略、不为 `null`；页面据 `closedCount` 为 0 显示 `—`）。
- 恒有 `option.closedCount + stock.closedCount = closedCount`、`option.realizedPnl + stock.realizedPnl = realizedPnl`（总计为 `realizedPnlTotal`）。

**分组**：同一标的的期权和股票**合成一行**（`symbol` 相同即同一行）；只有股票卖出的标的、月份也出现。排序不变（标的字符序升序；月份倒序，没有任何记录的月份不出现）。各组的 `closedCount` / `realizedPnl` / `option` / `stock` 分别加起来等于总计对应的数；同一日期范围下 `closedCount` / `realizedPnlTotal` = `GET /api/closed-trades`（不带 `type`）的 `filteredCount` / `filteredRealizedPnlTotal`。

示例（场景 M，全部时间）：
```json
{
  "startDate": null,
  "endDate": null,
  "closedCount": 5,
  "realizedPnlTotal": "141.51",
  "option": { "closedCount": 2, "realizedPnl": "20.00" },
  "stock": { "closedCount": 3, "realizedPnl": "121.51" },
  "bySymbol": [
    { "symbol": "IXQZ", "closedCount": 2, "realizedPnl": "109.84",
      "option": { "closedCount": 1, "realizedPnl": "35.00" }, "stock": { "closedCount": 1, "realizedPnl": "74.84" } },
    { "symbol": "QRTV", "closedCount": 1, "realizedPnl": "-20.00",
      "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 1, "realizedPnl": "-20.00" } },
    { "symbol": "XYZ", "closedCount": 2, "realizedPnl": "51.67",
      "option": { "closedCount": 1, "realizedPnl": "-15.00" }, "stock": { "closedCount": 1, "realizedPnl": "66.67" } }
  ],
  "byMonth": [
    { "month": "2026-11", "closedCount": 3, "realizedPnl": "126.51",
      "option": { "closedCount": 1, "realizedPnl": "-15.00" }, "stock": { "closedCount": 2, "realizedPnl": "141.51" } },
    { "month": "2026-10", "closedCount": 2, "realizedPnl": "15.00",
      "option": { "closedCount": 1, "realizedPnl": "35.00" }, "stock": { "closedCount": 1, "realizedPnl": "-20.00" } }
  ]
}
```

范围内没有记录：`closedCount` 0、`realizedPnlTotal` `"0.00"`、`option` / `stock` 都是 `{ "closedCount": 0, "realizedPnl": "0.00" }`，`bySymbol`、`byMonth` 都是 `[]`。

#### `GET /api/dashboard`（FEAT-008 扩展）

- `realizedPnl`、`currentMonthRealizedPnl`：**包含股票卖出**（笔数和金额；本月按卖出的美东日期）。只给合计，不给拆分（首页不显示拆分，Q91）。结构不变。
- `positions`：期权持仓，字段、口径、数字都**不变**（只数期权；页面文字"期权持仓 N 个"是前端的事，Q93）。
- `stockPositions`（新，Q89）：`{ "positionCount": number }` = 当前持股 > 0 的标的数，等于 `GET /api/stock-positions` 的 `positionCount`。

示例（场景 M + O5，今天 2026-11-13）：
```json
{
  "asOfDate": "2026-11-13",
  "currentMonth": "2026-11",
  "cash": { "totalCash": "19529.35", "frozenCash": "0.00", "availableCash": "19529.35" },
  "realizedPnl": { "closedCount": 5, "total": "141.51" },
  "currentMonthRealizedPnl": { "closedCount": 3, "total": "126.51" },
  "positions": {
    "positionCount": 1,
    "frozenTotal": "0.00",
    "expiringWithin7DaysCount": 0,
    "expiredUnprocessedCount": 0
  },
  "stockPositions": { "positionCount": 2 }
}
```

错误：只有通用错误（不变）。

#### 重新计算
补录、修改、删除任何股票记录或期权交易后，三个接口按当时的全部历史重放给出新数字（读取时计算，不落库）。例如修改转入的总成本会改变之后用到它的卖出的 `openingNetTotal`、`costPerShare`、`realizedPnl`、`soldLots`，以及合计、汇总和首页。

