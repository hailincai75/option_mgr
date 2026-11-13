# FEAT-004 测试报告

- **测试日期:** 2026-11-11
- **结论:** Verified（11 条验收标准全部通过，额外边界测试全部通过，没有发现 bug）
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/Feat004CloseTradeSystemTest.java`（30 个用例）
  - 走真实 HTTP，时钟固定为 2026-11-11 10:00（美东）。
  - 每笔被拒的请求都核对：现金汇总、完整流水（包括 `frozenChange`）、交易数和持仓都没有变化。
  - 每次写入后都核对持仓的 `frozenTotal` 等于现金汇总的 `frozenCash`。
- **E2E 测试:** `e2e/tests/feat-004-close-trade.spec.ts`（16 个用例），用真实时钟；两个入口都测了（持仓页的"平仓"对话框、"录入交易"页的 BTC / STC）。
- **对已有 E2E 的调整（意图不变）：**
  - `feat-002-trade-list.spec.ts`：导航文字从"录入开仓"改为"录入交易"。
  - `feat-001-open-trade.spec.ts` AC3：开仓确认里冻结变动为 0 时，原来显示 `0.00`，FEAT-004 前端说明中改为显示 `—`（与流水同一规则），所以期望值改为 `—`。

## 运行结果
| 命令 | 结果 |
|------|------|
| `cd backend && gradlew.bat build`（JDK 21） | **BUILD SUCCESSFUL**：407 个测试，0 失败；其中 QA 系统测试 224 个。boot jar 已重建 |
| `cd e2e && npx playwright test` | **122 passed (4.4m)**，0 失败：FEAT-007 27、FEAT-001 51、FEAT-002 19、FEAT-003 9、FEAT-004 16 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 平掉最早一笔整批次 | Pass — `ac1_closeEarliestLot` | Pass — `AC9+AC1 …` | 确认信息逐项核对：−241.30、冻结 −40,000.00、批次 `2026-11-03 10:30 STO 2 张 +698.70`、盈亏 +457.40。现金汇总、流水（期权平仓、冻结 −40,000.00、说明）、持仓（1 张、可平 [1]、冻结合计 20,000.00）、交易列表（首行和只选 BTC 的筛选）都正确，GET 与 POST 响应一致；开仓的 `coveredLots` 和 `realizedPnl` 为 null |
| 2 | 不能部分平仓 | Pass — `ac2_notWholeLots` | Pass — `AC2 …` | 提示原文、错误码 `CLOSE_NOT_WHOLE_LOTS`；9 个输入都保留，multiplier 隐藏；改为 2 张后成功 |
| 3 | 一笔覆盖多批 | Pass — `ac3_multipleLots` | Pass — `AC3 …` | 冻结 −60,000.00、两行批次、盈亏 +658.70；持仓页显示共 0 个持仓、冻结合计 0.00 |
| 4 | 超过持仓、方向不对、没有持仓 | Pass — `ac4_rejections` | Pass — `AC4 …` | 4 种情况的原文与契约一致；另测 L1 与 L2 之间的时点只能平 2 张 |
| 5 | 全部平掉后反方向重开 | Pass — `ac5_reopenOppositeDirection` | Pass — `AC5 …` | 盈亏 +210.00；BTO 后变为多头 −300.00；再平掉后可以换 multiplier 重开 |
| 6 | 先释放冻结再查现金（Q20） | Pass — `ac6_releaseBeforeCashCheck`、`ac6_exactBoundary` | Pass — 两个 `AC6 …` | 20,349.36 被拒，原文含"释放冻结"，输入保留；20,349.35 成功到 0/0/0，盈亏 −20,000.00；500.00 时盈亏 −150.65 |
| 7 | 多头 call STC | Pass — `ac7_stcLongCall` | Pass — `AC7 …` | 净额标签为"实收净额"；+830.00、冻结 `—`、盈亏 +317.35；流水说明正确；用 BTC 被拒并提示用 STC |
| 8 | 到期后不能平、到期日当天可以（Q36） | Pass — `ac8_expiry`、`expiryNewYorkDateBoundary` | Pass — `AC8 …` | 已过期的行没有平仓按钮；11-09 平仓时错误显示在成交时间字段下；到期日 16:30 平仓成功，盈亏 +290.00。**FEAT-012（Q83）已改变**：到期日 16:30 现在被拒（成交时间不能晚于到期日（2026-11-06）美东 16:00），16:00 成功 +290.00；今天到期的持仓 16:00 起不再有平仓按钮。`ac8_expiry` 与 E2E `AC8`/`AC9` 已更新（2026-11-13） |
| 9 | 持仓页"平仓"按钮 | Pass — `ac9_expiresTodayIsClosable` | Pass — `AC9+AC1 …`、`AC9 今天到期…` | 预填合约和方向（只显示、不可改），可平 2 / 3 默认 2，标签正确，默认时间为当前美东时间，对话框内显示现金汇总；提交后不刷新页面，可平张数变为只有 1；今天到期的多头行显示按钮，方向预填 STC |
| 10 | 补录平仓（Q39） | Pass — `ac10_backdatedCloseRematch` | Pass — `AC10 …` | 补录会让之后的平仓没有持仓时被拒，提示指出时间和那笔；补录合法时，之后那笔改为平 L2，盈亏 +260.00，交易列表与按 id 查询结果一致 |
| 11 | 字段校验与 multiplier 10 | Pass — `ac11_fieldValidation`（9 组）、`noteAndOpenEndpoint`、`ac11_multiplier10` | Pass — 对话框校验、录入页张数校验、`AC11 multiplier 10…` | 前端校验时不发请求；请求里带的 multiplier 被忽略；开仓接口仍拒绝 BTC/STC |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 可平张数列表：批次 1、2、1 → `[1, 3, 4]`；平 2 张时提示列出全部可平张数；`q`、`10.000` 也能识别为同一合约 | Pass | API |
| 补录 BTC 导致之后的出金时点现金变负 → `CASH_INSUFFICIENT`（沿用 FEAT-007 的文案） | Pass | API |
| 补录平仓导致之后的平仓超过持仓 → `CLOSE_EXCEEDS_POSITION` | Pass | API |
| 补录较早的开仓（走开仓接口）导致之后的平仓不再是整笔批次 → `CLOSE_NOT_WHOLE_LOTS`；补录反方向开仓 → 多空冲突；在平仓之后补录开仓则允许 | Pass | API |
| **Q28 + Q39：之后那笔平仓的释放额从 24.70 改为 24.69**：该笔流水的 `frozenChange` 原地改正，现金金额不变；冻结合计和现金汇总都回到 0.00 | Pass | API `frozenCorrectionOnRematch` |
| 同一时刻：先录入的开仓可以被同一时间后录入的平仓平掉；平仓先录入则没有持仓可平 | Pass | API |
| 到期日的美东日期边界：23:59（UTC 已是次日）允许，次日 00:00 拒绝；字段错误优先于"没有持仓" | Pass | API。**FEAT-012（Q83）已改变**：23:59、16:00:01 现在被拒（16:00 提示），16:00:00 允许；`expiryNewYorkDateBoundary` 已更新 |
| 读接口：持仓和现金汇总多次查询结果一致；POST 持仓接口返回 405 | Pass | API |
| 录入交易页：四个方向；导航文字为"录入交易"；BTO/STO 显示 multiplier，BTC/STC 隐藏；净额标签随方向切换 | Pass | E2E |
| 取消对话框不提交、数据不变 | Pass | E2E |

## 发现的 Bug
无。

## 备注
- `close-trade-modal` 这个 testid 挂在 antd 的 `.ant-modal-root` 上，这个元素尺寸为 0，Playwright 会判定它不可见。测试里改为在它下面找 `role=dialog` 来判断对话框是否打开。不算 bug，供 frontend-dev 参考：如果以后希望 testid 直接可见，可以挂到对话框内容上。
- antd 表单的错误提示在消失动画期间，旧提示和新提示会同时存在一小会儿。测试里已排除正在消失的那条。
