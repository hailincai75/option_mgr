# FEAT-009 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified
- **后端集成测试:**
  - `backend/src/test/java/com/optionmgr/systemtest/Feat009TradingCalendarSystemTest.java`（29 个用例；profile `test` + `e2e`，REST，假 Finnhub = `POST /api/test-support/trading-calendar/fetch`，假时钟 = `PUT /api/test-support/clock`）
  - `backend/src/test/java/com/optionmgr/systemtest/Feat009FinnhubKeySystemTest.java`（2 个用例；AC7 / AC9：真实启动拉取，指向本地假 Finnhub `calendar/FakeFinnhub`，key 来自临时 `.env` 文件（`optionmgr.finnhub.env-files`），`read-system-env=false`；从不访问真实 Finnhub，不读项目 `.env`）
- **E2E 测试:** `e2e/tests/feat-009-trading-calendar.spec.ts`（8 个用例；后端固定时钟 + 浏览器 `page.clock`，见 `e2e/support/clock.ts`）

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | P1 DTE 4 / 剩余 3，P2 DTE 25 / 剩余 18；无提示 | Pass | Pass | E2E 另验列标题悬停说明 |
| 2 | 今天 11-27：P1 0 / 0；今天 11-30：P1 已过期 3 天 / — | Pass | Pass | 另加周六 11-28 |
| 3 | 选择器：11-26、03-26 与周末不可选；11-27、03-25 可选 | Pass（接口） | Pass | 另验：手动输入非交易日提示；BTC 不限制 |
| 4 | 2025-01-06..12 → 01-09 National Mourning Day（FINNHUB）、01-11、01-12 | Pass | Pass（AC8 中 2025-01-09 不可选） | |
| 5 | 2028 先按规则（Good Friday，RULES），拉取 2028 后变 FINNHUB | Pass | — | 纯接口 |
| 6 | 绕过页面 BTO IXQZ 2027-03-26 30C → 422 原文，不保存，可用 10,770.00 | Pass | Pass | E2E：非交易日查询失败时页面不禁用，保存后原样显示后端错误 |
| 7 | 无 key、无数据：正常启动，"从未成功获取"提示，仍禁用 11-26，录入 / 平仓照常 | Pass | Pass | API 两层：真实启动（临时 `.env` 无 key → `NO_KEY`）+ e2e 假拉取 |
| 8 | 11-20 成功、11-21 500 → "上次成功：2026-11-20 08:00 美东"，2025-01-09 仍不可选；下次成功后消失 | Pass | Pass | 状态响应与契约示例逐字段相等 |
| 9 | `.env` key 只在 `X-Finnhub-Token`；URL、日志、所有 API 响应中都没有 | Pass | — | 检查了 status、non-trading-days（含 400）、持仓、现金、health、`/v3/api-docs`、404 响应和全部日志输出 |
| 10 | 已存周六到期持仓：DTE 5、剩余 4；平仓不被拒绝 | Pass | Pass | API 用 9P（JDBC 改到期日）；E2E 用 e2e 专用 legacy 接口建 9C（开仓 put 已无法建周六到期） |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| Q125：今天为周六 / 感恩节、美东日期边界（23:30 / 00:00）、16:00 之后、跨圣诞元旦（17）、规则年份 2028（8） | Pass | API |
| Q125：开仓后到期日被列为临时休市，照公式算（11） | Pass | API |
| Q126：周六 / 周日 / Finnhub 假日 / 规则年份 / 临时休市均拒绝，原因文字同 name | Pass | API |
| Q126 检查顺序：字段校验（含 16:00 截止、到期日早于成交日、BAD_REQUEST）先报；先于现金、覆盖、方向冲突、multiplier | Pass | API |
| Q126 不检查：修改、平仓、作废、批量作废、被指派、行权、删除、legacy 接口 | Pass | API |
| Q127：整年替换（删除的日期恢复为交易日、提前收盘改全天休市）、未返回年份保留、空 data 不替换、坏 atDate 整次拒绝 | Pass | API |
| 规则 3：年份被 Finnhub 覆盖后只认 Finnhub（规则假日 2028-07-04 变交易日） | Pass | API |
| 非交易日接口：周末优先、提前收盘不列出、任意年份（2020 / 2030）、2026 全年 114 天、366 / 367 天、from > to、缺参数 / 格式错误、不需要 X-Account-Id | Pass | API |
| 状态：新库、NO_KEY、HTTP_ERROR（500 / 503）、5 种 INVALID_RESPONSE（数据不变）、夏令时本地时间 | Pass | API |
| 跨账户：同样的剩余交易日与非交易日 | Pass | API |
| 股票持仓不加 remainingTradingDays | Pass | API |

## Q126 引起的旧测试修改（QA 自己的测试）
- 系统测试：`Feat001OpenTradeValidationSystemTest.expiredContractAndWeekendExpiry` 按 Q126 改写（过期合约补录用周五 2026-10-16；两个周六到期改为断言 422 原文）；`Feat012ExpireWorthlessSystemTest.holidayExpiration`、`Feat015BatchExpirationSystemTest.holidayAndMultiplier` 的感恩节到期改为"FEAT-009 之前已存的数据"：开仓到 11-25 后用 JDBC 改到期日（`OptionTradeSystemTestBase.moveExpiration`）。
- E2E：到期日相对真实"今天"计算的用例改为后端固定时钟 + 浏览器时钟（`e2e/support/clock.ts`：`fixNow`、`phaseInstant`；fixture 结束时恢复后端时钟），用固定的交易日：feat-001（0DTE）、feat-003 AC6、feat-004 AC9、feat-008 AC1、feat-010（全部，T = 需求原日期 2026-11-18）、feat-012 AC4 / AC9、feat-013、feat-014、feat-015、feat-041（T = 2026-11-23）、feat-039（T = 2026-11-13）。原先"按当前美东时段分支"的用例（feat-010 AC4、feat-012 AC4、feat-013 AC1/Q95 与 AC4/Q94、feat-014 AC1/Q81、feat-015 AC6、feat-041 AC1/Q95）现在在 10:00 / 16:30 / 17:45 三个时刻各跑一次，三个分支都有覆盖。
- 测试代码问题（已修）：浏览器时钟最初用 `setFixedTime`，antd 的消息提示按 `Date.now()` 计时、冻结时永不消失并挡住导航，改为 `page.clock.install`（从该时刻继续走）；feat-013 / 014 / 041 的 `contractOptions` 在下拉框动画期间可能读到空列表，改为等选项非空再读；feat-009 "手动输入"用例的 `waitForResponse` 注册太晚、错误文字动画离场元素被计入，已修。

## 回归与构建
| 检查 | 结果 |
|------|------|
| `gradlew.bat test --tests` Feat009 / Feat001 校验 / Feat012 / Feat015 | Pass |
| `gradlew.bat test --tests` Feat009FinnhubKeySystemTest | Pass（2） |
| `gradlew.bat build` | Pass（1013 个测试，0 失败；不含之后新增的 Feat009FinnhubKeySystemTest，见上一行） |
| 前端 `npm run lint && npm run typecheck && npm test` | Pass（36 个文件，618 个测试） |
| E2E feat-009 + 修改过的 11 个 spec（001、003、004、008、010、012、013、014、015、039、041） | 195/195 Pass（端口 18080 / 15173）。第一次运行 188/195：7 个失败都是上面所列的测试代码问题（浏览器冻结时钟、下拉框读取时机），修复后失败用例连续 3 次通过，再整体重跑 195/195 |

E2E 选择依据：feat-009 本身；其余 11 个是到期日相对"今天"计算、会被 Q126 拒绝而改为固定时钟的 spec（`grep -n "nyToday\|today()\|addDays(" e2e/tests` 中用作 `expiration` 的）。其它 spec 断言里没有用到本次改动的 testid / 文字，留给里程碑全量。

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- 规则年份中 Good Friday 以外的假日名称取 Jollyday 英文原名（如 `Labour day`），测试只校验前后缀，不校验原文。
