# 架构说明

## Backend（backend-dev 维护）

### 版本与构建
- Java 21（Gradle toolchain，Temurin），Spring Boot 3.5.16，Gradle 8.14.6（wrapper，Kotlin DSL），springdoc-openapi 2.8.17，Flyway（Boot 管理版本），H2
- 本机需安装 JDK 21（Temurin 21），系统 `JAVA_HOME` 指向它；Gradle toolchain 使用本机已安装的 JDK 21，不自动下载
- `gradlew.bat build` = 编译 + 全部测试 + bootJar

### 包结构（根包 `com.optionmgr`）
按功能（feature）分包，每个功能包内部再分层：
```
com.optionmgr
├── OptionMgrApplication
├── config/            全局配置：ClockConfig（唯一的 Clock bean）、JacksonConfig（BigDecimal→字符串）、DatabaseBackupBeforeMigration（迁移前备份，Q60）
├── account/           FEAT-033 账户：/api/accounts、X-Account-Id 请求头 → AccountRef（见下"多账户"）
├── common/error/      统一错误：ErrorCode、ErrorResponse、ApiException、GlobalExceptionHandler
├── common/money/      Money：金额 scale 2、RoundingMode.UNNECESSARY、给用户看的金额格式（千分位、U+2212 负号）
├── common/time/       MarketTime：美东本地时间 ↔ UTC Instant（America/New_York，java.time 默认 DST 规则）
├── health/            GET /api/health
├── cash/              FEAT-007 资金账户：入金/出金、现金汇总、统一现金流水（见下"现金流水模型"）
├── trade/             期权交易：开仓 FEAT-001、列表 FEAT-002、持仓 FEAT-003、平仓 FEAT-004、修改/删除 FEAT-005（见下"期权交易与持仓模型"）
├── stock/             FEAT-011 股票交易与股票持仓（见下"股票交易与股票持仓"）
├── audit/             FEAT-005 审计记录：只追加，GET /api/audit-log（见下"修改、删除与审计"）
├── quote/             FEAT-016 自动行情（CBOE 延迟报价 + Finnhub 股价兜底、手动覆盖，见下"自动行情"）
├── valuation/         FEAT-017 未实现盈亏与市值（见下"未实现盈亏"）
├── testsupport/       仅 `e2e` profile：POST /api/test-support/reset（见下"E2E 测试支持"）
└── <feature>/         以后的功能，例如 position/
    ├── web/           @RestController + 请求/响应 DTO（record），只做参数校验和映射
    ├── service/       事务边界、编排
    ├── domain/        纯 Java 领域模型与计算（盈亏、成本、到期处理），不依赖 Spring，可直接单元测试
    └── repository/    Spring Data JPA 接口与实体
```
规则：controller 不直接用 repository；domain 不 import `org.springframework.*`；金额一律 `BigDecimal` 并显式指定 scale 与 `RoundingMode`。

### 配置
- `src/main/resources/application.yml`：端口 8080；H2 文件库 `backend/data/optionmgr.mv.db`（相对运行目录，`data/` 被 git 忽略；`MODE=PostgreSQL` 方便以后迁移），`AUTO_SERVER=TRUE` 允许外部工具同时连接
- 测试：`build.gradle.kts` 为所有测试设置 `spring.profiles.active=test`，`application-test.yml` 换成内存库 `jdbc:h2:mem:optionmgr-test`，不碰开发数据。QA 的 `systemtest/` 同样适用
- E2E：`application-e2e.yml`（profile `e2e`）内存库 `jdbc:h2:mem:e2e` + 重置端点，见下"E2E 测试支持"
- JPA `ddl-auto: validate`，`open-in-view: false`。**schema 只由 Flyway 管理**：`src/main/resources/db/migration/V<n>__<desc>.sql`，只新增，不修改已有迁移。`V1__init.sql` 是空基线
- 时间：`Clock` bean（UTC）是"现在"的唯一来源，测试可替换（`e2e` profile 下为可设置的 `SettableClock`，FEAT-009）；JSON 时区 UTC；Hibernate JDBC 时区 UTC
- JSON：`BigDecimal` 输出为普通写法字符串（`JacksonConfig`）；`Instant`/`LocalDate` 输出 ISO-8601 字符串
- OpenAPI：`/v3/api-docs`，Swagger UI：`/swagger-ui.html`，只收录 `/api/**`

### 现金流水模型（FEAT-007，供 FEAT-001/004/005 及 M2 接入）
- 表（`V2__cash_account.sql`）：
  - `cash_account`：唯一一行（id=1，USD）。所有改变现金的事务先 `SELECT ... FOR UPDATE` 锁这一行，保证"重放检查 + 写入"串行
  - `cash_transfer`：用户录入的入金/出金原始记录（金额为正）
  - `cash_ledger_entry`：**所有来源**的统一现金流水。`amount` 带符号的现金变动；`frozen_change` 带符号的冻结变动（入金/出金为 0）；`source_type` + `source_id` 指向原始记录；`description` 系统生成说明；`remark` 用户备注；`id` = 录入顺序
- 余额不落库：总现金、冻结、可用、每条之后的余额都在读取时按 `(occurred_at, id)` 重放计算（`cash/domain/CashReplay`，纯 Java）
- Q19 检查：`CashReplay.checkInsertion(existing, CashMovement.pending(...))` 把新变动放在同一时间已有记录之后，从它起每一步之后 总现金 ≥ 0 且 可用 ≥ 0，返回 `InsertionCheck`（插入点之前的余额、第一个违规点）
- 新来源接入步骤（都在调用方的同一个 `@Transactional` 里，`CashLedgerService` 的这些方法要求已有事务）：
  1. 在 `CashSourceType` 加枚举值（如 `OPTION_OPEN`）
  2. `cashLedgerService.checkInsertion(occurredAt, cashChange, frozenChange)`（会加锁），不通过就抛 `ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.insufficient(required, check))`；需要额外前置检查（如 Q18 STO put"录入前可用 ≥ 担保"）时用 `check.inserted().before().available()`
  3. 保存原始记录（交易）
  4. `cashLedgerService.append(new NewLedgerEntry(occurredAt, sourceType, tradeId, cashChange, frozenChange, description, remark))`
- 已知限制：每次查询/检查都加载全部流水（个人系统数据量小，可接受）；以后数据量大时可改为快照 + 增量
- `CashShortfallMessages.insufficient(prefix, required, check)` / `notEnoughNow(prefix, required, available)` 支持不同前缀（FEAT-001 STO put 用 `可用现金不足以担保卖出 put`）；`CashLedgerService.findBySource(sourceType, sourceId)` 按原始记录找流水；流水 API 输出 `frozenChange`

### 期权交易与持仓模型（FEAT-001，供 FEAT-002/003/004/006 接入）
- 表 `option_trade`（`V3__option_trade.sql`）：一行一笔交易（用户录入的原始记录）。合约 = `symbol` + `expiration_date` + `strike`（`NUMERIC(10,3)`，按数值比较）+ `option_type`（`CALL`/`PUT`）；`action` ∈ `BTO`/`STO`/`BTC`/`STC`（FEAT-001 只写 BTO/STO，约束已允许平仓值）；`contracts`、`multiplier` 正整数；`net_amount` 正数（含手续费净额）；`traded_at` UTC；`id` = 录入顺序。同一合约多次开仓是多行（以后 FIFO 批次）
- 现金影响不存在交易表里：每笔交易在同一事务里写一条 `cash_ledger_entry`（`OPTION_OPEN`，`source_id` = 交易 id，`amount` = ±净额，`frozen_change` = STO put 担保）。两表之间没有外键（`source_id` 是多态引用）
- 包 `trade/`：
  - `domain/`（纯 Java）：`ContractKey`（合约标识 + 显示名 `AAPL 2026-12-18 200 P`，strike 去尾零）、`Symbols`（标的规范化）、`OptionType`、`TradeAction`（方向符号、是否开仓）、`OpenTradeTerms`（现金变动、担保 = 行权价 × multiplier × 张数 按分向上取整、流水说明 `describe`）、`LotTrade` / `ContractReplay` / `ContractHistory` / `ReplayedTrade` / `ViolationKind` / `CloseMatch`（合约重放：按时点持仓、FIFO 批次、整笔平仓匹配）、`PositionLot` / `OptionPosition` / `PositionBuilder`（当前持仓）、`TradeRejectionMessages`（中文拒绝原因）
  - `service/OptionTradeService`：一个事务内按需求顺序检查 → 保存交易 → 追加流水（→ 改正之后平仓的释放冻结，见 Q39）
  - `web/`：`OptionTradeController`（`POST /api/option-trades` 开仓、`POST /api/option-trades/closes` 平仓、`GET /api/option-trades`、`GET /api/option-trades/{id}`）、`OptionTradeDtos`、`@OpeningAction` / `@ClosingAction` 校验
- **合约重放（`ContractReplay`，FEAT-001/003/004 共用）**：持仓不落库。某合约的全部交易按 `(traded_at, id)` 重放：开仓（BTO/STO）成为一个批次（`PositionLot`）；平仓（BTC/STC）必须找到相反方向的持仓、不超过持仓、且**正好覆盖最早 1 笔或连续多笔整批次**（Q21），移除这些批次并得到 `CloseMatch`（被平批次、释放冻结 = 批次冻结之和、已实现盈亏 = 批次开仓净额之和 + 本笔现金变动）。每步记录之前的持仓、multiplier、可平张数（批次累计张数）和违规（`ViolationKind`：开仓多空冲突 Q7、multiplier 不一致 Q23、平仓无持仓 / 方向不对 / 超量 / 非整笔批次）。持仓回到 0 时 multiplier 清空，可以换方向和 multiplier 重新开仓
- 写入时把新交易（id = `LotTrade.NOT_YET_ENTERED`，排在同一时间已有交易之后）加入重放，用 `ContractHistory.firstViolationFrom` 找**从新交易起**的第一个违规（Q27/Q39：之后的已有交易也必须仍然合法）。开仓的检查顺序：非 multiplier 违规 → multiplier 违规 → 现金（FEAT-038 起不再有 Q16：STO call 允许；只有 STO put 冻结担保并做 Q18，见 `OpenTradeTerms.freezesCollateral`）；平仓：任一违规（按时间先后）→ 现金
- 并发：交易事务先调 `CashLedgerService.lockAccount()`（与入金/出金同一把 `cash_account` 行锁），所以重放检查、现金检查和写入都串行
- **平仓（FEAT-004）**：平仓 multiplier 取成交时点持仓的 multiplier（请求不带）；流水来源 `OPTION_CLOSE`，`amount` = BTC −净额 / STC +净额，`frozen_change` = −释放冻结。现金检查把"释放冻结 + 扣现金"作为同一笔变动插入现金重放（Q20：先释放再检查）
- **Q39 的存储选择**：被平批次和已实现盈亏**不落库**，每次读取（按 id、列表、持仓）时重放该合约得出，补录更早的交易后自动按新的 FIFO 匹配，不需要改写任何记录。释放的冻结**落在流水的 `frozen_change`**，因为现金重放（`CashReplay`）只认流水、不认识交易。写入新交易（开仓或平仓）时，比较重放后每笔已有平仓的释放冻结与其流水的 `frozen_change`，不同的在同一事务里改正（`CashLedgerService.correctFrozenChanges`），现金检查也用改正后的值（`checkInsertion(..., frozenOverrides)`）。同一合约所有批次单价相同，只有批次冻结的 Q28 取整差异（3 位小数行权价、multiplier 不是 10 的倍数）才会让释放额变化；流水的 `amount` 永远不变。这样现金汇总、流水与持仓冻结合计始终一致
- 已知限制：每次写入加载该合约的全部交易和全部现金流水重放；读取平仓时按合约重放（个人数据量小）
- **交易列表（FEAT-002）**：`GET /api/option-trades`。`web/TradeListQuery` 解析/校验 query 参数（无法解析 → `BAD_REQUEST`，规则不满足 → `VALIDATION_ERROR`，`field` = 参数名）→ `domain/TradeFilter`（纯 Java；美东日期范围换算为 `[startDate 00:00 NY, endDate+1 00:00 NY)` 的 Instant 区间）→ `repository/OptionTradeSpecifications`（JPA Specification）→ 数据库分页，固定排序 `traded_at DESC, id DESC`。列表项与按 id 查询共用 `OptionTradeService.views`：一页的交易一次批量查出对应流水（`CashLedgerService.findBySources`），取 `cashChange`/`frozenChange`/`ledgerEntryId`
- **持仓汇总（FEAT-003）**：`GET /api/option-positions`（`web/OptionPositionController` → `service/OptionPositionService`）。不落库：读出全部交易（`traded_at, id` 顺序）和对应流水，按合约分组，`domain/PositionBuilder` 把每个合约的交易按 FIFO（`LotTrade.FIFO_ORDER`）重放为 `OptionPosition`（方向、multiplier、`PositionLot` 列表；张数/开仓净额合计/每张平均（HALF_UP）/冻结都由批次求和得出），按 `OptionPosition.DISPLAY_ORDER`（到期日 → 标的 → 行权价数值 → CALL 在 PUT 前）排序。批次的开仓净额与冻结**取自该笔交易的现金流水**（`amount`、`frozen_change`），所以持仓冻结合计与现金汇总的冻结一致。"今天" = `Clock` 当前时间的美东日期，DTE = `ChronoUnit.DAYS.between(today, expiration)`
- `PositionLot` 有原值/剩余值（张数、净额、冻结）；整笔平仓规则（Q21）下剩余值总等于原值，批次被平即整体移除。`PositionBuilder.build` = `ContractReplay` 的 `openLots`；`OptionPosition` 另给出 `closeAction`、`allowedCloseContracts`、`closable(now)`（FEAT-012 起：当前时间 < 到期日美东 16:00，Q83）、`expiryPending(now)`、`expirable(now)`
- 交易对应的流水来源类型集中在 `OptionTradeService.TRADE_SOURCE_TYPES`（`{OPTION_OPEN, OPTION_CLOSE, OPTION_EXPIRE}`）；以后新的交易来源（如指派）加到这里。列表项和按 id 查询的平仓带 `coveredLots`、`realizedPnl`（同一页按合约各重放一次）
- **已平仓记录（FEAT-006）**：`GET /api/closed-trades`（`web/ClosedTradeController` → `service/ClosedTradeService`）。筛选复用 FEAT-002 的 `TradeListQuery`（`action` 只允许 BTC/STC）和 `OptionTradeSpecifications`。因为合计要覆盖全部筛选结果（Q46），每次请求取出**全部**符合条件的平仓（按 `traded_at DESC, id DESC`），用 `OptionTradeService.views` 按合约重放得到被平批次和盈亏（每个合约一次），算合计后在内存中分页（FEAT-040 起与股票卖出合并后再分页，见下）。`domain/ClosedTrade` 给出开仓净额合计、每张平均（HALF_UP，Q22）、平仓净额与每张平均、盈亏。个人系统数据量小，每次请求重放所有涉及的合约可以接受；数据量大时可改为缓存匹配结果
- **盈亏汇总与首页概览（FEAT-008）**：`GET /api/pnl-summary`、`GET /api/dashboard`（`web/PnlSummaryController` → `service/PnlSummaryService`）。不另算盈亏：平仓与盈亏来自 `ClosedTradeService.all(filter)`（与 `/api/closed-trades` 同一份；FEAT-040 起含股票卖出并给出期权 / 股票拆分），`domain/PnlSummary` 按标的（字符序升序）和平仓的美东自然月（倒序，无平仓的月份不出现）分组；首页的持仓数字来自 `OptionPositionService.currentPositions()`，现金来自 `CashLedgerService.currentBalance()`，所以各接口的数字天然一致。"今天 / 本月" = 注入 `Clock` 的美东日期 / 月；本月 = `startDate = 本月 1 日` 的平仓（不会有晚于当前的交易）。日期参数复用 `TradeListQuery.parse`（同样的错误格式）。每次请求重放所有涉及的合约，个人数据量下可以接受

### 到期作废（FEAT-012）
- **存储**：作废记录是 `option_trade` 的一行，`action = 'EXP'`（`TradeAction.EXP`，sign 0、不是开仓），与交易**共用 id**（= 录入顺序），所以"同一时间按录入顺序"的重放、交易列表、按 id 查询、删除接口、审计（`OPTION_TRADE`）、`has_audit_log` 都直接复用。`net_amount = 0`、`note = NULL`、`traded_at` = 到期日美东 16:00（cutoff）、`multiplier` = 持仓的、`contracts` = 作废时 cutoff 时点的持仓张数。迁移 `V7__expire_worthless.sql` 只放宽 `ck_option_trade_action`（加 `EXP`）和 `ck_option_trade_net`（`EXP` 必须为 0，其它仍 > 0）
- **现金**：释放冻结 > 0（空头 put）时才写一条流水 `OPTION_EXPIRE`（`amount` 0、`frozen_change` = −释放额、时间 = cutoff）；空头 call / 多头没有流水。因此 `OptionTradeService.TradeView.ledgerEntry` 和 `ContractTrades.ledgerByTrade` 对没有流水的 `EXP` 为空（`OptionTradeService.entry` 只对 `EXP` 允许缺失，其它交易缺流水仍抛异常）；响应的 `ledgerEntryId` 为 `null`、`cashChange` / `frozenChange` 为 0。`frozenCorrections` 把 `EXP` 当作平仓一起改正（Q39），没有流水的 `EXP` 期望释放额必为 0（同一合约的批次方向/类型不变，否则抛 `IllegalStateException`）
- **重放**：`ContractReplay` 不需要改：`EXP` 走平仓分支（有持仓 / 不超量 / 整笔批次 / multiplier 一致），因为 sign 为 0，"方向不对"永远不成立，所以空头、多头都能作废；盈亏 = 批次净额之和 + 0。`ClosedTrade.closedDirection` 对 `EXP` 取被作废批次的方向
- **时间**：`trade/domain/ExpirationTimes`（纯 Java）：`tradingCutoff`（美东 16:00）、`expirableFrom`（美东 17:30）、`closable` / `expiryPending` / `expirable`（按注入 `Clock` 的当前时间）、`afterCutoff`（16:00:00 允许）。开仓 / 平仓在字段校验阶段用 `OptionTradeService.checkNotAfterCutoff`；修改只在成交时间被改动时检查（Q85）
- **作废操作**：`service/OptionExpiryService.expire`（`POST /api/option-trades/expirations`）：17:30 检查 → 加账户锁 → `domain/ExpiryReplay.lotsAtCutoff`（只重放 ≤ cutoff 的记录）→ 张数核对 → `firstLateTrade`（旧的 16:00 之后交易）→ 把 `EXP`（id = `NOT_YET_ENTERED`，排在同一时间已有记录之后）加入合约重放取 `CloseMatch` → 现金插入检查 → 保存记录 + 可能的流水。不写审计
- **修改 / 删除**：`OptionTradeEditService.update` 对 `EXP` 直接 422 `EXPIRY_NOT_EDITABLE`；删除走通用删除（流水可能不存在），审计摘要 `到期作废 <合约> <张数> 张`。修改/删除一笔被 `EXP` 覆盖的开仓导致该 `EXP` 违规时用 `TradeRejectionMessages.expiredOpenChanged`（"该开仓被 … 的到期作废 N 张覆盖（…），请先删除该到期作废"）；其它让 `EXP` 违规的改动用 `EXP` 专用的"…的到期作废 N 张将…，请先删除该到期作废"。审计连带变化 `SideEffect.text()` 对 `EXP` 为 `到期作废 #id 冻结变动 …`
- **持仓**：`OptionPositionService.PositionSummary` 带 `now` 和每个持仓的 `expiryContracts`（`ExpiryReplay.contractsAtCutoff`）；控制器输出 `closable` / `expiryPending` / `expirable` / `expiryContracts` / `expiryAt(Local)`
- **已平仓、汇总**：`ClosedTradeService.CLOSING_ACTIONS` = BTC、STC、EXP，所以已平仓、盈亏汇总、首页自动计入（按 cutoff 时间归属）

### 到期批量作废（FEAT-015）
- 无迁移、无新记录类型：`OptionExpiryService.expire` 拆成 `check`（FEAT-012 检查 2–5，抛 `ApiException`）和 `save`（重放取 `CloseMatch` → 现金检查 → 保存 `EXP` + 可能的流水 → 覆盖纳入），单个作废 = 加锁 + `check` + `save`
- `expireAll`（`POST /api/option-trades/expirations/batch`，一个事务）：加账户锁 → 按 `PROCESSING_ORDER`（到期日 → 标的 → 行权价数值 → CALL 在 PUT 前，= Q35）对每项 `check`，捕获 `ApiException` 收集为 `BatchFailure`（请求下标 + 原 code / message）→ 有失败就抛 `BatchExpirationRejectedException`（什么都没写）→ 否则按同一顺序逐项 `save`（id 递增，同一 cutoff 按 id 重放即处理顺序）。各项合约互不相同，所以先全部检查再保存与逐项执行等价
- 请求校验在 `web/BatchExpireValidation`（不用 `@Valid`）：对每项用 `jakarta.validation.Validator` 校验 `ExpireRequest`，字段名加前缀 `items[i].`，再按规范化后的 `ContractKey` 查重，全部问题一次返回 `VALIDATION_ERROR`
- 422 的 `failures` 扩展字段由 `web/BatchExpirationErrorHandler`（`@RestControllerAdvice`，最高优先级）输出，统一错误格式的其它字段不变

### 股票交易与股票持仓（FEAT-011）
- **存储**：表 `stock_trade`（`V8__stock_trade.sql`）一行一条用户录入的股票记录：`type` ∈ `BUY` / `SELL` / `TRANSFER_IN`，`symbol`（规则同期权，`trade/domain/Symbols`）、`shares`（1–10,000,000）、`amount`（正数，含手续费：买入实付 / 卖出实收 / 转入总成本）、`traded_at`（UTC；转入 = 该日美东 00:00，Q86，`stock/domain/StockTimes`）、`note`、`has_audit_log`、`account_id`（外键）。`id` = 录入顺序（独立序列）。V8 同时把 `ck_audit_object_type` 放宽为含 `STOCK_TRADE`
- **现金**：`BUY` / `SELL` 在同一事务里写一条流水（`CashSourceType.STOCK_BUY` / `STOCK_SELL`，`source_id` = 股票记录 id，`amount` = −实付 / +实收，`frozen_change` 0，说明如 `买入 200 股 IXQZ`）；`TRANSFER_IN` 没有流水。所以"按 `(时间, 录入顺序)` 的共同现金重放"不需要新机制：`CashReplay` 只认流水，股票流水自动进入现金汇总、流水余额和所有已有的 Q19 检查（入金/出金、期权开仓 Q18、平仓、作废、各种修改/删除）。股票记录与其流水同一事务写入，所以股票 id 顺序与流水 id 顺序一致
- **包 `stock/`**：
  - `domain/`（纯 Java）：`StockTradeType`（现金方向、流水来源、中文名、说明）、`StockRecord`（重放输入，`NOT_YET_ENTERED` 排在同一时间已有记录之后）、`StockReplay`（按账户 + 标的、`(tradedAt, id)` 重放：买入/转入成为 `StockLot`；卖出按 FIFO 扣股数，整批扣完取剩余成本，部分扣取 `剩余成本 × 股数 ÷ 剩余股数` HALF_UP 到分（Q69/Q87）；卖出超过当时持股记为违规、不应用，继续重放）、`SaleMatch` / `LotSale`（被卖出批次、`soldCost`、`realizedPnl`）、`StockPosition`（持股、总成本、每股平均 HALF_UP 4 位，Q88；`perShare` 也用于每股价格）、`StockMessages`（中文拒绝文案）、`StockTradeFilter`
  - `service/StockTradeService`：新增（字段时间 → 加账户锁 → 卖出的持股重放（含之后的卖出，"补录后……"）→ 买入的现金插入检查 → 保存 + 流水）、按 id、列表（JPA Specification 分页，`traded_at DESC, id DESC`；一页内每个有卖出的标的重放一次）。`checkHoldings(accountId, symbol, change, subjectId, verb)` 供新增/修改/删除共用：把改动应用到该标的全部记录上重放，第一个违规是被改动的那笔 → "持股不足：……"，否则 → "<补录|修改|删除>后……"
  - `service/StockTradeEditService`：修改（按原类型校验时间 → 标的/类型不同 422 → 没有修改 422 → 持股重放 → 现金 `replayWithChanges`（只对有流水的买入/卖出）→ 更新记录与流水（流水 id 不变）、`markAudited`、审计）与删除（持股 → 现金 → 删除流水与记录、审计）。审计对象 `AuditObjectType.STOCK_TRADE`，`sideEffects` 恒为空（股票不冻结）
  - `service/StockPositionService`：读出账户全部股票记录，按标的分组各重放一次，剩余批次非空的为持仓，按标的字符序
  - `web/`：`StockTradeController`（`/api/stock-trades`）、`StockPositionController`（`/api/stock-positions`）、`StockTradeDtos`、`StockTradeListQuery`（同 FEAT-002 的 BAD_REQUEST / VALIDATION_ERROR 规则）、`@StockTradeTimeRequired`（类级约束：按 `type` 要求 `tradedAtLocal` 或 `tradeDate`，错误落在该字段上，与其它字段错误一起返回）
- 批次、被卖出成本、已实现盈亏都**不落库**（同 Q39 的做法），每次读取重放；补录/修改/删除后自动按新 FIFO 匹配，没有需要改正的存储值
- 与期权没有联动：期权的列表、持仓都不读 `stock_trade`；已平仓、汇总、首页从 FEAT-040 起包含股票卖出（见下）
- 已知限制：每次写入加载该标的全部股票记录和账户全部流水重放；列表每页按标的重放（个人数据量小）

### 卖出 put 被指派（FEAT-013）
- **存储（`V9__put_assignment.sql`）**：被指派是 `option_trade` 的一行，`action = 'ASG'`（`TradeAction.ASG`，sign +1 = 只能平空头；`isDeleteOnly()` 与 `EXP` 共用"只能删除"的处理），共用 id，`net_amount = 0`，新列 `assignment_fee`（只有 ASG 有值）。生成的股票批次是 `stock_trade` 一行 `type = 'ASSIGN_BUY'`，新列 `option_trade_id`（唯一、外键）指回 ASG；其 `amount` 存**当前**股票成本，重放使它变化时在同一事务里改正（`OptionTradeService.costCorrections` / `applyCostCorrections`，同 `frozenCorrections` 的做法），所以股票侧的读取、FIFO、持仓都不用改。现金是一条流水 `OPTION_ASSIGN`（`amount` = −(买股金额 + 指派费)，`frozen_change` = −释放冻结）；`toLotTrade` 对 ASG 取现金 0，`ContractReplay` 对 ASG 的盈亏恒为 0（Q8）
- **领域**：`trade/domain/AssignmentTerms`（纯 Java）：股数、买股金额（向上取整到分，Q96）、股票成本、每股成本（4 位）、现金 / 冻结变动、"需要"金额；时间规则 `recordedAt`（美东 16:00）、`latestDate`（Q94）、`dateProblem`；流水说明
- **操作**：`service/OptionAssignmentService.assign`（`POST /api/option-trades/assignments`）：把 ASG（`NOT_YET_ENTERED`）插入合约重放 → 多头 / call → 日期 → 没有持仓（末尾是 EXP 时加"已到期作废"提示）→ 整笔批次与之后记录（`rejectFirstViolation`）→ 股票成本 > 0（本条与被重新匹配的其它 ASG）→ 现金插入检查 → 保存记录 + 流水 + 股票批次。删除走 `OptionTradeEditService.delete`：合约重放 → 其它 ASG 成本 → 股票持股重放（`StockTradeService.checkHoldings`，去掉该批次）→ 现金 → 删除流水、股票批次、记录，一条审计（`before` 加被指派字段）。修改 ASG → 422；修改/删除之前的交易时 ASG 参与重放，成本变化写入审计 `sideEffects`（`field = stockCost`）
- **读取**：`TradeView` 带 `assignedLot`，`assignment()` 按当前匹配算出 `AssignmentTerms`；交易、已平仓响应输出 `assignment`；`CLOSING_ACTIONS` 加 ASG（已平仓、汇总、首页计笔数，金额 0，Q97），已平仓的平仓净额对 ASG 取 0；持仓输出 `assignable` / `assignChoices` / `assignmentDate*`；股票记录输出 `assignmentTradeId` / `editable` / `deletable`，`ASSIGN_BUY` 的 `PUT` / `DELETE` 被 `StockTradeEditService` 拒绝，`POST` 被 `@StockTradeTimeRequired` 拒绝

### 卖出 call 被指派（FEAT-041）
- **存储（`V10__call_assignment.sql`）**：同一种 ASG 记录；生成的是 `stock_trade` 一行 `type = 'ASSIGN_SELL'`（`option_trade_id` 指回 ASG），`amount` 存**当前**卖出实收（行权金额 + 权利金 − 指派费），由 `costCorrections` 在同一事务里改正。流水 `OPTION_ASSIGN`：`amount` = +(行权金额 − 指派费)，冻结 0
- **领域**：`CallAssignmentTerms`（行权金额向下取整到分 Q99、卖出实收、现金变动）；`StockTradeType.isSale()`（`SELL` / `ASSIGN_SELL` 都按 FIFO 卖出，交出成本、盈亏就是 `StockReplay` 的 `SaleMatch`）、`isGenerated()`
- **操作**：`OptionAssignmentService.assign` 第 1–6 步与 put 共用，call 走 `assignCall`：卖出实收 > 0 → 持股（把待存的 `ASSIGN_SELL` 以 `NOT_YET_ENTERED` 插入股票重放，`StockTradeService.checkHoldings` 的本条文案 / 之后卖出 / 之后 call 被指派文案）→ 现金。任何股票重放（股票补录 / 修改 / 删除、删除 put ASG）对之后的 `ASSIGN_SELL` 不成立时给"请先删除该被指派"。交出成本变化：`StockTradeService.assignedSaleCostEffects` 比较改动前后的重放，写进那次审计的 `sideEffects`（`soldCost`）；卖出实收改正写 `proceeds`
- **读取**：`TradeView.assignedSale`（call ASG 的 `SaleMatch`）、`callAssignment()`；`ClosedRecordFilter.stockSales()` 含 `ASSIGN_SELL`（已平仓一次 call 被指派 = 2 行）；持仓 `underlyingShares` 来自 `StockPositionService`（`OptionPositionService` 依赖它）

### 主动行权（FEAT-014）
- **存储（`V12__exercise.sql`）**：行权是 `option_trade` 一行 `action = 'EXR'`（`TradeAction.EXR`，sign −1 = 只能平多头；`isDeleteOnly()`），`net_amount = 0`，行权费存在已有列 `assignment_fee`（约束放宽为 ASG / EXR 非空）。生成的 `stock_trade`：call → `EXERCISE_BUY`（`amount` = 当前股票成本），put → `EXERCISE_SELL`（`amount` = 当前卖出实收），`option_trade_id` 指回 EXR；`stock_trade.type` 加宽到 `VARCHAR(16)`。流水 `OPTION_EXERCISE`（call −(买股金额 + 费)，put +(行权金额 − 费)，冻结 0）
- **"有股票一侧的记录"的统一处理**：`TradeAction.hasStockLeg()`（ASG / EXR）与 `buysStock(optionType)`（ASG put、EXR call 买股；ASG call、EXR put 卖股）。买股一侧都用 `AssignmentTerms`、卖股一侧都用 `CallAssignmentTerms`：两者的权利金取被平批次开仓净额的**现金符号和**，多头批次为负，所以 Q111 的"成本 = 买股 + 权利金 + 费""实收 = 行权金额 − 权利金 − 费"与被指派是同一公式；对外显示的 `premium` 取正。`TradeView.buyLeg()` / `sellLeg()`（原 `assignment()` / `callAssignment()`）、`costCorrections`（`CostCorrection.action`）、`toLotTrade`（现金 0）、`ContractReplay`（盈亏 0）、删除、审计摘要都按 `hasStockLeg` 走；`StockTradeType.isSale()` / `isGenerated()` / `isFromExercise()` 覆盖新类型
- **操作**：`service/OptionExerciseService.exercise`（`POST /api/option-trades/exercises`）：插入合约重放 → 空头 → 日期（`AssignmentTerms.dateProblem("行权日期", …)`）→ 没有持仓（EXP 提示）→ 整笔批次与之后记录 → call：其它记录成本 → 现金；put：实收 > 0 → 持股（`checkHoldings`，本条"持股不足：行权需要…"）→ 可用股数（`CoverageService.checkSale(…, exercise = true)`，Q113）→ E3（前缀"行权"）→ 现金。之后的 `EXERCISE_SELL` 在股票重放中不成立时文案"请先删除该行权"（`StockMessages.laterRecordExceedsHolding`）
- **读取**：交易响应 `exercise`（EXR 时 `assignment` 为 `null`）；已平仓 `exercise` / `exerciseTradeId`，`CLOSING_ACTIONS` 与 `ClosedRecordFilter.stockSales()` 含 EXR / `EXERCISE_SELL`；持仓 `exercisable` / `exerciseChoices` / `exerciseDate*`、多头 call 的 `availableCash`（`CashLedgerService.currentBalance`）、多头 put 的 `underlyingShares` / `availableShares`（覆盖时间线当前值）；股票记录 `exerciseTradeId`，`PUT` / `DELETE` → `STOCK_TRADE_FROM_EXERCISE`

### covered call 股票覆盖（FEAT-039）
- **存储（`V11__call_coverage.sql`）**：`option_trade.coverage_status`（STO call：`CHECKED` / `LEGACY`，其它 `NULL`；迁移把已有 STO call 标 `LEGACY`）；`option_trade` / `stock_trade` 共用录入顺序 `entry_seq`（序列 `entry_seq_gen`，DB 默认值，实体 `insertable = false`；本事务刚插入的记录读出来是 `null`，按"最后录入"处理）
- **领域**：`trade/domain/CoverageTimeline`（纯 Java）：按 `(时间, entry_seq)` 排列股票增减、空头 call 开仓 / 释放（BTC、EXP 在各自位置；call ASG 的释放与它的 `ASSIGN_SELL` 同一位置、先释放后交股），只计 `CHECKED` 批次；`before` / `current` / `firstShortfall` / `inclusions`（Q101 自动纳入）/ `legacyShortfall`；`CoverageMessages`（E1 / E2 / E3 文案）
- **操作**：`trade/service/CoverageService` 从库里建某账户某标的的时间线，可叠加一次"拟议变更"（股票记录列表变换、某个 call 合约的交易列表、视为 CHECKED 的批次、新的 call 被指派），提供 `checkCallOpen`（E1）、`checkSale`（E2）、`checkInvariant`（E3）与 `includeLegacy`（写成功后同一事务内自动纳入，返回审计 `sideEffects` 项 `coverage`）。各写服务在原有检查之后、现金检查之前调用；启动时 `CoverageStartupInclusion` 对所有账户跑一次纳入
- **读取**：`OptionPositionService` 给空头 call 行算 `RowCoverage` / `LotCoverage`；`StockPositionController` 用 `CoverageService.coveredShares`
- **测试**：e2e 用 `POST /api/test-support/legacy-option-trades` 预置 LEGACY；后端测试源里有 `testsupport/LegacyShortCallTestController`（只在测试 classpath），早期功能的测试用它录入没有股票的 STO call

### 股票已实现盈亏并入已平仓、汇总、首页（FEAT-040）
- 只读、无迁移、不落库：股票卖出的被卖出批次、成本和盈亏直接取 FEAT-011 的重放结果（`StockTradeService.views` → `SaleMatch`），不另算
- **领域（`trade/domain/`，纯 Java）**：`ClosedRecordType`（`OPTION` / `STOCK`）；`ClosedRecordKey`（type + id + 时间，`NEWEST_FIRST`：时间倒序 → 同一时间股票在上（Q92）→ 同类 id 倒序）；`ClosedRecordFilter`（期权的 `TradeFilter` + type；`includesOptions` / `includesStocks`：设了到期日、行权价、C/P、方向任一项就不含股票（Q90），`stockSales()` 转成标的 + 日期范围 + `SELL` 的 `StockTradeFilter`）；`ClosedStockSale`（把一笔卖出表示成"多头平仓"：开仓净额 = −被卖出成本、平仓净额 = +实收、每股成本 / 每股价格 HALF_UP 4 位（Q88）、批次 `netAmount` = −cost）；`PnlSummary` 的每个分组改为 `Breakdown(all, option, stock)`（Q91）
- **`ClosedTradeService.all(accountId, ClosedRecordFilter)`**：按需取出全部符合条件的期权平仓 / 作废（同 FEAT-006）和全部符合条件的股票卖出（`StockTradeSpecifications` + `StockTradeService.views`，每个有卖出的标的重放一次），合并后按 `ClosedRecordKey.NEWEST_FIRST` 排序；`page` 在合并后的列表上算合计并在内存中分页（所以跨两类的分页正确）。`ClosedRecordView` 是 sealed 接口（`ClosedTradeView` / `StockSaleView`），控制器按类型映射，另一类的专用字段输出 `null`
- **查询参数**：`TradeListQuery.parseClosed` 在 FEAT-006 的参数之外解析 `type`（无法解析时与其它 `BAD_REQUEST` 一起报告）
- **汇总 / 首页**：`PnlSummaryService.summary` 用不带 type 的 `ClosedRecordFilter`，所以与 `/api/closed-trades` 同一份数据；首页另调 `StockPositionService.currentPositions()` 得到 `stockPositions.positionCount`（Q89），期权持仓数字不变
- 已知限制：每次请求重放所有涉及的合约和有卖出的标的（个人数据量小）；`StockTradeService.views` 顺带为卖出查了一次流水 id（已平仓不用），开销可忽略

### 修改、删除与审计（FEAT-005）
- 接口：`PUT` / `DELETE /api/option-trades/{id}`（`service/OptionTradeEditService`），`PUT` / `DELETE /api/cash/transfers/{id}`（`cash/service/CashTransferEditService`），`GET /api/audit-log`（`audit/`）
- 原则：把改动应用到全部历史上重新检查，任何一笔交易或任何时点不成立就整笔拒绝，什么都不写。都在一个事务里，先拿 `cash_account` 行锁
  - 交易：用同一个 id 替换（修改）或去掉（删除）该笔 `LotTrade`，`ContractReplay` 重放整个合约；被修改的那笔本身违规 → "在成交时……"文案，其它交易违规 → "修改后/删除后……"文案（`TradeRejectionMessages.of(..., verb)`）；删除被平仓覆盖的开仓且那笔平仓因此违规 → `deletedOpenStillClosed`
  - 现金：`CashLedgerService.replayWithChanges(replacements, removals)` 用修改后的流水（同一流水 id，即录入顺序不变）重放整个现金流水，`CashReplay.firstViolation` 找第一个总现金/可用 < 0 的时点（Q19）；之后的 STO put 不重查 Q18（Q41），被修改的 STO put 本身在张数/multiplier/时间变化时按 Q18 检查
  - 通过后：交易实体 `update(...)`、流水 `CashLedgerEntryEntity.update(...)`（原地，id 不变）或删除；其它平仓释放冻结的改正（`OptionTradeService.frozenCorrections`）与新增交易时相同，但记入审计的 `sideEffects`（Q42）
  - 修改内容与原记录相同 → 422 `NO_CHANGES`，不写任何东西
- 审计表 `audit_record`（`V4__audit_record.sql`）：只追加（实体 `@Immutable`，没有修改/删除接口）。`before_json` / `after_json`（完整内容）、`changes_json`（显示用的字段变化）、`side_effects_json`（连带变化）以 JSON 文本存 `VARCHAR(10000)`，读取时原样作为 JSON 对象返回。`AuditService.record` 要求在调用方事务里（`Propagation.MANDATORY`），与改动一起提交或回滚
- 审计对象被删除后仍可在审计记录里按 `objectType` + `objectId` 查到（不设外键）
- 列表行的 `hasAuditLog`（交易列表与单笔交易响应、已平仓、现金流水的入金/出金行）：存在记录上的标记列 `option_trade.has_audit_log` / `cash_transfer.has_audit_log`（`V6__has_audit_log.sql`，`BOOLEAN NOT NULL DEFAULT FALSE`，迁移时按"同一账户内有同 `objectType` + `objectId` 的审计记录"回填）。只在修改路径置为 true：`OptionTradeEditService.update` / `CashTransferEditService.update` 在写审计记录的同一事务里调实体的 `markAudited()`，只标被修改的那一条（连带改正的其它交易不标）；拒绝的修改回滚，新增为 false。读取时直接取实体字段（已平仓取平仓交易的标记；现金流水页由 `CashLedgerService.ledgerPage` 对本页的入金/出金一次 `findAllById` 取 `cash_transfer` 的标记，期权流水恒 false），不查审计表
- e2e 重置按元数据自动包含 `audit_record`（`TestSupportResetE2eProfileTest` 已断言）

### 多账户（FEAT-033）
- 表 `account`（`V5__accounts.sql`）：`name`、`name_key`（去空白 + 小写，唯一约束 = 不区分大小写唯一）、`broker_note`、`created_at`。账户 1"默认账户"由迁移写入（空库也有）。`cash_transfer`、`cash_ledger_entry`、`option_trade`、`audit_record` 都加了 `account_id NOT NULL`（外键到 `account`），迁移时已有行全部为 1。`cash_account` 变为每个账户一行（`id` = 账户 id），仍是该账户现金写操作的行锁；新账户由 `CashLedgerService.openAccount` 建行
- **账户上下文**：请求头 `X-Account-Id`。`account/web/AccountRefArgumentResolver` 把它解析成 `AccountRef`（缺少 → 400 `ACCOUNT_REQUIRED`，非整数 → 400 `BAD_REQUEST`，不存在 → 404 `ACCOUNT_NOT_FOUND`）。所有账户范围的 controller 方法把 `AccountRef account` 作为**第一个参数**（所以在请求体校验之前检查），再把 `account.id()` 显式传给 service；没有隐式的"当前账户"状态。选请求头而不是路径前缀：已有路径和地址栏筛选参数都不变（Q55），前端只需在统一请求封装里加一个头
- 隔离：所有 repository 查询都带 `accountId`（`findByIdAndAccountId`、`findAllByAccountId…`、`OptionTradeSpecifications.matching(accountId, filter)` 等），别的账户的记录在查询层面就"不存在"（404）。`CashLedgerService` 的所有方法都按账户（锁、重放、流水），改写流水时还校验流水属于该账户。合约重放、FIFO、持仓、盈亏、汇总、首页、审计全部只在一个账户内
- 账户审计：`AuditObjectType.ACCOUNT`、`AuditOperation.CREATE`（`before` 为 `null`），记在该账户自己的审计里
- **迁移前备份（Q60）**：`config/DatabaseBackupBeforeMigration` 是一个 `FlywayMigrationStrategy`：有待执行迁移、已有迁移记录、且是 H2 文件库（`DATABASE_PATH()` 非空）时，先执行 H2 `BACKUP TO '<库所在目录>/<库名>-backup-before-V<最高待执行版本>-<UTC 时间>Z.zip'`（在线一致性备份），确认文件存在且非空后才 `migrate()`；任何失败都抛异常 → 应用不启动、不迁移。内存库、新库不备份。**H2 的 DDL 会各自提交，迁移中途失败无法自动回滚，所以备份是回退手段**：停应用 → 移走 `<库名>.mv.db` → 把 zip 里的 `<库名>.mv.db` 解压到原目录 → 用旧版本启动
- e2e 重置：快照包含 `account`（只有 1 号）和 `cash_account`（只有 1 号），所以重置后只剩"默认账户"、下一个账户 id 为 2；重置时锁全部账户行（`lockAllAccounts`）
- 测试：`account/MigrationFromM1Test` 用 Flyway `target=4` 建 M1 结构的文件库、写入场景 M（通过 API 在临时内存库生成后按 id 原样拷贝），再启动应用执行备份 + V5（+ V6），逐个接口比较迁移前后的 JSON 完全相同（含 V6 回填的 `hasAuditLog`）；另有迁移失败（测试专用 `db/failing-migration/V15__simulated_failure.sql`，版本号始终比最新的真实迁移大 1，新增真实迁移时要一起改名，并更新这两个测试里写死的备份名 `before-V<最新真实版本>`）、备份失败两个场景。`account/MigrationV6HasAuditLogTest` 用 `target=5` 建库、直接写入交易/入金和各种审计记录，验证 V6 回填（同类型、同 id、同账户才算）与列表上的 `hasAuditLog`

### 美股交易日历（FEAT-009）
- **包** `calendar/`：`domain/TradingCalendar`（纯 Java 快照：周一至五且非全天休市 = 交易日；Finnhub 覆盖年份只看 Finnhub，`tradingHour` 为空 = 全天休市，非空 = 提前收盘仍是交易日；其余年份用 `HolidayRules`；`nonTradingDays`、`remainingTradingDays`（Q125 (今天, 到期日]，过期 `null`））、`FetchStatus`（`stale` / `warning` 文案）、`TradingDayMessages`；`service/JollydayHolidayRules`（`de.focus-shift:jollyday-core` + `jollyday-jaxb` 2.22.1，NYSE 日历，取 observed 日期，按年缓存）、`FinnhubClient`（`java.net.http`，key 只放 `X-Finnhub-Token`）、`FinnhubHolidayParser`、`FinnhubKeyResolver`、`HolidayFetchService`（网络调用在事务外；`process(FetchAttempt)` 在一个事务里锁状态行、Q127 整年替换、记状态）、`HolidayFetchScheduler`（启动后后台拉一次 + cron `0 0 8 * * *` 美东；`optionmgr.finnhub.fetch-enabled=false` 时整个 bean 不存在）、`TradingCalendarService`（从库建快照、`requireTradingDayForOpen`）；`web/TradingCalendarController`（不取 `AccountRef`，所以不要求账户头）
- **存储（`V13__trading_calendar.sql`）**：`market_holiday`（只存 Finnhub 数据，主键日期）、`market_holiday_fetch_status`（单行 id=1）。规则年份不落库
- **接入**：`OptionTradeService.open` 在字段校验之后、加锁与其它 422 之前调用 `requireTradingDayForOpen`（`legacy` 测试支持路径不查）；`OptionPositionService.PositionSummary` 带日历快照，`OptionPositionController` 输出 `remainingTradingDays`
- **key 与测试**：key 优先级 `optionmgr.finnhub.api-key` > 环境变量 `FINNHUB_API_KEY`（`read-system-env`）> `env-files`（默认 `.env`、`../.env`）。`test` / `e2e` profile：`fetch-enabled=false`、`read-system-env=false`、`env-files` 指向不存在的文件、`base-url` 不可达，所以测试不会读真实 key 或访问 Finnhub。后端测试用 `calendar/FakeFinnhub`（本地 `HttpServer`）+ `base-url` / `api-key` / `env-files` 属性；e2e 用 `POST /api/test-support/trading-calendar/fetch` 与 `PUT|DELETE /api/test-support/clock`（`config/SettableClock`，e2e profile 下的 `Clock` bean；`reset` 时恢复）

### 临时休市检测与提醒（FEAT-042）
- **检测**：`calendar/domain/AdhocClosureDetector`（纯 Java）：输入本次成功响应、合并前已存 Finnhub 数据、`HolidayRules`、现有记录、今天（美东）→ 要新增 / 更新（eventName、恢复 ACTIVE）/ 撤销（Q127）的记录。`HolidayFetchService.parseAndMerge` 在 Q127 整年替换**之前**、同一事务里调用；失败的拉取不进入这一步
- **存储（`V14__adhoc_closure.sql`）**：`adhoc_closure`（主键日期，`status` ACTIVE / REVOKED，`detected_on` 不变，`revoked_on`）。全系统一份，e2e 重置快照自动包含
- **读取**：`calendar/service/AdhocClosureService`（`list`、`activeClosures`、`findActive(date)`，FEAT-043 复用）；`TradingCalendarController` `GET /api/trading-calendar/adhoc-closures`（无账户头）
- **提醒与标签**：`OptionPositionService.PositionSummary` 带 ACTIVE 临时休市（date → eventName）；`OptionPositionController` 输出 `expiresOnAdhocClosure` / `adhocClosureEventName`，`trade/web/AdhocClosureAlertController`（`GET /api/adhoc-closure-alerts`，按账户）用同一个 summary + `calendar/domain/AdhocClosureAlerts`（计数、文案），所以提醒个数与行标签总是一致。实时计算，不存
- **Jollyday 基线**：`calendar/service/JollydayFinnhubAgreementTest` 用 `src/test/resources/finnhub/market-holiday-us-2023-2027.json`（按 NYSE 公布日历构造，不来自真实调用）锁定 jollyday 2.22.1 与 Finnhub 2023–2027 全天休市完全一致（含 2025-01-09）
- **Q134 每年固定 vs 一次性**：`HolidayRules.recurringClosures(year)`（默认 = `closures`）；`JollydayHolidayRules` 判定：同一 properties key 在前一年或后一年也出现 = 每年固定（Jollyday 公共 API 不暴露 validFrom / validTo；一次性特殊日在其 NYSE 文件里是 `validFrom = validTo`）。检测只用 `recurringClosures`；交易日历（FEAT-009、Q126）仍用 `closures`，所以 2025-01-09 仍是非交易日。`JollydayHolidayRulesTest` 锁定分类

### 临时休市时调整到期日（FEAT-043）
- **规则**：`trade/domain/ExpirationAdjustmentRules`（纯 Java）：检查 3–9 及顺序（临时休市 → 已调整过 → 已结束 → 新日期是交易日 → 范围 → 16:00 → 目标合约已存在）、默认新日期（前一个交易日）、最后成交日、审计摘要
- **写入**：`trade/service/ExpirationAdjustmentService`（`POST /api/option-trades/expiration-adjustments`，`trade/web/ExpirationAdjustmentController`）：持 `cash_account` 锁；规则通过后仍按 Q130 重放（合约重放、call 的覆盖不变量、现金流水），再改每条 `option_trade.expiration_date`（`OptionTradeEntity.changeExpiration`）、置 `has_audit_log`，流水 `description` 里的合约文字换成新合约，写一条 `ADJUST_EXPIRATION` 审计，写 `expiration_adjustment`（唯一键在新合约上 = 只能调一次，Q131）
- **审计扩展（`V15__expiration_adjustment.sql`）**：`audit_record.detail_json`（操作专属数据，API 字段 `expirationAdjustment`）；`audit_record_object`（审计记录的其它对象 id）。`AuditService.record(r, detail, relatedObjectIds)`；`AuditRecordRepository.findByObject` 让 `objectType + objectId` 筛选同时匹配主对象和其它对象，所以每条被调整交易的"历史"都能看到这次调整。其它审计写入不变
- **持仓**：`OptionPositionService.PositionSummary.adjustability(p)`（`Adjustability`：记录条数、默认 / 最小 / 最大新日期），输出为 `expirationAdjustable` 等字段；不可调整时为 `null`
- **测试支持**：`legacy-option-trades` 接受任意开仓（BTO / STO，call / put），不查 Q126

### 自动行情（FEAT-016）
- **包** `quote/`：`domain/`（纯 Java）：`QuotePrices`（Q139 mark：bid、ask 都 > 0 取中间价，精确不四舍五入；否则 theo；显示至少 2 位小数）、`OccCode` + `ChainMatcher`（代码末尾 15 位 = YYMMDD + C/P + 行权价×1000；root 等于或以标的结尾；同一合约多个不同代码 = 歧义，不猜）、`QuoteMerge`（规则 6–8：失败不改已存值；链中消失 → `missingSince`；下一次成功的自动值替换手动覆盖，合约 mark 只在自动 mark 非空时替换）、`FetchSchedule`（Q138 时刻 + Q147 启动窗口）、`QuoteLabels`（Q141 行标签、顶部提示：从未成功且有跟踪标的时任何时间都提示；交易日 09:30–16:00 内距 max(上次成功, 09:30) 超过 30 分钟提示）、`UnderlyingState` / `OptionState`、`SymbolFetch`。`service/`：`QuoteTransport`（`HttpQuoteTransport` 真实 CBOE / Finnhub，`InMemoryQuoteTransport` 仅 test / e2e profile 的假数据源 + 请求记录）、`QuoteParsers`（Jackson 精确 `BigDecimal`）、`HeldPositionsService`（所有账户的持仓；`tracked(today)` = 未过期合约（Q150）+ 股票，指数除外（Q149））、`QuoteFetchService`（run：网络调用在事务外，结果一个事务写入；`runLock` 串行化 run；`refresh` 60 秒冷却；`tick` 判断时刻与交易日；`CLOSE` 存收盘快照）、`QuoteScheduler`（cron 每 5 分钟唤醒 + 启动后台一次；`optionmgr.quotes.fetch-enabled=false` 时不存在）、`QuoteQueryService`（`QuoteBook` 给持仓行组装 `underlyingQuote` / `optionQuote`，状态与提示实时计算）、`QuoteOverrideService`（手动覆盖，要求某账户持有；`QuoteWriteLock` 与 run 的写入互斥）。`web/MarketDataController`（`/api/market-data/**`，不取 `AccountRef`）
- **存储（`V16__market_data.sql`）**：`underlying_quote`（每标的最新一份 + 标的价 / 期权链各自的上次成功与失败标志 + 手动价）、`option_quote`（每合约最新一份含 IV / Greeks、`missing_since`、手动 mark；唯一键四要素）、`underlying_quote_close` / `option_quote_close`（每天 16:20 快照，自增 id + 唯一键（日期, 标的 / 合约））、`market_data_status`（单行）。全系统一份，e2e 重置快照自动包含
- **接入**：`OptionPositionController`、`ExpirationAdjustmentController`、`StockPositionController` 每次请求取一个 `QuoteBook`；`OptionPositionService.openContracts(accountId)` 给跟踪集合用。行情失败不影响任何记账（规则 9）
- **配置与测试**：`optionmgr.quotes.*`（`fetch-enabled`、`transport` http / in-memory、`cboe-base-url`、`connect-timeout-seconds` / `read-timeout-seconds`、`index-symbols`）；Finnhub key / base-url 复用 `optionmgr.finnhub.*`。`test` profile：不拉取、CBOE 不可达；需要拉取的测试设 `optionmgr.quotes.transport=in-memory` 或把 base-url 指向本地假服务。`e2e` profile：in-memory，测试支持 `/api/test-support/market-data/{sources,requests,tick,stored}`（`testsupport/web/MarketDataTestSupportController`），`reset` 也清空假数据源

### 未实现盈亏（FEAT-017）
- **只读、实时计算、不存库**（无迁移）。`valuation/domain`（纯 Java）：`UnrealizedValuation.option/stock` → `RowValuation`（`ValuationStatus` `OK` / `NO_QUOTE` / `EXPIRED_PENDING` + 标签；判定顺序：到期日 < 美东今天 → `EXPIRED_PENDING`（优先，即使有 mark）；显示 mark / 价格为 `null` → `NO_QUOTE`）。市值 `setScale(2, HALF_UP)`、空头取负（Q152 / Q154）；盈亏 = 净额 / 成本与取整后市值的精确加减；百分比 `pnl × 100 ÷ 分母` HALF_UP 2 位，分母 0 → `null`（Q155）。`UnrealizedSummary` 累加 OK 行为期权 / 股票 / 总计三组 + 按原因计数，拼 `excludedLabel`
- **接入**：`valuation/service/UnrealizedValuationService` 的静态 `option(...)` / `stock(...)` 被 `OptionPositionController.toResponse`（因此 FEAT-043 调整响应里的 `position` 也带新字段）和 `StockPositionController` 在组装行时调用，用的是同一个 `QuoteBook` 的显示 mark / 价格；`summary(accountId)` 读同样的持仓和 `QuoteBook`，给 `valuation/web/UnrealizedSummaryController`（`GET /api/positions/unrealized-summary`，`AccountRef`）。所以合计 = 两个持仓接口 OK 行之和；合计没有过滤参数（Q158）
- **测试**：`valuation/domain/UnrealizedValuationTest`（规则与验收数字）、`valuation/web/UnrealizedPnlControllerTest`（验收 1–10，in-memory 假数据源 + 可移动时钟）

### 单合约 Greeks（FEAT-018）
- **只读、实时计算、不存库**（无迁移、无新端点）。数据来自 FEAT-016 `option_quote` 的 `iv` / `delta` / `gamma` / `theta` / `vega`（CBOE 每股、多头视角；iv 小数，theta 每日历天，vega 每 IV 1 个百分点），不返回 rho。`valuation/domain`（纯 Java）：`GreeksValuation.option(longPosition, contracts, multiplier, expirationDate, today, Quote)` → `RowGreeks`（`GreeksStatus` `OK` / `NO_QUOTE` / `EXPIRED_PENDING`；判定顺序：到期日 < 美东今天 → `EXPIRED_PENDING`；从未有 CBOE 报价（`NEVER_QUOTED`，含指数、歧义）→ `NO_QUOTE`，有手动 mark 也是）。每张值 `setScale(4, HALF_UP)` 不翻转；持仓值 = 未取整原值 × 张数 × multiplier × 方向，`setScale(2, HALF_UP)`；IV ×100 两位；单字段 `null` 只置空该字段；手动 mark 时拼 `manualMarkNote`（`QuoteLabels.shortTime` 的美东 `MM-dd HH:mm`）
- **接入**：`UnrealizedValuationService.greeks(p, today, optionQuote, book.stored(c))` 在 `OptionPositionController.toResponse` 中调用（FEAT-043 调整响应的 `position` 同样带 `greeks`），与 `optionQuote` 用同一个 `QuoteBook`，所以 `quoteTime` / `manual` 行内一致。成功拉取整体替换已存 Greeks（含 `null`），失败保留（FEAT-016 `QuoteMerge`）
- **测试**：`valuation/domain/GreeksValuationTest`、`valuation/web/OptionGreeksControllerTest`（验收 1–9）

### E2E 测试支持（`e2e` profile）
- 用途：Playwright 套件只启动一个后端，每个测试前调用 `POST /api/test-support/reset` 得到干净的库，代替每个测试重启后端
- 启用：`SPRING_PROFILES_ACTIVE=e2e` 或 `--spring.profiles.active=e2e`（例如 `java -jar build/libs/<boot jar> --spring.profiles.active=e2e`，或 `gradlew.bat bootRun --args="--spring.profiles.active=e2e"`）。不启用时 `testsupport` 的 bean（`TestSupportController`、`DatabaseResetService`，都标 `@Profile("e2e")`）不存在，路径返回 404
- 数据库安全：`application-e2e.yml` 把数据源改为 `jdbc:h2:mem:e2e;...;DB_CLOSE_DELAY=-1`；另有 `E2eDatasourceGuard`（`EnvironmentPostProcessor`，在 `META-INF/spring.factories` 注册），`e2e` 生效时如果最终的 `spring.datasource.url`（含 `SPRING_DATASOURCE_URL`、命令行参数覆盖后）不是 `jdbc:h2:mem:` 开头，在创建任何 bean 之前就让启动失败；`DatabaseResetService` 构造时和每次重置时再用连接的实际 URL 检查一次
- 重置机制（`testsupport/service/DatabaseResetService`）：
  - 启动时（依赖 `FlywayMigrationInitializer`，保证迁移已完成）通过 JDBC 元数据读取当前 schema 的**所有表**（排除 Flyway 历史表）的列、行（迁移写入的初始数据）、外键父表、自增列，作为"新库快照"
  - 重置（一个事务）：先 `CashLedgerService.lockAccount()` 拿 `cash_account` 行锁；内容与快照不同的表（及引用它的子表）按子表先删、父表先插的顺序清空并写回快照；内容未变的表（如 `cash_account`）不动，被锁的行保持原位；最后把每个自增列 `ALTER TABLE ... ALTER COLUMN ... RESTART WITH (快照最大值 + 1)`。H2 中 RESTART 不提交事务但立即生效、不随回滚撤销，因此放在最后一步
  - **以后新增表不需要改这里**：新迁移建的表、写的初始数据会被自动纳入。要求：表在默认 schema；外键无环（有环时启动报错）；自增列用 `GENERATED BY DEFAULT AS IDENTITY`（`ALWAYS` 时写回快照行需改用 `OVERRIDING SYSTEM VALUE`）；如果以后新增"非表"的状态（如独立 SEQUENCE、进程内缓存），需在 `reset()` 中补上
- 已知限制：快照取自启动时的库，所以 `e2e` 必须是新的内存库（`application-e2e.yml` 保证）；同一 JVM 里多个 `e2e` Spring 上下文共用名为 `e2e` 的内存库时，后启动的上下文会把已有数据当作快照（目前测试里只有一个 `e2e` 上下文）

### 错误处理约定
- 格式与错误码定义见 `docs/api/api-contract.md`「统一错误格式」
- 业务代码抛 `ApiException`（`ApiException.notFound(...)`、`conflict(...)`、`businessRule(...)` 或 `new ApiException(ErrorCode, message, details)`）；不要在 controller 里自己拼错误响应
- `GlobalExceptionHandler`（`@RestControllerAdvice`）统一转换：Bean Validation 失败 → `VALIDATION_ERROR`；JSON 无法解析/参数类型错/缺参数 → `BAD_REQUEST`（请求体字段值无法转换时 `details` 给出属性路径）；未知路由（`NoResourceFoundException`）→ `NOT_FOUND`；405/415；其它带状态的 Spring 异常按状态映射；其余异常 → `INTERNAL_ERROR`，堆栈只写日志
- `ErrorCode` 新增值时同步更新契约中的错误码表
- 已知限制：在 Spring MVC 之外（servlet filter 中）抛出的异常会走 Spring Boot 默认的 `/error` 响应，格式不同于统一格式（已配置不输出堆栈和异常信息）。目前没有自定义 filter，加 filter 时需要处理

## Frontend（frontend-dev 维护）

### 版本与构建
- Node 22，npm；Vite 8，React 19，TypeScript 6.0（`strict` + `noUncheckedIndexedAccess`），Ant Design 6，TanStack Query 5，React Router 7（`react-router-dom`）
- 测试：Vitest 5 + jsdom + React Testing Library + jest-dom + user-event
- Lint：ESLint 10 flat config（`eslint.config.js`），typescript-eslint `strictTypeChecked`（类型感知）、react-hooks、react-refresh；`--max-warnings 0`；禁止 `any`
- TypeScript 停在 6.0.x：TypeScript 7（Go 原生版）目前不被 typescript-eslint 支持（peer `<6.1`），等其支持后再升级
- 命令（在 `frontend/` 下）：`npm run dev`（5173，`/api` 代理到 `http://localhost:8080`；环境变量 `OPTION_MGR_API_TARGET` 可改代理目标，如 `http://localhost:8081` 的 e2e 测试后端）、`npm run build`（`tsc -b && vite build`）、`npm run lint`、`npm run typecheck`（`tsc -b --noEmit`）、`npm test`（`vitest run`，非 watch）、`npm run test:watch`

### 目录结构
```
frontend/
├── index.html
├── vite.config.ts        dev server 端口/代理 + Vitest 配置（jsdom、setupFiles）
├── eslint.config.js
├── tsconfig*.json        app（src/）与 node（vite.config.ts）两个 project
└── src/
    ├── main.tsx          入口：AppProviders + BrowserRouter + App
    ├── App.tsx           路由表与外层 Layout（Header + 账户切换器 + Content）；`AccountProvider` 包住全部路由，页面内容按当前账户 key 重新挂载
    ├── account/          FEAT-033 当前账户：AccountProvider（加载账户、选当前账户、通知 API client）、AccountContext（`useAccount`、`useAccountId`、`accountKey`）、accountStorage（localStorage 记住选择、最小 id 回退）
    ├── AppProviders.tsx  QueryClientProvider + antd ConfigProvider(en_US) + antd App；测试复用
    ├── queryClient.ts    QueryClient 默认配置
    ├── api/
    │   ├── types.ts      按 API 契约手写的请求/响应类型（含统一错误格式 ApiErrorResponse）
    │   ├── client.ts     fetch 封装 apiRequest<T>() 与 ApiError；`setApiAccountId()` 后给账户范围内的请求加 `X-Account-Id`（FEAT-033）
    │   └── <feature>.ts  每个领域一个文件：请求函数 + queryKey（health.ts；cash.ts: getCashSummary/getCashLedger/createCashTransfer、cashQueryKeys；optionTrades.ts: createOptionTrade/createCloseTrade/createExpiration（FEAT-012）/createAssignment（FEAT-013）/createExercise（FEAT-014）/getOptionTrade/listOptionTrades/updateOptionTrade/deleteOptionTrade；cash.ts 另有 updateCashTransfer/deleteCashTransfer；auditLog.ts: getAuditLog、auditLogQueryKeys；closedTrades.ts: listClosedTrades、closedTradeListQuery（FEAT-040 加 `type`）、closedTradeQueryKeys；dashboard.ts: getDashboard、getPnlSummary 及其 queryKey；accounts.ts: listAccounts/createAccount/updateAccount、accountQueryKeys、optionTradeQueryKeys；optionPositions.ts: getOptionPositions、optionPositionQueryKeys；tradingCalendar.ts（FEAT-009，不按账户）: getNonTradingDays/getTradingCalendarStatus、tradingCalendarQueryKeys；adhocClosureAlerts.ts（FEAT-042，按账户）: getAdhocClosureAlerts、adhocClosureAlertQueryKeys；stockTrades.ts（FEAT-011）: createStockTrade/getStockTrade/listStockTrades/updateStockTrade/deleteStockTrade/getStockPositions、stockTradeQueryKeys、stockPositionQueryKeys；unrealizedSummary.ts（FEAT-017，按账户）: getUnrealizedSummary、unrealizedSummaryQueryKeys）
    ├── pages/            路由级页面组件（HomePage、CashPage、TradeEntryPage、TradeListPage、PositionsPage、StockTradesPage、StockTradeEntryPage、ClosedTradesPage、PnlSummaryPage、AuditLogPage、AccountsPage、NotFoundPage）
    ├── components/       跨页面复用组件：QueryError（查询错误 + 重试）、DeleteConfirmModal（删除确认，FEAT-005）
    │   ├── calendar/     FEAT-009 交易日历：tradingCalendar（月份范围、`useNonTradingDays` 按月取非交易日、`useTradingCalendarStatus`、文案）、TradingCalendarStaleAlert（"休市日数据未更新"提示，持仓页与录入页顶部）、AdhocClosureAlerts（FEAT-042 临时休市提醒，首页与持仓页顶部）
    │   ├── account/      AccountSwitcher（顶部账户切换器，FEAT-033）
    │   ├── cash/         CashSummaryCard（可复用现金汇总）、CashTransferForm + cashTransferValidation、CashLedgerTable
    │   └── trade/        TradeEntryForm（录入交易，开仓 + 平仓）、ExpirationDatePicker（FEAT-009 到期日选择器：antd DatePicker，禁用后端返回的非交易日，表单值仍为 `YYYY-MM-DD` 字符串）、EditTradeModal（修改交易，FEAT-005）+ openTradeValidation、TradeConfirmation（开/平仓确认，含被平批次与已实现盈亏；导出 CoveredLotTable，作废结果共用）、tradeFormSupport（字段错误映射、保存后刷新、净额标签）；TradeFilterForm、TradeTable、tradeListFilters（FEAT-002 列表筛选与 URL 状态）；tradeLabels（方向文案、`isExpiryAction`、正负颜色，共用）
    │   ├── audit/        AuditTable（可展开修改前后）、auditSearch（审计页 URL 状态与校验）、auditLinks（各行"历史"链接）（FEAT-005）
    │   ├── pnl/          pnlSummarySearch（盈亏汇总页 URL 状态、月份范围交集、到已平仓页的链接）（FEAT-008）
    │   ├── closed/       ClosedTradeTable（已平仓记录，可展开被平批次；FEAT-040 股票卖出行可展开被卖出批次，行 key = 类型 + id）（FEAT-006）、closedListFilters（FEAT-040：已平仓页 URL 状态 = 交易列表筛选 + `type`，校验、API 参数）
    │   ├── position/     PositionTable（可展开批次、平仓 / 作废按钮、到期待确认标签、空头 call 覆盖标签）、coverageLabels（FEAT-039 覆盖标签与悬停文案）、ClosePositionModal（平仓对话框，FEAT-004）、ExpirePositionModal（到期作废对话框，FEAT-012）+ expireLabels（方向、冻结文案，单个与批量作废共用）、BatchExpireModal + batchExpire（批量作废确认框、按钮条件、按合约匹配结果 / 失败项，FEAT-015）、AssignPositionModal + assignValidation（记录被指派对话框，FEAT-013）、ExercisePositionModal + exerciseValidation（记录行权对话框，FEAT-014）、positionLinks（合约 → 交易列表 URL，`tradesUrlForContract`，持仓和已平仓共用；`tradesUrlForAssignment` 被指派记录 / `tradesUrlForExercise` 行权记录 → 交易列表）、expiryFilter（FEAT-010：到期过滤 URL 状态、按 `dte` 匹配、空结果文案、`positionsUrlForExpiry`）（FEAT-003）
    │   ├── quote/        FEAT-016 行情：MarketDataBar（持仓页顶部行情提示 + 立即刷新）、QuoteCells（标的价 / 现价、Bid / Ask、Mark、报价时间单元格，含手动标签、清除、手动设置）、ManualQuoteModal（手动设置标的价 / Mark）、quoteLabels（文案、价格校验、刷新结果提示、冷却复查延时、覆盖错误拆分）、marketDataRefresh（`refreshQuotes`：失效期权 / 股票持仓与行情状态）
    │   ├── unrealized/   FEAT-017 未实现盈亏：UnrealizedCells（`MarketValueCell` / `UnrealizedPnlCell`，期权与股票持仓表共用）、UnrealizedSummaryBar（持仓页合计行）、unrealizedLabels（`unrealizedDisplay` 显示规则、表头说明文案）
    │   ├── greeks/       FEAT-018 单合约 Greeks：GreeksCells（`GreeksHeader` 列头 + `?` 悬停说明、`GreeksIvCell`（含手动 mark 标注）、`GreekValueCell`）、greeksLabels（`greeksDisplay` 显示规则、列头说明 / 口径说明文案、`显示 Greeks` 开关的 localStorage 读写）
    │   └── stock/        FEAT-011 股票：StockTradeForm（录入买入/卖出/转入）+ StockTradeConfirmation、EditStockTradeModal、StockTradeTable（可展开被卖出批次）+ SoldLotTable、StockFilterForm、StockPositionsCard + StockPositionTable（持仓页股票部分，可展开批次）、stockLabels（类型文案/颜色、金额标签、时间文本、删除摘要）、stockListFilters（股票页 URL 状态与校验、`stocksUrlForSymbol`、`stockSellUrl`）、stockValidation（股数、金额、转入日期）、stockPositionsAnchor（FEAT-040：持仓页股票部分锚点 `#stock-positions`）
    ├── utils/format.ts   统一的显示格式化（时间、金额；`formatLocalDateTime` 原样显示后端的美东本地时间；`formatInteger` 股数千分位；`formatPercent` 后端百分数加 `+`/`−` 和 `%`，FEAT-017）
    └── test/             setup.ts（jest-dom、matchMedia / ResizeObserver polyfill、清理 mock、清空当前账户和 localStorage）、render.tsx（renderWithProviders、jsonResponse、mockFetch——默认回答 `GET /api/accounts`）、userEvent.ts（无延迟、不做 pointer-events 检查的 user-event）、homeFixtures.ts
```
测试文件与被测文件放在一起：`*.test.ts(x)`。

### 路由
| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | `HomePage` | FEAT-008 首页概览（只读）：现金卡（`CashSummaryCard`）、已实现盈亏卡与持仓卡（`GET /api/dashboard`；FEAT-040 已实现盈亏含股票，持仓卡"期权持仓 N 个" + "股票持仓 N 个标的"链接到 `/positions#stock-positions`；FEAT-010 "7 天内到期 / 已过期未处理 N 个"链接到 `/positions?expiry=7` / `?expiry=expired`，0 也是链接）、录入交易入口；底部一行后端状态（`GET /api/health`） |
| `/pnl-summary` | `PnlSummaryPage` | FEAT-008 盈亏汇总：平仓 / 卖出日期范围（URL `startDate`/`endDate`）、总计（FEAT-040 另有小字"期权 N 笔 ±X · 股票 N 笔 ±X"）、按标的 / 按月两个表格（FEAT-040 列：笔数、期权、股票、合计；某类 0 笔显示 `—`；合计行用后端总计），标的/月份链接到 `/closed`（不带类型）（导航"盈亏汇总"） |
| `/trades` | `TradeListPage` | FEAT-002 交易列表：筛选 + 只读表格 + 分页；筛选/页码/每页条数都在 URL 查询参数里（导航"交易"） |
| `/positions` | `PositionsPage` | FEAT-003 期权持仓：截至日期/持仓数/冻结合计 + 持仓表（可展开 FIFO 批次，合约链接到 `/trades` 筛选；FEAT-004 可平仓行有"平仓"按钮，打开平仓对话框；FEAT-012 可作废行有"作废"按钮，打开作废对话框，16:00–17:30 显示"到期待确认"；FEAT-013 空头 put 行（FEAT-041 起空头 call 行也）有"被指派"按钮，打开记录被指派对话框；FEAT-014 多头 call / put 行按 `exercisable` 有"行权"按钮，打开记录行权对话框）；下方 FEAT-011"股票持仓"卡片（`GET /api/stock-positions`，可展开批次，标的链接到 `/stocks?symbol=`，"卖出"到 `/stocks/new?type=SELL&symbol=`；FEAT-040 卡片 `id="stock-positions"`，地址带 `#stock-positions` 时在期权与股票部分都加载后滚动到该卡片）；FEAT-010 期权表上方"到期"过滤（`?expiry=expired|today|7|14|30`，其它值按"全部"），只按后端 `dte` 比较、保留后端顺序，过滤时显示"显示 M / N 个持仓"并隐藏股票部分（导航"持仓"） |
| `/stocks` | `StockTradesPage` | FEAT-011 股票交易列表：筛选（标的、类型多选、美东日期范围）+ 表格（卖出行可展开被卖出批次，修改 / 删除 / 历史）+ 分页；筛选/页码/每页条数在 URL（`symbol`、`type`、`startDate`、`endDate`、`page`、`size`）（导航"股票"） |
| `/stocks/new` | `StockTradeEntryPage` | FEAT-011 录入股票交易（买入 / 卖出 / 转入）：现金汇总 + 表单 + 成功结果；`?type=SELL&symbol=…` 预选卖出并预填标的（持仓页股票行"卖出"） |
| `/trades/new` | `TradeEntryPage` | 录入交易（FEAT-001 开仓 BTO/STO + FEAT-004 平仓 BTC/STC，按方向调用不同接口）：现金汇总 + 表单 + 成功确认（导航"录入交易"） |
| `/closed` | `ClosedTradesPage` | FEAT-006 已平仓记录：筛选结果笔数 + 已实现盈亏合计，平仓表格（可展开被平批次），筛选/分页/URL 同交易列表，方向只有 BTC/STC/EXP；FEAT-040 含股票卖出（类型列、"合约 / 标的"列股票行链接到 `/stocks?symbol=`、可展开被卖出批次），URL 另有 `type`（`OPTION` / `STOCK`）（导航"已平仓"） |
| `/audit` | `AuditLogPage` | FEAT-005 审计记录：只读，按对象筛选（URL 参数 `objectType`/`objectId`/`page`/`size`），可展开修改前后（导航"审计记录"） |
| `/accounts` | `AccountsPage` | FEAT-033 账户：列表、新增、改名、切换到此账户；没有删除（从顶部切换器的"管理账户"进入） |
| `/cash` | `CashPage` | FEAT-007 资金：现金汇总、入金/出金表单、现金流水（导航"资金"） |
| `*` | `NotFoundPage` | 404 |

### 服务端数据与错误处理
- **账户（FEAT-033）**：所有业务数据都在"当前账户"内。当前账户由 `AccountProvider` 决定（localStorage 记住，不存在时取最小 id），`apiRequest` 自动加 `X-Account-Id`（`/api/health`、`/api/accounts/**`、`/api/test-support/**` 除外）；按账户的查询一律用 `account/useAccountQuery`（key 末尾自动加 `{ accountId }`，确定当前账户后才发请求；账户列表失败时报"账户未就绪，请重试"，其重试会重新加载账户列表），新页面也要这样做；`apiRequest` 在没有账户时不发账户范围的请求，后端的账户错误显示为中文；地址栏参数不带账户。切换账户时页面重新挂载，打开的对话框关闭
- 服务端数据只用 TanStack Query；不把服务端数据复制到全局 store。queryKey 与请求函数放在同一个 `src/api/<feature>.ts`
- QueryClient 默认：网络错误/5xx 重试 1 次，4xx 不重试；`refetchOnWindowFocus: false`；mutation 不重试
- `apiRequest` 总是抛 `ApiError { code, message, status, details, path }`：
  - 后端返回契约统一错误格式 → 原样带出 `code/message/details/path`；页面按 `code` 分支，`message` 可直接显示，表单把 `details[].field` 映射到对应字段
  - 请求没到服务器 → `code: 'NETWORK_ERROR'`，`status: 0`
  - 响应不是 JSON 或错误体不符合契约（例如 Vite 代理在后端未启动时返回的 502）→ `code: 'INVALID_RESPONSE'`
- 每个查询在页面上处理 loading / error（显示错误信息 + 重试按钮，可用 `components/QueryError`）/ 空数据三种状态
- 写操作成功后 invalidate 该领域的根 key（如 `cashQueryKeys.all` = `["cash"]`），依赖它的组件（如 `CashSummaryCard`）自动刷新；以后任何改变现金的操作也要 invalidate `["cash"]`；任何新增/修改交易的操作还要 invalidate `optionTradeQueryKeys.lists` = `["option-trades","list"]` 和 `optionPositionQueryKeys.all` = `["option-positions"]`；FEAT-005 起所有写操作（新增、修改、删除交易或入金出金）统一调用 `tradeFormSupport.refreshAfterChange`，invalidate `["cash"]`、`["option-trades"]`、`["option-positions"]`、`["audit-log"]`、`["closed-trades"]`（FEAT-006）、`["dashboard"]`、`["pnl-summary"]`（FEAT-008）、`["stock-trades"]`、`["stock-positions"]`（FEAT-011）；新增入金/出金、新增/修改/删除股票记录也调用它
- 列表页的筛选/分页状态以 URL 查询参数为唯一来源（`useSearchParams`），改变时 push 历史，后退可恢复；URL 中不合法的筛选值回填并在字段下报错、不发请求，不合法的 page/size 回退到默认值
- 表单提交失败：`VALIDATION_ERROR` / `BAD_REQUEST` 的 `details[].field` 用 `form.setFields` 显示到同名字段；无法对应字段的错误和业务错误（如 `CASH_INSUFFICIENT`，message 是中文可直接显示）显示在提交按钮上方；保留用户输入

### 显示约定
- 金额等小数：后端给字符串，前端只格式化不计算；格式化函数统一放 `src/utils/format.ts`
- 时间点（Instant）用 `formatInstantNY()` 显示为 `YYYY-MM-DD HH:mm:ss EST|EDT`，或 `formatDateTimeNY()` 显示为 `YYYY-MM-DD HH:mm`（列表用），均为 America/New_York；空值/非法值显示 `—`
- 金额用 `formatMoney(value, { signed? })`：纯字符串处理（不转浮点、不四舍五入），千分位、至少 2 位小数、负号 `−`(U+2212)，`signed` 时正数加 `+`；颜色用 `decimalSign()` 判断正负（正绿 `#389e0d`、负红 `#cf1322`）
- 用户录入的时间：原生 `datetime-local` 输入美东本地时间，原样以 `YYYY-MM-DDTHH:mm` 发给后端（`occurredAtLocal` 之类字段），前端不做时区换算；默认值用 `nowLocalDateTimeNY()`
- 交互元素和关键显示值加 `data-testid`，供 E2E 使用。已有：`nav-home`、`nav-cash`、`health-card`、`health-loading`、`health-status`、`health-version`、`health-time`、`health-error`、`health-retry`、`not-found`
- FEAT-007 资金页的 testid 列表见 `docs/requirements/FEAT-007-cash-account.md` 的 Frontend Implementation Notes；FEAT-001 录入开仓页见 `docs/requirements/FEAT-001-open-option-trade.md`；FEAT-002 交易列表见 `docs/requirements/FEAT-002-trade-list.md`；FEAT-003 持仓见 `docs/requirements/FEAT-003-option-positions.md`
- 用户录入的日期用原生 `type="date"`，原样以 `YYYY-MM-DD` 发送；例外：录入交易页的到期日用 `ExpirationDatePicker`（FEAT-009，开仓时禁用后端 `non-trading-days` 返回的日期，输入非交易日提示"所选日期不是美股交易日"；平仓不限制），值同样是 `YYYY-MM-DD` 字符串
- 交易日历（FEAT-009）：前端不判断周末 / 假日，只用后端 `non-trading-days`；`/api/trading-calendar/**` 不带 `X-Account-Id`（`client.ts` 免账户前缀），用普通 `useQuery`；"休市日数据未更新"只按 `status.stale` 显示后端 `warning` 原文；持仓"剩余交易日"取 `remainingTradingDays`，`null` 显示 `—`
- 临时休市提醒（FEAT-042）：`AdhocClosureAlerts`（`useAccountQuery`，按账户缓存，切换账户自动取新账户）在首页、持仓页顶部每个日期一行红色 Alert，`message` 原样显示，无关闭按钮（Q128），`alerts` 为空 / 加载中 / 请求失败时不渲染（Q129）。行末"去调整"：首页是到 `/positions` 的链接；持仓页是按钮，用 `position/adhocClosure.firstAdhocClosureRow` 找后端顺序第一个 `expiresOnAdhocClosure` 且 `expirationDate` = 该行 `date` 的持仓，到期过滤隐藏该行时先切回"全部"，再 `scrollIntoView` 并高亮该行（`data-highlighted="true"`）。持仓行到期日旁红色 `临时休市` 标签按 `expiresOnAdhocClosure`，悬停显示 `adhocClosureEventName`。前端不判断日期、不计数；`refreshAfterChange` 和单个 / 批量作废失败路径都让 `adhoc-closure-alerts` 与持仓一起失效
- 调整到期日（FEAT-043）：持仓操作列"调整到期日"只按后端 `expirationAdjustable`；`AdjustExpirationModal` 复用 `ExpirationDatePicker`（`minDate` / `maxDate` 取 `newExpirationDateMin` / `Max`，另禁用 `non-trading-days`），默认 `defaultNewExpirationDate`，条数 / 休市名称取后端；其它检查全由后端 422 原文显示；成功后关闭并 `refreshAfterChange`。"去调整"仍只滚动高亮。审计 `ADJUST_EXPIRATION` 显示"调整到期日"，行内显示记录 ID 和备注，展开显示 `expirationAdjustment` 详情
- 自动行情（FEAT-016）：前端不算 mark、不判断过期，价格原样经 `formatMoney`（不四舍五入），时间用 `formatMonthDayTime(…Local)` 显示 `MM-DD HH:mm`；后端整句文字（`warning`、`staleLabel`、`contractStatusLabel`）原样显示。`/api/market-data/**` 不带 `X-Account-Id`（`client.ts` 免账户前缀），行情状态用普通 `useQuery`（key `['market-data','status']`，每 60 秒轮询），持仓行情随 `option-positions` / `stock-positions` 按账户返回；立即刷新、设置 / 清除手动值后 `refreshQuotes` 失效这三者（不失效现金 / 交易）。持仓表在"张数"后加 `标的价`、`Bid / Ask`、`Mark`、`报价时间` 四列，股票表在"持股"后加 `现价`、`报价时间`；`mark` / `price` 为 `null`（或旧响应缺字段）显示 `无报价`。立即刷新：`refreshAllowed = false` 或请求进行中禁用（悬停 `1 分钟内只能刷新一次`）；浏览器只按 `refreshAvailableAt` 安排一次 1–60 秒后的状态复查（后端时钟可能是测试时钟，以后端 `refreshAllowed` 为准）；`performed: false` 显示 `1 分钟内只能刷新一次，本次未重新获取`。手动设置：前端格式校验文案同后端（请输入价格 / 价格必须大于 0 / 价格最多 4 位小数 / 价格过大，另有"请输入有效的价格"），原样以字符串提交；`details[].field` 为 `price` / `mark` 显示在输入框下，其它（含 422 `QUOTE_TARGET_NOT_HELD`）原文显示在按钮上方。状态请求失败时不显示提示、按钮可用
- FEAT-016 testid：顶部 `market-data-bar`、`market-data-stale-warning`（`warning` 原文）、`market-data-last-success`（`行情更新于 YYYY-MM-DD HH:mm（美东）` / `行情尚未成功获取`）、`market-data-refresh`（按钮）、`market-data-refresh-wrapper`（悬停提示用）、`market-data-refresh-result`（`data-kind` = `success` / `partial` / `cooldown` / `error`）。期权行（`position-row` 内）：`position-underlying-price`（值或 `无报价`，`data-source` = `CBOE` / `FINNHUB` / 空，`data-manual`）、`position-underlying-price-manual`（`手动 MM-DD HH:mm`）、`position-underlying-price-clear`、`position-underlying-price-set`、`position-underlying-price-stale`（标的价 `staleLabel`）、`position-bid-ask`（`1.20 / 1.30`，无报价 `—`）、`position-mark`（值或 `无报价`，`data-manual`、`data-contract-status`）、`position-mark-manual`、`position-mark-clear`、`position-mark-set`、`position-mark-status`（`contractStatusLabel`）、`position-mark-stale`（期权链 `staleLabel`）、`position-quote-time`（`MM-DD HH:mm` / `—`）。股票行（`stock-position-row` 内）：`stock-position-price`、`stock-position-price-manual`、`stock-position-price-clear`、`stock-position-price-set`、`stock-position-price-stale`、`stock-position-quote-time`。手动设置对话框：`manual-quote-modal`、`manual-quote-target`、`manual-quote-current`、`manual-quote-price`（输入）、`manual-quote-error`（`data-error-code`）、`manual-quote-submit`、`manual-quote-cancel`；成功 / 清除提示为 antd message（`已设置手动标的价` / `已设置手动 Mark` / `已清除手动值`）
- 未实现盈亏（FEAT-017）：前端不算市值 / 盈亏 / 百分比 / 合计，全部取后端字段，只格式化。期权表在 `Mark` 后加 `市值`（`formatMoney` 带显式 `+` / `−`）、`未实现盈亏`（带符号，正绿负红，下面小字 `formatPercent` 百分比；`unrealizedPnlPercent = null` 显示 `—`）；股票表在 `现价` 后加同样两列（市值同样带显式符号，Q162）。`unrealizedStatus ≠ OK` 时两列都原样显示后端 `unrealizedLabel`（灰色，无百分比）；旧响应缺字段显示 `—`。持仓页顶部一行（截至 / 持仓数 / 冻结）之下为合计行 `UnrealizedSummaryBar`（`GET /api/positions/unrealized-summary`，`useAccountQuery`，key `['unrealized-summary']`）：`期权 市值 ±X 未实现 ±Y ｜ 股票 … ｜ 合计 …`（Q162：所有市值都带显式符号，正数 `+`、负数 `−`、0 为 `0.00`；未实现带符号着色），`excludedLabel` 非 null 时原样灰字；下面说明"未实现盈亏已含开仓手续费…仅供参考"。合计不受到期过滤影响（无参数）。凡失效 `option-positions` 的地方都同时失效 `unrealized-summary`（`refreshAfterChange`、`refreshQuotes`、作废 / 批量作废 / 调整到期日对话框）；新增会让持仓重新查询的操作也要加上。合计请求失败只在合计行显示错误 + "重新加载合计"按钮，不影响持仓表
- FEAT-017 testid：期权行（`position-row` 内）`position-market-value`、`position-unrealized-pnl`（两者 `data-status` = `OK` / `NO_QUOTE` / `EXPIRED_PENDING`，旧响应为空）、`position-unrealized-pnl-percent`（仅可计算时存在）；股票行（`stock-position-row` 内）`stock-position-market-value`、`stock-position-unrealized-pnl`、`stock-position-unrealized-pnl-percent`（同上）。合计行：`unrealized-summary`（容器）、`unrealized-summary-totals`（整句文字）、`unrealized-summary-option` / `-stock` / `-total`（各组 `期权 市值 ±X 未实现 ±Y`）、`unrealized-summary-{option|stock|total}-market-value`、`unrealized-summary-{option|stock|total}-unrealized-pnl`、`unrealized-summary-excluded`（`excludedLabel`，null 时不存在）、`unrealized-summary-note`（说明）、`unrealized-summary-loading`、`unrealized-summary-error`、`unrealized-summary-retry`
- 单合约 Greeks（FEAT-018）：前端不算任何 Greeks / 持仓值，只格式化后端 `positions[].greeks`。期权表上方 `显示 Greeks` 开关（antd `Switch`，默认关闭，Q173）；关闭时表格与 FEAT-017 完全相同、不显示说明。开关状态记在 localStorage（key `option-mgr.showGreeks`，值 `true`；关闭时删除；读写 try/catch，存储不可用时按关闭），因此切换账户（页面按账户重新挂载）、刷新页面后保持。打开后：开关右侧灰字口径说明 `IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考`（100 固定写，Q161）；`未实现盈亏` 之后加 `IV`、`Delta`、`Gamma`、`Theta`、`Vega` 五列（不显示 Rho），列头文字后有圆圈 `?`，悬停显示通俗解释（需求开头的解释，含持仓值含义）。`status ≠ OK` 时五列都原样显示后端 `label`（灰色，无数字）；`OK` 时 IV 为 `formatPlainPercent(ivPercent)`（`35.12%`，无 `+`），Delta–Vega 每格上面持仓值 `formatMoney(…, { signed: true })`（`+30.21` / `−5.12` / `0.00`，不着色——Greeks 不是盈亏），下面灰色小字每张原值 `formatMoney`（`−0.3021` / `0.0512`，正数无 `+`，保留后端 4 位）；单字段 `null` → 持仓值和每张值两行都显示 `—`；`ivPercent = null` → `—`；旧响应缺 `greeks` → 五列 `—`。`manualMarkNote` 非 null 时在 IV 值下方灰色小字原样整句显示（不遮挡数值）。刷新沿用 `option-positions` 的失效（立即刷新、手动值、写操作、切换账户），不新增请求
- FEAT-018 testid：开关 `positions-greeks-toggle`（`aria-checked` = `true` / `false`；旁边文字 `显示 Greeks`）、说明 `positions-greeks-note`（仅打开时存在）。列头 `greeks-header-{iv|delta|gamma|theta|vega}`（标题 + `?`，`innerText` 如 `Theta?`）、`greeks-header-{…}-help`（`?` 图标，悬停出 antd tooltip）；注意 antd 表格 `scroll.x` 的 measure 行会再渲染一份列头，按 testid 取列头 / `?` 时用第一个（`.first()`，即 thead 中的那个）。期权行（`position-row` 内，仅打开时存在）：`position-greeks-iv`（`35.12%` / `—` / 后端 label，`data-status` = `OK` / `NO_QUOTE` / `EXPIRED_PENDING`，旧响应为空）、`position-greeks-manual-note`（手动 mark 标注整句，无标注时不存在）、`position-greeks-{delta|gamma|theta|vega}`（格容器，`data-status` 同上；非 OK 或旧响应时文字即 label / `—`，没有子元素）、`position-greeks-{…}-position`（持仓值，带显式符号或 `—`）、`position-greeks-{…}-per-contract`（每张原值或 `—`）
- 表单只做格式校验，文案尽量与后端 `details[].message` 一致；业务校验（担保、现金、持仓方向、multiplier）只在后端，422 的 `message` 原文显示
- 交易方向显示 `代码（中文）`：BTO 买入开仓、STO 卖出开仓、BTC 买入平仓、STC 卖出平仓；FEAT-012 的 `EXP` 只显示"到期作废"（交易列表、已平仓用紫色标签）；FEAT-013 的 `ASG` 只显示"被指派"（洋红标签，`SPECIAL_ACTION_COLORS`）；未知值原样显示
- 平仓：张数只能从后端 `allowedCloseContracts` 中选，按钮按 `closable` 显示，方向取 `closeAction`；已实现盈亏、被平批次、冻结释放全部取后端值
- 到期作废（FEAT-012）：持仓操作列只按后端 `closable` / `expiryPending` / `expirable` 三个互斥标志显示（平仓 / 无按钮 + "到期待确认" / 作废），前端不判断时间；作废请求的 `contracts` 原样用 `expiryContracts`；`EXP` 记录只能删除不能修改（按 `action` 判断），删除确认框用专门的提示（`DeleteConfirmModal` 的 `warning`）
- 被指派（FEAT-013）：持仓操作列的"被指派"按后端 `assignable` 独立显示（可与平仓 / 作废并列，到期待确认时是唯一按钮）；对话框张数只能从 `assignChoices` 中选（默认最后一项），日期默认 `assignmentDateDefault`、`max` 为 `assignmentDateMax`，日期范围、持仓、现金都由后端 422 判定并原文显示；指派费空 = 发 `null`；结果全部取响应。`ASG` 与 `EXP` 一样只能删除（`isDeleteOnlyAction`），交易列表净额列显示 `netAmount` 0.00（不着色），合约下方小字 `assignmentRowText`；已平仓行盈亏下方小字 + 链接到股票页（`stocksUrlForAssignment`：标的 + `type=ASSIGN_BUY` + 当日）；股票页 `ASSIGN_BUY` 行"修改 / 删除"按后端 `editable` / `deletable` 显示，金额列 `成本 X`，标的下方 `来自被指派 #id` 链接到交易列表（标的 + `action=ASG` + 当日）。股票类型分两组：`STOCK_TRADE_TYPES`（可录入）与 `STOCK_RECORD_TYPES`（筛选，含 `ASSIGN_BUY`）。审计 `before.action = ASG` 时多显示指派日期、指派费、股数、买股金额、股票成本、股票记录 ID
- 卖出 call 被指派（FEAT-041）：沿用 FEAT-013 的对话框和各页面，按 `optionType`（`isCallAssignment`）分支，前端不计算。持仓页空头 call 行同样按 `assignable` 显示"被指派"；对话框把"冻结现金"换成后端 `underlyingShares`（`assign-underlying-shares`），指派费说明和说明段换成 call 文案；结果显示行权金额 / 卖出实收 / 交出成本 / 股票盈亏（`assign-result-sale-amount`、`assign-result-proceeds`、`assign-result-sold-cost`、`assign-result-stock-pnl`）和交出批次表（`assign-result-sold-lots`，复用 `SoldLotTable`）；`SHARES_INSUFFICIENT` / `ASSIGNMENT_PROCEEDS_NOT_POSITIVE` 原文显示。put 专用字段对 call 为 `null`、call 专用字段对 put 为 `null`（类型中新字段为可选）。交易列表小字"卖出 N 股"，删除摘要 / 警告按 call 区分。已平仓：期权行小字"权利金已计入股票卖出（…实收 X）"、链接到 `stocksUrlForAssignment(…, 'ASSIGN_SELL')`；股票 `ASSIGN_SELL` 行用"指派卖出"标签 + `来自被指派 #id`（`closed-stock-assignment-link`，取 `assignmentTradeId`）；页面说明加 call 一句（`closed-call-assignment-note`）。股票页 `ASSIGN_SELL`：金额列 `实收 X`（不着色）、盈亏照常、可展开被卖出批次，无修改 / 删除（沿用 `stock-assign-buy-hint` 提示），类型筛选多一项（`stock-filter-type-assign-sell`），不进录入表单（`STOCK_TRADE_TYPES` 不含）。审计：`before.action = ASG` 且为 call 时显示权利金、行权金额、卖出实收、交出成本、股票盈亏；连带变化照常原文显示
- 主动行权（FEAT-014）：沿用被指派的对话框和各页样式，按 `optionType` 区分 call / put，前端不计算。持仓操作列"行权"只按后端 `exercisable`（缺省当 false）显示，可与平仓 / 作废并列、到期待确认时是多头行唯一按钮。`ExercisePositionModal`：张数只能从 `exerciseChoices` 选（默认最后一项），日期默认 `exerciseDateDefault`、`max` 为 `exerciseDateMax`；call 显示 `availableCash`（"可用现金 X"），put 显示 `underlyingShares` / `availableShares`（"当前持股 X 股，可用 Y 股"）；行权费空 = 发 `null`；422 原文显示，400 字段错误显示在字段下；结果取响应（call：买股金额 / 股票成本 / 每股；put：行权金额 / 卖出实收 / 交出成本 / 股票盈亏 / 交出批次表）。**`underlyingShares` / `availableShares` 不能用来判断"空头 call 行"**（多头 put 行也有值），一律看 `direction` + `optionType`（`coverageTags`、冻结列已如此）。`EXR` 只能删除（`isDeleteOnlyAction`，金色标签），净额列 `netAmount` 0.00 不着色，小字 `exerciseRowText`；已平仓期权行小字"权利金已计入股票成本 / 已从股票卖出实收中扣除" + 链接到股票页（`stocksUrlForAssignment(…, 'EXERCISE_BUY' | 'EXERCISE_SELL')`），股票 `EXERCISE_SELL` 行 `来自行权 #id`（`exerciseTradeId`）；股票页 `EXERCISE_BUY` / `EXERCISE_SELL`：`成本 X` / `实收 X`、`来自行权 #id`、提示"由行权生成，在交易列表删除"，修改 / 删除按后端 `editable` / `deletable`；流水来源 `OPTION_EXERCISE` 显示"行权"；审计 `before.action = EXR` 按 call / put 多显示行权字段。新字段在类型中声明为可选（兼容旧响应 / 旧测试数据）
- 批量作废（FEAT-015）：持仓页到期过滤为"已过期未处理" / "今天到期"时，期权表上方 `批量作废（N）`（`positions-batch-expire`），N = 显示行中 `expirable` 的个数，0 不显示；前端不判断时间。`BatchExpireModal` 用打开时的行快照，默认全选，请求项同 FEAT-012（`contracts` = `expiryContracts`），只发勾选的行。422 `BATCH_EXPIRATION_REJECTED` 的 `failures[]` 通过 `ApiError.body`（契约错误体原样保存）读取（`expirationBatchFailures`），逐项按合约（标的大写、行权价去尾零）显示在对应行下，对不上时按 `index`；成功响应 `expirations[]` 也按合约对应到行，不依赖下标。合计、每行盈亏和冻结变动全部取后端值；成功后 `refreshAfterChange`
- covered call 覆盖（FEAT-039）：前端不算覆盖。持仓页空头 call 行的冻结现金列不再显示"未冻结"，改由 `position/coverageLabels.coverageTags` 按后端 `coveredShares` / `uncoveredContracts` / `uncoveredShares` / `availableShares` / `uncoveredShortfallAtLocal` / `uncoveredShortfallHeldShares` 拼出 `已覆盖 N 股`（`position-cell-coverage-checked`）和 / 或 `未覆盖`（两种都有时 `未覆盖 k 张`，`position-cell-coverage-legacy`）及悬停文字（规则 7）；字段缺失时显示 `—`。股票持仓表在持股后加"被覆盖""可用"列（`stock-position-covered-shares`、`stock-position-available-shares`）。录入页 STO + Call 提示换成规则 7 文案。`COVERAGE_INSUFFICIENT`（422，`details` 为空）在录入 / 修改 / 删除 / 被指派各处沿用 `applySubmitError` / `DeleteConfirmModal` 原文显示；审计 `sideEffects` 的 `coverage` 项按 `text` 原样显示
- 修改 / 删除（FEAT-005）：对话框里不可改字段只显示；`PUT` 整体替换可改字段（每次发送全部可改字段）；422 原文显示、输入保留；删除用通用确认框，被拒时对话框不关闭。审计记录的 `changes[].text`、`sideEffects[].text` 原样显示
- 已平仓（FEAT-006）：筛选复用 `TradeFilterForm`（参数 `actions`、`dateLabelPrefix`、`testIdPrefix`）与 `tradeListFilters`（`validateTradeFilters` 可传允许的方向）；笔数、合计、每张平均、盈亏全部取后端值
- 已平仓的股票卖出（FEAT-040）：`ClosedTrade` 是按 `type` 区分的联合类型（`ClosedOptionRecord` / `ClosedStockRecord`），渲染按 `type` 分支；行 key、"历史"（`OPTION_TRADE` / `STOCK_TRADE`）都用类型 + id（两类 id 可能相同）。股票行：标的链接 `stocksUrlForSymbol`、方向为橙色"卖出"标签（无空头 / 多头）、`N 股`、成本 / 实收下方 `每股 X.XXXX`（后端 4 位小数原样）、展开为被卖出批次（时间：转入只显示日期；来源 买入 / 转入；股数；带符号成本）。`TradeFilterForm` 的 `recordType` 选项加"类型 不限 / 期权 / 股票"（`optionTypeLabel` 把 Call/Put 那项改名为"Call / Put"以免两个"类型"）：选"股票"时到期日 / 行权价 / Call·Put / 方向禁用，切换到或离开"股票"时清空这四项（地址里带来的值在切换前保留并禁用显示）；类型"不限"且这四项有值时显示提示。类型的合法性在前端按契约文案校验，后端 `details[].field = "type"` 也显示在类型下
- 盈亏汇总（FEAT-040）：期权 / 股票列按 `closedCount === 0` 显示 `—`，否则正绿负红；合计列加粗；不相加，全部取后端的 `option` / `stock` / 合计
- 首页 / 盈亏汇总（FEAT-008）：所有金额和个数取后端值，汇总表的合计行用响应的总计，前端不相加；前端只做日期计算（月份首末日、与当前范围取交集）
- 持仓 DTE、到期状态、平均净额、冻结等全部取后端字段显示（`expirationStatus` 决定"今天到期"/红色"已过期 N 天"），前端不做日期或金额计算
- 现金流水：已知 `sourceType` 显示中文（`DEPOSIT` 入金、`WITHDRAWAL` 出金、`OPTION_OPEN` 期权开仓、`OPTION_CLOSE` 期权平仓、`OPTION_EXPIRE` 到期作废、`OPTION_ASSIGN` 被指派、`STOCK_BUY` 股票买入、`STOCK_SELL` 股票卖出），未知值原样显示；`frozenChange` 单独一列，0 显示 `—`；只有入金/出金行可修改删除，期权行链接到 `/trades`、股票行链接到 `/stocks`
- 股票（FEAT-011）：股数用 `formatInteger` 千分位；每股价格 / 平均成本原样显示后端的 4 位小数（经 `formatMoney`，不四舍五入）；金额列买入/卖出显示带符号的 `cashChange`（正绿负红），转入显示 `总成本 X`（不着色）；时间：转入显示 `tradeDate`，其它显示 `tradedAtLocal` 到分钟；被卖出成本、已实现盈亏、批次全部取后端值。录入/修改按类型只发送对应时间字段（买入/卖出 `tradedAtLocal`，转入 `tradeDate`）；修改不发送 `symbol` / `type`（只显示）。审计对象类型 `STOCK_TRADE` 显示"股票交易 #id"
