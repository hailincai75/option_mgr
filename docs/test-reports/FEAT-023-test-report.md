# FEAT-023 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified
- **后端集成测试:**
  - backend/src/test/java/com/optionmgr/systemtest/Feat023UnderlyingPnlSystemTestBase.java（场景、读取与契约不变量、一致性检查）
  - backend/src/test/java/com/optionmgr/systemtest/Feat023UnderlyingPnlAcceptanceSystemTest.java（AC1–8，8 个）
  - backend/src/test/java/com/optionmgr/systemtest/Feat023UnderlyingPnlBoundarySystemTest.java（边界与错误，11 个）
- **E2E 测试:** e2e/tests/feat-023-underlying-pnl.spec.ts（AC1–9 + 行情刷新 + 3 个边界，共 13 个）；受影响：e2e/tests/feat-021-group-by-underlying.spec.ts（已按 FEAT-023 更新灰字、表头、禁止列）

## 运行结果
| 检查 | 命令 | 结果 |
|------|------|------|
| 后端全部构建与测试 | `cd backend && gradlew.bat build` | BUILD SUCCESSFUL；1382 个测试，0 失败 / 0 错误 / 0 跳过（含 FEAT-023 的 19 个） |
| 前端检查 | `cd frontend && npm run lint && npm run typecheck && npm test` | lint 0 warning；typecheck 通过；Vitest 49 个文件 746 个测试全部通过 |
| E2E（本需求 + 受影响） | `npx playwright test tests/feat-023-underlying-pnl.spec.ts tests/feat-021-group-by-underlying.spec.ts`（端口 18080+ / 15173+） | 第一次：23 通过、2 失败（都是测试代码问题，见下）；修正后重跑 feat-023：13/13 通过。feat-021：12/12 通过 |
| E2E 类型检查 | `cd e2e && npx tsc --noEmit` | 通过 |

**E2E 第一次失败（测试代码问题，已修正，不是产品 bug）：**
1. AC6 在交易列表里按"动作 + 合约"定位作废记录时用了 `:text-is`。合约单元格里是一个链接按钮，所以没匹配到。改成 `filter({ has: getByText(…, { exact: true }) })` 后通过。
2. AC8 断言账户 B 的已实现 `0.00` 不着色时，颜色判断函数把 antd 的链接蓝 rgb(22,119,255) 误判成绿色。实际上 0.00 用的是普通链接色（`SIGNED_MONEY_COLORS[0] = undefined`），符合"0.00 不着色"。函数改为只有绿、红分量占主导时才判为绿 / 红；正绿负红的断言没有放宽。

**受影响 spec 的选择与 grep 依据：** 本次前端改动的 testid / 文字只涉及策略页：`underlying-hint` 的文字，期权表、股票表新加的表头 `市值` / `未实现盈亏`，以及新增的 `underlying-pnl-*` / `underlying-*-cell-market-value|unrealized-pnl*`。另外 `tradeFormSupport`、`marketDataRefresh` 以及作废 / 批量作废 / 调整到期日三个对话框只在失效列表里加了 `underlying-summary` query key，git diff 中没有改 testid 或文字。
在 `e2e/tests/` 里 grep `underlying-hint|操作请到持仓页|nav-strategies|underlying-card|underlying-option|underlying-stock|underlying-pnl`，只命中 feat-021 和 feat-023。`underlying-price` 也带 `underlying-` 前缀，但它属于持仓页行情（feat-016 等），本次没有改动，所以不算受影响。最终选了 2 个 spec：feat-023、feat-021。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 账户 A 依次 IXQZ、QRTV、ZWLK（无 QFZ），摘要行数字与提示 | Pass | Pass | 数字与契约示例逐字相同；颜色、U+2212 |
| 2 | QRTV 卡片行市值 / 未实现 / 百分比；58P `已到期，待处理`；ZWLK `无报价` | Pass | Pass | 卡片 = 行之和 |
| 3 | 持仓页合计 +4,610.00 / +175.05 = 三张卡片之和；无全部标的合计；盈亏汇总四行 | Pass | Pass | 与 `unrealized-summary`、`pnl-summary`、两个持仓接口一致 |
| 4 | 点 `+39.70` → 已平仓页 QRTV、类型不限、无日期、只有 H2 | Pass | Pass | |
| 5 | 10:30 BTC 45P → QRTV +99.40 / +214.70 / +314.10 / +4,970.00 / +44.70，45P 行消失，不刷新页面 | Pass | Pass | |
| 6 | 作废 58P → IXQZ +170.00 / −50.00 / +120.00 / −250.00 / +200.00、提示消失；删除 → 恢复 | Pass | Pass | |
| 7 | ZWLK 手动现价 32.00 / H7 实收改 650.00 / 清除现价 | Pass | Pass | |
| 8 | 账户 B 只有 QRTV（0.00、+5.00、+5.00、−110.00、+115.00）；空账户 `暂无持仓` | Pass | Pass | |
| 9 | 显示 Greeks 打开时无 Greeks、无最大盈利等、无按钮；新灰字；表头加两列 | N/A（纯前端） | Pass | |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 错误码：无账户头 400 `ACCOUNT_REQUIRED`、非整数 400 `BAD_REQUEST`、不存在 404 `ACCOUNT_NOT_FOUND`、POST / PUT / DELETE 405 | Pass | API |
| 多余 query 参数（日期、到期过滤、symbol）忽略，已实现仍为全部时间 | Pass | API |
| 从未拉取行情：全部行不可算 → 市值 / 未实现 null，合计 = 已实现，净权利金照算 | Pass | API |
| 同一合约两批开仓 + BTC 1 张：FIFO 平掉最早一批（+99.40，不是 LIFO 的 +89.05） | Pass | API |
| 到期日当天（12-18）照常计算；周六 12-19 全部期权已到期待处理，IXQZ 全不可算 | Pass | API + E2E（到期日当天） |
| put 被指派：期权 0.00 计 1 笔，60P 离开净权利金，股票按上次现价计入；删除指派后恢复 | Pass | API + E2E |
| call 被指派：股票交出，卖出已实现 +785.35，hasStockPosition false | Pass | API |
| 股票卖完卡片消失（盈亏汇总仍有），再买入后重新出现且含全部历史 | Pass | API + E2E |
| 期权全部平掉只剩股票：netPremium null | Pass | API |
| 市值合计恰为 0.00（非 null、非 -0.00）；净权利金为负 | Pass | API |
| 只有已过期未处理期权：市值 / 未实现 null，净权利金照算 | Pass | API |
| 持仓页 `立即刷新` 拉到新行情 → 不刷新页面，卡片摘要和行更新（+405.05 / +444.75 / +5,040.00） | Pass | E2E |
| 摘要接口 500：卡片和行仍显示，摘要为 `盈亏暂不可用`，不显示数字 | Pass | E2E（失败状态的设计已由协调方确认） |

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- call 被指派的股票卖出实收按 Q8"暂定"口径（行权价 × 股数 + 权利金 − 费用）断言 +785.35。该口径以后如果改了，这条边界测试要同步改。
- 场景中 ZWLK"无现价"是靠假数据源不配置 ZWLK（= unreachable）实现的，ZWLK 的拉取结果会记为失败。这不影响本需求的数字。
