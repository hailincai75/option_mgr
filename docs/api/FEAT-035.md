# FEAT-035 全部账户合并查看（一）：`X-Account-Id: ALL` 只读范围

> **FEAT-046 修订**：接受 `ALL` 的 GET 从 7 个增加到 11 个：新增 `GET /api/option-trades`、`GET /api/stock-trades`、`GET /api/closed-trades`、`GET /api/cash/ledger`（行新增 `accountId`、`accountName`，合并后分页，流水余额为该行账户自己的余额，Q228）；`GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}` 仍不支持；盈亏汇总与已平仓的一致性在 `ALL` 下恢复。见 [FEAT-046.md](FEAT-046.md)。下文"7 个"处按此理解。
>
> **FEAT-047 修订**：再增加 2 个，共 **13 个**：`GET /api/positions/underlying-summary`（每个"账户 + 标的"一组，`groups[]` 新增 `accountId`、`accountName`，Q224 A）、`GET /api/positions/greeks-summary`（同一标的跨账户合并，结构不变，Q224 B / Q233 A / Q234 A）。文末"需求问题 1"已由 FEAT-047 解决。仍不支持 `ALL` 的 GET 只剩 `/api/option-trades/{id}`、`/api/stock-trades/{id}`、`/api/audit-log`。见 [FEAT-047.md](FEAT-047.md)。

口径全部已定：Q53–Q56、Q214–Q226（以及引用的 Q35 / Q110 / Q158 / Q160 / Q215）。本节只写**新增或不同**的部分；示例账户名（甲账户、乙账户）和标的（QRTV、IXQZ）均为虚构，数字与需求场景 S 一致（今天 2026-11-23 星期一，10:00 拉取成功）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| 请求头 `X-Account-Id` | 新取值 `ALL`（全部账户，只读），只被下面 7 个接口接受 | 其它要求账户的接口收到 `ALL` → 400 `ALL_ACCOUNTS_READ_ONLY`（写）/ 400 `ALL_ACCOUNTS_NOT_SUPPORTED`（读）。原来是 400 `BAD_REQUEST`（`账户 id 必须是整数`），只是错误码变得更具体；HTTP 状态不变 |
| `GET /api/dashboard` | 接受 `ALL`（各项为全部账户之和） | — |
| `GET /api/cash/summary` | 接受 `ALL`；新字段 `accounts[]`（每账户一行） | — |
| `GET /api/option-positions` | 接受 `ALL`；`positions[]` 新字段 `accountId`、`accountName` | — |
| `GET /api/stock-positions` | 接受 `ALL`；`positions[]` 新字段 `accountId`、`accountName`；`ALL` 时 `positionCount` = 跨账户不同标的数 | — |
| `GET /api/positions/unrealized-summary` | 接受 `ALL`（全部账户合计） | — |
| `GET /api/pnl-summary` | 接受 `ALL`（按标的 / 按月跨账户合并） | — |
| `GET /api/adhoc-closure-alerts` | 接受 `ALL`；`alerts[]` 新字段 `byAccount[]` | `ALL` 时 `message` 多一段按账户分列（单账户不变） |
| `POST /api/option-trades/expiration-adjustments`（FEAT-043） | 响应的 `position` 与持仓行同一结构，也带 `accountId`、`accountName` | — |
| `/api/market-data/**`、`/api/accounts/**`、`/api/trading-calendar/**`、`/api/health`、`/api/test-support/**`（不要求账户的） | — | — （仍忽略 `X-Account-Id`，带 `ALL` 也照常工作，Q218） |

所有新字段在**单账户**请求里也有值（就是该账户自己），所以前端类型不用分两套；单账户下已有字段、数字、排序、错误都**不变**。没有数据库迁移，没有新端点。

---

#### 设计：为什么用 `X-Account-Id: ALL`，不新开接口

- **数据形状完全相同**：全部账户下 4 个视图要的就是单账户那些接口的同一结构（Q53：每行数字与在该账户看到的相同），只是行多了账户、合计变成相加。新开 `/api/all-accounts/...` 等于把 7 个响应结构复制一遍，前后端都要维护两份类型。
- **前端最省事**：FEAT-033 已经在统一请求封装里按"当前账户"加请求头、TanStack Query key 含账户 id。"全部账户"只是当前账户的一个特殊值 `ALL`，页面查询代码不变，切换时 key 变化自动重查（规则 1、Q219）。地址栏筛选参数也照旧不带账户（Q55）。
- **只读保护集中在一处**：请求头解析器统一判断——接受 `ALL` 的只有明确声明支持的 7 个 GET；其余一律报错。**以后新增的任何写接口自动受保护**，不需要每个 controller 记得检查（Q217 后端拒绝）。
- **未支持的页面明确报错**（Q223 / Q224 / Q225）：交易列表、已平仓、股票交易、流水、审计、策略 / Greeks 汇总等接口收到 `ALL` 返回 400 `ALL_ACCOUNTS_NOT_SUPPORTED`，而不是悄悄返回某个账户或空数据。前端在这些页面本来就不发请求（显示占位），报错只是兜底，防止误用时显示错的数字。FEAT-046 / 047 上线时把对应接口加进"接受 `ALL`"的列表即可。

---

#### 请求头 `X-Account-Id` 的取值（FEAT-033 扩展）

| 值 | 含义 |
|----|------|
| 十进制整数，如 `1` | 单个账户（FEAT-033，不变） |
| `ALL`（去首尾空白后，**不区分大小写**） | 全部账户：**请求当时存在的全部账户**（规则 2；新增账户自动包含）。只读 |

检查顺序与位置同 FEAT-033（在请求体校验之前）：缺失 / 空白 → `ACCOUNT_REQUIRED`；是 `ALL` → 若接口支持全部账户则继续，否则见下表；不是整数 → `BAD_REQUEST`；账户不存在 → `ACCOUNT_NOT_FOUND`。

**接受 `ALL` 的接口（共 7 个，全部是 GET）**：

| 接口 | 需求视图 |
|------|---------|
| `GET /api/dashboard` | 首页 |
| `GET /api/cash/summary` | 首页（不用）、资金页汇总区 |
| `GET /api/option-positions` | 持仓页期权表 |
| `GET /api/stock-positions` | 持仓页股票表 |
| `GET /api/positions/unrealized-summary` | 持仓页未实现合计 |
| `GET /api/pnl-summary` | 盈亏汇总页 |
| `GET /api/adhoc-closure-alerts` | 首页、持仓页顶部提醒 |
| `GET /api/option-trades`（FEAT-046 起） | 交易列表 |
| `GET /api/stock-trades`（FEAT-046 起） | 股票交易 |
| `GET /api/closed-trades`（FEAT-046 起） | 已平仓 |
| `GET /api/cash/ledger`（FEAT-046 起） | 资金页流水区 |
| `GET /api/positions/underlying-summary`（FEAT-047 起） | 策略页卡片摘要 |
| `GET /api/positions/greeks-summary`（FEAT-047 起） | 持仓页按标的 Greeks 汇总 |

**其它要求账户的接口收到 `ALL`**：

| 情况 | HTTP | code | `message` | `details` |
|------|------|------|-----------|-----------|
| 写请求（`POST` / `PUT` / `DELETE` / `PATCH`），任何要求账户的接口（含测试支持 `POST /api/test-support/legacy-option-trades`） | 400 | `ALL_ACCOUNTS_READ_ONLY` | `全部账户模式只读：录入、修改、删除请先选择一个账户` | `[{"field": "X-Account-Id", "message": "请选择一个账户"}]` |
| 读请求（`GET`），不在上表中的接口 | 400 | `ALL_ACCOUNTS_NOT_SUPPORTED` | `此接口暂不支持全部账户，请选择一个账户` | `[{"field": "X-Account-Id", "message": "请选择一个账户"}]` |

- 两种错误都**什么都不读、不写**，不写审计。`message` 是中文，可直接显示（与 `CASH_INSUFFICIENT` 等业务码一样）。
- 判断只看 HTTP 方法，不看路径：所以不存在的方法仍是 405（方法不匹配时不会走到账户解析）。
- 返回 `ALL_ACCOUNTS_NOT_SUPPORTED` 的 GET 接口：~~`/api/option-trades`、`/api/closed-trades`、`/api/stock-trades`、`/api/cash/ledger`~~（FEAT-046 起支持）；`/api/option-trades/{id}`、`/api/stock-trades/{id}`（FEAT-046 决定仍不支持：只用于已隐藏的修改对话框）；~~`/api/positions/greeks-summary`（FEAT-047，Q224）；`/api/positions/underlying-summary`（见文末"需求问题 1"）~~（FEAT-047 起支持）；`/api/audit-log`（**永久**不支持，Q225）。
- 不要求账户的接口（行情刷新与手动覆盖、账户、交易日历、健康检查、测试支持的 reset / clock / fetch 等）仍**忽略**该请求头，带 `ALL` 也照常成功（Q218、规则 6）。已核对：`/api/market-data/refresh`、`PUT` / `DELETE /api/market-data/overrides/**` 今天就不取账户（行情全系统一份）。

写请求被拒示例（需求验收 9）：
```
POST /api/cash/transfers
X-Account-Id: ALL
Content-Type: application/json

{"type": "DEPOSIT", "amount": "100.00", "occurredAtLocal": "2026-11-23T09:30", "remark": null}
```
```json
{
  "code": "ALL_ACCOUNTS_READ_ONLY",
  "message": "全部账户模式只读：录入、修改、删除请先选择一个账户",
  "status": 400,
  "path": "/api/cash/transfers",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [ { "field": "X-Account-Id", "message": "请选择一个账户" } ]
}
```
（不带 `X-Account-Id` 的同一请求仍是 FEAT-033 的 400 `ACCOUNT_REQUIRED`。两种情况两个账户的现金都不变。）

读请求不支持示例：
```json
{
  "code": "ALL_ACCOUNTS_NOT_SUPPORTED",
  "message": "此接口暂不支持全部账户，请选择一个账户",
  "status": 400,
  "path": "/api/closed-trades",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [ { "field": "X-Account-Id", "message": "请选择一个账户" } ]
}
```

---

#### 通用规则（全部账户）
- **计算不变（Q53）**：每个账户先按自己的规则单独计算（与 `X-Account-Id: <id>` 完全相同的代码路径），再拼接 / 相加。没有跨账户 FIFO、跨账户担保或覆盖。
- **同一次读取**：一个请求只取一次时钟（`asOfDate`）和一次行情（`QuoteBook`），所有账户共用；同一合约在两个账户里的 mark 相同（FEAT-017 边界）。
- **行上的账户**（Q214）：`accountId`（number）、`accountName`（string，请求时的当前名称，改名后立即反映）。
- **排序**（Q214）：各接口原排序不变，原排序完全相同的行按 `accountId` 升序。
- **不合并行**（Q215）：同一合约 / 同一标的股票在不同账户分行。
- **合计**（Q216）：后端相加，都是 scale-2 金额的精确和，不重新取整；个数相加。持仓页、首页、盈亏汇总只给全部合计（不给分账户小计），资金汇总给每账户一行。
- **股票"个标的"计数例外（用户后定，编号见 decisions.md）**：`GET /api/stock-positions` 的 `positionCount` 与 `GET /api/dashboard` 的 `stockPositions.positionCount` 在 `ALL` 下是**跨账户不同 `symbol` 的个数**，不是"账户 + 标的"行数：甲持有 QRTV、IXQZ，乙持有 QRTV → 2（行仍是 3 行，Q215）。其它个数（期权 `positionCount`、到期计数、未实现 `includedCount` 等）不变，仍按行。单账户下每个标的本来只有一行，数字不变。前端直接显示该字段，不自己数行。
- **行上的"能否操作"字段**（`assignable`、`exercisable`、`expirationAdjustable`、`assignChoices`、`exerciseChoices`、日期上下限、`availableCash`、`availableShares` 等）照该行所在账户计算，原样返回；全部账户下前端不显示任何写按钮（规则 5），这些字段只是不被使用。
- **只有一个账户时**：`ALL` 仍然有效（结果就是那个账户，带账户字段）；前端不提供该选项（Q220）。

---

#### `GET /api/dashboard`（`ALL`）
结构不变（FEAT-008 / FEAT-040），每个数字为全部账户之和：

| 字段 | 全部账户时 |
|------|-----------|
| `asOfDate` / `currentMonth` | 同单账户（美东今天 / 本月） |
| `cash.totalCash` / `frozenCash` / `availableCash` | 各账户之和（= `GET /api/cash/summary` `ALL` 的顶层三项） |
| `realizedPnl.closedCount` / `total` | 各账户之和 |
| `currentMonthRealizedPnl.closedCount` / `total` | 各账户之和 |
| `positions.positionCount` / `frozenTotal` | = `GET /api/option-positions` `ALL` 的 `positionCount` / `frozenTotal`（行数，Q215） |
| `positions.expiringWithin7DaysCount` / `expiredUnprocessedCount` | 各账户之和（按行，Q221） |
| `stockPositions.positionCount` | = `GET /api/stock-positions` `ALL` 的 `positionCount`（**跨账户不同标的数**，同一标的在两个账户算 1；见"通用规则"） |

示例（场景 S，验收 2）：
```
GET /api/dashboard
X-Account-Id: ALL
```
```json
{
  "asOfDate": "2026-11-23",
  "currentMonth": "2026-11",
  "cash": { "totalCash": "70473.05", "frozenCash": "14500.00", "availableCash": "55973.05" },
  "realizedPnl": { "closedCount": 2, "total": "90.00" },
  "currentMonthRealizedPnl": { "closedCount": 1, "total": "60.00" },
  "positions": {
    "positionCount": 4,
    "frozenTotal": "14500.00",
    "expiringWithin7DaysCount": 1,
    "expiredUnprocessedCount": 1
  },
  "stockPositions": { "positionCount": 1 }
}
```

---

#### `GET /api/cash/summary`（`ALL`；新字段 `accounts`）
顶层三项为全部账户之和；新字段 `accounts[]` 每个账户一行，按 `accountId` 升序，**包含没有任何记录的账户**（三项 `"0.00"`，边界"新增账户"）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalCash` / `frozenCash` / `availableCash` | string | `ALL`：各账户之和；单账户：不变 |
| `accounts`（新） | array | `ALL`：全部账户各一行；单账户：只有该账户一行（数字与顶层相同） |
| `accounts[].accountId` / `accountName` | number / string | 账户 |
| `accounts[].totalCash` / `frozenCash` / `availableCash` | string | 该账户的三项，与 `X-Account-Id: <id>` 时的顶层三项相同 |

示例（验收 3）：
```json
{
  "totalCash": "70473.05",
  "frozenCash": "14500.00",
  "availableCash": "55973.05",
  "accounts": [
    { "accountId": 1, "accountName": "甲账户", "totalCash": "50263.05", "frozenCash": "5000.00", "availableCash": "45263.05" },
    { "accountId": 2, "accountName": "乙账户", "totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00" }
  ]
}
```
单账户（`X-Account-Id: 2`）：`{"totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00", "accounts": [{"accountId": 2, "accountName": "乙账户", "totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00"}]}`。

资金页的合计行直接用顶层三项。流水 `GET /api/cash/ledger`：FEAT-035 时不支持 `ALL`，**FEAT-046 起支持**（见 [FEAT-046.md](FEAT-046.md)）。

---

#### `GET /api/option-positions`（`ALL`；新字段 `accountId`、`accountName`）
- `positions[]`：全部账户的持仓行，每行字段、数字与在该账户下完全相同（批次、DTE、剩余交易日、冻结、覆盖字段、到期状态、临时休市标签、行情、市值、未实现、`greeks` 等），加 `accountId`、`accountName`。
- 排序：FEAT-003 原规则（`expirationDate` 升序 → `symbol` → `strike` 数值 → `CALL` 在 `PUT` 前），相同时 `accountId` 升序。
- `positionCount` = 行数；`frozenTotal` = 各行 `frozenCash` 之和（= 各账户 `frozenTotal` 之和）；`asOfDate` 不变。
- 批次的 `tradeId` 仍是全局 id（FEAT-033），但只在该行账户内有意义；全部账户下前端不用它发写请求。

示例（验收 4、5；`"…"` 表示其它字段同单账户）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 4,
  "frozenTotal": "14500.00",
  "positions": [
    {
      "accountId": 2, "accountName": "乙账户",
      "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
      "contractLabel": "QRTV 2026-11-20 45 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "60.00", "frozenCash": "4500.00",
      "dte": -3, "expirationStatus": "EXPIRED", "daysPastExpiration": 3, "remainingTradingDays": null,
      "marketValue": null, "unrealizedPnl": null, "unrealizedPnlPercent": null,
      "unrealizedStatus": "EXPIRED_PENDING", "unrealizedLabel": "已到期，待处理",
      "expiresOnAdhocClosure": false, "adhocClosureEventName": null, "…": "…"
    },
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "IXQZ", "expirationDate": "2026-11-27", "strike": "65", "optionType": "CALL",
      "contractLabel": "IXQZ 2026-11-27 65 C", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "84.35", "frozenCash": "0.00",
      "dte": 4, "expirationStatus": "ACTIVE", "daysPastExpiration": 0,
      "underlyingShares": 100, "coveredShares": 100, "uncoveredContracts": 0, "availableShares": 0,
      "optionQuote": { "mark": "0.85", "…": "…" },
      "marketValue": "-85.00", "unrealizedPnl": "-0.65", "unrealizedPnlPercent": "-0.77",
      "unrealizedStatus": "OK", "unrealizedLabel": null, "…": "…"
    },
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "118.70", "frozenCash": "5000.00",
      "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0,
      "optionQuote": { "mark": "1.25", "…": "…" },
      "marketValue": "-125.00", "unrealizedPnl": "-6.30", "unrealizedPnlPercent": "-5.31",
      "unrealizedStatus": "OK", "unrealizedLabel": null, "…": "…"
    },
    {
      "accountId": 2, "accountName": "乙账户",
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "openingNetTotal": "120.00", "frozenCash": "5000.00",
      "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0,
      "optionQuote": { "mark": "1.25", "…": "…" },
      "marketValue": "-125.00", "unrealizedPnl": "-5.00", "unrealizedPnlPercent": "-4.17",
      "unrealizedStatus": "OK", "unrealizedLabel": null, "…": "…"
    }
  ]
}
```
单账户（`X-Account-Id: 1`）：只有 A4、A1 两行（验收 1），每行 `accountId: 1`、`accountName: "甲账户"`，其它不变。

前端到期过滤（FEAT-010，`?expiry=7` / 已过期未处理）仍只在前端按 `dte` / `expirationStatus` 过滤这些行；`frozenTotal` 与未实现合计不随过滤（Q110 / Q158）。

---

#### `GET /api/stock-positions`（`ALL`；新字段 `accountId`、`accountName`）
- `positions[]`：全部账户中持股 > 0 的"账户 + 标的"各一行，字段、数字与单账户相同（批次、`coveredShares`、`availableShares`、`quote`、市值、未实现），加 `accountId`、`accountName`。
- 排序：`symbol` 升序，相同时 `accountId` 升序。`positionCount` = **不同 `symbol` 的个数**（页面"共 N 个标的"）；同一标的在两个账户仍分两行（Q215），但只算 1 个，所以 `positionCount` 可能小于 `positions` 长度。单账户 = 行数（不变）。

同一标的在两个账户的示例（甲：IXQZ、QRTV；乙：QRTV）：
```json
{
  "positionCount": 2,
  "positions": [
    { "accountId": 1, "accountName": "甲账户", "symbol": "IXQZ", "shares": 100, "…": "…" },
    { "accountId": 1, "accountName": "甲账户", "symbol": "QRTV", "shares": 100, "…": "…" },
    { "accountId": 2, "accountName": "乙账户", "symbol": "QRTV", "shares": 50, "…": "…" }
  ]
}
```
同一数据 `GET /api/dashboard`（`ALL`）的 `stockPositions` 为 `{"positionCount": 2}`；`unrealized-summary` 的 `stock.includedCount` 仍按行为 3（有行情时）。

示例（验收 5）：
```json
{
  "positionCount": 1,
  "positions": [
    {
      "accountId": 1, "accountName": "甲账户",
      "symbol": "IXQZ", "shares": 100, "coveredShares": 100, "availableShares": 0,
      "totalCost": "5880.00", "averageCost": "58.8000",
      "quote": { "symbol": "IXQZ", "price": "61.10", "…": "…" },
      "marketValue": "6110.00", "unrealizedPnl": "230.00", "unrealizedPnlPercent": "3.91",
      "unrealizedStatus": "OK", "unrealizedLabel": null, "…": "…"
    }
  ]
}
```

---

#### `GET /api/positions/unrealized-summary`（`ALL`）
结构不变（FEAT-017）。三组的每个金额、每个计数都是各账户之和（= 两个持仓接口 `ALL` 时各 `OK` 行之和）；`excludedLabel` 按合计后的 `total.noQuoteCount` / `total.expiredPendingCount` 用同一规则拼（规则 4、Q160）。不随到期过滤（Q158）。

示例（验收 5）：
```json
{
  "asOfDate": "2026-11-23",
  "option": { "marketValue": "-335.00", "unrealizedPnl": "-11.95", "includedCount": 3, "noQuoteCount": 0, "expiredPendingCount": 1 },
  "stock":  { "marketValue": "6110.00", "unrealizedPnl": "230.00", "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0 },
  "total":  { "marketValue": "5775.00", "unrealizedPnl": "218.05", "includedCount": 4, "noQuoteCount": 0, "expiredPendingCount": 1 },
  "excludedLabel": "1 个持仓已到期待处理，未计入"
}
```

---

#### `GET /api/pnl-summary`（`ALL`）
结构、参数、校验、错误都不变（FEAT-008 / FEAT-040）。范围内全部账户的平仓、作废、指派、行权、股票卖出一起统计（Q222）：
- `bySymbol[]`：同一标的跨账户**合并为一行**（不加账户字段）；`byMonth[]`：同一美东月份合并为一行。排序不变。
- 每行与总计的 `closedCount`、`realizedPnl`、`option`、`stock` 都是各账户对应数之和；各组之和仍等于总计。
- ~~FEAT-008 的一致性"= `GET /api/closed-trades` 的 `filteredCount` / `filteredRealizedPnlTotal`"在 `ALL` 下暂不适用（已平仓 `ALL` 在 FEAT-046）。~~ **FEAT-046 起恢复**：`ALL` 下同样成立，见 [FEAT-046.md](FEAT-046.md)。

示例（验收 7，全部时间）：
```
GET /api/pnl-summary
X-Account-Id: ALL
```
```json
{
  "startDate": null,
  "endDate": null,
  "closedCount": 2,
  "realizedPnlTotal": "90.00",
  "option": { "closedCount": 2, "realizedPnl": "90.00" },
  "stock": { "closedCount": 0, "realizedPnl": "0.00" },
  "bySymbol": [
    { "symbol": "QRTV", "closedCount": 2, "realizedPnl": "90.00",
      "option": { "closedCount": 2, "realizedPnl": "90.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
  ],
  "byMonth": [
    { "month": "2026-11", "closedCount": 1, "realizedPnl": "60.00",
      "option": { "closedCount": 1, "realizedPnl": "60.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
    { "month": "2026-10", "closedCount": 1, "realizedPnl": "30.00",
      "option": { "closedCount": 1, "realizedPnl": "30.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
  ]
}
```
`?startDate=2026-11-01&endDate=2026-11-30`（`ALL`）：`closedCount` 1、`realizedPnlTotal` `"60.00"`、`bySymbol` 只有 `QRTV 1 "60.00"`、`byMonth` 只有 `2026-11`。`X-Account-Id: 2`（全部时间）：`QRTV 1 "30.00"`（单账户不变）。

---

#### `GET /api/adhoc-closure-alerts`（`ALL`；新字段 `byAccount`）
- `alerts[]`：每个有受影响持仓（任一账户）的 `ACTIVE` 临时休市日一行，按 `date` 升序；`affectedPositionCount` = 各账户受影响持仓之和（按行，同一合约在两个账户算 2 个）。
- 新字段 `alerts[].byAccount[]`：有受影响持仓的账户各一项，按 `accountId` 升序，只含个数 ≥ 1 的账户：`accountId`、`accountName`、`affectedPositionCount`。单账户请求时只有该账户一项。前端据此给每个账户一个 `切换到 <accountName> 去调整`（Q221）；切换后在单账户持仓页按 FEAT-042 定位第一个受影响行。
- `message`：单账户**不变**。`ALL` 时在"在该日到期"后插入按账户分列（即使只有一个账户受影响也写）：
  `<date> 临时休市（<eventName>），你有 <n> 个持仓在该日到期（<账户1> <n1> 个、<账户2> <n2> 个），期权通常改为前一个交易日到期，请核对并调整到期日`。前端原样显示，不自己拼；行末链接由前端按 `byAccount` 生成。

示例（验收 8：场景 S 另有临时休市 2026-12-18 `Test Closure`）：
```json
{
  "alerts": [
    {
      "date": "2026-12-18",
      "eventName": "Test Closure",
      "affectedPositionCount": 2,
      "byAccount": [
        { "accountId": 1, "accountName": "甲账户", "affectedPositionCount": 1 },
        { "accountId": 2, "accountName": "乙账户", "affectedPositionCount": 1 }
      ],
      "message": "2026-12-18 临时休市（Test Closure），你有 2 个持仓在该日到期（甲账户 1 个、乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日"
    }
  ]
}
```
（同一场景 `X-Account-Id: 2`：`affectedPositionCount` 1，`byAccount` `[{"accountId": 2, "accountName": "乙账户", "affectedPositionCount": 1}]`，`message` 为 FEAT-042 原格式。）

---

#### 验收标准的数字从哪里来

| 验收 | 页面上的数字 | 来源 |
|------|------------|------|
| 1 | 切到甲账户后只有 A4、A1 | `GET /api/option-positions`（`X-Account-Id: 1`）2 行；全部 / 单账户切换只改请求头（前端记住 `ALL`，Q219） |
| 2 | 现金 `70,473.05 / 14,500.00 / 55,973.05`、`+90.00（2 笔）`、`本月 +60.00（1 笔）`、`期权持仓 4 个`、冻结 `14,500.00`、`7 天内到期 1 个`、`已过期未处理 1 个`、`股票持仓 1 个标的` | `GET /api/dashboard`（`ALL`）的 `cash`、`realizedPnl`、`currentMonthRealizedPnl`、`positions.*`、`stockPositions.positionCount` |
| 3 | 每账户一行 + 合计 | `GET /api/cash/summary`（`ALL`）的 `accounts[]` 与顶层三项 |
| 4 | 4 行顺序、`已过期 3 天`、DTE 4、`已覆盖 100 股`、净额、冻结、`持仓 4 个`、`14,500.00` | `GET /api/option-positions`（`ALL`）：行顺序、`accountName`、`daysPastExpiration`、`dte`、`coveredShares`、`openingNetTotal`、`frozenCash`、`positionCount`、`frozenTotal` |
| 5 | 股票行与各行未实现、合计行、未计入提示 | `GET /api/stock-positions`、`GET /api/option-positions`（行上 `marketValue` / `unrealizedPnl` / `unrealizedLabel`）+ `GET /api/positions/unrealized-summary`（`ALL`） |
| 6 | 过滤后的行、冻结与未实现合计不变 | 前端过滤 `option-positions` 的行；`frozenTotal`、`unrealized-summary` 不随过滤 |
| 7 | 按标的、按月、合计、日期范围、乙账户 | `GET /api/pnl-summary`（`ALL` / 带日期 / `X-Account-Id: 2`） |
| 8 | 提醒文字与两个"切换到 … 去调整" | `GET /api/adhoc-closure-alerts`（`ALL`）的 `message` 与 `byAccount[]`；定位 B3 用 `X-Account-Id: 2` 的 `option-positions` 行 `expiresOnAdhocClosure` |
| 9 | 后端拒绝写请求 | 400 `ALL_ACCOUNTS_READ_ONLY`（或不带请求头时 `ACCOUNT_REQUIRED`），两账户 `cash/summary` 不变 |
| 10 | 占位页 | 前端不发请求；兜底：这些接口收到 `ALL` → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`。每行 Greeks 来自 `option-positions`（`ALL`）的 `greeks` |

**核对（按现有计算规则重算，全部对得上）**：
- 甲现金：50,000.00 + 118.70 + 100.00 − 40.00 + 84.35 = 50,263.05；冻结 A1 5,000.00（48P 的 4,800.00 已随 BTC 释放；A4 为 covered call 不冻结，FEAT-038）；可用 45,263.05。
- 乙现金：20,000.00 − 50.00 + 80.00 + 120.00 + 60.00 = 20,210.00；冻结 B3 5,000.00 + B4 4,500.00 = 9,500.00（B4 已过期未处理仍冻结，Q34）；可用 10,710.00。合计 70,473.05 / 14,500.00 / 55,973.05。
- 已实现：甲 48P 100.00 − 40.00 = +60.00（11-10，本月）；乙 55C −50.00 + 80.00 = +30.00（10-20）。合计 +90.00（2 笔），本月 +60.00（1 笔）。
- 到期：今天 11-23；A4 11-27 DTE 4（7 天内 1 个）；B4 11-20 DTE −3（已过期 1 个）；A1 / B3 12-18 DTE 25。
- 未实现：A1 118.70 − 125.00 = −6.30；B3 120.00 − 125.00 = −5.00（同一 mark 1.25）；A4 84.35 − 85.00 = −0.65；B4 `EXPIRED_PENDING` 不计入。期权市值 −335.00、未实现 −11.95；股票 61.10 × 100 = 6,110.00，− 5,880.00 = +230.00；合计 5,775.00 / 218.05。
- 百分比（页面未显示，示例用）：B3 −5.00 ÷ 120.00 × 100 = −4.1666… → `-4.17`。
- 临时休市 12-18：A1（甲）、B3（乙）各 1 个；甲的 48P、乙的 55C 已平，不算。

---

#### 对其它契约的修订（均为纯新增字段或新增可接受的请求头值）
- [FEAT-033.md](FEAT-033.md)：`X-Account-Id` 新取值 `ALL` 与两个新错误码（见上）。
- [FEAT-003.md](FEAT-003.md) / [FEAT-043.md](FEAT-043.md)：持仓行（及调整响应的 `position`）新增 `accountId`、`accountName`。
- [FEAT-011.md](FEAT-011.md)：股票持仓行新增 `accountId`、`accountName`。
- [FEAT-007.md](FEAT-007.md)：`GET /api/cash/summary` 新增 `accounts[]`。
- [FEAT-008.md](FEAT-008.md) / [FEAT-040.md](FEAT-040.md)：首页、盈亏汇总接受 `ALL`。
- [FEAT-017.md](FEAT-017.md)：未实现合计接受 `ALL`。
- [FEAT-042.md](FEAT-042.md)：提醒接受 `ALL`，新增 `byAccount[]`，`ALL` 时 `message` 多按账户分列。
- [FEAT-019.md](FEAT-019.md) / [FEAT-023.md](FEAT-023.md)：收到 `ALL` → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`。

---

#### 实现说明（backend）
- **请求头解析**：`account.web` 新增 `AccountScope`（record：`all` 标志 + 按 id 升序的账户 `Member(id, name)` 列表；单账户时只有该账户）及 `AccountScopeArgumentResolver`；上面 7 个 controller 方法把参数从 `AccountRef` 改为 `AccountScope`。`AccountRefArgumentResolver` 遇到 `ALL` 按 HTTP 方法抛 `ALL_ACCOUNTS_NOT_SUPPORTED`（GET / HEAD）或 `ALL_ACCOUNTS_READ_ONLY`（其它）；两个码加到 `ErrorCode`（400）。
- **合并方式**：对 `AccountService.list()`（按 id 升序）逐个调用现有单账户计算（`OptionPositionService.currentPositions(List<Long>)` 只读一次 `Clock`；每个请求一个 `QuoteBook`），再拼接 / 相加 / 稳定排序（`account.domain.AccountRows`：原比较器 + `accountId`）。盈亏汇总、未实现合计把各账户的记录 / 行放在一起用原有的纯 Java 汇总（等于各账户之和）；提醒用 `AdhocClosureAlerts.alerts(closures, accounts, allAccounts)`。保证每行与单账户完全相同（Q53）。不设专门性能优化（Q226）。
- **测试**：每条验收至少一个测试；另测：写接口带 `ALL` 被拒且不改数据（入金、开仓、股票录入、平仓、作废、批量作废、指派、行权、调整到期日、修改 / 删除）；读接口不支持列表全部返回 `ALL_ACCOUNTS_NOT_SUPPORTED`；行情刷新 / 覆盖带 `ALL` 成功；新增空账户后 `cash/summary` 多一行 0.00；`all`（小写）与 ` ALL ` 被接受。

#### 已知限制
- 写接口的拒绝只看请求头，不看请求体；全部账户下不存在"在 X 账户下录入"的写法（先切换账户）。
- 同一合约跨账户的"一空一多"不做任何净额抵消（Out of Scope）。

---

#### 需求问题（交给 PM，契约暂按括号内处理）
1. **"按标的分组"页（FEAT-021 / FEAT-023，`GET /api/positions/underlying-summary`）未在需求里出现**：规则 7 的占位列表和 FEAT-046 / 047 都没提它。契约暂定该接口收到 `ALL` → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`；前端应在该页按规则 7 显示占位。需要 PM 确认（或纳入 FEAT-046 / 047）。
2. ~~**验收 2 文字 `股票持仓 1 个标的`**：按"账户 + 标的"计数会误导。~~ **已定（用户，编号见 decisions.md）**：首页"股票持仓 N 个标的"与持仓页"共 N 个标的"在全部账户下都按跨账户不同标的数计数（见"通用规则"）；文字不变。场景 S 数字无影响。
3. **验收 8 搭建顺序（给 QA）**：2026-12-18 被记录为临时休市后，它就是非交易日，再录入到期日 12-18 的开仓会被 422 `EXPIRATION_NOT_TRADING_DAY` 拒绝（Q126）。须先录入 A1 / B3，再注入假 Finnhub 拉取。
4. **提醒 `message` 只有一个账户受影响时**：需求只给了两个账户的例子；契约定为 `ALL` 下总是写括号（如 `（乙账户 1 个）`）。
