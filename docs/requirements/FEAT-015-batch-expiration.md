# FEAT-015: 到期批量处理（批量作废）

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-010（持仓页到期过滤）、FEAT-012（到期作废）、FEAT-013 / FEAT-041 / FEAT-014（被指派、行权，单行处理不变）、FEAT-033（多账户），都已 Verified
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
作为一个主要卖期权的交易者，每个到期日（常常是周五）我有好几个持仓同时到期，绝大多数是作废，偶尔有一两个被指派。FEAT-010 之后我已经能在"已过期未处理"过滤里看到这些行，但仍要一行一行点"作废"、确认、关闭。我想要一次勾选、一次确认把它们全部作废；被指派的那一两个取消勾选，照旧在行上点"被指派"。

## 已定的口径（只引用）
- Q9：作废是手动的（FEAT-015 可一次处理多个）。Q10、Q83：到期日 17:30 起才能作废；16:00–17:30 "到期待确认"。
- Q63：作废记在到期日美东 16:00。Q64：每次作废生成一条"到期作废"记录。Q65：一次作废该合约全部剩余张数。Q66、Q84：可删除（写审计）、不可修改、没有备注。
- Q13、Q28：空头 put 作废释放冻结。Q19：按 (时间, 录入顺序) 重放。Q53：按账户独立。
- Q34、Q35、Q80、Q110：DTE、持仓排序、到期过滤、`显示 M / N 个持仓`（FEAT-010）。
- 单个作废的检查与拒绝原因：FEAT-012"操作 1"检查 1–5（时间、有持仓、张数一致、无旧的 16:00 之后交易、现金）。
- Q115：只做批量作废，被指派 / 行权仍单行处理。Q116：全部成功或全部不保存，返回所有失败项；顺序到期日升序、同日按 Q35。Q117：默认全部勾选。Q118：按钮在"已过期未处理"和"今天到期"（17:30 起）。Q119：每个持仓一条普通"到期作废"，无批次记录。

## 规则
1. **入口**：持仓页到期过滤为"已过期未处理"或"今天到期"时（Q118），期权表上方显示按钮 `批量作废（N）`，N = 当前表中**显示"作废"按钮**的行数（即后端标为可以作废的行）。N = 0 时不显示按钮。其它过滤（全部、N 天内）不显示。
2. **确认框**：列出这 N 行，顺序同表（Q35）。每行：勾选框（默认全部勾选，Q117）、合约、空头 / 多头、张数（全部剩余）、开仓净额合计、将释放的冻结（0 显示 `—`）——都取持仓已有数据，前端不计算合计。顶部 `已选 K / N 个`，全选 / 全不选；K = 0 时"确认作废"不可点。说明文字："被指派或行权的持仓请取消勾选，在持仓行上单独处理；每条作废记在各自到期日 16:00，记错可在交易列表中逐条删除"。
3. **处理**：对勾选的每个持仓做一次与 FEAT-012 完全相同的作废（同样的检查、同样的记录、同样的盈亏和冻结）。处理顺序 = 到期日升序，同到期日按 Q35；同一到期日的多条作废时间相同（16:00），录入顺序按此顺序（Q116）。
4. **全部成功或全部不保存**（Q116）：任何一项未通过 FEAT-012 检查 1–5，整批拒绝、什么都不保存；返回**每一个**失败项及其原因（不只第一个），确认框在对应行旁原文显示，不关闭，用户可取消勾选该行后重试。
5. **结果**：成功后确认框显示后端返回的：作废个数、已实现盈亏合计、冻结变动合计，以及每行的已实现盈亏和冻结变动；"完成"关闭。持仓页（被作废的行从过滤结果中消失，FEAT-010 规则 7）、现金汇总、资金流水、交易列表、已平仓、盈亏汇总、首页无需手动刷新即更新。
6. **记录形式**（Q119）：不新增记录类型、不记"批次号"。每个持仓生成一条独立的"到期作废"记录，在所有页面上与单个作废完全一样，删除也逐条进行（FEAT-012 / Q66）。批量作废本身不写审计（与单个作废一致）。
7. **按账户**：只处理当前账户的持仓（Q53）。

## 需要的数据和操作（给 backend）
- **批量作废**（当前账户）：输入若干项，每项 = 合约（标的、到期日、行权价、C/P）+ 页面显示的张数（同 FEAT-012 的"张数一致"检查）。
  - 请求校验：至少 1 项；同一合约不能出现两次；任一项不满足即整批拒绝。
  - 按规则 3 的顺序逐项执行 FEAT-012 的作废，一个事务；规则 4 全部成功或全部不保存，失败时返回所有失败项（合约 + FEAT-012 的原文原因）。
  - 成功时返回：每项的作废结果（同单个作废：记账时间、已实现盈亏、冻结变动、被作废批次），以及合计（个数、已实现盈亏合计、冻结变动合计）。
- 持仓数据已有"可以作废"标志、张数、开仓净额合计、冻结（FEAT-012），无需新增字段。

## 页面与交互（给 frontend）
- 持仓页：规则 1 的按钮；规则 2 的确认框；规则 4 的逐行错误显示；规则 5 的结果与刷新。按钮是否出现、哪些行可选，只看后端给的"可以作废"标志，前端不判断时间。
- 单行的作废 / 被指派 / 行权 / 平仓按钮、对话框不变。不加新页面、新导航项。

## 验收标准
标的都是虚构的。E2E 用真实时钟时，把 2026-11-20 换为运行前一天（或前几天）的美东日期，其它日期按同样天数差。

**场景 S**（默认账户）：入金 50,000.00（2026-11-02 10:00）；股票转入 IXQZ 100 股、总成本 750.00、日期 2026-10-01；录入：

| 编号 | 方向 | 合约 | 张数 | 净额 | 成交（美东） | 冻结 |
|------|------|------|------|------|-------------|------|
| S1 | STO | XYZ 2026-11-20 45 P | 2 | 240.00 | 2026-11-03 10:00 | 9,000.00 |
| S2 | STO | XYZ 2026-11-20 42 P | 1 | 80.00 | 2026-11-03 11:00 | 4,200.00 |
| S3 | BTO | QRTV 2026-11-20 30 C | 1 | 60.00 | 2026-11-04 10:00 | — |
| S4 | STO | IXQZ 2026-11-20 9 C | 1 | 25.00 | 2026-11-05 10:00 | —（覆盖 100 股） |
| S5 | STO | XYZ 2026-11-27 40 P | 1 | 70.00 | 2026-11-06 10:00 | 4,000.00 |

场景 S 之后：总现金 50,355.00、冻结 17,200.00、可用 33,155.00。

1. **按钮与确认框**
   **Given** 场景 S，时钟 2026-11-23 10:00 **When** 持仓页选"已过期未处理" **Then** 表中 4 行（S4、S3、S2、S1 的合约，顺序 IXQZ 9 C、QRTV 30 C、XYZ 42 P、XYZ 45 P），上方 `批量作废（4）`；选"全部"或"7 天内" **Then** 没有该按钮。
   **When** 点 `批量作废（4）` **Then** 确认框 4 行全部勾选，`已选 4 / 4 个`；XYZ 45 P 行显示 `2 张`、`+240.00`、冻结 `9,000.00`；QRTV 30 C 行 `−60.00`、冻结 `—`。

2. **全部作废**
   **Given** 第 1 条的确认框 **When** 点"确认作废" **Then** 结果：`作废 4 个`、已实现盈亏合计 `+285.00`、冻结变动合计 `−13,200.00`；表中显示 `没有已过期未处理的持仓`。现金汇总：总现金 50,355.00、冻结 4,000.00、可用 46,355.00。交易列表 4 条"到期作废"，时间都是 `2026-11-20 16:00`，净额 `0.00`；已平仓页新增 4 行，盈亏 `+25.00`、`−60.00`、`+80.00`、`+240.00`。股票持仓 IXQZ 被覆盖 0、可用 100。

3. **取消勾选被指派的行，再单独处理**
   **Given** 场景 S，时钟 2026-11-23 10:00，确认框 **When** 取消勾选 XYZ 45 P（`已选 3 / 4 个`）并确认 **Then** `作废 3 个`、`+45.00`、`−4,200.00`；表中只剩 XYZ 45 P，按钮变为 `批量作废（1）`。**When** 在该行点"被指派"，2 张、指派日期 2026-11-20 **Then** 结果同 FEAT-013（总现金 −9,000.00、冻结 −9,000.00），表中显示 `没有已过期未处理的持仓`。
   **When** 确认框里点"全不选" **Then** `已选 0 / 4 个`，"确认作废"不可点。

4. **一项失败，整批不保存**
   **Given** 场景 S，时钟 2026-11-23 10:00，打开确认框（4 行）**When** 在另一页面补录 STO 1 张 XYZ 2026-11-20 42 P，净额 50.00（2026-11-19 10:00），回到确认框点"确认作废" **Then** 被拒绝，XYZ 42 P 行旁显示"持仓已变化（当前 2 张），请刷新后重试"，确认框不关闭；交易列表没有任何"到期作废"，冻结仍 21,400.00。**When** 关闭后重新打开（XYZ 42 P 显示 `2 张`、`+130.00`）并确认 **Then** `作废 4 个`、`+335.00`、`−17,400.00`。

5. **多个失败项全部返回**（仅后端测试：绕过校验直接存入 FEAT-012 之前的"旧的 16:00 之后交易"）
   **Given** 场景 S，再存入 STC 1 张 QRTV 2026-11-20 30 C，净额 20.00，成交 2026-11-20 16:10；时钟 2026-11-23 10:00 **When** 批量作废 4 项，其中 XYZ 42 P 的张数填 2 **Then** 整批拒绝，返回 2 个失败项：QRTV 30 C（FEAT-012 检查 4 的原因，提到 `2026-11-20 16:10 的 STC 1 张`）、XYZ 42 P（"持仓已变化（当前 1 张），请刷新后重试"）；IXQZ、XYZ 45 P 也未作废。
   **When** 请求为空，或同一合约出现两次 **Then** 整批拒绝，什么都不保存。

6. **到期日当天：17:30 前后**
   **Given** 场景 S，时钟 2026-11-20 16:30，过滤"今天到期" **Then** S1–S4 显示"到期待确认"，没有 `批量作废` 按钮。直接请求批量作废这 4 项 **Then** 整批拒绝，每项原因"到期日（2026-11-20）美东 17:30 起才能作废"。
   **Given** 时钟 2026-11-20 17:45 **Then** "今天到期"上方 `批量作废（4）`；确认后结果同第 2 条，表中显示 `没有今天到期的持仓`。

7. **不同到期日一起作废**
   **Given** 场景 S，时钟 2026-11-30 10:00 **When** "已过期未处理"→ `批量作废（5）`，全部确认 **Then** `作废 5 个`、`+355.00`、`−17,200.00`；4 条记在 `2026-11-20 16:00`、XYZ 40 P 记在 `2026-11-27 16:00`；盈亏汇总中都归 2026-11；冻结 0.00、可用 50,355.00。

8. **逐条删除，与单个作废相同**
   **Given** 第 2 条之后 **When** 在交易列表删除 XYZ 42 P 的"到期作废" **Then** 按 FEAT-012：写审计，冻结恢复 4,200.00；其余 3 条不变；"已过期未处理"中 XYZ 42 P 重新出现，按钮 `批量作废（1）`。审计记录页没有"批量作废"类型的记录。

9. **按账户隔离**
   **Given** 场景 S 之外，账户"小账户"：入金 5,000.00（2026-11-02 10:00）、STO 1 张 XYZ 2026-11-20 45 P，净额 100.00（2026-11-04 10:00）；时钟 2026-11-23 10:00 **When** 在默认账户执行第 2 条 **Then** 小账户的 XYZ 45 P 仍在、冻结 4,500.00；在小账户"已过期未处理"显示 `批量作废（1）`。**When** 在默认账户已全部作废后、以默认账户请求作废 XYZ 2026-11-20 45 P（张数 1）**Then** 被拒绝"该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废"。

## 边界情况
- **对话框打开期间持仓变化 / 已被单独处理**：整批拒绝（验收 4、9）。
- **周末 / 假日**：周五到期、周一处理，记在周五 16:00（验收 2）；假日按日历天（FEAT-009）。
- **multiplier ≠ 100、多批次的合约**：每行就是一次 FEAT-012 作废，口径不变。
- **作废后补录较早批次**：该合约重新出现，可再次批量或单个作废（Q65）。
- **页面停留跨过 17:30 / 美东午夜**：按钮和行不自动更新（同 FEAT-012）；过时的请求由后端检查拒绝。

## Out of Scope
- 在批量里选择"被指派""行权"、填指派 / 行权日期与费用（Q115）
- 批量撤销（删除）、批量平仓
- "全部"及 N 天内过滤上的批量按钮（Q118）
- 自动作废（Q9）；交易日判断（FEAT-009）；全部账户合并（FEAT-035）

## Open Questions
无（Q115–Q119 已定，见 decisions.md）。

## Implementation Notes

### Backend
- **契约**：`docs/api/FEAT-015.md`（`POST /api/option-trades/expirations/batch`），实现与契约一致，未改契约。
- **改动文件**：
  - `backend/src/main/java/com/optionmgr/trade/service/OptionExpiryService.java`：单个作废拆为 `check`（FEAT-012 检查 2–5）+ `save`（重放、现金检查、保存 `EXP` 与流水、覆盖纳入）；新增 `expireAll`、`PROCESSING_ORDER`、`BatchFailure`、`BatchResult`、`BatchExpirationRejectedException`。单个作废行为不变（时间检查现在在加锁之后，结果相同）。
  - `backend/src/main/java/com/optionmgr/trade/web/OptionTradeController.java`：新接口；注入 `jakarta.validation.Validator`。
  - `backend/src/main/java/com/optionmgr/trade/web/BatchExpireValidation.java`（新）：空列表 / 超过 500 项 / 每项字段（`items[i].field`）/ 重复合约（`items[i]`）一起返回 400。
  - `backend/src/main/java/com/optionmgr/trade/web/BatchExpirationErrorHandler.java`（新）：422 `BATCH_EXPIRATION_REJECTED` + `failures`。
  - `backend/src/main/java/com/optionmgr/trade/web/OptionTradeDtos.java`：请求、响应、失败项 DTO。
  - `backend/src/main/java/com/optionmgr/common/error/ErrorCode.java`：`BATCH_EXPIRATION_REJECTED`。
  - `backend/src/test/java/com/optionmgr/trade/web/BatchExpireControllerTest.java`（新）：验收 1–9 及请求校验。
  - `docs/architecture.md`：新增"到期批量作废（FEAT-015）"一节。
- **设计决定**：在一个事务、一把账户锁内，先按处理顺序对所有项做检查并收集全部失败，再按同一顺序逐项保存（各项合约不同，检查互不影响）；id 按处理顺序递增，所以同一 cutoff 的多条作废按此顺序重放。每条记录与单个作废完全相同，不写审计、不记批次。无数据库迁移，持仓接口无新字段（用 `expirable` / `expiryContracts`）。
- **已知限制**：每项只报第一个不通过的检查（同单个作废）；保存阶段的现金检查理论上不会失败，若失败整批回滚并返回通用 `CASH_INSUFFICIENT`（无 `failures`）。上限 500 项为防御性限制。

### Frontend
- **新增**：
  - `frontend/src/components/position/BatchExpireModal.tsx`：批量作废确认框。打开时取当前表中 `expirable` 行的快照（表中顺序），默认全部勾选（Q117）；每行勾选框、合约、空头 / 多头、`N 张`（`expiryContracts`）、开仓净额合计（正绿负红）、将释放的冻结（0 显示 `—`）；`已选 K / N 个`、全选 / 全不选，K = 0 时"确认作废"禁用；规则 2 说明文字。确认后 `POST /api/option-trades/expirations/batch`，只发勾选的行（`contracts` 原样用 `expiryContracts`）。422 `BATCH_EXPIRATION_REJECTED`：顶部原文显示 `message`，`failures[]` 每一项原文显示在对应行的合约下方（按合约匹配，合约对不上时按 `index` 对应发送的项，仍对不上的列在顶部错误框里），确认框不关闭，可取消勾选后重试；其它错误（400 等）原文 + `details` 显示在按钮上方。失败后在后台刷新持仓，确认框保持打开时的快照，关闭后重新打开显示新值。成功后显示 `作废 N 个`、已实现盈亏合计、冻结变动合计和每行结果（合约、方向、张数、记账时间、已实现盈亏、冻结变动），`expirations[]` 按合约对应到行，不依赖下标；"完成"关闭。
  - `frontend/src/components/position/batchExpire.ts`：按钮显示条件（只在"已过期未处理""今天到期"，Q118）、可选行（只看 `expirable`）、请求项、按合约匹配结果和失败项（标的大写、行权价去掉多余的 0 后比较，纯字符串处理）、文案。
  - `frontend/src/components/position/expireLabels.ts`：从 `ExpirePositionModal` 抽出的 `directionLabel` / `frozenText`，单个和批量作废共用；`SignedMoney` 改为从 `ExpirePositionModal` 导出。单个作废对话框行为、文案和 testid 不变。
  - API：`createExpirationBatch`、`expirationBatchFailures(error)`、`BATCH_EXPIRATION_REJECTED`（`src/api/optionTrades.ts`）；类型 `CreateExpirationBatchRequest`、`ExpirationBatchResponse`、`ExpirationBatchFailure`、`ExpirationBatchRejectedResponse`（`src/api/types.ts`）。`ApiError` 新增 `body`（契约错误体原样保存），用来读取 `failures` 扩展字段。
- **修改**：`frontend/src/pages/PositionsPage.tsx`：到期过滤为"已过期未处理"或"今天到期"且显示的行中有 `expirable` 时，在期权表上方显示 `批量作废（N）`；成功后用 `refreshAfterChange` 刷新所有相关数据（持仓、现金、交易、已平仓、盈亏汇总、首页等）。
- **data-testid**：`positions-batch-expire`；`batch-expire-modal`、`batch-expire-selected-count`、`batch-expire-select-all`、`batch-expire-select-none`、`batch-expire-table`、`batch-expire-row`（`data-contract`）、`batch-expire-row-checkbox`、`batch-expire-row-contract`、`batch-expire-row-direction`、`batch-expire-row-contracts`、`batch-expire-row-opening-net`、`batch-expire-row-frozen`、`batch-expire-row-error`（`data-error-code`）、`batch-expire-explanation`、`batch-expire-error`（`data-error-code`）、`batch-expire-unmatched-error`、`batch-expire-submit`、`batch-expire-cancel`；结果：`batch-expire-result`、`batch-expire-result-count`、`batch-expire-result-pnl-total`、`batch-expire-result-frozen-total`、`batch-expire-result-table`、`batch-expire-result-row`（`data-contract`）、`batch-expire-result-row-contract`、`batch-expire-result-row-direction`、`batch-expire-result-row-contracts`、`batch-expire-result-row-time`、`batch-expire-result-row-pnl`、`batch-expire-result-row-frozen-change`、`batch-expire-done`。
- **测试**：`frontend/src/components/position/batchExpire.test.ts`（显示条件、可选行、合约匹配、失败项匹配与回退）、`frontend/src/pages/BatchExpire.test.tsx`（按钮出现 / 不出现、到期待确认时没有按钮、确认框默认全选与数值、全选 / 全不选、取消、提交只发勾选项、结果乱序时按合约对应、多个失败项逐行显示且不关闭后重试、400 原文显示）。均用 mock 的 fetch。
- **已知限制**：确认框是打开时的快照，打开期间持仓变化不自动更新（由后端拒绝，同 FEAT-012）；页面停留跨过 17:30 时按钮不会自己出现，需刷新页面。冻结变动合计为 0 时显示 `—`（与单个作废一致）。
