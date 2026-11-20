# FEAT-052 行情数据源改用 Yahoo Finance（IV 取自 Yahoo，Greeks 由后端 Black-Scholes 计算）

口径：Q261（Yahoo 为期权数据源，CBOE 代码保留、配置关闭，Finnhub 继续兜底股价）、Q262–Q264（后端 Black-Scholes，r 为配置固定值，q = 0）、Q265（收盘后 0 / 缺失报价不覆盖已有有效报价）、Q266（接受 Yahoo 非官方接口风险）、Q268 / Q270（交易日 10:00–16:00 每整点 + 16:20；顶部提示阈值 90 分钟）、Q269（报价时间 = Yahoo `regularMarketTime`）、Q271（有效 = ask > 0、bid ≥ 0、ask ≥ bid，mark = 中间价；从未有过有效报价时用 `lastPrice` > 0，否则 `无报价`）。沿用 FEAT-016 / 017 / 018 / 019 / 048 的全部其它口径（见需求"已定的口径"）。本节只写**新增或不同**的部分；示例标的 QRTV、IXQZ 均为虚构，数字与需求验收一致（今天 2026-11-20 星期五，10:00 美东拉取成功，`r = 0.00%`，multiplier 100）。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/option-positions`（含 `ALL`）、FEAT-043 调整响应 `position` | `greeks.source`（`YAHOO` / `CBOE` / `null`） | `optionQuote.source`、`underlyingQuote.source` 可为 **`YAHOO`**；`optionQuote.markBasis` 可为 **`LAST`**；`greeks` 的数值对 Yahoo 报价由后端计算；`greeks.manualMarkNote` 对 Yahoo 报价改为新文字 |
| `GET /api/stock-positions`（含 `ALL`） | — | `quote.source` 可为 **`YAHOO`** |
| `GET /api/market-data/status` | `optionSource`、`riskFreeRatePercent` | 顶部提示阈值 30 → **90 分钟**（Q270） |
| `POST /api/market-data/refresh` | `results[].optionSource`、`results[].yahooResult`；`status` 同上 | `results[].cboeResult` 在 Yahoo 数据源下为 **`null`**（原来永不为 `null`） |
| 手动覆盖 `PUT` / `DELETE /api/market-data/overrides/**` | — | 请求、响应、错误不变；合约 mark 覆盖何时被自动替换见「手动覆盖」 |
| 定时拉取 | — | 交易日 **10:00、11:00、…、16:00（`SCHEDULED`，7 次）+ 16:20（`CLOSE`）**，原为每 15 分钟（Q270） |
| `GET /api/positions/unrealized-summary` / `greeks-summary` / `underlying-summary`、首页等 | — | 结构不变；数字随新的 mark / Greeks 变化 |
| 测试支持（`e2e` profile） | **新** `PUT /api/test-support/market-data/settings`；`sources` 新增 `yahoo`；请求记录新增 `kind`、`expirationDate`；`stored` 新增 `source` / `lastYahooResult` | `tick` 的定时时刻同上；`reset` 另恢复设置、清掉 Yahoo 会话 |
| 录入 / 平仓 / 到期 / 现金等其它所有接口 | — | — （规则 10：行情失败不影响记账） |

没有新的错误码；拉取失败仍不是 HTTP 错误。需要迁移 **V19**（见「迁移」）。

---

#### 概念

##### 数据源选择（规则 1，Q261）
- 配置 `optionmgr.quotes.option-source`：`yahoo`（默认）/ `cboe`。整个后端一份，启动时读取；`e2e` 下可用测试支持 `settings` 临时改。
- `yahoo`：期权链与标的价都向 Yahoo 请求；标的价没拿到时 Finnhub 兜底（同 FEAT-016 规则 6）。**不请求 CBOE**。
- `cboe`：行为与 FEAT-016 完全相同（CBOE 链 + Finnhub 兜底、CBOE 的 theo / Greeks 原样存、Q139 mark），**不请求 Yahoo**、不计算 Greeks、不用 r。只有拉取时刻与顶部提示阈值随 Q270 改变（两种数据源共用）。
- 一次 run 只用一种数据源，不在 Yahoo 失败时改问 CBOE。

##### Yahoo 请求（规则 2，可行性探测结果）
所有 Yahoo 请求都带浏览器 `User-Agent`（配置 `optionmgr.quotes.yahoo.user-agent`）和会话 cookie；**串行**，任意两个 Yahoo 请求之间间隔 `request-interval-millis`（默认 1000）；连接 / 读取超时沿用 `optionmgr.quotes.connect-timeout-seconds` / `read-timeout-seconds`（5 / 10）。

1. **会话握手**（会话 = cookie + crumb，保存在内存，跨 run 复用，重启后重新握手）：
   - `GET <yahoo.cookie-url>`（默认 `https://fc.yahoo.com/`）：任何 HTTP 状态都可以（实测 404），只为取得 cookie；网络错误 → 握手失败。
   - `GET <yahoo.base-url>/v1/test/getcrumb`（默认 base `https://query2.finance.yahoo.com`），带 cookie：`200` 且响应体去掉首尾空白后为 1–64 个可见字符、不含 `<`、`{`、空格 → crumb；否则（含 401 / 403 / 429 / 网络错误）→ 握手失败。
   - 只在需要时握手：本 run 第一个 Yahoo 数据请求之前、且内存里没有会话时。
2. **数据请求** `GET <yahoo.base-url>/v7/finance/options/{SYMBOL}?crumb=<crumb>[&date=<秒>]`：
   - `SYMBOL` 为持仓标的原样（大写，URL 编码）；指数标的（`index-symbols`）不请求，同 FEAT-016 / Q149。
   - **有跟踪合约的标的**：每个"跟踪合约的不同到期日"一次请求，`date` = 该到期日 **00:00 UTC** 的 Unix 秒（如 2027-02-19 → `1802995200`），按到期日升序。已过期未处理的合约（Q150）不算，所以平掉某到期日的全部持仓后不再请求它（验收 2）。
   - **只有股票、没有跟踪合约的标的**：一次不带 `date` 的请求，只用其中的 `quote`（Yahoo 会附带最近到期日的链，忽略）。
   - 标的按 `symbol` 升序处理。
3. **会话被拒**：数据请求返回 **401 或 403** → 丢弃会话、重新握手（本 run 最多一次），成功则**重发这一个请求一次**；重发仍 401 / 403 → 该标的 `HTTP_ERROR`。重新握手失败 → 该标的及本 run 尚未处理的标的都按 `HANDSHAKE_FAILED`（不再发 Yahoo 数据请求）。本 run 已经重新握手过一次后再遇到 401 / 403 → 该标的 `HTTP_ERROR`，不再握手。
4. **429**：该标的 `HTTP_ERROR`（`failureDetail` 含 `HTTP 429`），不重试、不重新握手；继续下一个标的。
5. **一个标的的任一到期日请求失败**（网络错误、非 200、无法解析）→ 该标的**整体失败**：不再请求它的其余到期日，本次不写它的任何合约报价（之前已成功的到期日响应也丢弃），标的价按 Finnhub 兜底（规则 7）。

##### Yahoo 响应解析与字段对照（规则 3、6）
解析用 Jackson `BigDecimal`（不经过 `double`）；时间字段为 Unix 秒。每个数据响应必须满足：顶层 `optionChain.result` 是非空数组、`optionChain.error` 为 `null` 或缺失、`result[0].quote` 是对象且 `quote.regularMarketTime` 为正整数——否则该请求 `INVALID_RESPONSE`（即该标的失败）。单个合约的单个字段格式错误 → 该字段按缺失处理，不影响整链。

| Yahoo 字段 | 存到（FEAT-016 已有存储） | 说明 |
|------------|-----------------------|------|
| `quote.regularMarketPrice` | 标的价 `price` | > 0 才算"标的价成功"；缺失 / ≤ 0 → Finnhub 兜底 |
| `quote.regularMarketTime` | 标的 `quoteTime`；**同一响应中所有合约的 `quoteTime`** | Q269；Yahoo 没有逐合约的报价时间 |
| `quote.bid` / `quote.ask` | 标的 `bid` / `ask` | 原样（可为 0） |
| `quote.regularMarketPreviousClose` | 标的 `prevClose` | |
| `quote.marketState` 等其它字段 | — | 不用 |
| `result[0].expirationDates` | — | 判断请求的到期日是否在 Yahoo 列表中（每个值换成 UTC 日期比较） |
| `options[0].calls[]` + `options[0].puts[]` | 合约 | 按 `contractSymbol` 匹配 |
| `contractSymbol` | `occCode` | OCC 格式，用 FEAT-016 的 `ChainMatcher`：末尾 15 位 = `YYMMDD` + `C`/`P` + 行权价 × 1000，前缀 root 等于或以持仓 `symbol` 结尾；四要素匹配到多个不同代码 = 歧义，按不在链中处理（同 FEAT-016） |
| `bid` / `ask` | `bid` / `ask` | 缺失为 `null` |
| `lastPrice` | `last` | |
| `impliedVolatility` | `iv` | 小数（`0.3` = 30%），存 8 位小数 HALF_UP |
| `volume` / `openInterest` | `volume` / `openInterest` | 整数；非整数 / 负数按缺失 |
| — | `theo`、`rho` | 一律 `null`（Yahoo 没有；Rho 不计算，Q167） |
| — | `delta` / `gamma` / `theta` / `vega` | 后端计算（见「Greeks 计算」） |
| `strike`、`expiration`、`lastTradeDate`、`change`、`percentChange`、`inTheMoney`、`contractSize`、`currency` | — | 不用（匹配只靠 `contractSymbol`） |

**标的价取哪个响应**：有多个到期日请求时，取本标的**最后一个**（到期日最晚的）`regularMarketPrice` > 0 的响应的 `quote`；都没有 → Finnhub 兜底。

**"链中已无该合约"**（规则 8，Q143）：标的成功时，某跟踪合约在它那个到期日的响应里匹配不到，**或**请求的到期日不在该响应的 `expirationDates` 中（Yahoo 会回别的到期日的链，不会匹配到）→ 同 FEAT-016 的 `MISSING_FROM_SOURCE` 处理（从未报价的保持 `NEVER_QUOTED`）。

##### 有效报价与是否覆盖已存报价（规则 4，Q265、Q271）
对标的成功、且在响应中匹配到的每个跟踪合约，先把本次 Yahoo 值分成三类：

| 本次分类 | 条件 |
|---------|------|
| **VALID** | `ask` > 0、`bid` ≥ 0、`ask` ≥ `bid`（都不为 `null`） |
| **LAST_ONLY** | 不是 VALID，且 `lastPrice` > 0 |
| **NONE** | 其它（如 bid / ask 为 0 或缺失，且 last 为 0 或缺失） |

库里该合约"**有可用报价**" = 已报价（`quoteTime` 非空）且按其来源规则算出的自动 mark 依据是 `MID` 或 `THEO`（Yahoo 的 VALID 报价、或 CBOE 能算出 mark 的旧报价）。

| 本次 | 库里有可用报价 | 库里没有（从未报价，或只有 LAST 报价，或 CBOE 旧报价算不出 mark） |
|------|---------------|------|
| VALID | **整体替换**：bid、ask、last、IV、volume、OI、报价时间、计算的 Greeks、`source = YAHOO`（`theo` / `rho` 置 `null`） | 同左 |
| LAST_ONLY | **全部保留不变**（含报价时间、IV、Greeks、来源） | **整体替换**为本次值（mark 依据 `LAST`，Greeks 按本次值计算） |
| NONE | 全部保留不变 | 全部保留不变（从未报价的仍 `NEVER_QUOTED` → `无报价`） |

- 不论是否替换，只要在链中出现，`missingSince` 清空（`contractStatus = QUOTED`），`fetchFailed = false`。被保留的合约**不**显示失败标签（Q265，验收 6）。
- 规则 9：库里的 CBOE 旧报价（含 CBOE 的 theo / Greeks、来源 `CBOE`）按上表处理：直到 Yahoo 给出 VALID（或该 CBOE 报价算不出 mark 时给出 LAST_ONLY）才被替换，期间照常显示、mark 仍按 CBOE 规则（Q139）。
- 标的价不分类：`regularMarketPrice` > 0 就更新（收盘后 16:20 的 50.30 照常更新，验收 6）。

##### Mark（修订 FEAT-016 Q139）
`automaticMark` 按**该合约已存报价的来源**计算（查询时计算，不存）：

| 来源 | 规则 | `markBasis` |
|------|------|------------|
| `YAHOO` | VALID → `(bid + ask) / 2`（精确，最多比输入多 1 位小数；`0 / 0.05` → `0.025`） | `MID` |
| `YAHOO` | 否则 `last` > 0 → `last` | **`LAST`**（新值） |
| `YAHOO` | 否则 → `null`（实际不会出现：NONE 不写库） | `null` |
| `CBOE` | 不变：bid、ask 都 > 0 → 中间价；否则 theo > 0 → theo；否则 `null` | `MID` / `THEO` |

显示格式不变（至少 2 位小数，`"0.65"`、`"4.10"`、`"0.025"`）。

##### 手动覆盖（Q140 / Q148）
- 合约 mark 覆盖：只在本次该合约为 **VALID 并因此被替换**时自动删除。LAST_ONLY（即使写入了库）、NONE、链中没有、标的失败 → 保留手动值（需求规则 4"本次无有效报价时保留手动值"）。CBOE 数据源下规则不变（自动 mark 非空即替换）。
- 标的价覆盖：不变（该标的下一次标的价成功——Yahoo 或 Finnhub——时删除）。

##### 收盘快照（Q142）
`CLOSE` run 后，把本次**链中出现的**跟踪合约的**当前已存值**（含按上表被保留的值）和标的价成功的标的写入当天快照；所以 16:20 报价为 0 时快照里是盘中最后的有效报价（验收 6：P1 mark 4.00）。快照多存一列 `source`。

##### Greeks 计算（规则 5，Q262–Q264）
只在 Yahoo 拉取**写入**合约报价时（VALID，或 LAST_ONLY 替换）计算并随报价存入 `option_quote`；查询时不重算；手动 mark / 手动标的价不触发重算（Q170）；保留的报价连同它的 Greeks 一起保留。CBOE 数据源不计算（存 CBOE 原值）。

- **模型**：Black-Scholes 欧式公式（用于美式期权的近似），股息率 q = 0。
- **输入**：
  - S = **同一响应**的 `quote.regularMarketPrice`（不用 Finnhub 价、不用手动价）；
  - K = 合约行权价；
  - σ = 本次存入的 IV（`impliedVolatility` 已 HALF_UP 到 8 位小数的值，保证用库里的值可复算）；
  - r = `optionmgr.quotes.risk-free-rate-percent` ÷ 100，按连续复利年化率使用；默认 `4.00`（即 0.04），重启后在下一次写入时生效；
  - T = (到期日 **16:00 美东**（`America/New_York`，按当天实际时区偏移）− 本响应 `regularMarketTime`) 的秒数 ÷ (365 × 86400)。夏令时按真实时间差。
- **公式**（√T 记作 s，φ 为标准正态密度，N 为标准正态分布函数）：
  - d1 = [ln(S/K) + (r + σ²/2)·T] / (σ·s)，d2 = d1 − σ·s
  - Call：Δ = N(d1)；Θ年 = −S·φ(d1)·σ / (2s) − r·K·e^(−rT)·N(d2)
  - Put：Δ = N(d1) − 1；Θ年 = −S·φ(d1)·σ / (2s) + r·K·e^(−rT)·N(−d2)
  - Γ = φ(d1) / (S·σ·s)；Vega年 = S·φ(d1)·s
- **存储单位**（与 FEAT-018 的 CBOE 单位相同：每股、多头视角）：`delta` = Δ；`gamma` = Γ；`theta` = Θ年 ÷ 365（每日历天，通常为负）；`vega` = Vega年 ÷ 100（IV 每 1 个百分点）。
- **精度与取整**：输入为 `BigDecimal`；ln / exp / √ / N 用 `double` 计算（`Math.log` / `Math.exp` / `Math.sqrt`，N 用误差 < 1e-14 的 erfc 实现），结果经 `BigDecimal.valueOf(x).setScale(8, HALF_UP)` 存入 `DECIMAL(19,8)`（"存未取整值"在 8 位小数的意义上）。显示取整完全沿用 FEAT-018（每张 4 位、持仓值用库里值 × 张数 × multiplier × 方向后 2 位，HALF_UP，负数远离 0；IV ×100 两位）。
- **存空（`null`）的情况**：σ 缺失或 ≤ 0；S 缺失或 ≤ 0；K ≤ 0；T ≤ 0（到期日 16:00 美东之后的报价）；或某个结果不是有限数（该 Greek 单独为 `null`）。这时 4 个 Greeks 都为 `null`（结果非有限数的情况除外），页面按 Q172 显示 `—`；IV 照常存、照常显示（缺失则 `—`，`0` 显示 `0.00%`）。
- 极端 IV（如 > 500%）不设上限，照常计算。

##### 拉取时刻与顶部提示（Q270，修订 FEAT-016 Q138 / Q141）
- **定时**：FEAT-009 交易日的美东 **10:00、11:00、12:00、13:00、14:00、15:00、16:00**（`SCHEDULED`）+ **16:20**（`CLOSE`）。固定写在代码里（`FetchSchedule`），不可配置。非交易日不拉；提前收盘日仍按上面时刻（已知限制）。两种数据源相同。
- **启动时拉取**（Q147）：不变（交易日 09:30–16:20 之间启动 → 后台一次 `SCHEDULED`）。
- **立即刷新**：不变（60 秒冷却）。
- **顶部提示**：规则 2 的阈值改为 **90 分钟**：交易日美东 09:30–16:00（含）之间，`now − max(lastSuccessAt, 今天 09:30) > 90 分钟` → `行情未更新（上次：<YYYY-MM-DD HH:mm> 美东）`。例（验收 7）：10:00 成功、11:00 失败 → 11:30 不提示，11:31 提示 `行情未更新（上次：2026-11-20 10:00 美东）`。"从未成功"规则不变。

##### 失败（规则 7、10）
- 结果值 `yahooResult`：`SUCCESS` / `NETWORK_ERROR` / `HTTP_ERROR`（非 200，含 401 / 403 / 429）/ `INVALID_RESPONSE` / **`HANDSHAKE_FAILED`**（新，本标的因握手失败没有发数据请求）。`SUCCESS` = 本标的全部到期日请求都成功（只有股票的标的：那一个请求成功）。
- 期权链成功 = `yahooResult = SUCCESS`；标的价成功 = Yahoo 价 > 0 或 Finnhub `SUCCESS`；`results[].result` 的 `SUCCESS` / `FALLBACK` / `FAILED`、行的 `fetchFailed` / `staleLabel`、run 成功的定义都同 FEAT-016（把 `cboeResult` 换成 `yahooResult`）。
- 握手失败 → 本 run 所有标的 `HANDSHAKE_FAILED`，每个标的照常 Finnhub 兜底股价；期权保留上次值并标 `行情未更新`；本 run 不算成功。
- 任何异常都收敛为该标的的失败结果，不中断 run；Yahoo 不可达不影响启动和记账（规则 10）。
- `failureDetail` 例：`Yahoo HTTP 403 (2027-02-19, after re-handshake); Finnhub SUCCESS`、`Yahoo HTTP 429 (2026-12-18); Finnhub NO_KEY`、`Yahoo handshake failed (getcrumb HTTP 401); Finnhub SUCCESS`、`Yahoo INVALID_RESPONSE (stock quote)`。**不含** crumb、cookie、完整 URL 查询串、响应全文；Finnhub key 同 FEAT-016 不出现。

---

#### `GET /api/option-positions`（FEAT-016 / FEAT-018 字段的变化）
结构不变，以下字段取值变化或新增：

| 字段 | 变化 |
|------|------|
| `underlyingQuote.source` | 新值 `YAHOO`（页面：`YAHOO` 不加悬停，`FINNHUB` 仍悬停 `来源 Finnhub`） |
| `optionQuote.source` | `YAHOO` / `CBOE`（该合约已存报价的来源；从未报价为 `null`） |
| `optionQuote.markBasis` | 新值 `LAST`（Yahoo 从未有过有效报价时用 `lastPrice`） |
| `optionQuote.quoteTime` | Yahoo 为 `regularMarketTime`；被保留的报价保持原时间 |
| `greeks.source` | **新增**，string \| null：Greeks 所属报价的来源 `YAHOO`（后端计算）/ `CBOE`（CBOE 原值）；从未报价为 `null`；`EXPIRED_PENDING` 照常给库里的来源 |
| `greeks.manualMarkNote` | `status = OK` 且手动 mark 时：来源 `YAHOO` → `Greeks 按 Yahoo 报价计算（MM-DD HH:mm），未随手动 Mark 调整`；来源 `CBOE` → 原文 `Greeks 按 CBOE 报价（MM-DD HH:mm），未随手动 Mark 调整`（时间都是 `greeks.quoteTime` 的美东 `MM-DD HH:mm`） |

`greeks.status` 的判定不变：`NO_QUOTE` 仍只看是否从未有过报价（`NEVER_QUOTED`）。所以 LAST 报价且 IV = 0 的合约是 `OK` 且 4 个 Greeks 为 `null`（页面 `—`，验收 6 的 P4）。

示例（验收 1 + 3，账户 A 的 P1，10:00 拉取后；只列相关字段）：
```json
{
  "symbol": "QRTV", "expirationDate": "2027-02-19", "strike": "50", "optionType": "PUT",
  "contractLabel": "QRTV 2027-02-19 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100, "…": "…",
  "underlyingQuote": {
    "symbol": "QRTV", "price": "50.00", "automaticPrice": "50.00", "source": "YAHOO",
    "quoteTime": "2026-11-20T15:00:00Z", "quoteTimeLocal": "2026-11-20T10:00:00",
    "manual": false, "manualEnteredAt": null, "manualEnteredAtLocal": null,
    "fetchFailed": false, "lastSuccessAt": "2026-11-20T15:00:00Z", "lastSuccessAtLocal": "2026-11-20T10:00:00",
    "staleLabel": null
  },
  "optionQuote": {
    "bid": "3.90", "ask": "4.10", "mark": "4.00", "automaticMark": "4.00", "markBasis": "MID", "source": "YAHOO",
    "quoteTime": "2026-11-20T15:00:00Z", "quoteTimeLocal": "2026-11-20T10:00:00",
    "contractStatus": "QUOTED", "contractStatusLabel": null,
    "manual": false, "manualEnteredAt": null, "manualEnteredAtLocal": null,
    "fetchFailed": false, "lastSuccessAt": "2026-11-20T15:00:00Z", "lastSuccessAtLocal": "2026-11-20T10:00:00",
    "staleLabel": null
  },
  "greeks": {
    "status": "OK", "label": null, "source": "YAHOO",
    "ivPercent": "30.00",
    "deltaPerContract": "-0.4701", "deltaPosition": "47.01",
    "gammaPerContract": "0.0530", "gammaPosition": "-5.30",
    "thetaPerContract": "-0.0163", "thetaPosition": "1.63",
    "vegaPerContract": "0.0995", "vegaPosition": "-9.95",
    "quoteTime": "2026-11-20T15:00:00Z", "quoteTimeLocal": "2026-11-20T10:00:00",
    "manualMarkNote": null
  }
}
```

验收 4（账户 B 的 P3，BTO 2 张 50C）`greeks`：
```json
{ "status": "OK", "label": null, "source": "YAHOO", "ivPercent": "30.00",
  "deltaPerContract": "0.5299", "deltaPosition": "105.98",
  "gammaPerContract": "0.0530", "gammaPosition": "10.61",
  "thetaPerContract": "-0.0163", "thetaPosition": "-3.27",
  "vegaPerContract": "0.0995", "vegaPosition": "19.89",
  "quoteTime": "2026-11-20T15:00:00Z", "quoteTimeLocal": "2026-11-20T10:00:00", "manualMarkNote": null }
```

验收 6（16:20 后的 P4：STO 1 张 45P，16:20 时 bid 0 / ask 0 / last 1.10 / IV 0，之前从未有过报价）：
```json
"optionQuote": { "bid": "0.00", "ask": "0.00", "mark": "1.10", "automaticMark": "1.10", "markBasis": "LAST", "source": "YAHOO",
  "quoteTime": "2026-11-20T21:00:00Z", "quoteTimeLocal": "2026-11-20T16:00:00", "contractStatus": "QUOTED", "…": "…" },
"greeks": { "status": "OK", "label": null, "source": "YAHOO", "ivPercent": "0.00",
  "deltaPerContract": null, "deltaPosition": null, "gammaPerContract": null, "gammaPosition": null,
  "thetaPerContract": null, "thetaPosition": null, "vegaPerContract": null, "vegaPosition": null,
  "quoteTime": "2026-11-20T21:00:00Z", "quoteTimeLocal": "2026-11-20T16:00:00", "manualMarkNote": null }
```
（报价时间示意 16:20 响应的 `regularMarketTime` = 16:00。last 也为 0 → NONE，不写库 → `optionQuote` 同 FEAT-016 的 `NEVER_QUOTED` 片段（`mark: null`，页面 `无报价`），`greeks.status = NO_QUOTE`。同一 run 中 P1 被保留：`optionQuote`、`greeks` 与上面 10:00 的示例完全相同，`underlyingQuote.price = "50.30"`。）

验收 9（P1 Mark 手动设为 4.20）：`optionQuote.mark = "4.20"`、`manual = true`，`greeks` 数值不变，`manualMarkNote = "Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整"`。

---

#### `GET /api/stock-positions`
`quote.source` 新值 `YAHOO`（`regularMarketPrice` > 0 时）；Finnhub 兜底时仍为 `FINNHUB`。其它不变。

---

#### `GET /api/market-data/status`（新增 2 个字段）
```json
{
  "stale": true,
  "warning": "行情未更新（上次：2026-11-20 10:00 美东）",
  "lastSuccessAt": "2026-11-20T15:00:00Z", "lastSuccessAtLocal": "2026-11-20T10:00:00",
  "lastAttemptAt": "2026-11-20T16:00:00Z", "lastAttemptAtLocal": "2026-11-20T11:00:00",
  "lastRefreshAt": null, "lastRefreshAtLocal": null,
  "refreshAvailableAt": null, "refreshAllowed": true,
  "trackedSymbolCount": 2,
  "optionSource": "YAHOO",
  "riskFreeRatePercent": "0.00"
}
```
（验收 7，11:31，`r = 0.00%` 的测试配置。）

| 字段 | 类型 | 说明 |
|------|------|------|
| `optionSource` | string | 当前期权数据源 `YAHOO` / `CBOE` |
| `riskFreeRatePercent` | string | Greeks 计算用的无风险利率（百分数的数值部分，不带 `%`），至少 2 位小数（`"4.00"`、`"0.00"`、`"4.125"`）。是**当前配置**的值；已存的 Greeks 可能是改配置前算的（重启后的下一次写入才用新值） |

前端 Greeks 表格上方说明：`optionSource = YAHOO` → `IV 来自 Yahoo Finance 延迟报价，Greeks 由系统按 Black-Scholes 计算（无风险利率 <riskFreeRatePercent>%），与券商数值可能略有差异；持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考`；`CBOE` → 保持 FEAT-018 原文。状态请求失败 / 尚未返回时按 `YAHOO` 文字、利率显示 `—`（前端自定）。

---

#### `POST /api/market-data/refresh`（`results[]` 新增字段）
冷却、同步、`status` 等不变（`status` 带上面的新字段）。

验收 1 / 2（10:00，Yahoo 数据源）：
```json
{
  "performed": true,
  "reason": null,
  "results": [
    { "symbol": "IXQZ", "result": "SUCCESS", "optionSource": "YAHOO", "yahooResult": "SUCCESS", "cboeResult": null,
      "finnhubResult": null, "underlyingPriceSource": "YAHOO", "failureDetail": null },
    { "symbol": "QRTV", "result": "SUCCESS", "optionSource": "YAHOO", "yahooResult": "SUCCESS", "cboeResult": null,
      "finnhubResult": null, "underlyingPriceSource": "YAHOO", "failureDetail": null }
  ],
  "status": { "…": "同 /status" }
}
```
验收 7（11:00，QRTV 403，Finnhub 50.20）：
```json
{ "symbol": "QRTV", "result": "FALLBACK", "optionSource": "YAHOO", "yahooResult": "HTTP_ERROR", "cboeResult": null,
  "finnhubResult": "SUCCESS", "underlyingPriceSource": "FINNHUB",
  "failureDetail": "Yahoo HTTP 403 (2026-12-18, after re-handshake); Finnhub SUCCESS" }
```

| 字段 | 说明 |
|------|------|
| `results[].optionSource` | 本次用的数据源 `YAHOO` / `CBOE` |
| `results[].yahooResult` | Yahoo 数据源时见「失败」；CBOE 数据源时 `null` |
| `results[].cboeResult` | CBOE 数据源时同 FEAT-016；**Yahoo 数据源时 `null`**（类型由 `string` 变为 `string \| null`） |
| `results[].underlyingPriceSource` | 新值 `YAHOO` |

---

#### 测试支持（仅 `e2e` profile）
`e2e` / `test` 下仍不访问真实 Yahoo / CBOE / Finnhub（规则 11）。假数据源仍返回"HTTP 状态 + 响应体文本"，之后的会话处理、解析、分类、Greeks、存储与真实拉取**完全相同**；in-memory 传输不等待请求间隔。

##### **新** `PUT /api/test-support/market-data/settings`
临时改数据源与利率（相当于改配置后重启，但不重启）。
```json
{ "optionSource": "CBOE", "riskFreeRatePercent": "5.00" }
```
- 两个字段都可省略或为 `null` = 恢复为配置文件的值。`optionSource`：`YAHOO` / `CBOE`（大小写不敏感）；`riskFreeRatePercent`：字符串数字，范围同配置（0–20）。
- 改数据源时丢弃内存中的 Yahoo 会话。已存报价、Greeks 不重算（同"重启后下一次写入生效"）。
- 响应 `200`：`{ "optionSource": "CBOE", "riskFreeRatePercent": "5.00" }`（生效值）。错误：值非法 → 400 `VALIDATION_ERROR`（`details` 指出字段）；无法解析 → 400 `BAD_REQUEST`。

##### `PUT /api/test-support/market-data/sources`（新增 `yahoo`）
整体替换假数据源配置（原 `cboe`、`finnhub`、`finnhubKeyPresent` 不变）；**同时丢弃内存中的 Yahoo 会话**（下一次 run 重新握手）。
```json
{
  "finnhubKeyPresent": true,
  "finnhub": { "QRTV": { "httpStatus": 200, "body": { "c": 50.20, "pc": 49.60, "t": 1795190340 } } },
  "yahoo": {
    "handshake": { "httpStatus": 200, "rawBody": "AbCdEfGhIjK" },
    "symbols": {
      "QRTV": {
        "byDate": {
          "2027-02-19": { "httpStatus": 200, "body": {
            "optionChain": { "error": null, "result": [ {
              "underlyingSymbol": "QRTV",
              "expirationDates": [1797552000, 1802995200],
              "strikes": [45, 50],
              "quote": { "symbol": "QRTV", "regularMarketPrice": 50.00, "regularMarketTime": 1795186800,
                         "regularMarketPreviousClose": 49.60, "bid": 49.98, "ask": 50.02, "marketState": "REGULAR" },
              "options": [ { "expirationDate": 1802995200,
                "calls": [ { "contractSymbol": "QRTV270219C00050000", "strike": 50, "bid": 4.00, "ask": 4.20,
                             "lastPrice": 4.10, "impliedVolatility": 0.3000, "volume": 12, "openInterest": 340 } ],
                "puts":  [ { "contractSymbol": "QRTV270219P00050000", "strike": 50, "bid": 3.90, "ask": 4.10,
                             "lastPrice": 3.95, "impliedVolatility": 0.3000, "volume": 20, "openInterest": 510 } ] } ] } ] } } },
          "2026-12-18": [
            { "httpStatus": 200, "body": { "…": "45P bid 0.60 / ask 0.70 / IV 0.3500 的同结构响应" } }
          ]
        }
      },
      "IXQZ": { "default": { "httpStatus": 200, "body": { "optionChain": { "error": null, "result": [ {
        "underlyingSymbol": "IXQZ", "expirationDates": [], "quote": { "symbol": "IXQZ", "regularMarketPrice": 61.10,
        "regularMarketTime": 1795186800 }, "options": [] } ] } } } }
    }
  }
}
```
- **回复写法**（`handshake`、`default`、`byDate` 的每个值）：同 FEAT-016 的 `{ "httpStatus", "body" }` / `{ "httpStatus", "rawBody" }` / `{ "unreachable": true }`；**或者一个数组**：按请求次数依次返回，用完后一直返回最后一个（例：`[{ "httpStatus": 401 }, { "httpStatus": 200, "body": … }]` 测"被拒 → 重新握手 → 成功"）。数组计数随每次 `PUT sources` 重置。
- `handshake`：代表整个握手（cookie + getcrumb 两步合为一次假请求，记录一条 `HANDSHAKE`）；`200` 时 `rawBody` 为 crumb。省略 `yahoo` 或 `handshake` → 握手成功（crumb `testcrumb01`）。握手失败用 `{ "httpStatus": 401 }` / `{ "unreachable": true }` 等。
- `symbols.<SYMBOL>.byDate.<YYYY-MM-DD>`：带 `date` 的请求（按请求的到期日）；`default`：不带 `date` 的请求，以及 `byDate` 里没配的到期日（模拟 Yahoo 对未上市到期日回最近的链）。都没配 = `unreachable`。没配的标的 = `unreachable`。
- 假数据源不校验 crumb；"会话被拒"用数据请求返回 401 / 403 表示。

##### `GET /api/test-support/market-data/requests`（每条新增 `kind`、`expirationDate`）
验收 2（10:00 拉取）：
```json
{ "requests": [
  { "source": "YAHOO", "kind": "HANDSHAKE", "symbol": null, "expirationDate": null, "at": "2026-11-20T15:00:00Z" },
  { "source": "YAHOO", "kind": "OPTIONS", "symbol": "IXQZ", "expirationDate": null, "at": "2026-11-20T15:00:00Z" },
  { "source": "YAHOO", "kind": "OPTIONS", "symbol": "QRTV", "expirationDate": "2026-12-18", "at": "2026-11-20T15:00:00Z" },
  { "source": "YAHOO", "kind": "OPTIONS", "symbol": "QRTV", "expirationDate": "2027-02-19", "at": "2026-11-20T15:00:00Z" }
] }
```
`kind`：Yahoo 为 `HANDSHAKE` / `OPTIONS`；CBOE、Finnhub 为 `null`（`expirationDate` 也为 `null`）。已有字段不变。

##### `POST /api/test-support/market-data/tick`
时刻判断改为 Q270：美东时间截到分钟后为 10:00–16:00 的整点（`SCHEDULED`）或 16:20（`CLOSE`），且是交易日；其它（如 09:30、10:15）→ `ran: false`、`skipReason: "NOT_A_SLOT"`。

##### `GET /api/test-support/market-data/stored`（新增字段）
- `underlyings[]`：`source` 可为 `YAHOO`；新增 `lastYahooResult`（string \| null）。
- `contracts[]`：新增 `source`（`YAHOO` / `CBOE` / `null`）；Yahoo 报价的 `theo`、`rho` 为 `null`，`delta` 等为计算值（8 位小数去尾零，如 `"-0.47010736"`）。
- `closeSnapshots[].contracts[]`：新增 `source`。

##### `POST /api/test-support/reset`
另外：设置恢复为配置值、丢弃 Yahoo 会话、假 Yahoo 恢复为空配置（握手成功、全部标的 `unreachable`）。

---

#### 实现说明（backend 自用，qa 参考）

##### 配置（`optionmgr.quotes.*` 新增）
| 属性 | 默认 | `test` | `e2e` | 说明 |
|------|------|--------|-------|------|
| `option-source` | `yahoo` | `cboe` | `cboe` | `yahoo` / `cboe`，其它值启动失败。测试 profile 默认 `cboe` 是为了让 FEAT-016–048 的已有测试（假 CBOE 链、CBOE Greeks 数字）不受影响；FEAT-052 的测试显式切到 Yahoo（后端测试 `@TestPropertySource` / e2e `PUT …/settings`） |
| `risk-free-rate-percent` | `4.00` | `0.00` | `0.00` | 年化百分数，0–20（含），超出或非数字启动失败 |
| `request-interval-millis` | `1000` | `0` | `0` | Yahoo 请求间隔（in-memory 传输忽略） |
| `yahoo.base-url` | `https://query2.finance.yahoo.com` | `http://127.0.0.1:9` | `http://127.0.0.1:9` | getcrumb 与 options 接口 |
| `yahoo.cookie-url` | `https://fc.yahoo.com/` | `http://127.0.0.1:9/` | `http://127.0.0.1:9/` | 取 cookie |
| `yahoo.user-agent` | 一个常见桌面浏览器 UA（写在 `application.yml`） | 同 | 同 | |
| 已有：`fetch-enabled`、`transport`、`cboe-base-url`、超时、`index-symbols` | 不变 | | | `cboe-base-url` 仍保留（Q261：CBOE 代码保留） |

验收 10 的手工做法：`gradlew.bat bootRun --args="--optionmgr.quotes.yahoo.base-url=http://127.0.0.1:9 --optionmgr.quotes.yahoo.cookie-url=http://127.0.0.1:9/"`（无 Finnhub key 的做法同 FEAT-016）。

##### 传输与会话
- `QuoteTransport` 新增 `yahooHandshake()` → crumb 或失败、`yahooOptions(symbol, LocalDate dateOrNull, crumb)` → `FetchAttempt`；`HttpQuoteTransport` 用 `java.net.http.HttpClient` + 内存 `CookieManager`（只在后端进程内，不落盘、不打日志），`InMemoryQuoteTransport` 按测试支持配置返回并记录请求。
- `YahooSession`（service，单例）：保存 cookie + crumb，run 锁内使用；规则见「Yahoo 请求」3。
- 请求间隔用可注入的 `Sleeper`（测试为空操作）。

##### 分层
- `quote/domain`（纯 Java）：
  - `YahooChain` / `YahooOption`（解析后的领域对象）；
  - `YahooQuoteRules`：VALID / LAST_ONLY / NONE 分类、"库里有可用报价"判断、Q271 mark；
  - `QuotePrices.mark` 增加来源参数（`CBOE` 规则不变，`YAHOO` 新规则），`MarkBasis` 新增 `LAST`，`PriceSource` 新增 `YAHOO`，`SourceResult` 新增 `HANDSHAKE_FAILED`；
  - `QuoteMerge`：新增 Yahoo 合约合并（上面的替换 / 保留表、手动覆盖只在 VALID 替换时删除）；CBOE 合并不变；
  - `BlackScholes`（建议放 `quote/domain`，或 `valuation/domain`）：`greeks(S, K, σ, r, T, call)` → 4 个 `BigDecimal`（8 位）或 `null`，含 T 计算（到期日 16:00 `America/New_York`）；
  - `FetchSchedule`：新时刻；`QuoteLabels`：阈值 90 分钟、Yahoo 版 `manualMarkNote`。
- `quote/service`：`QuoteParsers` 新增 Yahoo 解析；`QuoteFetchService` 按 `option-source` 分支（Yahoo：握手、按 标的 + 到期日 串行请求、整标的失败、Finnhub 兜底、计算 Greeks、写库）；`QuoteProperties` 新字段与校验；`QuoteQueryService` 输出 `source`、新的 mark。
- `valuation/domain/GreeksValuation`：不改判定与取整，只是 `manualMarkNote` 按来源选文字、`RowGreeks` 带 `source`。
- `quote/web/MarketDataController`：`status` 与 `results[]` 新字段。`testsupport/web/MarketDataTestSupportController`：`settings`、`yahoo` 配置、请求记录字段、`stored` 字段。

##### 迁移 `V19__yahoo_quotes.sql`
真实数据迁移（启动前自动备份 `<db>-backup-before-V19-…`，同已有机制）：
- `underlying_quote`：删除 `ck_underlying_quote_source`，重建为 `price_source IS NULL OR price_source IN ('CBOE', 'FINNHUB', 'YAHOO')`；新增 `last_yahoo_result VARCHAR(20) NULL`。
- `option_quote`：新增 `quote_source VARCHAR(10) NULL`；`UPDATE option_quote SET quote_source = 'CBOE' WHERE quote_time IS NOT NULL`（已有报价都来自 CBOE，规则 9）；新增约束 `ck_option_quote_source`：`quote_source IS NULL OR quote_source IN ('CBOE', 'YAHOO')`，以及 `(quote_time IS NULL) = (quote_source IS NULL)`。
- `option_quote_close`：新增 `quote_source VARCHAR(10) NULL`；`UPDATE option_quote_close SET quote_source = 'CBOE'`（历史快照全是 CBOE，内容不改）。
- 不改其它表与已有值；`option_quote` 的 Greeks 列仍为 `DECIMAL(19,8)`，不需要改。
- `backend/src/test/resources/db/failing-migration/V19__simulated_failure.sql` 改名为 `V20__simulated_failure.sql`（注释改为 "V20 so it runs after the real V19"）；`MigrationVersions` 动态取最新版本，不用改常量。
- 新列由测试支持重置快照机制自动纳入。

##### 测试（后端）
- `quote/domain/BlackScholesTest`：验收 3 / 4 / 5 的数字（见下表）、T ≤ 0 / σ ≤ 0 / S ≤ 0 → `null`、到期日当天 16:00 前后、夏令时、极端 IV。
- `quote/domain/YahooQuoteRulesTest`：分类、替换 / 保留表（含 CBOE 旧报价）、Q271 mark（`0 / 0.05` → `0.025`）、手动覆盖。
- `quote/service/QuoteParsersTest`（Yahoo 部分）：缺字段、`optionChain.error`、空 `result`、科学计数法 IV（`1.0E-5`）、非整数 volume。
- `quote/service/HttpQuoteTransportTest`：本地假 HTTP 服务模拟 fc / getcrumb / options（cookie 回传、401 → 重新握手 → 重发、429 不重试、请求间隔可设为 0）。
- `quote/web/YahooQuotesControllerTest`：in-memory 假数据源 + 可设时钟，`option-source=yahoo`、`r = 0.00`，覆盖验收 1–10。
- 已有测试中按 15 分钟时刻 `tick`（09:30、10:15、…）或依赖 30 分钟阈值的用例需要改为新时刻 / 新阈值（`MarketDataControllerTest` 等由 backend 改；`systemtest/` 与 e2e 由 qa 改）。

---

#### Greeks 数值复核（需求验收 3–5）
场景：S = 50.00，K = 50，σ = 0.3000，q = 0，报价时间 2026-11-20 10:00 美东（15:00Z）。到期 2027-02-19 16:00 美东（21:00Z，两端都是 EST）→ 91 天 6 小时 = 91.25 天，T = 91.25 / 365 = **0.25**。2026-12-18 45P：28.25 天。

| 合约 | r | Δ（存 8 位） | Γ | Θ / 天 | Vega / 1% | 每张显示 | 持仓值（方向 × 张数 × 100） |
|------|---|------|---|---|---|---|---|
| P1 50P（STO 1） | 0% | −0.47010736 | 0.05304291 | −0.01634884 | 0.09945546 | −0.4701 / 0.0530 / −0.0163 / 0.0995 | **+47.01 / −5.30 / +1.63 / −9.95** ✓ |
| P3 50C（BTO 2） | 0% | 0.52989264 | 0.05304291 | −0.01634884 | 0.09945546 | 0.5299 / 0.0530 / −0.0163 / 0.0995 | **+105.98 / +10.61 / −3.27 / +19.89** ✓ |
| P1 50P | 5% | −0.43709707 | 0.05252971 | −0.01283104 | 0.09849321 | −0.4371 | Delta **+43.71** ✓ |
| P1 50P | 4%（默认） | −0.44367165 | 0.05266120 | −0.01350069 | 0.09873976 | −0.4437 | +44.37 |
| P2 45P（STO 1，σ 0.35，T 28.25/365） | 0% | −0.12908334 | 0.04323875 | −0.01813954 | 0.02928241 | −0.1291 / 0.0432 / −0.0181 / 0.0293 | +12.91 / −4.32 / +1.81 / −2.93 |

需求验收 3、4、5 的数字全部正确（独立按上面公式用双精度复算）。按标的汇总（账户 B）QRTV 期权 Delta = +105.98 ✓。

---

#### 已知限制
- Yahoo 为非官方接口（Q266）：握手方式、字段或频率限制变化时按失败处理（`行情未更新`），另立需求修复。只做过一次盘中探测；收盘后的 bid / ask / IV 实际形态、`date` 参数是否必须与 `expirationDates` 中的值逐秒相同（本契约用 00:00 UTC，探测中的列表值未逐个核对）尚未实测。
- Greeks 为欧式 Black-Scholes、q = 0、单一固定 r 的近似：深度实值美式 put、临近除息的 call 与券商数值会有差异。IV 直接用 Yahoo 的值，不自行反推。
- Greeks 计算内部用 `double`（只在超越函数部分；输入输出都是 `BigDecimal`，结果 8 位小数），这是项目"禁止 double"规则对金额以外的分析计算的例外，写进架构说明。
- Yahoo 报价时间是标的的 `regularMarketTime`，不是合约成交时间；流动性差的合约其 bid / ask 可能明显旧于该时间。
- `LAST` mark 可能是几天前的成交价（Q271 已接受）。
- 标的代码原样发送：带点的代码（如 `BRK.B`，Yahoo 写作 `BRK-B`）会失败，需手动覆盖或另立需求。
- 请求数 = 各标的持有到期日数之和（只有股票的标的 1 个）+ 必要时的握手；串行 + 1 秒间隔，"立即刷新"同步等待，标的、到期日多时需要数十秒。
- 拉取时刻固定（不可配置）；提前收盘日仍按 10:00–16:00 / 16:20。
- `riskFreeRatePercent` 是当前配置值，不随每条 Greeks 存储；改 r 后到下一次写入前，旧 Greeks 与说明文字里的 r 可能不一致。

---

#### 对其它契约的修订
- [FEAT-016.md](FEAT-016.md)：数据源可配置（默认 Yahoo），CBOE 部分只在 `option-source=cboe` 时生效；定时时刻（Q270）、顶部提示阈值 90 分钟；`source` / `underlyingPriceSource` 新值 `YAHOO`、`markBasis` 新值 `LAST`、mark 规则按来源；`status` 新字段；`results[]` 新字段与 `cboeResult` 可为 `null`；测试支持的 `settings`、`yahoo`、请求记录字段、`tick` 时刻、`stored` 字段；迁移 V19。
- [FEAT-017.md](FEAT-017.md)：使用的 mark 可为 `LAST`；收盘后 0 报价不改变已存 mark（市值按盘中最后有效报价，Q265）。
- [FEAT-018.md](FEAT-018.md)：Yahoo 报价的 Greeks 由后端在拉取时计算并存储（读取方式、判定、取整不变）；`greeks.source` 新字段；`manualMarkNote` 按来源选文字；说明文字按 `optionSource`。
- [FEAT-019.md](FEAT-019.md)：结构与规则不变；行 Greeks 来自 FEAT-018（Yahoo 时为计算值），标的价来源可为 `YAHOO`。
- [FEAT-048.md](FEAT-048.md)：批次所用的 mark 可为 `LAST`。
- [FEAT-047.md](FEAT-047.md) / [FEAT-023.md](FEAT-023.md) / [FEAT-035.md](FEAT-035.md)：无结构变化（数字随 mark / Greeks 变化），不加修订注。
