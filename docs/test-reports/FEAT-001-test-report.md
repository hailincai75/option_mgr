# FEAT-001 测试报告

- **测试日期:** 2026-11-11
- **结论:** Verified（19 条验收标准全部通过，额外边界测试全部通过，没有发现 bug）
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/`
  - `OptionTradeSystemTestBase.java`：真实 HTTP 端口（`RANDOM_PORT` + `TestRestTemplate`），内存 H2；"当前时间"固定为 `2026-11-12T15:00:00Z`（美东 10:00），个别用例自己调时钟（0DTE 等）；每个测试前后清空 `cash_ledger_entry` / `cash_transfer` / `option_trade`
  - `Feat001OpenTradeAcceptanceSystemTest.java`（22 个用例）：验收 1–11、13–15、17、18，以及"冻结的现金不能取出"
  - `Feat001OpenTradeValidationSystemTest.java`（65 个用例，含参数化）：验收 12、19，检查顺序，Q7/Q23/Q27 边界，0DTE / 美东日期，错误体，GET 错误，并发
  - 公共改动：`CashSystemTestBase` 的时钟改为每个测试可设置（`MutableClock`），所有系统测试共用一个 Spring 上下文；清理时也清空 `option_trade`
- **E2E 测试:** `e2e/tests/feat-001-open-trade.spec.ts`（51 个用例）+ 回归 `e2e/tests/feat-007-cash.spec.ts`（27 个，流水表新增"冻结变动"列后仍全部通过）
  - 用 `e2e` profile 后端，每个测试前调 `POST /api/test-support/reset`（见 `e2e/README.md`）。"Given"状态通过 API 准备，"When/Then"都在页面上操作和断言
  - 新增 `e2e/support/trade.ts`（录入表单的 page object、`apiTrade` 等）；`fixtures.ts` 的流水行读取新增 `frozen`（`cash-ledger-frozen`）

## 运行结果
| 命令 | 结果 |
|------|------|
| `cd backend && gradlew.bat build`（JDK 21） | **BUILD SUCCESSFUL**：283 个测试，0 失败（QA 系统测试 151 个：FEAT-001 22 + 65，FEAT-007 20 + 44）；boot jar 已重建 |
| `cd e2e && npx playwright test` | **78 passed (3.0m)**（FEAT-001 51 个 + FEAT-007 27 个），0 失败 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | STO put 成功并冻结 | Pass — `ac1_stoPut` | Pass — `AC1+AC16 …` | 存 `2026-11-10T15:30:00Z`；201 所有字段、GET 与 POST 响应完全一致；流水 `OPTION_OPEN`、`sourceId`、`frozenChange 20000.00`、说明 `STO 1 张 AAPL 2026-12-18 200 P`；E2E 在资金页看到"期权开仓 +349.35 冻结 +20,000.00" |
| 2 | 多张 + 小数行权价 | Pass — `ac2_multipleContractsDecimalStrike` | Pass — `AC2 …` | `strike` 为 `"12.5"` |
| 3 | BTO 成功 | Pass — `ac3_bto` | Pass — `AC3 …` | `cashChange -512.65`、冻结变动 0（页面显示 `—`） |
| 4 | BTO 可用不足 | Pass — `ac4_btoInsufficient` | Pass — `AC4+AC16 …` | 原文 `可用现金不足：需要 512.65，可用 500.00`，`data-error-code=CASH_INSUFFICIENT`；没有新增交易和流水；改成 500.00 后成功 |
| 5 | 冻结的现金不能用于买入 | Pass — `ac5_frozenCashCannotBuy` | Pass — `AC5 …` | |
| 6 | STO put 担保不足（Q18） | Pass — `ac6_collateralShortByOneCent`、`ac6_exactCollateral` | Pass — 两个 `AC6 …` | 另测：可用恰好等于担保时允许，本笔权利金不计入（`q18_exactCollateral`） |
| 7 | 同一合约多次开仓 | Pass — `ac7_sameContractTwice` | Pass — `AC7 …`（第二笔时间改为 11-10 11:00，见疑问 1） | 2 笔独立交易，可以分别 GET |
| 8 | 多空冲突（Q7） | Pass — `ac8_directionConflict` | Pass — `AC8 …` | 持有多头 call 时 STO call 报 Q7（STC），不报 Q16 |
| 9 | STO call（Q16） | Pass — `ac9_stoCallRejected` | Pass — `AC9 …` | |
| 10 | 标的规范化 | Pass — `ac10_symbolNormalisation` | Pass — `AC10 …` | |
| 11 | 成交时间（美东 ↔ UTC） | Pass — `ac11_timeConversion` | Pass — 3 个 `AC11 …` | 浏览器时区 Asia/Shanghai 下默认值仍为美东时间（含夏令时）；夏令时切换当天 02:30 → `07:30Z`、01:30 → `05:30Z`；等于当前时间允许 |
| 12 | 字段校验 + 0DTE | Pass — `ac12_fieldValidation`（20 组）、`ac12_requiredFields`（8 组）、`ac12_noteLength`、`ac12_zeroDte` | Pass — 18 个单项 + 未选方向/类型 + 0DTE + 后端字段错误映射 | API：每组都核对 `details` 恰好是（字段，中文 message），并且没有保存任何东西。E2E：错误只出现在对应字段、没有发出 POST、汇总不变 |
| 13 | 补录交易的现金检查（Q19） | Pass — `ac13_backdatedBtoReplay` | Pass — `AC13 …` | |
| 14 | 补录 STO put 影响之后的出金 | Pass — `ac14_…`（2 个） | Pass — 2 个 `AC14 …` | 补录后的逐条余额重新累计正确 |
| 15 | 非标准 multiplier（Q23） | Pass — `ac15_nonStandardMultiplier` | Pass — `AC15 …` | 说明带 `（multiplier 10）`；Q23 先于现金检查 |
| 16 | 失败保留输入、成功后刷新 | —（前端） | Pass — `AC4+AC16`（10 个字段全部保留）、`AC1+AC16`（确认信息 + 页面不重载即刷新 + 表单清空） | |
| 17 | 补录较早开仓不能让之后的交易违规（Q27） | Pass — `ac17_backfillDirectionConflict`、`ac17_backfillMultiplierMismatch` | Pass — 2 个 `AC17 …`（日期整体提前一天，见疑问 1） | 错误码和 message 与契约一致；被拒后汇总不变 |
| 18 | 担保向上取整（Q28） | Pass — `ac18_collateralRoundedUp`、`ac18_roundedCollateralInsufficient` | Pass — 2 个 `AC18 …` | 另测 12.341×1×1 → 12.35，12.345×1×3 → 37.04，12.345×10 → 123.45 |
| 19 | 数值上限（Q29） | Pass — `ac19_overLimits`（5 组）、`ac19_atLimits` | Pass — 4 个超限（AC12 组内）+ `AC19 等于上限允许` | 页面上超限值被前端校验先拦下，错误显示在对应字段，不会发请求；"后端返回 `VALIDATION_ERROR`"由 API 测试验证（见疑问 2） |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| **冻结的现金不能取出**（补 FEAT-007 验收 11 的缺口）：入金 25,000、STO put 冻结 20,000 后出金 5,500 被拒；补录到 STO 之前的出金会让 STO 时点变成 −150.65，也被拒；取完可用之后 0.01 也被拒 | Pass | API `frozenCashCannotBeWithdrawn` + E2E 资金页 |
| 检查顺序：字段 → Q7 → Q16 → Q23 → 现金 | Pass | 字段错误 + 方向冲突 → 400；方向冲突 + 现金不足 → Q7；STO call 且无现金 → Q16；multiplier 不一致且现金不足 → Q23 |
| 时间相关的两项校验在其它字段都通过后才做；两项可以同时出现 | Pass | |
| 同一字段多条 message（strike `-5.1234`）、details 按 field 排序 | Pass | |
| Q7 同一时刻：已有 STO 10:30，新 BTO 10:30 排在它之后 | Pass | |
| Q27：之后有多笔交易时报告第一笔冲突的；持仓张数是累计值（多头 2 张）；同方向补录允许 | Pass | |
| Q27 只看同一合约（不同行权价、到期日、C/P 互不影响） | Pass | |
| 行权价按数值比较（`200` / `200.0` / `200.000` 是同一合约，显示 `200`） | Pass | |
| Q23 多头一侧同样检查；不同合约的 multiplier 可以不同 | Pass | |
| 成交日按美东日期：NY 12-18 23:59（UTC 已是 12-19）、到期 12-18 允许；NY 11-09 21:00、到期 11-09 允许；NY 11-10 00:30、到期 11-09 拒绝 | Pass | |
| 补录已过期合约（10-01 开仓、10-17 到期）；周末的到期日和成交时间 | Pass | |
| multiplier 省略或 null → 100 | Pass | |
| 无法解析的值（`P`、`put`、`SELL`、`abc`、`2026-02-30`、`2026/12/18`、带 `Z` 的时间）→ `BAD_REQUEST`，并指出字段 | Pass | |
| 极大担保（9999999.999 × 10000 × 100000）不溢出，按现金不足拒绝 | Pass | |
| 净额等于上限 9,999,999,999,999.99 | Pass | |
| GET 404（`Option trade 99 not found`）、GET `abc` → 400 field `id` | Pass | |
| 并发：可用 20,000 时 5 笔并发 STO put（各需担保 20,000）→ 恰好 1 笔成功，其余 422，没有 500 | Pass | |
| 备注全空白 → null，流水 remark 也是 null | Pass | |
| E2E：净额标签随方向切换；清空按钮；从导航进入并在表单上方看到汇总 | Pass | |

不适用于本需求：部分平仓、FIFO 扣减、冻结释放（FEAT-004）；指派、到期（M2）；手续费单独字段（Q6，只记净额）。

## 发现的 Bug
无。

## 疑问 / 需求不清楚的地方
1. **E2E 的日期调整**：E2E 后端用的是真实时钟，测试时为 2026-11-11 09:0x（美东）。验收 7 的第二笔（2026-11-11 11:00）和验收 17（2026-11-11 10:00）当时还在未来，会被"不得晚于当前时间"拒绝。所以 E2E 里把这两个场景整体提前一天（相对时间关系不变），原日期由固定时钟的 API 测试逐字验证。
2. **验收 19 的措辞**：验收写"后端返回 `VALIDATION_ERROR`，错误显示在对应字段下"。在页面上，前端校验（与后端同样的上限）先拦下了，不会发请求。我把它理解为"后端会拒绝 + 页面字段下有错误"，两者分别由 API 测试和 E2E 验证。如果你的意思是页面必须把请求发到后端再显示后端的错误，请告诉我。
