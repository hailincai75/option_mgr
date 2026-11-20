# FEAT-051 平仓时指定批次（每笔平仓保存所选批次；Q39 自动重新匹配取消）

口径：Q255 A（每笔 BTC / STC 都保存所选批次，已有平仓按现在的 FIFO 结果写入；Q39 的"补录后之后的平仓重新匹配"取消）、Q256（已有平仓的所选批次可修改，写审计）、Q257（录入交易页仍按张数 FIFO，保存匹配到的批次）、Q258（表单批次信息、默认最早 1 笔）、Q259 / Q260（股票卖出、作废、被指派、行权不选批次）。Q21 的"整笔"不变（不能部分平掉一笔批次）。示例中 ZZQ 是虚构标的，甲（账户 id 2）/ 乙（账户 id 3）是虚构账户。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades/closes`（FEAT-004） | 请求可带 `lotTradeIds`（所选批次）；带了时 `contracts` 可省略 | 新错误码 `CLOSE_LOTS_INVALID`；补录不再让之后的平仓重新匹配（见「Q39 取消后的重放」） |
| `PUT /api/option-trades/{id}`（FEAT-005） | 平仓可带 `lotTradeIds` 修改所选批次；平仓的 `contracts` 可省略 | 修改 / 删除开仓时，引用它的平仓不再重新匹配，改为 `CLOSE_LOTS_INVALID` 拒绝；审计 `before` / `after` / `changes` 新增 `selectedLots` |
| `DELETE /api/option-trades/{id}`（FEAT-005） | — | 删除被平仓选中的开仓 → `CLOSE_LOTS_INVALID`（原来按违规种类给 `CLOSE_EXCEEDS_POSITION` 等，或在 FIFO 能改配时允许） |
| `POST /api/option-trades`（FEAT-001 开仓补录） | — | 补录更早的开仓不再改变之后平仓的被平批次（原来可能 `CLOSE_NOT_WHOLE_LOTS` 拒绝或改配） |
| **新** `GET /api/option-trades/{id}/close-lot-choices` | 修改平仓表单用的可选批次 | — |
| `GET /api/option-positions`（FEAT-003，含 `ALL`）、FEAT-043 调整响应 `position` | `lots[].closeDefault` | 持仓 / 批次按保存的所选批次扣除（结果对已有数据不变） |
| `GET /api/option-trades`、`/{id}`、`GET /api/closed-trades` | — | `coveredLots` / `realizedPnl` / `frozenChange` = 保存的所选批次，不再随补录变化；字段与格式不变 |
| `GET /api/audit-log` | 平仓记录的 `before` / `after` 新增 `selectedLots`，`changes` 可出现 `selectedLots` | — |

不变：作废（`EXP`）、被指派（`ASG`）、行权（`EXR`）仍按"最早整批次"在读取时重放得出（Q260，规则 7），作用在所选批次平掉之后剩下的批次上，它们的释放冻结 / 股票成本仍由原有改正机制（`frozenCorrections` / `costCorrections`）维护；股票卖出、call 被指派交股仍按股票 FIFO（Q259）。全部账户（`ALL`）下所有写接口仍 400 `ALL_ACCOUNTS_READ_ONLY`。

---

#### 概念

- **所选批次（规则 1）**：一笔 BTC / STC 平掉的 1 笔或多笔开仓批次，用开仓交易 id 表示，可以不连续。每个所选批次必须：是本账户、本合约的开仓（BTO / STO）；在本笔成交时点已开仓（按 `(成交时间, 交易 id)`，同一时间先录入的开仓在前，Q19；新录入的平仓排在同一时间已有交易之后）；在本笔之前没有被其它平仓 / 作废 / 被指派 / 行权平掉；不重复；至少 1 笔。
- **张数 = 所选批次张数之和（规则 2）**；不能部分平掉一笔批次（Q21）。
- **保存（规则 5、Q255 A）**：每笔 BTC / STC 都在库里保存它的所选批次（表 `option_close_lot`，见「迁移」）。之后任何重放（补录、修改、删除）都用保存的批次，不再按 FIFO 重新匹配。
- **计算（规则 4）**：已实现盈亏 = 所选批次开仓净额（带现金符号）之和 + 本笔 `cashChange`（Q21 公式不变）；BTC 释放冻结 = 所选批次冻结之和（Q20，先释放再查现金）；剩余批次 = 未被选中的批次；FEAT-039 覆盖释放 = 所选批次中受检（`CHECKED`）批次的股数（`LEGACY` 批次释放 0，Q79）；FEAT-048 批次未实现盈亏按剩余批次计算。
- **默认（规则 3）**：持仓的最早 1 笔批次（`lots[0]`）标 `closeDefault: true`。

#### Q39 取消后的重放（补录 / 修改 / 删除）

合约重放仍按 `(成交时间, 交易 id)`。一笔**已保存所选批次的平仓**在重放中的检查顺序：
1. 当时没有持仓 → `NO_POSITION_TO_CLOSE`；方向不对 → `POSITION_DIRECTION_CONFLICT`（文案同 FEAT-004 / FEAT-005，不变）
2. multiplier 不一致 → `MULTIPLIER_MISMATCH`（不变）
3. 所选批次（按开仓时间顺序逐个）：在该平仓成交时还未开仓 / 已被更早的记录平掉 / 张数被改得与平仓张数不一致 → `CLOSE_LOTS_INVALID`

原来的 `CLOSE_EXCEEDS_POSITION`、`CLOSE_NOT_WHOLE_LOTS` 只再用于**不带 `lotTradeIds` 的新平仓（FIFO 模式）**和作废 / 被指派 / 行权。结果：
- 补录**开仓**（任何时间）不会改变已有平仓的被平批次、盈亏、释放冻结；只要不造成多空冲突 / multiplier 不一致就允许（例：已有 BTC 2 张平掉 L1，补录更早的 STO 1 张 → 现在**允许**，它成为剩余批次）。
- 补录**平仓**若选中（或按 FIFO 匹配到）之后某笔平仓已选的批次 → 拒绝，提示指出之后那笔平仓和批次（不再自动改配）。
- 修改开仓的张数、或把开仓时间改到选中它的平仓之后、或删除被选中的开仓 → 拒绝；只改开仓净额 / 备注（及不越过平仓的时间）允许，选中它的平仓的盈亏随之变化。
- 删除平仓：它的所选批次恢复为持仓（与现在相同）。
- BTC / STC 的释放冻结只取决于所选批次的冻结，而开仓冻结只在张数 / multiplier 变化时变化（这两种改动对被选中的开仓都会被拒绝），所以 **BTC 的流水 `frozenChange` 不再被连带改正**；`EXP` / `ASG` 的改正与 `sideEffects` 照旧。

---

#### `POST /api/option-trades/closes`（变化）

请求体新增 `lotTradeIds`：

```json
{
  "symbol": "ZZQ",
  "expirationDate": "2026-12-18",
  "strike": "9",
  "optionType": "CALL",
  "action": "BTC",
  "lotTradeIds": [2],
  "netAmount": "15.10",
  "tradedAtLocal": "2026-11-19T10:00",
  "note": null
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `lotTradeIds` | number[] \| null | 否 | **带了（非 `null`）= 指定批次模式**：所选批次的开仓交易 id，顺序无关。空数组 → 400 `VALIDATION_ERROR`（field `lotTradeIds`，`请至少选择一笔开仓批次`）；元素为 `null` 或 ≤ 0 → 400 `VALIDATION_ERROR`（field `lotTradeIds`，`批次必须是开仓交易 id`）；元素不是整数 → 400 `BAD_REQUEST`；最多 1000 个（`最多选择 1000 笔批次`）。其余规则在第 3 步检查（见下）。**不带或 `null` = FIFO 模式（Q257，录入交易页）**：与原来完全相同，按 `contracts` 匹配成交时点最早 1 笔或连续多笔整批次 |
| `contracts` | number | 指定批次模式：否；FIFO 模式：是 | 规则同 FEAT-004（正整数，≤ 100000）。指定批次模式下可省略（= 所选批次张数之和）；带了且 ≠ 所选批次张数之和 → 422 `CLOSE_LOTS_INVALID`。FIFO 模式下缺少 → 400 `VALIDATION_ERROR`（`张数不能为空`，同原来） |
| 其它字段 | | | 同 FEAT-004 |

前端持仓页平仓对话框用指定批次模式（一个都不勾时不提交）；录入交易页的 BTC / STC 不带 `lotTradeIds`（Q257）。两种模式都把最终匹配到的批次保存为本笔的所选批次。

检查顺序（FEAT-004，任一失败整笔拒绝、什么都不写）：
1. 字段校验 → 400
2. 成交时点有持仓、方向正确 → 422 `NO_POSITION_TO_CLOSE` / `POSITION_DIRECTION_CONFLICT`（不变）
3. 批次：
   - 指定批次模式，按下列顺序，每项按 `lotTradeIds` 的顺序逐个检查，报告第一个问题 → 422 `CLOSE_LOTS_INVALID`：
     a. 不是本账户该合约的开仓交易（不存在、属于别的账户、别的合约、或是平仓 / 作废等记录）：`所选批次 #3 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易`（只给 id，不透露别的账户的内容）
     b. 重复：`所选批次（2026-11-11 10:00 STO 1 张）重复选择`
     c. 在成交时点还未开仓：`所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓`
     d. 在成交时点之前已被平掉：`所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉`（平掉它的记录：`BTC 1 张` / `STC 1 张` / `到期作废 1 张` / `被指派 1 张` / `行权 1 张`）
     e. 带了 `contracts` 且不等于所选批次张数之和：`平仓张数 2 张与所选批次张数之和 1 张不一致`
   - FIFO 模式：不超过持仓、是最早若干笔整批次之和 → 422 `CLOSE_EXCEEDS_POSITION` / `CLOSE_NOT_WHOLE_LOTS`（不变）。"最早若干笔"取成交时点剩余的批次（已被更早平仓选走的不算），**不排除**之后的平仓已选的批次——选到了会在第 4 步被拒
4. 之后的已有记录仍然合法（见「Q39 取消后的重放」）→ 422，code 按违规种类，文案见下「之后的平仓违规文案」
5. FEAT-039 覆盖（E3）→ 422 `COVERAGE_INSUFFICIENT`（不变）
6. 现金 → 422 `CASH_INSUFFICIENT`（Q20 先释放所选批次冻结，不变）

批次描述 `<批次>` 统一为 `<开仓美东时间 YYYY-MM-DD HH:mm> <BTO|STO> <张数> 张`，如 `2026-11-11 10:00 STO 1 张`（张数为该开仓交易**当前**的张数）。

响应 `201 Created`：交易表示不变（FEAT-004 / FEAT-046），`coveredLots` = 所选批次（按开仓 `(成交时间, 交易 id)` 升序），`contracts` = 所选批次张数之和。验收 2（场景 Z，甲：A = #1、B = #2，入金流水 #1、A #2、B #3）：

```json
{
  "accountId": 2,
  "accountName": "甲",
  "id": 3,
  "symbol": "ZZQ",
  "expirationDate": "2026-12-18",
  "strike": "9",
  "optionType": "CALL",
  "action": "BTC",
  "contracts": 1,
  "multiplier": 100,
  "netAmount": "15.10",
  "tradedAt": "2026-11-19T15:00:00Z",
  "tradedAtLocal": "2026-11-19T10:00:00",
  "cashChange": "-15.10",
  "frozenChange": "0.00",
  "contractLabel": "ZZQ 2026-12-18 9 C",
  "note": null,
  "ledgerEntryId": 4,
  "createdAt": "2026-11-20T15:00:00Z",
  "coveredLots": [
    {
      "tradeId": 2,
      "action": "STO",
      "tradedAt": "2026-11-11T15:00:00Z",
      "tradedAtLocal": "2026-11-11T10:00:00",
      "contracts": 1,
      "netAmount": "37.34",
      "frozen": "0.00"
    }
  ],
  "realizedPnl": "22.24",
  "hasAuditLog": false,
  "expiredDirection": null,
  "assignment": null,
  "exercise": null
}
```

之后：现金汇总 `1040.58 / 0.00 / 1040.58`；持仓 ZZQ 2026-12-18 9 C 空头 1 张，`lots` 只有 #1（`2026-11-10T10:00:00`、`18.34`）；股票 ZZQ 被覆盖 100、可用 100。

验收 4（场景 P：P1 = #1 STO 2 张 冻结 1,600.00，P2 = #2 STO 1 张 冻结 800.00）：`lotTradeIds: [2]`、`netAmount: "9.65"` → `contracts` 1、`frozenChange` `"-800.00"`、`realizedPnl` `"11.35"`，现金汇总 `30041.35 / 1600.00 / 28441.35`；另起场景 `lotTradeIds: [1, 2]`、`"20.00"` → `contracts` 3、`frozenChange` `"-2400.00"`、`realizedPnl` `"31.00"`，现金汇总 `30031.00 / 0.00 / 30031.00`。

错误示例（验收 5，场景 Z，乙的 C = #3）：

```json
{
  "code": "CLOSE_LOTS_INVALID",
  "message": "所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓",
  "status": 422,
  "path": "/api/option-trades/closes",
  "timestamp": "2026-11-20T15:00:00Z",
  "details": []
}
```

| 请求（甲） | 结果 |
|-----------|------|
| `lotTradeIds: [2]`，成交 2026-11-10 15:00 | 422 `CLOSE_LOTS_INVALID`：`所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓` |
| `lotTradeIds: []` | 400 `VALIDATION_ERROR`，`details`：`[{ "field": "lotTradeIds", "message": "请至少选择一笔开仓批次" }]` |
| `lotTradeIds: [3]`（乙的批次） | 422 `CLOSE_LOTS_INVALID`：`所选批次 #3 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易` |
| `lotTradeIds: [2, 2]` | 422 `CLOSE_LOTS_INVALID`：`所选批次（2026-11-11 10:00 STO 1 张）重复选择` |
| 验收 2 之后再 `lotTradeIds: [2]`，成交 2026-11-20 09:00 | 422 `CLOSE_LOTS_INVALID`：`所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉` |
| `lotTradeIds: [1]`、`contracts: 2` | 422 `CLOSE_LOTS_INVALID`：`平仓张数 2 张与所选批次张数之和 1 张不一致` |

#### 之后的平仓违规文案（补录 / 修改 / 删除，`CLOSE_LOTS_INVALID`）

格式 `<动作>后，<平仓美东时间> <BTC|STC> <张数> 张 <合约> 选中的批次 <批次> <原因>`，`<动作>` 为 `补录` / `修改` / `删除`（与 FEAT-004 / FEAT-005 相同），`<批次>` 用改动**之后**的开仓时间与张数：
- 已被更早的记录平掉：`补录后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 1 张 已被更早的平仓平掉`（规则 5 的例子；被作废 / 被指派 / 行权平掉时为 `已被更早的到期作废平掉` / `已被更早的被指派平掉` / `已被更早的行权平掉`）
- 开仓时间改到平仓之后：`修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-19 11:00 STO 1 张 在该平仓成交时还未开仓`
- 开仓张数被改：`修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 2 张 张数与平仓不一致（所选批次共 2 张，平仓 1 张）`
- 删除被选中的开仓（取代 FEAT-005 的 `deletedOpenStillClosed` 文案，开头和结尾不变）：`该开仓被 2026-11-19 10:00 的 BTC 1 张平掉（所选批次 2026-11-11 10:00 STO 1 张），请先修改或删除那笔平仓`

一次改动造成多处违规时，按重放顺序报告第一处（同 FEAT-004 / FEAT-005）。

验收 6（验收 2 之后）：
- `PUT /api/option-trades/2` 把 B 的时间改为 `2026-11-19T11:00` → 422 `CLOSE_LOTS_INVALID`：`修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-19 11:00 STO 1 张 在该平仓成交时还未开仓`
- `DELETE /api/option-trades/2` → 422 `CLOSE_LOTS_INVALID`：`该开仓被 2026-11-19 10:00 的 BTC 1 张平掉（所选批次 2026-11-11 10:00 STO 1 张），请先修改或删除那笔平仓`
- 补录 `lotTradeIds: [2]`、成交 `2026-11-15T10:00` → 422 `CLOSE_LOTS_INVALID`：`补录后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 1 张 已被更早的平仓平掉`；改 `lotTradeIds: [1]`、`"10.00"` → 201，`realizedPnl` `"8.34"`；#3 仍是 B、`"22.24"`；持仓中没有该合约；现金汇总 `1030.58 / 0.00 / 1030.58`

---

#### `PUT /api/option-trades/{id}`（平仓的变化，Q256）

平仓（BTC / STC）的请求体：

```json
{
  "lotTradeIds": [2],
  "netAmount": "15.10",
  "tradedAtLocal": "2026-11-19T10:00",
  "note": null
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| `lotTradeIds` | 否 | 新的所选批次（整体替换），字段规则同新增平仓。**不带或 `null` = 所选批次不变** |
| `contracts` | 平仓：否（开仓仍必填，规则同 FEAT-005） | 平仓可省略（= 所选批次张数之和）；带了且 ≠ 之和 → 422 `CLOSE_LOTS_INVALID`（`平仓张数 2 张与所选批次张数之和 1 张不一致`）。所以只改张数不改批次的旧请求会被拒绝 |
| `netAmount` / `tradedAtLocal` / `note` | 同 FEAT-005 | 不变；改时间时所选批次按新时间检查 |

检查顺序沿用 FEAT-005：字段 → 被修改的平仓本身（有持仓 / 方向 → 所选批次 a–e，文案同新增平仓，"成交时间"为修改后的时间）→ 该合约其它记录（`修改后，…` 文案）→ FEAT-039 覆盖（换批次会改变释放的覆盖股数，可能 E3"修改"）→ 现金（换批次会改变释放冻结）。`NO_CHANGES` 比较时所选批次按集合比较（顺序无关）。

响应 `200 OK`：修改后的交易表示。验收 7（场景 Z 验收 3 之后，平仓 #3 当前选 #1）：`{"lotTradeIds": [2], "netAmount": "15.10", "tradedAtLocal": "2026-11-19T10:00", "note": null}` → `coveredLots` 为 #2、`realizedPnl` `"22.24"`、`hasAuditLog` `true`；持仓剩 #1 `18.34`；现金汇总不变 `1040.58 / 0.00 / 1040.58`。场景 P（验收 4 第一种之后，平仓 #3 选 #2）改 `lotTradeIds: [1]` → `contracts` 2、`frozenChange` `"-1600.00"`、`realizedPnl` `"20.35"`，现金汇总 `30041.35 / 800.00 / 29241.35`。

**审计**（`GET /api/audit-log`）：平仓的 `before` / `after` 新增 `selectedLots`（数组，按开仓时间顺序；每项 `tradeId`、`action`、`tradedAt`、`tradedAtLocal`、`contracts`）。所选批次变化时 `changes` 多一项（排在最前）：

```json
{
  "field": "selectedLots",
  "label": "被平批次",
  "before": "2026-11-10 10:00 1 张",
  "after": "2026-11-11 10:00 1 张",
  "text": "被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张"
}
```

多个批次用 `、` 连接，如 `2026-11-10 10:00 2 张、2026-11-11 10:00 1 张`。张数、冻结变动随之变化时照常另列 `contracts`（`张数：1 → 2`）、`frozenChange`（`冻结变动：−800.00 → −1,600.00`）。删除平仓的 `before` 也带 `selectedLots`。本需求之前写入的审计记录没有 `selectedLots`，原样返回，不补写。开仓、入金出金的审计内容不变。

#### `GET /api/option-trades/{id}/close-lot-choices`（新）

修改平仓表单的可选批次（Q256）：该平仓在某个成交时点**可以选**的批次——把本笔从重放中拿掉后，该时点仍持有的批次，再去掉之后其它 BTC / STC 已保存选中的批次。按开仓 `(成交时间, 交易 id)` 升序。只读，要求 `X-Account-Id`（`ALL` → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`）。

| query 参数 | 类型 | 规则 |
|------------|------|------|
| `tradedAtLocal` | string | 可选：美东本地时间（同 FEAT-001 的格式），表单改了成交时间时传新值；不传 = 本笔当前成交时间。无法解析 → 400 `BAD_REQUEST`（field `tradedAtLocal`） |

响应 `200 OK`（验收 7，场景 Z 验收 3 之后，平仓 #3）：

```json
{
  "closeTradeId": 3,
  "tradedAt": "2026-11-19T15:00:00Z",
  "tradedAtLocal": "2026-11-19T10:00:00",
  "lots": [
    {
      "tradeId": 1,
      "action": "STO",
      "tradedAt": "2026-11-10T15:00:00Z",
      "tradedAtLocal": "2026-11-10T10:00:00",
      "contracts": 1,
      "netAmount": "18.34",
      "frozen": "0.00",
      "selected": true
    },
    {
      "tradeId": 2,
      "action": "STO",
      "tradedAt": "2026-11-11T15:00:00Z",
      "tradedAtLocal": "2026-11-11T10:00:00",
      "contracts": 1,
      "netAmount": "37.34",
      "frozen": "0.00",
      "selected": false
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `closeTradeId` | 平仓交易 id |
| `tradedAt` / `tradedAtLocal` | 计算所用的成交时点（参数或本笔当前时间） |
| `lots[]` | 字段同 `coveredLots`（`netAmount` 带现金符号，`frozen` 为该批次冻结）+ `selected`：是否为本笔当前的所选批次。时间改动后本笔当前所选批次若在该时点不可选，就不在列表里（前端去掉其勾选）。不带未实现盈亏（被本笔平掉的批次当前没有持仓估值） |

之后的作废 / 被指派 / 行权按 FIFO 动态匹配，不从列表里排除；选中会让它们不成立的批次时，保存会按原有文案被拒。

错误：`id` 不是整数 → 400 `BAD_REQUEST`；不存在 / 别的账户 → 404 `NOT_FOUND`；不是 BTC / STC → 422 `BUSINESS_RULE_VIOLATION`（`只有平仓（BTC / STC）可以选择批次`）。

#### `GET /api/option-positions`（新增字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `positions[].lots[].closeDefault` | boolean | 平仓表单默认勾选的批次（规则 3）：该持仓的第一个批次（最早开仓）为 `true`，其它 `false` |

持仓对话框用 `lots[]` 已有的 `tradeId`、`action`、`tradedAtLocal`、`remainingContracts`、`remainingNetAmount`、`remainingFrozen` 和 FEAT-048 的 `unrealizedPnl` / `unrealizedLabel`（Q258）。`allowedCloseContracts` 保留（含义不变：剩余批次按顺序的累计张数，FIFO 模式 / 指派 / 行权选项仍用），持仓页平仓对话框不再用它。`X-Account-Id: ALL` 的行、FEAT-043 调整响应的 `position.lots[]` 同样带 `closeDefault`。验收 1：ZZQ 2026-12-18 9 C 行 `lots` 为 #1（`2026-11-10T10:00:00`、1 张、`18.34`、`closeDefault: true`）、#2（`2026-11-11T10:00:00`、1 张、`37.34`、`false`），`closeAction` `BTC`。

#### 已平仓、按 id 查询、交易列表

字段不变。`coveredLots` = 保存的所选批次；`openingNetTotal` / `realizedPnl` 按它计算（验收 2：已平仓行 `openingNetTotal` `"37.34"`、`realizedPnl` `"22.24"`、`coveredLots` 为 #2；`ALL` 下 `accountName` `"甲"`）。

---

#### 迁移（规则 6、Q255 A）

- **`V17__option_close_lot.sql`**（新表，只新增）：
  ```sql
  CREATE TABLE option_close_lot (
      close_trade_id BIGINT NOT NULL,
      lot_trade_id   BIGINT NOT NULL,
      account_id     BIGINT NOT NULL,
      CONSTRAINT pk_option_close_lot PRIMARY KEY (close_trade_id, lot_trade_id),
      CONSTRAINT uq_option_close_lot_lot UNIQUE (lot_trade_id),
      CONSTRAINT fk_option_close_lot_close FOREIGN KEY (close_trade_id) REFERENCES option_trade (id),
      CONSTRAINT fk_option_close_lot_lot FOREIGN KEY (lot_trade_id) REFERENCES option_trade (id),
      CONSTRAINT fk_option_close_lot_account FOREIGN KEY (account_id) REFERENCES account (id)
  );
  CREATE INDEX ix_option_close_lot_account ON option_close_lot (account_id);
  ```
  只有 BTC / STC 有行（`EXP` / `ASG` / `EXR` 不存，仍按 FIFO 重放）。一笔开仓最多被一笔平仓选中（唯一约束）。删除平仓时先删它的行；被选中的开仓不能删（服务层先拒绝，外键兜底）。
- **`V18__backfill_option_close_lots`**（Flyway Java 迁移，`backend/src/main/java/db/migration/`）：对每个账户、每个合约（标的 + 到期日 + 行权价 + C/P），按 `(traded_at, id)` 读出全部期权记录，用**迁移类自带的**最小 FIFO 整批次匹配（不调用应用的 `ContractReplay`，以免以后领域代码变化改变迁移结果）：开仓入队；BTC / STC / `EXP` / `ASG` / `EXR` 从队首取整笔批次直到张数正好相等；对 BTC / STC 写入 `(close_trade_id, lot_trade_id, account_id)`。以下任一情况抛异常（迁移失败、应用不启动，用 Q60 的迁移前备份恢复）：取不到整批次 / 张数对不上；BTC 的流水 `frozen_change` ≠ −所选批次开仓流水 `frozen_change` 之和（现有数据由 Q39 改正保持一致，不应出现）。
- 不修改任何已有表的行：`option_trade`、`cash_ledger_entry`、`stock_trade`、`audit_record` 的内容与 id 都不变，所以迁移前后每笔平仓的被平批次、已实现盈亏、现金流水、冻结、持仓、已平仓记录完全一致（验收 8：FEAT-004 场景 A + BTC 2 张 241.30 → 迁移后被平批次仍为 L1、`realizedPnl` `"457.40"`，现金汇总 `80817.40 / 20000.00 / 60817.40`，持仓剩 L2 1 张，流水与 id 不变）。
- Q60：有待执行迁移时先备份（备份名 `…-backup-before-V18-…`）。测试专用的失败迁移 `db/failing-migration/V17__simulated_failure.sql` 改名为 `V19__simulated_failure.sql`，迁移测试里写死的备份名改为 `before-V18`。
- 迁移测试（实现阶段）：同 `MigrationFromM1Test` 的做法，`target=16` 建库并写入旧版本数据（多账户；单批次 / 多批次平仓；同一合约开平多轮；`EXP` / put `ASG` / `EXR` 夹在平仓之间；曾被 Q39 改配过的平仓；Q28 取整的冻结），升级后逐个接口比较 JSON 完全相同，并检查 `option_close_lot` 的内容。
- e2e 重置按元数据自动包含新表（无环外键）。

#### 错误码

| code | HTTP | 何时 |
|------|------|------|
| `CLOSE_LOTS_INVALID` | 422 | 新增 / 修改平仓的所选批次不满足规则 1、2；补录 / 修改 / 删除使某笔已有平仓的所选批次不再成立（规则 5）。文案见上 |

其余错误码不变。`ErrorCode` 新增 `CLOSE_LOTS_INVALID`。
