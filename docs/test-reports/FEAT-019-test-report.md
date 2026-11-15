# FEAT-019 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified（第 1 轮全部通过，没有 bug）
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat019PortfolioGreeksAcceptanceSystemTest.java（10 个）、Feat019PortfolioGreeksBoundarySystemTest.java（14 个）、公共基类 Feat019PortfolioGreeksSystemTestBase.java（继承 FEAT-018 / 017 / 016 的 QA 基类：用假 CBOE + tick + 可设时钟，不访问真实 CBOE / Finnhub，使用随机端口）
- **E2E 测试:** e2e/tests/feat-019-portfolio-greeks.spec.ts（10 个）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试，含 bootJar） | BUILD SUCCESSFUL（2 分 55 秒）。111 个测试类、1341 个测试，0 失败、0 错误、0 跳过。FEAT-019 的 24 个全部通过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 47 个文件、722 个测试全部通过 |
| E2E：本需求 + 受影响 spec（3 个 worker，端口 18080+ / 15173+） | feat-019 10 / 10、feat-018 10 / 10，共 20 / 20 通过 |
| M3 里程碑全量 E2E | 见下方「M3 里程碑全量 E2E」：371 个测试中 370 个通过，1 个是测试代码时序问题，修复后重跑通过 |

### 受影响 spec 的选择（grep 依据）
本次的前端改动：持仓页打开 `显示 Greeks` 后，在期权持仓表上方新增 `greeks-summary*` 区块，里面有一个带 `thead` 的表格和 3 个 `?` 说明（`HelpIcon` 从 FEAT-018 的列头抽成了共用组件，testid 没变）；另外几个写操作对话框和行情刷新后，多让汇总重新查询一次（只加了 `invalidateQueries`，没有改文字或 testid）。在 `e2e/tests/` 中 grep：
- `greeks-summary`、`按标的 Greeks 汇总`、`greeks-header`、`positions-greeks-toggle`、`greeks-(total|summary)`：除了 feat-019，只有 `feat-018-greeks.spec.ts` 命中，**选入**。它有两处相关：一是 `getByTestId(/greeks-(total|summary)/)` 断言"没有 Greeks 合计"；二是 `headers()` 会读打开开关后页面上所有的 `thead th`。
- feat-005 被 `summary)` 这个片段误命中（它断言的是审计的 `summary` 字段），与本需求无关，未选。
- 其余持仓页 spec 只断言自己的 testid，开关默认关闭时页面也没有变化，所以不选，留给全量 E2E（本次正好接着跑了）。

### 修改既有 E2E（测试代码问题，没有放宽断言）
- `feat-018-greeks.spec.ts`（AC1–4）：原来的 `getByTestId(/greeks-(total|summary)/)` 计数为 0，表示 FEAT-018 阶段"页面上没有任何 Greeks 合计"（Q165 规定合计放到 FEAT-019 做）。FEAT-019 按需求加上了 `greeks-summary` 区块，这条断言就不再适用。现在改为检查两点：持仓表里没有 `greeks-total*`；FEAT-017 的合计区（`unrealized-summary-totals`）里没有任何 Greeks 的 testid。改后仍然覆盖"持仓表和未实现盈亏合计里不混入 Greeks 合计"。
- `feat-043-adjust-expiration.spec.ts`（AC1+AC2）：见下方里程碑一节。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 账户 A QRTV：+87.21 / 0.00 / +87.21 / 52.40 / +4,569.80 / +4.48 / −3.24 / +5.89，行下 `1 个持仓无报价，未计入` | Pass | Pass | 计数 3 行：2 行计入，1 行无报价。组内的值等于持仓接口里各 OK 行持仓值相加（Q181） |
| 2 | IXQZ：−18.35 / +100.00 / +81.65 / 61.10 / +4,988.82（4988.815 按 HALF_UP）/ −3.13 / +2.47 / −5.03，无提示 | Pass | Pass | |
| 3 | 全组合 +9,558.62 / −0.77 / +0.86，净 Delta / Gamma 为空；下方 `1 个持仓无报价，未计入` | Pass | Pass | API：整个响应与契约里的示例 JSON **完全相等**，`portfolio` 不含跨标的的 Delta / Gamma 字段。E2E：合计行 9 格依次为 `全组合, '', '', '', '', +9,558.62, '', −0.77, +0.86` |
| 4 | 账户 B 只有 QRTV：+90.63 / +100.00 / +190.63 / +9,989.01 / −15.36 / +13.68 / −21.33；全组合相同，无提示 | Pass | Pass | API 响应与契约示例完全相等；账户 A 的值不受影响 |
| 5 | 手动标的价 52.00 → A 为 +4,534.92（`按手动标的价`），全组合 +9,523.74；B 为 +9,912.76；10:15 拉取失败后不变；清除后恢复 | Pass | Pass | E2E 通过页面上的"设置 / 清除标的价"操作，汇总随之更新，不需要刷新页面 |
| 6 | P2 的 gamma、vega 为 null → Gamma −5.12、Vega −7.11，行下 `Gamma、Vega：1 个持仓缺值，未计入`；全组合 Vega −12.14 | Pass | Pass | 全组合下方依次显示 `1 个持仓无报价，未计入` 和 `Vega：1 个持仓缺值，未计入`（按契约，已确认） |
| 7 | 加上 P6（已过期待处理）、P7（今天到期）→ +132.21 / +6,927.80 / −7.52 / +4.76 / +4.39，`1 个持仓无报价、1 个持仓已到期待处理，未计入`；全组合 +11,916.62 / +7.23 / −0.64 | Pass | Pass | P7 的行值为 45.00 / −12.00 / 8.00 / −1.50 |
| 8 | 行情库为空、账户 C 只持 IXQZ 100 股 → 期权列 `—`、+100.00、`无标的价`；全组合 0.00 ×3 + `1 个标的无标的价，美元敞口未计入`；手动 60.00 → +6,000.00 | Pass | Pass | API 响应与契约示例完全相等。E2E 在股票行上设置手动价 |
| 9 | STC 2 张 P2 → +30.21 / +1,583.00 / −5.12 / +4.56 / −7.11；全组合 +6,571.82 / +7.03 / −12.14；再 BTC P3 并卖出全部 IXQZ → IXQZ 行消失 | Pass | Pass | API 另外验证了中间状态：只剩股票时 IXQZ 为 `— / +100.00 / +100.00 / +6,110.00`；账户 B 不受影响 |
| 10 | 开关关闭时没有区块；悬停 `Delta 美元敞口` 的 `?` 显示白话解释；到期过滤选"今天到期"时汇总与 AC1–3 相同 | Pass（API 侧：带 `expiry=` 参数时结果不变） | Pass | E2E 还检查了：列头顺序、上方灰字说明、区块位于期权表上方、净 Delta / 股票 Delta 的 `?`、关闭开关后区块消失 |

每次读取汇总时，API 测试都会做以下检查：字段集合与契约完全一致；数值都是 scale 2 的字符串，不带 `+`，没有 `-0.00`；`groups` 按 symbol 排序；`exposureStatus` 的判定顺序为 NO_DELTA → NO_UNDERLYING_PRICE → OK；敞口 = 净 Delta × 标的价，按 HALF_UP 取整；三个 Label 用契约的拼法从计数独立拼出来比对；全组合 = 各组之和。另外用 `/api/option-positions` 和 `/api/stock-positions` 独立重算整个响应（行持仓值求和、持股数、标的价和 manual 标志），与接口返回值比对。

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 错误：无账户 400 `ACCOUNT_REQUIRED`、非整数 400 `BAD_REQUEST`、不存在 404 `ACCOUNT_NOT_FOUND`、POST / PUT / DELETE 405 | Pass | API |
| 没有持仓的账户、全新数据库 → `groups []`，全组合 0.00 ×3，Label 都为 null | Pass | API |
| 只剩已到期待处理期权、没有股票 → NO_DELTA，不计入"无标的价"个数；转入股票后净 Delta = 股票 Delta，敞口照常算；作废处理后不再有未计入提示 | Pass | API |
| 指数 SPX：无报价且无标的价 → NO_DELTA（不是 NO_UNDERLYING_PRICE）；设了手动 mark 或手动标的价也仍是 NO_DELTA，没有手动标注 | Pass | API |
| 不同的缺值个数：`Delta、Gamma：1 个持仓缺值；Vega：2 个持仓缺值，未计入`；所有行 Vega 都缺 → 组 Vega 为 null；Delta 缺值影响敞口（2986.80）；全组合的提示只含 Delta / Vega | Pass | API |
| 唯一的期权行缺 Delta、又没有股票 → NO_DELTA，Theta 等照常 | Pass | API |
| 净 Delta 为 0（100 股 + 卖出 delta 为 1 的 call）→ 0.00 / 0.00，没有负零 | Pass | API |
| 负敞口 HALF_UP：−1121.185 → −1121.19；手动标的价 61.105（3 位小数）→ −1121.28，标的价原样显示 | Pass | API |
| Q181：先对行值取整再相加（两行各 0.005 → 0.01，组合计 0.02），敞口 1.048 → 1.05 | Pass | API |
| Q170 手动 mark 不影响汇总（设置和清除前后完全相同） | Pass | API |
| 无标的价的股票标的（AAQZ）排在第一位，计入 `1 个标的无标的价，美元敞口未计入`，全组合敞口不变 | Pass | API |
| put 被指派生成的股票自动计入股票 Delta | Pass | API |
| FIFO：同一合约开仓两次，BTC 整笔平掉最早一笔后按剩余张数计算（90.63 → 60.42） | Pass | API |
| 周末（周六）不拉取，汇总与周一相同，`asOfDate` 为周六 | Pass | API |
| 后端汇总接口 500 → 区块显示错误和"重新加载汇总"按钮，持仓表照常显示；恢复后点重试显示汇总 | Pass | E2E |

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方
- `missingLabel` 的拼法：契约写的是"缺值个数相同的 Greek 用 `、` 合并为一段"，但没说个数相同、位置**不相邻**的 Greek 怎么处理。例如 Delta 1、Gamma 2、Theta 1，是拼成 `Delta、Theta：1 …；Gamma：2 …`，还是按顺序拆成三段？本次测试数据里没有出现这种情况，测试的公共方法按"合并为一段、段的顺序按首次出现"来实现。以后如果出现这种数据，需要 backend-dev 在契约里写明。

## M3 里程碑全量 E2E

- **日期:** 2026-11-14
- **命令:** `cd e2e && npx playwright test`（默认 3 个 worker，每个 worker 用自己的 e2e 后端 18080+i 和 Vite 15173+i，不使用 8080 / 5173）
- **范围:** `e2e/tests/` 下全部 26 个 spec（FEAT-001 至 FEAT-019、033、038–043）

| 轮次 | 测试数 | 通过 | 失败 | 耗时 |
|------|-------|------|------|------|
| 全量运行 | 371 | 370 | 1 | 12.5 分钟 |
| 单独重跑失败的 spec `feat-043-adjust-expiration.spec.ts`（修改前） | 7 | 7 | 0 | 51 秒 |
| 修复后只重跑 `feat-043-adjust-expiration.spec.ts` | 7 | 7 | 0 | 38 秒 |

- **唯一的失败**：`feat-043` 的 AC1+AC2。点击日期选择器的"上个月"后，标题仍是 `Dec2026`。单独运行这个 spec 时通过，判断是**测试代码的时序问题**：3 个 worker 同时跑、负载较高时，下拉框还在打开动画中，第一次点击被丢掉了。不是产品问题。
- **修复**（只改测试代码）：只有在标题仍是 12 月时才再点一次，并用 `toPass` 重试，直到标题变为 11 月。这样最多回到 11 月，不会翻过头到 10 月，后续断言（11-25 可选，11-24、11-26 不可选）都不变，没有放宽。
- **其余 25 个 spec 全部通过**，包括 M3 的 feat-016 / 017 / 018 / 019。
