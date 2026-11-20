# FEAT-052: 行情数据源改用 Yahoo Finance（IV 取自 Yahoo，Greeks 由后端计算）

- **Status:** Verified（用户 2026-11-20 批准；QA 2026-11-20 验证，见 docs/test-reports/FEAT-052-test-report.md）
- **Priority:** P0（真实使用阻塞：CBOE 现在对所有请求返回 403，没有任何期权报价、IV、Greeks）
- **Depends on:** FEAT-016、017、018、019、048（都已 Verified）
- **Scope:**
  - [x] Backend: Done（主要工作：换数据源、Greeks 计算）
  - [x] Frontend: Done（只改说明文字和来源标签，见"页面与交互"）

## 背景 / 用户故事
FEAT-016 用的 CBOE 延迟报价接口（Q136、Q144）现在对每个请求都返回 403，持仓页没有期权 bid / ask / mark、IV、Greeks，未实现盈亏和 Greeks 汇总都无法使用。Finnhub 只能兜底股价（期权链免费版 403）。用户要求改用 Yahoo Finance。

作为期权交易者，我想让系统改从 Yahoo Finance 自动获取持有合约的报价和 IV，由系统算出 Greeks，持仓页、未实现盈亏、批次盈亏、Greeks 汇总照常可用。

## 已定的口径（只引用）
- 沿用：Q138 立即刷新冷却（拉取频率见 Q268 / Q270）；Q140 / Q148 手动覆盖；Q141 / Q145 / Q146 失败与顶部提示；Q142 收盘快照；Q143 数据源已无该合约；Q147 启动时拉取；Q149 指数期权不拉取；Q150 / Q151 过期合约冻结；Q152–Q162（未实现盈亏）；Q163–Q173（Greeks 显示，来源部分除外）；Q174–Q182（汇总）；Q237–Q241（批次）。
- 本需求修订：Q136（数据源）、Q137（Greeks 改为后端计算）、Q139（Yahoo 没有 theo）、Q144（风险接受对象扩展到 Yahoo）。
- 本需求已定（全文见 decisions.md）：
  - Q261：Yahoo 为数据源；CBOE 代码保留、配置关闭；Finnhub 继续兜底股价。
  - Q262–Q264（用户委托 PM）：后端 Black-Scholes 计算 Greeks，无风险利率为配置固定值，q = 0（规则 5）。
  - Q265：收盘后按盘中最后有效报价，无效报价不覆盖。
  - Q266：接受 Yahoo 非官方接口风险。
  - Q267：主会话对真实 Yahoo 试探一次，见"可行性探测结果"。
  - Q268 / Q270：交易日 10:00–16:00 每整点 + 16:20；Q141 阈值改为 90 分钟。
  - Q269：报价时间取 Yahoo 标的报价时间（规则 6）。
  - Q271：有效报价定义与 lastPrice 兜底（规则 4）。

## 数据源（概要）
- **Yahoo Finance 期权链**：非官方 JSON 接口（Yahoo 自己网页在用），需要先做 cookie + "crumb" 会话握手。**一次请求只返回一个标的的一个到期日**，含：每个合约 `contractSymbol`（OCC 格式，同 FEAT-016 规则 3 匹配）、bid、ask、lastPrice、volume、openInterest、impliedVolatility、inTheMoney；以及标的报价（regularMarketPrice、regularMarketTime 等）。**不含 delta / gamma / theta / vega / theo。** 收盘后 bid / ask 常为 0，IV 可能为接近 0 的无效值。
- 后端直接调用该 JSON 接口；无头浏览器只在试探（Q267）证明 JSON 方式不可用时再议。具体接口、字段、时间格式由 backend-dev 依据试探结果写进契约。
- **Finnhub**：继续只作股价兜底（FEAT-016 规则 6）。**CBOE**：代码保留，配置关闭（Q261）。

## 规则
1. **数据源选择**（Q261）：配置项选择期权数据源，默认 Yahoo、CBOE 关闭；切回 CBOE 时行为完全同 FEAT-016。不在一次拉取中自动 Yahoo → CBOE 串联。
2. **拉取范围与请求**：跟踪范围同 FEAT-016 规则 1。每个 标的 + 持有的到期日 一次请求；只有股票、没有跟踪期权的标的一次请求只取标的价。会话握手结果复用，被拒绝时重新握手一次；请求串行、间隔约 1 秒；遇 429 本次该标的按失败处理、不重试。只存跟踪合约的报价。频率与时刻见 Q268 / Q270。
3. **存什么**：标的价、报价时间（Q269）、来源 `Yahoo`；每个跟踪合约：bid、ask、last、IV、volume、open interest、报价时间，以及规则 5 算出的 delta / gamma / theta / vega。theo 存空；Rho 不计算（存空，Q167 不显示）。
4. **Mark 与无效报价**（Q265、Q271）：
   - **有效报价**（Q271：ask > 0、bid ≥ 0、ask ≥ bid）→ mark = (bid + ask) ÷ 2，合约全部字段（含 IV、Greeks、报价时间）用本次值。
   - 否则（如收盘后 bid、ask 都为 0 或缺失）→ 本次无有效报价：库里有上次有效报价 → 全部保留不变，不显示失败标签，收盘快照也用保留值（Q265）；从未有过有效报价 → mark 用 lastPrice（> 0），否则 `无报价`（Q271）。
   - 手动 mark 何时被替换沿用 Q148（本次无有效报价时保留手动值）。
5. **Greeks 计算**（Q262–Q264 已定）：只在拉取时、对有效报价（或 Q271 的 lastPrice 兜底）计算并存储，查询时不重算。
   - 模型：Black-Scholes 欧式公式（美式期权近似）；输入：同一响应的标的价 S、行权价 K、Yahoo IV σ（小数，如 0.30）、无风险利率 r、股息率 q = 0。
   - r：配置文件中的固定年化值，默认 **4.00%**，用户可改，重启后在下一次有效报价时生效。
   - T = （到期日美东 16:00 − 报价时间）的实际时间差 ÷ 365 天。
   - 单位（同 FEAT-018，每股、多头视角）：Delta = ∂V/∂S；Gamma = 标的涨 1 美元时 Delta 的变化；Theta = 年化 θ ÷ 365（每日历日，通常为负）；Vega = 年化 vega ÷ 100（IV 每 1 个百分点）。
   - 精度：存未取整值；显示取整沿用 Q166（每张 4 位、持仓值 2 位，HALF_UP、负数远离 0）。
   - IV 缺失、≤ 0，或 S ≤ 0，或 T ≤ 0 → 该合约 delta / gamma / theta / vega 存空，页面按 Q172 显示 `—`；IV 仍照常显示（缺失则 `—`）。
   - 手动覆盖的 mark 或标的价不触发重算（同 Q170）。
6. **报价时间**（Q269）：每个标的取 Yahoo 标的报价时间（regularMarketTime），同一响应中所有合约共用。
7. **失败**：Yahoo 某标的请求失败（含 403 / 429 / 格式变化）→ 按 FEAT-016 规则 6（Finnhub 兜底股价、期权保留上次、行标 `行情未更新`）。会话握手失败 → 本次所有标的按失败处理。一个标的的任一到期日请求失败 → 该标的整体按失败处理（不部分更新）。
8. **合约状态**：同 FEAT-016 规则 7。Yahoo 该到期日响应里没有该合约、或该到期日不在 Yahoo 列表中，都算"链中已无该合约"。
9. **已有数据**：库里已存的 CBOE 报价（含 CBOE 的 theo / Greeks）不迁移、不删除，作为"上次报价"显示、来源仍为 CBOE，直到被 Yahoo 有效报价替换；收盘快照历史不改。
10. **不影响记账**：Yahoo 不可达、握手失败时后端正常启动，录入 / 平仓 / 到期处理照常。
11. **测试**：自动化测试一律不访问真实 Yahoo / CBOE / Finnhub，用现有的假数据源与可设时钟机制（扩展支持按到期日响应、0 bid/ask、无效 IV、握手失败、429）。

## 需要的数据和操作（给 backend）
- Yahoo 数据源实现（握手、按 标的 + 到期日 取链、取标的价）、数据源配置切换（规则 1）、无风险利率配置（规则 5）、频率（Q268 / Q270）。
- Greeks 计算（规则 5），结果存到 FEAT-016 已有的合约报价存储，FEAT-018 / 019 / 048 照常读取。
- 持仓行、行情状态的现有字段不变；来源增加 `Yahoo`；给前端 Greeks 说明所需的信息（Greeks 来源 / 是否系统计算、所用利率）。
- 假数据源与 e2e 测试支持接口扩展（规则 11）。

## 页面与交互（给 frontend）
- 持仓页结构、列、按钮都不变。只改文字：
  - Greeks 表格上方说明改为：`IV 来自 Yahoo Finance 延迟报价，Greeks 由系统按 Black-Scholes 计算（无风险利率 <r>%），与券商数值可能略有差异；持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考`（r 由后端给出）。
  - 手动 mark 时的 Greeks 标注（Q170）按来源：Yahoo 为 `Greeks 按 Yahoo 报价计算（MM-DD HH:mm），未随手动 Mark 调整`；仍为旧 CBOE 数据时保留原文字。
  - 标的价来源悬停：Finnhub 保持 `来源 Finnhub`；Yahoo 不加悬停（同原 CBOE）。
- 前端不算 mark、不算 Greeks，只显示后端值和文字。

## 验收标准
场景（共用）：今天 2026-11-20（星期五），测试配置 r = 0.00%、数据源 Yahoo。账户 A：P1 STO 1 张 QRTV 2027-02-19 50P；P2 STO 1 张 QRTV 2026-12-18 45P。账户 B：P3 BTO 2 张 QRTV 2027-02-19 50C；持有 IXQZ 100 股（无 IXQZ 期权）。假 Yahoo：QRTV 标的价 50.00、报价时间 11-20 10:00 美东；2027-02-19 链：50P bid 3.90 / ask 4.10 / last 3.95 / IV 0.3000，50C bid 4.00 / ask 4.20 / IV 0.3000；2026-12-18 链：45P bid 0.60 / ask 0.70 / IV 0.3500；IXQZ 标的价 61.10。10:00 拉取。标的均为虚构，multiplier 100。

1. **Given** 场景 **When** 10:00 拉取成功 **Then** P1 显示 标的价 50.00、Bid / Ask 3.90 / 4.10、Mark 4.00、报价时间 11-20 10:00；P3 Mark 4.10；P2 Mark 0.65；IXQZ 现价 61.10；未实现盈亏按这些 mark 照常计算。
2. **Given** 场景 **When** 拉取 **Then** 假 Yahoo 只收到 3 个数据请求（QRTV 2027-02-19、QRTV 2026-12-18、IXQZ 标的价）和 1 次会话握手；CBOE 没有被请求。**When** P2 BTC 平仓后再拉取 **Then** 不再请求 QRTV 2026-12-18。
3. **Given** 场景 **When** 打开账户 A 持仓页并打开 `显示 Greeks` **Then** P1：IV `30.00%`；Delta `+47.01`（`−0.4701`）；Gamma `−5.30`（`0.0530`）；Theta `+1.63`（`−0.0163`）；Vega `−9.95`（`0.0995`）。
4. **Given** 场景 **When** 切换到账户 B **Then** P3：Delta `+105.98`（`0.5299`）；Gamma `+10.61`；Theta `−3.27`；Vega `+19.89`；按标的 Greeks 汇总 QRTV 期权 Delta `+105.98`，美元敞口按标的价 50.00 计算。
5. **Given** 配置 r 改为 5.00% 并重启 **When** 拉取成功 **Then** P1 每张 Delta `−0.4371`、持仓值 `+43.71`。
6. **Given** 10:00 拉取成功；16:20 拉取时 2027-02-19 链中 50P、50C 都是 bid 0 / ask 0 / last 3.95、IV 0.00001，标的价 50.30 **When** 拉取 **Then** P1 仍为 Mark 4.00、IV `30.00%`、Delta `+47.01`、报价时间 11-20 10:00，无失败标签；标的价 50.30；当天收盘快照中 P1 mark 为 4.00。**Given** 15:00 新开 P4 STO 1 张 QRTV 2027-02-19 45P，16:20 时 P4 为 bid 0 / ask 0 / last 1.10 / IV 0 **Then**（Q271）P4 Mark 1.10，Greeks 各列 `—`；若 last 也为 0 **Then** P4 `无报价`。
7. **Given** 10:00 拉取成功 **When** 11:00 假 Yahoo 对 QRTV 返回 403，假 Finnhub QRTV 50.20 **Then** QRTV 标的价 50.20（来源 Finnhub），P1、P2、P3 保留 10:00 的值并标 `行情未更新（上次：11-20 10:00）`。**When** 11:00 起会话握手持续失败，到 11:31（Q270：阈值 90 分钟） **Then** 顶部显示 `行情未更新（上次：2026-11-20 10:00 美东）`。
8. **Given** P2 在 10:00 有报价 **When** 11:00 拉取成功但 2026-12-18 响应中没有 P2 **Then** P2 显示 Mark 0.65，标 `数据源已无该合约（最后报价：11-20 10:00）`。
9. **Given** 10:00 拉取成功，把 P1 Mark 手动设为 4.20 **When** 查看 **Then** Greeks 不变，标注 `Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整`；表格上方说明含 `Black-Scholes` 和 `无风险利率 0.00%`。
10. **Given** 升级前库里有 P1 的 CBOE 报价（mark 3.50，来源 CBOE），升级后 Yahoo 不可达 **When** 用 `gradlew.bat bootRun` 启动并打开持仓页 **Then** 后端正常启动，P1 显示 3.50 并标 `行情未更新`，录入交易照常保存。**Given** 配置数据源改为 CBOE **When** 拉取 **Then** 只请求假 CBOE，行为同 FEAT-016。

## 边界情况
- **到期日当天**：16:00 后 T ≤ 0，Greeks 为 `—`（规则 5）；之前正常计算。已过期未处理：冻结同 Q150 / Q171。
- **Yahoo IV 异常大**（如 > 500%）：照常计算，不设上限。
- **拆股调整合约 / 指数期权**：同 FEAT-016（无报价，可手动覆盖）。
- **同一合约多个账户**：共用一份报价与 Greeks。
- **夏令时期间的 T**：按真实时间差。
- **持有的到期日很多**：请求数随之增长，串行 + 间隔使单次拉取变长，不影响页面。

## Out of Scope
- 无头浏览器方案（除非试探证明 JSON 方式不可用，届时另议）。
- 美式期权定价模型（二叉树等）、股息率、IV 曲面、自动获取利率。
- 自动在 Yahoo / CBOE 之间切换；付费数据源；券商接入（FEAT-034）。
- 期权链浏览、未持有合约的报价；指数期权（FEAT-044）。

## Open Questions
无。Q261–Q271 已定（见 decisions.md）。

## 可行性探测结果
主会话 2026-11-20 盘中（marketState = `REGULAR`）执行一次，标的用 AAPL（通用标的，不用用户持仓）。结论：**JSON 方式可用，不需要无头浏览器。**
- 握手：`GET https://fc.yahoo.com/` 返回 404，但会种 cookie；带 cookie `GET https://query2.finance.yahoo.com/v1/test/getcrumb` 返回 crumb（纯文本，11 个字符）。请求需带浏览器 User-Agent。
- 期权链：带 cookie `GET https://query2.finance.yahoo.com/v7/finance/options/{SYMBOL}?crumb=...` → 200，约 60 KB。结构 `optionChain.result[0]`：`underlyingSymbol`、`expirationDates`（全部到期日，Unix 秒，本次 21 个）、`strikes`、`quote`、`options[0]`。
- **不带 `date` 只返回最近一个到期日**的 `calls` / `puts`；其它到期日需加 `&date=<到期日 Unix 秒>`。
- `quote` 含 `regularMarketPrice`、`regularMarketTime`（Unix 秒）、`marketState`、`bid`、`ask` 等。
- 合约字段：`contractSymbol, strike, currency, lastPrice, change, percentChange, volume, openInterest, bid, ask, contractSize, expiration, lastTradeDate, impliedVolatility, inTheMoney`。`impliedVolatility` 为小数（如 0.6455 = 64.55%），时间字段为 Unix 秒。**确认没有 delta / gamma / theta / vega / theo。**
- 样例（盘中）：2026-11-20 327.5 C，bid 17.60、ask 18.95、last 17.98、IV 0.6455。
- 未测：收盘后 bid / ask / IV 的表现（只允许一次调用，Q265 / Q271 的规则按"可能为 0 或缺失"处理）。真实响应未提交。

## Implementation Notes
（dev 填写）

### Backend
- **迁移** `V19__yahoo_quotes.sql`：`underlying_quote` 重建 `ck_underlying_quote_source`（允许 `YAHOO`）、加 `last_yahoo_result`；`option_quote` 加 `quote_source`（已报价行填 `CBOE`）+ 约束 `ck_option_quote_source`（取值 + `(quote_time IS NULL) = (quote_source IS NULL)`）；`option_quote_close` 加 `quote_source`（全部填 `CBOE`）。不改任何已存报价值；Q60 备份照旧在迁移前自动执行（文件名 `…-backup-before-V19-…`）。test fixture `db/failing-migration/V19__simulated_failure.sql` 改名 `V20__…`。
- **domain（纯 Java）**：新 `QuoteSource`、`YahooChain` / `YahooOption`、`YahooQuoteRules`（VALID / LAST_ONLY / NONE、"库里有可用报价"、是否替换）、`BlackScholes`（T 到 到期日 16:00 美东的真实秒数 / 365 天；N 用 Hart / West 双精度算法，测试对 80 位精度级数验证误差 < 1e-14）；`QuotePrices.mark(source, …)`（Yahoo Q271）、`MarkBasis.LAST`、`PriceSource.YAHOO`、`SourceResult.HANDSHAKE_FAILED`；`OptionState` / `UnderlyingState` / `SymbolFetch` / `RowGreeks` / `GreeksValuation.Quote` 加来源字段（保留旧构造器，旧测试不用改）；`QuoteMerge.yahooOption`（替换 / 保留表、手动 mark 只在 VALID 替换时删除）；`FetchSchedule`（10:00–16:00 整点 + 16:20）；`QuoteLabels.STALE_AFTER` = 90 分钟；`GreeksValuation` 按来源选 `manualMarkNote`。
- **service**：`QuoteProperties` 新字段与校验（`option-source`、`risk-free-rate-percent` 0–20、`request-interval-millis`、`yahoo.*`）；`QuoteSettings`（生效的数据源 / 利率，e2e 可临时改）；`YahooSession`（内存 crumb）；`QuoteTransport.yahooHandshake()` / `yahooOptions(...)`；`HttpQuoteTransport` 独立的 Yahoo `HttpClient` + 内存 `CookieManager`、浏览器 UA、请求间隔（可注入 `Sleeper`）；`InMemoryQuoteTransport` 假 Yahoo（握手、`byDate` / `default`、回复序列）与请求记录 `kind` / `expirationDate`；`QuoteParsers.parseYahoo` / `yahooCrumb`；`QuoteFetchService` 按数据源分支（Yahoo：按需握手、401 / 403 重新握手一次并重发、429 不重试、任一到期日失败 = 整标的失败、Finnhub 兜底、到期日不在 `expirationDates` = 链中没有、Greeks 在写入时算）；`QuoteScheduler` cron 改为 `0 0,20 10-16 * * MON-FRI`。
- **web**：`status` 加 `optionSource` / `riskFreeRatePercent`；`results[]` 加 `optionSource` / `yahooResult`；`greeks.source`；测试支持 `PUT …/settings`、`sources.yahoo`、`stored` 新字段，`sources` 与 `reset` 清 Yahoo 会话，`reset` 恢复设置。
- **配置**：`application.yml` 默认 `yahoo`、`4.00`、`1000` ms；`test` / `e2e` 为 `cboe`、`0.00`、`0` ms，Yahoo 地址指向 `127.0.0.1:9`。
- **测试**：`quote/domain/BlackScholesTest`、`YahooQuoteRulesTest`、`QuoteDomainTest`（新时刻 / 阈值）、`quote/service/QuoteParsersTest`、`HttpQuoteTransportTest`（本地假服务：cookie 回传、UA、crumb 校验、`date` 参数、间隔）、`quote/web/YahooQuotesControllerTest`（验收 1–10 + 重新握手成功、429）、`account/MigrationV19YahooQuotesTest`（V18 文件库 → V19：备份、值不变、来源填充、约束）。`MarketDataControllerTest` 与 `valuation/web/*ControllerTest` 中 10:15 / 10:30 等拉取时刻改为整点，30 分钟阈值用例改为 90 分钟；`MigrationV18CloseLotsTest` 的备份文件名改为按最新版本。
- **设计决定**：Greeks 内部用 `double`（只在 ln / exp / √ / N），输入输出 `BigDecimal`，结果 8 位小数 HALF_UP（写入 architecture.md）；r 不随每条 Greeks 存；首次 NONE 不写库；之后的 NONE 保留 LAST 报价；`settings` 的利率非数字由 JSON 绑定失败给 400 `BAD_REQUEST`，超范围给 `VALIDATION_ERROR`。
- **契约更正**：`docs/api/FEAT-052.md` 复核表中 r = 5% 的 Vega 应为 `0.09849321`（原写 `0.09849322`，双精度实算 0.098493214988…；显示值 0.0985 不变）。
- **已知限制**：同契约"已知限制"。另：没有跟踪标的时的 run 按 FEAT-016 规则算成功，所以启动时还没有持仓、之后才录入的情况下，顶部提示按"上次成功 = 启动时"计 90 分钟，而不是"从未成功获取"。

### Frontend
- 新增 `frontend/src/components/greeks/GreeksNote.tsx`：持仓页 `显示 Greeks` 打开时的灰字说明（testid 仍为 `positions-greeks-note`），读 `GET /api/market-data/status`（与顶部 `MarketDataBar` 共用同一个 TanStack Query 缓存 key，不增加请求）。`greeksLabels.ts` 的 `greeksNote(status)`：`optionSource = CBOE` → FEAT-018 原文；`YAHOO` → 本需求新文字，`<r>` 为后端 `riskFreeRatePercent` 原样；状态未返回 / 请求失败时用 Yahoo 文字、利率显示 `—`（契约允许前端自定）。原常量 `GREEKS_NOTE` 改名为 `GREEKS_NOTE_CBOE`。
- 手动 mark 的 Greeks 标注照旧原样显示后端 `greeks.manualMarkNote`（Yahoo / CBOE 文字由后端选）。
- 来源：`YAHOO` 不悬停（只有 `FINNHUB` 悬停 `来源 Finnhub`，逻辑未变）；`position-underlying-price` 的 `data-source` 可为 `YAHOO`。`markBasis = LAST` 页面不单独显示，mark 原样。
- 顶部"行情未更新"提示完全按后端 `stale` / `warning`，前端没有写死 30 分钟的地方，无需改动。
- 类型（`src/api/types.ts`）：新增 `QuoteSource`；`UnderlyingQuoteSource` 加 `YAHOO`；`MarkBasis` 加 `LAST`；`OptionQuote.source` 改为 `QuoteSource`；`OptionGreeks.source`；`MarketDataStatus.optionSource` / `riskFreeRatePercent`；`MarketDataRefreshResult.optionSource` / `yahooResult`，`cboeResult` 改为可空。
- 测试（mock）：`greeksLabels.test.ts`、`quoteLabels.test.ts`、`pages/Greeks.test.tsx`、`pages/MarketData.test.tsx`（新增 Yahoo 说明、利率 `—` 兜底、Yahoo 手动 mark 标注、Yahoo 标的价不悬停 / LAST mark 显示）。
- 无新增 testid。
- 已知限制：说明里的 r 是后端当前配置值（契约已知限制），与已存 Greeks 计算时的 r 可能暂时不一致；未与真实后端联调（后端未 Done）。
- 给 qa：`e2e/tests/feat-018-greeks.spec.ts` 断言了旧 CBOE 说明 / 标注文字，Yahoo 数据源下需按新文字更新。
