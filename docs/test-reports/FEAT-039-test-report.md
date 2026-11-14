# FEAT-039 测试报告

- **测试日期:** 2026-11-13
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat039CoveredCallSystemTest.java（23 个，全部通过）
- **E2E 测试:** e2e/tests/feat-039-covered-call.spec.ts（9 个，全部通过；相对日期、真实时钟）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 卖出 call 检查（Q77）E1；C1/C2 已覆盖 100 股；200/200/0 | Pass | Pass | 页面被拒后输入保留 |
| 2 | 锁定与 BTC 释放（Q78）：E2、BTC 后 200/100/100、10:30 仍拒、12:00 成功、删除 / 改 BTC → E3 | Pass | Pass | 另测：修改卖出时间到 BTC 之前 → E2 |
| 3 | 补录 / 修改 / 删除重放（Q19）：补录卖出、S1 改 150、删 S1、C2 张数 2、C1 时间 11-01、C1 净额 55 | Pass | Pass | 另测：改 multiplier 也做 E1；只改净额不写覆盖连带变化 |
| 4 | 作废与 call 被指派：可用不变、作废释放、删除作废 → E3；到期日 15:00 卖出 → E2 | Pass | Pass | 另测：已过期未处理的 call 仍占用（Q34） |
| 5 | put 被指派增加持股（wheel）；删除 put 被指派 → E3 | Pass | Pass | |
| 6 | 旧批次不挡操作（Q79）：未覆盖 + 悬停、整批不部分覆盖、新卖 call 成功、改备注仍旧批次、改张数 → E1 | Pass | Pass | |
| 7 | 自动纳入（Q101）：转入 11-04 仍未覆盖；改录 11-01 → 已覆盖 200 股 300/300/0；卖出 E2；删除转入 E3 | Pass | Pass | 新增记录引起的纳入不写审计 |
| 8 | 提示与标签（Q61、Q62）：新提示全文；40 P 冻结 4,000.00；已覆盖 100 股；无"未冻结"；冻结合计 4,000.00 | Pass | Pass | 首页、交易列表也无"未冻结" |
| 9 | multiplier 10、账户隔离 | Pass | Pass | |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 同一时间、更晚录入的买入不算 STO 时的持股 | Pass | 依赖修改不改变录入顺序（entry_seq） |
| 同一时间、更早录入的买入算持股 | Pass | |
| 删除 STO call 不因覆盖被拒、释放股票 | Pass | |
| 旧批次被 call 指派使受检批次不足 → E3「被指派后…」 | Pass | Q75 已通过 |
| 价差不算覆盖（Q102） | Pass | |
| Q7 方向冲突先于 E1 | Pass | |
| 修改股票引起的纳入写审计 sideEffects（field coverage，STO #id 纳入覆盖检查），不改 hasAuditLog | Pass | |
| 修改旧批次张数通过 → 变为受检，审计含自身纳入 | Pass | |
| 一行同时有受检与旧批次：「已覆盖 100 股」「未覆盖 2 张」 | Pass | API + E2E |
| 多头 call / 空头 put 行覆盖字段为 null | Pass | |
| test profile 下 legacy 测试接口 404 | Pass | |
| 被拒时什么都不改（现金、流水、交易、持仓、股票、审计、覆盖状态） | Pass | 每个拒绝都检查 |

## 回归（FEAT-039 让无股票的 STO call 被拒，已修正旧测试的准备数据）
- 系统测试：Feat001（两个）、Feat011、Feat012、Feat013、Feat038、Feat041 + OptionTradeSystemTestBase（新增 `transferInShares`、`okLegacyTrade`）。全部通过。
- E2E：feat-001、feat-011、feat-012、feat-013、feat-038、feat-041 + support/trade.ts（`apiTransferInShares`、`apiLegacyTrade`）；"未冻结"/旧 Q61 提示断言改为新文案与"已覆盖 N 股"。
- 运行结果：
  - `gradlew.bat test --tests` FEAT-039 + 改动的系统测试：237 个全部通过
  - `gradlew.bat build`：BUILD SUCCESSFUL（857 个测试，0 失败）
  - 前端 `npm run lint`、`npm run typecheck`、`npm test`（514 个）：全部通过
  - Playwright（端口 18080 / 15173）：feat-039、038、001、011、012、013、041 共 123 个；首轮 121 通过、2 失败，都与 FEAT-039 无关：
    - feat-001「0DTE」：测试代码在美东 17:00 后运行时用"现在 − 1 小时"作成交时间，超过当天到期的 16:00 截止（FEAT-012 Q83）。已把测试改为当天 00:00，复跑通过。
    - feat-013 AC1：复跑通过（按钮阶段与当前美东时间有关；首轮恰好在 17:00–17:30 之间运行）。未改代码。

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方
- 无。说明：系统测试在 `test` profile 下运行，场景 L 用 JDBC 把正常录入的 call 标为 `LEGACY`（临时转入只用于通过录入检查，随后删除）；E2E 用 `POST /api/test-support/legacy-option-trades`。
