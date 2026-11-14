# FEAT-014 测试报告

- **测试日期:** 2026-11-13
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat014ExerciseSystemTest.java（27 个用例，固定时钟，场景 E）
- **E2E 测试:** e2e/tests/feat-014-exercise.spec.ts（20 个用例，真实时钟，日期相对美东今天 T：到期日 T−3，S1 到期 T+25）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 按钮（Q81：到期前 平仓+行权、16:00–17:30 只有行权、之后 作废+行权；空头无行权）、对话框选项 / 默认值 / 可用现金 / 持股与可用股数、行权 S1 被拒 | Pass | Pass | API 覆盖三个时段；E2E 到期日当天按美东时段分支断言（本次在 17:30 之后运行） |
| 2 | call 全部行权：成本 9,460.00、每股 31.5333、现金 2,510.00、行权买入 300 股、已平仓 1 行 0.00 共 1 笔、流水说明、无审计 | Pass | Pass | 不刷新页面，各页都更新 |
| 3 | put 行权：实收 4,380.00、交出 T1 4,000.00、+380.00、现金 16,010.00、XYZ 100/100/0、已平仓 2 行 2 笔、首页 +380.00（2 笔） | Pass | Pass | |
| 4 | 提前行权、整笔批次、日期规则、费用 5.00（成本 6,305.00）、剩余作废 −160.00；先作废再行权被拒 | Pass | Pass | |
| 5 | 现金不足 −490.00；2 张成功 2,510.00 | Pass | Pass | |
| 6 | 持股不足（小账户）；被 call 覆盖（Q113）被拒、BTC 后成功 | Pass | Pass | |
| 7 | 删除与审计摘要 / before；之后卖出使删除被拒；不可修改；生成的股票记录不能改删 | Pass | Pass | |
| 8 | 重放：L2 → 170.00 成本 9,470.00 与审计连带变化；L3 → 130.00 实收 4,370.00；现金 6,990.00；删除 L2 被拒 | Pass | Pass | |
| 9 | multiplier 1、12.345：买股 12.35（向上）、行权金额 12.34（向下）、流水带 multiplier | Pass | Pass | |
| 10 | 账户隔离（404）、格式校验在字段下 | Pass | Pass | |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 行权 4 张超过持仓 3 张；"行权日期不能晚于今天"；行权日期早于开仓 → 无持仓 | Pass | API |
| 作废 3 张后补记更早的行权 → 之后的到期作废超量 | Pass | API |
| put 卖出实收 −5.00 / 0.00 被拒，0.01 允许（股票盈亏为负） | Pass | API |
| put 行权使之后的 covered call 覆盖不足（E3） | Pass | API |
| call 行权得到的股票用于之后的 covered call；同为 16:00、行权之后录入的卖出用到行权买入批次（部分分摊 3,153.33） | Pass | API |
| 删除 put 行权；修改 T1 → 审计 `交出成本 4,000.00 → 3,900.00`；删除 T1 被拒 | Pass | API |
| exerciseDate 格式错 / 不存在、行权费不是数字 → 400；忽略 action / netAmount / multiplier；备注 500 字可以、全空白存 null | Pass | API |
| 开仓 / 平仓接口收到 EXR → 400；交易 / 股票 / 已平仓筛选的新文案 | Pass | API |
| 交易列表"行权"筛选、股票页"行权买入 / 行权卖出"筛选、已平仓"行权"筛选只剩期权行 | Pass | E2E |
| 后端返回 500 时对话框的提示 | Pass | E2E |

## 回归与构建
| 检查 | 结果 |
|------|------|
| `gradlew.bat build` | Pass（906 个测试，0 失败） |
| 前端 `npm run lint && npm run typecheck && npm test` | Pass（32 个文件，580 个测试） |
| E2E feat-014 | 20/20 Pass |
| E2E 受影响 spec（002、003、005、006、007、008、010、011、012、013、038、039、040、041） | 第一次 189/191 Pass，2 个失败都是测试代码问题（见下），修完后 feat-003 + feat-013 重跑 26/26 Pass |
| 端口 | E2E 用 18080 / 15173，跑完无残留进程 |
| 全量 Playwright | 按规则未运行（不是里程碑结束） |

测试代码调整：
- 写测试阶段按契约更新了已有系统测试的筛选文案（Feat002、Feat006、Feat011、Feat040、Feat041：方向加 `EXR`，股票类型加 `EXERCISE_BUY`、`EXERCISE_SELL`）。
- Feat014 AC8：审计 `sideEffects` 的 `before` / `after` 是显示用金额（`"9,460.00"`，同 FEAT-013，契约已写明），测试最初按 `"9460.00"` 断言，属测试代码问题，已改正。
- E2E 预设的 testid 与前端实现一致，无需改动（核对了 `frontend/src` 中的 `position-exercise`、`exercise-*`、`trade-cell-exercise`、`closed-cell-exercise`、`closed-stock-exercise-link`、`closed-exercise-note`、`stock-exercise-link`、`stock-exercise-hint`、`stock-filter-type-exercise-buy/sell`、`trade/closed-filter-action-exr`）。
- feat-003 AC1+AC5：旧断言"持仓页不能有'行权'按钮"与 FEAT-014（多头行显示行权）冲突，属预期行为变化；改为断言"不能有删除 / 编辑"，且 `position-exercise` 正好在两个多头行（AAPL 210 C、MSFT 450 C）。
- feat-013 AC1：第二个对话框的张数下拉读到空列表（`.ant-select-dropdown:visible` 时序问题），单独重跑通过，判为偶发；与 FEAT-014 无关，代码未改，后续如再出现再加固。

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- 需求没有给出对话框说明段的原文，E2E 只检查 put 版含"被 call 覆盖的股票不能卖出"、call 版不含。
- AC4 "QRTV 行权 1 张 → 拒绝" 没写日期，测试用 2026-11-18（让整笔批次检查而非日期检查起作用）。
