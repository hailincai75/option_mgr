# FEAT-018 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified（第 2 轮）。第 1 轮 10 条验收全部通过（API 和 E2E 都通过），但前端全量 `npm test` 有 1 个失败：frontend-dev 的单元测试没有随新开关更新（BUG-007，frontend），所以判为 Failed。BUG-007 修好后复测通过，已 Closed。见下方「复测记录」
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat018GreeksAcceptanceSystemTest.java（10 个）、Feat018GreeksBoundarySystemTest.java（17 个）、公共基类 Feat018GreeksSystemTestBase.java（继承 FEAT-017 / FEAT-016 的 QA 基类：假 CBOE + tick + 可设时钟，不访问真实 CBOE / Finnhub，随机端口）
- **E2E 测试:** e2e/tests/feat-018-greeks.spec.ts（10 个）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试，含 bootJar） | BUILD SUCCESSFUL（2 分 52 秒）；107 个测试类，1296 个测试，0 失败 / 0 错误 / 0 跳过。FEAT-018 共 27 个，全部通过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint 和 typecheck 通过。Vitest 44 个文件、706 个测试，**1 个失败**：`PositionsPage.test.tsx` 的 “shows header values and every position in backend order (AC 1)”，见 BUG-007 |
| E2E：本需求 + 受影响 spec（3 个 worker，端口 18080+ / 15173+） | 第 1 轮 29 / 34 通过：feat-018 10 / 10、feat-016 11 / 11、feat-010 8 / 13。feat-010 的 5 个失败是测试代码的问题（见下），修好后只重跑 feat-010，13 / 13 通过。最终 34 / 34 通过 |

## 复测记录
| 时间 | 检查 | 结果 |
|------|------|------|
| 2026-11-14 第 2 轮 | BUG-007 修复（只改了 `PositionsPage.test.tsx`）后，跑前端全量 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 44 个文件、706 个测试全部通过 → BUG-007 Closed，FEAT-018 **Verified** |
| 2026-11-14 第 2 轮 | 后端和 E2E | 未重跑：产品代码没有变化，沿用第 1 轮的结果（后端 1296 / 1296，E2E 34 / 34） |

### 受影响 spec 的选择（grep 依据）
本次改动：持仓页期权卡片（`positions-page`）里新加了开关，文字是 `显示 Greeks`，默认关闭。打开后显示口径说明，并多出 5 列（`greeks-header-*`、`position-greeks-*`）。在 `e2e/tests/` 中 grep 了以下内容：
- `greek`、`显示 Greeks`、`positions-greeks`、`position-greeks`、`greeks-header`、`CBOE 延迟报价`：除了 feat-018，只有 `feat-016-auto-quotes.spec.ts:216` 命中。它断言行内不显示 IV / Greeks（`not.toContainText('0.3512' / '-0.3021')`），所以**选入**。
- `显示`：`feat-010-expiry-filter.spec.ts:164` 断言 `positions-page` 整个卡片不含 `显示`。新开关的文字就在这张卡片里，所以**选入**。
- `thead th|columnheader`：feat-009 只看 `剩余交易日` 一列，feat-040 看的是已平仓页和首页卡片，都与持仓表的新列无关，未选。
- feat-003 的 `th` 断言只用 `toContain('仅供参考')`，按钮断言用的是 `getByRole('button')`，而 antd Switch 的 role 是 switch。开关默认关闭，列也不变，所以未选。其余引用持仓页的 spec 只断言自己的 testid，留给里程碑的全量 E2E。

### 修改既有 E2E（测试代码问题）
`feat-010-expiry-filter.spec.ts:164` 原来是 `not.toContainText('显示')`。它的本意是“全部”过滤时不出现 `显示 M / N 个持仓`（helper 的注释写的是 `no 显示 M / N`）。FEAT-018 按需求（Q173）加的 `显示 Greeks` 也会命中这条断言。现在改为 `not.toContainText(/显示 \d+ \/ \d+/)`：检查的内容还是原来那个，没有放宽。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | P1：IV 35.12%；Delta +30.21（−0.3021）；Gamma −5.12（0.0512）；Theta +4.56（−0.0456）；Vega −7.11（0.0711）；没有 Rho | Pass | Pass | API 还检查了：greeks 字段集合与契约完全一致、quoteTime 09:45、FEAT-017 的值没变、按库里原值独立重算一致 |
| 2 | P2：33.20%；+57.00（0.2850）；+9.60；−7.80（−0.0390）；+13.00 | Pass | Pass | |
| 3 | P3：29.00%；−18.35（0.1835）；−3.13（0.0313）；+2.47；−5.03 | Pass | Pass | 库里原值确实是 0.18345 / 0.03125：每张值 HALF_UP 到 4 位，持仓值用未取整的原值计算 |
| 4 | P4 五列都是 无报价；FEAT-017 合计不变；没有 Greeks 合计 | Pass | Pass | 合计 160.00 / 61.75，总计 6270.00 / 291.75，与 FEAT-017 一致；股票行没有 greeks |
| 5 | 账户 B 的 P5：35.12% / +90.63 / −15.36 / +13.68 / −21.33 | Pass | Pass | |
| 6 | 10:15 拉到新值 36.00% / +33.00 / −5.30 / +4.70 / −7.20；10:30 拉取失败后保留 | Pass | Pass | staleLabel 为 `行情未更新（上次：11-23 10:15）`；账户 B 跟着同一份报价变化 |
| 7 | 手动 Mark 1.40：Greeks 不变，显示标注；清除后标注消失；P4 设手动 0.30 仍是 无报价 | Pass | Pass | 标注全文 `Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整`；P4 的未实现盈亏变为 OK，Greeks 仍为 NO_QUOTE |
| 8 | P2 的 gamma、vega 为 null：这两列显示 —，其它照常 | Pass | Pass | 库里这两个字段也被成功拉取整体替换为 null |
| 9 | P6 显示 已到期，待处理；P7（今天到期）Delta +45.00（−0.4500） | Pass | Pass | P6 的 quoteTime 仍给出库里的最后报价时间 |
| 10 | 开关默认关闭、没有 Greeks 列；打开后 未实现盈亏 之后五列；悬停 Theta 的 ? 显示说明；上方口径说明 | Pass（API 侧：每行都有 greeks） | Pass | E2E 还检查了：没有 Rho 列、关闭后列头恢复原样、悬停 Delta 的 ? 显示说明 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 已过期且从未报价 → EXPIRED_PENDING（优先于 NO_QUOTE）；已过期 + 手动 mark → EXPIRED_PENDING，无标注 | Pass | API |
| 到期日当天有报价 → OK；收盘后从链中消失（MISSING_FROM_SOURCE）→ 仍用最后值；次日 → 待处理 | Pass | API |
| 周末不拉取，用库里最后的值；周五到期的合约周六变为待处理 | Pass | API |
| 指数 SPX → 无报价，有手动 mark 也是无报价；匹配有歧义（`B1` 前缀）→ 无报价；从未成功拉取 → 无报价，第一次成功后有值 | Pass | API |
| bid 0 且无 theo：未实现盈亏 NO_QUOTE，Greeks 仍 OK；mark 来自 theo 时 Greeks 照常 | Pass | API |
| 未过期合约从链中消失 → 保留最后的 Greeks；立即刷新后得到新值 | Pass | API |
| iv 缺失、字段为显式 JSON null、字段格式错误（`"N/A"`）→ 只有该字段为 null | Pass | API |
| 取整与零：1.0000 / 200.00；0.0000 / 0.00；−0.00005 → −0.0001；−0.004 → 0.00（无负号）；IV 0.123456 → 12.35 | Pass | API；E2E 检查页面显示 `0.00` 不带符号、IV 为 `—` |
| multiplier 10：持仓值 3.70（按字段值计算，不写死 100） | Pass | API |
| FIFO：整笔平掉最早一笔后按剩余张数重算；全部平仓后行消失 | Pass | API |
| Q148：下一次成功拉取替换掉手动值 → 标注消失 | Pass | API |
| Q157：只看当前账户 | Pass | API |
| FEAT-043 调整到期日：响应里 `position.greeks` 结构正确，与随后 GET 一致；新合约被拉到报价后显示其 Greeks | Pass | API |
| 响应缺少 greeks（旧响应）→ 该行五列显示 `—` | Pass | E2E（`page.route`） |

## 发现的 Bug
- BUG-007（frontend，**Closed**）：`PositionsPage.test.tsx` 断言卡片里只有指定的那几个按钮，新开关（`<button role="switch">`）多出了一个。这是单元测试没有更新，不是功能缺陷，但前端全量 `npm test` 因此不通过。第 2 轮复测已通过。

## 疑问 / 需求不清楚的地方
- 无。测试编写阶段的 4 个疑问都已由协调方答复：格式错误只把该字段当缺失；保留 multiplier 10 的用例；开关记在 localStorage；Q148 的用例已收紧。
