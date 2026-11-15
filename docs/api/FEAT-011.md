# FEAT-011 股票交易与股票持仓

> **FEAT-035 修订**：`GET /api/stock-positions` 接受 `X-Account-Id: ALL`，行新增 `accountId`、`accountName`（单账户也有值），`positionCount` 为跨账户的**不同标的数**（同一标的在两个账户分两行，但只算 1 个；单账户不变）；`/api/stock-trades/**` 不接受 `ALL`。见 [FEAT-035.md](FEAT-035.md)。
>
> **FEAT-046 修订**：`GET /api/stock-trades`（列表）接受 `ALL`：参数、校验、错误不变，排序原规则 + `accountId` 升序，合并后分页；股票记录表示（所有接口）新增 `accountId`、`accountName`（单账户也有值）。`GET /api/stock-trades/{id}` 与写接口仍不接受 `ALL`。见 [FEAT-046.md](FEAT-046.md)。

**兼容性**：新增接口、新增枚举值（流水 `sourceType` 的 `STOCK_BUY` / `STOCK_SELL`、审计 `objectType` 的 `STOCK_TRADE`）属于兼容变更，已有接口的路径、字段、数字都不变（期权交易列表、期权持仓、已平仓、盈亏汇总、首页**不含**股票，股票盈亏并入在 FEAT-040）。**FEAT-040 起**已平仓、盈亏汇总、首页包含股票卖出的已实现盈亏，见「FEAT-040」。数据库迁移 `V8__stock_trade.sql` 新建表 `stock_trade`、放宽审计对象类型的检查约束，不改任何已有数据。所有接口都要求 `X-Account-Id`（FEAT-033），只读写该账户。

#### 概念
- **股票记录**：三种 `type` 之一：`BUY`（买入）、`SELL`（卖出）、`TRANSFER_IN`（转入 / 期初持仓）。只记**股数 + 含手续费的金额**（Q67）：买入 `amount` = 实付（含手续费），卖出 = 实收（扣手续费后），转入 = 总成本。用户只填正数，`type` 决定现金方向。
- **时间**：买入、卖出 = 美东成交时间（存 UTC）；转入只填美东日期，记在**该日美东 00:00**（Q86），如 `2026-10-01` → `2026-10-01T04:00:00Z`（EDT）、`2026-12-01` → `2026-12-01T05:00:00Z`（EST）。
- **录入顺序**：股票记录 `id` 单调递增（自己的 id 序列）。所有重放都按 `(时间, 录入顺序)`：持股按 `(tradedAt, 股票记录 id)`；现金按 `(occurredAt, 流水 id)`，股票买入/卖出的流水与股票记录在同一事务里写入，所以两种顺序一致。修改不改变录入顺序。
- **现金**：`BUY` 写一条流水 `STOCK_BUY`（`amount` = −实付）；`SELL` 写一条 `STOCK_SELL`（`amount` = +实收）；`TRANSFER_IN` **不写流水**、不影响现金。冻结变动恒为 `0.00`（股票不冻结现金，也不被锁定）。股票流水与入金/出金、期权流水一起参与现金汇总、流水余额和所有 Q19 现金检查（入金/出金的新增、修改、删除，期权开仓 Q18/Q19、平仓、到期作废、删除……都会把股票流水算进去）。
- **批次与 FIFO（按账户 + 标的）**：每笔 `BUY`、`TRANSFER_IN` 是一个批次（原股数、原成本、剩余股数、剩余成本）。同一账户同一标的的全部股票记录按 `(tradedAt, id)` 重放；`SELL` 从最早的、剩余股数 > 0 的批次开始扣股数：
  - 整个批次被扣完 → 这部分成本 = 批次**剩余成本**；
  - 只扣一部分 → 这部分成本 = 剩余成本 × 扣除股数 ÷ 剩余股数，**HALF_UP 到分**（Q69、Q87），批次剩余成本减去这部分；
  - `soldCost` = 各部分之和；`realizedPnl` = 卖出实收 − `soldCost`。
  - 被卖出批次、`soldCost`、`realizedPnl`、持仓都**不落库**，每次读取时重放得出；补录/修改/删除后自动按新的 FIFO 匹配。
- **持股规则（Q15）**：任一时点持股（各批次剩余股数之和）不能为负 ⇔ 每笔 `SELL` 的股数 ≤ 卖出时点（同一时间按录入顺序）的持股。不能卖空；不支持融资（Q68：买入实付 ≤ 当时可用现金）。
- **显示用的每股价格**：`pricePerShare` = `amount ÷ shares`、`averageCost` = `totalCost ÷ shares`，**HALF_UP 到 4 位小数**（Q88），只用于显示；成本、盈亏只按分计算。
- **与期权的关系**：没有联动。股票持仓不影响 STO call（FEAT-038 不变：不冻结、不检查覆盖）；有空头 call 时股票照样可以全部卖出。同一标的的股票与期权互不影响。

#### 字段规则（新增与修改共用）

| 字段 | 类型 | 规则与 `VALIDATION_ERROR` 文案（`details[].field` = 字段名） |
|------|------|------|
| `type` | string | 新增必填：`BUY` / `SELL` / `TRANSFER_IN`。缺失或 `null` → `请选择类型`；其它值 → 400 `BAD_REQUEST`（`details[].field = "type"`） |
| `symbol` | string | 新增必填。先去首尾空白、转大写（同 FEAT-001），再校验：`标的不能为空`、`标的只能包含字母、数字和点`、`标的最多 10 个字符`。保存规范化后的值 |
| `shares` | number | 必填，整数，1 ≤ 股数 ≤ 10000000（Q70、Q88）：`股数不能为空`、`股数必须是正整数`（0、负数、`1.5`）、`股数过大`。不会被截断成整数；不是数字 → 400 `BAD_REQUEST` |
| `amount` | string | 必填，> 0，最多 2 位小数，≤ `9999999999999.99`：`金额不能为空`、`金额必须大于 0`、`金额最多 2 位小数`、`金额过大`。不是数字 → 400 `BAD_REQUEST` |
| `tradedAtLocal` | string | **`BUY` / `SELL` 必填**：美东本地日期时间，格式与解析规则同 FEAT-001（`YYYY-MM-DDTHH:mm[:ss]`，不带时区，严格解析，夏令时规则相同）：`成交时间不能为空`、`成交时间不能晚于当前时间`。`TRANSFER_IN` 时忽略。格式不对 → 400 `BAD_REQUEST` |
| `tradeDate` | string | **`TRANSFER_IN` 必填**：美东日期 `YYYY-MM-DD`：`日期不能为空`、`日期不能晚于今天`（今天 = 服务器当前时间的美东日期；今天本身允许）。`BUY` / `SELL` 时忽略。格式不对或日期不存在 → 400 `BAD_REQUEST` |
| `note` | string | 可选，最多 500 个字符：`备注最多 500 个字符`；`null`、空串、全空白保存为 `null` |

不检查交易日、交易时段（FEAT-009），不检查期权的到期时间（Q36、Q83 不适用于股票）。

#### 股票记录的表示（所有接口共用）

```json
{
  "id": 4,
  "type": "SELL",
  "symbol": "IXQZ",
  "shares": 150,
  "amount": "1200.00",
  "cashChange": "1200.00",
  "pricePerShare": "8.0000",
  "tradedAt": "2026-11-05T15:00:00Z",
  "tradedAtLocal": "2026-11-05T10:00:00",
  "tradeDate": null,
  "note": null,
  "ledgerEntryId": 4,
  "createdAt": "2026-11-13T15:00:00Z",
  "hasAuditLog": false,
  "soldCost": "1125.16",
  "realizedPnl": "74.84",
  "soldLots": [
    {
      "tradeId": 1,
      "type": "TRANSFER_IN",
      "tradedAt": "2026-10-01T04:00:00Z",
      "tradedAtLocal": "2026-10-01T00:00:00",
      "tradeDate": "2026-10-01",
      "shares": 100,
      "cost": "750.00"
    },
    {
      "tradeId": 2,
      "type": "BUY",
      "tradedAt": "2026-11-03T15:00:00Z",
      "tradedAtLocal": "2026-11-03T10:00:00",
      "tradeDate": null,
      "shares": 50,
      "cost": "375.16"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 股票记录 id（流水的 `sourceId`；审计 `objectType=STOCK_TRADE&objectId=<id>`） |
| `type` | string | `BUY` / `SELL` / `TRANSFER_IN` |
| `symbol` | string | 规范化后的标的 |
| `shares` | number | 股数（JSON 整数） |
| `amount` | string | 用户录入的金额（正数，scale 2）：买入实付 / 卖出实收 / 转入总成本 |
| `cashChange` | string | 对总现金的带符号影响，scale 2：`BUY` 为 `−amount`，`SELL` 为 `+amount`，`TRANSFER_IN` 为 `"0.00"` |
| `pricePerShare` | string | `amount ÷ shares`，HALF_UP 4 位小数（如 `"7.5033"`）；转入即"每股成本" |
| `tradedAt` | string | 时间，ISO-8601 UTC Instant（转入为该日美东 00:00） |
| `tradedAtLocal` | string | 同一时间的美东本地时间 `YYYY-MM-DDTHH:mm:ss`（转入为 `…T00:00:00`） |
| `tradeDate` | string \| null | 只有 `TRANSFER_IN`：美东日期 `YYYY-MM-DD`；其它为 `null` |
| `note` | string \| null | 备注 |
| `ledgerEntryId` | number \| null | `BUY` / `SELL` 的现金流水 id；`TRANSFER_IN` 为 `null` |
| `createdAt` | string | 录入时间（服务器时间） |
| `hasAuditLog` | boolean | 该记录被修改过时为 `true`（存在记录上的标记，同 FEAT-005 的 `has_audit_log`）；新增为 `false`，`PUT` 成功的响应恒为 `true` |
| `soldCost` | string \| null | 只有 `SELL`：被卖出成本（各被卖出批次 `cost` 之和），scale 2；其它为 `null` |
| `realizedPnl` | string \| null | 只有 `SELL`：已实现盈亏 = `amount − soldCost`，scale 2，盈利正、亏损负；其它为 `null` |
| `soldLots` | array \| null | 只有 `SELL`：被卖出的批次，FIFO 顺序；其它为 `null` |
| `soldLots[].tradeId` | number | 批次来源记录 id（`BUY` 或 `TRANSFER_IN` 的股票记录） |
| `soldLots[].type` | string | 批次来源类型 `BUY` / `TRANSFER_IN` |
| `soldLots[].tradedAt` / `tradedAtLocal` | string | 批次时间（UTC / 美东本地） |
| `soldLots[].tradeDate` | string \| null | 批次为转入时的日期，否则 `null` |
| `soldLots[].shares` | number | 从该批次扣除的股数 |
| `soldLots[].cost` | string | 这部分成本（整批扣完 = 批次剩余成本；否则按比例 HALF_UP 到分），scale 2 |

`soldLots`、`soldCost`、`realizedPnl` 按**当前**的全部历史重放给出：补录/修改/删除较早的记录后再查询会变成新的匹配。

#### `POST /api/stock-trades`
新增一条股票记录。

请求体（买入）：
```json
{
  "type": "BUY",
  "symbol": " ixqz ",
  "shares": 200,
  "amount": "1500.65",
  "tradedAtLocal": "2026-11-03T10:00",
  "note": null
}
```
请求体（转入）：
```json
{
  "type": "TRANSFER_IN",
  "symbol": "IXQZ",
  "shares": 100,
  "amount": "750.00",
  "tradeDate": "2026-10-01",
  "note": "上线前持有"
}
```

检查顺序（任一失败即整笔拒绝，什么都不保存）：
1. 字段 → 400 `VALIDATION_ERROR` / `BAD_REQUEST`。先做与请求体本身有关的校验（含按 `type` 必填的 `tradedAtLocal` / `tradeDate`）；全部通过后再检查"晚于当前时间 / 晚于今天"
2. 持股（`SELL`，Q15）：把本笔按 `(时间, 新 id)` 插入该标的的历史（同一时间的已有记录排在本笔之前），卖出时点持股 ≥ 卖出股数 → 否则 422 `SHARES_INSUFFICIENT`
3. 之后的卖出（补录，Q15）：插入后该标的之后的每一笔 `SELL` 仍不超过当时持股 → 否则 422 `SHARES_INSUFFICIENT`（只有补录较早的 `SELL` 才会出现）
4. 现金（`BUY`，Q68、Q19）：把本笔插入全部现金流水重放，从本笔起每个时点总现金、可用现金 ≥ 0 → 否则 422 `CASH_INSUFFICIENT`。`SELL`、`TRANSFER_IN` 不会让现金减少，不检查

响应 `201 Created`：股票记录的表示（见上）。不写审计。

#### `GET /api/stock-trades/{id}`
响应 `200 OK`：股票记录的表示。不存在、已删除或属于其它账户 → 404 `NOT_FOUND`（`Stock trade 99 not found`）；`id` 不是整数 → 400 `BAD_REQUEST`。

#### `GET /api/stock-trades`
当前账户的全部股票记录。**排序（固定）**：`tradedAt` 倒序；同一时间 `id` 大（后录入）的在上。

| query 参数 | 规则 |
|------------|------|
| `symbol` | 可选。去首尾空白、转大写后与标的**精确**相等（Q31）。规范化后须满足标的格式，否则 `VALIDATION_ERROR`：`标的只能包含字母、数字和点`、`标的最多 10 个字符` |
| `type` | 可选，可多值（重复参数 `type=BUY&type=TRANSFER_IN` 或逗号分隔 `type=BUY,TRANSFER_IN`，空项忽略），之间为"或者"。其它值 → `BAD_REQUEST`：`类型只能是 BUY、SELL、TRANSFER_IN` |
| `startDate` / `endDate` | 可选，美东日期 `YYYY-MM-DD`，两端包含，可只填一端（同 FEAT-002；转入按它的日期）。格式不对 → `BAD_REQUEST`（`日期格式不正确（YYYY-MM-DD）`）；开始晚于结束 → `VALIDATION_ERROR`，field `endDate`，`开始日期不能晚于结束日期` |
| `page` / `size` | 同 FEAT-002：`page` ≥ 1 默认 1（`page 必须 ≥ 1`），`size` 1–100 默认 20（`size 必须在 1 到 100 之间`）；超过最后一页 `items` 为 `[]` |

不传、空串或全空白的参数 = 不筛选；条件之间为"并且"。有 `BAD_REQUEST` 问题时先报它，不再报 `VALIDATION_ERROR`（同 FEAT-002）；`page` / `size` 不是整数 → `BAD_REQUEST`（`must be a valid int`）。

响应 `200 OK`：
```json
{
  "items": [ { "id": 5, "type": "SELL", "symbol": "XYZ", "shares": 100, "amount": "400.00", "...": "（股票记录的表示）" } ],
  "page": 1,
  "size": 20,
  "totalItems": 5,
  "totalPages": 1
}
```
没有记录：`{"items": [], "page": 1, "size": 20, "totalItems": 0, "totalPages": 0}`。

#### `PUT /api/stock-trades/{id}`
修改可改字段：`shares`、`amount`、时间（`BUY` / `SELL`：`tradedAtLocal`；`TRANSFER_IN`：`tradeDate`）、`note`（整体替换：不传 `note` = 清空）。**标的和类型不能改**（Q43：录错请删除后重录）。

请求体：
```json
{ "shares": 200, "amount": "1500.00", "tradedAtLocal": "2026-11-03T10:00", "note": null }
```
```json
{ "shares": 100, "amount": "750.00", "tradeDate": "2026-11-04", "note": null }
```
- `symbol`、`type` 可以不传；如果传了且与原记录（`symbol` 按规范化后比较）不同 → 422 `STOCK_TRADE_IDENTITY_NOT_EDITABLE`，`message`：`标的或类型录错请删除后重新录入`。与原记录相同则忽略。
- 字段规则同新增（`tradedAtLocal` / `tradeDate` 按**原记录**的类型必填）。
- 提交内容与原记录相同（股数、金额按数值、时间按换算后的时间点、备注空白 = 无备注）→ 422 `NO_CHANGES`（`没有修改`），不保存、不写审计。
- 把修改应用到全部历史上重放（记录保留原 id = 录入顺序；`BUY` / `SELL` 的流水原地更新，id 不变）：
  1. 该标的每一笔 `SELL` 不超过当时持股 → 否则 422 `SHARES_INSUFFICIENT`（被修改的就是那笔卖出且它本身不够时用"持股不足……"文案，其它卖出用"修改后……"文案）
  2. 现金每个时点总现金、可用现金 ≥ 0（只在 `BUY` / `SELL` 时检查）→ 否则 422 `CASH_INSUFFICIENT`
- 成功 `200 OK`：修改后的股票记录（`hasAuditLog` 为 `true`）。之后的卖出按新 FIFO 重新匹配；写一条审计（`UPDATE`，`STOCK_TRADE`），`sideEffects` 为 `[]`（股票没有冻结，Q42 的连带变化不适用）。

检查顺序：请求体 400 → 记录不存在 404 → 按原类型的必填 / 时间 400 → 标的或类型不同 422 → 没有修改 422 → 持股 422 → 现金 422。

#### `DELETE /api/stock-trades/{id}`
删除一条股票记录及其流水（如有）。把删除应用到全部历史上重放：持股（该标的每一笔 `SELL`）、现金（只在删除 `BUY` / `SELL` 时）任一不成立就 422（`SHARES_INSUFFICIENT` 用"删除后……"文案；`CASH_INSUFFICIENT` 见下）。成功 `204 No Content`，写一条审计（`DELETE`，`after` 为 `null`，`changes` 为 `[]`）。

#### `GET /api/stock-positions`
当前账户的全部股票持仓（当前持股 > 0 的标的）。无参数，不分页、不筛选。

响应 `200 OK`：
```json
{
  "positionCount": 2,
  "positions": [
    {
      "symbol": "IXQZ",
      "shares": 150,
      "totalCost": "1125.49",
      "averageCost": "7.5033",
      "lotCount": 1,
      "lots": [
        {
          "tradeId": 2,
          "type": "BUY",
          "tradedAt": "2026-11-03T15:00:00Z",
          "tradedAtLocal": "2026-11-03T10:00:00",
          "tradeDate": null,
          "originalShares": 200,
          "remainingShares": 150,
          "originalCost": "1500.65",
          "remainingCost": "1125.49",
          "note": null
        }
      ]
    },
    {
      "symbol": "XYZ",
      "shares": 200,
      "totalCost": "666.67",
      "averageCost": "3.3334",
      "lotCount": 1,
      "lots": [
        {
          "tradeId": 3,
          "type": "BUY",
          "tradedAt": "2026-11-04T15:00:00Z",
          "tradedAtLocal": "2026-11-04T10:00:00",
          "tradeDate": null,
          "originalShares": 300,
          "remainingShares": 200,
          "originalCost": "1000.00",
          "remainingCost": "666.67",
          "note": null
        }
      ]
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `positionCount` | number | 股票持仓个数（标的数）。单账户 = `positions` 长度；FEAT-035 `ALL` 时为不同 `symbol` 的个数（可能小于 `positions` 长度）。没有总成本合计 |
| `positions[]` | array | 按 `symbol` 升序（字符编码序，与 FEAT-003 相同）。没有持仓时 `[]` |
| `positions[].symbol` | string | 标的；可原样作为 `GET /api/stock-trades?symbol=` 的参数 |
| `positions[].shares` | number | 持股 = 各批次 `remainingShares` 之和 |
| `positions[].totalCost` | string | 各批次 `remainingCost` 之和，scale 2 |
| `positions[].averageCost` | string | `totalCost ÷ shares`，HALF_UP 4 位小数，仅显示 |
| `positions[].lotCount` | number | 批次数（= `lots` 长度） |
| `positions[].lots[]` | array | 剩余股数 > 0 的批次，FIFO 顺序（最早在前）。字段：`tradeId`（来源记录 id）、`type`（`BUY` / `TRANSFER_IN`）、`tradedAt` / `tradedAtLocal`、`tradeDate`（转入的日期，否则 `null`）、`originalShares`、`remainingShares`、`originalCost`、`remainingCost`（scale 2）、`note`（来源记录的备注） |

没有持仓：`{"positionCount": 0, "positions": []}`。

#### 现金流水（`GET /api/cash/ledger`）
- `STOCK_BUY` / `STOCK_SELL` 行：`occurredAt` = 成交时间，`amount` = `cashChange`，`frozenChange` `"0.00"`，`description` 如 `买入 200 股 IXQZ`、`卖出 150 股 IXQZ`，`remark` = 备注，`sourceId` = 股票记录 id，`hasAuditLog` 恒为 `false`（流水上没有修改/删除接口，到股票交易中修改；"历史"看股票记录的 `hasAuditLog`）。修改股票记录 → 这条流水原地更新（id 不变）；删除 → 流水删除。
- 转入没有流水。

#### 审计（`GET /api/audit-log`）
- `objectType` 新增 `STOCK_TRADE`（可作筛选参数），`objectId` = 股票记录 id。已删除记录的审计仍可查到。
- `summary`：`<买入|卖出|转入> <标的> <股数> 股`（修改前的值），如 `买入 IXQZ 200 股`。
- `before` / `after` 的内容（`tradeDate` 只有转入有值）：
```json
{
  "id": 1, "type": "TRANSFER_IN", "symbol": "IXQZ", "shares": 100, "amount": "750.00",
  "tradedAt": "2026-10-01T04:00:00Z", "tradedAtLocal": "2026-10-01T00:00:00", "tradeDate": "2026-10-01",
  "note": null, "cashChange": "0.00"
}
```
- `changes`（修改时前后不同的字段，显示值）：`shares`（`股数`，如 `200`）、`amount`（`BUY` / `SELL` 为 `金额`，`TRANSFER_IN` 为 `总成本`；如 `1,500.65`）、时间（`BUY` / `SELL`：field `tradedAt`，`时间`，美东 `YYYY-MM-DD HH:mm`；`TRANSFER_IN`：field `tradeDate`，`日期`，`YYYY-MM-DD`）、`note`（`备注`）。例：`金额：1,500.65 → 1,500.00`、`日期：2026-10-01 → 2026-11-04`。`cashChange` 不单独列出。
- `sideEffects` 恒为 `[]`。

#### 错误

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 字段规则（见上表）；列表 query 参数规则 |
| `BAD_REQUEST` | 400 | 值无法解析（`type` 不是枚举值、数字 / 日期 / 时间格式不对、JSON 语法错误）；`id` 不是整数 |
| `NOT_FOUND` | 404 | 记录不存在、已删除或属于其它账户：`Stock trade 99 not found` |
| `NO_CHANGES` | 422 | `PUT` 内容与原记录相同：`没有修改` |
| `STOCK_TRADE_IDENTITY_NOT_EDITABLE` | 422 | `PUT` 带了不同的 `symbol` / `type`：`标的或类型录错请删除后重新录入` |
| `SHARES_INSUFFICIENT` | 422 | 卖出超过当时持股（Q15），见下 |
| `CASH_INSUFFICIENT` | 422 | 买入（或修改 / 删除后）某个时点总现金或可用现金为负（Q68、Q19），见下 |

422 的 `message`（中文，可直接显示，`details` 为 `[]`；时间为美东 `YYYY-MM-DD HH:mm`，金额千分位 2 位小数、负数 `−`(U+2212)）：
- `SHARES_INSUFFICIENT`
  - 本笔（新增的卖出，或被修改的那笔卖出）：`持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股`
  - 补录后之后的卖出：`补录后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股`
  - 修改 / 删除后的其它卖出：`修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股`、`删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股`
- `CASH_INSUFFICIENT`
  - 新增买入，总是给出第一个变负的时点（可能就是本笔时点，也可能是之后某个时点；与 FEAT-007 出金"本笔时点不够"时的 `需要 X，可用 Y` 写法不同）：`可用现金不足：需要 600.00；2026-11-03 10:00 时可用现金将变为 −80.00`、`可用现金不足：需要 1,200.00；2026-11-07 10:00 时可用现金将变为 −100.65`
  - 修改 / 删除：`可用现金不足：修改后 2026-11-03 10:00 时可用现金将变为 −1.00`、`可用现金不足：删除后 2026-11-07 10:00 时可用现金将变为 −100.65`

示例（422）：
```json
{
  "code": "SHARES_INSUFFICIENT",
  "message": "持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股",
  "status": 422,
  "path": "/api/stock-trades",
  "timestamp": "2026-11-13T15:00:00Z",
  "details": []
}
```

示例（400）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/stock-trades",
  "timestamp": "2026-11-13T15:00:00Z",
  "details": [
    { "field": "shares", "message": "股数必须是正整数" },
    { "field": "symbol", "message": "标的只能包含字母、数字和点" }
  ]
}
```

