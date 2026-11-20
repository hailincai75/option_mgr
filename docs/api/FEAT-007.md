# FEAT-007 资金账户（入金/出金、现金汇总、现金流水）

> **FEAT-035 修订**：`GET /api/cash/summary` 接受 `X-Account-Id: ALL`（顶层为全部账户之和），新增 `accounts[]`（每账户一行；单账户时只有该账户）；流水与入金 / 出金写接口不接受 `ALL`。见 [FEAT-035.md](FEAT-035.md)。
>
> **FEAT-046 修订**：`GET /api/cash/ledger` 接受 `ALL`：行新增 `accountId`、`accountName`（单账户也有值）；排序 `occurredAt` 倒序 → `id` 倒序 → `accountId` 升序，合并后分页；`balanceAfter` 仍是**该行所在账户自己的**累计总现金（Q228），不是跨账户连续余额。入金 / 出金写接口仍不接受 `ALL`。见 [FEAT-046.md](FEAT-046.md)。
>
> **FEAT-051 修订**：平仓保存被平批次（Q39 自动重新匹配取消），`OPTION_CLOSE` 的 `frozenChange` 不再因补录 / 修改 / 删除被改正（下表"可能按新的 FIFO 匹配被更新"对 `OPTION_CLOSE` 不再成立；`OPTION_EXPIRE` / `OPTION_ASSIGN` 照旧）。见 [FEAT-051.md](FEAT-051.md)。

单账户、只有 USD。所有金额 scale 固定为 2（`"0.00"`、`"30000.00"`、`"-2500.50"`），JSON 字符串，**不带千分位**；千分位、`+`/`−` 号由前端格式化。

#### 概念
- **现金流水（ledger entry）**：所有现金变动的统一记录。每条流水有来源类型 `sourceType` 和来源记录 id `sourceId`（如入金记录 id；以后是交易 id）。
- **录入顺序**：流水 `id` 单调递增，即录入顺序。所有排序、重放都按 `(occurredAt, id)`：先按时间，同一时间按录入顺序。
- **总现金** = 所有流水 `amount` 之和；**冻结现金** = 所有流水的冻结变动之和（FEAT-007 内始终 `0.00`，FEAT-001 STO put 开始产生）；**可用现金** = 总现金 − 冻结现金。
- **重放检查（Q19）**：出金（以及 FEAT-001 的期权开仓）时，把本笔按 `(occurredAt, 新 id)` 插入所有已有流水中按顺序重放；从本笔起（含本笔）的每一条流水之后，总现金和可用现金都必须 ≥ 0，否则整笔拒绝、不保存。同一时间的已有流水都排在本笔之前（本笔是最新录入的）。入金不做此检查。

#### `sourceType` 取值
| 值 | 含义 | 来自 |
|----|------|------|
| `DEPOSIT` | 入金 | FEAT-007 |
| `WITHDRAWAL` | 出金 | FEAT-007 |
| `OPTION_OPEN` | 期权开仓（BTO / STO），`sourceId` = 交易 id（`GET /api/option-trades/{id}`） | FEAT-001 |
| `OPTION_CLOSE` | 期权平仓（BTC / STC），`sourceId` = 交易 id；补录更早的平仓后，该条的 `frozenChange` 可能按新的 FIFO 匹配被更新（Q39） | FEAT-004 |
| `OPTION_EXPIRE` | 到期作废（`action = EXP`），`sourceId` = 作废记录 id（与交易共用 id，`GET /api/option-trades/{id}`）。**只在释放冻结（空头 put）时才有这一条**：`amount` 恒为 `"0.00"`，`frozenChange` 为负；`frozenChange` 同样可能被 Q39 改正 | FEAT-012 |
| `STOCK_BUY` | 股票买入，`sourceId` = 股票记录 id（`GET /api/stock-trades/{id}`）；`amount` = −实付，`frozenChange` 恒为 `"0.00"` | FEAT-011 |
| `STOCK_SELL` | 股票卖出，`sourceId` = 股票记录 id；`amount` = +实收，`frozenChange` 恒为 `"0.00"`。（股票转入不产生流水） | FEAT-011 |
| `OPTION_ASSIGN` | 被指派（`action = ASG`），`sourceId` = 被指派记录 id（`GET /api/option-trades/{id}`）；`amount` = −(买股金额 + 指派费)，`frozenChange` = −释放冻结（可能被 Q39 改正）。见「FEAT-013」 | FEAT-013 |

以后的需求会新增取值（如 FEAT-001 的期权开仓），新增取值属于兼容变更。前端遇到不认识的取值时应原样显示，不要报错。

#### `GET /api/cash/summary`
当前现金汇总（所有记录都不晚于当前时间，所以就是全部流水之和）。无参数。供资金页面和 FEAT-001 录入表单复用。

响应 `200 OK`：
```json
{
  "totalCash": "27499.50",
  "frozenCash": "0.00",
  "availableCash": "27499.50"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalCash` | string | 总现金 |
| `frozenCash` | string | 冻结现金（FEAT-007 内始终 `"0.00"`） |
| `availableCash` | string | 可用现金 = 总现金 − 冻结现金 |

空库时三项都是 `"0.00"`。

#### `POST /api/cash/transfers`
新增一笔入金或出金。成功后自动产生一条现金流水。

请求体：
```json
{
  "type": "WITHDRAWAL",
  "amount": "2500.50",
  "occurredAtLocal": "2026-11-03T09:30",
  "remark": "转回银行"
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `type` | string | 是 | `DEPOSIT`（入金）或 `WITHDRAWAL`（出金） |
| `amount` | string | 是 | > 0，最多 2 位小数，整数部分最多 13 位。只填正数，`type` 决定流入还是流出 |
| `occurredAtLocal` | string | 是 | **美东时间（America/New_York）**的本地日期时间，格式 `YYYY-MM-DDTHH:mm` 或 `YYYY-MM-DDTHH:mm:ss`，**不带时区/偏移**，不带小数秒。后端换算为 UTC 保存；换算后不得晚于服务器当前时间 |
| `remark` | string | 否 | 最多 500 个字符；`null`、空串或全空白视为没有备注（保存为 `null`），其它情况原样保存 |

时间换算规则（后端用 Java `ZonedDateTime.of(local, America/New_York)` 的默认规则）：
- 夏令时 `2026-07-15T10:30` → `2026-07-15T14:30:00Z`；标准时间 `2026-11-02T10:00` → `2026-11-02T15:00:00Z`
- 夏令时开始当天不存在的时刻（如 `2026-03-08T02:30`）：顺延一小时，按 03:30 EDT 保存 → `2026-03-08T07:30:00Z`
- 夏令时结束当天重复的时刻（如 `2026-11-01T01:30`）：取较早的那个（EDT，UTC−4）→ `2026-11-01T05:30:00Z`

响应 `201 Created`：
```json
{
  "id": 2,
  "type": "WITHDRAWAL",
  "amount": "2500.50",
  "occurredAt": "2026-11-03T14:30:00Z",
  "remark": "转回银行",
  "ledgerEntryId": 2,
  "createdAt": "2026-11-10T14:45:00.123456Z"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 入金/出金记录 id（即流水的 `sourceId`） |
| `type` | string | `DEPOSIT` / `WITHDRAWAL` |
| `amount` | string | 正数金额，scale 2 |
| `occurredAt` | string | 发生时间，ISO-8601 UTC Instant |
| `remark` | string \| null | 备注 |
| `ledgerEntryId` | number | 该记录产生的现金流水 id |
| `createdAt` | string | 录入时间（服务器时间），ISO-8601 UTC Instant |

错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | `type` 缺失；`amount` 缺失、为 `null` 或空串 `""`（视为缺失，message 为"金额不能为空"）、≤ 0、超过 2 位小数或过大；`occurredAtLocal` 缺失或晚于当前时间；`remark` 超过 500 字符。`details[].field` 为对应字段名，`details[].message` 为中文说明，可直接显示在字段下 |
| `BAD_REQUEST` | 400 | `type` 不是允许的取值、`amount` 不是数字、`occurredAtLocal` 格式不对（含带了时区或小数秒）或日期时间不存在（如 `2026-02-30T10:00`、`2026-11-02T24:00`，严格校验，不会被改成月末）、JSON 语法错误。`details` 指出字段（JSON 语法错误除外） |
| `CASH_INSUFFICIENT` | 422 | 出金会让本笔时点或之后某个时点的总现金/可用现金变负。`message` 是**中文、可直接显示给用户**的原因，`details` 为 `[]`。不保存任何东西 |

`CASH_INSUFFICIENT` 的 `message` 有两种形式（金额带千分位、2 位小数，负数用 `−`(U+2212)，时间为美东 `YYYY-MM-DD HH:mm`）：
- 本笔时点就不够：`可用现金不足：需要 27,499.51，可用 27,499.50`（"可用"是插入本笔之前那一刻的可用现金）
- 本笔时点够、但之后某个时点会变负：`可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00`（取第一个变负的时点）

示例（422）：
```json
{
  "code": "CASH_INSUFFICIENT",
  "message": "可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00",
  "status": 422,
  "path": "/api/cash/transfers",
  "timestamp": "2026-11-10T14:45:00Z",
  "details": []
}
```

示例（400，金额 3 位小数）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/cash/transfers",
  "timestamp": "2026-11-10T14:45:00Z",
  "details": [ { "field": "amount", "message": "金额最多 2 位小数" } ]
}
```

示例（400，时间晚于当前）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/cash/transfers",
  "timestamp": "2026-11-10T14:45:00Z",
  "details": [ { "field": "occurredAtLocal", "message": "时间不能晚于当前时间" } ]
}
```

#### `GET /api/cash/ledger?page=1&size=20`
现金流水，按时间倒序（最新在上；同一时间的，后录入的在上），分页。

| query 参数 | 类型 | 默认 | 规则 |
|------------|------|------|------|
| `page` | number | `1` | 从 **1** 开始，≥ 1；超出最后一页时 `items` 为空数组 |
| `size` | number | `20` | 1–100 |

`page`/`size` 不是整数 → 400 `BAD_REQUEST`；超出范围 → 400 `VALIDATION_ERROR`（`details[].field` 为 `page` 或 `size`）。

响应 `200 OK`：
```json
{
  "items": [
    {
      "id": 2,
      "occurredAt": "2026-11-03T14:30:00Z",
      "sourceType": "WITHDRAWAL",
      "sourceId": 2,
      "amount": "-2500.50",
      "balanceAfter": "27499.50",
      "frozenChange": "0.00",
      "description": null,
      "remark": "转回银行",
      "hasAuditLog": true
    },
    {
      "id": 1,
      "occurredAt": "2026-11-02T15:00:00Z",
      "sourceType": "DEPOSIT",
      "sourceId": 1,
      "amount": "30000.00",
      "balanceAfter": "30000.00",
      "frozenChange": "0.00",
      "description": null,
      "remark": "初始资金",
      "hasAuditLog": false
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
| `items[].id` | number | 流水 id，同时代表录入顺序 |
| `items[].occurredAt` | string | 发生时间，ISO-8601 UTC Instant |
| `items[].sourceType` | string | 来源类型，见上表 |
| `items[].sourceId` | number | 来源记录 id（入金/出金为 `POST /api/cash/transfers` 返回的 `id`） |
| `items[].amount` | string | 带符号金额：流入为正，流出为负（如 `"-2500.50"`） |
| `items[].balanceAfter` | string | 按 `(occurredAt, id)` 顺序累计到该条（含）为止的**总现金**。补录历史记录后，之后各条的值会随之变化（每次查询时重新计算） |
| `items[].frozenChange` | string | 该条流水带来的冻结现金变动（带符号，scale 2）：入金/出金、BTO 为 `"0.00"`；STO put 为 `+担保金额`（如 `"20000.00"`）；以后的平仓/到期释放为负。**FEAT-001 新增字段（兼容变更）** |
| `items[].description` | string \| null | 系统生成的说明（入金/出金为 `null`；期权交易流水写明交易，如 `STO 1 张 AAPL 2026-12-18 200 P`，格式见 FEAT-001） |
| `items[].remark` | string \| null | 用户备注 |
| `items[].hasAuditLog` | boolean | 入金/出金（`DEPOSIT` / `WITHDRAWAL`）行：该笔入金/出金（`sourceId`）被修改过时为 `true`（存在记录上的标记；即审计里有 `objectType=CASH_TRANSFER&objectId=<sourceId>`），前端据此决定是否显示"历史"链接。期权交易流水（`OPTION_OPEN` / `OPTION_CLOSE` / `OPTION_EXPIRE`）没有"历史"链接，恒为 `false`。**新增字段（兼容变更），见"列表行的 `hasAuditLog`"** |
| `page` / `size` | number | 本次使用的分页参数 |
| `totalItems` | number | 流水总条数 |
| `totalPages` | number | 总页数；没有流水时为 `0` |

空库：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。

