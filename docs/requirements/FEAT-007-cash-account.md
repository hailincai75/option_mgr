# FEAT-007: 资金账户：入金/出金、现金余额与流水

- **Status:** Verified
- **Priority:** P0
- **Depends on:** 无（M1 第一个实现的需求；FEAT-001 依赖本需求）
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
作为一个期权交易者，我想要在系统里记录入金和出金，并随时看到 总现金 / 冻结现金 / 可用现金 和每一笔现金流水，以便后面录入期权交易时系统能检查现金够不够，账上现金和券商一致、永远不为负。

## 已定的口径（来自 backlog Open Questions）
- 单账户、只有 USD（Q3、Q4）
- 用"入金"录入初始资金；现金永远不能为负；可用现金 = 总现金 − 冻结现金（Q13）
- 时间按美东时间（America/New_York）录入，默认当前时间，存 UTC（Q14）
- 补录历史记录按时间顺序重放：插入后从该时点起每个时点的总现金和可用现金都不能为负；同一时间的多条记录按录入顺序排（Q19）
- 备注最多 500 字符（Q25）
- 本需求**只做新增**入金/出金。修改/删除放在 FEAT-005（Q24 未定）。录错时可以先录一笔反向的入金/出金冲正，并在备注里说明

## 需要的数据和操作（给 backend）

### 入金 / 出金记录
| 字段 | 说明 / 规则 |
|------|-------------|
| 类型 | 入金 或 出金 |
| 金额 | 必填，> 0，最多 2 位小数；用户只填正数，类型决定流入还是流出 |
| 时间 | 必填，用户提供的是美东时间的日期 + 时刻；保存为 UTC 时间点；不得晚于当前时间 |
| 备注 | 可选，最多 500 字符 |

- 金额全部用精确十进制，不允许二进制浮点误差（例：入金 0.10 + 入金 0.20 = 0.30，不能是 0.30000000000000004）。
- 每条记录要能确定"录入顺序"，用于同一时间多条记录的排序。

### 操作：新增入金
- 字段校验通过即保存。入金只会增加现金，不会让任何时点变负，所以补录到过去也总是允许。

### 操作：新增出金
- 字段校验。
- 现金检查：把本笔按时间插入已有的所有现金记录（入金、出金，以及以后 FEAT-001 等产生的交易流水和冻结变化）中按时间顺序重放，插入后**从本笔时间起的每个时点**，总现金和可用现金都不能为负；否则整笔拒绝、不保存，并返回能直接给用户看的原因，包含需要的金额、不足时点的可用现金和该时点（如"可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00"）。
- 出金检查的是**可用现金**而不是总现金：被冻结的现金不能取出（冻结要等 FEAT-001 才会出现，见下面"冻结现金"）。

### 现金汇总
- 总现金：所有流水金额之和。
- 冻结现金：当前被冻结的金额。本需求里没有任何会产生冻结的操作，所以**始终为 0.00**；但后端要把冻结作为汇总和重放检查的一部分来设计，FEAT-001 上线后 STO put 会产生冻结，不需要再改本需求的规则。
- 可用现金 = 总现金 − 冻结现金。
- 汇总按"当前"计算（所有记录都不晚于当前时间）。
- 汇总要能被其它页面复用（FEAT-001 的录入表单会显示它）。

### 现金流水
- 流水是所有现金变动的统一列表。本需求产生两种来源：入金、出金。以后的需求会加入其它来源（FEAT-001 期权开仓、FEAT-004 期权平仓，M2 的股票交易、指派/行权等），所以每条流水要能区分**来源类型**，并能关联到产生它的原始记录（如某笔交易）。
- 每条流水包含：时间、来源类型、带正负号的金额（流入为正、流出为负）、该条之后的总现金余额、说明/备注。
- "该条之后的总现金余额"按时间顺序（同一时间按录入顺序）累计；补录历史记录后，之后各条的余额要随之更新。
- 列表按时间倒序（最新在上；同一时间的，后录入的在上），支持分页。

## 页面与交互（给 frontend）
- 新增一个"资金"页面，导航里有入口。
- 页面顶部：现金汇总，显示 总现金 / 冻结现金 / 可用现金 三个数字。做成可复用的组件，FEAT-001 的录入表单会用到。
- 入金/出金表单（同一页面的表单或对话框均可）：类型（入金/出金）、金额、时间（默认当前美东时间，旁边注明"美东时间"）、备注。
  - 前端做基本格式校验（必填、> 0、最多 2 位小数、备注长度），错误显示在对应字段下。
  - 后端拒绝时，显示后端返回的原因原文，**保留已填内容**。
  - 成功后提示成功，现金汇总和流水列表自动刷新（无需手动刷新页面）。
  - 前端不计算余额、不预测提交后的现金（业务计算只在后端）。
- 流水列表（表格）：时间（美东，格式如 `2026-11-02 10:00`）、类型（入金 / 出金；以后会有"期权开仓"等）、金额（流入显示 `+30,000.00`，流出显示 `−2,500.50`）、余额、备注/说明。按时间倒序，分页。没有记录时显示"暂无流水"。
- 金额统一用项目的格式化 util：2 位小数、千分位。
- 本需求不提供编辑、删除按钮。

## 验收标准
除特别说明，以下场景都从空库开始；"现金汇总"指页面上的 总现金 / 冻结 / 可用现金。

1. **空账户**
   **Given** 没有任何记录 **When** 打开资金页面 **Then** 现金汇总为 0.00 / 0.00 / 0.00，流水列表显示"暂无流水"。

2. **入金**
   **When** 入金 30,000.00，时间 2026-11-02 10:00（美东），备注"初始资金"
   **Then** 保存成功；时间存为 `2026-11-02T15:00:00Z`；现金汇总为 30,000.00 / 0.00 / 30,000.00；流水列表第一行为 `2026-11-02 10:00`、入金、`+30,000.00`、余额 `30,000.00`、备注"初始资金"。

3. **出金**
   **Given** 第 2 条之后
   **When** 出金 2,500.50，时间 2026-11-03 09:30（美东）
   **Then** 现金汇总为 27,499.50 / 0.00 / 27,499.50；流水列表第一行为出金 `−2,500.50`、余额 `27,499.50`，第二行为入金那条。

4. **出金超过可用现金**
   **Given** 现金汇总为 27,499.50 / 0.00 / 27,499.50
   **When** 出金 27,499.51
   **Then** 被拒绝，提示中包含需要 27,499.51、可用 27,499.50；已填内容保留；汇总和流水都不变。
   **And When** 出金 27,499.50 **Then** 保存成功，现金汇总为 0.00 / 0.00 / 0.00。

5. **补录历史出金（Q19 重放）**
   **Given** 入金 10,000.00（2026-11-02 10:00）、出金 9,000.00（2026-11-05 10:00），现金汇总 1,000.00 / 0.00 / 1,000.00
   **When** 补录出金 1,500.00，时间 2026-11-03 10:00
   **Then** 被拒绝：2026-11-03 时可用 8,500.00 本身没问题，但 2026-11-05 出金后会变成 −500.00；提示中说明这个时点。
   **When** 补录出金 1,000.00，时间 2026-11-03 10:00 **Then** 保存成功，现金汇总为 0.00 / 0.00 / 0.00。
   **When**（在上一步之前的状态下）补录出金 100.00，时间 2026-11-01 10:00（早于第一笔入金）**Then** 被拒绝，可用 0.00。

6. **补录历史入金，余额重新累计**
   **Given** 入金 10,000.00（2026-11-05 10:00）
   **When** 补录入金 5,000.00，时间 2026-11-02 10:00
   **Then** 保存成功；现金汇总 15,000.00 / 0.00 / 15,000.00；流水列表从上到下为：2026-11-05 入金 `+10,000.00` 余额 `15,000.00`；2026-11-02 入金 `+5,000.00` 余额 `5,000.00`。

7. **同一时间多条记录按录入顺序**
   **Given** 入金 1,000.00，时间 2026-11-03 10:00
   **When** 再录入出金 1,000.00，时间同为 2026-11-03 10:00
   **Then** 保存成功（入金先录入，排在前面）；流水列表第一行为出金 `−1,000.00` 余额 `0.00`，第二行为入金 `+1,000.00` 余额 `1,000.00`。
   **And Given** 空库 **When** 先录出金 1,000.00（2026-11-03 10:00）**Then** 被拒绝（之后再录同一时间的入金也不会让这笔出金"排到后面"）。

8. **时间换算（夏令时 / 标准时间）与默认值**
   **When** 打开入金/出金表单 **Then** 时间默认是当前美东时间（例：当前为 UTC 2026-11-10 14:45 → 显示 2026-11-10 09:45）。
   **When** 入金时间填 2026-07-15 10:30（美东，夏令时）**Then** 存为 `2026-07-15T14:30:00Z`，列表显示 `2026-07-15 10:30`。
   **When** 入金时间填 2026-11-02 10:00（美东，标准时间）**Then** 存为 `2026-11-02T15:00:00Z`。

9. **字段校验**（每一项单独提交都应被拒绝、不保存，错误显示在对应字段）
   - 金额为空、0、−100.00、100.001（3 位小数）
   - 时间晚于当前（例：当前 2026-11-10 09:45 美东，填 2026-11-10 10:00）
   - 未选择类型
   - 备注 501 个字符（500 个字符应允许）

10. **金额精度与显示**
    **When** 入金 0.10，再入金 0.20 **Then** 现金汇总总现金正好显示 `0.30`。
    **When** 入金 1,234,567.89 **Then** 汇总和流水都显示为 `1,234,567.89`。

11. **冻结现金在本需求中为 0**
    **Given** 任意入金/出金组合（如第 2、3 条）**Then** 冻结现金始终显示 0.00，可用现金始终等于总现金。
    冻结大于 0 时的出金限制（出金不能动用冻结现金），在 FEAT-001 完成后由 FEAT-001 验收 14 验证（入金 25,000.00、补录 STO put 冻结 20,000.00 后，出金 5,500.00 被拒绝）。

12. **失败保留输入、成功自动刷新**
    **Given** 第 4 条的拒绝场景 **Then** 表单中类型、金额、时间、备注都保留原值。
    **Given** 第 3 条的成功场景 **Then** 不刷新页面，汇总和流水即显示新值。

13. **分页与排序**
    **Given** 录入 60 条入金，每条 1.00，时间从 2026-09-01 10:00 起每天一条
    **Then** 流水按时间倒序，第一页第一行是最晚那条（余额 `60.00`），最后一页最后一行是 2026-09-01 那条（余额 `1.00`），可以翻页看到全部 60 条。

## 边界情况
- **总现金恰好为 0**：允许（验收 4、5）。
- **补录到最早时间之前**：入金允许；出金因当时可用为 0 被拒绝（验收 5）。
- **同一时间多条记录**：按录入顺序（验收 7）。
- **周末 / 美股假日**：入金/出金不受交易日限制，任何日期都允许。
- **夏令时切换当天**不存在/重复的时刻（如 2026-03-08 02:30、2026-11-01 01:30）：由后端按 Java 时区库的默认规则处理，并在 Implementation Notes 说明。
- **录错了**：本需求不能改/删，用反向记录冲正（例：误入金 1,000.00 → 再录出金 1,000.00，备注"冲正"）；正式的修改/删除和审计在 FEAT-005。
- **利息、券商杂费等**：本需求只有入金/出金两类，可用它们加备注记录（Q26）。

## Out of Scope
- 修改/删除入金出金及审计（FEAT-005，Q24）
- 期权交易产生的流水和冻结（FEAT-001 开仓、FEAT-004 平仓）
- 利息、杂费等单独的现金类型（Q26）
- 流水筛选（按类型/日期）、导出 CSV（FEAT-029）
- 多账户（FEAT-033）、多币种
- 任何券商接入

## Open Questions
无阻塞问题。相关但不阻塞本需求的：
- **Q24** 入金/出金的修改/删除与审计——本需求不做，FEAT-005 再定。
- **Q26** 利息/杂费是否需要单独类型——本需求先用入金/出金 + 备注。

## Implementation Notes
### Backend
**API**（详见 `docs/api/api-contract.md` 的 FEAT-007 小节）
- `GET /api/cash/summary` → `totalCash` / `frozenCash` / `availableCash`（字符串，scale 2）
- `POST /api/cash/transfers` → 新增入金/出金，`occurredAtLocal` 传**美东本地时间**（`YYYY-MM-DDTHH:mm[:ss]`，不带时区），后端换算 UTC；成功 201
- `GET /api/cash/ledger?page=1&size=20` → 流水倒序分页（page 从 1 开始，size 1–100），每条含 `occurredAt`、`sourceType`、`sourceId`、带符号 `amount`、`balanceAfter`、`description`、`remark`
- 现金不足：422 `CASH_INSUFFICIENT`，`message` 为可直接显示的中文，如 `可用现金不足：需要 27,499.51，可用 27,499.50` 或 `可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00`
- 字段错误：400 `VALIDATION_ERROR`，`details[].field` ∈ `type` / `amount` / `occurredAtLocal` / `remark`，message 为中文；值无法解析（非法枚举、非数字、时间格式错）为 400 `BAD_REQUEST`，`details` 同样指出字段

**文件**
- 新增 `backend/src/main/resources/db/migration/V2__cash_account.sql`（`cash_account`、`cash_transfer`、`cash_ledger_entry`）
- 新增 `backend/src/main/java/com/optionmgr/cash/`：`domain/`（`CashMovement`、`CashBalance`、`CashReplay`、`ReplayStep`、`InsertionCheck`、`CashShortfallMessages`、`CashSourceType`、`TransferType`）、`repository/`（3 个实体 + repository）、`service/`（`CashLedgerService`、`CashTransferService`、`NewLedgerEntry`、`LedgerLine`、`LedgerPage`）、`web/`（`CashController`、`CashDtos`）
- 新增 `common/money/Money.java`、`common/time/MarketTime.java`
- 修改 `common/error/ErrorCode.java`（加 `CASH_INSUFFICIENT` 422）、`common/error/GlobalExceptionHandler.java`（请求体字段值无法转换时 `BAD_REQUEST` 的 `details` 指出字段）
- 测试：`cash/domain/CashReplayTest`、`cash/web/CashControllerTest`（验收 1–11、13 的后端部分，固定时钟 2026-11-10T14:45Z）、`common/money/MoneyTest`、`common/time/MarketTimeTest`
- 文档：`docs/api/api-contract.md`、`docs/architecture.md`（"现金流水模型"）

**设计决定**
- 统一流水表 `cash_ledger_entry`：`source_type` + `source_id` 关联原始记录；`amount`（带符号现金变动）+ `frozen_change`（带符号冻结变动）。入金/出金 `frozen_change = 0`；FEAT-001 STO put 写一条 `+净额 / +担保` 的流水即可，汇总和重放检查不用改
- 余额、冻结、可用、每条的 `balanceAfter` 都不落库，读取时按 `(occurred_at, id)` 重放（`id` = 录入顺序），补录后自然重算
- 出金检查：把本笔放在同一时间已有记录之后插入重放，本笔及之后每条流水之后 总现金 ≥ 0 且 可用 ≥ 0；入金不检查（只增加现金）
- 提示文案：本笔时点就不够 → "需要 X，可用 Y"（Y = 本笔之前的可用）；本笔够但之后某时点变负 → "需要 X；T 时可用现金将变为 Z"（取第一个变负的时点，T 为美东 `YYYY-MM-DD HH:mm`）
- 并发：所有改现金的事务先锁 `cash_account` 唯一一行（`SELECT ... FOR UPDATE`），检查与写入串行
- 时间：后端接收美东本地时间并换算（而不是前端换算），保证 DST 规则只在后端一处。夏令时开始当天不存在的时刻（如 2026-03-08 02:30）按 java.time 默认规则顺延 1 小时 → 03:30 EDT（`07:30Z`）；结束当天重复的时刻（如 2026-11-01 01:30）取较早的 EDT（`05:30Z`）。只接受到分或秒，不接受小数秒和时区后缀；按 STRICT 规则解析，不存在的日期（如 2026-02-30）返回 400 BAD_REQUEST 而不是被改成月末（BUG-001，`MarketTime.parseLocal` + `MarketLocalDateTimeDeserializer`，FEAT-001 复用）。"不得晚于当前时间"按换算后的 Instant 与服务器时钟比较，等于当前允许
- 金额：`NUMERIC(19,2)`，入参最多 2 位小数、整数部分最多 13 位（≤ 9,999,999,999,999.99），统一 `setScale(2, UNNECESSARY)`；`"30000"` 会规范成 `"30000.00"`
- 备注：最多 500 个字符（按 Java `String.length()`，即 UTF-16 单元；中文每字算 1，emoji 算 2）；空串/全空白保存为 `null`
- 入金/出金流水的 `description` 为 `null`（说明字段留给系统生成的文本，如以后的交易流水）；用户备注在 `remark`

**FEAT-001 接入方式**（同一事务内）：`CashSourceType` 加值 → `cashLedgerService.checkInsertion(...)`（加锁 + 重放，Q18 用 `check.inserted().before().available()` 做录入前担保检查）→ 保存交易 → `cashLedgerService.append(new NewLedgerEntry(..., tradeId, ±净额, 担保, 说明, 备注))`。

**已知限制**
- 每次汇总/流水/检查都加载全部流水在内存中重放；个人系统数据量下没问题，以后可加快照
- 没有 `GET /api/cash/transfers/{id}`（本需求不需要）；修改/删除留给 FEAT-005
- 流水列表暂不返回 `frozenChange`；FEAT-001 如需在流水中显示冻结变化，可兼容地新增字段
### Frontend
**页面 / 组件**
- 路由 `/cash`（`src/pages/CashPage.tsx`），顶部导航"资金"（`nav-cash`）
- `src/components/cash/CashSummaryCard.tsx`：**可复用**现金汇总（总现金 / 冻结现金 / 可用现金），自带查询 `GET /api/cash/summary`，可传 `title`、`size`；FEAT-001 录入表单直接 `<CashSummaryCard size="small" />` 即可，只要提交后 invalidate `cashQueryKeys.all` 就会刷新
- `src/components/cash/CashTransferForm.tsx`：入金/出金表单（类型无默认值、金额、时间、备注）；校验函数在 `cashTransferValidation.ts`
- `src/components/cash/CashLedgerTable.tsx`：流水表格 + 分页（每页 20，page 由页面控制，保存成功后回到第 1 页）
- `src/components/QueryError.tsx`：查询失败的错误提示 + 重试按钮
- API：`src/api/cash.ts`（`getCashSummary` / `getCashLedger` / `createCashTransfer`、`cashQueryKeys`），类型在 `src/api/types.ts`
- 格式化：`src/utils/format.ts` 新增 `formatMoney`（字符串处理，千分位、2 位小数、负号 U+2212、可选 `+`，不转浮点、不四舍五入）、`decimalSign`（只判断正负用于颜色）、`formatDateTimeNY`（美东 `YYYY-MM-DD HH:mm`）、`nowLocalDateTimeNY`（表单默认值）

**行为**
- 时间用原生 `<input type="datetime-local">`（antd `Input`），值即 `YYYY-MM-DDTHH:mm` 美东本地时间，原样作为 `occurredAtLocal` 发送，前端不换算 UTC；默认当前美东时间（按浏览器时钟 + America/New_York 计算，与浏览器本机时区无关）
- 前端校验：类型必选；金额必填、> 0、最多 2 位小数、整数部分 ≤ 13 位（允许输入千分位逗号，提交前去掉）；时间必填且不晚于当前美东时间（分钟精度，以后端为准）；备注 ≤ 500（按 `String.length`，与后端一致）
- 后端错误：`VALIDATION_ERROR` / `BAD_REQUEST` 的 `details[].field`（type/amount/occurredAtLocal/remark）显示在对应字段下；其它错误（如 `CASH_INSUFFICIENT`）原文显示在保存按钮上方（`cash-transfer-error`）。失败时保留全部输入
- 成功：提示"入金已保存/出金已保存"，表单重置（时间重置为当前美东时间），invalidate `["cash"]` → 汇总与流水自动刷新
- 流水：金额正数绿色带 `+`、负数红色带 `−`；类型 DEPOSIT→入金、WITHDRAWAL→出金，不认识的 `sourceType` 原样显示；说明/备注列显示 `description` 和 `remark`（都有时用" — "连接，都没有显示 `—`）；空时显示"暂无流水"

**data-testid**（E2E 用）
- 汇总：`cash-summary`、`cash-summary-total`、`cash-summary-frozen`、`cash-summary-available`、`cash-summary-loading`、`cash-summary-error`、`cash-summary-retry`
- 表单：`cash-transfer-form`、`cash-transfer-type`、`cash-transfer-type-deposit`、`cash-transfer-type-withdrawal`、`cash-transfer-amount`、`cash-transfer-time`、`cash-transfer-remark`、`cash-transfer-submit`、`cash-transfer-error`。字段错误显示在该输入所在的 `.ant-form-item` 的 `.ant-form-item-explain-error` 里
- 流水：`cash-ledger`、`cash-ledger-row`（每行），行内 `cash-ledger-time` / `-type` / `-amount` / `-balance` / `-note`；`cash-ledger-empty`、`cash-ledger-pagination`（内含 antd Pagination，页码按钮 aria-label 为 `Page N`，另有 `Previous Page` / `Next Page`）、`cash-ledger-total`（"共 N 条"）、`cash-ledger-error`、`cash-ledger-retry`
- 导航：`nav-cash`

**测试**：`src/pages/CashPage.test.tsx`（mock fetch 覆盖验收 1、2、4、8、9、12、13 的前端部分及流水显示/错误重试）、`src/components/cash/cashTransferValidation.test.ts`、`src/utils/format.test.ts`、`src/App.test.tsx`（导航到 /cash）

**已知限制**
- "时间不晚于当前"的前端检查依赖浏览器时钟、分钟精度，只是提示；最终以后端为准
- 原生 datetime-local 的显示格式随浏览器语言（如 `11/10/2026 10:50 PM`），但提交值固定为 `YYYY-MM-DDTHH:mm`；E2E 用 `fill("2026-11-02T10:00")` 即可
- 夏令时切换当天不存在/重复的时刻前端不提示，由后端按契约规则处理
- 分页器文字为英文（全局 antd locale 为 en_US）
- 构建产物单 chunk 约 1.1 MB（Vite 提示 >500 kB），以后页面多了再做按路由拆包
