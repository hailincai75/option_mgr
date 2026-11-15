# FEAT-047 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat047AllAccountsStrategyGreeksSystemTestBase.java、Feat047AllAccountsStrategyGreeksAcceptanceSystemTest.java（13 个）、Feat047AllAccountsStrategyGreeksBoundarySystemTest.java（10 个）
- **E2E 测试:** e2e/tests/feat-047-all-accounts-strategy-greeks.spec.ts（12 个）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 卡片顺序与标题（甲 ZZA、乙 ZZA、甲 ZZB、乙 ZZB；无占位、无合计） | Pass | Pass | E2E 用 `underlying-card[data-account-id]`、`underlying-card-heading`、`underlying-card-account` |
| 2 | 卡片摘要四行 + 乙 ZZA 45P 行；切到甲只有甲的两张、标题无账户名 | Pass | Pass | ALL 响应与契约示例逐字相同；ALL 各账户组 = 单账户组（Q53） |
| 3 | 一致性：合计 +4,610.00 / +245.35 / 1 个已到期；ZZA 2 笔 +119.70 | Pass | Pass | 另对 ALL / 甲 / 乙 做卡片之和 = 未实现合计、已实现 = 各账户 pnl-summary 的校验 |
| 4 | 链接：45 P → 交易列表仍全部账户只有 P3；`+39.70` → 切到甲、已平仓只有 H2 | Pass | Pass | Q235 |
| 5 | Greeks 合并行 ZZA / ZZB，无账户列 | Pass | Pass | ALL 响应与契约示例逐字相同；从 ALL 持仓行独立重算一致 |
| 6 | 全组合 ALL / 甲 / 乙 | Pass | Pass | 乙 ZZB 单独 NO_DELTA，ALL 下 OK |
| 7 | 手动标的价 52.00 → ZZA +5,720.00、全组合 +6,920.00；清除恢复 | Pass | Pass | 见疑问 1 |
| 8 | 甲 BTC P1 后卡片与 Greeks 更新（不刷新页面） | Pass | Pass | |
| 9 | 开关关闭无区块无灰字；策略页新灰字；无持仓 `暂无持仓` | Pass | Pass | |
| 10 | 后端：数字、账户字段、不再 NOT_SUPPORTED、空账户不变、只有一个账户 = 该账户、ALL 写被拒 | Pass | Pass | 接受 ALL 的 GET 13 个；`/{id}`、审计仍 NOT_SUPPORTED |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 单账户 NO_DELTA 的标的在 ALL 下合并为 OK（契约注，乙 ZZB） | Pass | API + E2E |
| 某标的在所有账户都只剩已到期待处理期权 → ALL 仍 NO_DELTA，expiredPendingCount 2 | Pass | API |
| 甲、乙都持有无行情 ZZC → ALL 一个 ZZC 组，noUnderlyingPriceCount 按合并后标的计 1 次 | Pass | API；E2E 中 ZZC + ZZD 为 2 个标的 |
| 只有已平仓历史的 账户 + 标的不出卡片；另一账户的卡片已实现不借用 | Pass | API + E2E（乙 ZZD） |
| Q233 A 取整：合并 14558.82 vs 各账户之和 14558.81 | Pass | API |
| 覆盖检查仍只看同一账户：甲有股票，乙 STO call → 422 COVERAGE_INSUFFICIENT | Pass | API |
| 手动 mark（Q170）只影响卡片市值 / 未实现，不影响 Greeks | Pass | API |
| 改名账户 → 卡片 accountName 立即更新 | Pass | API |
| 请求头错误（缺失 / 非整数 / 不存在）不变 | Pass | API |
| 全部账户下策略页、Greeks 区块无写按钮；持仓页无平仓；ALL 平仓被拒 | Pass | E2E |

## 回归与检查（最终一次）
- `gradlew.bat build`：BUILD SUCCESSFUL，1506 个测试，0 失败 / 0 跳过
- 前端 `npm run lint`、`npm run typecheck`：通过；`npm test`：53 个文件、789 个测试全部通过
- E2E：feat-047（12 passed）；受影响 spec feat-035、feat-023、feat-021、feat-019（共 49 passed）
- 受影响 spec 的 grep 依据（在 `e2e/tests/` 中查改动的 testid / 文字）：
  - `greeks-summary-all-accounts`、`暂只支持单个账户` → feat-035
  - `all-accounts-placeholder` → feat-035、feat-046（feat-046 只断言 4 个列表页无占位，与本次改动无关，未选）
  - `underlying-card`、`nav-strategies`、`underlying-hint` → feat-021、feat-023（卡片分组改为 账户 + 标的）
  - `underlying-pnl-realized` → feat-023（链接加了全部账户行为）
  - `greeks-summary-note` → feat-019（`GreeksSummaryCard` 说明文字按账户模式切换）

## 本需求对旧 QA 测试的调整（需求变更，不是放宽断言）
- `Feat023UnderlyingPnlSystemTestBase`：组字段集合加 `accountId`、`accountName`（契约：单账户也返回）
- `Feat035AllAccountsAcceptanceSystemTest`、`Feat035AllAccountsGuardBoundarySystemTest`、`Feat046AllAccountsListsAcceptanceSystemTest`：greeks-summary / underlying-summary 移出"ALL 仍不支持"列表
- `e2e/tests/feat-035-all-accounts-view.spec.ts` AC10：占位只在 /audit；/strategies 无占位；Greeks 开关打开显示汇总区块而不是灰字

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方
1. 验收 7 只写了"期权行的市值 / 未实现不变"。系统测试另外断言了甲 ZZA 卡片股票部分随手动标的价变化：市值 5,140.00、未实现 425.35、合计 465.05（−60.00 + 100 × 52.00；25.35 + 400.00）。这些数字是 QA 推出来的，依据 FEAT-017 / FEAT-023 的规则（股票市值 = 股数 × 显示的标的价，含手动值），契约 docs/api/FEAT-047.md 没有直接给出。后端返回与推导一致，所以没有分歧。
2. 未覆盖"合并后净 Delta 恰好为 0 → 美元敞口 0.00"的边界（场景 G 不好构造；FEAT-019 已测过同一 domain 计算）。

## M5 里程碑全量 E2E

- **日期:** 2026-11-15
- **命令:** `cd e2e && npx playwright test`（默认 3 个 worker，每个 worker 用自己的 e2e 后端 18080+i 和 Vite 15173+i，不使用 8080 / 5173；后端 boot jar 用 FEAT-047 验证时 `gradlew.bat build` 生成的，之后 `backend/src/main` 没有改动）
- **范围:** `e2e/tests/` 下全部 31 个 spec（M4 的 28 个 + FEAT-035、046、047）
- 后端 `gradlew.bat build` 和前端 lint / typecheck / test 在 FEAT-047 验证时已通过，之后没有产品代码改动，本次没有重跑（见上文"回归与检查"）

| 检查 | 结果 | 耗时 |
|------|------|------|
| 全量 E2E `npx playwright test`（第一次） | 435 个测试，434 通过，1 失败（retries = 0） | 14.5 分钟 |
| 重跑 `feat-035-all-accounts-view.spec.ts`（未改代码） | 14 / 14 通过；AC7 单独 `--repeat-each=5` 5 / 5 通过 | 1.3 分钟 + 30 秒 |
| 修测试同步后重跑 `feat-035-all-accounts-view.spec.ts` | 14 / 14 通过 | 1.2 分钟 |

- **失败:** `feat-035-all-accounts-view.spec.ts` AC7（盈亏汇总），在最后一步 `switchTo(乙账户)`：`account-current` 一直是"全部账户（只读）"。失败截图里切换下拉框是打开的，乙账户处于悬停状态，说明点击发生了但没有生效。前一步刚点了 `pnl-filter-clear`，页面还在重新请求、重新渲染。
- **判断：测试时序问题，不是产品 bug。** 依据：同一个 spec 不改代码重跑 14 / 14 通过，这一条单独重复 5 次也全部通过；FEAT-047 验证时这个 spec 同样通过；其他 spec 用同样方式切换账户都通过（3 个 worker 并行时机器负载更高）。
- **修改（只改了 e2e，没有放宽断言）：** `e2e/tests/feat-035-all-accounts-view.spec.ts`
  - `switchTo`：先等 `account-option-<id>` 可见，再点击；
  - AC7：点"清除"后先断言 `pnl-total` 回到 `+90.00`（清除后的全部范围，和本用例开头的值一样），再切换账户。
  原有断言都保留：切换后仍要求 `account-current` = 乙账户、`QRTV 1 +30.00 — +30.00`、合计 `+30.00`。
- **结论:** 435 个测试全部通过（434 个第一次就通过，1 个修了测试时序后重跑通过）。M1–M5 各需求的 spec 都通过。
- **发现的 Bug:** 无。
