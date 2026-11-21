# FEAT-052 测试报告

- **测试日期:** 2026-11-20
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat052YahooQuotesSystemTestBase.java、Feat052YahooQuotesAcceptanceSystemTest.java（15 个）、Feat052YahooQuotesBoundarySystemTest.java（25 个）、Feat052YahooRealHttpSystemTest.java（2 个，真实 HTTP 传输 + 本地假 Yahoo / Yahoo 不可达启动）
- **E2E 测试:** e2e/tests/feat-052-yahoo-quotes.spec.ts（12 个）

场景按需求：今天 2026-11-20（星期五，美东），测试配置 r = 0.00%。`test` / `e2e` profile 默认 `cboe`，所以每个 FEAT-052 测试都先用 `PUT /api/test-support/market-data/settings` 显式切到 Yahoo。虚构标的 QRTV / IXQZ。假 Yahoo 响应按需求"可行性探测结果"的真实结构构造：`optionChain.result[0]` 含 `underlyingSymbol`、`expirationDates`、`strikes`、`quote`、`options[0].calls/puts`；时间为 Unix 秒，IV 为小数，没有 Greeks。没有调用真实 Yahoo / CBOE / Finnhub。Greeks 由 QA 用 Python（erfc 实现的 N）独立按 Black-Scholes 复算，库里 8 位小数在 1e-8 内一致；r = 5% 的 vega 与契约表在第 8 位差 1，属于浮点取整，不作为断言。

## 运行结果（commit 前全量）
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试） | BUILD SUCCESSFUL，1679 个测试，0 失败、0 跳过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 54 个文件、810 个测试全部通过 |
| E2E：feat-052、016、017、018、019、048 | 63 passed（2.3 分钟） |

**选 E2E spec 的依据：** 在 `e2e/tests/` 里 grep 了 tick 时刻（`tickAt('…T10:15/10:30/…')`、09:30）、30 分钟提示、`market-data-stale-warning`、`positions-greeks-note` / `来自 CBOE`、`cboeResult`。
- 只有 016、017、018、019、048 用到 :15 / :30 / :45 时刻、旧阈值或说明文字，这几个已按 Q270 修改。
- 018 的说明文字在 e2e 默认的 CBOE 下保持原文，不受影响。
- 其它 spec 只在 10:00 拉取，不算受影响，留给里程碑的全量 E2E。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 10:00 拉取：P1 50.00、3.90 / 4.10、Mark 4.00、11-20 10:00；P3 4.10；P2 0.65；IXQZ 61.10；未实现盈亏照常 | Pass | Pass | API：来源 YAHOO、`quoteTime` = regularMarketTime、theo / rho 为 null、`lastYahooResult` SUCCESS。盈亏：P1 −400.00 / −20.00 / −5.26%，P2 −65.00 / +5.00 / +7.14%，P3 +820.00 / +20.00 / +2.50%，IXQZ +6,110.00 / +110.00 / +1.83%。E2E：标的价 `data-source=YAHOO`，无悬停提示 |
| 2 | 只收到 1 次握手 + 3 个数据请求，CBOE 未被请求；P2 BTC 后不再请求 2026-12-18 | Pass | — | 请求记录严格顺序为 HANDSHAKE、IXQZ（无 date）、QRTV 12-18、QRTV 02-19；没有 CBOE 和 Finnhub 请求。只存 3 个持有合约。下一次 run 复用会话，不再握手 |
| 3 | 账户 A：P1 IV 30.00%、+47.01（−0.4701）/ −5.30 / +1.63 / −9.95 | Pass | Pass | P2 也核对：+12.91 / −4.32 / +1.81 / −2.93。`greeks.source = YAHOO` |
| 4 | 账户 B：P3 +105.98（0.5299）/ +10.61 / −3.27 / +19.89；汇总 QRTV 期权 Delta +105.98，按 50.00 计算 | Pass | Pass | 汇总行 0.00 / +105.98 / 50.00 / +5,299.00 |
| 5 | r = 5.00% → P1 −0.4371、+43.71 | Pass | Pass | 改 r 后已存的 Greeks 不重算，下一次有效报价才用新 r。E2E 说明文字显示 5.00% |
| 6 | 16:20 bid/ask 为 0、IV 0.00001、标的价 50.30 → P1 保持 4.00 / 30.00% / +47.01 / 10:00，无失败标签；标的价 50.30；快照 4.00；P4 last 1.10 → Mark 1.10、Greeks 为 —；last 也为 0 → 无报价 | Pass | Pass | 快照行 bid 3.90 / ask 4.10、来源 YAHOO、10:00；P4 盈亏 −110.00 / −5.00 / −4.76% |
| 7 | 11:00 QRTV 403，Finnhub 50.20 → 标的价 50.20（FINNHUB），P1–P3 保留并标 行情未更新（上次：11-20 10:00）；握手持续失败，11:30 不提示、11:31 提示 | Pass | Pass | 403 → 重新握手一次 → 重发仍 403 → HTTP_ERROR，不再请求 02-19。E2E 悬停显示 `来源 Finnhub` |
| 8 | 12-18 响应中没有 P2 → 0.65 + 数据源已无该合约（最后报价：11-20 10:00） | Pass | Pass | 最后一次的 Greeks 保留 |
| 9 | 手动 Mark 4.20 → Greeks 不变，显示 Yahoo 版标注；说明含 Black-Scholes、无风险利率 0.00% | Pass | Pass | 状态接口给出 `optionSource = YAHOO`、`riskFreeRatePercent = "0.00"`。E2E 经页面手动设置，盈亏 −420.00 / −40.00 / −10.53% |
| 10 | 库里已有 CBOE 报价 3.50，Yahoo 不可达 → 正常启动，P1 3.50 + 行情未更新，录入照常；数据源改 CBOE → 只请求 CBOE，行为同 FEAT-016 | Pass | Pass | NONE、LAST_ONLY 都保留 CBOE 报价（含 CBOE 的 Greeks 和 CBOE 标注原文），有效报价才替换为 YAHOO（theo / rho 清空）。真实 HTTP：Yahoo 不可达时应用启动、refresh 为 HANDSHAKE_FAILED、交易照常保存。CBOE 模式：Q139 theo mark，r 不参与计算，即使配了假 Yahoo 也不请求 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 握手失败（getcrumb 401 / 不可达）→ 所有标的 HANDSHAKE_FAILED，不发数据请求，Finnhub 兜底；从未成功时提示 `从未成功获取`；握手恢复后成功 | Pass | API |
| 数据请求 401 → 重新握手 → 重发成功；同一 run 再遇 401 → HTTP_ERROR，不再握手；重新握手失败 → 当前标的和后续标的都 HANDSHAKE_FAILED | Pass | API；真实 HTTP 同样验证了 401 → 重新握手 |
| 429 → HTTP_ERROR（详情含 HTTP 429），不重试、不重新握手，继续处理下一个标的 | Pass | API |
| 一个到期日失败 → 整个标的失败：先失败则不再请求后面的到期日；后失败则前面已成功的响应也丢弃，标的价走 Finnhub | Pass | API |
| LAST_ONLY 只填空行：16:20 写入 last 1.10（IV 0.35，Greeks 按 S 50.30、T 91 天计算 +23.44 …）→ 次日 NONE 保留 → LAST_ONLY 1.20 替换 → VALID 1.35 → 之后 LAST_ONLY 保留 VALID | Pass | API |
| Q271：bid 0 / ask 0.05 → 0.025（MID）；ask < bid → LAST 且 Greeks 照算；bid、ask、last 都缺失 → 无报价 | Pass | API |
| Greeks 存空：IV 缺失 / 为负（仍显示 −10.00%）/ "N/A" / 0；S = 0（该响应的合约存空，标的价取另一个响应）；到期日当天 16:00 报价时 T = 0 | Pass | API；E2E 覆盖 IV 0 |
| 夏令时 T（2027-03-19，119 天 5 小时）；IV 600% 不设上限 | Pass | API |
| 手动 mark 在 NONE、LAST_ONLY、链中没有、标的失败时都保留，VALID 时被替换；手动标的价不触发重算 | Pass | API |
| 请求的到期日不在 expirationDates 里 → 算作数据源已无该合约；INVALID_RESPONSE 4 种（非 JSON、error 非空、result 为空、缺 regularMarketTime）；SPX 不请求 | Pass | API |
| Q270 时刻：10:00–16:00 每个整点 + 16:20；09:30 / 09:45 / 10:15 / **10:20** / 10:30 / 10:45 / **11:20** / **15:20** / 15:45 / 16:15 都是 NOT_A_SLOT，不发请求；一天只握手一次 | Pass | 定时器 cron `0 0,20 10-16 * * MON-FRI` 在每小时 :20 也会触发。确认 `FetchSchedule.slotAt` 对这些时刻返回空，不会拉取（Yahoo 与 CBOE 两组时刻测试都加了 10:20 / 15:20） |
| 90 分钟阈值：11:30 不提示、11:31 提示；次日按 09:30 起算，11:00 不提示、11:01 提示 | Pass | API + E2E |
| settings：大小写不敏感，5 → "5.00"，4.125；0 / 20 为边界；null / {} 恢复配置；未知数据源、利率 < 0 或 > 20 → VALIDATION_ERROR；"abc"、JSON 损坏 → BAD_REQUEST；reset 恢复设置并清会话；PUT sources 丢弃会话 | Pass | API |
| 真实 HTTP：cookie URL（404 + Set-Cookie）→ 带 cookie 请求 getcrumb → `options/{SYMBOL}?crumb=…&date=`，date 为到期日 00:00 UTC（2027-02-19 = 1802995200），只有股票的标的不带 date；每个请求带 Mozilla UA 和 cookie；failureDetail 中没有 crumb 和 cookie | Pass | Feat052YahooRealHttpSystemTest |
| 行情状态接口 500 → Greeks 说明按 Yahoo 文字、利率显示 —，Greeks 数值照常 | Pass | E2E |

## 因 Q270 / 契约修改的 QA 已有测试（old → new，不是放宽）
**结构**
- `Feat016QuoteSystemTestBase`：
  - status 字段加 `optionSource`、`riskFreeRatePercent`；
  - results 字段加 `optionSource`、`yahooResult`，并断言 `cboeResult` 与 `yahooResult` 恰好一个非空。
- `Feat018GreeksSystemTestBase`：
  - greeks 字段加 `source`，要求与 `optionQuote.source` 相同（NO_QUOTE 时为 null）；
  - 手动 mark 标注按来源选择 CBOE / Yahoo 文字。

**时刻**
| 文件 | old → new |
|------|-----------|
| Feat016AutoQuotesAcceptanceSystemTest AC2/3/5/6a/8 | tick 10:15 → 11:00，10:30 → 12:00；lastSuccess 10:15 → 11:00；missingSince 15:15Z → 16:00Z；setNow 10:20 → 11:05 |
| 同上 AC6b | 失败 tick 10:15 + 10:30 → 11:00；"恰好不提示"10:30 → 11:30；提示 10:31 → 11:31；恢复 10:45 → 12:00 |
| 同上 AC7 | 16:20 之后的 NOT_A_SLOT 加 09:30 / 09:45；09:29 → 09:59；次日首拉 09:30 → 10:00；感恩节列表 (09:30, 09:45, 10:00, 12:15, 15:45, 16:00, 16:20) → (10:00, 11:00, 12:00, 15:00, 16:00, 16:20) |
| Feat016AutoQuotesBoundarySystemTest | 10:15 → 11:00，10:30 → 12:00，11:15 → 12:00，setNow 10:20 → 11:05；invalid 时刻 (10:00, 10:15, 10:30, 10:45) → (10:00, 11:00, 12:00, 13:00) |
| 同上 scheduleSlots | 09:30、15:45 由 SCHEDULED 改为 NOT_A_SLOT；新增 09:45 / 10:15 / 10:20 / 10:30 / 10:45 / 15:20 为 NOT_A_SLOT，11:00–15:00 为 SCHEDULED |
| 同上 staleWindow | 次日 10:00 不提示 / 10:01 提示 → 11:00 / 11:01 |
| 同上 refreshRules | 10:14:30 → 10:59:30；tick 10:15 → 11:00；10:15:29 / 10:15:30 → 11:00:29 / 11:00:30 |
| Feat017 / 018 / 019 / 048 的 Acceptance 与 Boundary | tick 10:15 → 11:00，10:30 → 12:00；FEAT-018 AC6 标签"上次：11-23 10:15"→"11:00"，假时间戳 10:15:00 → 11:00:00；显示名同步 |
| e2e feat-016 | 10:15 / 10:20 → 11:00 / 11:05；AC6：失败 tick 10:30 + 10:45 → 12:00，页面时刻 10:45 → 12:30（不提示），10:46 → 12:31，提示里"上次 10:15"→"11:00"，恢复 11:00 / 11:01 → 13:00 / 13:01；AC8：10:15 / 10:16 → 11:00 / 11:01，10:30 / 10:31 → 12:00 / 12:01；标题"30 分钟"→"90 分钟" |
| e2e feat-017 / 018 / 019 / 048 | tick 10:15 → 11:00，10:30 → 12:00；fixNow 10:16 → 11:01，10:20 → 11:05，10:31 → 12:01；feat-018 标签和时间戳 10:15 → 11:00；标题同步 |

**V19 导致的调整**
- `Feat051CloseLotsMigrationSystemTest` 原来有"V18 为最新迁移"的守卫，现在改为 V19。
- 回到 V16 时先撤销 V19：删 `option_quote.quote_source` 及其约束、删 `option_quote_close.quote_source`、删 `underlying_quote.last_yahoo_result`，并把 `ck_underlying_quote_source` 恢复成 V16 定义。然后照旧删 `option_close_lot`，并从 history 中删除 17、18、19 三行。
- 重启后 V17、V18、V19 全部重跑，最新版本断言 18 → 19。证明内容不变：所有读接口逐字相同，回填结果与应用写入的一致。

**FEAT-052 自身测试的更正（按 FEAT-016 契约）**
- `Feat052YahooRealHttpSystemTest$YahooUnreachableStartup` 原来期望 `行情未更新（从未成功获取）`。
- 但 FEAT-016 契约规定"run 成功：跟踪标的为空，或至少一个标的期权链成功"。10:00 启动时没有持仓，这次启动 run 算成功（lastSuccessAt 10:00），所以 10:05 不提示。
- 已改为按契约断言：lastSuccessAt 10:00、10:05 不提示；把时钟拨到 11:31 后提示 `行情未更新（上次：2026-11-20 10:00 美东）`。

## 发现的 Bug
- 首次验证时无。
- commit d71cac7 之后在真实环境发现 [BUG-008](../bugs/BUG-008-yahoo-getcrumb-406.md)（backend）：getcrumb 返回 HTTP 406。已修复并复测通过（见下文“BUG-008 复测”）。

## BUG-008 复测（2026-11-20）
**问题与修复**
- 真实 Yahoo 环境下，每次刷新都报 `Yahoo handshake failed (getcrumb HTTP 406)`。
- 原因：Yahoo 请求带了 `Accept: application/json`，而 getcrumb 的响应是 `text/plain`。
- backend-dev 修复：Yahoo 的所有请求改带 `Accept: */*` 和 `Accept-Language: en-US,en;q=0.9`；CBOE 和 Finnhub 仍带 `application/json`。契约 `docs/api/FEAT-052.md` 已补请求头表。

**QA 补的测试**（`Feat052YahooRealHttpSystemTest`）
- 假 Yahoo 现在会记录 `Accept` 和 `Accept-Language`。和真实 Yahoo 一样，getcrumb 在 `Accept` 不允许 `text/plain` 时回 406。
- `FakeYahooOverHttp`：断言首次握手（cookie 页、getcrumb）、options 请求，以及 401 后重新握手的每个请求，都带 `Accept: */*` 和 `Accept-Language: en-US,en;q=0.9`。
- 新增假服务自检 `fakeGetcrumbRejectsJsonOnlyAccept`：
  - `Accept: application/json` → 406；
  - `*/*` → 200 + crumb；
  - 另外覆盖 `text/*`、q=0 排除的情况。
  - 这证明修复前的代码在本测试里会握手失败。
- CBOE / Finnhub 的 `application/json` 由 dev 的 `HttpQuoteTransportTest` 覆盖。本系统测试把 CBOE / Finnhub 指向不可达端口，不重复断言。

**运行结果**
| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests com.optionmgr.systemtest.Feat052* --tests com.optionmgr.quote.service.HttpQuoteTransportTest` | 54 个全部通过：HttpQuoteTransportTest 11、Acceptance 15、Boundary 25、RealHttp 3 |
| `gradlew.bat build`（全部后端测试） | BUILD SUCCESSFUL，1682 个测试，0 失败、0 跳过（原 1679 + dev 新增 2 + QA 新增 1） |
| 前端 | 未改动，不跑 |
| E2E | 不跑（理由见下） |

**不跑 E2E 的依据**
- `backend/src/main/resources/application-e2e.yml` 配置 `quotes.transport: in-memory`。E2E 后端不发 HTTP 请求，所以请求头不起作用。
- 在 `e2e/` 里 grep `accept|getcrumb|406|transport`，只在 feat-001 / 004 / 011 / 039 的注释里命中 "acceptance" / "accepted"。没有 spec 涉及请求头或 HTTP 传输。
- 本次没有改动任何 testid、文字或错误信息。

**结论：** BUG-008 Closed；FEAT-052 保持 Verified。仍有一个剩余风险：真实 Yahoo 的其他请求头或反爬规则变化，只能在真实环境中发现。

## 疑问 / 需求不清楚的地方
- 请求记录断言假设：某个标的 Yahoo 失败后，紧接着就是它的 Finnhub 兜底请求。契约只写了"按时间顺序""标的按 symbol 升序"，当前实现符合这个假设。
- failureDetail 只断言关键片段（HTTP 403 / 429、到期日、handshake），没有逐字比对契约里的示例句子。
- 已知限制（契约已写明）：Yahoo 收盘后的实际响应形态、`date` 是否必须逐秒等于 `expirationDates` 中的值，都只能在真实环境中观察，自动化测试不访问真实 Yahoo。
