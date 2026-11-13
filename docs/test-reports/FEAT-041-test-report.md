# FEAT-041 测试报告

- **测试日期:** 2026-11-13
- **结论:** Verified（BUG-003 已修复并复测通过）
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat041CallAssignmentSystemTest.java（23 个用例，固定时钟）
- **E2E 测试:** e2e/tests/feat-041-call-assignment.spec.ts（14 个用例，真实时钟，日期相对美东今天 T：到期日 T−3）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 按钮（Q95）、对话框选项 / 默认值 / 当前持股、多头 10 C 被拒 | Pass | Pass | E2E 到期日当天按美东时段分支断言 |
| 2 | 全部被指派：+235.00、现金 10,220.00、已平仓 2 行、汇总 / 首页 2 笔、无审计 | Pass | Pass | 不刷新页面，各页都更新 |
| 3 | 提前指派、指派费 5.00、部分卖出股票批次、剩余作废 +25.00 | Pass | Pass | |
| 4 | 持股不足（Q75）、更晚的买入不算、补录转入后成功 | Pass | Pass | |
| 5 | 使之后的卖出不成立；改为 2 张后成功 | Pass | Pass | |
| 6 | 删除、审计摘要与 before；不可修改；生成的股票记录不能改删 | Pass | Pass | |
| 7 | 重放：C2 净额改动、补录更早的转入、删除 S2 / 补录卖出被拒 | Pass | Pass | |
| 8 | multiplier 1、行权价 12.345，行权金额向下取整（Q99） | Pass | Pass | |
| 9 | 账户隔离、格式校验 | Pass | Pass | |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 同为 16:00、在被指派之前录入的买入算持股；股票盈亏为负（−80.00） | Pass | API |
| 同为 16:00、在被指派之后录入的卖出 → 持股不足 | Pass | API |
| 之后已有的 call 被指派不再成立 | Pass | API |
| Wheel：put 被指派生成的 ASSIGN_BUY 被 call 交出；之后删除 put 被指派被拒 | Pass | API |
| 卖出实收 −5.00 / 0.00 被拒，0.01 允许 | Pass | API |
| 指派费大于行权金额导致现金不足 | Pass | API |
| 修改 S1 → STOCK_TRADE 审计 sideEffects `交出成本 2,550.00 → 2,560.00`；修改 S2 被拒 | Pass | API |
| 日期规则、无持仓、备注 / 指派费的 remark | Pass | API |
| 后端返回 500 时对话框的提示 | Pass | E2E |

## 回归与构建
| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests` Feat041 / Feat013 / Feat011 | Pass（Feat041 AC5 第一次失败，是测试代码问题：卖出录入时时钟还没调到 11-25；已修复） |
| E2E feat-041 | 14/14 Pass |
| E2E feat-013、feat-012、feat-011、feat-040 | 67/67 Pass（修复后）。2 个用例最初失败，都是测试代码问题，只在美东 16:00–17:30 才会走到的分支里：`getByRole('button')` 把批次展开按钮也算进去了，现在只匹配操作按钮；FEAT-012 悬停说明改回需求原文（不含"空头 put 可记录被指派"）。这 2 个 spec 单独重跑，Pass |
| `gradlew.bat build` | Pass（809 个测试）。Feat033MigrationSystemTest 最初失败，是 QA 测试代码问题：`MAX(version)` 按字符串比较，有了 V10 后 "9" > "10"；已改为按数字比较 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck Pass；`npm test` 最初 1 / 507 失败（BUG-003），修复后复测 `assignValidation.test.ts` 14/14 Pass |
| 全量 Playwright | 按 qa.md 未运行 |

为适配 FEAT-041 契约而更新的已有测试：Feat013 AC1（空头 call 现在可以被指派，无股票时持股不足）、Feat011 类型文案、feat-013 E2E AC1 的空头 call 按钮。

## 发现的 Bug
- BUG-003（frontend）：`assignValidation.test.ts` 仍断言旧的股票类型文案，导致 `npm test` 失败。已修复，复测通过，Closed。

## 疑问 / 需求不清楚的地方
- 无。
