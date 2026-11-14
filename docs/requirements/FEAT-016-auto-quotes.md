# FEAT-016: 自动行情（只读，CBOE 延迟报价 + Finnhub 股价兜底）

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-003（期权持仓页）、FEAT-011（股票持仓）、FEAT-033（多账户）、FEAT-009（交易日历、Finnhub key），都已 Verified
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
作为一个期权交易者，我想让系统自动拉取我持有的标的和合约的报价，在持仓页看到标的价、bid / ask / mark 和报价时间，行情过期时有提示，必要时能手动覆盖；为未实现盈亏（FEAT-017）和 Greeks（FEAT-018/019）准备数据。原 FEAT-020 已并入本需求。

## 已定的口径（只引用）
- Q135：自动拉取为主，手动只作兜底覆盖。Q136：CBOE 延迟报价 + Finnhub `quote` 股价兜底。Q137：IV / Greeks 取自 CBOE，不做 Black-Scholes。
- Q138：交易时段每 15 分钟 + 16:20 一次，非交易日不拉；"立即刷新"按钮 1 分钟内不能再按。
- Q139：mark = bid/ask 中间价（两者都 > 0），否则 theo，都没有则"无报价"。
- Q140：标的价、合约 mark 都可手动覆盖，有效到下一次成功拉取，显示"手动"标签和录入时间。
- Q141：交易时段超过 30 分钟无成功拉取 → 顶部提示；单标的失败只标该标的的行；非交易时段不提示；失败保留上次报价。
- Q142：存最新一份 + 每天收盘后一份。
- Q143：曾有报价、现已从 CBOE 消失的合约保留最后报价并标注；从未有过报价的显示"无报价"。
- Q144：接受 CBOE 非官方接口风险（个人使用）；失败时股价用 Finnhub 兜底，期权保留上次报价；接口变化另立需求。
- 契约阶段补充（Q145–Q151）：
  - Q145：CBOE 失败、Finnhub 成功 → 股价用 Finnhub；期权行保留上次报价并标"行情未更新"，从未有报价的显示"无报价"并带同样标签。
  - Q146：有跟踪标的但从未成功拉取过 → 任何时间都显示顶部提示"行情未更新（从未成功获取）"（Q141 的例外）。
  - Q147：交易日 09:30–16:20 之间启动时，立即后台拉一次。
  - Q148：手动 mark 只在该合约出现在成功拉取的链中且自动 mark 不为空时被替换。
  - Q149：指数期权（SPX、NDX、RUT、VIX、XSP 等）不拉取，一律"无报价"（可手动覆盖）；完整支持见 FEAT-044。
  - Q150：到期日 < 美东今天的合约（含已过期未处理）冻结、不再跟踪；到期日当天仍跟踪；只剩过期未处理持仓的标的不再拉取；冻结行显示库里最后报价（没有则"无报价"），不显示失败/过期提示，仍可手动覆盖。
  - Q151：已过期不再跟踪且曾有报价的合约标"已到期（最后报价：…）"，从未有报价的显示"无报价"；只剩过期合约的标的显示库里最后的标的价，不显示失败/"行情未更新"标签。

## 数据源（概要）
- **CBOE**：`https://cdn-api.cboe.com/api/global/delayed_quotes/options/{SYMBOL}.json`，无 key，按标的返回全部期权链：
  `{timestamp, data:{symbol, current_price, bid, ask, close, prev_day_close, iv30, …, options:[{option:"XYZ261218C00360000", bid, ask, iv, delta, gamma, theta, vega, rho, theo, last_trade_price, last_trade_time, volume, open_interest, …}]}}`
  `option` 为 OCC 格式：标的 + YYMMDD + C/P + 行权价 × 1000（8 位）。约 15 分钟延迟。
- **Finnhub**：`quote?symbol=` 只给股价；key 只放请求头（沿用 FEAT-009 规则 1）。

## 规则
1. **拉取范围**：所有账户的跟踪合约（当前持有且到期日 ≥ 美东今天的期权，Q150）和股票持仓涉及的标的（去重，跨账户共用一份行情）。已无持仓、或只剩过期未处理持仓的标的不再拉取。
2. **存什么**：每个标的：标的价（`current_price`）、bid、ask、前收、来源、报价时间（CBOE `timestamp`）；只存**跟踪合约**（规则 1）的报价：bid、ask、last、theo、iv、delta、gamma、theta、vega、rho、volume、open interest、报价时间。IV / Greeks 只存不显示（给 FEAT-018）。历史按 Q142：16:20 那次拉取另存一份当天收盘快照。
3. **合约匹配**：按 标的 + 到期日 + 行权价 + Call/Put 匹配 CBOE `option` 代码。
4. **Mark**：按 Q139，由后端算。
5. **刷新**：按 Q138，交易日和时段用 FEAT-009 日历（含 FEAT-042 临时休市）。"立即刷新"任何时间都可按（含非交易日），上次按下后 1 分钟内按钮禁用；后端对 1 分钟内的重复请求不再拉取。
6. **失败**（Q141/Q144）：单个标的失败不影响其它标的。CBOE 失败或无 `current_price` 时用 Finnhub 取股价（来源 Finnhub；无 key 不调）。失败时期权和股价都保留上次成功的值，不清空；该标的的行显示 `行情未更新（上次：<该标的上次成功时间>）`。顶部提示见 Q141。
7. **合约状态**（Q143）：
   - 本次有报价 → 正常显示；
   - 曾有报价、本次标的拉取成功但链中已无该合约 → 保留最后报价，标 `数据源已无该合约（最后报价：<报价时间>）`；
   - 从未有过报价 → `无报价`；
   - 已冻结（到期日 < 美东今天，Q150/Q151）→ 曾有报价的显示库里最后报价，标 `已到期（最后报价：<报价时间>）`；没有则 `无报价`；不再更新，不显示规则 6 的失败标注和 `数据源已无该合约`；仍可手动覆盖（规则 8）。只剩过期合约的标的显示库里最后的标的价，不显示规则 6 的标签。
8. **手动覆盖**（Q140）：可设标的价或合约 mark（> 0）；显示覆盖值 + `手动` 标签 + 录入时间（美东）；该标的价 / 该合约下一次成功拉取到报价时被自动值替换（拉取失败或链中无该合约则继续保留）；用户也可清除。
9. **不影响记账**：拉取失败、无 key、CBOE 不可达时后端正常启动，录入 / 平仓 / 到期处理照常。
10. 测试中 CBOE、Finnhub 和当前时间都必须可替换（假服务、可设时钟）。

## 需要的数据和操作（给 backend）
- 定时拉取（规则 5）；"立即刷新"操作，返回各标的成功 / 失败。
- 期权持仓每行加：标的价、bid、ask、mark、报价时间、来源、合约状态（规则 7）、是否手动及录入时间、该标的是否本次失败及上次成功时间。股票持仓每行加：现价、报价时间、来源、手动 / 失败信息。
- 行情状态：上次成功拉取时间、是否需要顶部提示及提示文字、上次"立即刷新"时间。
- 设置 / 清除手动覆盖（标的价、合约 mark）。

## 页面与交互（给 frontend）
- **期权持仓页**：新增列 `标的价`、`Bid / Ask`、`Mark`、`报价时间`（美东 `MM-DD HH:mm`）。规则 6、7 的标注显示在该行；Finnhub 来源的标的价悬停显示"来源 Finnhub"。
- **股票持仓**：新增 `现价`、`报价时间`。
- **手动覆盖**：Mark 和标的价单元格可点"手动设置"，输入价格保存；手动值旁 `手动 11-23 10:05` 标签，可"清除"。
- **顶部**：后端给出提示时显示其文字；右侧 `立即刷新` 按钮，刷新中和 1 分钟内禁用（悬停说明"1 分钟内只能刷新一次"），完成后列表更新。
- 前端只显示后端值，不算 mark、不判断过期。

## 验收标准
场景：今天 2026-11-23（星期一），`.env` 有 Finnhub key。账户 A：STO 1 张 QRTV 2026-12-18 50P（P1）；账户 B：持有 IXQZ 100 股、STO 1 张 IXQZ 2026-12-18 60C（P2）、STO 1 张 QRTV 2026-12-18 50P（P3）。假 CBOE：QRTV `current_price` 52.40、`timestamp` 11-23 09:45 美东，含 `QRTV261218P00050000` bid 1.20 / ask 1.30 / theo 1.24 / iv 0.3512 / delta −0.3021 / theta −0.0456；IXQZ `current_price` 61.10、`timestamp` 09:45，含 `IXQZ261218C00060000` bid 2.10 / ask 2.30。10:00 进行第一次拉取。标的均为虚构。

1. **Given** 场景 **When** 10:00 拉取成功 **Then** 账户 A 的 P1 显示 标的价 52.40、Bid / Ask 1.20 / 1.30、Mark 1.25、报价时间 11-23 09:45；账户 B 的 P3 同上，P2 显示 61.10、2.10 / 2.30、2.20，IXQZ 股票现价 61.10。
2. **Given** 场景，QRTV 响应含 3,400 个合约 **When** 拉取 **Then** 只请求 QRTV、IXQZ，只保存 2 个持有合约的报价，P1 存有 iv 0.3512、delta −0.3021、theta −0.0456、theo 1.24（页面不显示）。**When** P1、P3 都 BTC 平仓后再次拉取 **Then** 不再请求 QRTV。
3. **Given** 场景但 P2 的报价为 bid 0 / ask 0.05 / theo 0.03 **When** 拉取 **Then** P2 Mark 0.03；若 theo 也缺失 **Then** P2 显示 `无报价`。
4. **Given** 首次拉取（之前从无 IXQZ 报价），假 CBOE 对 IXQZ 返回 500，假 Finnhub 当前价 61.05 **When** 10:00 拉取 **Then** IXQZ 现价 61.05（来源 Finnhub）；P2 显示 `无报价`（从未有过报价）；P1、P3 正常为 1.25。
5. **Given** 账户 A 另有 STO 1 张 QRTV 2026-12-18 47.5P（P4），链中从无该合约；P1 在 10:00 有报价，10:15 拉取成功但链中已无 P1 **When** 查看持仓页 **Then** P4 显示 `无报价`；P1 仍显示 Mark 1.25，标注 `数据源已无该合约（最后报价：11-23 09:45）`。
6. **Given** 10:00 拉取成功；10:15 起 IXQZ 的 CBOE 和 Finnhub 都失败，QRTV 正常 **When** 10:15 后查看 **Then** IXQZ 的行显示 `行情未更新（上次：11-23 10:00）`，P2 仍为 2.20、现价仍为 61.10，无顶部提示。**When** 10:15、10:30 两个标的全部失败，到 10:31 **Then** 顶部显示 `行情未更新（上次：2026-11-23 10:00 美东）`，所有值保留；下一次成功后提示消失。
7. **Given** 10:00 拉取成功 **When** 16:20 拉取成功 **Then** 另存一份 11-23 收盘快照；之后到次日 09:30 前不再定时拉取，也不显示顶部提示。**Given** 2026-11-26（感恩节） **Then** 全天不定时拉取。
8. **Given** 10:00 拉取后，10:05 把 P1 的 Mark 手动设为 1.40、QRTV 标的价设为 52.00 **When** 查看 **Then** 显示 1.40、52.00，各带 `手动 11-23 10:05`；账户 B 的 P3 也显示 1.40。**When** 10:15 拉取 QRTV 失败 **Then** 仍为 1.40；**When** 10:30 拉取成功 **Then** 恢复为 1.25、52.40，标签消失。
9. **Given** 10:00 拉取后，10:02 假 CBOE 中 P1 改为 bid 1.30 / ask 1.40 **When** 点 `立即刷新` **Then** P1 Mark 变为 1.35，按钮禁用至 10:03；10:02:30 绕过页面再次请求刷新 **Then** 不发起新的 CBOE 请求。
10. **Given** `.env` 没有 `FINNHUB_API_KEY`、假 CBOE 不可达、从未成功拉取过 **When** 用 `gradlew.bat bootRun` 启动并打开持仓页 **Then** 后端正常启动，所有持仓显示 `无报价`（从未有过报价）、顶部显示行情未更新提示（从未成功）；STO 1 张 QRTV 2027-01-15 45P 照常保存。

## 边界情况
- **到期日当天**：仍跟踪，链中消失后按规则 7 保留最后报价。**已过期未处理的持仓**：冻结，见规则 7（Q150）。
- **指数期权**（SPX / SPXW 等，Q149 不拉取）、**拆股调整合约**（非 100 股、代码带数字后缀）：从未匹配到 → `无报价`，可手动覆盖（规则 8）；拆股见 FEAT-028。
- **同一合约在多个账户**：共用一份行情和手动覆盖（规则 1）。
- **CBOE 响应格式变化 / 字段缺失**：视为该标的失败（规则 6），不让整次拉取崩溃。
- **新开仓的合约**：下一次拉取前显示 `无报价`，可按立即刷新。
- **时区**：所有时间按美东显示。

## Out of Scope
- 未实现盈亏、市值（FEAT-017）；Greeks / IV 显示与汇总（FEAT-018/019）。
- 收盘快照的查看与图表（以后另立）。
- 未持有标的的报价、期权链浏览；实时行情、付费数据源、券商接入（FEAT-034）。
- 合并查看（FEAT-035）。

## Open Questions
无（Q135–Q151 已定）。

## Implementation Notes

### Frontend
- **新增**：`src/api/marketData.ts`（status / refresh / 设置与清除手动值，免 `X-Account-Id`）；`src/api/types.ts` 的 `UnderlyingQuote`、`OptionQuote`、`MarketDataStatus`、`MarketDataRefreshResponse` 等，`OptionPosition.underlyingQuote` / `optionQuote`、`StockPosition.quote`（类型中可选，兼容旧响应，缺失显示 `无报价`）；`src/components/quote/`：`MarketDataBar`（持仓页顶部：`warning` 原文 + 上次成功时间 + `立即刷新`）、`QuoteCells`（标的价 / 现价、Bid / Ask、Mark、报价时间）、`ManualQuoteModal`（手动设置）、`quoteLabels`、`marketDataRefresh`；`utils/format.formatMonthDayTime`（`MM-DD HH:mm`）。`client.ts` 把 `/api/market-data` 加入免账户前缀。
- **页面**：期权持仓表"张数"后加 `标的价`、`Bid / Ask`、`Mark`、`报价时间`；股票持仓表"持股"后加 `现价`、`报价时间`。`staleLabel`（标的价与期权链各自一个，分别显示在标的价、Mark 单元格）、`contractStatusLabel` 原样显示为标签；Finnhub 标的价悬停 `来源 Finnhub`；手动值旁 `手动 MM-DD HH:mm` + `清除`，每个价格单元格都有 `手动设置`。
- **立即刷新**：后端 `refreshAllowed = false` 或请求进行中禁用，悬停 `1 分钟内只能刷新一次`；完成后用响应的 `status` 并重新查询期权 / 股票持仓和状态；`performed: false` 显示 `1 分钟内只能刷新一次，本次未重新获取`；部分标的未更新时列出（只作提示）。状态每 60 秒轮询，冷却结束时另按 `refreshAvailableAt` 复查一次（1–60 秒，以后端判断为准）。
- **testid**：见 `docs/architecture.md` 前端部分"FEAT-016 testid"。
- **测试**：`src/pages/MarketData.test.tsx`（mock 后端：各种行情状态显示、顶部提示、刷新与冷却、设置 / 清除手动值、错误显示、无账户头）、`src/components/quote/quoteLabels.test.ts`、`src/utils/format.test.ts`、`src/api/client.test.ts`。
- **已知限制**：
  - 未接真实后端联调（后端开发中），只用 mock 测试；Q150（过期合约冻结）在契约中尚未体现，前端按后端字段原样显示，无需额外处理。
  - 同一行标的价和期权链都失败时（验收 6）两个单元格各显示一次 `行情未更新（上次：…）`。
  - 冷却结束的复查用浏览器时钟估算间隔；在 e2e 固定测试时钟下按钮状态以后端 `refreshAllowed` 为准，需重新查询（60 秒轮询或刷新页面）才会更新。

### Backend
- **新增**：`backend/src/main/java/com/optionmgr/quote/`：`domain/`（`QuotePrices` Q139 mark 与显示格式、`OccCode` / `ChainMatcher` 末尾 15 位解析 + root 后缀匹配 + 歧义、`QuoteMerge` 规则 6–8、`FetchSchedule` Q138 / Q147、`QuoteLabels` Q141 文案与顶部提示、`UnderlyingState` / `OptionState` / `SymbolFetch` 等）；`repository/`（5 个实体与仓库）；`service/`（`QuoteParsers`、`HttpQuoteTransport`、`InMemoryQuoteTransport`、`HeldPositionsService`、`QuoteFetchService`、`QuoteScheduler`、`QuoteQueryService`、`QuoteOverrideService`、`QuoteViews`、`QuoteWriteLock`、`QuoteProperties` / `QuoteConfig`）；`web/MarketDataController`；`testsupport/web/MarketDataTestSupportController`（仅 e2e）；迁移 `V16__market_data.sql`。
- **修改**：`OptionPositionService`（`openContracts(accountId)`，抽出 `replayInput`）；`OptionPositionController` / `ExpirationAdjustmentController`（持仓行加 `underlyingQuote`、`optionQuote`）；`StockPositionController` / `StockTradeDtos`（股票行加 `quote`）；`ErrorCode.QUOTE_TARGET_NOT_HELD`；`TestSupportController.reset` 也清空假数据源；`application.yml` / `application-e2e.yml` / `application-test.yml` 加 `optionmgr.quotes.*`；failing-migration fixture 改名 `V17__simulated_failure.sql`，`MigrationFromM1Test` 的备份名改为 `before-V16` / `before-V17`。
- **设计决定**：行情全系统一份（`/api/market-data/**` 不要求账户头）；网络调用在事务外，一次 run 的结果在一个事务里写入；run 由 `runLock` 串行化，写入与手动覆盖由 `QuoteWriteLock` 互斥；`QuoteScheduler` 每 5 分钟唤醒、由 `tick` 判断 Q138 时刻与 FEAT-009 交易日；Q150 已过期合约不再跟踪（显示时曾有报价的按 `MISSING_FROM_SOURCE`，Q151 标签 `已到期（最后报价：…）`），Q149 指数标的不拉取；mark 中间价精确保存（不四舍五入），显示至少 2 位小数；收盘快照表用自增 id + 唯一键（契约草案中的复合主键改为唯一约束）。
- **测试**：`quote/domain/QuoteDomainTest`、`quote/service/QuoteParsersTest`、`quote/service/HttpQuoteTransportTest`、`quote/web/MarketDataControllerTest`（验收 1–10、Q149、Q150 / Q151、歧义，in-memory 假数据源 + 可设时钟，不访问真实 CBOE / Finnhub）。另跑通 QA 的 `systemtest/Feat016*`（35 个）及受影响的持仓 / 股票 / 调整到期日 / 迁移 / 测试支持 / 日历测试。
- **已知限制**：CBOE `timestamp` 格式按用户 2026-11-14 实测实现，未在本环境调用真实接口；提前收盘日仍按 16:00 / 16:20；立即刷新为同步请求；收盘快照没有查询接口。
