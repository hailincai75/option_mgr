# FEAT-004 录入平仓交易（BTC / STC）

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
- **到期（Q36）**：成交时间的美东日期不得晚于到期日；到期日当天任何时刻都可以平仓。**FEAT-012 起收紧（Q83）**：成交时间不得晚于到期日美东 16:00:00，见 FEAT-012「交易成交时间收紧」。

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
  "realizedPnl": "457.40",
  "hasAuditLog": false
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
| `hasAuditLog` | boolean | 该笔交易被修改过时为 `true`（存在交易上的标记；即审计里有 `objectType=OPTION_TRADE&objectId=<id>`；新增不审计，所以 `POST` 的响应恒为 `false`；`PUT` 成功的响应恒为 `true`）。**后加字段（兼容变更），见"列表行的 `hasAuditLog`"** |

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
| `positions[].closable` | boolean | ~~今天（`asOfDate`）≤ 到期日时为 `true`~~ **FEAT-012 起：当前时间 < 到期日美东 16:00 时为 `true`**（到期日当天 16:00 起、已过期为 `false`）。平仓按钮按它显示 |

持仓、批次、冻结合计按 FIFO 扣掉被平批次后给出：被整笔平掉的批次不再出现在 `lots` 中；净张数为 0 的合约不再出现在 `positions` 中。批次的 `remaining*` 字段在整笔平仓规则下总是等于 `original*`。

