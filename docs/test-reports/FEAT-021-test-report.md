# FEAT-021 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified（第 1 轮全部通过，没有 bug）
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat021GroupByUnderlyingSystemTest.java（7 个；后端 N/A，只断言两个已有持仓接口在需求场景下的字段值）
- **E2E 测试:** e2e/tests/feat-021-group-by-underlying.spec.ts（12 个：AC1–9 各 1 个 + 3 个边界）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试，含 bootJar） | BUILD SUCCESSFUL（2 分 23 秒）。112 个测试类、1348 个测试，0 失败、0 错误、0 跳过。FEAT-021 的 7 个全部通过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint（`--max-warnings 0`）、typecheck 通过；Vitest 48 个文件、735 个测试全部通过 |
| 导航宽度检查（疑问 7） | 用临时 spec 在 Desktop Chrome 1280×720 下检查（检查后已删除）：9 个 `nav-*` 都可见、都能点击，没有收进 `…`（`.ant-menu-overflow-item-rest` 为 0）。最后一项 `审计记录` 右边缘约在 x=861，右侧账户切换器之前还有空位。没有 bug |
| E2E：feat-021（端口 18080+ / 15173+） | 12 / 12 通过（47 秒） |
| E2E：受影响 spec，13 个（3 个 worker） | 155 / 155 通过（5.4 分钟），明细见下 |

### 受影响 spec 的选择（grep 依据）
本次前端改动：
1. `App.tsx` 新增导航项 `nav-strategies` 和路由 `/strategies`。
2. `PositionTable.tsx` 把 `DirectionTag`、`SignedMoney`、`DteCell`、`ExpiryPendingTag` 抽到共用的 `PositionCells.tsx`。持仓页仍用默认前缀，testid 应保持不变：`position-cell-direction`、`position-cell-dte`、`position-cell-dte-today`、`position-expiry-pending`、`position-cell-opening-net`、`position-cell-average-net`、`lot-cell-net-amount`。
3. 新页面 `UnderlyingGroupsPage`（`underlying-*`）。

在 `e2e/tests/` 中 grep：
- `position-cell-dte|position-cell-direction|position-expiry-pending`：feat-003（4 处）、004（4）、009（1）、010（19）、012（10）、013（3）、014（2）、015（1）、033（3）、038（4）、041（2）、042（1）、043（2）。逐个确认过，都是对这些单元格文字的真实断言（方向文字、DTE 数字、`已过期 N 天`、`今天到期`、`到期待确认`），按协调方的要求**全部选入，共 13 个**。数量超过 qa.md 一般的 2–5 个，原因是这次重构的正是持仓表里这几个各 spec 都在断言的单元格。
- `nav-strategies|/strategies|underlying-`：除 feat-021 外，命中 feat-014 / 016 / 019 / 041。命中的都是 `underlying-price*` / `underlying-shares`（FEAT-016 / 019 的标的价、FEAT-014 / 041 的持股），与本次改动无关，**未因此选入**（feat-014 / 041 已按上一条选入）。
- 其它只是"页面多了一个导航项"的 spec，没有断言导航项的个数或位置，留给里程碑全量 E2E。

| spec | 通过 / 总数 |
|------|-----------|
| feat-003-positions | 9 / 9 |
| feat-004-close-trade | 16 / 16 |
| feat-009-trading-calendar | 8 / 8 |
| feat-010-expiry-filter | 13 / 13 |
| feat-012-expire-worthless | 11 / 11 |
| feat-013-put-assignment | 21 / 21 |
| feat-014-exercise | 22 / 22 |
| feat-015-batch-expiration | 12 / 12 |
| feat-033-accounts | 7 / 7 |
| feat-038-short-call | 4 / 4 |
| feat-041-call-assignment | 16 / 16 |
| feat-042-adhoc-closure-alert | 9 / 9 |
| feat-043-adjust-expiration | 7 / 7 |

### 与实现对齐时对 spec 的修改（没有放宽断言）
- 契约阶段 spec 里的 testid 和路由是 QA 猜的，现在按 `UnderlyingGroupsPage.tsx` 和 docs/architecture.md 的"按标的（FEAT-021）"改为实际值：`nav-strategies`、`/strategies`、`underlying-page / -title / -hint / -empty / -error / -retry`、`underlying-card[data-symbol]`、`underlying-card-symbol / -summary`、`underlying-options`、`underlying-option-row[data-contract]`、`underlying-option-cell-*`、`underlying-stock`、`underlying-stock-row`、`underlying-stock-cell-{shares|covered-shares|available-shares|total-cost|average-cost}`。改动集中在 spec 顶部的 `S` 常量里。
- 后端报错的用例：原来恢复时用 `page.reload()`，改为点 `underlying-retry`；另外增加断言：错误文字包含 `INTERNAL_ERROR`。这两处都是在原有断言之外加的检查。

### 后端系统测试说明
后端 N/A，没有新接口。系统测试只断言 `GET /api/option-positions` 和 `GET /api/stock-positions` 在需求场景下的字段值，也就是页面直接显示、自己不计算的那些值：
- 期权行：`contractLabel`、`direction`、`contracts`、`openingNetTotal`（字符串）、`expirationDate`、`dte`、`expirationStatus`、`daysPastExpiration`，按接口返回的顺序比对；45P 的两个批次合并为 1 行（`lotCount` 2）。
- 股票行：`shares`、`coveredShares`、`availableShares`（= shares − covered）、`totalCost`、`averageCost`（`"48.0000"` / `"30.0000"` / `"58.0000"`，按 Q88 为 4 位小数）。
- AC4–7 跟随数据变化：BTC 按 FIFO 平仓、STC、卖出股票、作废后删除（重放）、put 被指派后删除、call 被指派。AC8 检查账户隔离（账户 B、空账户 C）。
- 另外检查了到期日当天 `dte` 0 / `EXPIRES_TODAY`；组内行权价按数值排序（`7.5 P` < `40 P` < … < `100 P`）。
- 已有的 FEAT-003 / 011 / 039 / 012 / 013 / 041 / 033 系统测试覆盖了这些行为，但没有一个用到本需求场景的数值，所以另写了这个类。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 账户 A 依次 3 张卡片：`IXQZ` `期权 2 个持仓`；`QRTV` `期权 4 个持仓 · 股票 100 股`；`ZWLK` `股票 50 股` | Pass | Pass | E2E 还检查了导航 `策略`、标题 `按标的`、灰字说明，以及没有 `暂无持仓` |
| 2 | QRTV 期权依次为 40P 多头 1 `−40.65`、45P 空头 2 `+230.35`、55C 空头 1 `+85.35`（DTE 均 25）、2027-01-15 45P `+150.35` DTE 53；股票 `100`、`100`、`0`、`4,800.00`、`48.0000` | Pass | Pass | E2E 还检查了两张表的列头，负数用的是 U+2212 |
| 3 | IXQZ：58P 在上，DTE 红色 `已过期 3 天`；60P `+200.00` DTE `25`；没有股票行。ZWLK：没有期权表，股票 `50`、`0`、`50`、`1,500.00`、`30.0000` | Pass | Pass | 红色用计算后的颜色判断；60P 的 DTE 不是红色 |
| 4 | 不刷新页面：BTC 1 张 45P 60.65 → `1`、`+110.00`；STC 40P → 40P 行消失，标题 `期权 3 个持仓 · 股票 100 股`；卖出 ZWLK 50 股 → ZWLK 卡片消失 | Pass | Pass | 写操作在持仓页平仓对话框和股票录入页完成，再从导航回到策略页；用 window 标记确认没有 reload |
| 5 | 作废 IXQZ 58P → 只剩 60P，标题 `期权 1 个持仓`；删除作废记录 → 恢复验收 3 | Pass | Pass | 在交易列表删除；恢复后 58P 的 DTE 仍是红色 |
| 6 | 12-18 18:00 IXQZ 60P 被指派 → 期权只剩 58P（`已过期 28 天`），股票 `100`、`0`、`100`、`5,800.00`、`58.0000`，标题 `期权 1 个持仓 · 股票 100 股`；删除被指派记录 → 股票行消失、60P 恢复 | Pass | Pass | E2E 同时检查了到期日当天 DTE 显示 `今天到期` |
| 7 | 12-18 18:00 QRTV 55C 被指派 → 55C 行和股票行都消失，标题 `期权 3 个持仓` | Pass | Pass | |
| 8 | 账户 B 只有 `QRTV` `期权 1 个持仓`：45P 空头 1 张 `+115.00`，没有股票行；没有持仓的账户显示 `暂无持仓` | Pass | Pass | 切回账户 A 后数据恢复 |
| 9 | 打开 `显示 Greeks` 后，卡片里仍然没有市值、未实现盈亏、Greeks 列，也没有任何操作按钮；点合约 → 交易列表按合约筛选；点 `ZWLK` → 股票页按 ZWLK 筛选 | N/A（纯前端） | Pass | 卡片里按钮数为 0；交易列表筛选为 QRTV / 2026-12-18 / 55 / Call，`共 1 条`；股票页只有 ZWLK 一行 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 到期日当天 DTE `今天到期`，已过期 28 天（API + E2E AC6） | Pass | |
| 行权价按数值排序（7.5 / 40 / 45 / 55 / 100） | Pass（API） | 前端保持接口返回的顺序，不自己排序 |
| 股票部分卖出：ZWLK 卖出 20 股 → `30`、`0`、`30`、`900.00`、`30.0000`，卡片还在，标题 `股票 30 股` | Pass | |
| 持股千分位：ZWLK 共 1,050 股 → 股票行 `1,050`、`31,500.00`，标题 `股票 1,050 股` | Pass | 实现用 `formatInteger`，与持股列一致 |
| 后端报错（`/api/option-positions` 500）→ 显示 `underlying-error`（含 `INTERNAL_ERROR`），不显示 `暂无持仓`，也不显示卡片；点重试后恢复 | Pass | |
| 导航 9 项在 1280 宽度下不溢出 | Pass | 见运行结果 |

## 发现的 Bug
- 无

## 疑问 / 需求不清楚的地方（已由协调方答复）
1. testid 和路由：已按实现对齐，见上文。
2. 标题 `股票 M 股` 用千分位：实现用 `formatInteger`，与 E2E 的假设一致。
3. 后端报错的状态：显示 `underlying-error` 和重试按钮，需求没有规定文案，E2E 不断言具体文案。
4. "不刷新页面"：确认的理解是在应用内其它页面写，再从导航回到策略页，期间不 reload。
5. AC4 没给 ZWLK 卖出金额，测试用 1,600.00；入金时间没给，统一用 2026-10-01 09:00。这两项都不影响页面显示的值。
