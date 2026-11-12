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

### 测试支持（仅 `e2e` profile，正常运行时不存在）

> ⚠️ **只供 E2E 测试使用。** 以下接口只在后端以 `e2e` profile 启动时存在（`SPRING_PROFILES_ACTIVE=e2e` 或 `--spring.profiles.active=e2e`）。默认运行、`test` profile 及任何其它情况下，这些路径和任何未知路径一样返回 404 `NOT_FOUND`。前端页面不得调用。
>
> `e2e` profile 强制使用内存 H2（`jdbc:h2:mem:e2e`）；如果最终生效的 `spring.datasource.url` 不是 `jdbc:h2:mem:...`（例如被 `SPRING_DATASOURCE_URL` 改成文件库），后端**拒绝启动**，所以不可能误清开发库 `backend/data/`。

#### `POST /api/test-support/reset`
删除所有业务数据，把数据库恢复成"刚完成 Flyway 迁移的新库"状态：
- 所有表（除 `flyway_schema_history`）恢复到启动时迁移完成那一刻的内容：业务表清空，迁移写入的初始数据（如 `cash_account` 唯一一行）保持/恢复原样
- 所有自增 id 重新从 1 开始（或从迁移初始数据的最大 id + 1 开始），所以重置后的写入与新库完全一致（如第一笔入金 `id = 1`、`ledgerEntryId = 1`，流水录入顺序从头开始）
- 在一个事务里执行，并持有所有现金写操作使用的 `cash_account` 行锁：不会与并发写操作交错（正在进行的写操作先完成，重置后到的写操作在重置之后执行）

无请求参数，无请求体。

响应 `204 No Content`（无响应体）。重复调用是安全的（幂等）。

错误：只有通用错误（如 `GET /api/test-support/reset` → 405 `METHOD_NOT_ALLOWED`；未启用 `e2e` profile → 404 `NOT_FOUND`）。

示例：
```
POST /api/test-support/reset
→ 204 No Content

GET /api/cash/summary
→ 200 {"totalCash": "0.00", "frozenCash": "0.00", "availableCash": "0.00"}
```

### FEAT-007 资金账户（入金/出金、现金汇总、现金流水）

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
      "remark": "转回银行"
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
| `items[].frozenChange` | string | 该条流水带来的冻结现金变动（带符号，scale 2）：入金/出金、BTO 为 `"0.00"`；STO put 为 `+担保金额`（如 `"20000.00"`）；以后的平仓/到期释放为负。**FEAT-001 新增字段（兼容变更）** |
| `items[].description` | string \| null | 系统生成的说明（入金/出金为 `null`；期权交易流水写明交易，如 `STO 1 张 AAPL 2026-12-18 200 P`，格式见 FEAT-001） |
| `items[].remark` | string \| null | 用户备注 |
| `page` / `size` | number | 本次使用的分页参数 |
| `totalItems` | number | 流水总条数 |
| `totalPages` | number | 总页数；没有流水时为 `0` |

空库：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。

### FEAT-001 录入开仓期权交易（BTO / STO）

单账户、只有 USD。一笔交易只记**含手续费的净额**（Q6）。交易成功后同一事务内自动产生一条现金流水（`sourceType = OPTION_OPEN`，`sourceId` = 交易 id），现金汇总和流水沿用 FEAT-007 的 `GET /api/cash/summary`、`GET /api/cash/ledger`（流水项新增 `frozenChange` 字段，见上）。

#### 概念
- **合约** = `symbol` + `expirationDate` + `strike` + `optionType`。`strike` 按数值比较（`200`、`200.0`、`200.000` 是同一合约）。同一合约多次开仓保存为多笔独立交易（以后 FIFO 批次）。
- **方向** `action`：`BTO`（买入开仓，多头 +张数）、`STO`（卖出开仓，空头 −张数）。`BTC` / `STC`（平仓）属于 FEAT-004，本接口目前拒绝（见错误表）。
- **持仓（按时点）**：某合约在某时点的持仓 = 该合约所有交易按 `(成交时间, 交易 id)` 顺序累加到该时点为止的带符号张数（BTO/BTC 为 +，STO/STC 为 −）。同一时间的已有交易排在本笔之前（本笔是最新录入的）。FEAT-001 只有开仓，所以持仓只增不减；FEAT-004 起平仓会减少持仓。
- **现金影响**：
  - BTO：总现金 −净额，冻结不变
  - STO put：总现金 +净额，冻结 +担保；**担保 = 行权价 × multiplier × 张数**，按分向上取整（只有 multiplier 不是 10 的倍数且行权价有 3 位小数时才会真正发生，如 `12.345 × 1 × 1 = 12.35`）
  - 冻结在本需求中不会释放（FEAT-004 / M2）

#### `POST /api/option-trades`
新增一笔开仓交易。

请求体：
```json
{
  "symbol": " aapl ",
  "expirationDate": "2026-12-18",
  "strike": "200",
  "optionType": "PUT",
  "action": "STO",
  "contracts": 1,
  "netAmount": "349.35",
  "tradedAtLocal": "2026-11-10T10:30",
  "multiplier": 100,
  "note": "第一笔 CSP"
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `symbol` | string | 是 | 先去掉首尾空白、转大写，再校验：只允许字母 `A–Z`、数字和 `.`，长度 1–10（如 `BRK.B`）。保存规范化后的值 |
| `expirationDate` | string | 是 | `YYYY-MM-DD`（严格校验，`2026-02-30` 为 400 `BAD_REQUEST`）。不得早于成交时间所在的**美东日期**；可以等于（0DTE） |
| `strike` | string | 是 | > 0，最多 3 位小数，≤ `9999999.999` |
| `optionType` | string | 是 | `CALL` 或 `PUT` |
| `action` | string | 是 | `BTO` 或 `STO`（`BTC`/`STC` → 400 `VALIDATION_ERROR`，见下） |
| `contracts` | number | 是 | 正整数，≤ 100000。`1.5`、`0`、`-1` → 400 `VALIDATION_ERROR`（不会被截断成整数） |
| `netAmount` | string | 是 | 含手续费的净额：STO = 扣除手续费后的实收，BTO = 含手续费的实付。> 0，最多 2 位小数，整数部分最多 13 位；只填正数，方向决定流入/流出 |
| `tradedAtLocal` | string | 是 | **美东时间**本地日期时间，格式与规则同 FEAT-007 `occurredAtLocal`（`YYYY-MM-DDTHH:mm[:ss]`，不带时区，严格解析，夏令时按 Java 默认规则：不存在的时刻顺延 1 小时，重复的时刻取较早的 EDT）。换算后不得晚于服务器当前时间 |
| `multiplier` | number | 否 | 正整数，≤ 10000。缺省或 `null` 时为 `100` |
| `note` | string | 否 | 最多 500 个字符；`null`、空串、全空白保存为 `null` |

检查顺序（任一失败即整笔拒绝，不保存交易、流水和冻结）：
1. 字段校验 → 400 `VALIDATION_ERROR` / `BAD_REQUEST`。先做与请求体本身有关的校验（必填、格式、范围）；全部通过后再做与时间有关的两项：`tradedAtLocal` 晚于当前时间、`expirationDate` 早于成交日（这两项可能一起出现在 `details` 中）
2. 多空冲突（Q7）→ 422 `POSITION_DIRECTION_CONFLICT`
3. STO call（Q16）→ 422 `SELL_CALL_NOT_SUPPORTED`
4. multiplier 一致（Q23）→ 422 `MULTIPLIER_MISMATCH`
5. 现金（Q13、Q18、Q19）→ 422 `CASH_INSUFFICIENT`

第 2、4 步按"持仓（按时点）"重放该合约的全部交易（本笔插入其中）：既检查本笔成交时点，也检查本笔之后该合约的已有交易——补录一笔较早的交易不能让之后已有的开仓变成多空冲突或 multiplier 不一致（Q7"任一时刻只能纯多或纯空"）。

响应 `201 Created`：
```json
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
  "tradedAt": "2026-11-10T15:30:00Z",
  "tradedAtLocal": "2026-11-10T10:30:00",
  "cashChange": "349.35",
  "frozenChange": "20000.00",
  "contractLabel": "AAPL 2026-12-18 200 P",
  "note": "第一笔 CSP",
  "ledgerEntryId": 2,
  "createdAt": "2026-11-10T15:45:00.123456Z"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 交易 id（即流水的 `sourceId`） |
| `symbol` | string | 规范化后的标的（大写、无首尾空白） |
| `expirationDate` | string | 到期日 `YYYY-MM-DD` |
| `strike` | string | 行权价，**去掉末尾多余的 0**（`"200"`、`"12.5"`、`"12.345"`），可直接用于显示 |
| `optionType` | string | `CALL` / `PUT` |
| `action` | string | `BTO` / `STO` |
| `contracts` | number | 张数（JSON 整数） |
| `multiplier` | number | 合约乘数（JSON 整数） |
| `netAmount` | string | 用户录入的净额（正数，scale 2） |
| `tradedAt` | string | 成交时间，ISO-8601 UTC Instant |
| `tradedAtLocal` | string | 同一成交时间的美东本地时间 `YYYY-MM-DDTHH:mm:ss`（无时区），方便显示；与 `tradedAt` 一一对应 |
| `cashChange` | string | 本笔对总现金的带符号影响（scale 2）：STO 为 `+净额`，BTO 为 `−净额`（如 `"-512.65"`） |
| `frozenChange` | string | 本笔对冻结现金的影响（scale 2）：STO put 为担保金额，BTO 为 `"0.00"` |
| `contractLabel` | string | 合约显示名：`<symbol> <expirationDate> <strike> <C 或 P>`，如 `"AAPL 2026-12-18 200 P"` |
| `note` | string \| null | 备注 |
| `ledgerEntryId` | number | 本笔产生的现金流水 id |
| `createdAt` | string | 录入时间（服务器时间），ISO-8601 UTC Instant |

本笔产生的现金流水（`GET /api/cash/ledger` 中）：`occurredAt` = `tradedAt`，`sourceType` = `"OPTION_OPEN"`，`sourceId` = 交易 `id`，`amount` = `cashChange`，`frozenChange` = 本笔 `frozenChange`，`remark` = `note`，`description` = `"<action> <contracts> 张 <contractLabel>"`，multiplier 不是 100 时再加 `"（multiplier <m>）"`，例：
- `STO 1 张 AAPL 2026-12-18 200 P`
- `BTO 2 张 F 2026-12-18 12.5 C`
- `STO 1 张 XYZ 2026-12-18 200 P（multiplier 10）`

错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 字段不满足上表规则；`action` 为 `BTC`/`STC`；`tradedAtLocal` 晚于当前时间；`expirationDate` 早于成交日（美东）。`details[].field` 为请求体字段名，`details[].message` 为中文，可直接显示在字段下（见下表） |
| `BAD_REQUEST` | 400 | 值无法解析：`optionType`/`action` 不是枚举值、`strike`/`netAmount`/`contracts`/`multiplier` 不是数字、`expirationDate`/`tradedAtLocal` 格式不对或日期不存在、JSON 语法错误。`details` 指出字段（JSON 语法错误除外） |
| `POSITION_DIRECTION_CONFLICT` | 422 | Q7：成交时点该合约为空头时录 BTO、为多头时录 STO；或补录后该合约之后的某笔已有开仓会与持仓方向冲突 |
| `SELL_CALL_NOT_SUPPORTED` | 422 | Q16：M1 不允许 STO call |
| `MULTIPLIER_MISMATCH` | 422 | Q23：成交时点该合约有未平仓持仓且 multiplier 不同；或补录后该合约之后的某笔已有交易与持仓 multiplier 不同 |
| `CASH_INSUFFICIENT` | 422 | Q13/Q18/Q19 现金不足（同 FEAT-007） |

所有 422 的 `message` 都是**中文、可直接显示给用户**的完整原因，`details` 为 `[]`。前端按 `code` 分支即可，不需要解析 `message`。

`VALIDATION_ERROR` 的 `details[].message`：

| field | message |
|-------|---------|
| `symbol` | `标的不能为空`、`标的只能包含字母、数字和点`、`标的最多 10 个字符` |
| `expirationDate` | `到期日不能为空`、`到期日不能早于成交日（2026-11-10）`（括号内为成交时间的美东日期） |
| `strike` | `行权价不能为空`、`行权价必须大于 0`、`行权价最多 3 位小数`、`行权价过大` |
| `optionType` | `请选择 Call 或 Put` |
| `action` | `请选择方向`、`本功能只支持开仓（BTO / STO），平仓请使用平仓功能` |
| `contracts` | `张数不能为空`、`张数必须是正整数`、`张数过大` |
| `netAmount` | `净额不能为空`、`净额必须大于 0`、`净额最多 2 位小数`、`净额过大` |
| `tradedAtLocal` | `成交时间不能为空`、`成交时间不能晚于当前时间` |
| `multiplier` | `multiplier 必须是正整数`、`multiplier 过大` |
| `note` | `备注最多 500 个字符` |

一个字段可能同时有多条 message（如 `strike` 为 `-5.1234`）；同一字段相同的 message 只出现一次。

422 `message` 格式（金额千分位 2 位小数、负数用 `−`(U+2212)，时间为美东 `YYYY-MM-DD HH:mm`，合约为 `contractLabel`）：
- `POSITION_DIRECTION_CONFLICT`
  - 成交时点为空头、录 BTO：`该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）`
  - 成交时点为多头、录 STO：`该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）`
  - 补录后之后某笔冲突：`补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-11 11:00 的 STO 将与多头持仓（1 张）冲突；一个合约任一时刻只能纯多或纯空`
- `SELL_CALL_NOT_SUPPORTED`：`需要股票持仓功能（FEAT-011）后才能卖出 call`
- `MULTIPLIER_MISMATCH`
  - 成交时点：`该合约（XYZ 2026-12-18 200 P）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同`
  - 补录后之后某笔不一致：`补录后该合约（XYZ 2026-12-18 200 P）在 2026-11-11 11:00 的交易 multiplier 为 10，与当时持仓的 multiplier 100 不一致`
- `CASH_INSUFFICIENT`
  - BTO，本笔时点不够：`可用现金不足：需要 512.65，可用 500.00`（"可用"为本笔录入前该时点的可用现金）
  - BTO，之后某时点变负：`可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00`
  - STO put，录入前可用 < 担保（Q18，不含本笔权利金）：`可用现金不足以担保卖出 put：需要 20,000.00，可用 19,999.99`
  - STO put，之后某时点变负：`可用现金不足以担保卖出 put：需要 20,000.00；2026-11-05 10:00 时可用现金将变为 −150.65`

示例（422）：
```json
{
  "code": "CASH_INSUFFICIENT",
  "message": "可用现金不足以担保卖出 put：需要 20,000.00，可用 19,999.99",
  "status": 422,
  "path": "/api/option-trades",
  "timestamp": "2026-11-12T15:00:00Z",
  "details": []
}
```

示例（400）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/option-trades",
  "timestamp": "2026-11-12T15:00:00Z",
  "details": [
    { "field": "contracts", "message": "张数必须是正整数" },
    { "field": "symbol", "message": "标的只能包含字母、数字和点" }
  ]
}
```

#### `GET /api/option-trades/{id}`
按 id 查一笔交易。响应 `200 OK`，响应体与 `POST /api/option-trades` 的 `201` 响应完全相同（含 `ledgerEntryId`）。

错误：`id` 不是整数 → 400 `BAD_REQUEST`（`details[].field = "id"`）；不存在 → 404 `NOT_FOUND`（`message`：`Option trade 99 not found`）。

### FEAT-002 交易列表与筛选

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
| `action` | string，可多值 | `BTO` / `STO` / `BTC` / `STC`，选中的方向之间是"或者"。多值写法：重复参数 `action=BTO&action=STO`，或逗号分隔 `action=BTO,STO`（两种可混用；空项忽略）。`BTC` / `STC` 现在就合法（FEAT-004 之前结果为空，不报错）。不认识的值 → `BAD_REQUEST` |
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
      "createdAt": "2026-11-05T15:00:00Z"
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
      "createdAt": "2026-11-05T15:00:00Z"
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
| `action` | `BAD_REQUEST` | `方向只能是 BTO、STO、BTC、STC` |
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

### FEAT-003 期权持仓汇总

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

### FEAT-004 录入平仓交易（BTC / STC）

平仓与开仓存放在同一个交易记录里：`GET /api/option-trades/{id}`、`GET /api/option-trades`（FEAT-002）都能查到平仓；`GET /api/option-positions`（FEAT-003）按 FIFO 扣掉被平批次。开仓仍用 `POST /api/option-trades`（它继续拒绝 `BTC`/`STC`，提示"平仓请使用平仓功能"）；平仓用下面的 `POST /api/option-trades/closes`。

#### 概念
- **批次与整笔平仓（Q21）**：每笔开仓是一个批次，只能一次整笔平掉。一笔平仓按 FIFO（成交时间、交易 id）平掉该合约**最早的 1 笔或连续多笔整批次**，张数必须正好等于这些批次张数之和。例：批次 A 2 张、B 1 张 → 可平 2（A）或 3（A+B）张，平 1 张被拒绝。
- **可平张数**：某时点最早 1 笔、最早 2 笔……整批次的累计张数，如 `[2, 3]`。
- **方向（Q7）**：`BTC` 只能平空头，`STC` 只能平多头。
- **multiplier**：平仓的 multiplier 取该合约在成交时点持仓的 multiplier，请求里不需要（带了也**忽略**）。
- **现金影响**：
  - `BTC`：总现金 −净额；冻结 −被平批次冻结之和（释放）。**同一时点先释放冻结再扣现金**（Q20）：要求平仓后总现金 ≥ 0、可用现金 ≥ 0，然后按 Q19 重放之后每个时点。
  - `STC`：总现金 +净额，冻结不变。
- **已实现盈亏（Q21、Q38）**：按这一笔平仓整体计算，不分摊、不舍入：空头 = 被平批次开仓净额之和 − BTC 净额；多头 = STC 净额 − 被平批次开仓净额之和。
- **补录（Q27、Q39）**：本笔按 `(成交时间, 新 id)` 插入该合约全部交易重放（同一时间的已有交易排在本笔之前）。从插入点起之后的每笔已有交易按新的持仓和 FIFO 批次重新检查，任一笔违规（平仓没有持仓 / 方向不对 / 超过持仓 / 不是整笔批次；开仓多空冲突 / multiplier 不一致）就拒绝。合法时，之后的平仓按新的 FIFO 匹配：它们被平的批次、已实现盈亏在查询时按新匹配给出，释放的冻结（流水的 `frozenChange`）随之更新（同一事务，现金检查用更新后的值）。这一条同样适用于补录开仓（`POST /api/option-trades`）。
- **到期（Q36）**：成交时间的美东日期不得晚于到期日；到期日当天任何时刻都可以平仓。

#### `POST /api/option-trades/closes`
新增一笔平仓交易。

请求体：
```json
{
  "symbol": "AAPL",
  "expirationDate": "2026-12-18",
  "strike": "200",
  "optionType": "PUT",
  "action": "BTC",
  "contracts": 2,
  "netAmount": "241.30",
  "tradedAtLocal": "2026-11-05T10:00",
  "note": null
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | | 是 | 合约，规则与 FEAT-001 完全相同（标的规范化、行权价按数值比较） |
| `action` | string | 是 | `BTC` 或 `STC`。`BTO`/`STO` → 400 `VALIDATION_ERROR`（`本功能只支持平仓（BTC / STC），开仓请使用开仓功能`） |
| `contracts` | number | 是 | 正整数，≤ 100000（同 FEAT-001）；另须满足整笔批次规则 |
| `netAmount` | string | 是 | BTC = 含手续费实付，STC = 扣手续费实收。> 0，最多 2 位小数，≤ `9999999999999.99` |
| `tradedAtLocal` | string | 是 | 美东本地时间，规则同 FEAT-001；不得晚于当前时间；**美东日期不得晚于 `expirationDate`** |
| `note` | string | 否 | 最多 500 个字符；空白保存为 `null` |
| `multiplier` | — | — | 不需要；如果带了，**忽略**（使用持仓的 multiplier） |

检查顺序（任一失败即整笔拒绝，不保存交易、流水，也不改动之后的交易）：
1. 字段校验 → 400 `VALIDATION_ERROR` / `BAD_REQUEST`（与时间有关的两项——晚于当前、晚于到期日——在其它字段都通过后检查）
2. 成交时点有持仓、方向正确 → 422 `NO_POSITION_TO_CLOSE` / `POSITION_DIRECTION_CONFLICT`
3. 张数：不超过持仓、是最早若干笔整批次之和 → 422 `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS`
4. 之后的已有交易仍然合法 → 422，code 按之后那笔违规的种类（见错误表），message 指出那笔的时间和内容
5. 现金 → 422 `CASH_INSUFFICIENT`

响应 `201 Created`：与 `GET /api/option-trades/{id}` 相同的交易表示（FEAT-001 字段 + 下面新增的两个字段）：
```json
{
  "id": 3,
  "symbol": "AAPL",
  "expirationDate": "2026-12-18",
  "strike": "200",
  "optionType": "PUT",
  "action": "BTC",
  "contracts": 2,
  "multiplier": 100,
  "netAmount": "241.30",
  "tradedAt": "2026-11-05T15:00:00Z",
  "tradedAtLocal": "2026-11-05T10:00:00",
  "cashChange": "-241.30",
  "frozenChange": "-40000.00",
  "contractLabel": "AAPL 2026-12-18 200 P",
  "note": null,
  "ledgerEntryId": 4,
  "createdAt": "2026-11-11T15:00:00Z",
  "coveredLots": [
    {
      "tradeId": 1,
      "action": "STO",
      "tradedAt": "2026-11-03T15:30:00Z",
      "tradedAtLocal": "2026-11-03T10:30:00",
      "contracts": 2,
      "netAmount": "698.70",
      "frozen": "40000.00"
    }
  ],
  "realizedPnl": "457.40"
}
```

**交易表示新增字段（FEAT-004，兼容变更；开仓、平仓、列表项、按 id 查询都有）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `coveredLots` | array \| null | 平仓：被这笔平仓平掉的开仓批次，FIFO 顺序。开仓：`null` |
| `coveredLots[].tradeId` | number | 开仓交易 id |
| `coveredLots[].action` | string | `BTO` / `STO` |
| `coveredLots[].tradedAt` / `tradedAtLocal` | string | 开仓时间（UTC / 美东本地 `YYYY-MM-DDTHH:mm:ss`） |
| `coveredLots[].contracts` | number | 该批次张数（整笔） |
| `coveredLots[].netAmount` | string | 该批次开仓净额，带现金方向符号（STO +，BTO −），如 `"698.70"`、`"-512.65"` |
| `coveredLots[].frozen` | string | 该批次的冻结（BTC 平空头 put 时被释放），`"0.00"` 表示没有 |
| `realizedPnl` | string \| null | 平仓：本笔已实现盈亏（scale 2，盈利正、亏损负），= 被平批次 `netAmount` 之和 + 本笔 `cashChange`。开仓：`null` |

平仓交易的其它字段：`multiplier` = 持仓的 multiplier；`cashChange` = BTC `−净额` / STC `+净额`；`frozenChange` = BTC 平空头 put 时为 `−释放金额`，其它 `"0.00"`。`coveredLots`、`realizedPnl`、`frozenChange` 都按**当前**的 FIFO 匹配给出——之后若补录了更早的平仓（Q39），再查询时会变成新的匹配结果。

本笔产生的现金流水：`sourceType` = `"OPTION_CLOSE"`，`sourceId` = 交易 id，`amount` = `cashChange`，`frozenChange` = 本笔 `frozenChange`，`remark` = `note`，`description` 与开仓同格式，如 `BTC 2 张 AAPL 2026-12-18 200 P`、`BTC 1 张 XYZ 2026-12-18 200 P（multiplier 10）`。

错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 字段规则（文案同 FEAT-001 表）；`action` 不是平仓；`tradedAtLocal` 晚于当前时间（`成交时间不能晚于当前时间`）或美东日期晚于到期日（field `tradedAtLocal`，`到期日（2026-11-06）之后不能平仓`） |
| `BAD_REQUEST` | 400 | 同 FEAT-001 |
| `NO_POSITION_TO_CLOSE` | 422 | 成交时点该合约没有持仓；或补录后之后的某笔平仓没有持仓可平 |
| `POSITION_DIRECTION_CONFLICT` | 422 | 方向不对（BTC 遇多头 / STC 遇空头）；或补录后之后某笔交易方向冲突 |
| `CLOSE_EXCEEDS_POSITION` | 422 | 平仓张数超过成交时点持仓；或补录后之后某笔平仓超过当时持仓 |
| `CLOSE_NOT_WHOLE_LOTS` | 422 | 张数不是最早若干笔整批次之和；或补录后之后某笔平仓不再是整笔批次 |
| `MULTIPLIER_MISMATCH` | 422 | 补录后之后某笔交易与当时持仓 multiplier 不一致 |
| `CASH_INSUFFICIENT` | 422 | 现金不足（Q13/Q19/Q20） |

422 的 `message`（中文，可直接显示；合约为 `contractLabel`，时间为美东 `YYYY-MM-DD HH:mm`）：
- 本笔成交时点：
  - `该合约（AAPL 2026-12-18 205 P）在成交时没有持仓，无法平仓`
  - `该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（3 张），应使用 Buy to Close（BTC）`
  - `该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），应使用 Sell to Close（STC）`
  - `平仓 4 张超过当时持仓 3 张`
  - `必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张`（可平张数全部列出：`可平 1 张`、`可平 2 张或 3 张`、`可平 1 张、3 张或 4 张`）
- 补录后之后的某笔（前缀 `补录后该合约（<合约>）在 <时间> 的 <方向> <张数> 张将`）：
  - `补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 1 张将没有持仓可平`
  - `… 的 BTC 1 张将与持仓方向不符（当时为多头持仓 1 张）`
  - `… 的 BTC 3 张将超过当时持仓 2 张`
  - `… 的 BTC 1 张将不是整笔开仓（最早一笔是 2 张，可平 2 张或 3 张）`
  - 之后的开仓：沿用 FEAT-001 的"补录后…冲突"/"multiplier 不一致"文案
- `CASH_INSUFFICIENT`：
  - BTC 本笔时点不够：`可用现金不足：需要 20,349.36，可用 349.35 + 释放冻结 20,000.00 = 20,349.35`（没有释放冻结时为 `可用现金不足：需要 X，可用 Y`）
  - 之后某时点变负：`可用现金不足：需要 X；2026-11-05 10:00 时可用现金将变为 −500.00`

示例（422）：
```json
{
  "code": "CLOSE_NOT_WHOLE_LOTS",
  "message": "必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张",
  "status": 422,
  "path": "/api/option-trades/closes",
  "timestamp": "2026-11-11T15:00:00Z",
  "details": []
}
```

#### 对 FEAT-003 `GET /api/option-positions` 的新增字段（兼容变更）

| 字段 | 类型 | 说明 |
|------|------|------|
| `positions[].closeAction` | string | 平这个持仓应使用的方向：空头 `BTC`，多头 `STC` |
| `positions[].allowedCloseContracts` | number[] | 当前可平张数（最早 1 笔、最早 2 笔……整批次的累计张数，升序），如 `[2, 3]`；平仓表单只能从中选择 |
| `positions[].closable` | boolean | 今天（`asOfDate`）≤ 到期日时为 `true`（含"今天到期"），已过期为 `false`。平仓按钮按它显示 |

持仓、批次、冻结合计按 FIFO 扣掉被平批次后给出：被整笔平掉的批次不再出现在 `lots` 中；净张数为 0 的合约不再出现在 `positions` 中。批次的 `remaining*` 字段在整笔平仓规则下总是等于 `original*`。

### FEAT-005 修改 / 删除交易与入金出金（含审计）

#### 原则
- 修改、删除都把改动应用到**全部历史**上重新检查：受影响合约的全部交易按 `(成交时间, 交易 id)` 重放（FEAT-004 的合约重放），现金流水按 `(时间, 流水 id)` 重放。**任何一笔交易或任何一个时点不成立就整笔拒绝**，什么都不改、不写审计。
- 修改**保留录入顺序**：交易 id、流水 id 都不变（时间改成与另一条相同时，按两者原来的录入顺序排）。
- 修改交易 → 它的现金流水原地更新（时间、金额、冻结变动、说明、备注）；删除 → 流水删除。之后平仓的被平批次、已实现盈亏按新历史得出；释放冻结有变化的平仓，其流水 `frozenChange` 被改正，并作为**连带变化**写进同一条审计记录（Q42）。
- 之后已有的交易：开仓只查 Q7/Q23，平仓查有持仓/方向/不超量/整笔批次；现金只查每个时点总现金 ≥ 0、可用现金 ≥ 0（Q19）。**之后的 STO put 不重查 Q18**（Q41）。
- 被修改的 STO put 本身按 Q18 检查（本笔之前的可用现金 ≥ 新担保，不含本笔权利金）——仅当修改改变了张数、multiplier 或成交时间时（只改净额 / 备注时担保和"本笔之前"都不变，不重查）。
- 提交内容与原记录相同（规范化后比较：备注空白 = 无备注，金额按数值，时间按换算后的时间点）→ 422 `NO_CHANGES`（`没有修改`），不保存、不写审计。
- 已删除或不存在的记录：修改 / 删除 / 按 id 查询都返回 404 `NOT_FOUND`。
- 所有写操作在一个事务里，持有 `cash_account` 行锁（与新增交易、入金出金串行）。

#### `PUT /api/option-trades/{id}`
修改一笔交易的可改字段（Q43）。合约（标的、到期日、行权价、Call/Put）和方向不能改；请求里带了这些字段会被**忽略**（录错请删除后重录）。

请求体（开仓 BTO / STO）：
```json
{
  "contracts": 1,
  "netAmount": "365.00",
  "tradedAtLocal": "2026-11-04T11:00",
  "multiplier": 100,
  "note": "加仓"
}
```
请求体（平仓 BTC / STC）：同上但没有 `multiplier`（带了也忽略；平仓 multiplier 始终取持仓的值）。

| 字段 | 必填 | 规则 |
|------|------|------|
| `contracts` | 是 | 同 FEAT-001（正整数，≤ 100000） |
| `netAmount` | 是 | 同 FEAT-001（> 0，最多 2 位小数，≤ `9999999999999.99`） |
| `tradedAtLocal` | 是 | 美东本地时间，同 FEAT-001；不得晚于当前时间；开仓：成交日不得晚于到期日（`成交日不能晚于到期日（2026-12-18）`）；平仓：同 FEAT-004（`到期日（2026-11-06）之后不能平仓`）。这两条的 `field` 都是 `tradedAtLocal` |
| `multiplier` | 否 | 仅开仓：正整数，≤ 10000；**缺省或 `null` = 不改** |
| `note` | 否 | 最多 500 字符；`null` / 空白 = 没有备注（这是整体替换：不传 `note` 会清空备注） |

响应 `200 OK`：修改后的交易，表示与 `GET /api/option-trades/{id}` 相同。

#### `DELETE /api/option-trades/{id}`
删除一笔交易（开仓或平仓）及其现金流水。响应 `204 No Content`。

#### `PUT /api/cash/transfers/{id}`
修改一笔入金/出金（Q24）：可改类型、金额、时间、备注。请求体与 `POST /api/cash/transfers` 完全相同，规则也相同（`remark` 为整体替换）。响应 `200 OK`，响应体与 `POST /api/cash/transfers` 的 201 响应相同。

#### `DELETE /api/cash/transfers/{id}`
删除一笔入金/出金及其流水（Q40）。响应 `204 No Content`。

#### 错误（四个写接口共用）

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 字段规则（文案同 FEAT-001 / FEAT-004 / FEAT-007） |
| `BAD_REQUEST` | 400 | 值无法解析；`id` 不是整数 |
| `NOT_FOUND` | 404 | 记录不存在或已删除（`Option trade 99 not found` / `Cash transfer 99 not found`） |
| `NO_CHANGES` | 422 | 提交内容与原记录相同：`没有修改` |
| `POSITION_DIRECTION_CONFLICT` / `MULTIPLIER_MISMATCH` / `NO_POSITION_TO_CLOSE` / `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS` | 422 | 被修改的交易本身，或（修改/删除后）该合约的另一笔交易违规。code 按违规种类 |
| `CASH_INSUFFICIENT` | 422 | 修改后的 STO put 不满足 Q18；或修改/删除后某个时点总现金/可用现金为负 |

422 的 `message`（中文，可直接显示；`details` 为 `[]`）：
- 被修改的交易本身：与 FEAT-001 / FEAT-004 相同的"在成交时……"文案，如 `该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓`、`必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张`
- 其它交易：前缀改为 `修改后` / `删除后`，如 `修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将不是整笔开仓（最早一笔是 3 张，可平 3 张或 4 张）`、`修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将超过当时持仓 1 张`
- 删除一笔被之后平仓平掉、因此让那笔平仓违规的开仓：`该开仓被 2026-11-05 10:00 的 BTC 2 张平掉（删除后那笔平仓将超过当时持仓 1 张），请先修改或删除那笔平仓`
- 现金：`可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 −41.30`、`可用现金不足：删除后 2026-11-06 10:00 时可用现金将变为 −28,941.30`（第一个变负的时点；报告可用现金，可用 ≤ 总现金）
- 被修改的 STO put（Q18）：`可用现金不足以担保卖出 put：需要 40,000.00，可用 39,000.00`

#### `GET /api/audit-log`
审计记录（Q12、Q44），只读，没有修改/删除接口。按操作时间倒序，同一时间审计 id 大的在上；分页同 FEAT-002。

| query 参数 | 类型 | 规则 |
|------------|------|------|
| `objectType` | string | 可选：`OPTION_TRADE`（期权交易）或 `CASH_TRANSFER`（入金/出金）。其它值 → 400 `BAD_REQUEST` |
| `objectId` | number | 可选，与 `objectType` 一起使用：只看该对象的记录。只给 `objectId` 不给 `objectType` → 400 `VALIDATION_ERROR`（field `objectType`，`请同时指定对象类型`）；不是整数 → 400 `BAD_REQUEST` |
| `page` / `size` | number | 同 FEAT-002：`page` ≥ 1 默认 1，`size` 1–100 默认 20 |

响应 `200 OK`：
```json
{
  "items": [
    {
      "id": 1,
      "operatedAt": "2026-11-11T15:00:00Z",
      "operation": "UPDATE",
      "objectType": "OPTION_TRADE",
      "objectId": 2,
      "summary": "STO AAPL 2026-12-18 200 P",
      "before": {
        "id": 2, "symbol": "AAPL", "expirationDate": "2026-12-18", "strike": "200", "optionType": "PUT",
        "contractLabel": "AAPL 2026-12-18 200 P", "action": "STO", "contracts": 1, "multiplier": 100,
        "netAmount": "360.00", "tradedAt": "2026-11-04T16:00:00Z", "tradedAtLocal": "2026-11-04T11:00:00",
        "note": null, "cashChange": "360.00", "frozenChange": "20000.00"
      },
      "after": {
        "id": 2, "symbol": "AAPL", "expirationDate": "2026-12-18", "strike": "200", "optionType": "PUT",
        "contractLabel": "AAPL 2026-12-18 200 P", "action": "STO", "contracts": 1, "multiplier": 100,
        "netAmount": "365.00", "tradedAt": "2026-11-04T16:00:00Z", "tradedAtLocal": "2026-11-04T11:00:00",
        "note": null, "cashChange": "365.00", "frozenChange": "20000.00"
      },
      "changes": [
        { "field": "netAmount", "label": "净额", "before": "360.00", "after": "365.00", "text": "净额：360.00 → 365.00" }
      ],
      "sideEffects": []
    }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 审计 id，递增 |
| `operatedAt` | string | 操作时间（服务器时间），ISO-8601 UTC Instant；页面显示为美东 |
| `operation` | string | `UPDATE`（修改）/ `DELETE`（删除） |
| `objectType` / `objectId` | string / number | `OPTION_TRADE` + 交易 id，或 `CASH_TRANSFER` + 入金出金 id |
| `summary` | string | 摘要：交易 `<方向> <合约>`（如 `STO AAPL 2026-12-18 200 P`）；入金出金 `<入金\|出金> <金额>`（如 `入金 80,000.00`，取修改前的值） |
| `before` | object | 修改/删除前的完整内容（见下） |
| `after` | object \| null | 修改后的完整内容；删除为 `null` |
| `changes` | array | 修改时前后不同的字段；删除为 `[]`。每项：`field`、`label`（中文名）、`before` / `after`（显示用字符串，空值为 `"—"`）、`text`（`<label>：<before> → <after>`，可直接显示） |
| `sideEffects` | array | 连带变化（Q42）：本次改动让其它平仓的冻结变动被系统改正时逐条列出，否则 `[]`。每项：`tradeId`、`action`、`contractLabel`、`field`（目前总是 `frozenChange`）、`before` / `after`（显示用带符号金额）、`text`（如 `交易 #3 BTC 冻结变动 −24.70 → −24.69`） |

`before` / `after` 的内容：
- 期权交易：`id`、`symbol`、`expirationDate`、`strike`（去尾零）、`optionType`、`contractLabel`、`action`、`contracts`、`multiplier`、`netAmount`、`tradedAt`、`tradedAtLocal`（美东 `YYYY-MM-DDTHH:mm:ss`）、`note`、`cashChange`、`frozenChange`（金额为字符串，scale 2）
- 入金/出金：`id`、`type`（`DEPOSIT`/`WITHDRAWAL`）、`amount`、`occurredAt`、`occurredAtLocal`、`remark`

`changes` 比较的字段及显示格式：
- 期权交易：`contracts`（张数）、`netAmount`（净额，如 `360.00`）、`tradedAt`（成交时间，美东 `YYYY-MM-DD HH:mm`）、`multiplier`、`note`（备注）、`frozenChange`（冻结变动，带符号如 `+12.35`、`−24.70`，系统按新内容重算的结果）。`cashChange` 不单独列出（由方向和净额决定）
- 入金/出金：`type`（类型：`入金` / `出金`）、`amount`（金额，如 `1,000.00`）、`occurredAt`（时间，美东 `YYYY-MM-DD HH:mm`）、`remark`（备注）

没有记录：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。
