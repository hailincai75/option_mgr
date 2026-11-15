# FEAT-021 按标的分组查看

**结论：后端 N/A（无新接口、无新字段、无迁移、无行为变化）。** "策略"页复用两个已有的持仓接口，前端只做**按标的分组、计数、按后端顺序排列**；页面上的每一个数字都直接取后端字段。需求 Scope 中 `Backend` 可标 `N/A`。示例标的均为虚构（QRTV、IXQZ、ZWLK）。

## 为什么不新增接口
- 需求规则 2 要求"值与持仓页完全一致"（Q186）。直接用持仓页同一对接口，就是**同一份响应**，不存在两套代码算出不同值的可能；新接口只能是这两个接口的重新包装。
- 验收 1–9 显示的全部数值（DTE、`已过期 N 天`、张数、开仓净额合计、持股、被覆盖、可用、总成本、每股平均成本）在现有响应里都已存在，见下表。前端没有任何需要计算的数字。
- 前端剩下的工作只是展示：按 `symbol` 分桶、数组长度计数（标题里的 `期权 N 个持仓`）、按标的字母序排列卡片。这些不是业务计算（CLAUDE.md 禁止的是盈亏 / 成本 / 保证金类计算）。
- 刷新沿用持仓页：两个查询的 TanStack Query key 本来就包含账户 id，持仓页已有的各写操作失效逻辑会让本页同时更新（规则 5、验收 4–7），不需要后端配合。
- 代价：页面发两个请求，与持仓页相同；某次写操作后两个请求理论上可能先后读到写前 / 写后的数据，持仓页也有同样的特性，失效后会再取一次，最终一致。

## 使用的接口
都要求 `X-Account-Id`（FEAT-033），只返回该账户的数据，错误见 [FEAT-033.md](FEAT-033.md)（`ACCOUNT_REQUIRED` / `BAD_REQUEST` / `ACCOUNT_NOT_FOUND`）。两个接口都无参数、不分页；**不要**带持仓页 FEAT-010 的前端到期过滤（那个过滤本来只在前端），本页显示全部当前持仓（含已过期未处理）。

| 接口 | 契约 | 本页用途 |
|------|------|---------|
| `GET /api/option-positions` | [FEAT-003.md](FEAT-003.md)（及 FEAT-004 / 009 / 012–019 / 039 / 042 / 043 的纯新增字段） | 期权行 |
| `GET /api/stock-positions` | [FEAT-011.md](FEAT-011.md)（及 [FEAT-039.md](FEAT-039.md) 的 `coveredShares` / `availableShares`） | 股票行 |

两个接口都只返回"当前仍持有"的仓位：期权为净张数 ≠ 0 的合约（已平仓、作废、被指派、行权后消失，已过期未处理仍在，Q34）；股票为持股 > 0 的标的（卖完、指派 / 行权交出后消失）。这正好就是规则 1 / Q185 的"有持仓的标的"。

## 字段来源

### 期权表（每行 = `option-positions` 的一个 `positions[]` 元素）

| 列 | 字段 | 说明 |
|----|------|------|
| 合约 | `contractLabel` | 如 `"QRTV 2026-12-18 45 P"`。点击跳转交易列表：`symbol` / `expirationDate` / `strike` / `optionType` 原样作为 FEAT-002 的 query 参数（同持仓页） |
| 方向 | `direction` | `SHORT` → `空头`，`LONG` → `多头` |
| 张数 | `contracts` | 合约级，批次已合并（Q186） |
| 开仓净额 | `openingNetTotal` | 带现金方向符号（空头正、多头负），scale 2；前端按持仓页的格式加 `+` / `−` |
| 到期日 | `expirationDate` | FEAT-043 调整后已是新到期日 |
| DTE | `dte`、`expirationStatus`、`daysPastExpiration` | 同持仓页：`EXPIRES_TODAY` → `今天到期`；`EXPIRED` → 红色 `已过期 {daysPastExpiration} 天`；否则显示 `dte` |

不使用的字段（不显示，Q187 / 规则 6）：`marketValue`、`unrealizedPnl*`、`greeks`、`underlyingQuote`、`optionQuote`，以及所有操作相关字段（`assignable`、`exercisable`、`expirationAdjustable` 等）。

### 股票行（`stock-positions` 中 `symbol` 相同的那一个 `positions[]` 元素，至多一个）

| 列 | 字段 | 说明 |
|----|------|------|
| 持股 | `shares` | 千分位 |
| 被覆盖 | `coveredShares` | FEAT-039，当前被 `CHECKED` 空头 call 占用的股数 |
| 可用 | `availableShares` | = `shares − coveredShares`，后端给出 |
| 总成本 | `totalCost` | scale 2；put 被指派 / 行权生成的批次已按 Q8 扣除权利金 |
| 每股平均成本 | `averageCost` | **4 位小数**（Q88），如 `"48.0000"`，与股票页 / 持仓页显示一致；见文末"需求中的不一致" |

不使用：`quote`、`marketValue`、`unrealizedPnl*`、`lots`。

### 卡片与顺序

| 元素 | 来源 |
|------|------|
| 卡片集合 | 两个响应中出现过的 `symbol` 的并集（精确字符串相等；两边都已规范化为大写，FEAT-001 / FEAT-011） |
| 卡片顺序 | `symbol` 按字符编码升序（与两个接口的排序口径相同；`stock-positions` 本身已按此排序） |
| 组内期权顺序 | **保持 `option-positions` 返回的顺序，前端不要重新排序**。该接口按 `expirationDate` → `symbol` → `strike` 数值 → `CALL` 在 `PUT` 前排序，按 `symbol` 过滤后剩下的子序列就是规则 3 要求的 到期日 → 行权价 → Call 在 Put 前。（前端若自己排 `strike` 字符串会出错，如 `"100"` < `"45"`） |
| 标题 `期权 N 个持仓` | N = 该组期权行数（计数，不是计算）；无期权行时省略 |
| 标题 `股票 M 股` | M = 股票行的 `shares`；无股票行时省略 |
| 标的链接 | 同 FEAT-011：`symbol` 原样作为 `GET /api/stock-trades?symbol=` / 股票页筛选参数 |
| 空状态 `暂无持仓` | 两个响应的 `positions` 都为 `[]` |

## 验收场景对照（账户 A，`asOfDate` `"2026-11-23"`）
`GET /api/option-positions` 中与本页相关的片段（其余字段省略；顺序即接口返回顺序）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 6,
  "positions": [
    { "symbol": "IXQZ", "contractLabel": "IXQZ 2026-11-20 58 P", "direction": "SHORT", "contracts": 1, "openingNetTotal": "90.00",  "expirationDate": "2026-11-20", "dte": -3, "expirationStatus": "EXPIRED", "daysPastExpiration": 3 },
    { "symbol": "IXQZ", "contractLabel": "IXQZ 2026-12-18 60 P", "direction": "SHORT", "contracts": 1, "openingNetTotal": "200.00", "expirationDate": "2026-12-18", "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0 },
    { "symbol": "QRTV", "contractLabel": "QRTV 2026-12-18 40 P", "direction": "LONG",  "contracts": 1, "openingNetTotal": "-40.65", "expirationDate": "2026-12-18", "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0 },
    { "symbol": "QRTV", "contractLabel": "QRTV 2026-12-18 45 P", "direction": "SHORT", "contracts": 2, "openingNetTotal": "230.35", "expirationDate": "2026-12-18", "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0 },
    { "symbol": "QRTV", "contractLabel": "QRTV 2026-12-18 55 C", "direction": "SHORT", "contracts": 1, "openingNetTotal": "85.35",  "expirationDate": "2026-12-18", "dte": 25, "expirationStatus": "ACTIVE", "daysPastExpiration": 0 },
    { "symbol": "QRTV", "contractLabel": "QRTV 2027-01-15 45 P", "direction": "SHORT", "contracts": 1, "openingNetTotal": "150.35", "expirationDate": "2027-01-15", "dte": 53, "expirationStatus": "ACTIVE", "daysPastExpiration": 0 }
  ]
}
```
`GET /api/stock-positions`（片段）：
```json
{
  "positionCount": 2,
  "positions": [
    { "symbol": "QRTV", "shares": 100, "coveredShares": 100, "availableShares": 0,  "totalCost": "4800.00", "averageCost": "48.0000" },
    { "symbol": "ZWLK", "shares": 50,  "coveredShares": 0,   "availableShares": 50, "totalCost": "1500.00", "averageCost": "30.0000" }
  ]
}
```
分组结果：`IXQZ`（期权 2）、`QRTV`（期权 4 · 股票 100）、`ZWLK`（股票 50），与验收 1–3 一致。验收 4–8 的变化（平仓 FIFO 扣批次、作废 / 指派 / 删除重放、指派生成 `ASSIGN_BUY` 批次 `totalCost` `"5800.00"`、call 被指派交出股票、账户 B 隔离）全部由已有接口的已有行为给出，FEAT-004 / 012 / 013 / 041 / 011 / 039 / 033 已覆盖。

## 后端变更
无。不新增接口、字段、错误码、测试支持接口或迁移。QA 的后端系统测试如需覆盖，只需断言上述两个接口在验收场景下的字段值。

## 需求中的不一致（已报告，待 PM / 用户确认）
- 验收 2 / 3 / 6 的每股平均成本写成 `48.00`、`30.00`、`58.00`（2 位），但规则 2 要求"值与持仓页完全一致"，而 FEAT-011 / Q88 规定每股平均成本显示 4 位小数（后端 `averageCost` 为 `"48.0000"`，股票页原样显示）。本契约按 Q88 返回 4 位；若本页要 2 位，需要 PM 改验收或明确本页另一种格式（届时仍不需要后端改动，但会与"完全一致"矛盾）。
