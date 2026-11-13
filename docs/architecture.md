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
├── audit/             FEAT-005 审计记录：只追加，GET /api/audit-log（见下"修改、删除与审计"）
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
- 时间：`Clock` bean（UTC）是"现在"的唯一来源，测试可替换；JSON 时区 UTC；Hibernate JDBC 时区 UTC
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
- `PositionLot` 有原值/剩余值（张数、净额、冻结）；整笔平仓规则（Q21）下剩余值总等于原值，批次被平即整体移除。`PositionBuilder.build` = `ContractReplay` 的 `openLots`；`OptionPosition` 另给出 `closeAction`、`allowedCloseContracts`、`closable(today)`（today ≤ 到期日，Q36）
- 交易对应的流水来源类型集中在 `OptionTradeService.TRADE_SOURCE_TYPES`（`{OPTION_OPEN, OPTION_CLOSE}`）；以后新的交易来源（如 M2 到期作废/指派）加到这里。列表项和按 id 查询的平仓带 `coveredLots`、`realizedPnl`（同一页按合约各重放一次）
- **已平仓记录（FEAT-006）**：`GET /api/closed-trades`（`web/ClosedTradeController` → `service/ClosedTradeService`）。筛选复用 FEAT-002 的 `TradeListQuery`（`action` 只允许 BTC/STC）和 `OptionTradeSpecifications`。因为合计要覆盖全部筛选结果（Q46），每次请求取出**全部**符合条件的平仓（按 `traded_at DESC, id DESC`），用 `OptionTradeService.views` 按合约重放得到被平批次和盈亏（每个合约一次），算合计后在内存中分页。`domain/ClosedTrade` 给出开仓净额合计、每张平均（HALF_UP，Q22）、平仓净额与每张平均、盈亏。个人系统数据量小，每次请求重放所有涉及的合约可以接受；数据量大时可改为缓存匹配结果
- **盈亏汇总与首页概览（FEAT-008）**：`GET /api/pnl-summary`、`GET /api/dashboard`（`web/PnlSummaryController` → `service/PnlSummaryService`）。不另算盈亏：平仓与盈亏来自 `ClosedTradeService.all(filter)`（与 `/api/closed-trades` 同一份），`domain/PnlSummary` 按标的（字符序升序）和平仓的美东自然月（倒序，无平仓的月份不出现）分组；首页的持仓数字来自 `OptionPositionService.currentPositions()`，现金来自 `CashLedgerService.currentBalance()`，所以各接口的数字天然一致。"今天 / 本月" = 注入 `Clock` 的美东日期 / 月；本月 = `startDate = 本月 1 日` 的平仓（不会有晚于当前的交易）。日期参数复用 `TradeListQuery.parse`（同样的错误格式）。每次请求重放所有涉及的合约，个人数据量下可以接受

### 修改、删除与审计（FEAT-005）
- 接口：`PUT` / `DELETE /api/option-trades/{id}`（`service/OptionTradeEditService`），`PUT` / `DELETE /api/cash/transfers/{id}`（`cash/service/CashTransferEditService`），`GET /api/audit-log`（`audit/`）
- 原则：把改动应用到全部历史上重新检查，任何一笔交易或任何时点不成立就整笔拒绝，什么都不写。都在一个事务里，先拿 `cash_account` 行锁
  - 交易：用同一个 id 替换（修改）或去掉（删除）该笔 `LotTrade`，`ContractReplay` 重放整个合约；被修改的那笔本身违规 → "在成交时……"文案，其它交易违规 → "修改后/删除后……"文案（`TradeRejectionMessages.of(..., verb)`）；删除被平仓覆盖的开仓且那笔平仓因此违规 → `deletedOpenStillClosed`
  - 现金：`CashLedgerService.replayWithChanges(replacements, removals)` 用修改后的流水（同一流水 id，即录入顺序不变）重放整个现金流水，`CashReplay.firstViolation` 找第一个总现金/可用 < 0 的时点（Q19）；之后的 STO put 不重查 Q18（Q41），被修改的 STO put 本身在张数/multiplier/时间变化时按 Q18 检查
  - 通过后：交易实体 `update(...)`、流水 `CashLedgerEntryEntity.update(...)`（原地，id 不变）或删除；其它平仓释放冻结的改正（`OptionTradeService.frozenCorrections`）与新增交易时相同，但记入审计的 `sideEffects`（Q42）
  - 修改内容与原记录相同 → 422 `NO_CHANGES`，不写任何东西
- 审计表 `audit_record`（`V4__audit_record.sql`）：只追加（实体 `@Immutable`，没有修改/删除接口）。`before_json` / `after_json`（完整内容）、`changes_json`（显示用的字段变化）、`side_effects_json`（连带变化）以 JSON 文本存 `VARCHAR(10000)`，读取时原样作为 JSON 对象返回。`AuditService.record` 要求在调用方事务里（`Propagation.MANDATORY`），与改动一起提交或回滚
- 审计对象被删除后仍可在审计记录里按 `objectType` + `objectId` 查到（不设外键）
- e2e 重置按元数据自动包含 `audit_record`（`TestSupportResetE2eProfileTest` 已断言）

### 多账户（FEAT-033）
- 表 `account`（`V5__accounts.sql`）：`name`、`name_key`（去空白 + 小写，唯一约束 = 不区分大小写唯一）、`broker_note`、`created_at`。账户 1"默认账户"由迁移写入（空库也有）。`cash_transfer`、`cash_ledger_entry`、`option_trade`、`audit_record` 都加了 `account_id NOT NULL`（外键到 `account`），迁移时已有行全部为 1。`cash_account` 变为每个账户一行（`id` = 账户 id），仍是该账户现金写操作的行锁；新账户由 `CashLedgerService.openAccount` 建行
- **账户上下文**：请求头 `X-Account-Id`。`account/web/AccountRefArgumentResolver` 把它解析成 `AccountRef`（缺少 → 400 `ACCOUNT_REQUIRED`，非整数 → 400 `BAD_REQUEST`，不存在 → 404 `ACCOUNT_NOT_FOUND`）。所有账户范围的 controller 方法把 `AccountRef account` 作为**第一个参数**（所以在请求体校验之前检查），再把 `account.id()` 显式传给 service；没有隐式的"当前账户"状态。选请求头而不是路径前缀：已有路径和地址栏筛选参数都不变（Q55），前端只需在统一请求封装里加一个头
- 隔离：所有 repository 查询都带 `accountId`（`findByIdAndAccountId`、`findAllByAccountId…`、`OptionTradeSpecifications.matching(accountId, filter)` 等），别的账户的记录在查询层面就"不存在"（404）。`CashLedgerService` 的所有方法都按账户（锁、重放、流水），改写流水时还校验流水属于该账户。合约重放、FIFO、持仓、盈亏、汇总、首页、审计全部只在一个账户内
- 账户审计：`AuditObjectType.ACCOUNT`、`AuditOperation.CREATE`（`before` 为 `null`），记在该账户自己的审计里
- **迁移前备份（Q60）**：`config/DatabaseBackupBeforeMigration` 是一个 `FlywayMigrationStrategy`：有待执行迁移、已有迁移记录、且是 H2 文件库（`DATABASE_PATH()` 非空）时，先执行 H2 `BACKUP TO '<库所在目录>/<库名>-backup-before-V<最高待执行版本>-<UTC 时间>Z.zip'`（在线一致性备份），确认文件存在且非空后才 `migrate()`；任何失败都抛异常 → 应用不启动、不迁移。内存库、新库不备份。**H2 的 DDL 会各自提交，迁移中途失败无法自动回滚，所以备份是回退手段**：停应用 → 移走 `<库名>.mv.db` → 把 zip 里的 `<库名>.mv.db` 解压到原目录 → 用旧版本启动
- e2e 重置：快照包含 `account`（只有 1 号）和 `cash_account`（只有 1 号），所以重置后只剩"默认账户"、下一个账户 id 为 2；重置时锁全部账户行（`lockAllAccounts`）
- 测试：`account/MigrationFromM1Test` 用 Flyway `target=4` 建 M1 结构的文件库、写入场景 M（通过 API 在临时内存库生成后按 id 原样拷贝），再启动应用执行备份 + V5，逐个接口比较迁移前后的 JSON 完全相同；另有迁移失败、备份失败两个场景

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
    │   └── <feature>.ts  每个领域一个文件：请求函数 + queryKey（health.ts；cash.ts: getCashSummary/getCashLedger/createCashTransfer、cashQueryKeys；optionTrades.ts: createOptionTrade/createCloseTrade/getOptionTrade/listOptionTrades/updateOptionTrade/deleteOptionTrade；cash.ts 另有 updateCashTransfer/deleteCashTransfer；auditLog.ts: getAuditLog、auditLogQueryKeys；closedTrades.ts: listClosedTrades、closedTradeQueryKeys；dashboard.ts: getDashboard、getPnlSummary 及其 queryKey；accounts.ts: listAccounts/createAccount/updateAccount、accountQueryKeys、optionTradeQueryKeys；optionPositions.ts: getOptionPositions、optionPositionQueryKeys）
    ├── pages/            路由级页面组件（HomePage、CashPage、TradeEntryPage、TradeListPage、PositionsPage、ClosedTradesPage、PnlSummaryPage、AuditLogPage、AccountsPage、NotFoundPage）
    ├── components/       跨页面复用组件：QueryError（查询错误 + 重试）、DeleteConfirmModal（删除确认，FEAT-005）
    │   ├── account/      AccountSwitcher（顶部账户切换器，FEAT-033）
    │   ├── cash/         CashSummaryCard（可复用现金汇总）、CashTransferForm + cashTransferValidation、CashLedgerTable
    │   └── trade/        TradeEntryForm（录入交易，开仓 + 平仓）、EditTradeModal（修改交易，FEAT-005）+ openTradeValidation、TradeConfirmation（开/平仓确认，含被平批次与已实现盈亏）、tradeFormSupport（字段错误映射、保存后刷新、净额标签）；TradeFilterForm、TradeTable、tradeListFilters（FEAT-002 列表筛选与 URL 状态）；tradeLabels（方向文案、正负颜色，共用）
    │   ├── audit/        AuditTable（可展开修改前后）、auditSearch（审计页 URL 状态与校验）、auditLinks（各行"历史"链接）（FEAT-005）
    │   ├── pnl/          pnlSummarySearch（盈亏汇总页 URL 状态、月份范围交集、到已平仓页的链接）（FEAT-008）
    │   ├── closed/       ClosedTradeTable（已平仓记录，可展开被平批次）（FEAT-006）
    │   └── position/     PositionTable（可展开批次、平仓按钮）、ClosePositionModal（平仓对话框，FEAT-004）、positionLinks（合约 → 交易列表 URL，`tradesUrlForContract`，持仓和已平仓共用）（FEAT-003）
    ├── utils/format.ts   统一的显示格式化（时间、金额）
    └── test/             setup.ts（jest-dom、matchMedia / ResizeObserver polyfill、清理 mock、清空当前账户和 localStorage）、render.tsx（renderWithProviders、jsonResponse、mockFetch——默认回答 `GET /api/accounts`）、userEvent.ts（无延迟、不做 pointer-events 检查的 user-event）、homeFixtures.ts
```
测试文件与被测文件放在一起：`*.test.ts(x)`。

### 路由
| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | `HomePage` | FEAT-008 首页概览（只读）：现金卡（`CashSummaryCard`）、已实现盈亏卡与持仓卡（`GET /api/dashboard`）、录入交易入口；底部一行后端状态（`GET /api/health`） |
| `/pnl-summary` | `PnlSummaryPage` | FEAT-008 盈亏汇总：平仓日期范围（URL `startDate`/`endDate`）、按标的 / 按月两个表格（合计行用后端总计），标的/月份链接到 `/closed`（导航"盈亏汇总"） |
| `/trades` | `TradeListPage` | FEAT-002 交易列表：筛选 + 只读表格 + 分页；筛选/页码/每页条数都在 URL 查询参数里（导航"交易"） |
| `/positions` | `PositionsPage` | FEAT-003 期权持仓：截至日期/持仓数/冻结合计 + 持仓表（可展开 FIFO 批次，合约链接到 `/trades` 筛选；FEAT-004 可平仓行有"平仓"按钮，打开平仓对话框）（导航"持仓"） |
| `/trades/new` | `TradeEntryPage` | 录入交易（FEAT-001 开仓 BTO/STO + FEAT-004 平仓 BTC/STC，按方向调用不同接口）：现金汇总 + 表单 + 成功确认（导航"录入交易"） |
| `/closed` | `ClosedTradesPage` | FEAT-006 已平仓记录：筛选结果笔数 + 已实现盈亏合计，平仓表格（可展开被平批次），筛选/分页/URL 同交易列表，方向只有 BTC/STC（导航"已平仓"） |
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
- 写操作成功后 invalidate 该领域的根 key（如 `cashQueryKeys.all` = `["cash"]`），依赖它的组件（如 `CashSummaryCard`）自动刷新；以后任何改变现金的操作也要 invalidate `["cash"]`；任何新增/修改交易的操作还要 invalidate `optionTradeQueryKeys.lists` = `["option-trades","list"]` 和 `optionPositionQueryKeys.all` = `["option-positions"]`；FEAT-005 起所有写操作（新增、修改、删除交易或入金出金）统一调用 `tradeFormSupport.refreshAfterChange`，invalidate `["cash"]`、`["option-trades"]`、`["option-positions"]`、`["audit-log"]`、`["closed-trades"]`（FEAT-006）、`["dashboard"]`、`["pnl-summary"]`（FEAT-008）；新增入金/出金也调用它
- 列表页的筛选/分页状态以 URL 查询参数为唯一来源（`useSearchParams`），改变时 push 历史，后退可恢复；URL 中不合法的筛选值回填并在字段下报错、不发请求，不合法的 page/size 回退到默认值
- 表单提交失败：`VALIDATION_ERROR` / `BAD_REQUEST` 的 `details[].field` 用 `form.setFields` 显示到同名字段；无法对应字段的错误和业务错误（如 `CASH_INSUFFICIENT`，message 是中文可直接显示）显示在提交按钮上方；保留用户输入

### 显示约定
- 金额等小数：后端给字符串，前端只格式化不计算；格式化函数统一放 `src/utils/format.ts`
- 时间点（Instant）用 `formatInstantNY()` 显示为 `YYYY-MM-DD HH:mm:ss EST|EDT`，或 `formatDateTimeNY()` 显示为 `YYYY-MM-DD HH:mm`（列表用），均为 America/New_York；空值/非法值显示 `—`
- 金额用 `formatMoney(value, { signed? })`：纯字符串处理（不转浮点、不四舍五入），千分位、至少 2 位小数、负号 `−`(U+2212)，`signed` 时正数加 `+`；颜色用 `decimalSign()` 判断正负（正绿 `#389e0d`、负红 `#cf1322`）
- 用户录入的时间：原生 `datetime-local` 输入美东本地时间，原样以 `YYYY-MM-DDTHH:mm` 发给后端（`occurredAtLocal` 之类字段），前端不做时区换算；默认值用 `nowLocalDateTimeNY()`
- 交互元素和关键显示值加 `data-testid`，供 E2E 使用。已有：`nav-home`、`nav-cash`、`health-card`、`health-loading`、`health-status`、`health-version`、`health-time`、`health-error`、`health-retry`、`not-found`
- FEAT-007 资金页的 testid 列表见 `docs/requirements/FEAT-007-cash-account.md` 的 Frontend Implementation Notes；FEAT-001 录入开仓页见 `docs/requirements/FEAT-001-open-option-trade.md`；FEAT-002 交易列表见 `docs/requirements/FEAT-002-trade-list.md`；FEAT-003 持仓见 `docs/requirements/FEAT-003-option-positions.md`
- 用户录入的日期（如到期日）用原生 `type="date"`，原样以 `YYYY-MM-DD` 发送
- 表单只做格式校验，文案尽量与后端 `details[].message` 一致；业务校验（担保、现金、持仓方向、multiplier）只在后端，422 的 `message` 原文显示
- 交易方向显示 `代码（中文）`：BTO 买入开仓、STO 卖出开仓、BTC 买入平仓、STC 卖出平仓，未知值原样显示
- 平仓：张数只能从后端 `allowedCloseContracts` 中选，按钮按 `closable` 显示，方向取 `closeAction`；已实现盈亏、被平批次、冻结释放全部取后端值
- 修改 / 删除（FEAT-005）：对话框里不可改字段只显示；`PUT` 整体替换可改字段（每次发送全部可改字段）；422 原文显示、输入保留；删除用通用确认框，被拒时对话框不关闭。审计记录的 `changes[].text`、`sideEffects[].text` 原样显示
- 已平仓（FEAT-006）：筛选复用 `TradeFilterForm`（参数 `actions`、`dateLabelPrefix`、`testIdPrefix`）与 `tradeListFilters`（`validateTradeFilters` 可传允许的方向）；笔数、合计、每张平均、盈亏全部取后端值
- 首页 / 盈亏汇总（FEAT-008）：所有金额和个数取后端值，汇总表的合计行用响应的总计，前端不相加；前端只做日期计算（月份首末日、与当前范围取交集）
- 持仓 DTE、到期状态、平均净额、冻结等全部取后端字段显示（`expirationStatus` 决定"今天到期"/红色"已过期 N 天"），前端不做日期或金额计算
- 现金流水：已知 `sourceType` 显示中文（`DEPOSIT` 入金、`WITHDRAWAL` 出金、`OPTION_OPEN` 期权开仓），未知值原样显示；`frozenChange` 单独一列，0 显示 `—`
