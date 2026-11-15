# FEAT-046 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified
- **后端集成测试:**
  - `backend/src/test/java/com/optionmgr/systemtest/Feat046AllAccountsListsSystemTestBase.java`（场景 L、查询 / 分页拼接 / 一致性 / 快照工具）
  - `backend/src/test/java/com/optionmgr/systemtest/Feat046AllAccountsListsAcceptanceSystemTest.java`（验收 1–10，12 个测试）
  - `backend/src/test/java/com/optionmgr/systemtest/Feat046AllAccountsListsBoundarySystemTest.java`（分页、校验、请求头、边界，13 个测试）
  - 因需求变更修改：`Feat035AllAccountsAcceptanceSystemTest`（AC3 流水、AC10 兜底列表）、`Feat035AllAccountsGuardBoundarySystemTest`（`unsupportedReads`）。4 个列表不再断言 `ALL_ACCOUNTS_NOT_SUPPORTED`；`/{id}`、审计、`greeks-summary`、`underlying-summary` 的断言保留
- **E2E 测试:**
  - `e2e/tests/feat-046-all-accounts-lists.spec.ts`（验收 1–10 + 3 个边界，13 个测试）
  - 因需求变更修改：`e2e/tests/feat-035-all-accounts-view.spec.ts`。AC3 流水区不再是占位，显示 10 条；AC10 只有 /strategies、/audit 还是占位，/trades、/stocks、/closed 不再是占位

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 交易列表 ALL：共 8 条、顺序（Q229）、账户、净额 / 冻结；无写入口 | Pass | Pass | API 另查 Q53：ALL 每行 = 该账户单独读到的行 |
| 2 | 交易列表筛选：qrtv 7 条；点 #10 合约 2 条；清空后 STO 5 条；切到乙 #10、#7、无账户列 | Pass | Pass | E2E 另测后退 / 前进恢复 |
| 3 | 股票交易 ALL：3 条、展开 #13 批次、IXQZ / 卖出筛选；无写入口 | Pass | Pass | 转入行页面文案是 `总成本 5,880.00`（FEAT-011），需求写 `成本 5,880.00`；只是文案差异，E2E 用包含 `5,880.00` 判断（协调方已确认可以） |
| 4 | 已平仓 ALL：3 笔 +140.00、类型 / 日期筛选 | Pass | Pass | API 另测 Q90 期权专用条件在 ALL 下同样去掉股票行 |
| 5 | 盈亏汇总 = 已平仓合计（规则 6）；点 QRTV / 2026-10 | Pass | Pass | API 交叉核对：ALL / 甲 / 乙 × 6 个日期范围，逐项对比总计、option / stock、每个 bySymbol、每个 byMonth |
| 6 | 深链接：首页本月 → 已平仓；#8 合约 → 交易列表；持仓 IXQZ → 股票交易；全程全部账户 | Pass | Pass | |
| 7 | 资金流水 ALL：12 条、每行账户余额（Q228）、表头 `账户余额`、汇总 68,123.05 / 14,500.00 / 53,623.05；无修改 / 删除 / 在…中修改 | Pass | Pass | 汇总数字是需求更正后的值（原 70,123.05 / 55,623.05 是加法错误，PM 已改需求） |
| 8 | 历史（Q231）：只有 #10 有历史；点后切到乙账户、审计按 #10 筛选 | Pass | Pass | API：乙账户读审计 1 条（备注 → 同价位），甲账户读到 0 条，ALL 仍 NOT_SUPPORTED |
| 9 | 切回甲账户：交易 4 条、按钮恢复；已平仓 1 笔 +60.00；流水 5 条首行 50,263.05 | Pass | Pass | E2E 另查表头恢复为 `余额` |
| 10 | 后端：分页第 2 页 #6 #5 #4（8 条 2 页）；ALL 改 #10 / 删 #13 被拒且数据不变；审计不支持；空账户不变 | Pass | Pass | API 另测 `/{id}`、`greeks-summary`、`underlying-summary` 仍 NOT_SUPPORTED |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 合并分页：4 个列表 × ALL / 甲 / 乙，size 1–3 逐页拼接 = 不分页结果；totalItems / totalPages 正确；超过最后一页返回 `[]` | Pass | API `pagesStitchWithoutFilters` |
| 合并分页 + 筛选（交易 4 组、股票 3 组、已平仓 4 组）；已平仓每页的合计 = 全部行的合计 | Pass | API `pagesStitchWithFilters` |
| 默认 page 1 / size 20；没有结果时 totalPages 0、合计 0.00 | Pass | API |
| Q53 / Q228：4 个列表 ALL 每行（全部字段）= 该账户单独读到的行；相邻两行的余额不连续 | Pass | API `allRowsEqualSingleRows` |
| 单账户响应与写接口响应都带 `accountId` / `accountName`；`GET /{id}` 的结果 = 列表里的同一行 | Pass | API |
| ALL 下参数错误与单账户完全相同（18 种） | Pass | API `validationSameAsSingleAccount` |
| 请求头 `all` / ` ALL ` / 缺失 / `ALLX` / `99` | Pass | API |
| 改名立即生效（4 个列表） | Pass | API |
| 只有一个账户：ALL = 单账户 | Pass | API |
| 单账户修改 / 入金后，ALL 立即显示最新数据 | Pass | API + E2E（在页面上改 #9 备注后切到全部账户，不用刷新） |
| 被指派（乙 #7）：ASG 行、指派买入行、已平仓 ASG 行在 ALL 下照常出现，数字 = 单账户，盈亏汇总一致 | Pass | API |
| 到期作废（乙 #7）：EXP 行、释放冻结的流水行带乙账户 | Pass | API |
| ALL 写请求（开仓、买股票、删交易、改入金）→ READ_ONLY，什么都不写 | Pass | API |
| 地址栏参数不合法（ALL）：`?symbol=$QRTV`、`/closed?type=FOO` → 字段错误，不查询 | Pass | E2E |
| 后端报错（ALL）：流水接口 500 → 流水区显示错误和重试，不是占位 | Pass | E2E（`page.route` 模拟 500） |

## 运行记录
- 相关后端测试：`gradlew.bat test --tests "com.optionmgr.systemtest.Feat046*" --tests "com.optionmgr.systemtest.Feat035*"`，共 51 个，全部通过（Feat046 验收 12 + 边界 13，Feat035 验收 12 + 保护 / 边界 14）
- `gradlew.bat build`：BUILD SUCCESSFUL。123 个测试类、1471 个测试，0 失败、0 跳过
- 前端：`npm run lint` 通过（0 warning），`npm run typecheck` 通过，`npm test` 51 个文件、781 个测试全部通过
- E2E（`npx playwright test` 只跑下面 5 个 spec，用 support/backend.ts 的独立端口和 bootJar，没用 8080 / 5173）：75 个测试全部通过（3.1 分钟）
  | spec | 测试数 | 结果 | 选它的原因（grep 依据） |
  |------|-------|------|------------------------|
  | `feat-046-all-accounts-lists.spec.ts` | 13 | Pass | 本需求；`grep -l "账户余额\|trade-cell-account\|stock-cell-account\|closed-cell-account\|cash-ledger-account" e2e/tests` 只命中它 |
  | `feat-035-all-accounts-view.spec.ts` | 14 | Pass | 唯一断言占位 `all-accounts-placeholder*` / `cash-ledger-all-accounts*` 和 `account-option-all` 的旧 spec（`grep -l "all-accounts-placeholder\|cash-ledger-all-accounts\|account-option-all"`） |
  | `feat-005-edit-delete-audit.spec.ts` | 12 | Pass | "历史"改由 `HistoryLink` 渲染（testid 没变）；它断言 `trade-history` / `cash-ledger-history` 的跳转，并断言 `cash-ledger-trades-link`、`trade-edit` / `trade-delete`（这些在全部账户下改为条件渲染） |
  | `feat-002-trade-list.spec.ts` | 19 | Pass | 断言 `trade-list-new` / `trade-list-empty-new`，交易列表页和 `TradeTable` 为全部账户模式加了条件渲染和账户列 |
  | `feat-011-stock-trades.spec.ts` | 17 | Pass | 断言 `stock-list-new` / `stock-history` / `stock-edit` / `stock-delete`，股票交易页和 `StockTradeTable` 同样被改动 |
- 没有选的：`grep -l "trade-history\|stock-history\|closed-history\|cash-ledger-history"` 还命中 feat-006/012/013/014/040/041/043，`grep -l "trade-list-new\|...\|cash-ledger-stocks-link"` 还命中 feat-012/013/040。这些 spec 只在单账户下使用这些 testid，单账户行为没变，而且每类已有一个代表 spec 跑过，所以留给里程碑的全量 E2E

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方
1. 验收 7 / 场景 L 的全部账户汇总原写 `70,123.05 / 55,623.05`，是加法错误，PM 已在需求中更正为 `68,123.05 / 14,500.00 / 53,623.05`。测试按更正后的值断言并通过。
2. 验收 2 已按澄清后的"清空筛选后，方向只选 STO"执行。
3. 验收 3 的转入行文案是 `总成本 5,880.00`，与需求写的 `成本 5,880.00` 不同，只是文案差异（见验收 3 备注）。

## 需要 PM 做的
- 需求状态已改为 `Verified`。请 PM 把 `docs/backlog.md` 中 FEAT-046 一行的状态从 `Implemented` 改为 `Verified`（QA 不改 backlog）。
