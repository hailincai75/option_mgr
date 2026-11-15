# FEAT-035 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified
- **后端集成测试:**
  - backend/src/test/java/com/optionmgr/systemtest/Feat035AllAccountsSystemTestBase.java（场景 S、假 CBOE / Finnhub、断言工具）
  - backend/src/test/java/com/optionmgr/systemtest/Feat035AllAccountsAcceptanceSystemTest.java（验收 1–10，11 个，全部通过）
  - backend/src/test/java/com/optionmgr/systemtest/Feat035AllAccountsGuardBoundarySystemTest.java（只读保护、请求头、不按账户的接口、单账户新字段、边界，14 个，全部通过）
  - 修改：Feat042AdhocClosureSystemTest.java 的提醒字段集合加入 `byAccount`（契约对 FEAT-042 的纯新增字段），并断言单账户 `byAccount` 只有本账户一项、个数相同（27 个，全部通过）
- **E2E 测试:** e2e/tests/feat-035-all-accounts-view.spec.ts（验收 1–10 + Q227 补充 + 3 个边界，14 个，全部通过）。`TID` 常量块已按 docs/architecture.md "全部账户（FEAT-035）" 的 testid 核对改正；另改 e2e/tests/feat-033-accounts.spec.ts 一处时序（见运行结果）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests Feat035* --tests Feat042*` | 52 / 52 通过 |
| `gradlew.bat build`（全部后端测试） | BUILD SUCCESSFUL；120 个测试类，1425 个测试，0 失败 / 0 错误 / 0 跳过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 50 个文件，770 个测试全部通过 |
| E2E feat-035（1 worker） | 13 / 13 通过（1.2 分钟） |
| E2E 受影响 spec（3 workers）：feat-033、feat-042、feat-043、feat-007、feat-019 | 第一次：59 / 60 通过（3.7 分钟）。feat-033「切换账户会关闭打开的对话框…」失败：切换账户后的成功提示（`已切换到账户：小账户`，FEAT-033 已有行为）浮在顶部导航上，鼠标悬停时 antd 不关闭它，挡住了 `nav-open-trade` 的点击（超时）。单独跑该 spec 7 / 7 通过，该用例重复 5 次 5 / 5 通过，判定为测试代码的时序问题，不是产品 bug。修正测试：点导航前把鼠标移开并等提示消失（不放宽断言）。修后重跑 feat-033：7 / 7 通过 |

**受影响 E2E spec 的选择（grep 依据）：** 本次前端改动的文件：AccountSwitcher、AdhocClosureAlerts、CashSummaryCard、PositionTable、StockPositionTable / StockPositionsCard、HomePage、PositionsPage、CashPage、App。
- `feat-033-accounts.spec.ts`：AccountSwitcher 的 DOM 改了（`account-switcher` 不再是原来的 Button，文字前缀分"当前账户：" / "当前："，新增 `account-option-all`）。feat-033 断言 `account-current` 文字和 `[data-testid^="account-option-"]` 个数（这个前缀也会选到 `account-option-all`）。grep `account-option-` 命中 18 个 spec，但其余 spec 只是用它切换账户、不断言切换器本身，不选。
- `feat-042-adhoc-closure-alert.spec.ts`、`feat-043-adjust-expiration.spec.ts`：grep `adhoc-closure-alert` 只命中这两个（和 feat-035）。AdhocClosureAlerts 中 `adhoc-closure-alert-adjust` 的渲染分支改了（单账户保留，全部账户换成 `adhoc-closure-alert-switch-<id>`）。
- `feat-007-cash.spec.ts`：CashSummaryCard 重写为可按账户分行的表，`cash-summary-total / -frozen / -available` 改成从同一行数据渲染；feat-007 是这些 id 的主 spec（grep 这些 id 命中 21 个 spec，其余只读数字，不选）。
- `feat-019-portfolio-greeks.spec.ts`：PositionsPage 中组合 Greeks 汇总（`greeks-summary`）外面加了全部账户分支；feat-019 是该区块的主 spec（grep `greeks-summary` 另命中 feat-021 / 023，它们只断言区块不存在，不选）。
- 不选：feat-003（`positions-empty-new`）、feat-008（`dashboard-new-trade`）、feat-011（`stock-positions-new`）——diff 中这些元素只是包进 `!isAllAccounts` 条件，单账户下 DOM 不变。持仓 / 股票表多出的 `账户` 列只在全部账户出现，行上加了 `data-account-id`，不影响旧断言。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 切换器选全部账户 → `当前：全部账户（只读）` + 只读提示；刷新仍是全部账户；切到甲账户 → 只有 A4、A1，无账户列，按钮恢复 | Pass | Pass | 切回单账户不刷新页面 |
| 2 | 首页合计：现金 70,473.05 / 14,500.00 / 55,973.05；+90.00（2 笔）；本月 +60.00（1 笔）；期权 4、冻结 14,500.00、7 天内 1、已过期 1、股票 1；无录入交易 | Pass | Pass | API 另验证首页每个数字 = 两账户之和（Q216） |
| 3 | 资金页每账户一行 + 合计；无入金 / 出金表单；流水占位 | Pass | Pass | API：`cash/summary` 整体 JSON 相等；`cash/ledger` ALL → NOT_SUPPORTED |
| 4 | 持仓期权 4 行顺序（乙 B4、甲 A4、甲 A1、乙 B3）、已过期 3 天、DTE 4、已覆盖 100 股、净额、冻结；持仓 4 个、冻结 14,500.00 | Pass | Pass | API 另验证 ALL 每行与单账户行全字段相同（Q53）。页面文字沿用 FEAT-003：`共 4 个持仓`、`冻结现金合计 14,500.00` |
| 5 | 股票行与各行未实现、合计行、未计入提示 | Pass | Pass | API：`unrealized-summary` 整体 JSON 相等 + 逐项为两账户之和 |
| 6 | 首页 7 天内到期链接 → `?expiry=7` 只有甲 A4；已过期未处理只有乙 B4；冻结与未实现合计不变 | Pass | Pass | |
| 7 | 盈亏汇总合并：QRTV 2 笔 +90.00；按月两行；11 月范围 QRTV 1 笔 +60.00；乙账户 QRTV 1 笔 +30.00 | Pass | Pass | 拆分列为 0 时页面显示 `—`（FEAT-040 原有格式），验收写的是"股票 0.00"：只是显示写法不同，不算失败 |
| 8 | 临时休市提醒合计并按账户分列 + 两个"切换到 … 去调整"；点乙 → 乙账户、定位 B3 | Pass | Pass | 从首页点击：切到乙账户、打开持仓页、B3 `data-highlighted="true"` 且在视口内 |
| 9 | 只读：无写按钮 / 入口；`/trades/new` 选账户 → 表单；后端拒绝 ALL 入金，现金不变 | Pass | Pass | `/stocks/new` 同样显示选择账户 |
| 10 | 未支持页面占位 + 两个账户按钮；Greeks 汇总灰字、每行 Greeks 照常 | Pass | Pass | E2E 另验证占位页没有发出带 ALL 的请求；占位按钮切换后页面正常显示 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 18 个写请求（入金 / 改 / 删流水、开仓、非法请求体、平仓、作废、批量作废、指派、行权、调整到期日、改 / 删交易、股票录入 / 改 / 删、legacy 测试支持）带 ALL → 400 ALL_ACCOUNTS_READ_ONLY，什么都不写（含审计） | Pass | 快照：6 张表行数 + 甲 / 乙 / ALL 的 7 个读接口 + 流水 / 交易 / 股票 / 审计 |
| 写保护先于请求体校验；`all`、`All`、` ALL ` 同样拒绝；同一入金带单账户头成功（对照） | Pass | |
| 9 个不支持的 GET（带查询参数也一样）→ ALL_ACCOUNTS_NOT_SUPPORTED，不读不写 | Pass | 含 `/api/positions/underlying-summary` |
| 7 个接口：`ALL` / `all` / ` ALL ` 结果相同；缺失、空白、`abc`、`ALLX`、`1,2`、`99` 的错误不变 | Pass | |
| 行情刷新、覆盖 mark / 标的价（设置与清除）、行情状态、账户列表 / 新增 / 改名、交易日历、健康检查带 ALL 照常；覆盖 mark 同时改变两个账户同一合约的行 | Pass | API + E2E |
| 单账户响应只多了新字段（行的 accountId / accountName、`accounts[]` 一行、alerts 空） | Pass | |
| FEAT-043 调整到期日响应的 `position` 带账户字段且与持仓行相同；调整后 ALL 提醒只剩甲账户 | Pass | |
| 新增空账户：资金汇总多一行 0.00，其它合计不变 | Pass | API + E2E（在全部账户模式下用账户页新增） |
| 同一标的股票在两个账户（乙转入 50 股、卖 10 股 +100.00）：两行、个数按不同标的 = 1（Q227）、合计相加；盈亏汇总 IXQZ 行与 11 月合并 | Pass | Q227 后复测通过 |
| 同一合约一空一多（丙 BTO 同一 50P）：第 5 行 LONG，合计直接相加、无净额抵消 | Pass | |
| 只有一个账户：ALL 结果与单账户相同；页面无全部账户项，记住的 ALL 回落到默认账户 | Pass | API + E2E |
| 改名立即反映在 ALL 行与资金汇总 | Pass | |
| 临时休市只影响一个账户：ALL message 仍带括号 `（乙账户 1 个）` | Pass | 已由协调方确认接受 |
| 乙账户作废已过期 B4 后 ALL 立即少一行、冻结 10,000.00、已实现 +150.00（3 笔） | Pass | |

## Q227 复测（2026-11-15）
用户决定 Q227：全部账户下首页 `股票持仓 N 个标的`、持仓页股票卡 `共 N 个标的` 的 N 按跨账户**不同标的**计数（后端 `/api/stock-positions` 的 `positionCount` 与 `/api/dashboard` 的 `stockPositions.positionCount`）；股票行仍按账户分行，市值、未实现照常相加。这是需求变更，不是放宽断言。

测试改动：
- `Feat035AllAccountsGuardBoundarySystemTest.sameStockInBothAccounts`：同一标的在两个账户时个数由 2 改为 1（股票卡与首页），并改显示名；另断言乙账户单独仍为 1。
- `Feat035AllAccountsSystemTestBase.assertAllRowsEqualSingleRows`：股票的 `positionCount` 改为核对不同标的数（期权仍为行数）。
- `Feat035AllAccountsAcceptanceSystemTest.ac2_dashboard`：逐项求和检查不再包含 `stockPositions.positionCount`（Q227 例外），改为核对它等于 ALL 股票行的不同标的数。
- 新增 `Feat035AllAccountsAcceptanceSystemTest.ac5_q227DistinctSymbolCount`（验收 5 补充）：乙转入 IXQZ 50 股 → 股票两行（甲 IXQZ 100、乙 IXQZ 50），`positionCount` 1、首页 1、乙单独 1；乙行市值 3,055.00 / 未实现 +55.00；股票合计 9,165.00 / +285.00、`includedCount` 2（按行）。再加 QRTV（甲 10 股、乙 20 股）→ 4 行、2 个标的（决定中的例子）。
- E2E 新增「AC5（Q227 补充）」：全部账户下首页 `股票持仓 1 个标的`、持仓页股票两行（甲账户 IXQZ、乙账户 IXQZ）、`共 1 个标的`；切到乙账户 `共 1 个标的`。

| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests "com.optionmgr.systemtest.Feat035*" --tests "com.optionmgr.account.AllAccountsControllerTest"` + Feat040 / Feat011 系统测试 | 全部通过：FEAT-035 验收 12、只读保护 / 边界 14、AllAccountsControllerTest 14、Feat040 23、Feat011 29 |
| E2E feat-035（重建 boot jar 后，1 worker） | 14 / 14 通过 |
| 前端 | 未改动，未重跑（按协调方要求） |

| 验收 | API | E2E |
|------|-----|-----|
| 5（Q227 补充：两行 IXQZ、共 1 个标的、首页 1 个标的、乙单独 1 个标的） | Pass | Pass |

结论不变：**Verified**。

## 发现的 Bug
- 无。唯一的 E2E 失败是 feat-033 测试代码的时序问题（见上），已修改测试并复测通过

## 疑问 / 已确认
1. 策略页占位与 `underlying-summary` → NOT_SUPPORTED：已确认正确。
2. 首页"股票持仓 N 个标的"的计数：用户已定 Q227（按不同标的跨账户去重），见"Q227 复测"。
3. 只有一个账户受影响时提醒带括号：已确认接受。
4. 显示写法差异（`共 4 个持仓`、拆分列 `—` vs `0.00`）沿用原页面格式，不算失败。
5. 首页"切换到 乙账户 去调整"：实现是切换账户 + 打开持仓页 + 按 FEAT-042 高亮（`data-highlighted="true"`）第一个受影响行，已验证。
