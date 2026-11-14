# FEAT-043: 临时休市时调整合约到期日（Q43 的例外）

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-042（临时休市记录与提醒）、FEAT-009（交易日判断）、FEAT-005（审计）、FEAT-039（覆盖检查）、FEAT-012 / 013 / 041 / 014（作废、被指派、行权）
- **Scope:**
  - [x] Backend: Done（调整操作、检查、审计）
  - [x] Frontend: Done（持仓行按钮、调整对话框）

## 背景 / 用户故事
2025-01-09 临时休市时，原定该日到期的期权改为 2025-01-08 到期（Q120 背景）。合约到期日按 Q43 不能修改，只能删掉全部记录再重录，很麻烦。作为期权交易者，当 FEAT-042 提醒我有持仓在临时休市日到期时，我想一次把这个合约的到期日改成实际到期的交易日，记录全部跟着改，并留下审计。

## 已定的口径（只引用）
- Q43 例外（用户决定）：只有到期日是 FEAT-042 检测到的"有效"临时休市日、尚未结束的合约可以调整；新日期必须是交易日；该账户该合约的全部开仓、平仓记录一起改；写审计；新日期由用户填，默认前一个交易日。
- Q12、Q44：审计记录与审计页；`has_audit_log`。Q19：按 (时间, 录入顺序) 重放。Q83：成交时间 ≤ 到期日美东 16:00。Q63：作废记在到期日 16:00。
- Q125：剩余交易日；Q126：到期日必须是交易日。Q129：受影响持仓为 0 时提醒消失。Q53：按账户。
- Q130：金额不变，仍完整重放（含覆盖、Q83），失败即拒绝。Q131：新日期早于当前到期日、不早于最后一条记录的成交日，可以是过去；每条记录成交时间 ≤ 新到期日 16:00；调整后不能再调。Q132：目标合约已有记录则拒绝。Q133：有任何作废 / 被指派 / 行权记录则拒绝。

## 规则
1. **入口**：持仓页上带 `临时休市` 标签的期权持仓行（FEAT-042 规则 5）加按钮 `调整到期日`；其它行没有。FEAT-042 提醒的 `去调整` 指向这些行。
2. **对话框**：显示合约、当前到期日和休市名称（如 `2026-12-04（National Day of Mourning）`）、将被修改的记录条数（开仓 + 平仓）；`新到期日` 选择器默认 = 临时休市日的前一个交易日，非交易日禁用（FEAT-009）；可选备注（≤ 500 字符，写进审计）；说明文字 `该合约在本账户的 N 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录`。
3. **检查**（任一不满足即拒绝、什么都不改）：
   1. 当前到期日是"有效"临时休市日，否则 `只能调整到期日为临时休市日的合约`；
   2. 该账户该合约没有作废 / 被指派 / 行权记录，否则 `合约已作废 / 被指派 / 行权，不能调整到期日`（含部分被指派 / 行权，Q133）；
   3. 新到期日是交易日，否则 `新到期日 2026-12-05 不是美股交易日`；
   4. 新到期日早于当前到期日，且不早于该合约最后一条记录的成交日期；可以是过去的日期（Q131）；
   5. 该合约每条记录的成交时间 ≤ 新到期日美东 16:00（Q131、Q83）；
   6. 该账户中"标的 + 新到期日 + 行权价 + C/P"没有任何记录（Q132），否则 `该账户已有合约 QRTV 2026-12-03 50P 的记录，不能合并`；
   7. 修改后按 Q19 重放（含 FEAT-039 覆盖检查）全部通过（Q130）。
4. **结果**：该账户该合约的全部开仓、平仓记录的到期日改为新日期，其它字段（时间、张数、净额、批次、备注）不变；现金、冻结、覆盖股数、已实现盈亏的金额都不变。持仓页显示新到期日、DTE、剩余交易日，标签和按钮消失；FEAT-042 提醒的个数相应减少。
5. **审计**：每次调整写一条审计记录，类型 `调整到期日`：账户、合约（标的、行权价、C/P）、原到期日 → 新到期日、被修改的记录 ID、备注；被修改的记录置 `has_audit_log`，在交易列表上出现"历史"入口。不能撤销；调整后到期日已是交易日，不能再次调整（选错只能删除后重录，见 Q131）。
6. **之后的到期处理**：一律按新到期日（16:00 截止、17:30 作废、作废记在新到期日 16:00）。

## 需要的数据和操作（给 backend）
- **调整到期日**（当前账户）：输入 合约（标的、当前到期日、行权价、C/P）、新到期日、可选备注；按规则 3 检查，一个事务完成规则 4、5。返回被修改的记录 ID 和新的持仓数据。
- **持仓数据**：受影响行可调整的标志（规则 1、3.1、3.2）、默认新日期（前一个交易日）、将被修改的记录条数。
- **审计**：新增类型 `调整到期日`，审计页查询能返回它。

## 页面与交互（给 frontend）
- 持仓页：规则 1 的按钮、规则 2 的对话框；错误原样显示在对话框里、不关闭；成功后关闭，持仓页、提醒、交易列表、已平仓无需手动刷新即更新。
- 审计页：显示 `调整到期日` 记录（原 → 新到期日、记录 ID、备注）。

## 验收标准
场景：今天 2026-12-01 10:00 美东，2026-12-04 已被 FEAT-042 记为临时休市（National Day of Mourning）。账户 A：
P1 = QRTV 2026-12-04 50P：STO 1 张 2026-11-20 10:00 净额 120.00（批次 1）、STO 1 张 2026-11-24 10:00 净额 100.00（批次 2）、BTC 1 张 2026-11-25 10:00 净额 40.00（平批次 1，已实现 +80.00）；当前空头 1 张，冻结 5,000.00。
P2 = BTO 2 张 IXQZ 2026-12-04 9C 净额 60.00。P3 = STO 1 张 QRTV 2026-12-18 45P。标的均为虚构。

1. **Given** 场景 **When** 打开持仓页 **Then** P1、P2 有 `调整到期日` 按钮，P3 没有。
2. **Given** 场景 **When** 在 P1 点 `调整到期日` **Then** 对话框显示 `2026-12-04（National Day of Mourning）`、`3 条开仓 / 平仓记录`，新到期日默认 2026-12-03；12-04、12-05、12-06 不可选。
3. **Given** 验收 2 **When** 确认 2026-12-03 **Then** P1 到期日 2026-12-03、DTE `2`、剩余交易日 `2`、冻结仍 5,000.00，可用现金不变；P1 无 `临时休市` 标签；FEAT-042 提醒变为 `……你有 1 个持仓在该日到期……`。
4. **Given** 验收 3 **When** 查看交易列表、已平仓页、审计页 **Then** 3 条记录的合约都显示 `QRTV 2026-12-03 50P` 且有"历史"入口；已平仓页该 BTC 行已实现盈亏仍为 +80.00；审计页有 1 条 `调整到期日`：QRTV 50P，2026-12-04 → 2026-12-03，3 个记录 ID。
5. **Given** 验收 3 **When** 对 P2 调整为 2026-12-03 **Then** 成功，FEAT-042 提醒消失（Q129）。
6. **Given** 场景 **When** 绕过页面对 P3 提交调整为 2026-12-17 **Then** 拒绝 `只能调整到期日为临时休市日的合约`，什么都不改。
7. **Given** 场景 **When** 对 P1 提交新到期日 2026-12-05 **Then** 拒绝 `新到期日 2026-12-05 不是美股交易日`。
8. **Given** 账户 C 有 QRTV 2025-01-09 40P：STO 1 张 2024-12-20 10:00，已"到期作废" **When** 提交调整为 2025-01-08 **Then** 拒绝 `合约已作废 / 被指派 / 行权，不能调整到期日`；持仓页没有该行，也没有按钮。
9. **Given** 账户 D 有 IXQZ 2025-01-09 8P：STO 1 张 2024-12-20 10:00，未处理 **When** 调整为 2025-01-08 **Then** 成功；持仓行显示 `已过期 692 天`、`作废` 按钮；点作废后记录时间为 2025-01-08 16:00（Q63）。

## 边界情况
- **一笔平仓覆盖多笔开仓、同一合约多次开仓**：全部记录一起改，批次和 FIFO 匹配不变（验收 4）。
- **空头 call 有股票覆盖**：覆盖与锁定不随到期日变化（Q130）。
- **调整后 FEAT-042 记录被撤销**（Q127）：已调整的记录不回退。
- **到期日当天**：今天正是新到期日时，按 Q10 / Q83 显示平仓 / 到期待确认 / 作废。
- **跨账户**：只改当前账户；其它账户同一合约各自调整。
- **删除记录**：调整后删除其中一条照 FEAT-005，审计中保留调整记录。

## Out of Scope
- 调整其它日期（非临时休市日）的到期日；修改标的、行权价、C/P。
- 撤销调整；批量调整多个合约。
- 自动调整（无人确认）。

## Open Questions
- 无（Q129–Q133 已定）。

## Implementation Notes

### Backend
- **契约**：`docs/api/FEAT-043.md`（实现阶段两处更正 / 补充：`legacy-option-trades` 平仓方向的 400 文案同 `POST /api/option-trades`；迁移多一列 `audit_record.detail_json`）。
- **新增文件**：`db/migration/V15__expiration_adjustment.sql`；`trade/domain/ExpirationAdjustmentRules`；`trade/service/ExpirationAdjustmentService`；`trade/web/ExpirationAdjustmentController`；`trade/repository/ExpirationAdjustmentEntity` / `ExpirationAdjustmentRepository`；`audit/repository/AuditRecordObjectEntity` / `AuditRecordObjectRepository`。
- **修改**：`AuditOperation`（`ADJUST_EXPIRATION`）、`AuditRecordEntity`（`detail_json`，operation 列宽 20）、`AuditRecordRepository.findByObject`、`AuditService`（`record(r, detail, relatedObjectIds)`、`AuditView.detail`）、`AuditController`（`expirationAdjustment`）；`ErrorCode`（7 个新码）；`OptionTradeEntity.changeExpiration`；`OptionPositionService`（`Adjustability`）、`OptionPositionController`（5 个新字段，`toResponse` 供调整响应复用）；`OptionTradeDtos.AdjustExpirationRequest`；`TestSupportController`（`legacy-option-trades` 接受任意开仓）。
- **测试**：`ExpirationAdjustmentRulesTest`（规则与顺序）、`ExpirationAdjustmentControllerTest`（验收 1–9 的后端部分、404 / 400、跨账户、范围、Q132、只调一次、FEAT-005 审计筛选回归）；`TestSupportResetE2eProfileTest` 增加任意开仓预置；迁移测试改为 `before-V15` / `V16__simulated_failure.sql`，`MigrationFromM1Test` 复制 M1 行时去掉 `detail_json`。
- **设计决定**：
  - 一次调整一条审计（`objectId` = 最小交易 id），其它交易 id 写进 `audit_record_object`，审计按对象筛选同时匹配，所以 3 条记录的"历史"都显示这次调整；`hasAuditLog` 只在调整的交易上置 `true`，其它审计行为不变。
  - "只调一次"用 `expiration_adjustment` 的唯一键（账户 + 新合约），调整后新录入在新合约上的记录不影响判断。
  - Q130 重放：合约重放、call 的覆盖不变量（覆盖不依赖到期日）、现金流水重放；数据正确时不会失败，是防御性检查。
  - 现金流水 `description` 中的旧合约文字改为新合约文字（金额、冻结、时间不变）。
- **已知限制**：不能撤销；调整后 FEAT-042 记录被撤销时不回退；`legacy-option-trades` 只在 `e2e` profile 下存在。

### Frontend
- **新增文件**：`components/position/AdjustExpirationModal.tsx`（调整到期日对话框）、`components/position/expirationAdjustment.ts`（按钮 / 对话框文案与请求体，纯函数）、`pages/AdjustExpiration.test.tsx`。
- **修改**：`api/types.ts`（持仓 5 个新字段、`CreateExpirationAdjustmentRequest`、`ExpirationAdjustmentResponse`、`AuditExpirationAdjustment`、`AuditOperation` 加 `ADJUST_EXPIRATION`、审计项 `expirationAdjustment`，新字段均为可选以兼容旧测试数据）；`api/optionTrades.ts`（`createExpirationAdjustment`）；`PositionTable`（按 `expirationAdjustable` 显示按钮，可与其它按钮并列或单独出现）；`PositionsPage`（打开对话框）；`ExpirationDatePicker`（新增可选 `minDate` / `maxDate` / `dayTestIdPrefix`；单元格加 `data-disabled`；`value` 改为按字符串 memo 的 Dayjs——原先每次渲染新建对象，已有值时翻月会被拉回原月份，录入页也受益）；`AuditTable`（操作标签映射，`ADJUST_EXPIRATION` 显示紫色"调整到期日"；改动列在 `changes` 之后加"记录：#…""备注：…"；展开行显示调整详情代替修改前 / 后快照）。
- **对话框**：合约、`当前到期日（休市名称）`、`N 条开仓 / 平仓记录`、说明文字全部来自后端字段；新到期日默认 `defaultNewExpirationDate`，`newExpirationDateMin`..`newExpirationDateMax` 之外及 FEAT-009 `non-trading-days` 的日期禁用（休市日查询失败时提示不禁用，与录入页相同）；手动输入范围外 / 非交易日不在前端拦截，提交后显示后端 422 原文（验收 7）。前端只校验"新到期日必填"和备注 ≤ 500。错误原文显示、对话框不关闭，并让持仓和提醒失效重查；成功后提示 `已调整到期日：<新合约>`、关闭对话框、`refreshAfterChange`（含持仓、提醒、交易列表、已平仓、审计、现金）。
- **FEAT-042 去调整**：行为不变（滚动并高亮第一个受影响行），需求规则 1 只要求"指向这些行"，不自动打开对话框。
- **data-testid**：持仓行 `position-adjust-expiration`；对话框 `adjust-expiration-modal`、`adjust-expiration-contract`、`adjust-expiration-current`、`adjust-expiration-record-count`、`adjust-expiration-form`、`adjust-expiration-date`（在 input 上）、日期单元格 `adjust-expiration-day-YYYY-MM-DD`（`data-non-trading` / `data-disabled`）、`adjust-expiration-calendar-error`、`adjust-expiration-note`、`adjust-expiration-hint`、`adjust-expiration-error`（`data-error-code`）、`adjust-expiration-submit`、`adjust-expiration-cancel`；审计 `audit-cell-operation` 加 `data-operation`，行内 `audit-adjust-trades`、`audit-adjust-trade-ids`、`audit-adjust-note`，展开 `audit-adjustment`、`audit-adjustment-contract`、`audit-adjustment-dates`、`audit-adjustment-event`、`audit-adjustment-trade-ids`、`audit-adjustment-note`。
- **测试**：`pages/AdjustExpiration.test.tsx`（11 条：文案 / 请求体纯函数、按钮只在可调整行、对话框内容与默认值、范围外 / 非交易日禁用、提交请求体与成功后关闭刷新、422 原文、必填、取消、审计行与展开详情、无备注）。
- **已知限制**：前端不判断日期（只用后端给的范围和非交易日禁用）；`ExpirationDatePicker` 的 memo 修正影响录入页，`TradingCalendar.test.tsx` 等已有测试留给 qa 全量 `npm test` 验证。
