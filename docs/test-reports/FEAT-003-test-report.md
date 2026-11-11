# FEAT-003 测试报告

- **测试日期:** 2026-11-11
- **结论:** Verified（10 条验收标准全部通过，额外边界测试全部通过，没有发现 bug）
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/Feat003OptionPositionsSystemTest.java`（15 个用例）
  - 走真实 HTTP，时钟固定为 2026-11-11 10:00（美东），个别用例会调整时钟来测 DTE 边界。
- **E2E 测试:** `e2e/tests/feat-003-positions.spec.ts`（9 个用例）
  - 用真实时钟，运行时美东日期为 2026-11-11。"今天"取运行时的美东日期，期望的 DTE 按"到期日 − 今天"计算。
  - 0DTE 用例的到期日取今天；已过期用例是补录一笔成交日 ≤ 到期日 < 今天的交易。
  - 为方便录入基准数据，`e2e/support/trade.ts` 的 `apiTrade` 增加了可选的 `note` 参数。

## 运行结果
| 命令 | 结果 |
|------|------|
| `cd backend && gradlew.bat build`（JDK 21） | **BUILD SUCCESSFUL**：360 个测试，0 失败；其中 QA 系统测试 194 个。boot jar 已重建 |
| `cd e2e && npx playwright test` | **106 passed (4.1m)**，0 失败：FEAT-007 27、FEAT-001 51、FEAT-002 19、FEAT-003 9 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 持仓列表与排序 | Pass — `ac1_positionsAndOrder` | Pass — `AC1+AC5 …` | 截至 2026-11-11、共 5 个持仓、冻结合计 44,500.00，与资金页的冻结现金一致；5 行的顺序和每一列都逐格比对；表头有"仅供参考"和含手续费的说明；没有平仓、删除、编辑等操作按钮；导航中"持仓"高亮 |
| 2 | 合并显示、展开 FIFO 批次 | Pass — `ac2_lots`（每个批次的原值和剩余值、UTC 时间和美东时间都核对） | Pass — `AC2 …` | AAPL 200 P 两个批次 T1→T4；F 一笔 2 张只算 1 个批次，显示 `2 / 2`；BTO 批次净额为 `−512.65`、冻结显示 `—`；收起后批次表隐藏 |
| 3 | 平均净额舍入（Q22） | Pass — `ac3_averageRounding`、`q22_halfUp` | Pass — `AC3 …` | KO −66.67、PEP −33.33；另测半分处一律向上：0.125→0.13、−0.125→−0.13、2.005→2.01、0.175→0.18、0.0033→0.00，合计金额不受舍入影响 |
| 4 | 冻结按批次相加（Q28） | Pass — `ac4_frozenSumOfLots` | Pass — `AC4 …` | 24.70（12.35 + 12.35）；与现金汇总、资金页的冻结一致 |
| 5 | DTE | Pass — `ac5_dteNewYorkMidnight`、`dteAcrossDstDays` | Pass — `AC1+AC5 …`（用真实日期计算） | 美东 11-12 00:30 → 36；UTC 已是 11-12 但美东还是 11-11 23:30 → 37；美东午夜 23:59:59 与 00:00 的切换；夏令时切换日（3-08、11-01）的美东午夜 |
| 6 | 今天到期与已过期 | Pass — `ac6_expiredAndToday` | Pass — `AC6 …` | TSLA：`EXPIRED`、−5、已过期 5 天、冻结 25,000 不释放；SPY：`EXPIRES_TODAY`、0；第二天 SPY 变为已过期 1 天。E2E 中"已过期 N 天"为红色（计算后的颜色以红为主），SPY 显示 `0` 和"今天到期"标签 |
| 7 | 新录入后自动刷新 | Pass — `ac7_newTradesReflected` | Pass — `AC7 …` | 不刷新页面：AAPL 200 P 变为 3 张、+1,049.35、平均 +349.78、冻结 60,000.00，新批次在最下；冻结合计 64,500.00；NVDA 排在 F 之后、XYZ 之前，共 6 个持仓 |
| 8 | 跳转到交易列表 | Pass — `ac8_linkParametersWorkWithTradeList` | Pass — `AC8 …` | 交易列表筛选区回填 AAPL / 2026-12-18 / 200 / Put，显示 T4、T1，共 2 条 |
| 9 | 空状态 | Pass — `ac9_empty` | Pass — `AC9 …` | 显示"暂无持仓"、共 0 个持仓、冻结现金合计 0.00；"录入开仓"入口可用 |
| 10 | 只读 | Pass — `ac10_readOnly` | Pass — `AC10 …` | 多次查询结果一致；POST、DELETE 返回 405；现金汇总、流水、交易数都不变 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| FIFO：补录较早的开仓排在第一；同一时间先录入的在前；行权价写成 `200.000` 也归入同一持仓 | Pass | API `fifoOrder` |
| 排序：已过期的在最上 → 到期日 → 标的按字符编码（`B2` < `BRK.B` < `BRKB`）→ 行权价按数值（9.5 < 10 < 100）→ 同一行权价 Call 在 Put 前 | Pass | API `sorting` |
| 多头 put 冻结为 0；multiplier 10 的空头 put 按实际 multiplier 冻结（2 张 × 50 × 10 = 1,000） | Pass | API `longPutAndMultiplier` |
| 冻结合计始终等于现金汇总里的冻结现金（基准数据、24.70、25,000、1,000 各场景） | Pass | API + E2E |
| FEAT-007 / 001 / 002 回归（导航新增"持仓"） | Pass | 97 个原有 E2E 全部通过 |

不适用于本需求：平仓（FEAT-004）造成的剩余张数、净额、冻结与原值不同的情况，以及到期作废、指派（M2）。目前接口返回的剩余值等于原值，这一点已核对。

## 发现的 Bug
无。

## 疑问 / 需求不清楚的地方
- 无。页面停留期间跨过美东午夜时 DTE 不会自动更新，这是需求允许的，没有测。
