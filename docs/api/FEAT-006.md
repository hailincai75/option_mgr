# FEAT-006 已实现盈亏 / 已平仓记录

> **FEAT-046 修订**：`GET /api/closed-trades` 接受 `X-Account-Id: ALL`：参数（含 `type`）、校验、错误不变；行新增 `accountId`、`accountName`；排序原规则 + `accountId` 升序，合并后分页；`filteredCount` / `filteredRealizedPnlTotal` 为全部账户之和，与 `ALL` 的盈亏汇总一致。见 [FEAT-046.md](FEAT-046.md)。

只读。每笔平仓交易（BTC / STC）一条。被平批次和已实现盈亏都在读取时按 FIFO 重放得出（FEAT-004），所以补录、修改、删除交易后，结果自动是新的匹配（Q39、FEAT-005）。

> **FEAT-040 起**：结果还包含股票卖出（`type: "STOCK"` 行），新增 `type` 筛选与行字段，排序在同一时间把股票行放在期权行之上，股票行上期权专用字段为 `null`。本节描述的是期权行（`type: "OPTION"`）；变化见文末「FEAT-040」。

#### `GET /api/closed-trades`
筛选、排序、分页。

**排序（固定）**：平仓成交时间倒序；同一时间交易 id 大（后录入）的在上。

**query 参数**：与 FEAT-002 `GET /api/option-trades` 完全相同（`symbol`、`expirationDate`、`strike`、`optionType`、`action`、`startDate`、`endDate`、`page`、`size`，规则、错误码、错误文案都相同），只有两处不同：
- `action` 只接受 `BTC` / `STC`（FEAT-012 起还有 `EXP`，多值写法同 FEAT-002）；其它值（含 `BTO`/`STO`）→ 400 `BAD_REQUEST`，`details[].field = "action"`，`message` 为 `方向只能是 BTC、STC、EXP`（FEAT-012 之前为 `方向只能是 BTC、STC`）。不传 = 全部（BTC、STC、EXP）
- `startDate` / `endDate` 筛的是**平仓**成交时间的美东日期（两端包含），与开仓日期无关

示例：
```
GET /api/closed-trades?symbol=aapl&page=1&size=20
```

响应 `200 OK`：
```json
{
  "items": [
    {
      "id": 9,
      "tradedAt": "2026-11-07T15:00:00Z",
      "tradedAtLocal": "2026-11-07T10:00:00",
      "symbol": "AAPL",
      "expirationDate": "2026-12-18",
      "strike": "210",
      "optionType": "CALL",
      "contractLabel": "AAPL 2026-12-18 210 C",
      "action": "STC",
      "closedDirection": "LONG",
      "contracts": 2,
      "multiplier": 100,
      "openingNetTotal": "-1000.00",
      "openingNetPerContract": "-500.00",
      "closeNetAmount": "640.00",
      "closeNetPerContract": "320.00",
      "realizedPnl": "-360.00",
      "note": null,
      "coveredLots": [
        {
          "tradeId": 4,
          "action": "BTO",
          "tradedAt": "2026-11-03T14:45:00Z",
          "tradedAtLocal": "2026-11-03T09:45:00",
          "contracts": 2,
          "netAmount": "-1000.00",
          "frozen": "0.00"
        }
      ],
      "hasAuditLog": false
    }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 2,
  "totalPages": 1,
  "filteredCount": 2,
  "filteredRealizedPnlTotal": "298.70"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `items[].id` | number | 平仓交易 id（`GET /api/option-trades/{id}`；审计筛选用 `objectType=OPTION_TRADE&objectId=<id>`） |
| `items[].tradedAt` / `tradedAtLocal` | string | 平仓成交时间：UTC Instant / 美东本地 `YYYY-MM-DDTHH:mm:ss` |
| `items[].symbol` / `expirationDate` / `strike` / `optionType` / `contractLabel` | string | 合约，与 FEAT-002 相同（`strike` 去尾零）；四个合约字段可原样作为 FEAT-002 的 query 参数跳转 |
| `items[].action` | string | `BTC` / `STC` |
| `items[].closedDirection` | string | 被平掉的持仓方向：BTC → `SHORT`（空头），STC → `LONG`（多头） |
| `items[].contracts` / `multiplier` | number | 平仓张数 / multiplier（≠ 100 时前端附注） |
| `items[].openingNetTotal` | string | 被平批次开仓净额之和，带现金方向符号（空头正、多头负），scale 2 |
| `items[].openingNetPerContract` | string | `openingNetTotal ÷ contracts`，**四舍五入（HALF_UP）到分**，同号（Q22），仅显示 |
| `items[].closeNetAmount` | string | 平仓净额，带现金方向符号（BTC 负、STC 正），即该交易的 `cashChange` |
| `items[].closeNetPerContract` | string | `closeNetAmount ÷ contracts`，HALF_UP 到分，同号，仅显示 |
| `items[].realizedPnl` | string | `openingNetTotal + closeNetAmount`（Q21，整笔计算，不分摊、不舍入） |
| `items[].note` | string \| null | 平仓交易的备注 |
| `items[].coveredLots` | array | 被平批次，FIFO 顺序（最早在前），字段同 FEAT-004 的 `coveredLots`（`tradeId`、`action`、`tradedAt`、`tradedAtLocal`、`contracts`、`netAmount` 带符号、`frozen`） |
| `items[].hasAuditLog` | boolean | **平仓交易本身**（`id`）被修改过时为 `true`（取平仓交易上的标记）；只修改过被平的开仓批次、或只被连带改正不算。**新增字段（兼容变更），见"列表行的 `hasAuditLog`"** |
| `page` / `size` / `totalItems` / `totalPages` | number | 同 FEAT-002；超过最后一页时 `items` 为 `[]` |
| `filteredCount` | number | 符合筛选条件的**全部**平仓笔数（= `totalItems`） |
| `filteredRealizedPnlTotal` | string | 符合筛选条件的**全部**平仓（不只是当前页）的 `realizedPnl` 之和，scale 2；没有记录时 `"0.00"`（Q46） |

没有记录：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0, "filteredCount": 0, "filteredRealizedPnlTotal": "0.00"}`。

错误：同 FEAT-002（400 `VALIDATION_ERROR` / `BAD_REQUEST`，`details[].field` = query 参数名）。

