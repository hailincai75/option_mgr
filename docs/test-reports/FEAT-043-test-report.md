# FEAT-043 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat043AdjustExpirationSystemTest.java（29 个，全部通过）
- **E2E 测试:** e2e/tests/feat-043-adjust-expiration.spec.ts（7 个，全部通过）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests Feat043AdjustExpirationSystemTest` | 29 / 29 通过 |
| `gradlew.bat build`（全部后端测试） | BUILD SUCCESSFUL（1 分 54 秒）；85 个测试类，1131 个测试，0 失败 / 0 错误 / 0 跳过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 38 个文件，637 个测试全部通过 |
| E2E | 本需求是 M2 最后一个功能，用 M2 里程碑全量 E2E 代替受影响 spec 的单独运行，见下方「M2 里程碑全量 E2E」 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | P1、P2 有"调整到期日"，P3 没有 | Pass | Pass | `expirationAdjustable` 等 5 个新字段（含 null 不省略） |
| 2 | 对话框：`2026-12-04（National Day of Mourning）`、3 条记录、默认 12-03；12-04/05/06 不可选 | Pass | Pass | API：默认 12-03，范围 11-25..12-03；E2E 还验证 11-24、11-26 不可选（翻到上个月） |
| 3 | 调整为 12-03：到期日、DTE 2、剩余交易日 2、冻结 5,000.00、可用现金不变、无标签，提醒剩 1 个 | Pass | Pass | 响应的 `position` 与 GET 持仓一致；页面不刷新即更新 |
| 4 | 交易列表 / 已平仓 / 审计：新合约文字、"历史"、+80.00、1 条 `调整到期日`（3 个 id） | Pass | Pass | 完整审计结构；`objectId` 筛选每条被调整记录都返回该调整 |
| 5 | 再调整 P2 → 提醒消失（Q129） | Pass | Pass | |
| 6 | 绕过页面调整 P3 → `只能调整到期日为临时休市日的合约`，什么都不改 | Pass | — | 只能在 API 层测；对全部可见数据做前后快照比对 |
| 7 | 新到期日 2026-12-05 → `新到期日 2026-12-05 不是美股交易日` | Pass | Pass（页面禁用该日） | 另测 11-26、12-04 |
| 8 | 已作废的 2025-01-09 40P → `合约已作废 / 被指派 / 行权，不能调整到期日`；没有行、没有按钮 | Pass | Pass | |
| 9 | 2025-01-09 8P 调整为 2025-01-08 → 已过期 692 天、作废；作废记在 2025-01-08 16:00 | Pass | Pass | |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 错误码按检查顺序：字段 / 账户 → NOT_FOUND → NOT_ADHOC → ALREADY_ADJUSTED → SETTLED → NOT_TRADING_DAY → OUT_OF_RANGE（两种文案）→ TRADE_AFTER → TARGET_EXISTS | Pass | 构造同时违反两条检查的请求来验证顺序：1 先于 2、2 先于 3、3 先于 6、4 先于 6/7、5 先于 6、6 先于 7、7 先于 9、8 先于 9 |
| Q131 最早允许日（最后成交日 11-25，过去日期）/ 最晚允许日（12-03）；11-24、12-07 被拒 | Pass | 调到 11-25 后持仓显示已过期、待处理，冻结仍在 |
| Q131 不能再调：第二次调整 → NOT_ADHOC_CLOSURE；新日期之后也被记为临时休市 → ALREADY_ADJUSTED，无按钮 | Pass | |
| Q83：新到期日当天 16:00 的记录可以调；16:30 / 16:45 → TRADE_AFTER，报出最早一笔 | Pass | 之后进入到期待确认，17:30 起可作废，作废时间记在新到期日 16:00 |
| Q132 目标合约已有记录（已平完也算）；其它账户的同名合约不挡 | Pass | E2E 验证错误原样显示在对话框中、对话框不关闭 |
| Q133 部分被指派 / 部分行权：仍是持仓、仍带标签，但不可调整 → SETTLED | Pass | |
| Q130 金额不变：交易逐字段（除到期日 / 合约文字 / hasAuditLog）、现金汇总、流水金额与余额、批次不变；流水说明改为新合约文字 | Pass | |
| Q130 空头 call 有股票覆盖：覆盖股数、批次 CHECKED、股票可用股数不变 | Pass | |
| 已全部平仓的合约：`position: null` | Pass | |
| 跨账户（Q53）：只改当前账户 | Pass | |
| 调整后临时休市被撤销（Q127）：已调整记录不回退 | Pass | |
| 调整后删除其中一条：调整审计保留 | Pass | |
| 标的 / 行权价规范化、忽略多余字段、备注去首尾空白（全空白存为 null）、500 字可以 / 501 字被拒 | Pass | |
| 后端 500 时页面：对话框显示错误、不关闭 | Pass | E2E 中拦截请求模拟 |
| 测试支持接口 `legacy-option-trades` 的扩展：BTO call / BTO put 可预置；BTC 被拒（VALIDATION_ERROR，field `action`） | Pass | |

本轮修改的测试代码问题（都不是产品 bug）：
- 系统测试「跨账户」：FEAT-033 建账户会写一条 ACCOUNT 审计，改为只查 `objectType=OPTION_TRADE`。
- 系统测试 legacy BTC 文案：改为契约修正后的 `本功能只支持开仓（BTO / STO），平仓请使用平仓功能`。
- E2E AC1+AC2：12 月视图中看不到 11-24 / 25 / 26，改为先翻到 11 月再检查。这一步同时覆盖了前端修过的"翻月时跳回"问题。

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- 契约检查第 10 步（调整后重放失败：`POSITION_DIRECTION_CONFLICT` / `CASH_INSUFFICIENT` 等）按契约属于防御性检查，正常数据下不会出现，通过 REST 无法构造，由后端 `ExpirationAdjustmentRules` 单元测试覆盖。系统测试未覆盖。

---

## M2 里程碑全量 E2E

- **日期:** 2026-11-14
- **命令:** `cd e2e && npx playwright test`（默认 3 个 worker；每个 worker 用自己的 e2e 后端 18080+i 和 Vite 15173+i，不用 8080 / 5173）
- **范围:** `e2e/tests/` 下全部 22 个 spec（FEAT-001 至 FEAT-015、033、038–043）

| 轮次 | 测试数 | 通过 | 失败 | 耗时 |
|------|-------|------|------|------|
| 全量运行 | 329 | 328 | 1 | 10.5 分钟（Playwright 统计；连启动约 633 秒） |
| 修复后只重跑失败的 spec `feat-043-adjust-expiration.spec.ts` | 7 | 7 | 0 | 35 秒 |

- **唯一的失败** 是 `feat-043` 的 AC1+AC2，原因在测试代码：在 12 月视图里找 11 月的格子。已修复，重跑通过。
- **其余 21 个旧 spec 全部通过**，包括与 FEAT-043 共用 `ExpirationDatePicker` 的 FEAT-001 / FEAT-009 录入页。
- **结论：** M2 全量 E2E 329 / 329 通过（按修复后结果计）。
- 运行结束后 18080+ / 15173+ 端口均已释放，没有残留进程。
