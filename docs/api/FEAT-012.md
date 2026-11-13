# FEAT-012 到期作废（`EXP`）、到期日 16:00 / 17:30

**兼容性**：新增接口、新增字段、新增枚举值（交易 `action` 的 `EXP`、流水 `sourceType` 的 `OPTION_EXPIRE`）属于兼容变更；前端遇到不认识的值应原样显示。**行为变化（Q83）**：`positions[].closable` 的口径、开仓/平仓/修改交易新增"成交时间不能晚于到期日美东 16:00"的拒绝、两个方向筛选的错误文案（见下）、`ledgerEntryId` 可以为 `null`（只有 `EXP` 记录）。数据库迁移 `V7__expire_worthless.sql` 只放宽 `option_trade` 的两个检查约束（允许 `action = 'EXP'`、`EXP` 的 `net_amount = 0`），不改任何已有数据。

#### 概念
- **到期日的两个时间点**（都按到期日当天的美东时区换算，夏令时正确）：
  - **截止交易时间（cutoff）** = 到期日美东 16:00:00。例：`2026-11-20` → `2026-11-20T21:00:00Z`（EST）；`2027-03-19` → `2027-03-19T20:00:00Z`（EDT）
  - **可作废时间** = 到期日美东 17:30:00（`2026-11-20` → `2026-11-20T22:30:00Z`）。不检查交易日、假日、提前收盘
- **到期作废记录**：一条 `action = "EXP"` 的期权记录，与交易存在同一张表、**共用 id**（id 即录入顺序）。时间固定为该合约的 cutoff（Q63）；`netAmount` / `cashChange` 恒为 `"0.00"`；`note` 恒为 `null`（Q84）；`multiplier` 取持仓的；`contracts` 为作废时 cutoff 时点的全部持仓张数（Q65），保存后不变。不能修改（Q66），可以删除。
- **重放**：`EXP` 与该合约的交易一起按 `(时间, id)` 重放，等同于"一笔平掉 N 张的平仓"，但不分方向（空头、多头都能作废）：必须有持仓、不超过持仓、正好是最早 1 笔或连续多笔整批次（Q21）、multiplier 与持仓相同（Q23）。被作废批次、已实现盈亏（= 被作废批次开仓净额之和：空头为正、多头为负）、释放冻结（= 被作废批次冻结之和，Q28 不重算）都在读取时按 FIFO 重放得出；补录/修改/删除后自动按新匹配（同 Q39），释放冻结变化时改正该 `EXP` 流水的 `frozenChange`（修改/删除时写入审计 `sideEffects`）。
- **现金**：`EXP` 不动现金。只有释放冻结 > 0（空头 put）时才写一条流水 `OPTION_EXPIRE`（时间 = cutoff，`amount` `"0.00"`，`frozenChange` = −释放额），所以冻结从 cutoff 起释放（之后各时点的可用现金随之增加，参与 Q19 重放）。空头 call、多头没有流水。
- **持仓方向**：被作废的是空头还是多头由重放得出（`expiredDirection` / `closedDirection`）。

#### `POST /api/option-trades/expirations`
作废一个持仓：该合约在 cutoff 时点的全部剩余批次一次作废。

请求体：
```json
{
  "symbol": "XYZ",
  "expirationDate": "2026-11-20",
  "strike": "45",
  "optionType": "PUT",
  "contracts": 3
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | | 是 | 合约，规则与 FEAT-001 完全相同（标的规范化、行权价按数值比较，错误文案相同） |
| `contracts` | number | 是 | 页面上看到的作废张数，即持仓的 `expiryContracts` 原样传回（**不是用户填写的值**，只用于发现"对话框打开期间持仓已变化"）。整数，0–100000。缺失 → `张数不能为空`；负数或小数 → `张数必须是非负整数`；过大 → `张数过大`（0 只会在下面"已知限制"的情况出现） |
| 其它字段 | — | — | `note`、`tradedAtLocal`、`netAmount`、`multiplier`、`action` 等带了一律**忽略**（Q84：不能填备注；时间、净额、multiplier 由系统决定） |

检查顺序（任一失败即整笔拒绝，什么都不保存）：
1. 字段校验 → 400 `VALIDATION_ERROR` / `BAD_REQUEST`
2. 时间（Q10）：服务器当前时间 ≥ 该到期日的可作废时间（美东 17:30，含）→ 否则 422 `EXPIRY_TOO_EARLY`
3. 有持仓：cutoff 时点（同一时间已有的记录都排在本次之前）该合约有持仓 → 否则 422 `NO_POSITION_TO_EXPIRE`（包括已经作废过、已全部平仓、从未有过）
4. 张数一致：cutoff 时点的持仓张数（即 `expiryContracts`）= 请求的 `contracts` → 否则 422 `POSITION_CHANGED`
5. 没有旧的 16:00 之后交易：该合约（当前账户）没有成交时间晚于 cutoff 的交易（只可能是 FEAT-012 之前录入的）→ 否则 422 `EXPIRY_BLOCKED_BY_LATE_TRADE`，指出最早的那一笔
6. 现金（Q19；作废只释放冻结，实际不会失败）→ 422 `CASH_INSUFFICIENT`

响应 `201 Created`：作废记录，表示与 `GET /api/option-trades/{id}` 相同（交易表示，见下"交易表示的变化"）：
```json
{
  "id": 7,
  "symbol": "XYZ",
  "expirationDate": "2026-11-20",
  "strike": "45",
  "optionType": "PUT",
  "action": "EXP",
  "contracts": 3,
  "multiplier": 100,
  "netAmount": "0.00",
  "tradedAt": "2026-11-20T21:00:00Z",
  "tradedAtLocal": "2026-11-20T16:00:00",
  "cashChange": "0.00",
  "frozenChange": "-13500.00",
  "contractLabel": "XYZ 2026-11-20 45 P",
  "note": null,
  "ledgerEntryId": 7,
  "createdAt": "2026-11-23T15:00:00Z",
  "coveredLots": [
    { "tradeId": 1, "action": "STO", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
      "contracts": 2, "netAmount": "240.00", "frozen": "9000.00" },
    { "tradeId": 3, "action": "STO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
      "contracts": 1, "netAmount": "130.00", "frozen": "4500.00" }
  ],
  "realizedPnl": "370.00",
  "hasAuditLog": false,
  "expiredDirection": "SHORT"
}
```
多头 call 作废的响应（没有流水）：`"action": "EXP"`、`"frozenChange": "0.00"`、`"ledgerEntryId": null`、`"realizedPnl": "-85.65"`、`"expiredDirection": "LONG"`、`coveredLots` 中 `netAmount` 为负（如 `"-85.65"`）、`frozen` 为 `"0.00"`。

本次产生的现金流水（仅当释放冻结 > 0）：`occurredAt` = cutoff，`sourceType` = `"OPTION_EXPIRE"`，`sourceId` = 作废记录 `id`，`amount` = `"0.00"`，`frozenChange` = 本记录 `frozenChange`，`remark` = `null`，`description` = `到期作废 <张数> 张 <contractLabel>`，multiplier 不是 100 时再加 `（multiplier <m>）`，如 `到期作废 3 张 XYZ 2026-11-20 45 P`、`到期作废 2 张 QFZ 2026-11-20 12.345 P（multiplier 1）`。

不写审计（与新增交易一致，Q42），`hasAuditLog` 为 `false`。

错误：

| code | HTTP | 何时 / `message`（中文，可直接显示；422 的 `details` 为 `[]`） |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 字段规则（`details[].field` 为字段名，文案见上表和 FEAT-001） |
| `BAD_REQUEST` | 400 | 同 FEAT-001（枚举、数字、日期无法解析，JSON 语法错误） |
| `EXPIRY_TOO_EARLY` | 422 | `到期日（2026-11-20）美东 17:30 起才能作废` |
| `NO_POSITION_TO_EXPIRE` | 422 | `该合约（XYZ 2026-11-20 50 P）没有持仓，无法作废` |
| `POSITION_CHANGED` | 422 | `持仓已变化（当前 4 张），请刷新后重试`（"当前" = cutoff 时点的持仓张数） |
| `EXPIRY_BLOCKED_BY_LATE_TRADE` | 422 | `2026-11-20 16:10 的 BTC 2 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它`（时间为美东 `YYYY-MM-DD HH:mm`，秒不为 0 时为 `HH:mm:ss`） |
| `CASH_INSUFFICIENT` | 422 | 同 FEAT-007（实际不会出现） |

示例（422）：
```json
{
  "code": "POSITION_CHANGED",
  "message": "持仓已变化（当前 4 张），请刷新后重试",
  "status": 422,
  "path": "/api/option-trades/expirations",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": []
}
```

#### 交易表示的变化（`GET /api/option-trades`、`GET /api/option-trades/{id}`、`POST`、`PUT` 的响应）
- `action` 新增取值 `EXP`（到期作废）。
- 新增字段 **`expiredDirection`**（string \| null）：`EXP` 记录为被作废的持仓方向 `SHORT` / `LONG`（按当前重放），其它记录为 `null`。
- `EXP` 记录的其它字段：`contracts` = 作废张数；`multiplier` = 持仓的；`netAmount` / `cashChange` = `"0.00"`；`tradedAt` = cutoff（`tradedAtLocal` 如 `"2026-11-20T16:00:00"`）；`frozenChange` = 流水的 `frozenChange`（空头 put 为负的释放额），没有流水时 `"0.00"`；**`ledgerEntryId` 没有流水时为 `null`**（其它记录仍总是数字）；`note` = `null`；`coveredLots`、`realizedPnl` 与平仓相同（按当前 FIFO 重放）；`hasAuditLog` 恒为 `false`（作废记录不能修改，所以不会有自己的修改审计）。
- **可改 / 可删**：前端按 `action` 判断——`EXP` 只能删除（`DELETE /api/option-trades/{id}`），没有"修改"；其它方向可修改、可删除。"历史"仍按 `hasAuditLog` 显示（`EXP` 行因此不显示）。
- `GET /api/option-trades?action=EXP`：只看到期作废；可与其它方向多选（`action=EXP,STO` 或重复参数）。排序不变（时间倒序，同一时间 id 大的在上），`EXP` 行按 cutoff 时间排序。
- 开仓接口（`POST /api/option-trades`）收到 `action: "EXP"` → 400 `VALIDATION_ERROR`（`本功能只支持开仓（BTO / STO），平仓请使用平仓功能`）；平仓接口收到 `EXP` → 400 `VALIDATION_ERROR`（`本功能只支持平仓（BTC / STC），开仓请使用开仓功能`）。作废只能用 `POST /api/option-trades/expirations`。

#### `PUT /api/option-trades/{id}` 对 `EXP`
422 `EXPIRY_NOT_EDITABLE`，`message`：`到期作废不能修改，记错请删除后重新作废`；什么都不改、不写审计。（在找到记录之后、其它业务检查之前判断；请求体本身不合法时先返回 400。其它账户的或不存在的 id 仍是 404。）

#### `DELETE /api/option-trades/{id}` 对 `EXP`（Q66）
与删除交易相同的原则（FEAT-005）：把删除应用到全部历史上重放。
- 合约重放：被作废的批次恢复为持仓；之后同合约的记录（例如较晚的另一条 `EXP`、在作废之后录入的成交时间正好 16:00 的交易）按新历史重新检查，违规 → 422（code 按违规种类，文案见下"拒绝文案"），如 `删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，可为 2 张、3 张或 4 张），请先删除该到期作废`。
- 现金重放（Q19）：冻结从 cutoff 起恢复，之后每个时点总现金、可用现金都不能为负，否则 422 `CASH_INSUFFICIENT`，如 `可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00`。
- 成功 `204`：作废记录和它的流水（如有）删除；写一条审计（`operation` `DELETE`，`objectType` `OPTION_TRADE`，`objectId` = 作废记录 id），`summary` 为 `到期作废 <contractLabel> <张数> 张`（如 `到期作废 XYZ 2026-11-20 45 P 3 张`）；`before` 与交易相同的字段（见下例）；`after` 为 `null`；`changes` 为 `[]`；`sideEffects` 同 FEAT-005（删除引起其它平仓 / 作废的冻结变动改正时）。
- 删除后可以重新作废（新记录、新 id、`hasAuditLog` 为 `false`）。

审计 `before` 示例：
```json
{
  "id": 7, "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
  "contractLabel": "XYZ 2026-11-20 45 P", "action": "EXP", "contracts": 3, "multiplier": 100,
  "netAmount": "0.00", "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
  "note": null, "cashChange": "0.00", "frozenChange": "-13500.00"
}
```

审计 `sideEffects[]`：被改正的是 `EXP` 记录时 `action` 为 `"EXP"`，`text` 为 `到期作废 #<id> 冻结变动 −24.70 → −24.69`（平仓仍为 `交易 #3 BTC 冻结变动 …`）。

#### 与补录 / 修改 / 删除其它交易的交互（FEAT-001 / 004 / 005）
`EXP` 作为"一笔平掉 N 张的记录"参与这些写操作的合约重放（规则同 FEAT-004 补录、FEAT-005 修改/删除），违规时 422，code 按违规种类：`NO_POSITION_TO_CLOSE`（作废时没有持仓）、`CLOSE_EXCEEDS_POSITION`（作废张数超过当时持仓）、`CLOSE_NOT_WHOLE_LOTS`（不再是整笔批次）、`MULTIPLIER_MISMATCH`（与当时持仓 multiplier 不同）；在作废之后录入的同一时间开仓因删除作废而与恢复的持仓冲突时为 `POSITION_DIRECTION_CONFLICT`（FEAT-001 原文案）。合约检查先于现金检查。

拒绝文案（`<改动>` 为 `补录` / `修改` / `删除`，时间为美东 `YYYY-MM-DD HH:mm`）：
- 被修改 / 删除的开仓正是该 `EXP` 作废的批次之一：`该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（<改动>后作废将超过当时持仓 2 张），请先删除该到期作废`；括号内其它原因：`<改动>后作废将没有持仓可作废`、`<改动>后作废将不是整笔批次（最早一笔是 2 张，可为 2 张、4 张或 5 张）`、`<改动>后作废将与当时持仓的 multiplier 10 不一致`
- 其它情况：`<改动>后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废`；`… 张将没有持仓可作废，请先删除该到期作废`；`… 张将不是整笔批次（最早一笔是 2 张，可为 2 张、4 张或 5 张），请先删除该到期作废`；multiplier：`<改动>后该合约（…）在 2026-11-20 16:00 的到期作废 multiplier 为 100，与当时持仓的 multiplier 10 不一致，请先删除该到期作废`
- "可为 …"列出可以作废的张数（最早 1 笔、2 笔……整批次的累计张数），格式同平仓的"可平 …"

注意排序：在作废之后**录入**的、成交时间正好 = cutoff 的交易排在 `EXP` 之后（同一时间按 id），所以这样的平仓会因"在成交时没有持仓"被拒（`NO_POSITION_TO_CLOSE`，FEAT-004 原文案），这样的开仓成为新的持仓批次（可以再作废）；修改一笔已有交易时它保留原 id（录入顺序），时间改为 cutoff 时排在 id 更大的 `EXP` 之前。

#### 交易成交时间收紧（Q83，FEAT-001 / FEAT-004 / FEAT-005）
- 开仓（`POST /api/option-trades`）、平仓（`POST /api/option-trades/closes`）：成交日 = 到期日且成交时间晚于 cutoff（16:00:00 允许，16:00:01 起拒绝）→ 400 `VALIDATION_ERROR`，`details`：`{"field": "tradedAtLocal", "message": "成交时间不能晚于到期日（2026-11-20）美东 16:00"}`。成交日晚于到期日的原有错误不变（开仓 `expirationDate`：`到期日不能早于成交日（…）`；平仓 `tradedAtLocal`：`到期日（…）之后不能平仓`），此时不再另报 16:00 的错误。与"晚于当前时间"等其它时间错误一起放在 `details` 中（同 FEAT-001：与时间有关的检查在其它字段都通过后进行）。
- 修改交易（`PUT /api/option-trades/{id}`）：**只有成交时间被改动时**才按上面规则检查改后的时间（同一 `field`、同一文案）；不改成交时间时不检查（Q85：FEAT-012 之前录入的 16:00 之后交易可以只改净额、备注、张数等）。成交日晚于到期日的原有检查不变。
- 检查只针对被新增/修改的那一笔；重放时其它已存储的交易（包括旧的 16:00 之后交易）不因时间规则被拒。入金/出金不受影响。

示例（400）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/option-trades/closes",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [ { "field": "tradedAtLocal", "message": "成交时间不能晚于到期日（2026-11-20）美东 16:00" } ]
}
```

#### `GET /api/option-positions` 的变化
`closable` 口径改变，并新增字段（兼容变更）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `positions[].closable` | boolean | **改为**：服务器当前时间 < cutoff（到期日美东 16:00）。到期日 16:00 起为 `false` |
| `positions[].expiryPending` | boolean | 新增。"到期待确认"：cutoff ≤ 当前时间 < 到期日美东 17:30。此时 `closable`、`expirable` 都为 `false` |
| `positions[].expirable` | boolean | 新增。可以作废：当前时间 ≥ 到期日美东 17:30（含到期日之后的任何时间）。"作废"按钮按它显示；后端不预先判断"旧的 16:00 之后交易"冲突（点确认后由 `POST /api/option-trades/expirations` 拒绝） |
| `positions[].expiryContracts` | number | 新增。作废张数 = cutoff 时点该合约的持仓张数（作废请求的 `contracts` 原样用它）。没有旧的 16:00 之后交易时等于 `contracts` |
| `positions[].expiryAt` | string | 新增。作废会记在的时间 = cutoff，ISO-8601 UTC（如 `"2026-11-20T21:00:00Z"`） |
| `positions[].expiryAtLocal` | string | 新增。同一时间的美东本地时间 `YYYY-MM-DDTHH:mm:ss`（如 `"2026-11-20T16:00:00"`），确认框显示 `2026-11-20 16:00（美东）` |

三个布尔值任一时刻至多一个为 `true`（到期日 16:00 之前：只有 `closable`；到期日 16:00–17:30 前：只有 `expiryPending`；17:30 起：只有 `expirable`）。`dte`、`expirationStatus`、`daysPastExpiration` 的口径不变（到期日当天 17:30 之后仍是 `EXPIRES_TODAY`）。确认框中的"开仓净额合计""将释放的冻结"直接用该持仓的 `openingNetTotal`、`frozenCash`（没有旧的 16:00 之后交易时，作废的就是全部这些批次）。

示例（时钟 2026-11-23 10:00 美东，空头 put 已过期）：
```json
{
  "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
  "contractLabel": "XYZ 2026-11-20 45 P", "direction": "SHORT", "contracts": 3, "multiplier": 100,
  "openingNetTotal": "370.00", "averageNetPerContract": "123.33", "frozenCash": "13500.00",
  "dte": -3, "expirationStatus": "EXPIRED", "daysPastExpiration": 3, "lotCount": 2, "lots": [],
  "closeAction": "BTC", "allowedCloseContracts": [2, 3], "closable": false,
  "expiryPending": false, "expirable": true, "expiryContracts": 3,
  "expiryAt": "2026-11-20T21:00:00Z", "expiryAtLocal": "2026-11-20T16:00:00"
}
```
（`lots` 省略。）

#### `GET /api/closed-trades` 的变化
- `EXP` 记录作为一行：`action` `"EXP"`，`closedDirection` = 被作废持仓方向（按重放，`SHORT` / `LONG`），`tradedAt` = cutoff，`closeNetAmount` / `closeNetPerContract` = `"0.00"`，`openingNetTotal` / `openingNetPerContract`、`realizedPnl`（= `openingNetTotal`）、`coveredLots` 同平仓，`note` = `null`，`hasAuditLog` = `false`。进入 `filteredCount` 和 `filteredRealizedPnlTotal`。
- `action` 筛选接受 `BTC` / `STC` / `EXP`（可多选）；不传 = 三者都要。其它值 → 400 `BAD_REQUEST`，`方向只能是 BTC、STC、EXP`。
- `startDate` / `endDate` 按 cutoff 的美东日期筛选。

#### `GET /api/pnl-summary`、`GET /api/dashboard`
没有新字段。`EXP` 与平仓一样计入（按 cutoff 的美东日期 / 月份归属，Q63）：笔数、总计、本月、按标的、按月。`expiredUnprocessedCount` 仍是 `dte < 0` 的持仓个数，作废后该持仓消失、个数随之减少。

#### `GET /api/cash/summary`、`GET /api/cash/ledger`
现金汇总按流水重放，`OPTION_EXPIRE` 流水从 cutoff 起减少冻结。流水行见上（`sourceType` `OPTION_EXPIRE`，`amount` `"0.00"`，`balanceAfter` 不变，`frozenChange` 为负，`description` 如 `到期作废 3 张 XYZ 2026-11-20 45 P`，`remark` `null`，`hasAuditLog` `false`）。`OPTION_EXPIRE` 流水没有修改/删除接口（到交易列表删除作废记录，`sourceId` 即作废记录 id）。

#### `GET /api/audit-log`
没有新字段。`EXP` 的删除记为 `objectType` `OPTION_TRADE`（与交易共用 id），摘要以 `到期作废` 开头，见上。

#### 已知限制
- 只有旧的 16:00 之后**开仓**、cutoff 时点没有持仓的合约（FEAT-012 之前录入的数据）：持仓的 `expiryContracts` 为 `0`、`expirable` 照常为 `true`；作废请求返回 `NO_POSITION_TO_EXPIRE`（检查顺序第 3 步先于第 5 步）。
- 有旧的 16:00 之后交易时，持仓的 `contracts`（当前张数）与 `expiryContracts`（cutoff 时点张数）可能不同；请求必须传 `expiryContracts`，才能通过第 4 步并在第 5 步得到 `EXPIRY_BLOCKED_BY_LATE_TRADE`（传当前张数会先得到 `POSITION_CHANGED`）。把那笔交易的时间改为 16:00 或之前（或删除它）后即可正常作废。

