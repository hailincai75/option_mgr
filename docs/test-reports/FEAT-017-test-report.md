# FEAT-017 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified（第 3 轮）。第一轮因 BUG-006 判为 Failed；BUG-006 修复后 Closed。之后按 Q162 把所有市值改为带显式正负号，前端修改完成后重新验证，全部通过。见下方「复测记录」
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat017UnrealizedAcceptanceSystemTest.java（10 个）、Feat017UnrealizedBoundarySystemTest.java（14 个）、公共基类 Feat017UnrealizedSystemTestBase.java（继承 FEAT-016 的 QA 基类：假 CBOE + tick + 可设时钟，不访问真实 CBOE / Finnhub）
- **E2E 测试:** e2e/tests/feat-017-unrealized-pnl.spec.ts（12 个）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试，含 bootJar） | BUILD SUCCESSFUL（2 分 59 秒）；103 个测试类，1252 个测试，0 失败 / 0 错误 / 0 跳过；FEAT-017 共 24 个全部通过 |
| 前端 `npm run lint && npm run typecheck && npm test`（第 1 轮） | lint、typecheck 通过；Vitest 42 个文件、685 个测试，**1 个失败**（`tradeFormSupport.test.ts`，见 BUG-006） |
| E2E：本需求 + 受影响 spec（第 1 轮，3 个 worker，端口 18080+ / 15173+） | 44 / 44 通过（1.8 分钟）：feat-017 12 个、feat-003 9 个、feat-010 12 个、feat-016 11 个 |
| **前端 `npm run lint && npm run typecheck && npm test`（第 3 轮，最终）** | lint、typecheck 通过；Vitest 42 个文件、686 个测试全部通过 |
| **E2E：本需求 + 受影响 spec（第 3 轮，最终）** | 44 / 44 通过（1.7 分钟）：feat-017 12 个、feat-003 9 个、feat-010 12 个、feat-016 11 个 |
| 后端（第 3 轮） | 未重跑：Q162 只改前端，后端代码没有变化，沿用第 1 轮的 `gradlew.bat build` 结果 |

### 受影响 spec 的选择（grep 依据）
本次改动：期权行和股票行各加 `市值`、`未实现盈亏` 两列（新 testid 为 `position-market-value` 等）；持仓页顶部加合计行（`unrealized-summary*`，文字为 `期权 市值 … ｜ …`、`未实现盈亏已含开仓手续费…仅供参考`、`重新加载合计` 按钮）。在 `e2e/tests/` 中 grep 了这些内容：
- 新 testid、`市值`、`未实现`、`unrealized`、`market-value`：除 feat-017 外没有命中。
- `仅供参考`：`feat-003-positions.spec.ts:121` 断言持仓页全部 `th` 文字，并用 `getByRole('button', { name: /删除|编辑/ })` 检查页面上的按钮；新列和新按钮可能影响它，**选入**。feat-011 / feat-040 的命中是股票交易列表和已平仓页的说明，与本次改动无关，未选。
- `columnheader|thead`：只有 feat-009（`剩余交易日` 列）和 feat-040（已平仓页 / 首页卡片），不涉及持仓表的新列，未选。
- 整页 / 整行文字断言：`feat-010-expiry-filter.spec.ts:164` 断言 `positions-page` 整页不含 `显示`，合计行在这个页面里，**选入**；`feat-016-auto-quotes.spec.ts` 对 `position-row` 整行做 `not.toContainText`，新列在行内，**选入**（协调方也要求检查 feat-016）。
- 其余引用 `positions-as-of` / `stock-position-row` 的 spec（feat-004/009/011/012/013/014/015/039/040/041/042/043）只断言自己的 testid，页面上只是多显示了新列，按窄规则不选，留给里程碑全量 E2E。

## 复测记录
| 时间 | 检查 | 结果 |
|------|------|------|
| 2026-11-14 第 2 轮 | BUG-006：`npx vitest run src/components/trade/tradeFormSupport.test.ts` | 1 / 1 通过 → BUG-006 Closed |
| 2026-11-14 第 2 轮 | 前端 `npm run lint && npm run typecheck && npm test`（全量） | lint、typecheck 通过；Vitest 42 个文件、686 个测试，12 个失败（`UnrealizedPnl.test.tsx` 7 个、`unrealizedLabels.test.ts` 5 个）。原因：用户刚决定所有市值都带显式正负号（合计三组、股票行也带 +，如 `+160.00`、`+6,110.00`），frontend-dev 正在修改，单元测试已改为新格式（期望 `+6,110.00`、新调用方式），实现还没改完（实际为 `6,110.00`；`unrealizedDisplay` 报 `Cannot destructure property 'signedMarketValue'`）。这是开发中的中间状态，不是缺陷，所以没有写 bug |
| 2026-11-14 第 3 轮 | Q162（所有市值带显式正负号，0 显示 0.00 不带符号）：更新 feat-017 E2E 的市值断言。合计三组、股票行正数加 +，整句（`unrealized-summary-totals`）和分组（`unrealized-summary-option`）文字同步修改；0.00 的断言不变 | `npx tsc --noEmit` 通过 |
| 2026-11-14 第 3 轮 | 重新 grep 受影响 spec：在 `e2e/tests/` 中 grep `market-value`、`市值`，只有 feat-017 命中，其它 spec 都没有断言市值文字。共享的 `formatMoney` 没有改（`format.ts` 只新增了 `formatPercent`），带符号显示只在 FEAT-017 组件里通过调用参数实现。所以受影响 spec 仍是上面那 3 个（feat-003 / feat-010 / feat-016，理由同上） | — |
| 2026-11-14 第 3 轮 | 前端全量检查 + E2E（feat-017 / 003 / 010 / 016） | 686 / 686 通过；E2E 44 / 44 通过 → **Verified** |

下方表格的金额已按 Q162 写成带符号的格式。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | P1 −125.00 / −6.30（−5.31%）；P2 +370.00 / +68.70（+22.80%）；P3 −85.00 / −0.65（−0.77%） | Pass | Pass | API 还检查行内 mark 与 `optionQuote.mark` 一致、`openingNetTotal` 带方向；E2E 还检查正绿负红、`data-status` |
| 2 | IXQZ 股票 +6,110.00 / +230.00（+3.91%） | Pass | Pass | 被 covered call P3 覆盖的 100 股照算 |
| 3 | P4 无报价；合计 +160.00 / +61.75 ｜ +6,110.00 / +230.00 ｜ +6,270.00 / +291.75；`1 个持仓无报价，未计入` | Pass | Pass | E2E 断言 `unrealized-summary-totals` 的整句和表头说明；API 检查合计 = 各 OK 行之和、计数、`asOfDate` |
| 4 | 账户 B：P5 −125.00 / −5.00（−4.17%）；合计 −125.00 / −5.00、股票 0.00 / 0.00，无提示 | Pass | Pass | 账户 A 的合计不受 B 影响（Q157） |
| 5 | 手动设 P1 Mark 1.40 → −140.00 / −21.30，期权合计 +46.75；B 的 P5 −20.00；手动设 P4 0.30 → −30.00 / +15.00，提示消失 | Pass | Pass | E2E 在页面上设置，行和合计不刷新页面即更新；清除手动值后恢复 |
| 6 | P6 0.125 → +12.50 / −3.15（−20.13%），合计 +172.50 / +58.60、+6,282.50 / +288.60；改用 theo 0.03 后 → +3.00 / −12.65（−80.83%），+163.00 / +49.10、+6,273.00 / +279.10 | Pass | Pass | `markBasis` 从 MID 变为 THEO |
| 7 | QRTV 拉取失败（`行情未更新（上次：11-23 10:00）`）→ P1 / P2 仍按 1.25 / 1.85 计算，计入合计 | Pass | Pass | |
| 8 | 已过期未处理（最后 mark 0.05）→ `已到期，待处理`，合计不变，提示 `1 个持仓无报价、1 个持仓已到期待处理，未计入`；作废后行消失、提示恢复 | Pass | Pass | 时钟先拨到 11-20 10:00 拉到报价，再拨到 11-23；E2E 在页面上作废 |
| 9 | STC 2 张 P2、净额 368.70 → 期权 −210.00 / −6.95，合计 +5,900.00 / +223.05 | Pass | Pass | E2E 用页面上的平仓对话框 |
| 10 | 空账户 → 合计全部 0.00，无提示 | Pass | Pass | API 另测全新空库 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 已过期 + 手动 mark → 仍为 `EXPIRED_PENDING`；已过期且从未报价 → `EXPIRED_PENDING`（优先于 NO_QUOTE） | Pass | 提示 `2 个持仓已到期待处理，未计入` |
| 到期日当天 10:00 正常计算；16:20 收盘后链中消失，16:30 仍按最后报价计算；次日变为已到期，待处理 | Pass | 到期日当天 16:30 仍为 OK（协调方已接受） |
| 周末：不拉取，按最后报价计算；周六 `asOfDate` 为周六，周五到期的持仓已到期，待处理 | Pass | |
| 指数 SPX（Q149）：无报价不计入；手动 mark 5.10 → 510.00 / +10.00（2.00）；清除后又不计入 | Pass | |
| bid 0 且无 theo → 无报价；未过期合约从链中消失（数据源已无该合约）→ 按最后报价计算 | Pass | |
| Q154 股票：手动价 61.105 × 33 股 = 2016.465 → +2,016.47（HALF_UP，HALF_EVEN 会得到 .46）；12.3456 × 7 → +86.42 | Pass | API + E2E（E2E 在页面上手动设价） |
| Q154 期权：mark 0.10025 → 空头 −10.03 / 多头 +10.03（对称）；合计 0.00，不重新取整 | Pass | |
| 同一合约两批开仓 → BTC 平掉最早一批（FIFO）→ 按剩余净额 130.00 计算 | Pass | |
| 股票部分卖出 → 按比例分摊后剩余成本 3,528.00，3,666.00 / +138.00 | Pass | |
| put 被指派得到的股票：成本 4,440.00（已扣权利金，Q8）→ 5,240.00 / +800.00（18.02） | Pass | |
| Q158：合计接口带 `?expiry=` 参数结果不变；页面切换到期过滤，合计不变 | Pass | API + E2E |
| 拉到新报价后，行和合计都随之更新（实时计算） | Pass | |
| 合计接口错误：缺 X-Account-Id → 400 ACCOUNT_REQUIRED；`abc` → 400 BAD_REQUEST；999 → 404 ACCOUNT_NOT_FOUND；POST / PUT → 405 | Pass | |
| Q159 首页 `/api/dashboard` 不出现未实现字段 | Pass | |
| 合计接口 500 → 合计行显示 `unrealized-summary-error`（含 INTERNAL_ERROR），不显示数字，持仓行照常；恢复后点"重新加载合计"即显示合计 | Pass | E2E |
| 行级契约不变式（每个场景）：OK 行 pnl = 净额 + 市值（股票为 市值 − 成本）、scale 2、百分比不带 +/%；非 OK 行三个数都是 null、标签正确 | Pass | |

## 发现的 Bug
- BUG-006（frontend，已 Closed）：`refreshAfterChange` 按设计新增失效 `unrealized-summary`，但旧单元测试 `tradeFormSupport.test.ts` 精确列出全部 queryKey，没有更新，导致 `npm test` 失败。页面行为正确，只需更新这个测试。详见 docs/bugs/BUG-006-feat017-refresh-after-change-unit-test.md。

## 疑问 / 需求不清楚的地方
- 百分比分母为 0（显示 `—`）无法从 API 造出数据（金额都必须 > 0，指派又有 `STOCK_COST_NOT_POSITIVE` 拦着），只由后端 domain 单元测试和前端单元测试覆盖。协调方已接受。
- 显示格式按 Q162（用户决定）验证：所有市值（期权行、股票行、合计三组）都带显式正负号；未实现盈亏带符号；百分比如 +22.80%；值为 0 时显示 0.00，不带符号。
