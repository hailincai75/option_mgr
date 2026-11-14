# FEAT-014: 主动行权（多头期权）

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-013、FEAT-041（被指派记录形式）、FEAT-039（股票覆盖锁定）、FEAT-012（作废）、FEAT-011 / 040（股票及其盈亏）
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
我主要卖期权，偶尔买。买入的 call / put 价内时我可能提前行权，或到期价内被自动行权。我想在持仓页多头行点"行权"：call 按行权价买股，put 按行权价卖股，权利金并入股票一侧。记错可以删除。

## 已定的口径
- Q8 / Q111：权利金并入股票成本 / 卖出实收，期权记 0.00（规则 4）。
- Q11：只支持股票 / ETF 期权。
- Q15、Q68、Q19：不能卖空；买股实付 ≤ 可用现金；按时间重放。
- Q21 / Q73 / Q112：整笔批次，可只行权最早的部分批次（规则 3）。
- Q65：先记行权、再作废剩余。Q69 / Q87：股票 FIFO 与分摊。
- Q78 / Q113：被 call 覆盖的股票不能行权卖出。
- Q81：提前行权、按钮时间、日期规则、检查项（规则 2、5）。
- Q96 / Q99：付款向上、收款向下取整到分。Q97 / Q100 / Q114：笔数（规则 7）。

## 规则
沿用 FEAT-013 / 041：记录形式（"行权"期权记录 + 生成的股票记录 + 一条现金流水，同生同灭）、日期字段与默认 / 最晚值（Q76、Q94，文案"指派"换成"行权"）、整笔批次与之后同合约记录的重放检查、与作废的先后、删除（一条审计、可重记）、不可修改、生成的股票记录不能单独改删、重放连带变化写审计（Q42）、备注 ≤ 500、按账户隔离（Q53）、前端不做计算。以下只写差异。

1. **对象**：只有多头 call / put。对空头请求 → 拒绝"空头持仓不能行权（被行权请用"被指派"）"。
2. **按钮（Q81）**：多头行任何时候显示"行权"：到期前与 `平仓` 并列；到期日 16:00–17:30 唯一按钮；17:30 起及已过期与 `作废` 并列。
3. **张数**：最早 1 笔或连续多笔整批次之和，否则"必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张"。剩余批次照常平仓、作废或再行权。
4. **金额（Q111）**：股数 = 张数 × multiplier；权利金 = 被行权批次开仓净额之和；行权费可选，默认 0.00（≥ 0、2 位小数）；期权已实现盈亏 0.00；冻结不变。
   - **call**：买股金额 = 行权价 × 股数（向上取整）；股票成本 = 买股金额 + 权利金 + 行权费；现金 −（买股金额 + 行权费）。
   - **put**：行权金额 = 行权价 × 股数（向下取整）；卖出实收 = 行权金额 − 权利金 − 行权费，须 > 0.00；交出成本按股票 FIFO；股票盈亏 = 卖出实收 − 交出成本；现金 +（行权金额 − 行权费）。
5. **检查顺序**：字段 → 对象 → 日期 → 有持仓 → 整笔批次 → 之后同合约记录 → put：实收 > 0、持股、可用股数 → 覆盖不变式（FEAT-039 E3，前缀"行权"）→ 现金。文案：
   - 持股不足："持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）"；之后的卖出因此超量 → FEAT-011 文案。
   - 被覆盖（Q113）："行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call"。
   - 现金不足：FEAT-013 风格，"可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00"。
6. **生成的股票记录**：call → 新批次 **"行权买入"**（成本 = 股票成本）；put → **"行权卖出"**（参与 FIFO、产生股票盈亏）。时间 = 行权时间，关联行权 ID；改删提示"该股票记录由行权生成，请在交易列表删除对应的行权记录"。行权本身修改 → "行权不能修改，记错请删除后重新记录"。审计摘要如 `行权 QRTV 2026-11-20 30 C 3 张（买入 300 股 QRTV）`；连带变化如 `行权 #15 股票成本 9,460.00 → 9,470.00`、`行权 #16 股票卖出实收 4,380.00 → 4,370.00`。
7. **笔数（Q114）**：call 行权计 1 笔（期权行 0.00）；put 行权计 2 笔（期权行 0.00 + 股票"行权卖出"行）；已平仓、汇总、首页一致。

## 需要的数据和操作（给 backend）
- **记录行权**：输入 合约、张数、行权日期、行权费、备注。返回：合约、张数、行权时间（UTC + 美东）、被行权批次、权利金、股数、行权费、期权盈亏、现金变动、股票记录 ID、备注；call 另给买股金额、股票成本、每股成本（4 位小数）；put 另给行权金额、卖出实收、交出批次（来源类型、时间、股数、成本）、交出成本、股票盈亏。
- **删除行权**：按 FEAT-013 操作 2 重放（合约、股票持股与覆盖、现金）。
- **持仓**：多头行"可以行权"、可选张数及股数、日期默认 / 最晚值；call 行另给当前可用现金，put 行另给该标的持股和可用股数。
- **查询**：交易列表（方向"行权"可筛选、只删不改）、已平仓（期权行另给股数及股票成本或卖出实收；"行权"筛选属期权专用，Q90）、股票列表（两种新类型可筛选、关联行权 ID、不可改删）、资金流水（来源"行权"，说明如 `行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV`，multiplier ≠ 100 加 `（multiplier 1）`）、审计。

## 页面与交互（给 frontend）
沿用 FEAT-013 / 041 的对话框和各页样式，差异：
- **持仓页**：多头行按后端标志显示"行权"。
- **对话框"记录行权"**：只读 合约、`多头`、行权价、multiplier、当前张数；call 显示"可用现金 X"，put 显示"当前持股 X 股，可用 Y 股"。张数下拉如 `3 张（300 股）`，默认最大；行权日期；行权费（说明"券商收取的行权手续费"）；备注。说明段按规则 4 的 call / put 公式各一句；put 加"被 call 覆盖的股票不能卖出"。结果显示返回各项（put 含交出批次表）。
- **交易列表**：方向 `行权`、净额 `0.00`、冻结 `—`；小字 `买入 300 股 QRTV，现金 −9,000.00` / `卖出 100 股 XYZ，现金 +4,500.00`（有费用加 `，行权费 5.00`）。
- **已平仓页**：期权行小字 `权利金已计入股票成本（300 股 QRTV，成本 9,460.00）` / `权利金已从股票卖出实收中扣除（100 股 XYZ，实收 4,380.00）`；股票行标签 `行权卖出`、链接 `来自行权 #16`；页面说明补一句行权口径。
- **股票页**：标签 `行权买入`（`成本 9,460.00`、每股）/ `行权卖出`（`实收 4,380.00`、盈亏）；提示"由行权生成，在交易列表删除"。
- **资金页**：来源 `行权`，链接到交易列表。

## 验收标准
后端测试固定时钟；E2E 按 FEAT-013 换成相对日期。标的均为虚构。现金 = 总 / 冻结 / 可用；股票 = 总股数 / 被覆盖 / 可用。

**场景 E**（默认账户）：入金 20,000.00（2026-11-02 10:00），按顺序：

| 编号 | 记录 | 时间（美东） |
|------|------|------|
| T1 | 买入 XYZ 200 股，8,000.00 | 2026-11-03 10:00 |
| L1 | BTO QRTV 2026-11-20 30 C，2 张，净额 300.00 | 2026-11-04 10:00 |
| L2 | BTO QRTV 2026-11-20 30 C，1 张，净额 160.00 | 2026-11-05 10:00 |
| L3 | BTO XYZ 2026-11-20 45 P，1 张，净额 120.00 | 2026-11-05 11:00 |
| S1 | STO XYZ 2026-12-18 50 C，1 张，净额 90.00 | 2026-11-06 10:00 |

现金 **11,510.00 / 0.00 / 11,510.00**；XYZ `200 / 100 / 100`。除注明外时钟 2026-11-23 10:00、行权日期 2026-11-20、费用 0.00。

1. **按钮与对话框**：时钟 2026-11-17 10:00 → QRTV、XYZ 45 P 行有 `平仓`、`行权`，S1 没有 `行权`；时钟 2026-11-20 16:30 → 两行只有 `行权`；时钟 2026-11-23 → `作废`、`行权`。QRTV 对话框：张数 `2 张（200 股）`、`3 张（300 股）`，默认 3；日期默认 2026-11-20、最晚 2026-11-20；"可用现金 11,510.00"。XYZ 45 P 对话框："当前持股 200 股，可用 100 股"。行权 S1 → 拒绝"空头持仓不能行权（被行权请用"被指派"）"。

2. **call 全部行权**：行权 QRTV 3 张 → 时间 `2026-11-20T21:00:00Z`、权利金 `460.00`、买股 `9,000.00`、股票成本 `9,460.00`、每股 `31.5333`、现金 `−9,000.00`、期权 `0.00`。现金 **2,510.00 / 0.00 / 2,510.00**；QRTV 期权无持仓、股票 300 股 9,460.00（`行权买入`）。已平仓 1 行 `0.00`、`共 1 笔`；流水 `行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV`；审计无新记录。

3. **put 行权**：行权 XYZ 45 P 1 张 → 行权金额 `4,500.00`、实收 `4,380.00`、交出 T1 100 股 `4,000.00`、股票盈亏 `+380.00`、现金 `+4,500.00`。现金 **16,010.00 / 0.00 / 16,010.00**；XYZ `100 / 100 / 0`。已平仓 2 行（`行权卖出` `+380.00` 在上、`行权` `0.00` 在下）、`共 2 笔`；首页 `+380.00（2 笔）`。

4. **提前、部分批次、作废、日期**：时钟 2026-11-19 10:00。QRTV 行权 1 张 → 拒绝（规则 3 文案）；日期 2026-11-19 → 拒绝"行权日期为今天时，美东 16:00 之后才能记录"；日期 2026-11-21 → 拒绝"行权日期不能晚于到期日（2026-11-20）"。行权 2 张、日期 2026-11-18、费用 5.00 → 批次 L1、成本 `6,305.00`、每股 `31.5250`、现金 `−6,005.00`；现金 5,505.00 / 0.00 / 5,505.00；QRTV 剩 1 张。时钟 2026-11-23 作废 QRTV → 1 张 `−160.00`。另：先作废 QRTV 再行权 3 张 → 拒绝，含"如果实际是行权，请先删除该到期作废"。

5. **现金不足**：场景 E 加出金 3,000.00（2026-11-10 10:00）。行权 QRTV 3 张 → 拒绝"可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00"；2 张 → 成功，现金 2,510.00 / 0.00 / 2,510.00。

6. **持股不足与覆盖**：
   - "小账户"：入金 1,000.00（2026-11-02 10:00）；BTO 1 张 XYZ 2026-11-20 45 P，净额 120.00（2026-11-03 10:00）。行权 → 拒绝（规则 5 持股不足文案）。
   - 场景 E 加 STO 1 张 XYZ 2026-12-18 50 C，净额 80.00（2026-11-07 10:00）→ XYZ `200 / 200 / 0`；行权 XYZ 45 P → 拒绝（规则 5 被覆盖文案）。BTC 该 call、实付 30.00（2026-11-10 10:00）后 → 成功，实收 `4,380.00`、盈亏 `+380.00`。

7. **删除与不可修改**：第 2 条后删除行权 → 现金 11,510.00 / 0.00 / 11,510.00，QRTV 恢复 3 张，股票、流水、已平仓行消失，审计一条（规则 6 摘要）；再行权成功、无"历史"。第 2 条后卖出 QRTV 100 股、实收 3,300.00（2026-11-24 10:00），再删除行权 → 拒绝"删除后 2026-11-24 10:00 的卖出 QRTV 100 股将超过当时持股 0 股"。修改行权、改删生成的股票记录 → 拒绝（规则 6 文案）。

8. **重放**（第 2、3 条后）：L2 净额 → 170.00 → 股票成本 `9,470.00`，审计连带变化含 `行权 #<ID> 股票成本 9,460.00 → 9,470.00`。L3 净额 → 130.00 → 实收 `4,370.00`、盈亏 `+370.00`。现金 6,990.00 / 0.00 / 6,990.00。删除 L2 → 拒绝"该开仓被 2026-11-20 16:00 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权"。

9. **multiplier ≠ 100、取整**：新账户：入金 100.00（2026-11-02 10:00）；转入 QFZ 1 股成本 10.00（2026-11-02）；BTO 1 张 QFZ 2026-11-20 12.345 C、multiplier 1、净额 0.50（2026-11-03 10:00）；BTO 1 张 QFZ 2026-11-20 12.345 P、multiplier 1、净额 0.40（2026-11-03 10:05）。现金 99.10。行权 C → 买股 `12.35`、成本 `12.85`、现金 86.75；行权 P → 行权金额 `12.34`、实收 `11.94`、交出转入 1 股 `10.00`、盈亏 `+1.94`、现金 99.09；流水含 `（multiplier 1）`。

10. **账户隔离、格式**：第 2 条后，第 6 条"小账户"的 BTO 1 张 QRTV 2026-11-20 30 C（净额 50.00，2026-11-04 10:00）仍为 1 张、现金不变；在"小账户"用默认账户的行权 ID 删除 → "不存在"。行权费 `−1.00` / `1.234`、日期为空、备注 501 字符、张数 0 → 拒绝，错误显示在字段下。

## 边界情况
- 到期价内被自动行权（OCC）：用户按到期日补记"行权"；系统不自动判断。
- 一次行权覆盖多个批次：权利金相加，call 生成一个股票批次。
- call 行权得到的股票可用于之后的 covered call；put 行权使覆盖不足 → E3 拒绝。
- put 交出的批次可以是"指派买入""行权买入""转入"，照常 FIFO。
- 同一时间 16:00 在行权之后录入的同标的卖出可以用到"行权买入"批次。
- 拆股见 FEAT-028。

## Out of Scope
- 自动判断价内 / 自动行权（FEAT-015、M3）；放弃行权指令
- 指数期权（Q11）；拆分批次；指定交出哪批股票；融资买股
- 修改行权记录；单独改删生成的股票记录
- 批量到期（FEAT-015）、合并查看（FEAT-035）、券商接入

## Open Questions
无（Q81、Q111–Q114 已定，见 decisions.md）。

## Implementation Notes

### Frontend
- **新增**：`frontend/src/components/position/ExercisePositionModal.tsx`（记录行权对话框：只读合约 / 多头 / 行权价 / multiplier / 张数，call "可用现金 X"、put "当前持股 X 股，可用 Y 股"；张数下拉 `N 张（M 股）` 默认最后一项，行权日期默认 / 最晚取后端，行权费、备注；call / put 说明段；结果按响应显示，put 含交出批次表）、`exerciseValidation.ts`（格式检查）、API `createExercise`（`src/api/optionTrades.ts`）及类型（`CreateExerciseRequest`、`OptionTradeExercise`、`ClosedExercise`、错误码常量）。
- **修改（无新页面）**：持仓页 `PositionTable` / `PositionsPage`（"行权"按后端 `exercisable` 显示，可与平仓 / 作废并列，到期待确认时是多头行唯一按钮）；`tradeLabels`（`EXR` → "行权"、只删不改、`exerciseRowText`）、交易列表（小字、净额 0.00 不着色、删除标题 / 摘要 / 警告）、方向筛选加 `EXR`（交易列表、已平仓）；`ClosedTradeTable`（期权行小字 + 股票记录链接，股票 `EXERCISE_SELL` 行"行权卖出" + `来自行权 #id`）、已平仓页说明句；`stockLabels` / `StockFilterForm` / `StockTradeTable`（`行权买入` / `行权卖出`，`成本 X` / `实收 X`，`来自行权 #id`，提示"由行权生成，在交易列表删除"）；资金流水 `OPTION_EXERCISE` → "行权"；审计 `EXR` 快照字段。多头 put 行的 `underlyingShares` / `availableShares` 不作为"空头 call 行"的判断（覆盖标签只看 `direction` + `optionType`，已有测试覆盖）。
- **新 data-testid**：`position-exercise`；对话框 `exercise-modal`、`exercise-contract`、`exercise-direction`、`exercise-strike`、`exercise-multiplier`、`exercise-position-contracts`、`exercise-available-cash`、`exercise-underlying-shares`、`exercise-form`、`exercise-contracts`、`exercise-contracts-option-<N>`、`exercise-date`、`exercise-fee`、`exercise-note`、`exercise-explanation`、`exercise-error`（带 `data-error-code`）、`exercise-submit`、`exercise-cancel`；结果 `exercise-result`、`exercise-result-contract` / `-time` / `-contracts` / `-premium` / `-shares` / `-fee` / `-purchase` / `-stock-cost` / `-cost-per-share` / `-sale-amount` / `-proceeds` / `-sold-cost` / `-stock-pnl` / `-cash-change` / `-pnl` / `-stock-trade` / `-note` / `-lots` / `-sold-lots`、`exercise-done`；列表 `trade-cell-exercise`、`trade-filter-action-exr`、`closed-filter-action-exr`、`closed-cell-exercise`、`closed-exercise-stock-link`、`closed-stock-exercise-link`、`closed-exercise-note`、`stock-exercise-link`、`stock-exercise-hint`、`stock-filter-type-exercise-buy`、`stock-filter-type-exercise-sell`；审计 `audit-before-exerciseDate` / `-exerciseFee` 等。
- **测试**：`frontend/src/pages/Exercise.test.tsx`、`frontend/src/components/position/exerciseValidation.test.ts`（mock 后端按契约示例）；按契约新文案更新了 `closedListFilters.test.ts`、`assignValidation.test.ts`、`stockUtils.test.ts`、`tradeListFilters.test.ts`、`ClosedTradesPage.test.tsx`、`TradeListPage.test.tsx` 中的方向 / 类型枚举错误文案。
- **已知限制**：按契约 mock 开发，未与真实后端联调；契约新增字段在类型中声明为可选（兼容旧响应与旧测试数据，`exercisable` 缺省按 false）；资金页"行权"行沿用期权行的"在交易列表中修改"链接（不带筛选）；持仓上的可用现金 / 持股是当前值，较早日期以提交时后端检查为准。

### Backend
- **新增文件**：`backend/src/main/java/com/optionmgr/trade/service/OptionExerciseService.java`（`POST /api/option-trades/exercises`）；`backend/src/main/resources/db/migration/V12__exercise.sql`；测试 `trade/web/ExerciseControllerTest.java`（验收 1–10，16 个用例）、`trade/domain/ExerciseTermsTest.java`。
- **修改**：`TradeAction`（`EXR`、`hasStockLeg()`、`buysStock()`）、`ContractReplay`、`AssignmentTerms` / `CallAssignmentTerms`（`describe(action, …)`、`dateProblem("行权日期", …)`）、`TradeRejectionMessages`、`CoverageMessages` / `CoverageService.checkSale(…, exercise)`、`OptionTradeService`（`buyLeg()` / `sellLeg()`、成本改正按 `hasStockLeg`）、`OptionTradeEditService`（修改 EXR → 422、删除 / 审计）、`ClosedTradeService` / `ClosedRecordFilter` / `ClosedTradeController`（`exercise`、`exerciseTradeId`）、`OptionPositionService` / `OptionPositionController`（`exercisable` 等、`availableCash`、多头 put 的 `underlyingShares` / `availableShares`）、`OptionTradeController` / `OptionTradeDtos`、`TradeListQuery`；股票侧 `StockTradeType`（`EXERCISE_BUY` / `EXERCISE_SELL`）、`StockTradeEntity`、`StockMessages`、`StockTradeService`、`StockTradeEditService`、`StockTradeDtos` / `StockTradeController` / `StockTradeListQuery` / `StockTradeTimeRequired`；`CashSourceType.OPTION_EXERCISE`、`SideEffect`（`EXR` → `行权 #id`）、`ErrorCode`（6 个新码）。迁移测试：`V12__simulated_failure.sql` 改名 `V13__…`，`MigrationFromM1Test`、`MigrationV6HasAuditLogTest` 的备份名改为 `before-V12` / `before-V13`。随枚举 / 文案变化更新了 `StockTradeControllerTest`、`ClosedTradeControllerTest`、`OptionTradeListControllerTest`、`StockPnlInSummariesControllerTest`、`ClosedRecordsTest` 的断言。
- **设计决定**：行权与被指派统一为"有股票一侧的记录"。用多头批次开仓净额的现金符号（负数）做权利金，call 行权复用 `AssignmentTerms`（成本 = 买股 + 权利金 + 费，向上取整），put 行权复用 `CallAssignmentTerms`（实收 = 行权金额 − 权利金 − 费，向下取整）；对外 `premium` 取正。行权费沿用 `assignment_fee` 列（契约中"预计新增 `exercise_fee` 列"已改为此做法，不影响 API）。put 行权按 Q113 检查可用股数，文案前缀"行权需要卖出…"，之后再做 E3（前缀"行权"）。
- **已知限制**：持仓上的 `availableCash`、`underlyingShares`、`availableShares`、`exerciseChoices` 都是当前值，行权日期较早时以提交时的检查为准；不能指定交出哪批股票（股票 FIFO）；拆股见 FEAT-028。
