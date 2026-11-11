# FEAT-007 测试报告

- **测试日期:** 2026-11-10（首测），2026-11-11（BUG-001 修复后复测）
- **结论:** Verified（复测：13 条验收标准与全部额外边界测试通过；BUG-001 已 Closed）
- **首测结论:** Failed（13 条验收标准全部通过；额外边界测试发现 BUG-001）
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/`
  - `CashSystemTestBase.java`：真实 HTTP 端口（`RANDOM_PORT` + `TestRestTemplate`，不用 MockMvc），内存 H2（test profile），时钟固定为 `2026-11-10T14:45:00Z`（= 美东 09:45），每个测试前后清空流水
  - `Feat007CashAcceptanceSystemTest.java`（20 个用例）：完整场景、Q19 重放、同时刻排序、DST、精度、分页
  - `Feat007CashValidationSystemTest.java`（复测后 44 个用例，含参数化）：字段校验、错误体契约、分页参数、并发出金
- **E2E 测试:** `e2e/tests/feat-007-cash.spec.ts`（27 个用例，Chromium）
  - 隔离方式：每个测试启动一个**全新的后端进程**（`java -jar` boot jar，`SPRING_DATASOURCE_URL=jdbc:h2:mem:e2e…`），测试结束后关闭 → 每个测试都从空库开始，不碰 `backend/data/` 的开发库。若 8080 已被其它后端占用，测试直接报错拒绝运行
  - 前端复用已运行的 Vite dev server（5173）；未运行时 Playwright 会自动 `npm run dev`
  - 前置：`cd backend && gradlew.bat bootJar`（或 `build`）；JDK 路径可用 `E2E_JAVA_HOME` 覆盖

## 复测结果（2026-11-11，BUG-001 修复后）
| 命令 | 结果 |
|------|------|
| `cd backend && gradlew.bat clean build`（JDK 21） | **BUILD SUCCESSFUL**：140 个测试，0 失败（QA 系统测试 20 + 44；dev 测试 76，含新增 `MarketLocalDateTimeParsingTest` 25 个）；boot jar 已重建 |
| `cd e2e && npx playwright test`（用重建的 jar） | **27 passed (5.3m)**，0 失败 |

复测新增的回归用例：`badTimeFormat` 增加 `2025-02-29`、`2027-02-29`（平年）、`2026-11-02T24:00`、`T10:60`、`T10:00:60`、`2026-13-01` → 400 `BAD_REQUEST`；`ac8_newYorkTimeConversion` 增加闰日 `2024-02-29T10:00` → `2024-02-29T15:00:00Z`、月末 `2026-04-30T23:59:59` → `2026-05-01T03:59:59Z`（均允许）。

## 首测运行结果（2026-11-10）
| 命令 | 结果 |
|------|------|
| `cd backend && gradlew.bat build`（JDK 21） | **BUILD FAILED**：109 个测试，107 通过，2 失败（都是 BUG-001：`badTimeFormat` 的 `2026-02-30T10:00`、`2026-04-31T10:00`）。dev 的单元/控制器测试 51 个全部通过 |
| `cd e2e && npx playwright test` | **27 passed (4.2m)**，0 失败 |

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 空账户 | Pass — `ac1_emptyAccount` | Pass — `AC1 空账户…` | E2E 从首页点导航 `nav-cash` 进入；分页器不显示 |
| 2 | 入金 30,000.00 | Pass — `ac2to4_depositWithdrawOverdrawThenExact` | Pass — `AC2+AC3+AC11+AC12…` | 存为 `2026-11-02T15:00:00Z`；201 响应字段、`sourceId`↔`id`、`ledgerEntryId`↔流水 `id`、null 字段不省略均已核对 |
| 3 | 出金 2,500.50 | Pass — 同上 | Pass — 同上 | 流水 `−2,500.50`（U+2212）、余额 `27,499.50`；E2E 用带千分位的 `2,500.50` 输入 |
| 4 | 出金超过可用 | Pass — 同上 | Pass — `AC4+AC12…` | 提示原文 `可用现金不足：需要 27,499.51，可用 27,499.50`；拒绝后 DB 行数不变；再出 27,499.50 → 0.00 |
| 5 | 补录历史出金（Q19） | Pass — `ac5_backdatedWithdrawalReplay`、`ac5_withdrawalBeforeFirstDepositIsRejected` | Pass — 两个 `AC5 …` 用例 | 提示 `…；2026-11-05 10:00 时可用现金将变为 −500.00`；早于首笔入金 → `可用 0.00` |
| 6 | 补录历史入金，余额重算 | Pass — `ac6_backdatedDepositRecomputesBalances` | Pass — `AC6 …` | 流水 `id` 保持录入顺序 |
| 7 | 同一时间按录入顺序 | Pass — `ac7_sameInstantEntryOrder`、`ac7_withdrawalFirstIsRejectedAndNotRetroactivelyAccepted` | Pass — 两个 `AC7 …` 用例 | 另验证同一时刻 4 条交替入/出金的顺序 |
| 8 | 时间换算与默认值 | Pass — `ac8_newYorkTimeConversion` | Pass — 3 个 `AC8 …` 用例 | E2E 浏览器时区设为 Asia/Shanghai、时钟固定 UTC 14:45 → 默认 `2026-11-10T09:45`；另用真实时钟核对；DST/标准时间存储值通过 API 核对 |
| 9 | 字段校验 | Pass — 金额/时间/类型/备注各用例 | Pass — 10 个单项用例 + 500 字符允许 + 后端字段错误显示 | E2E 每项都核对：错误只出现在对应字段下、没有发出 POST、库仍为空 |
| 10 | 金额精度与显示 | Pass — `ac10_exactDecimals` | Pass — 两个 `AC10 …` 用例 | `0.30` 精确；`1,234,567.89` 汇总与流水一致 |
| 11 | 冻结始终 0.00 | Pass — `ac11_frozenAlwaysZero` 及所有 `assertSummary` | Pass — 所有 `expectSummary` 都断言冻结 `0.00`、可用 = 总现金 | 冻结 > 0 的出金限制按需求由 FEAT-001 验收 14 验证，本次不测 |
| 12 | 失败保留输入、成功自动刷新 | —（前端行为） | Pass — `AC4+AC12…`（类型/金额/时间/备注都保留）、`AC2+AC3+AC11+AC12…`（页面未重载，汇总和流水即更新） | |
| 13 | 分页与排序（60 条） | Pass — `ac13_pagination` | Pass — `AC13 …` | 3 页 × 20、60 个不同时间严格倒序、首条 `60.00`、末条 `2026-09-01 10:00` `1.00`；上一页/下一页；新增后回到第 1 页 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| Q19：多个后续出金，报告**第一个**变负的时点 | Pass | 800.00 @11-03 → 报 11-04 `−100.00` 而不是 11-05 |
| Q19：之后的入金不能“救回”中间变负的时点 | Pass | 最终余额会是 0，但 11-05 会变 −500 → 拒绝 |
| 与已有出金同一时刻补录：排在已有记录之后检查 | Pass | `需要 500.00，可用 400.00` |
| 同一分钟不同秒：按秒排序优先于录入顺序 | Pass | |
| 补录入金到最早记录之前（2020-01-01） | Pass | 后面各条余额全部重算 |
| DST 开始当天不存在的 02:30 → `07:30Z`；结束当天重复的 01:30 → `05:30Z` | Pass | 与契约一致 |
| 时间恰好等于当前（09:45 / 09:45:00）允许；09:45:01、09:46 拒绝 | Pass | 入金和出金都测 |
| 周末（周六/周日）和美股假日（元旦、劳动节） | Pass | 入金/出金都允许 |
| 金额 `"30000"`、`"0.5"`、JSON 数字 `0.1` 规范为 scale 2 字符串 | Pass | |
| 金额上限 `9999999999999.99` 允许；`10000000000000.00` 拒绝 | Pass | |
| 金额 `0`、`0.00`、`-0.01`、`0.001`、`100.001`（入金和出金） | Pass | 出金的字段错误优先于现金检查（400 而非 422） |
| 金额 `abc`、`1,000.00`、`12.3.4`（API 直接调用） | Pass | `BAD_REQUEST`，field `amount` |
| 时间带 `Z`/偏移/小数秒、空格分隔、只有日期、乱码 | Pass | `BAD_REQUEST`，field `occurredAtLocal` |
| 时间为不存在的日期 `2026-02-30`、`2026-04-31`、平年 `02-29`、`24:00`、`10:60` 等 | Pass（复测） | 首测 Fail → BUG-001，已修复；闰日 `2024-02-29` 允许 |
| 类型 `FOO`、小写 `deposit`、`BUY_TO_OPEN` | Pass | `BAD_REQUEST`，field `type` |
| 备注：501 字符（ASCII/中文/emoji 251 个）拒绝；500 字符（中文、emoji 250 个）允许；空串/全空白存 null；首尾空格原样保存 | Pass | |
| 多字段同时出错：details 全部列出且按 field 排序 | Pass | |
| JSON 语法错误、空请求体 → `BAD_REQUEST` 且 details `[]`；`text/plain` → 415；错误方法 → 405 | Pass | 错误体字段（code/message/status/path/timestamp/details）逐项核对 |
| 流水分页参数：`page=0`、`size=0/101/-5` → `VALIDATION_ERROR`；`page=abc`、`size=1.5` → `BAD_REQUEST`；超出最后一页 → 空 items | Pass | |
| 并发：余额 1,000.00，10 个并发出金 200.00 | Pass | 恰好 5 个 201 + 5 个 422，无 500，最终 0.00 |
| 冲正：误入金后录同额出金，备注“冲正” | Pass | |
| 前端：后端返回字段错误（浏览器时钟偏快，后端判定时间在未来）→ 显示在时间字段下、保留输入 | Pass | E2E |
| 前端：后端宕机时提交 → 显示错误、保留输入；刷新后汇总/流水显示错误 + 重试；后端恢复后重试成功 | Pass | E2E |

不适用于本需求（无期权交易）：部分平仓、FIFO、到期日当天、指派后股票持仓、手续费。

## 发现的 Bug
- BUG-001（backend, Minor，**Closed**，2026-11-11 复测通过）：`occurredAtLocal` 为不存在的日期（`2026-02-30T10:00`）时没有返回 `400 BAD_REQUEST`，而是保存成 2026-02-28。见 `docs/bugs/BUG-001-invalid-calendar-date-silently-adjusted.md`。前端用原生 datetime 控件，正常操作不会触发。

## 疑问 / 需求不清楚的地方
- 首测时关于“仅 BUG-001 失败是否可 Verified”的疑问已不再需要：BUG-001 已修复并复测通过。
- 金额空串 `""` 走 API 时契约没写是 `VALIDATION_ERROR` 还是 `BAD_REQUEST`；测试只要求 400 且 field 为 `amount`（实际行为符合）。
- 给 backend-dev 的建议（非 bug）：E2E 目前靠“每个测试重启后端”实现空库（每个测试约 +5 秒）。如果以后用例增多，希望提供一个只在 e2e profile 下启用的重置端点（不在 `backend/src/main` 默认启用），由 backend-dev 决定是否实现。
