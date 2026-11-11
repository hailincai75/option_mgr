# API 契约

由 backend-dev 维护，frontend-dev 和 qa 只读。每个需求实现前先在这里写好 API。

## 通用约定
- Base path：`/api`；请求与响应体均为 `application/json`（UTF-8）
- **金额/价格/数量类小数**：JSON 字符串，十进制普通写法，不用科学计数法（如 `"349.35"`、`"1000"`）。后端统一把所有 `BigDecimal` 序列化成字符串；请求中金额也应传字符串（传数字也能解析，但不推荐，可能在前端丢精度）。字符串保留后端给出的 scale，前端不要自行四舍五入后再回传
- **日期**（到期日等 `LocalDate`）：ISO-8601 `YYYY-MM-DD`，如 `"2026-12-18"`
- **时间点**（交易时间等 `Instant`）：ISO-8601 UTC，带 `Z` 后缀，如 `"2026-11-10T14:30:00Z"`；小数秒位数不固定（0~9 位），解析时不要假设位数
- 字段名 camelCase；值为空时字段输出 `null`（不省略）
- 在线文档：运行后端后访问 `/swagger-ui.html`（OpenAPI JSON：`/v3/api-docs`）。以本文件为准，两者冲突时报告给 backend-dev

## 统一错误格式
所有 `/api/**` 接口出错时（HTTP 4xx/5xx）返回同一结构：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/trades",
  "timestamp": "2026-11-10T14:30:00.123Z",
  "details": [
    { "field": "price", "message": "must be greater than 0" },
    { "field": "symbol", "message": "must not be blank" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | string | 机器可读错误码，见下表。前端按 `code` 分支处理，不要解析 `message` |
| `message` | string | 给人看的简要说明（英文）。可以显示给用户，但不保证措辞稳定 |
| `status` | number | 与 HTTP 状态码相同 |
| `path` | string | 出错请求的路径（不含 query string） |
| `timestamp` | string | 出错时间，ISO-8601 UTC Instant |
| `details` | array | 字段级错误列表，没有时为空数组 `[]`（永不为 `null`）。每项：`field`（请求体属性名或参数名；嵌套属性用点号，如 `legs[0].strike`）、`message`（该字段的问题）。按 `field` 排序 |

服务器内部错误（500）不返回堆栈、异常类名或内部信息，`message` 固定为 `"Internal server error"`；详细信息只记在服务端日志。

### 通用错误码
| code | HTTP | 何时出现 |
|------|------|---------|
| `VALIDATION_ERROR` | 400 | 请求体/参数能解析但不满足校验规则（必填、正数、格式等）；`details` 列出每个字段 |
| `BAD_REQUEST` | 400 | 请求无法解析：JSON 格式错误、类型不对（如路径参数要数字却给了 `abc`；请求体字段的值无法转换，如金额给了 `"abc"`、枚举值不存在、时间格式不对）、缺少必填 query 参数。类型不对/缺参数时 `details` 会指出字段（请求体字段用属性路径，如 `amount`）；JSON 语法错误时 `details` 为空 |
| `NOT_FOUND` | 404 | 路径不存在，或资源（如某笔交易 id）不存在 |
| `METHOD_NOT_ALLOWED` | 405 | 路径存在但不支持该 HTTP 方法 |
| `CONFLICT` | 409 | 与现有数据冲突（如重复创建、并发修改） |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` 不是 `application/json` |
| `BUSINESS_RULE_VIOLATION` | 422 | 请求格式正确，但违反业务规则（如平仓数量超过持仓） |
| `INTERNAL_ERROR` | 500 | 未预期的服务器错误 |

各需求如需更具体的业务错误码（例如 `POSITION_INSUFFICIENT`），在该需求的 Endpoints 小节里列出，并注明对应的 HTTP 状态；未列出的情况一律使用上表的通用码。

### 错误示例
404，路径不存在：
```json
{
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "status": 404,
  "path": "/api/nope",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": []
}
```

400，路径参数类型错误：
```json
{
  "code": "BAD_REQUEST",
  "message": "Invalid parameter value",
  "status": 400,
  "path": "/api/trades/abc",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": [ { "field": "id", "message": "must be a valid long" } ]
}
```

500：
```json
{
  "code": "INTERNAL_ERROR",
  "message": "Internal server error",
  "status": 500,
  "path": "/api/positions",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": []
}
```

## Endpoints
（按需求编号分节，例如 `### FEAT-001`）

### 基础设施

#### `GET /api/health`
服务存活检查，供前端/E2E 判断后端是否就绪。无请求参数，无请求体。

响应 `200 OK`：
```json
{
  "status": "UP",
  "version": "0.0.1-SNAPSHOT",
  "time": "2026-11-10T22:09:08.676Z"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | 固定 `"UP"`（服务能响应即为 UP；目前不检查数据库） |
| `version` | string | 后端构建版本；构建信息缺失时（如直接在 IDE 运行）为 `"dev"` |
| `time` | string | 服务器当前时间，ISO-8601 UTC Instant |

错误：只有通用错误（如 `DELETE /api/health` → 405 `METHOD_NOT_ALLOWED`）。

### FEAT-007 资金账户（入金/出金、现金汇总、现金流水）

单账户、只有 USD。所有金额 scale 固定为 2（`"0.00"`、`"30000.00"`、`"-2500.50"`），JSON 字符串，**不带千分位**；千分位、`+`/`−` 号由前端格式化。

#### 概念
- **现金流水（ledger entry）**：所有现金变动的统一记录。每条流水有来源类型 `sourceType` 和来源记录 id `sourceId`（如入金记录 id；以后是交易 id）。
- **录入顺序**：流水 `id` 单调递增，即录入顺序。所有排序、重放都按 `(occurredAt, id)`：先按时间，同一时间按录入顺序。
- **总现金** = 所有流水 `amount` 之和；**冻结现金** = 所有流水的冻结变动之和（FEAT-007 内始终 `0.00`，FEAT-001 STO put 开始产生）；**可用现金** = 总现金 − 冻结现金。
- **重放检查（Q19）**：出金时，把本笔按 `(occurredAt, 新 id)` 插入所有已有流水中按顺序重放；从本笔起（含本笔）的每一条流水之后，总现金和可用现金都必须 ≥ 0，否则整笔拒绝、不保存。同一时间的已有流水都排在本笔之前（本笔是最新录入的）。入金不做此检查。

#### `sourceType` 取值
| 值 | 含义 | 来自 |
|----|------|------|
| `DEPOSIT` | 入金 | FEAT-007 |
| `WITHDRAWAL` | 出金 | FEAT-007 |

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
| `VALIDATION_ERROR` | 400 | `type` 缺失；`amount` 缺失、≤ 0、超过 2 位小数或过大；`occurredAtLocal` 缺失或晚于当前时间；`remark` 超过 500 字符。`details[].field` 为对应字段名，`details[].message` 为中文说明，可直接显示在字段下 |
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
      "description": null,
      "remark": "转回银行"
    },
    {
      "id": 1,
      "occurredAt": "2026-11-02T15:00:00Z",
      "sourceType": "DEPOSIT",
      "sourceId": 1,
      "amount": "30000.00",
      "balanceAfter": "30000.00",
      "description": null,
      "remark": "初始资金"
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
| `items[].description` | string \| null | 系统生成的说明（入金/出金为 `null`；以后交易流水会写明交易，如 `STO 1 AAPL 2026-12-18 200 P`） |
| `items[].remark` | string \| null | 用户备注 |
| `page` / `size` | number | 本次使用的分页参数 |
| `totalItems` | number | 流水总条数 |
| `totalPages` | number | 总页数；没有流水时为 `0` |

空库：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。
