# FEAT-003 期权持仓汇总

只读：不修改任何数据，不影响现金。持仓不存库，每次请求时按 `(成交时间, 交易 id)` 重放每个合约的全部交易得出（见 FEAT-001"持仓（按时点）"）；因为所有交易都不晚于当前时间，结果就是"当前"持仓。

#### 概念
- **批次（lot）**：每一笔开仓交易（BTO / STO）是一个批次，有"原"值（原张数、原开仓净额、原冻结）和"剩余"值（剩余张数、剩余开仓净额、剩余冻结）。FEAT-004 的平仓按 FIFO 从最早的批次扣减剩余值；**本需求内没有平仓，剩余 = 原值**。剩余张数为 0 的批次不再返回。
- **持仓**：某合约净张数不为 0 时就是一个持仓（Q7：一个合约最多一个持仓、方向唯一；Q23：一个 multiplier）。由该合约剩余张数 > 0 的批次组成，批次按 FIFO 顺序（成交时间早的在前，同一时间先录入的在前）。
- **金额符号**：开仓净额按**现金方向**带符号——空头（STO）为正（实收），多头（BTO）为负（实付），与交易的 `cashChange` 一致。冻结金额为非负数。
- **今天**：服务器当前时间所在的**美东日期**（美东午夜切换，不是 UTC 午夜）。
- **到期状态**：过了到期日的持仓在 M2 处理前仍是持仓，冻结不释放（Q34）。

#### `GET /api/option-positions`
返回全部当前持仓。无参数，不分页、不筛选（Q35）。

响应 `200 OK`：
```json
{
  "asOfDate": "2026-11-11",
  "positionCount": 2,
  "frozenTotal": "40000.00",
  "positions": [
    {
      "symbol": "AAPL",
      "expirationDate": "2026-12-18",
      "strike": "200",
      "optionType": "PUT",
      "contractLabel": "AAPL 2026-12-18 200 P",
      "direction": "SHORT",
      "contracts": 2,
      "multiplier": 100,
      "openingNetTotal": "709.35",
      "averageNetPerContract": "354.68",
      "frozenCash": "40000.00",
      "dte": 37,
      "expirationStatus": "ACTIVE",
      "daysPastExpiration": 0,
      "lotCount": 2,
      "lots": [
        {
          "tradeId": 1,
          "action": "STO",
          "tradedAt": "2026-11-03T15:30:00Z",
          "tradedAtLocal": "2026-11-03T10:30:00",
          "originalContracts": 1,
          "remainingContracts": 1,
          "originalNetAmount": "349.35",
          "remainingNetAmount": "349.35",
          "originalFrozen": "20000.00",
          "remainingFrozen": "20000.00",
          "note": "第一笔 CSP"
        },
        {
          "tradeId": 4,
          "action": "STO",
          "tradedAt": "2026-11-04T16:00:00Z",
          "tradedAtLocal": "2026-11-04T11:00:00",
          "originalContracts": 1,
          "remainingContracts": 1,
          "originalNetAmount": "360.00",
          "remainingNetAmount": "360.00",
          "originalFrozen": "20000.00",
          "remainingFrozen": "20000.00",
          "note": "加仓"
        }
      ]
    },
    {
      "symbol": "AAPL",
      "expirationDate": "2026-12-18",
      "strike": "210",
      "optionType": "CALL",
      "contractLabel": "AAPL 2026-12-18 210 C",
      "direction": "LONG",
      "contracts": 1,
      "multiplier": 100,
      "openingNetTotal": "-512.65",
      "averageNetPerContract": "-512.65",
      "frozenCash": "0.00",
      "dte": 37,
      "expirationStatus": "ACTIVE",
      "daysPastExpiration": 0,
      "lotCount": 1,
      "lots": [
        {
          "tradeId": 2,
          "action": "BTO",
          "tradedAt": "2026-11-03T15:30:00Z",
          "tradedAtLocal": "2026-11-03T10:30:00",
          "originalContracts": 1,
          "remainingContracts": 1,
          "originalNetAmount": "-512.65",
          "remainingNetAmount": "-512.65",
          "originalFrozen": "0.00",
          "remainingFrozen": "0.00",
          "note": null
        }
      ]
    }
  ]
}
```

顶层字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `asOfDate` | string | "今天"的美东日期 `YYYY-MM-DD`，所有 `dte` 都相对它计算（页面显示"截至 … （美东）"） |
| `positionCount` | number | 持仓个数（= `positions` 长度） |
| `frozenTotal` | string | 所有持仓 `frozenCash` 之和，scale 2。M2 之前等于 `GET /api/cash/summary` 的 `frozenCash` |
| `positions` | array | 全部持仓，排序：`expirationDate` 升序 → `symbol` 升序（按字符编码，即字母序）→ `strike` 数值升序 → `CALL` 在 `PUT` 前。没有持仓时为 `[]` |

`positions[]` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | string | 合约。`strike` 去尾零（如 `"12.5"`）。这四个值可**原样**作为 FEAT-002 交易列表的同名 query 参数，跳转到该合约的交易 |
| `contractLabel` | string | 合约显示名，如 `"AAPL 2026-12-18 200 P"` |
| `direction` | string | `SHORT`（空头，由 STO 建立）/ `LONG`（多头，由 BTO 建立） |
| `contracts` | number | 净张数绝对值 = 各批次 `remainingContracts` 之和 |
| `multiplier` | number | 该持仓的 multiplier |
| `openingNetTotal` | string | 各批次 `remainingNetAmount` 之和，带现金方向符号（空头正，多头负），scale 2 |
| `averageNetPerContract` | string | `openingNetTotal ÷ contracts`，**四舍五入（HALF_UP）到分**，符号与合计相同（Q22）。只用于显示（如 `"354.68"`、`"-66.67"`） |
| `frozenCash` | string | 各批次 `remainingFrozen` 之和（不是按合计张数重新计算），scale 2。空头 put 以外为 `"0.00"` |
| `dte` | number | 到期日 − `asOfDate` 的日历天数（含周末、假日）。到期日当天 `0`，已过期为负数 |
| `expirationStatus` | string | `ACTIVE`（`dte` > 0）/ `EXPIRES_TODAY`（`dte` = 0，"今天到期"）/ `EXPIRED`（`dte` < 0，"已过期 N 天"） |
| `daysPastExpiration` | number | 已过期天数 N（= −`dte`）；未过期为 `0` |
| `lotCount` | number | 批次数（= `lots` 长度） |
| `lots` | array | 剩余张数 > 0 的批次，FIFO 顺序（最早的在前） |

`lots[]` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `tradeId` | number | 开仓交易 id（`GET /api/option-trades/{id}`） |
| `action` | string | `BTO` / `STO` |
| `tradedAt` | string | 成交时间，ISO-8601 UTC Instant |
| `tradedAtLocal` | string | 同一时间的美东本地时间 `YYYY-MM-DDTHH:mm:ss` |
| `originalContracts` / `remainingContracts` | number | 原张数 / 剩余张数（FEAT-004 之前相等） |
| `originalNetAmount` / `remainingNetAmount` | string | 原 / 剩余开仓净额，带现金方向符号，scale 2（FEAT-004 之前相等） |
| `originalFrozen` / `remainingFrozen` | string | 原 / 剩余冻结，scale 2；STO put 为该笔交易的担保（每笔单独向上取整到分，Q28），其它为 `"0.00"` |
| `note` | string \| null | 开仓交易的备注 |

没有持仓：`{"asOfDate": "2026-11-11", "positionCount": 0, "frozenTotal": "0.00", "positions": []}`。

错误：只有通用错误（如 `POST /api/option-positions` → 405 `METHOD_NOT_ALLOWED`）。

