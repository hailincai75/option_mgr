# FEAT-042 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat042AdhocClosureSystemTest.java（27 个，全部通过）
- **E2E 测试:** e2e/tests/feat-042-adhoc-closure-alert.spec.ts（9 个，全部通过）

## 运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（全部后端测试） | BUILD SUCCESSFUL；82 个测试类，1077 个测试，0 失败 / 0 错误 / 0 跳过 |
| 前端 `npm run lint && npm run typecheck && npm test` | lint、typecheck 通过；Vitest 37 个文件，626 个测试全部通过 |
| E2E（3 workers）：feat-042、feat-003、feat-009 | 26 / 26 通过（1.1 分钟） |

**受影响 E2E spec 的选择（grep 依据）：**
- `feat-042-adhoc-closure-alert.spec.ts`：本需求。
- `feat-003-positions.spec.ts`：`grep position-cell-expiration e2e/tests` 只命中该 spec。PositionTable 中这个单元格的 DOM 改了（外层包了 span，旁边加 `临时休市` 标签）。
- `feat-009-trading-calendar.spec.ts`：`grep trading-calendar/fetch e2e/tests` 只命中 feat-009 和 feat-042。只有这两个 spec 注入 Finnhub 数据，所以检测逻辑在 feat-009 的每次拉取中也会运行（例如 2025-01-09 被记录）；它的持仓页、"休市日数据未更新"断言可能受影响。
- 不选：feat-010、feat-008 等。它们的断言没有用到改动过的 testid / 文字；不拉取 Finnhub 数据就不会产生提醒。只是多了一个查询，或失效时同时重查，由前端全量单元测试覆盖。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 第一次拉取只记录 2025-01-09（检测日期 11-30）；11-26、11-27 不记录 | Pass | — | `ac1FirstFetch`；Q134 后 2025-01-09 仍按一次性休市记录 |
| 2 | 12-01 新增 12-04（检测日期 12-01）；12-02 重复拉取不新增、检测日期不变 | Pass | — | `ac2SecondFetchAndRepeat` |
| 3 | 账户 A 首页和持仓页提醒"……2 个持仓……"；P1、P2 有标签，P3 没有 | Pass | Pass | 标签悬停显示 eventName |
| 4 | 账户 B 首页和持仓页都没有提醒 | Pass | Pass | E2E 切回账户 A 后提醒又出现 |
| 5 | STC P2 → 1 个；BTC P1 → 消失；没有关闭按钮 | Pass | Pass | E2E 通过持仓页平仓弹窗操作 |
| 6 | 账户 C 两行，依次 2025-01-09、2026-12-04，各 1 个 | Pass | Pass | 2025-01-09 持仓的录入方式见"疑问" |
| 7 | 去调整打开持仓页；作废按钮、DTE、剩余交易日照旧 | Pass | Pass | 12-01：DTE 3 / 剩余 2 / 不能作废；12-04 17:45：0 今天到期 / 0 / 有作废按钮 |
| 8 | 12-04 被删除 → 已撤销；提醒和标签消失；开仓选择器 12-04 可选 | Pass | Pass | revokedOn 2026-12-02；API 也验证能在 12-04 开仓 |
| 9 | 拉取失败 → 记录不变、提醒照常、另有"休市日数据未更新" | Pass | Pass | 提示文字："休市日数据未更新（上次成功：2026-12-01 08:00 美东）" |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 检测：覆盖年份中之前没有、Jollyday 也产生的日期（感恩节）→ 记录 | Pass | 规则 2 单独生效 |
| 检测：新年份（2028）第一次出现只用规则判断；2028-03-03 记录 | Pass | |
| 检测：observed 假日（2026-07-03、2023-01-02、2027-12-24）不记录 | Pass | |
| 检测：周末全天休市不记录（第一次拉取，以及已覆盖年份中新出现的） | Pass | |
| 检测：提前收盘不记录；原来是提前收盘、后改为全天休市 → 记录 | Pass | 2026-12-24 |
| 检测：失败的拉取（NO_KEY、HTTP 500、INVALID_RESPONSE）不检测；新库上失败也没有记录 | Pass | |
| detectedOn 按美东日期（UTC 03:30 算前一天） | Pass | |
| 再次拉到时 eventName 更新、detectedOn 不变；提醒和标签用新名称 | Pass | |
| Q127：改为提前收盘 → 撤销 | Pass | |
| Q127：撤销后又出现 → 恢复为有效，revokedOn 为 null，detectedOn 仍是 12-01 | Pass | |
| Q127：2025 年滚出拉取窗口 → 仍有效，账户 C 仍有提醒 | Pass | |
| 同一合约两个批次算 1 个持仓；已过期未处理的仍计入 | Pass | |
| 行权、被指派、作废、批量作废后不再计入；删除指派 / 作废 / 平仓后再次计入；股票持仓不计入 | Pass | |
| 账户隔离（A=2、B=0、C 两行，各自计数） | Pass | |
| `GET /api/trading-calendar/adhoc-closures`：status 过滤、按日期排序、字段完全一致、不需要账户头（带了也忽略） | Pass | |
| status 值非法（`active` / `FOO` / `Revoked` / `ALL`）→ 400 BAD_REQUEST，field `status` | Pass | |
| 提醒接口账户错误：ACCOUNT_REQUIRED / BAD_REQUEST / ACCOUNT_NOT_FOUND | Pass | |
| reset 清空临时休市记录；没有记录时持仓字段为 false / null（不省略） | Pass | |
| E2E：在持仓页点"去调整"，滚动到第一个受影响行（前面有 12 行更早到期的） | Pass | |
| E2E：提醒接口返回 500 时，持仓页和首页照常可用，标签照常显示 | Pass | 需求没有规定这时的提示，只验证页面不受影响 |

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- **验收 6 中 2025-01-09 持仓的录入方式（测试做法，需求不变）：** Q134 之后，Jollyday 2.22.1 把 2025-01-09 当作非交易日，Q126 会拒绝正常开仓；e2e 的 legacy 接口又只接受 STO CALL。所以：
  - 系统测试先在 2025-01-10 正常开 STO 1 QRTV 40P，再用 JDBC 把到期日改为 2025-01-09（与 `OptionTradeSystemTestBase.moveExpiration` 做法相同），与需求的 40P 完全一致；
  - E2E 改用 legacy 接口录入 STO 1 QRTV 2025-01-09 **40C** 代替 40P。提醒、标签、计数逻辑与期权类型无关，所以验收意图不变。如果需要在 E2E 中用 put，需要 backend 扩展 legacy 接口。
- 提醒接口出错时页面怎么提示，需求没有规定。目前前端不显示提醒、页面其它部分照常，测试只验证后者。
