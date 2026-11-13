# FEAT-005 修改 / 删除交易与入金出金（含审计）

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
| `objectType` | string | 可选：`OPTION_TRADE`（期权交易）、`CASH_TRANSFER`（入金/出金）、`ACCOUNT`（FEAT-033）或 `STOCK_TRADE`（股票记录，FEAT-011）。其它值 → 400 `BAD_REQUEST` |
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

