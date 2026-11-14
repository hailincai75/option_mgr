# FEAT-015 测试报告

- **测试日期:** 2026-11-13（E2E 运行时美东约 22:10–22:30）
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat015BatchExpirationSystemTest.java（18 个，全部通过）
- **E2E 测试:** e2e/tests/feat-015-batch-expiration.spec.ts（10 个，全部通过）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 按钮与确认框 | Pass | Pass | API：expirable 行 = 4、Q35 顺序、确认框所需字段。E2E：`批量作废（4）`；全部 / 7 天内 / 空的今天到期都没有按钮；已选 4 / 4 个；XYZ 45 P `2 张` `+240.00` `9,000.00`；QRTV `−60.00` `—`；说明原文 |
| 2 | 全部作废 | Pass | Pass | 作废 4 个、+285.00、−13,200.00；按处理顺序返回、id 递增；16:00 记账；现金 50,355.00 / 4,000.00 / 46,355.00；流水、交易列表、已平仓、IXQZ 100/0/100；无审计；不刷新页面 |
| 3 | 取消勾选后单独被指派 | Pass | Pass | 3 个、+45.00、−4,200.00；`批量作废（1）`；被指派后 41,355.00 / 4,000.00 / 37,355.00；全不选 → 0 / 4、确认不可点 |
| 4 | 一项失败整批不保存 | Pass | Pass | 行旁原文"持仓已变化（当前 2 张），请刷新后重试"（data-error-code POSITION_CHANGED），确认框不关闭，无 EXP、冻结 21,400.00；重开 2 张 +130.00 → 4 个 +335.00 −17,400.00 |
| 5 | 多个失败项全部返回 | Pass | N/A（仅后端） | QRTV 旧的 16:10 STC + XYZ 42 P 张数 2 → 2 个失败项，按处理顺序、带请求下标，原因与单个作废一字不差；空 / 缺 / null / 重复合约 → 400，什么都不保存 |
| 6 | 到期日当天 17:30 前后 | Pass | Pass（仅 17:30 后分支） | API：16:30、17:29:59 每项 EXPIRY_TOO_EARLY；17:45 成功同第 2 条。E2E 在美东 22:xx 运行，只走了"17:30 起"分支；16:00–17:30 分支由 API 测试覆盖 |
| 7 | 不同到期日一起作废 | Pass | Pass | 5 个、+355.00、−17,200.00；XYZ 40 P 记在 EXP+7 16:00、排最后；冻结 0.00；盈亏汇总都归同一个月 |
| 8 | 逐条删除 | Pass | Pass | 删除 XYZ 42 P 作废 → 审计 DELETE、冻结 8,200.00（恢复 4,200.00）、其余 3 条不变、`批量作废（1）`；审计页没有"批量作废" |
| 9 | 按账户隔离 | Pass | Pass | 小账户 XYZ 45 P 仍在、冻结 4,500.00、`批量作废（1）`；默认账户再请求 → "该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废" |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 处理顺序与请求顺序无关；到期日 → 标的 → 行权价数值（9 在 10 前）→ Call 在 Put 前 | Pass | API |
| 混合失败原因（NO_POSITION + EXPIRY_TOO_EARLY）按处理顺序返回；返回规范化后的合约（小写标的、`50.000`） | Pass | API |
| 400：项字段错误带下标、超过 500 项、BAD_REQUEST（枚举 / 日期 / JSON）、校验先于业务检查 | Pass | API |
| 只有 BATCH_EXPIRATION_REJECTED 带 `failures`（400、单个作废 422、缺 X-Account-Id 都没有） | Pass | API |
| 多带的字段（note、tradedAtLocal、netAmount、multiplier、action、顶层 batchId）一律忽略 | Pass | API |
| 已单独作废的合约出现在批量中 → 整批拒绝；补录较晚批次后可再批量作废（Q65） | Pass | API |
| 感恩节到期 + multiplier 1 合约一起作废，各自记在到期日 16:00，冻结 −24.69 | Pass | API |
| 失败后取消勾选出错的行再确认 → 其余 3 个成功 | Pass | E2E |
| 批量接口 500 / 网络失败：确认框显示错误、不关闭、什么都不保存 | Pass | E2E |
| 全部持仓作废后，持仓页显示原有空状态"暂无持仓"（FEAT-010 规则 6），没有按钮 | Pass | E2E（AC7） |

## 运行记录
- `gradlew.bat build`：BUILD SUCCESSFUL，935 个测试，0 失败 / 0 跳过。
- 前端：`npm run lint` 通过；`npm run typecheck` 通过；`npm test` 34 个文件、599 个测试全部通过（包括 ExpireWorthless.test.tsx、client.test.ts，在共享作废代码和 ApiError 重构后第一次运行）。
- E2E（端口 18080 / 15173）：feat-015 10/10、feat-012 11/11、feat-010 13/13，合计 34 个全部通过。
- 受影响的 spec 及 grep 依据：
  - feat-012-expire-worthless：单个作废对话框重构（ExpirePositionModal.tsx 抽出 `directionLabel` / `frozenText` / `SignedMoney`）；grep `expire-direction|expire-frozen|expire-result-frozen-change|expire-opening-net` 命中 feat-010、feat-012。
  - feat-010-expiry-filter：按钮加在过滤区域（PositionsPage.tsx）；grep `positions-expiry-filter|positions-filter-count` 命中 feat-010、feat-015。
  - 没有选其它 spec：ApiError 只新增了 `body` 字段，错误显示文字不变，前端全量单元测试已覆盖。
- 测试代码修正（不是产品问题）：E2E 契约阶段假设的 testid 按 BatchExpireModal.tsx 改了（`batch-expire-row` + data-contract、`-row-checkbox`、`-selected-count`、结果表 `batch-expire-result-row*`、`-result-pnl-total` / `-result-frozen-total`）。AC7 中全部持仓作废后，页面显示 FEAT-010 规则 6 的"暂无持仓"，不是过滤的空提示，断言已按规则 6 改正。显示文字与需求一致，没有因为 UI 改需求断言。

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方
- 已平仓页和交易列表上同一时间的 4 条作废，显示顺序需求没写，测试按无序比较。
