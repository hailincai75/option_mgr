# FEAT-001 录入开仓期权交易（BTO / STO）

单账户、只有 USD。一笔交易只记**含手续费的净额**（Q6）。交易成功后同一事务内自动产生一条现金流水（`sourceType = OPTION_OPEN`，`sourceId` = 交易 id），现金汇总和流水沿用 FEAT-007 的 `GET /api/cash/summary`、`GET /api/cash/ledger`（流水项新增 `frozenChange` 字段，见上）。

#### 概念
- **合约** = `symbol` + `expirationDate` + `strike` + `optionType`。`strike` 按数值比较（`200`、`200.0`、`200.000` 是同一合约）。同一合约多次开仓保存为多笔独立交易（以后 FIFO 批次）。
- **方向** `action`：`BTO`（买入开仓，多头 +张数）、`STO`（卖出开仓，空头 −张数）。`BTC` / `STC`（平仓）属于 FEAT-004，本接口目前拒绝（见错误表）。
- **持仓（按时点）**：某合约在某时点的持仓 = 该合约所有交易按 `(成交时间, 交易 id)` 顺序累加到该时点为止的带符号张数（BTO/BTC 为 +，STO/STC 为 −）。同一时间的已有交易排在本笔之前（本笔是最新录入的）。FEAT-001 只有开仓，所以持仓只增不减；FEAT-004 起平仓会减少持仓。
- **现金影响**：
  - BTO：总现金 −净额，冻结不变
  - STO put：总现金 +净额，冻结 +担保；**担保 = 行权价 × multiplier × 张数**，按分向上取整（只有 multiplier 不是 10 的倍数且行权价有 3 位小数时才会真正发生，如 `12.345 × 1 × 1 = 12.35`）
  - STO call（FEAT-038 起允许）：总现金 +净额，冻结 **0**（不冻结、不做 Q18 担保检查、不检查股票覆盖）
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
3. ~~STO call（Q16）→ 422 `SELL_CALL_NOT_SUPPORTED`~~ **FEAT-038 起取消**：STO call 允许，不再有此检查，也不再返回该错误码
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
| ~~`SELL_CALL_NOT_SUPPORTED`~~ | 422 | **FEAT-038 起不再返回**（原 Q16：M1 不允许 STO call） |
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
- ~~`SELL_CALL_NOT_SUPPORTED`：`需要股票持仓功能（FEAT-011）后才能卖出 call`~~（FEAT-038 起不再返回）
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

