# FEAT-009: 美股交易日历（Finnhub 休市日 + 规则兜底、剩余交易日、到期日选择器）

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-001（开仓录入）、FEAT-003（持仓页）、FEAT-033（多账户），都已 Verified
- **Scope:**
  - [x] Backend: Done（读取 `.env`、拉取并保存休市日、交易日判断、剩余交易日、非交易日查询、开仓校验）
  - [x] Frontend: Done（持仓页"剩余交易日"列、开仓到期日选择器、"休市日数据未更新"提示）

## 背景 / 用户故事
作为一个主要卖期权的交易者，我想在持仓页 DTE 旁看到"还剩几个交易日"，录入开仓时到期日选择器里点不了周末和休市日。休市日要准确，包括临时休市（如 2025-01-09），而且我不想手工维护，所以用 Finnhub 的休市日数据，Finnhub 还没覆盖的年份按 NYSE 规则推算。

## 已定的口径（只引用）
- Q34：DTE 按美东日历天（不变）。Q43：合约不能改，只有开仓表单有到期日选择器。
- Q82：选择器禁用非交易日，不做提示流程。Q120（新）：Finnhub + Jollyday 兜底，key 在 `.env`。Q121：作废。
- Q122：提前收盘日存下来，但算普通交易日，不改任何规则。Q123：只在持仓页和开仓到期日选择器体现。Q124：到期处理按录入的到期日。
- Q125：剩余交易日 = (今天, 到期日] 的交易日数。Q126：后端拒绝非交易日到期的新开仓。Q127：新响应中出现的年份整年替换，不再返回的年份保留。
- Q53：日历全系统一份，与账户无关。

## 规则
1. **读取 key**：后端启动时读取项目根目录 `.env` 里的 `FINNHUB_API_KEY`（按文档命令 `cd backend && gradlew.bat bootRun` 启动也要能读到；操作系统环境变量里有同名变量时以环境变量为准）。没有 `.env` 或没有这个变量时正常启动。key 只放在请求头 `X-Finnhub-Token`，不放进 URL，不写日志，不出现在任何 API 响应或页面上。
2. **拉取**：启动时拉取一次，之后每天一次（Q120）。成功后把返回的每一条（日期、eventName、tradingHour）存入数据库。合并按 Q127：最新响应里出现的年份整年替换；不再返回的旧年份保留上次存的数据。
3. **覆盖年份**：已存数据中出现过的年份 = Finnhub 覆盖的年份，以 Finnhub 为准；其余年份（如 2028+）用 Jollyday NYSE 规则推算。以后某次拉取返回了 2028，2028 自动改用 Finnhub。
4. **交易日**：星期一至星期五，且不是全天休市日。全天休市 = Finnhub 中 `tradingHour` 为空的日期（覆盖年份）或 Jollyday 推算的休市日（其余年份）。`tradingHour` 非空的（如 `09:30-13:00` 提前收盘）是交易日（Q122）。
5. **拉取失败**：没有 key、网络错误、非 200 响应或格式不对 → 不改已存数据，继续使用已存数据和规则；记下失败时间和原因（不含 key）。其它功能不受影响。从未成功过时全部年份按规则推算。
6. **"休市日数据未更新"**：最近一次拉取不成功时，持仓页和开仓表单上方显示灰黄色提示 `休市日数据未更新（上次成功：2026-11-20 08:00 美东）`；从未成功过显示 `休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）`。下一次拉取成功后消失。
7. **剩余交易日**（Q125）：持仓页期权表 DTE 旁加"剩余交易日"列，后端按 (今天美东日期, 到期日] 计算；到期日当天 `0`；已过期 `—`；到期日本身非交易日的旧数据照公式算。DTE、排序、FEAT-010 过滤不变；股票部分不加。
8. **到期日选择器**：开仓表单的到期日选择器禁用后端返回的非交易日；手动输入非交易日提示 `所选日期不是美股交易日`。其它日期选择器不变。
9. **后端校验**（Q126）：新增开仓时到期日不是交易日 → 业务错误 `到期日 2027-03-26 不是美股交易日（Good Friday）`，不保存。名称取 Finnhub eventName 或 Jollyday 名称。已存数据不追溯；平仓、作废、被指派、行权不检查。

## 需要的数据和操作（给 backend）
- `.env` 加载与拉取（规则 1、2、5）；存储：日期、eventName、tradingHour、拉取时间；拉取状态：最近一次尝试时间与结果（成功 / 无 key / 失败）、最近一次成功时间、覆盖年份。
- **持仓数据**：每个期权持仓加"剩余交易日"（整数；已过期为空）。
- **查询非交易日**：输入日期范围（含两端），返回范围内所有非交易日（周末 + 全天休市），每个带日期、原因（周末 / 休市名称）、来源（Finnhub / 规则）。范围上限由 dev 定；起日晚于止日或格式不对 → 业务错误。
- **查询拉取状态**（供规则 6）：上次成功时间、最近一次是否成功；不含 key。
- 开仓校验（规则 9）。
- 测试中 Finnhub 必须可替换为假服务（不用真实 key 跑测试）。

## 页面与交互（给 frontend）
- **持仓页**：规则 7 的列，标题 `剩余交易日`，悬停说明"今天之后到到期日（含）的美股交易日数，不含周末和休市日"；规则 6 的提示。
- **开仓表单**：到期日选择器翻到某月时向后端查询该月非交易日并禁用；查询失败时不禁用，保存时后端校验兜底（规则 9 的错误原样显示）；规则 6 的提示。前端不自己判断周末或假日。

## 验收标准
场景：今天 2026-11-23（星期一）10:00 美东，`.env` 有 key，假 Finnhub 返回 2023–2027 的数据（含 2025-01-09 National Mourning Day、2026-11-26 Thanksgiving Day、2026-11-27 `tradingHour` `09:30-13:00`、2027-03-26 Good Friday）。账户 A 已有（成交时间 2026-11-20 10:00）：STO 1 张 QRTV 2026-11-27 50P 净额 120.00（P1）、STO 1 张 QRTV 2026-12-18 45P 净额 150.00（P2），可用现金 10,770.00。标的均为虚构。

1. **Given** 场景 **When** 打开持仓页 **Then** P1：DTE `4`、剩余交易日 `3`；P2：DTE `25`、剩余交易日 `18`；无"休市日数据未更新"提示。
2. **Given** 场景，今天改为 2026-11-27 **Then** P1 DTE `0`、剩余交易日 `0`；今天改为 2026-11-30 且 P1 未处理 **Then** P1 显示 `已过期 3 天`、剩余交易日 `—`。
3. **Given** 场景 **When** 开仓表单到期日选择器翻到 2026 年 11 月和 2027 年 3 月 **Then** 11-26、03-26 及周末不可选；11-27（提前收盘）、03-25 可选。
4. **Given** 场景 **When** 查询 2025-01-06 至 2025-01-12 的非交易日 **Then** 返回 2025-01-09（National Mourning Day，来源 Finnhub）、01-11、01-12。
5. **Given** 场景（Finnhub 无 2028） **When** 查询 2028-04-10 至 2028-04-16 **Then** 返回 2028-04-14（Good Friday，来源规则）、04-15、04-16。**When** 之后某次拉取返回了 2028 的数据（含 2028-04-14 Good Friday） **Then** 同一查询的来源变为 Finnhub。
6. **Given** 场景 **When** 绕过页面提交开仓 BTO 1 张 IXQZ 2027-03-26 30C、净额 80.00 **Then** 返回 `到期日 2027-03-26 不是美股交易日（Good Friday）`，不保存，可用现金仍为 10,770.00。
7. **Given** `.env` 没有 `FINNHUB_API_KEY`、数据库无休市数据 **When** 用 `gradlew.bat bootRun` 启动并打开持仓页 **Then** 后端正常启动，持仓页显示 `休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）`；选择器仍禁用 2026-11-26；录入、平仓等照常可用。
8. **Given** 2026-11-20 08:00 拉取成功，2026-11-21 拉取时假 Finnhub 返回 500 **When** 打开开仓表单 **Then** 显示 `休市日数据未更新（上次成功：2026-11-20 08:00 美东）`，非交易日仍按已存数据（2025-01-09 仍不可选）；下一次拉取成功后提示消失。
9. **Given** `.env` 中 `FINNHUB_API_KEY=test-key-123` **When** 后端启动并拉取 **Then** 请求头 `X-Finnhub-Token` 为 `test-key-123`，请求 URL 不含该值；后端日志和所有 API 响应中都找不到 `test-key-123`。
10. **Given** FEAT-009 之前已存的持仓 STO 1 张 IXQZ 2026-11-21（星期六）9P，今天 2026-11-16 **When** 打开持仓页并平仓 **Then** DTE `5`、剩余交易日 `4`；平仓不被拒绝。

## 边界情况
- **跨账户**：账户 A、B 看到同样的禁用日期和剩余交易日。
- **今天是周末 / 假日**：今天不计入（规则 7）。
- **Finnhub 返回的数据缩小**（旧年份滚出、某个日期被删除）：按 Q127。
- **免费额度**：每天 1 次远低于 60 次/分钟；不需要重试风暴，失败等下一次。
- **临时休市**：Finnhub 新出现、规则没有的全天休市由 FEAT-042 检测和提醒；本需求只把它当作非交易日。
- **拆股**：与交易日无关。

## Out of Scope
- 临时休市检测、提醒（FEAT-042）；调整到期日（FEAT-043）。
- 按交易日计算 DTE、过滤、排序或记账（Q34）；提前收盘日规则变化（Q122）。
- 成交时间、指派 / 行权日期、股票交易的交易日检查。
- 页面上手动刷新休市日、在页面输入 key。

## Open Questions
- 无（Q120、Q122–Q127 已定）。

## Implementation Notes

### Backend
- **契约**：`docs/api/FEAT-009.md`（实现阶段只新增"配置属性"小节：`optionmgr.finnhub.fetch-enabled` / `base-url` / `api-key` / `read-system-env` / `env-files`；接口、字段、错误码与契约阶段一致）；`FEAT-003.md` 加 `remainingTradingDays`；`api-contract.md` 索引与免账户头列表。
- **新文件**：`calendar/domain/`（`TradingCalendar`、`NonTradingDay`、`NonTradingDayType`、`HolidaySource`、`ExchangeHoliday`、`HolidayRules`、`FetchResult`、`FetchStatus`、`TradingDayMessages`）；`calendar/repository/`（`MarketHolidayEntity/Repository`、`FetchStatusEntity/Repository`）；`calendar/service/`（`JollydayHolidayRules`、`FinnhubClient`、`FinnhubHolidayParser`、`FinnhubKeyResolver`、`FinnhubProperties`、`CalendarConfig`、`FetchAttempt`、`HolidayFetchService`、`HolidayFetchScheduler`、`TradingCalendarService`）；`calendar/web/TradingCalendarController`；`config/SettableClock`；迁移 `V13__trading_calendar.sql`。
- **修改**：`build.gradle.kts`（`de.focus-shift:jollyday-core` / `jollyday-jaxb` 2.22.1）；`ErrorCode.EXPIRATION_NOT_TRADING_DAY`；`OptionTradeService.open`（字段校验之后检查到期日，`legacy` 测试路径不查）；`OptionPositionService` / `OptionPositionController`（`remainingTradingDays`）；`ClockConfig`（`e2e` profile 用 `SettableClock`）；`TestSupportController`（`trading-calendar/fetch`、`clock`，`reset` 恢复时钟）；`application.yml` / `application-e2e.yml` / 测试 `application-test.yml`（`optionmgr.finnhub.*`）。
- **设计决定**：日历每次请求从库建快照（约 60 行）+ Jollyday 按年缓存；只存 Finnhub 数据，规则年份实时推算。拉取的网络调用在事务外，处理（锁状态行、整年替换、记状态）在一个事务里。启动拉取在后台线程（启动不等网络），每天美东 08:00 再拉，失败不重试。key 优先级：属性 `optionmgr.finnhub.api-key` > 环境变量 > `.env`（`.env`、`../.env` 依次找）；key 只进请求头，失败说明只含异常类名 / HTTP 状态。Jollyday 名称用其英文描述（如 `Good Friday`、`Labour day`），NYSE 规则取 observed 日期（如 2026-07-03）。
- **测试**（`com.optionmgr.calendar.*`、`testsupport.TradingCalendarTestSupportE2eTest`）：`TradingCalendarTest`（AC1、2、3、4、5、10，Q122、Q126 文案）、`FetchStatusTest`（AC7、8 文案）、`JollydayHolidayRulesTest`、`FinnhubHolidayParserTest`、`FinnhubKeyResolverTest`、`FinnhubClientTest`（AC9）、`HolidayFetchStartupTest`（启动拉取：有 key 成功且 key 只在请求头 / 日志无 key；无 key → `NO_KEY`，AC7）、`TradingCalendarControllerTest`（AC1–AC10 全部经 HTTP + 本地假 Finnhub，Q127、参数错误、test profile 不自动拉取）。另改 `MigrationFromM1Test`、`MigrationV6HasAuditLogTest`（备份名 V12→V13、失败迁移 V13→V14），测试资源 `db/failing-migration/V13__simulated_failure.sql` 改名 `V14__simulated_failure.sql`。
- **已知限制 / 给 QA**：
  - `test` profile 下没有自动拉取，日历 = Jollyday 规则（Finnhub 无数据）+ 测试注入的数据。系统测试可：`HolidayFetchService.process(new FetchAttempt.Response(200, body))` 直接注入；或 `@SpringBootTest(properties = {"optionmgr.finnhub.base-url=<假服务>", "optionmgr.finnhub.api-key=test-key-123"})` 后调用 `HolidayFetchService.fetchNow()`；或加 `optionmgr.finnhub.fetch-enabled=true` 测启动拉取（后台线程，需轮询 status）。
  - 以下 qa 的系统测试用非交易日到期开仓，Q126 起会得到 422 `EXPIRATION_NOT_TRADING_DAY`（未修改；已运行确认这 3 个用例失败，原因均为该错误码）：`Feat001OpenTradeValidationSystemTest.expiredContractAndWeekendExpiry`（2026-10-17、2026-12-19 星期六）、`Feat012ExpireWorthlessSystemTest.holidayExpiration`（2026-11-26 感恩节）、`Feat015BatchExpirationSystemTest.holidayAndMultiplier`（2026-11-26）。按字面量扫描得出，变量拼出的日期未覆盖。
  - 本地后端测试只跑了受影响的类（含 qa 的 `Feat009TradingCalendarSystemTest` 29 个全部通过、`Feat003` / `Feat033Migration` 通过）+ `gradlew.bat build -x test`；未跑全量。

### Frontend
- **API**：`src/api/tradingCalendar.ts`（`getNonTradingDays(from, to)`、`getTradingCalendarStatus()`、`tradingCalendarQueryKeys`，根 key `["trading-calendar"]`）；类型 `NonTradingDay` / `NonTradingDaysResponse` / `TradingCalendarStatus`、常量 `EXPIRATION_NOT_TRADING_DAY` 在 `src/api/types.ts`；`OptionPosition.remainingTradingDays` 声明为可选（兼容旧测试数据）。`/api/trading-calendar` 加入 `client.ts` 的免账户前缀：不带 `X-Account-Id`，账户未就绪时也能请求；查询用普通 `useQuery`（不按账户分 key）。
- **`components/calendar/`**：`tradingCalendar.ts`（月份范围 `monthRange` / `shiftMonth` / `monthOf` / `monthsToLoad`；`useNonTradingDays(month)` 按月请求当前月及前后各一个月——面板会显示相邻月的日期；`useTradingCalendarStatus()`；文案常量）、`TradingCalendarStaleAlert`（`stale = true` 且有 `warning` 时原样显示后端文字，灰黄色 warning 提示；加载中或 status 请求失败时不显示，页面照常可用）。
- **持仓页**：期权表 DTE 后加"剩余交易日"列（表头悬停显示需求文案），值取后端 `remainingTradingDays`，`null` / 缺失显示 `—`；页面顶部 `TradingCalendarStaleAlert`。DTE、排序、FEAT-010 过滤不变；股票部分不变。
- **录入交易页**：页面顶部 `TradingCalendarStaleAlert`。到期日从原生 `type="date"` 换成 `components/trade/ExpirationDatePicker`（antd DatePicker，格式 `YYYY-MM-DD`，表单值仍是字符串，`data-testid="open-trade-expiration"` 仍在 input 上，可直接输入）：
  - 面板翻到哪个月就请求该月（及前后月）的非交易日，并用 `disabledDate` 禁用；前端不判断周末 / 假日。非交易日格子带 `title` = 后端 `name`。
  - 直接输入完整日期时立即写入表单（包括非交易日——antd 本身会丢弃被禁用的输入，所以组件自己监听 input），非交易日在字段下显示 `所选日期不是美股交易日`，不提交；该月数据晚到时会重新校验。
  - 只限制开仓（方向未选或 BTO / STO）；BTC / STC 不禁用、不报错（旧持仓的非交易日到期日照常平仓）。
  - 当前月查询失败：不禁用任何日期，字段下显示 `休市日查询失败，暂不禁用非交易日，保存时由后端校验`。
  - 后端 422 `EXPIRATION_NOT_TRADING_DAY` 沿用 `applySubmitError`，在 `open-trade-error` 原文显示（`data-error-code` 为该 code），输入保留。
- **新依赖**：`dayjs`（antd 已带的同一版本，改为直接依赖，供 DatePicker 值转换）。
- **data-testid**：`trading-calendar-stale-warning`（持仓页、录入页）、`position-cell-remaining-trading-days`、`open-trade-expiration`（不变，现在是 DatePicker 的 input）、`open-trade-expiration-day-YYYY-MM-DD`（面板日期格子内层 div；被禁用时其父 `td` 有 class `ant-picker-cell-disabled`，非交易日另有 `data-non-trading="true"`）、`open-trade-expiration-calendar-error`。列标题没有 testid（antd 表格会渲染两份表头），E2E 用 `getByRole('columnheader', { name: '剩余交易日' })`。
- **测试**：`src/pages/TradingCalendar.test.tsx`（列与 `—`、表头悬停、stale 提示两种文字 / 不显示 / status 失败、请求不带账户头且按月范围、11 月与 2027-03 面板禁用、输入非交易日报错不提交、交易日提交、平仓不限制、422 原文、查询失败不禁用）、`src/components/calendar/tradingCalendar.test.ts`。
- **已知限制 / 给 QA**：
  - E2E 里 `expiration.fill('YYYY-MM-DD')` 仍可用；打开面板用点击 `open-trade-expiration`，翻月用 `.ant-picker-header-next-btn` / `.ant-picker-header-prev-btn`。面板打开时会盖住下方字段，点击下方控件前先 `fill` 其它输入框或按 Escape。
  - 现有前端测试里开仓到期日用的都是工作日（2026-12-18、2026-11-20、2026-11-09），且它们的 mock 不回答日历请求（= 查询失败、不禁用），不需要改；未全量运行，留给 qa 的 `npm test`。
