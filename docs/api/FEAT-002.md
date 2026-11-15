# FEAT-002 交易列表与筛选

> **FEAT-046 修订**：`GET /api/option-trades` 接受 `X-Account-Id: ALL`（全部账户，只读）：参数、校验、错误不变；排序 `tradedAt` 倒序 → `id` 倒序 → `accountId` 升序；对合并后的整个列表分页。每项（以及所有返回期权交易表示的接口）新增 `accountId`、`accountName`（单账户也有值）。`GET /api/option-trades/{id}` 仍不接受 `ALL`。见 [FEAT-046.md](FEAT-046.md)。

只读：不新增、不修改任何数据，不影响现金汇总和流水。列出所有期权交易（不论方向；FEAT-004 之后的 BTC / STC 也会自动出现，不需要改接口）。

#### `GET /api/option-trades`
按筛选条件过滤、按固定顺序排序、分页。

**排序（固定）**：成交时间 `tradedAt` 倒序；成交时间相同时，后录入的（交易 `id` 大的）在上。

**query 参数**（全部可选；不传、空串或全空白 = 不按该条件筛选；多个条件之间是"并且"）：

| 参数 | 类型 | 规则 |
|------|------|------|
| `symbol` | string | 先去首尾空白、转大写，再与交易的标的**精确**相等（`aapl` 查到 `AAPL`；`AAP` 查不到 `AAPL`）。规范化后须满足 FEAT-001 的标的格式（1–10 位字母/数字/点），否则 `VALIDATION_ERROR` |
| `expirationDate` | string | `YYYY-MM-DD`，与交易的到期日相等。格式不对或日期不存在（如 `2026-02-30`）→ `BAD_REQUEST` |
| `strike` | string | 按**数值**与交易的行权价相等（`200`、`200.0`、`200.000` 等价）。不是数字 → `BAD_REQUEST`；≤ 0、超过 3 位小数、> `9999999.999` → `VALIDATION_ERROR` |
| `optionType` | string | `CALL` 或 `PUT`（区分大小写，与 FEAT-001 一致）。其它值 → `BAD_REQUEST` |
| `action` | string，可多值 | `BTO` / `STO` / `BTC` / `STC` / `EXP`（到期作废，FEAT-012 起），选中的方向之间是"或者"。多值写法：重复参数 `action=BTO&action=STO`，或逗号分隔 `action=BTO,STO`（两种可混用；空项忽略）。`BTC` / `STC` 现在就合法（FEAT-004 之前结果为空，不报错）。不认识的值 → `BAD_REQUEST` |
| `startDate` | string | `YYYY-MM-DD`，**美东日期**。成交时间换算成美东日期后 ≥ `startDate` 的交易。格式不对 → `BAD_REQUEST` |
| `endDate` | string | `YYYY-MM-DD`，**美东日期**。成交时间换算成美东日期后 ≤ `endDate` 的交易（包含当天整天，直到美东 23:59:59.999…）。格式不对 → `BAD_REQUEST`。两端都填且 `startDate` 晚于 `endDate` → `VALIDATION_ERROR`，`field` 为 **`endDate`** |
| `page` | number | 默认 `1`，≥ 1；超出最后一页时 `items` 为空数组（不是错误） |
| `size` | number | 默认 `20`，1–100 |

日期范围可以只填一端，也可以包含未来日期（不报错）。美东日期举例：美东 2026-11-03 23:30（= `2026-11-04T04:30:00Z`）属于 2026-11-03；夏令时美东 2026-07-15 21:00（= `2026-07-16T01:00:00Z`）属于 2026-07-15。实现上 `startDate` 换算为该美东日期 00:00 的时间点（含），`endDate` 换算为次日美东 00:00 的时间点（不含），夏令时切换当天（23 / 25 小时）同样正确。

示例：
```
GET /api/option-trades?symbol=aapl&action=STO&startDate=2026-11-03&endDate=2026-11-04&page=1&size=20
```

响应 `200 OK`：
```json
{
  "items": [
    {
      "id": 4,
      "symbol": "AAPL",
      "expirationDate": "2026-12-18",
      "strike": "200",
      "optionType": "PUT",
      "action": "STO",
      "contracts": 1,
      "multiplier": 100,
      "netAmount": "360.00",
      "tradedAt": "2026-11-04T16:00:00Z",
      "tradedAtLocal": "2026-11-04T11:00:00",
      "cashChange": "360.00",
      "frozenChange": "20000.00",
      "contractLabel": "AAPL 2026-12-18 200 P",
      "note": "加仓",
      "ledgerEntryId": 5,
      "createdAt": "2026-11-05T15:00:00Z",
      "coveredLots": null,
      "realizedPnl": null,
      "hasAuditLog": false
    },
    {
      "id": 1,
      "symbol": "AAPL",
      "expirationDate": "2026-12-18",
      "strike": "200",
      "optionType": "PUT",
      "action": "STO",
      "contracts": 1,
      "multiplier": 100,
      "netAmount": "349.35",
      "tradedAt": "2026-11-03T15:30:00Z",
      "tradedAtLocal": "2026-11-03T10:30:00",
      "cashChange": "349.35",
      "frozenChange": "20000.00",
      "contractLabel": "AAPL 2026-12-18 200 P",
      "note": "第一笔 CSP",
      "ledgerEntryId": 2,
      "createdAt": "2026-11-05T15:00:00Z",
      "coveredLots": null,
      "realizedPnl": null,
      "hasAuditLog": true
    }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 2,
  "totalPages": 1
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `items[]` | array | 本页交易。每项与 `GET /api/option-trades/{id}` 的响应体**完全相同**（字段说明见 FEAT-001）：`cashChange` 为对总现金的带符号影响（STO/STC +，BTO/BTC −），`frozenChange` 为对冻结现金的带符号影响（STO put 为 +担保，BTO 为 `"0.00"`，以后 BTC 平空头 put 释放冻结为负） |
| `page` / `size` | number | 本次使用的分页参数 |
| `totalItems` | number | 符合筛选条件的总条数 |
| `totalPages` | number | 总页数；没有符合条件的交易时为 `0` |

没有符合条件的交易：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。

错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 能解析但不满足规则：`symbol` 格式、`strike` 范围/小数位、`startDate` 晚于 `endDate`、`page` < 1、`size` 不在 1–100。所有这类问题一起放在 `details` 里，`details[].field` 为 query 参数名，`message` 为中文、可直接显示在对应筛选项下 |
| `BAD_REQUEST` | 400 | 值无法解析：`expirationDate`/`startDate`/`endDate` 不是合法日期，`strike` 不是数字，`optionType`/`action` 不是允许的取值，`page`/`size` 不是整数。`details[].field` 为 query 参数名，`message` 为中文（`page`/`size` 不是整数时沿用通用的英文 `must be a valid int`）。有 `BAD_REQUEST` 问题时先报它，不再报 `VALIDATION_ERROR` |

`details[].message`：

| field | code | message |
|-------|------|---------|
| `symbol` | `VALIDATION_ERROR` | `标的只能包含字母、数字和点`、`标的最多 10 个字符` |
| `strike` | `VALIDATION_ERROR` | `行权价必须大于 0`、`行权价最多 3 位小数`、`行权价过大` |
| `strike` | `BAD_REQUEST` | `行权价必须是数字` |
| `expirationDate` / `startDate` / `endDate` | `BAD_REQUEST` | `日期格式不正确（YYYY-MM-DD）` |
| `endDate` | `VALIDATION_ERROR` | `开始日期不能晚于结束日期` |
| `optionType` | `BAD_REQUEST` | `类型只能是 CALL 或 PUT` |
| `action` | `BAD_REQUEST` | `方向只能是 BTO、STO、BTC、STC、EXP`（FEAT-012 起；之前为 `方向只能是 BTO、STO、BTC、STC`） |
| `page` | `VALIDATION_ERROR` | `page 必须 ≥ 1` |
| `size` | `VALIDATION_ERROR` | `size 必须在 1 到 100 之间` |

示例（400）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/option-trades",
  "timestamp": "2026-11-05T15:00:00Z",
  "details": [
    { "field": "endDate", "message": "开始日期不能晚于结束日期" },
    { "field": "symbol", "message": "标的只能包含字母、数字和点" }
  ]
}
```

