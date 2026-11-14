# FEAT-018 单合约 Greeks（只读，实时计算，不存库）

口径全部已定：Q163–Q173（以及引用的 Q137 / Q141 / Q143 / Q149 / Q150 / Q152 / Q156 / Q161）。行情字段沿用 [FEAT-016.md](FEAT-016.md)，未实现盈亏沿用 [FEAT-017.md](FEAT-017.md)，本节只写**新增**的部分。示例标的均为虚构（QRTV、IXQZ），数字与需求验收一致（今天 2026-11-23 星期一，10:00 拉取成功，报价时间 09:45，multiplier 100）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/option-positions` | `positions[]` 新字段 `greeks`（对象，永不为 `null`；纯新增） | — |
| `POST /api/option-trades/expiration-adjustments`（FEAT-043） | 响应里的 `position`（与持仓行同一结构）也带 `greeks`（纯新增） | — |
| 其它所有接口（含 `GET /api/stock-positions`、`GET /api/positions/unrealized-summary`、`/api/market-data/**`、测试支持） | — | — |

已有字段、排序、错误都不变；旧客户端忽略新字段即可。**没有新端点、没有写操作、没有数据库迁移**：IV / Greeks 已由 FEAT-016 存在 `option_quote`，每次请求按当时的持仓和库里的报价实时计算。不返回 Rho（Q167，库里仍保留）。股票行不加 Greeks（FEAT-019）。不做任何 Greeks 合计（Q165，FEAT-019）。

---

#### CBOE 单位（已实测核实）
用户 2026-11-14 用真实 CBOE 响应核实（SPY ATM 754 合约，20 天到期），与需求的理解一致，本契约按此计算，不做任何换算：

| CBOE 字段 | 单位 | 实测 |
|-----------|------|------|
| `iv` | 小数（年化），`0.1461` = 14.61% | 0.1461 |
| `delta` | 每股；标的涨 1 美元期权每股价格变化 | — |
| `gamma` | 每股；标的涨 1 美元 delta 变化 | — |
| `theta` | 每股、**每日历天** | −0.253（与 Black-Scholes 计算一致） |
| `vega` | 每股、IV 每 **1 个百分点** | 0.72（一致） |

所有值都是"买入 1 股"（多头）视角。

---

#### 概念
- **来源**（规则 1）：该合约在 `option_quote` 中最新存的 `iv` / `delta` / `gamma` / `theta` / `vega`（FEAT-016 最后一次**成功**期权链里该合约的值），全系统一份，所有账户共用。拉取失败、数据源已无该合约（`MISSING_FROM_SOURCE`）时就是保留的最后值（Q141 / Q143）——FEAT-016 失败不改已存值，所以本需求不需要额外处理。一次成功的拉取会**整体替换**该合约的报价字段：CBOE 这次给 `null` 的字段在库里也变成 `null`（验收 8），不沿用上一次的值。
- **Greeks 状态** `greeks.status`（每行一个，判定顺序如下，先命中的为准）：
  1. `EXPIRED_PENDING`：`expirationDate` < `asOfDate`（美东今天），即 FEAT-003 `expirationStatus = EXPIRED`（Q171，同 Q156）。**即使库里有最后的 Greeks 也不显示**。到期日当天正常显示。
  2. `NO_QUOTE`：该合约从未有过 CBOE 报价，即 FEAT-016 `optionQuote.contractStatus = NEVER_QUOTED`（库里 `quote_time` 为空：从未匹配、链里从没有、匹配有歧义、指数标的 Q149）。**有手动 mark 也是 `NO_QUOTE`**（Q170，验收 7 的 P4）。
  3. `OK`：其余情况（`QUOTED` 或 `MISSING_FROM_SOURCE`），按下面的规则给值；单个字段为 `null` 只影响该字段（Q172）。
- **与 FEAT-017 `unrealizedStatus` 的区别**：`EXPIRED_PENDING` 判定相同；但 FEAT-017 的 `NO_QUOTE` 看**显示用 mark** 是否为 `null`，本需求看**是否有过 CBOE 报价**。所以两者可能不同：
  - 从未报价但有手动 mark（验收 7 的 P4）：未实现盈亏 `OK`，Greeks `NO_QUOTE`；
  - 有 CBOE 报价但 bid 0 且无 theo、也无手动 mark（`mark = null`）：未实现盈亏 `NO_QUOTE`，Greeks `OK`（照常显示 CBOE 值）。
- **标签** `greeks.label`：`EXPIRED_PENDING` → `已到期，待处理`；`NO_QUOTE` → `无报价`；`OK` → `null`。页面 IV / Delta / Gamma / Theta / Vega 五列都原样显示这段文字（灰色）。
- **数值规则**（规则 2–4，Q163 / Q164 / Q166）：
  - **每张原值** `*PerContract`：库里的原值 `setScale(4, HALF_UP)`，**不按方向翻转**。总是 4 位小数（`"0.2850"`、`"-0.0456"`、`"1.0000"`、`"0.0000"`）。
  - **持仓值** `*Position`：**未取整的原值** × `contracts` × `multiplier` × 方向（`LONG` +1，`SHORT` −1），再 `setScale(2, HALF_UP)`。HALF_UP 对负数按绝对值进位（−18.345 → −18.35，−3.125 → −3.13）。总是 2 位小数。含义：Delta = 等价股数；Gamma = 标的涨 1 美元时等价股数的变化；Theta = 美元 / 天；Vega = 美元 / IV 每 1 个百分点。
  - **IV** `ivPercent`：`iv × 100`，`setScale(2, HALF_UP)`（`0.3512` → `"35.12"`）。IV 没有持仓值、也不给每张小数原值（Q168）。
  - 0 一律输出无符号的 `"0.00"` / `"0.0000"`（`BigDecimal` 没有负零；用 `toPlainString()`）。
- **手动 mark 标注**（规则 6，Q170）：`status = OK` 且 `optionQuote.manual = true` 时，`greeks.manualMarkNote` = `Greeks 按 CBOE 报价（<quoteTime 美东 MM-DD HH:mm>），未随手动 Mark 调整`；其它情况 `null`。清除手动值、或下一次成功拉取自动替换掉手动值（FEAT-016 Q140）后，标注随之消失。Greeks 数值本身与手动 mark 无关，不重新估算。

---

#### `GET /api/option-positions`（FEAT-003，新增字段）
`positions[]` 每行新增 `greeks` 对象（**永不为 `null`**）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `OK` / `NO_QUOTE` / `EXPIRED_PENDING`（见概念） |
| `label` | string \| null | `无报价` / `已到期，待处理`；`OK` 时 `null`。前端原样显示，不按状态自己拼 |
| `ivPercent` | string \| null | IV 百分数的数值部分（已 ×100），scale 2，不带 `%`（如 `"35.12"`）。`status ≠ OK` 或 CBOE `iv` 为空时 `null` |
| `deltaPerContract` / `gammaPerContract` / `thetaPerContract` / `vegaPerContract` | string \| null | 每张原值（CBOE 每股原值，多头视角，不翻转），scale 4。`status ≠ OK` 或该字段为空时 `null` |
| `deltaPosition` / `gammaPosition` / `thetaPosition` / `vegaPosition` | string \| null | 持仓值，scale 2，负数带 `-`、正数**不带 `+`**（同 FEAT-017 金额约定）。`status ≠ OK` 或该字段为空时 `null`（每张值为 `null` 时持仓值一定也为 `null`，反之亦然） |
| `quoteTime` / `quoteTimeLocal` | string \| null | Greeks 所属的 CBOE 报价时间（Instant / 美东本地 `YYYY-MM-DDTHH:mm:ss`），与 `optionQuote.quoteTime` / `quoteTimeLocal` 相同。从未有过报价为 `null`；`EXPIRED_PENDING` 时照常给库里的最后报价时间（页面不显示） |
| `manualMarkNote` | string \| null | 手动 mark 标注整句（见概念）；前端原样灰字显示，不自己拼 |

不返回 Rho（Q167）。

前端显示约定（格式化，不是计算）：
- `status ≠ OK`：五列都显示 `label`（灰色），不看其它字段。
- `status = OK`：IV 列显示 `ivPercent` + `%`；Delta–Vega 每格上面是持仓值，**显式符号**（> 0 加 `+`，< 0 用 `−`，`0.00` 无符号，如 `+30.21`、`−5.12`），下面灰色小字是每张原值（< 0 用 `−`，正数不加 `+`，如 `−0.3021`、`0.0512`）。某字段为 `null` → 该格（持仓值和每张值）显示 `—`；`ivPercent = null` → IV 列 `—`。
- `manualMarkNote ≠ null`：灰色小字显示在该行 Greeks 区域。
- 旧响应没有 `greeks`（不应出现）：五列显示 `—`。

示例（验收 1–4 的账户 A；只列相关字段，`"…"` 表示其它字段不变）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 4,
  "positions": [
    {
      "symbol": "IXQZ", "expirationDate": "2026-12-18", "strike": "65", "optionType": "CALL",
      "contractLabel": "IXQZ 2026-12-18 65 C", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "0.80", "ask": "0.90", "mark": "0.85", "contractStatus": "QUOTED", "manual": false,
                       "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00", "…": "…" },
      "unrealizedStatus": "OK", "…": "…",
      "greeks": {
        "status": "OK",
        "label": null,
        "ivPercent": "29.00",
        "deltaPerContract": "0.1835",
        "deltaPosition": "-18.35",
        "gammaPerContract": "0.0313",
        "gammaPosition": "-3.13",
        "thetaPerContract": "-0.0247",
        "thetaPosition": "2.47",
        "vegaPerContract": "0.0503",
        "vegaPosition": "-5.03",
        "quoteTime": "2026-11-23T14:45:00Z",
        "quoteTimeLocal": "2026-11-23T09:45:00",
        "manualMarkNote": null
      }
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "47.5", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 47.5 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": null, "ask": null, "mark": null, "contractStatus": "NEVER_QUOTED", "manual": false,
                       "quoteTime": null, "quoteTimeLocal": null, "…": "…" },
      "unrealizedStatus": "NO_QUOTE", "…": "…",
      "greeks": {
        "status": "NO_QUOTE",
        "label": "无报价",
        "ivPercent": null,
        "deltaPerContract": null, "deltaPosition": null,
        "gammaPerContract": null, "gammaPosition": null,
        "thetaPerContract": null, "thetaPosition": null,
        "vegaPerContract": null, "vegaPosition": null,
        "quoteTime": null,
        "quoteTimeLocal": null,
        "manualMarkNote": null
      }
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "50", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
      "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "1.20", "ask": "1.30", "mark": "1.25", "contractStatus": "QUOTED", "manual": false,
                       "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00", "…": "…" },
      "unrealizedStatus": "OK", "…": "…",
      "greeks": {
        "status": "OK",
        "label": null,
        "ivPercent": "35.12",
        "deltaPerContract": "-0.3021",
        "deltaPosition": "30.21",
        "gammaPerContract": "0.0512",
        "gammaPosition": "-5.12",
        "thetaPerContract": "-0.0456",
        "thetaPosition": "4.56",
        "vegaPerContract": "0.0711",
        "vegaPosition": "-7.11",
        "quoteTime": "2026-11-23T14:45:00Z",
        "quoteTimeLocal": "2026-11-23T09:45:00",
        "manualMarkNote": null
      }
    },
    {
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "55", "optionType": "CALL",
      "contractLabel": "QRTV 2026-12-18 55 C", "direction": "LONG", "contracts": 2, "multiplier": 100,
      "expirationStatus": "ACTIVE", "…": "…",
      "optionQuote": { "bid": "1.80", "ask": "1.90", "mark": "1.85", "contractStatus": "QUOTED", "manual": false,
                       "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00", "…": "…" },
      "unrealizedStatus": "OK", "…": "…",
      "greeks": {
        "status": "OK",
        "label": null,
        "ivPercent": "33.20",
        "deltaPerContract": "0.2850",
        "deltaPosition": "57.00",
        "gammaPerContract": "0.0480",
        "gammaPosition": "9.60",
        "thetaPerContract": "-0.0390",
        "thetaPosition": "-7.80",
        "vegaPerContract": "0.0650",
        "vegaPosition": "13.00",
        "quoteTime": "2026-11-23T14:45:00Z",
        "quoteTimeLocal": "2026-11-23T09:45:00",
        "manualMarkNote": null
      }
    }
  ]
}
```
（P3 的 CBOE 原值 delta `0.18345`、gamma `0.03125`：每张值 HALF_UP 为 `0.1835` / `0.0313`；持仓值用未取整原值 −18.345 / −3.125 → `-18.35` / `-3.13`。）

其它情况的 `greeks` 片段：

验收 5（账户 B，P5：STO 3 张 QRTV 50P）：
```json
{ "status": "OK", "label": null, "ivPercent": "35.12",
  "deltaPerContract": "-0.3021", "deltaPosition": "90.63",
  "gammaPerContract": "0.0512", "gammaPosition": "-15.36",
  "thetaPerContract": "-0.0456", "thetaPosition": "13.68",
  "vegaPerContract": "0.0711", "vegaPosition": "-21.33",
  "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00", "manualMarkNote": null }
```

验收 6（10:15 拉取成功后 P1；之后 QRTV 拉取失败时完全相同，失败只体现在 FEAT-016 的 `optionQuote.fetchFailed` / `staleLabel`）。报价时间示意为 10:15 那次 CBOE 快照的时间：
```json
{ "status": "OK", "label": null, "ivPercent": "36.00",
  "deltaPerContract": "-0.3300", "deltaPosition": "33.00",
  "gammaPerContract": "0.0530", "gammaPosition": "-5.30",
  "thetaPerContract": "-0.0470", "thetaPosition": "4.70",
  "vegaPerContract": "0.0720", "vegaPosition": "-7.20",
  "quoteTime": "2026-11-23T15:00:00Z", "quoteTimeLocal": "2026-11-23T10:00:00", "manualMarkNote": null }
```

验收 7（P1 的 Mark 手动设为 1.40；数值同验收 1）：
```json
{ "status": "OK", "label": null, "ivPercent": "35.12",
  "deltaPerContract": "-0.3021", "deltaPosition": "30.21",
  "gammaPerContract": "0.0512", "gammaPosition": "-5.12",
  "thetaPerContract": "-0.0456", "thetaPosition": "4.56",
  "vegaPerContract": "0.0711", "vegaPosition": "-7.11",
  "quoteTime": "2026-11-23T14:45:00Z", "quoteTimeLocal": "2026-11-23T09:45:00",
  "manualMarkNote": "Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整" }
```
P4 的 Mark 手动设为 0.30：`greeks` 与上面账户 A 示例中的 P4 完全相同（`NO_QUOTE`、`manualMarkNote: null`），而 FEAT-017 的 `unrealizedStatus` 变为 `OK`。

验收 8（10:15 拉取成功，P2 的 gamma、vega 为 `null`）：
```json
{ "status": "OK", "label": null, "ivPercent": "33.20",
  "deltaPerContract": "0.2850", "deltaPosition": "57.00",
  "gammaPerContract": null, "gammaPosition": null,
  "thetaPerContract": "-0.0390", "thetaPosition": "-7.80",
  "vegaPerContract": null, "vegaPosition": null,
  "quoteTime": "2026-11-23T15:00:00Z", "quoteTimeLocal": "2026-11-23T10:00:00", "manualMarkNote": null }
```

验收 9（P6：STO 1 张 QRTV 2026-11-20 45P，已过期未处理，库里最后 delta −0.0100）：
```json
{ "status": "EXPIRED_PENDING", "label": "已到期，待处理", "ivPercent": null,
  "deltaPerContract": null, "deltaPosition": null,
  "gammaPerContract": null, "gammaPosition": null,
  "thetaPerContract": null, "thetaPosition": null,
  "vegaPerContract": null, "vegaPosition": null,
  "quoteTime": "2026-11-20T21:00:00Z", "quoteTimeLocal": "2026-11-20T16:00:00", "manualMarkNote": null }
```
（`quoteTime` 只是示意库里的最后报价时间。P7：STO 1 张 QRTV 2026-11-23 52P，今天到期 → `status: "OK"`、`deltaPerContract: "-0.4500"`、`deltaPosition: "45.00"`。）

错误：沿用 FEAT-003 / FEAT-033，没有本需求专有的错误码。行情或 Greeks 缺失不是错误（按 `NO_QUOTE` 或字段 `null` 处理）。

---

#### 实现说明（backend）
- **取值来源**：FEAT-016 的 `option_quote` 表（`iv`、`delta`、`gamma`、`theta`、`vega`，`DECIMAL(19,8)`；`quote_time`；`missing_since`；`manual_mark`）。通过同一个请求里已经取到的 `QuoteBook`（`QuoteQueryService.book()`）读，不另查库；需要让 `QuoteBook` 对外给出该合约的 `OptionState`（或一个只含 iv / Greeks / quoteTime / manual / status 的视图）。行内 `optionQuote` 与 `greeks` 来自同一次读取，所以 `quoteTime`、`manual` 一定对得上。
- **分层**：
  - `domain`（纯 Java，不依赖 Spring）：放在 `com.optionmgr.valuation.domain`（FEAT-019 的合计也会放这里）。`GreeksValuation.option(direction, contracts, multiplier, expirationDate, today, contractStatus, iv, delta, gamma, theta, vega, quoteTime, manual)` → `RowGreeks`（`GreeksStatus`、label、ivPercent、4 × (perContract, position)、quoteTime、manualMarkNote）。判定顺序、取整、符号、标签与标注文字全在这里；时间格式复用 `QuoteLabels` 的美东 `MM-dd HH:mm`。单元测试覆盖验收 1–9 的数字和边界（0 → `0.00`、HALF_UP 负数远离 0、Delta ±1.0000、Gamma 0、单字段 null、到期日当天）。
  - `service` / `web`：`OptionPositionController.toResponse` 在组装行时调用（与 FEAT-017 的 `UnrealizedValuationService.option` 同一处），所以 `GET /api/option-positions` 和 FEAT-043 调整响应里的 `position` 都带 `greeks`。`PositionResponse` 末尾加 `greeks` 字段（`GreeksResponse` record）。
- **取整**：每张值 `setScale(4, RoundingMode.HALF_UP)`；持仓值 `raw.multiply(contracts).multiply(multiplier).multiply(±1).setScale(2, HALF_UP)`（先乘后取整，用未取整原值；`multiplier` 用字段值，不写死 100）；`ivPercent` = `iv.movePointRight(2).setScale(2, HALF_UP)`。输出 `toPlainString()`。
- **判定复用 FEAT-016 / FEAT-017**：
  - `EXPIRED_PENDING` 用与 FEAT-017 相同的 `expirationDate < asOfDate`（`asOfDate` 由注入 `Clock` 的美东日期得出，同一请求只取一次），优先于其它判断。
  - `NO_QUOTE` 只看 FEAT-016 的 `ContractStatus.NEVER_QUOTED`（`OptionState.quoted() == false`），不另判断指数、歧义或调整合约——FEAT-016 已把它们都表示为 `NEVER_QUOTED`。**不看** `mark`（与 FEAT-017 不同，见概念）。
  - `fetchFailed`、`MISSING_FROM_SOURCE`、`markBasis = THEO`、`manual` 都不影响 Greeks 数值。
- **验收 8 依赖 FEAT-016 现有行为**：成功拉取时 `QuoteMerge.option` 用这次链里的值整体替换（含 `null`），已确认代码如此，无需改动。
- **测试**：`valuation/domain/GreeksValuationTest`（规则与验收数字）；`valuation/web/OptionGreeksControllerTest`（或并入现有持仓控制器测试；in-memory 假数据源 + 可移动时钟覆盖验收 1–9）。测试支持无新增接口：场景用 FEAT-016 的 `PUT /api/test-support/market-data/sources`（假链里已有 `iv` / `delta` / `gamma` / `theta` / `vega` 字段，可给 `null`）+ refresh / tick + 假时钟搭建；`GET /api/test-support/market-data/stored` 可核对库里原值。

#### 已知限制
- `option_quote` 的 Greeks 列为 `DECIMAL(19,8)`，CBOE 给出超过 8 位小数时入库已 HALF_UP 到 8 位；再取 4 位 / 算持仓值时理论上存在双重取整，实际 Greeks 不超过 4–5 位小数，不影响。
- 已过期未处理合约不显示 Greeks（Q171），即使库里有最后值。
- CBOE 深度实值 / 临近到期给出的 Delta ±1、Gamma 0 或 IV `null` 原样显示，不修正。
