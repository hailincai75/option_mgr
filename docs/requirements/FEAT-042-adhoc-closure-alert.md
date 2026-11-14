# FEAT-042: 检测临时休市并提醒受影响的持仓

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-009（Finnhub 休市日数据与 Jollyday 规则）、FEAT-003（持仓页）、FEAT-008（首页）、FEAT-033（多账户）
- **Scope:**
  - [x] Backend: Done（检测、记录临时休市；按账户统计受影响持仓）
  - [x] Frontend: Done（首页、持仓页提醒；持仓行标签）

## 背景 / 用户故事
2025-01-09 美股临时休市（国家哀悼日），原定该日到期的所有美股期权改为按 2025-01-08 收盘价到期（用户经 ISDA、NYSE 公告核实，Q120 背景）。这种事事先没有规则可推算。作为期权交易者，我想要系统在 Finnhub 数据里出现新的临时休市时，如果我有持仓正好在那天到期，就在首页和持仓页醒目地提醒我，让我去核对并调整到期日（FEAT-043）。

## 已定的口径（只引用）
- Q120：Finnhub 为覆盖年份的权威，规则（Jollyday）兜底；每天拉取。Q127：被之后的拉取删除的临时休市标"已撤销"、不再提醒。Q128：提醒不能关闭。Q129：受影响持仓为 0 时提醒自动消失。Q134：只有 Jollyday 每年固定的假日不算临时休市，一次性休市日算。
- Q124：到期处理按录入的到期日（调整前不变）。Q43 例外：调整到期日见 FEAT-043。
- Q53：提醒按账户；临时休市记录全系统一份。

## 规则
1. **什么是临时休市**（Q134）：某次成功拉取（FEAT-009 规则 2）后，Finnhub 数据中一个**全天休市**日期（`tradingHour` 为空、星期一至五），满足其一：
   - 不是 Jollyday NYSE 的**每年固定**假日（Jollyday 中只适用于单一年份的一次性休市日，如国家哀悼日，不算"规则产生"，仍算临时休市）；
   - 上一次成功拉取时已存数据覆盖该年，但没有这一天。
   第一次成功拉取（之前没有已存数据）只用第一条判断。提前收盘日（`tradingHour` 非空）不算。
2. **记录**：检测到时记录 日期、eventName、检测日期（拉取当天的美东日期）、状态"有效"。同一日期只记一次，以后再拉取到不重复、不改检测日期。被之后的拉取删除时按 Q127 标为"已撤销"。
3. **受影响持仓**：当前账户中，到期日 = 某个"有效"临时休市日、且仍有未平张数的期权持仓（即还没有被平完、作废、被指派、行权）。
4. **提醒**：当前账户有受影响持仓时，**首页和持仓页顶部**显示醒目（红色 / 橙色）提醒，每个临时休市日一行，按日期升序：
   `2025-01-09 临时休市（National Mourning Day），你有 2 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日`
   数字 = 规则 3 的持仓个数。行末链接 `去调整` 打开持仓页（首页上点击时跳转，持仓页上点击时滚动到第一个受影响行）。
5. **持仓行标签**：受影响持仓的到期日旁显示红色标签 `临时休市`，悬停显示 eventName。FEAT-043 在这些行上加"调整到期日"按钮。
6. **何时消失**：受影响持仓个数为 0（全部平仓 / 作废 / 被指派 / 行权 / 经 FEAT-043 调整）时该行提醒自动消失（Q129）；不提供关闭按钮（Q128）。
7. **不改其它行为**：作废 / 被指派 / 行权的按钮和时刻、DTE、剩余交易日照旧按录入的到期日（Q124）；FEAT-009 已把该日当作非交易日（新开仓不能选）。

## 需要的数据和操作（给 backend）
- 每次 FEAT-009 拉取成功后按规则 1 检测，规则 2 记录；拉取失败不检测、已有记录不变。
- **查询提醒**（当前账户）：返回每个有受影响持仓的"有效"临时休市日：日期、eventName、受影响持仓个数。
- **持仓数据**：每个期权持仓加"临时休市到期"标志和 eventName（规则 3、5）。
- 测试中用假 Finnhub 服务和可控的"今天"。

## 页面与交互（给 frontend）
- 首页、持仓页顶部：规则 4 的提醒（只显示后端返回的数据，前端不判断日期）。
- 持仓页：规则 5 的标签。
- 不加新页面、新导航项。

## 验收标准
场景：账户 A 有持仓 P1 = STO 1 张 QRTV 2026-12-04 50P，P2 = BTO 2 张 IXQZ 2026-12-04 9C，P3 = STO 1 张 QRTV 2026-12-18 45P。账户 B 没有 2026-12-04 到期的持仓。假 Finnhub 在 2026-11-30 返回 2023–2027 数据（含 2025-01-09 National Mourning Day，不含 2026-12-04）；2026-12-01 08:00 返回的数据多了 2026-12-04 `National Day of Mourning`（`tradingHour` 为空）。Jollyday 不产生 2025-01-09 和 2026-12-04。标的均为虚构。

1. **Given** 数据库无已存休市数据 **When** 2026-11-30 第一次拉取成功 **Then** 临时休市记录只有 2025-01-09（National Mourning Day，检测日期 2026-11-30）；2026-11-26 Thanksgiving、2026-11-27（提前收盘）不被记录。
2. **Given** 验收 1 之后 **When** 2026-12-01 拉取 **Then** 新增 2026-12-04（检测日期 2026-12-01）；**When** 2026-12-02 再拉取到同样数据 **Then** 不新增，2026-12-04 的检测日期仍为 2026-12-01。
3. **Given** 验收 2 之后、账户 A **When** 打开首页和持仓页 **Then** 两处顶部都显示 `2026-12-04 临时休市（National Day of Mourning），你有 2 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日`；P1、P2 到期日旁有 `临时休市` 标签，P3 没有。
4. **Given** 验收 2 之后 **When** 切换到账户 B **Then** 首页和持仓页都没有提醒。
5. **Given** 验收 3 **When** 在账户 A 对 P2 录入 STC 2 张（平完） **Then** 提醒变为 `……你有 1 个持仓在该日到期……`；再对 P1 BTC 1 张后提醒消失（Q129），期间提醒行没有关闭按钮（Q128）。
6. **Given** 账户 C 有 FEAT-009 之前录入、一直未处理的 STO 1 张 QRTV 2025-01-09 40P，以及 P4 = STO 1 张 IXQZ 2026-12-04 8P **When** 验收 2 之后打开账户 C 首页 **Then** 两行提醒，依次为 2025-01-09（1 个持仓）、2026-12-04（1 个持仓）。
7. **Given** 验收 3 **When** 在首页点 `去调整` **Then** 打开持仓页；P1、P2 的作废按钮、DTE、剩余交易日与没有临时休市时相同（按 2026-12-04）。
8. **Given** 验收 3 **When** 2026-12-02 的拉取中 2026-12-04 被删除 **Then** 该记录状态为"已撤销"（Q127），提醒和标签消失，开仓选择器中 2026-12-04 可选。
9. **Given** 验收 3 **When** 2026-12-02 拉取失败 **Then** 记录不变，提醒照常显示，另有 FEAT-009 的"休市日数据未更新"提示。

## 边界情况
- **Jollyday 本身已包含某个一次性休市**（如 2.22.1 已含 2025-01-09 "National Day of Mourning (death of President Jimmy Carter)"，或以后升级加入新的一次性休市日）：仍算临时休市（Q134），只有每年固定的假日不算。
- **临时休市在周末**：不记录（本来就不是交易日）。
- **临时休市日已经过去很久**（如 2025-01-09）：仍检测、仍提醒，只要还有受影响持仓。
- **同一合约多个批次**：算 1 个持仓。
- **新开仓**：该日已是非交易日，不能再开（Q126），不会新增受影响持仓。
- **删除交易 / 删除作废**导致持仓重新出现：再次计入提醒。

## Out of Scope
- 调整到期日（FEAT-043）；自动调整或自动作废。
- 临时休市列表页、通知（邮件 / 推送）。
- 提前收盘日的提醒（Q122）。

## Open Questions
- 无（Q127–Q129 已定）。

## Implementation Notes

### Backend
- **Q134（Jollyday 一次性特殊日）**：`jollyday` 2.22.1 的 NYSE 日历包含 2025-01-09（`National Day of Mourning (death of President Jimmy Carter)`，只在 2025 年有效）。按 Q134，只有每年固定的 NYSE 假日算"规则产生"：`HolidayRules.recurringClosures`，`JollydayHolidayRules` 以"同一 properties key 在前一年或后一年也出现"判定每年固定（Jollyday 公共 API 不暴露 validFrom / validTo）。所以 2025-01-09 在第一次拉取时被记录。FEAT-009 不变：2025-01-09 仍是非交易日，正常开仓仍被 Q126 拒绝。
- **Jollyday 与 Finnhub 一致性**：固定样本 `backend/src/test/resources/finnhub/market-holiday-us-2023-2027.json`（按 NYSE 公布日历构造，未调用真实 Finnhub）。2023–2027 每年 Jollyday 的全天休市与样本**完全一致**（New Year、MLK、Presidents、Good Friday、Memorial、Juneteenth、Independence（含 observed）、Labor、Thanksgiving、Christmas（含 2027-12-24 observed），以及 2025-01-09），常规假日没有任何不一致，且都判为每年固定；2025-01-09 判为一次性。对这份数据第一次拉取只记录 2025-01-09（不会误报 Juneteenth 或 observed 日期）。
- **新增文件**：`V14__adhoc_closure.sql`；`calendar/domain/AdhocClosure`、`AdhocClosureStatus`、`AdhocClosureDetector`、`AdhocClosureAlerts`；`calendar/repository/AdhocClosureEntity`、`AdhocClosureRepository`；`calendar/service/AdhocClosureService`；`trade/web/AdhocClosureAlertController`。
- **修改**：`HolidayFetchService`（成功拉取在合并前检测，同一事务）；`TradingCalendarController`（`GET /api/trading-calendar/adhoc-closures`）；`OptionPositionService.PositionSummary`（带 ACTIVE 临时休市）与 `OptionPositionController`（`expiresOnAdhocClosure`、`adhocClosureEventName`）。测试资源 `db/failing-migration/V14__simulated_failure.sql` → `V15__simulated_failure.sql`，`MigrationFromM1Test`、`MigrationV6HasAuditLogTest` 的备份名改为 `before-V14` / `before-V15`。
- **设计**：检测逻辑在 domain（纯 Java）；提醒与持仓标签用同一个持仓 summary 计算，个数总是一致；实时计算不存库，所以平仓 / 作废 / 指派 / 行权 / 删除后立即反映（Q129）。契约中的 7 个选择全部按契约实现（撤销后再出现恢复 ACTIVE 且 detectedOn 不变；eventName 跟随最新响应；提前收盘 ↔ 全天休市；年份滚出窗口不撤销；不回填；`revokedOn`；规则 1 字面解释）。
- **测试**：`AdhocClosureDetectorTest`、`AdhocClosureAlertsTest`（domain）、`JollydayHolidayRulesTest`（Q134 分类）、`JollydayFinnhubAgreementTest`、`AdhocClosureControllerTest`（验收 1–9 的后端部分；验收 6 的 2025-01-09 持仓用 `OptionTradeService.open(..., legacy = true)` 录入）、`TradingCalendarTestSupportE2eTest`（e2e 假拉取检测、reset 清空）。
- **QA 系统测试**：`systemtest/Feat042AdhocClosureSystemTest` 目前 27 个中 19 个失败，原因都是同一处：用正常开仓接口录入 2025-01-09 到期的卖出 put，被 Q126 拒绝（422）。未修改 systemtest。它是进程内测试（RANDOM_PORT），可以 `@Autowired OptionTradeService` 调用 `open(accountId, command, true)`（legacy，不查交易日），不需要新的测试支持。E2E（Playwright）只能走 HTTP，而 `POST /api/test-support/legacy-option-trades` 目前只接受 STO CALL：如 E2E 需要 2025-01-09 的持仓，最小改动是让该接口也接受 STO PUT（未实现，等需要时再加）。
- **已知限制**：只检测拉取响应中的日期；"每年固定"按相邻年份判断，若 Jollyday 以后加入只在连续两年有效的特殊日，会被当作每年固定（`JollydayHolidayRulesTest` 会提醒）。

### Frontend
- **API**：`src/api/adhocClosureAlerts.ts`（`getAdhocClosureAlerts`、`adhocClosureAlertQueryKeys`）；`types.ts` 新增 `AdhocClosureAlert` / `AdhocClosureAlertsResponse`，`OptionPosition` 加 `expiresOnAdhocClosure`、`adhocClosureEventName`（可选，兼容旧 fixture）。
- **提醒**：`components/calendar/AdhocClosureAlerts.tsx`，首页（`HomePage`）和持仓页（`PositionsPage`）最顶部；每个日期一行红色 Alert，后端 `message` 原样显示，无关闭按钮（Q128），列表为空时整块不渲染（Q129）。按账户查询（`useAccountQuery`），切换账户即取新账户的数据。
- **去调整**：首页是到 `/positions` 的链接；持仓页是按钮，滚动到后端顺序中第一个 `expiresOnAdhocClosure = true` 且 `expirationDate` = 该行 `date` 的持仓行并高亮（`data-highlighted="true"`，红色底）。若当前"到期"过滤把该行隐藏，先切回"全部"再滚动。FEAT-043 再加调整操作。
- **持仓行标签**：`PositionTable` 到期日旁红色 `临时休市` 标签（`expiresOnAdhocClosure` 为 true 时），悬停 Tooltip 显示 `adhocClosureEventName`；`position-cell-expiration` 文字不变。
- **刷新**：`refreshAfterChange`（所有写操作共用）加入 `adhoc-closure-alerts`；单个 / 批量作废失败后刷新持仓时也一起刷新。
- **data-testid**：`adhoc-closure-alerts`（容器）、`adhoc-closure-alert`（每行，带 `data-date`）、`adhoc-closure-alert-message`、`adhoc-closure-alert-adjust`、`position-adhoc-closure-tag`（带 `data-event-name`）；持仓行高亮属性 `data-highlighted`。
- **测试**：`src/pages/AdhocClosureAlert.test.tsx`（mock 后端，覆盖验收 3–7 的前端部分：原文、无关闭按钮、多行顺序、切换账户、标签与悬停、去调整滚动 / 高亮 / 清过滤、写操作后更新与消失）；`tradeFormSupport.test.ts` 更新失效 key 列表。
- **已知限制**：提醒加载中或请求失败时不显示任何内容（与 FEAT-009"休市日数据未更新"一致，不阻塞页面）；尚未连真实后端联调（后端开发中），接口按契约实现。
