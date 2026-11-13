# FEAT-040 测试报告

- **测试日期:** 2026-11-13（E2E 用真实时钟，运行时美东 2026-11-13 星期五下午，"本月"为 2026-11）
- **结论:** Verified
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/Feat040StockPnlInSummariesSystemTest.java`（23 个用例，真实 HTTP 端口，固定时钟 2026-11-13 10:00 美东 = `2026-11-13T15:00:00Z`，AC12 调时钟）。每个用例前把 `option_trade`、`stock_trade` 的 id 序列重置为 1（仅测试用内存 H2），使场景 M 的 id 与需求一致（期权 O1–O4 = 1–4，股票 S1–S7 = 1–7），两类 id 有意重叠。
- **E2E 测试:** `e2e/tests/feat-040-stock-pnl-in-summaries.spec.ts`（22 个用例）
- **按新口径改写的已有测试:** `Feat011StockTradesSystemTest.ac13NoOptionInteraction`、`optionNumbersUnchangedByStocks`；`e2e/tests/feat-006-closed-trades.spec.ts`（日期标签）、`feat-008-dashboard-pnl.spec.ts`（总计行文字、说明、期权持仓 / 股票持仓）、`feat-011-stock-trades.spec.ts`（股票页说明、AC13）；另修正 `feat-002-trade-list.spec.ts` AC2 的用例时序问题（见下）。FEAT-006 / 008 / 011 报告末尾加了"FEAT-040 后续变化"说明。

## 运行结果
| 套件 | 命令 | 结果 |
|------|------|------|
| 后端构建（含全部单元测试与 systemtest） | `cd backend && gradlew.bat build`（JDK 21） | BUILD SUCCESSFUL，725 个测试 0 失败（systemtest 394 个，其中 FEAT-040 23 个、FEAT-011 29 个） |
| 前端检查 | `cd frontend && npm run lint && npm run typecheck && npm test` | 通过；Vitest 24 个文件 464 个测试全部通过 |
| E2E 全部 | `E2E_BACKEND_PORT=18080 E2E_FRONTEND_PORT=15173 npx playwright test` | 首次全量：**211 passed，1 failed**（10.5 分钟）。唯一失败为 `feat-002-trade-list.spec.ts` AC2（见下"首次全量运行的失败"），修正用例后单独重跑该用例通过。按新流程规则没有再跑第二次全量（已开始的第二次全量被我中途停止，未计入结果） |
| E2E FEAT-040 | 同上，`tests/feat-040-stock-pnl-in-summaries.spec.ts` | 22 passed。首次运行的 2 个失败都是我写的用例问题（标签定位同时匹配到单选项的 label；`page.route('**/api/dashboard**')` 也拦截了 Vite 的源码模块 `/src/api/dashboard.ts` 导致白屏），已改为按 pathname 拦截，与实现无关 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 已平仓页：期权和股票合并、各列 | Pass — `ac1Merged` | Pass — `AC1 已平仓…` | `共 5 笔平仓` `+141.51`；顺序 O4、S7、S6、S3、O2；各列与需求表逐格一致（`−1,125.16` / `每股 7.5011`、`+1,200.00` / `每股 8.0000` 等）；S3 备注"盘后"；股票行没有空头/多头、没有 multiplier 附注；无"历史"；S1/S2/S4/S5 不出现。API：股票行期权专用字段全为 `null`、期权行 `shares`/`costPerShare`/`pricePerShare`/`soldLots` 为 `null`，`openingNetTotal + closeNetAmount = realizedPnl`，与 `GET /api/stock-trades/{id}` 的 `soldCost`/`realizedPnl`/`pricePerShare`/`note`/`hasAuditLog` 一致；S3 `tradedAt=2026-11-01T03:30:00Z`。E2E：类型标签期权/股票颜色不同、股票"卖出"标签与股票页卖出标签同色、表头"合约 / 标的""张数 / 股数""开仓净额合计 / 成本""平仓净额 / 卖出实收"、类型列紧跟平仓时间、页面说明原文 |
| 2 | 展开被卖出批次 | Pass — `ac2SoldLots` | Pass — `AC2 展开…` | S6：`2026-10-01 转入 100 股 −750.00`、`2026-11-03 10:00 买入 50 股 −375.16`；S7：`2026-11-04 10:00 买入 100 股 −333.33`；O2 不变 `2026-10-27 10:00 STO（卖出开仓） 1 张 +45.00`。API：批次 `netAmount` 之和 = `openingNetTotal`，转入批次 `tradedAtLocal` 为当日 00:00、`tradeDate` 有值 |
| 3 | 类型筛选与合计 | Pass — `ac3TypeFilter` | Pass — `AC3 类型筛选…` | 股票 3 笔 +121.51、期权 2 笔 +20.00、`ixqz` +109.84、IXQZ + 股票 +74.84、QFZ 0 笔 0.00；空 / 全空白 `type` = 不限；`FUTURE`、小写、`OPTION,STOCK`、`SELL` → 400 `BAD_REQUEST` field `type` `类型只能是 OPTION 或 STOCK`，与 `action` 错误一起报告，优先于日期 `VALIDATION_ERROR`。E2E：地址栏 `type=STOCK`、刷新保留、后退回到不限；`?type=FUTURE` 类型下报错、不发请求、不崩溃 |
| 4 | 期权专用筛选（Q90） | Pass — `ac4OptionOnlyFilters` | Pass — `AC4 Q90…` | BTC → O4 O2 +20.00；到期日 11-20 → O2；CALL → O4；行权价 3 / 3.00 → O4；类型=股票 + 行权价/方向/到期日/Call → 空结果 0 笔 0.00 不报错；空值参数不排除股票；`action=SELL` → 400 `方向只能是 BTC、STC、EXP`。E2E：提示原文在类型不限且有期权条件时出现；BTC + 到期日后选股票 → 四项清空并禁用、提示消失、结果 S7 S6 S3 +121.51、地址栏无 `action`/`expirationDate`；改回不限恢复可用且为空；`?type=STOCK&action=BTC` → 无结果 0 笔 0.00、BTC 按地址回填并禁用；Call/Put 字段标签为"Call / Put" |
| 5 | 按美东日期筛选（含深夜卖出） | Pass — `ac5Dates` | Pass — `AC5 美东日期…` | 10-31 → 只有 S3 −20.00；11-01 → 无、0.00；11-06~11-09 → O4 S7 +51.67；颠倒 → `endDate` `开始日期不能晚于结束日期`（E2E 不发请求） |
| 6 | 分页与合计（后端） | Pass — `ac6Paging` | —（后端） | 每页 2：O4 S7 / S6 S3 / O2 / 空；每页 `totalItems`/`filteredCount` 5、合计 141.51、`totalPages` 3；按标的筛选跨类型分页同样正确 |
| 7 | 同一时间的排序（Q92） | Pass — `ac7SameTimeOrderAndIdCollision` | Pass — `AC7 同一时间…` | 最上两行 股票 QRTV +5.00、期权 QRTV 2026-12-18 5 P BTC +40.00；共 7 笔 +186.51；同一时间两笔股票卖出：后录入在上。期权 #6 与股票 #6 同时出现为两行，E2E 展开一行不影响另一行 |
| 8 | "历史"与跳转 | Pass — `ac8HistoryByTypeAndId` | Pass — `AC8 历史按类型+id…` | 只改 S1 → 无"历史"；股票页改 S6 备注"减仓" → 只有股票 #6 行有"历史"，进入审计页 `objectType=STOCK_TRADE&objectId=6`（1 条）；交易列表改 O2 → O2 行"历史" → `OPTION_TRADE` #2，S3、S7 仍无。点 S6 的 IXQZ → 股票页按 IXQZ 筛选（6、4、1）；点 O2 合约 → 交易列表按合约筛选（2、1） |
| 9 | 盈亏汇总：全部时间（Q91） | Pass — `ac9SummaryAllTime` | Pass — `AC9 盈亏汇总…` | 总计 5 / +141.51，小字 `期权 2 笔 +20.00 · 股票 3 笔 +121.51`；按标的 IXQZ 2 +35.00 +74.84 +109.84、QRTV 1 — −20.00 −20.00、XYZ 2 −15.00 +66.67 +51.67；按月 2026-11 3 −15.00 +141.51 +126.51、2026-10 2 +35.00 −20.00 +15.00；合计行 5 +20.00 +121.51 +141.51；说明原文；表头 笔数/期权/股票/合计；颜色、合计列加粗。API 每次都校验：期权+股票=合计（总计和每行）、各组相加=总计、总计 = 已平仓页同范围（含按 `type` 分别对应 `option`/`stock`） |
| 10 | 盈亏汇总：日期范围与跳转 | Pass — `ac10SummaryRanges` | Pass — `AC10 汇总日期范围…` | 10-31 → 1 笔 −20.00（期权 0 笔 0.00 · 股票 1 笔 −20.00），只有 QRTV、2026-10；从 11-01 → 3 笔 +126.51，IXQZ 期权 `—`，没有 QRTV，已平仓页同范围也是 +126.51；2027 → "所选范围内没有平仓记录"、拆分都是 0 笔 0.00；点 QRTV → 已平仓 `symbol=QRTV` 不带 type，只有 S3 −20.00；点 2026-10 → 10-01~10-31，S3 O2 +15.00 |
| 11 | 首页（Q89、Q93） | Pass — `ac11Dashboard` | Pass — `AC11 首页…` | 现金 19,529.35 / 0.00 / 19,529.35；`总计 +141.51（5 笔）`、`本月（2026-11）+126.51（3 笔）`；`期权持仓 1 个`、冻结 0.00、7 天内 0、已过期 0；`股票持仓 2 个标的`（= `GET /api/stock-positions` 个数）。点击 → `/positions#stock-positions`，在 420px 高的窗口里页面已滚动、股票持仓部分在视野内（IXQZ、XYZ）。录入页卖出 XYZ 200 @700（+33.33）后不刷新：`股票持仓 1 个标的`、+174.84（6 笔）、本月 +159.84（4 笔）、期权持仓 1 个不变 |
| 12 | 本月的边界（调时钟） | Pass — `ac12MonthEdges` | —（后端） | 时钟 10-31 23:45 EDT（`2026-11-01T03:45Z`）→ 本月 2026-10 +15.00（2 笔）、总计 +15.00（2 笔）；11-01 00:30 EDT → 本月 2026-11 0.00（0 笔）、总计不变，按月 S3 仍在 2026-10；另测 01:30 EST（夏令时结束后）同样 0.00 |
| 13 | 修改、删除、补录后重新计算 | Pass — `ac13EditTransferCost`、`ac13EditSaleProceeds`、`ac13DeleteSale`、`ac13BackfillSale`、`ac13DeleteUsedLot`、`ac13TransferAndBuyNoPnl`、`ac13EditOptionReflows` | Pass — 6 个 `AC13 …` 用例（全部经页面操作，`window` 标记确认未刷新） | 改转入 S1 800：S6 −1,175.16 / 每股 7.8344 / +24.84、S6 无"历史"、合计 +91.51、IXQZ 股票 +24.84 合计 +59.84、2026-11 +76.51、股票合计 +71.51、首页 +91.51（5）/ 本月 +76.51（3）；改 S7 实收 300：−33.33（红）、+41.51、XYZ −48.33；删 S7：4 笔 +74.84、XYZ 股票 `—`、2026-11 2 笔 +59.84、首页 4 笔、股票持仓 2；补录 XYZ 100 @420：−333.34 / 3.3334 / +86.66、6 笔 +228.17、2026-11 4 笔 +213.17；删 S1：S6 −1,125.49 / +74.51、+141.18；转入 QFZ / 买入 / 出金：盈亏与笔数 JSON 不变，首页股票持仓 +1；另测改期权 O4 净额同样重算 |
| 14 | 按账户隔离 | Pass — `ac14AccountIsolation` | Pass — `AC14 按账户隔离…` | 小账户：1 行股票 IXQZ +60.00、汇总 IXQZ（期权 `—`、股票 +60.00）、首页 +60.00（1 笔）、期权持仓 0、股票持仓 0 个标的；切回默认：5 笔 +141.51、IXQZ +109.84 |
| 15 | 没有股票时不变（回归） | Pass — `ac15OptionsOnlyRegression`；`Feat006*`、`Feat008*`、`Feat012*` 全部通过；`Feat011…ac13NoOptionInteraction` 已按本条改写 | Pass — `AC15 只有期权…`；FEAT-006 / 008 / 012 / 033 / 038 的 E2E 全部通过 | 场景 Q：6 笔 +703.05，全部类型"期权"，汇总股票列全为 `—`，小字 `期权 6 笔 +703.05 · 股票 0 笔 0.00`，首页 `股票持仓 0 个标的`。FEAT-011 场景 S + STO 5 张 IXQZ 9 C：`共 2 笔平仓` +141.51（S4 +74.84、S5 +66.67）、首页 +141.51（2 笔）、期权持仓 1、股票持仓 2 |
| 16 | 空状态 | Pass — `ac16Empty` | Pass — `AC16 空状态…` | 已平仓"暂无平仓记录"、0 笔 0.00；汇总"暂无平仓记录"、拆分对象为 `{0, "0.00"}`（不为 null）；首页 0.00（0 笔）、`股票持仓 2 个标的` |
| 17 | 股票页说明 | — | Pass — `AC17 股票页说明`；`feat-011 AC2` 已更新 | 原文"金额均已含手续费；每股价格 = 金额 ÷ 股数，仅供参考；股票已实现盈亏同时计入已平仓页、盈亏汇总和首页" |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 到期作废与股票卖出同在到期日 16:00 | Pass（API `expiryAndZeroPnl`） | EXP 和股票卖出 `tradedAtLocal` 都是 2026-11-06 16:00，股票在上（Q92）；`type=OPTION` 和 `action=EXP` 都包含 EXP，`type=STOCK` 不含 |
| 盈亏为 0.00 的卖出 | Pass（API + E2E `边界：盈亏 0.00…`） | 显示 `0.00`、不着色，计 1 笔；汇总 `QFZ 1 — 0.00 0.00`；首页 0.00（1 笔） |
| 两类 id 相同 | Pass（API `ac7…`、`ac8…`；E2E AC7、AC8） | 期权 #6 / 股票 #6、期权 #2 / 股票 #2 的"历史"、展开、跳转都按类型区分 |
| 同一时间两笔股票卖出 | Pass（API `ac7…`） | 后录入（id 大）在上 |
| 一笔卖出跨多个批次、部分卖出按比例舍入 | Pass（AC2、AC13） | 375.1625 → 375.16；333.335 → 333.34 |
| 夏令时边界 | Pass（API AC12） | 10-31 23:30 EDT 卖出归 10 月；11-01 01:30 EST 本月为 11 月 |
| 后端报错 | Pass（E2E `后端报错…`） | 已平仓、汇总、首页（盈亏卡、持仓卡）500 → 错误提示、不崩溃；后端返回 `details[].field = "type"` 时显示在类型筛选项下；恢复后正常 |
| 入金、出金不计入盈亏 | Pass（API `ac13TransferAndBuyNoPnl`） | 出金后汇总 JSON 不变 |

## 发现的 Bug
无。

## 疑问 / 需求不清楚的地方（不影响结论）
- 验收 4 第 3 条"先填方向 BTC、到期日，再把类型改为股票 → 这两项被清空并禁用"：已验证。注意这几项在**未提交**时的提示也会实时显示/消失（提示按表单当前值，而非地址栏的值），与需求"填了这四项中任何一项时显示"一致。
- 后端集成测试用 String 路径调 `TestRestTemplate` 时 `%20` 会被再编码一次，所以"全空白 `type` / 首尾空白的标的"两条用原样 URI 发送验证（`rawGet`），结果符合契约。
- 前端控制台仍有 antd 警告 ``[antd: Table] `expandable.expandIcon` is deprecated``（已平仓表格），不影响功能，供 frontend-dev 参考。

## 首次全量运行的失败
| 失败用例 | 根因 | 处理与重跑原因 |
|---------|------|---------------|
| `feat-002-trade-list.spec.ts` › `AC2 标的精确匹配…`（填 `AAP` 查询后找不到"没有符合条件的交易"） | **用例时序问题，不是产品 bug。** 交易列表的筛选表单以地址栏筛选为 `key`，查询后地址变化会让表单重新挂载。用例查询 `' AAPL '` 后，结果行与上一次（`aapl`）相同，`expectLabels` 立即通过；用例随即填 `AAP`，而表单在之后才重新挂载，把输入框重置为地址里的 `AAPL`，于是点"查询"没有发出新请求（trace 中只有 `symbol=aapl`、`symbol=AAPL` 两个请求，截图输入框为 `AAPL`）。 | 单独跑该 spec 3 次，复现 1 次，是间歇性的，起初原因不明，所以重复运行以确认。用 trace 定位原因后，改为等待地址变化**并且**输入框显示重新挂载后的值 `AAPL` 再继续输入。只改测试代码，之后单独跑该用例 10 次全部通过。表单 `key` 的设计早于 FEAT-040（`TradeListPage` 未改）；FEAT-040 给共用的 `TradeFilterForm` 加了 `Form.useWatch`，可能让这个时间窗略有变化，但对真人操作没有影响（窗口不到一秒）。 |
