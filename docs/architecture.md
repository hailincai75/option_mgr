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
├── config/            全局配置：ClockConfig（唯一的 Clock bean）、JacksonConfig（BigDecimal→字符串）
├── common/error/      统一错误：ErrorCode、ErrorResponse、ApiException、GlobalExceptionHandler
├── common/money/      Money：金额 scale 2、RoundingMode.UNNECESSARY、给用户看的金额格式（千分位、U+2212 负号）
├── common/time/       MarketTime：美东本地时间 ↔ UTC Instant（America/New_York，java.time 默认 DST 规则）
├── health/            GET /api/health
├── cash/              FEAT-007 资金账户：入金/出金、现金汇总、统一现金流水（见下"现金流水模型"）
├── trade/             FEAT-001 期权交易（目前只有开仓 BTO/STO）与按时点持仓重放、FEAT-002 交易列表与筛选（见下"期权交易与持仓模型"）
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
  - `domain/`（纯 Java）：`ContractKey`（合约标识 + 显示名 `AAPL 2026-12-18 200 P`，strike 去尾零）、`Symbols`（标的规范化）、`OptionType`、`TradeAction`（方向符号、是否开仓）、`OpenTradeTerms`（现金变动、担保 = 行权价 × multiplier × 张数 按分向上取整、流水说明）、`PositionEvent` / `PositionReplay` / `PositionStep` / `PositionInsertionCheck`（按时点持仓）、`TradeRejectionMessages`（中文拒绝原因）
  - `service/OptionTradeService`：一个事务内按需求顺序检查 → 保存交易 → 追加流水
  - `web/`：`OptionTradeController`（`POST /api/option-trades`、`GET /api/option-trades/{id}`）、`OptionTradeDtos`、`@OpeningAction` 校验
- **按时点持仓**：持仓不落库。某合约的持仓 = 该合约所有交易按 `(traded_at, id)` 顺序累加带符号张数（BTO/BTC +，STO/STC −）。`PositionReplay.checkInsertion(existing, PositionEvent.pending(...))` 把新交易放在同一时间已有交易之后，返回插入点之前的持仓及**从插入点起**第一个多空冲突（Q7：开仓方向与非零持仓相反）和第一个 multiplier 不一致（Q23：持仓非零时交易 multiplier ≠ 持仓的 multiplier）。持仓回到 0 时 multiplier 清空
- 并发：交易事务先调 `CashLedgerService.lockAccount()`（与入金/出金同一把 `cash_account` 行锁），所以持仓检查、现金检查和写入都串行
- **FEAT-004 接入方式**：平仓（BTC/STC）也存进 `option_trade`，`PositionReplay` 已按符号处理平仓（减少持仓）；FEAT-004 在 `PositionStep` 上加平仓检查（如平仓张数 ≤ 插入点持仓、方向必须与持仓相反、补录后之后的平仓仍不超量），新增 `CashSourceType.OPTION_CLOSE` 和释放冻结的负 `frozen_change`；FIFO 批次匹配按同一 `(traded_at, id)` 顺序
- 已知限制：每次开仓检查加载该合约的全部交易（按合约索引 `ix_option_trade_contract`）
- **交易列表（FEAT-002）**：`GET /api/option-trades`。`web/TradeListQuery` 解析/校验 query 参数（无法解析 → `BAD_REQUEST`，规则不满足 → `VALIDATION_ERROR`，`field` = 参数名）→ `domain/TradeFilter`（纯 Java；美东日期范围换算为 `[startDate 00:00 NY, endDate+1 00:00 NY)` 的 Instant 区间）→ `repository/OptionTradeSpecifications`（JPA Specification）→ 数据库分页，固定排序 `traded_at DESC, id DESC`。列表项与按 id 查询共用 `OptionTradeService.withLedgerEntries`：一页的交易一次批量查出对应流水（`CashLedgerService.findBySources`），取 `cashChange`/`frozenChange`/`ledgerEntryId`
- 交易对应的流水来源类型集中在 `OptionTradeService.TRADE_SOURCE_TYPES`（目前 `{OPTION_OPEN}`）。**FEAT-004 把 `OPTION_CLOSE` 加进这个集合**后，平仓交易会自动出现在列表与按 id 查询中，方向筛选已支持 BTC/STC

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
- 命令（在 `frontend/` 下）：`npm run dev`（5173，`/api` 代理到 `http://localhost:8080`）、`npm run build`（`tsc -b && vite build`）、`npm run lint`、`npm run typecheck`（`tsc -b --noEmit`）、`npm test`（`vitest run`，非 watch）、`npm run test:watch`

### 目录结构
```
frontend/
├── index.html
├── vite.config.ts        dev server 端口/代理 + Vitest 配置（jsdom、setupFiles）
├── eslint.config.js
├── tsconfig*.json        app（src/）与 node（vite.config.ts）两个 project
└── src/
    ├── main.tsx          入口：AppProviders + BrowserRouter + App
    ├── App.tsx           路由表与外层 Layout（Header + Content）
    ├── AppProviders.tsx  QueryClientProvider + antd ConfigProvider(en_US) + antd App；测试复用
    ├── queryClient.ts    QueryClient 默认配置
    ├── api/
    │   ├── types.ts      按 API 契约手写的请求/响应类型（含统一错误格式 ApiErrorResponse）
    │   ├── client.ts     fetch 封装 apiRequest<T>() 与 ApiError
    │   └── <feature>.ts  每个领域一个文件：请求函数 + queryKey（health.ts；cash.ts: getCashSummary/getCashLedger/createCashTransfer、cashQueryKeys；optionTrades.ts: createOptionTrade/getOptionTrade/listOptionTrades、optionTradeQueryKeys）
    ├── pages/            路由级页面组件（HomePage、CashPage、OpenTradePage、TradeListPage、NotFoundPage）
    ├── components/       跨页面复用组件：QueryError（查询错误 + 重试）
    │   ├── cash/         CashSummaryCard（可复用现金汇总）、CashTransferForm + cashTransferValidation、CashLedgerTable
    │   └── trade/        OpenTradeForm + openTradeValidation、OpenTradeConfirmation（FEAT-001）；TradeFilterForm、TradeTable、tradeListFilters（FEAT-002 列表筛选与 URL 状态）
    ├── utils/format.ts   统一的显示格式化（时间、金额）
    └── test/             setup.ts（jest-dom、matchMedia / ResizeObserver polyfill、清理 mock）、render.tsx（renderWithProviders、jsonResponse、mockFetch）
```
测试文件与被测文件放在一起：`*.test.ts(x)`。

### 路由
| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | `HomePage` | 调 `GET /api/health` 显示后端状态、版本、服务器时间（纽约时区） |
| `/trades` | `TradeListPage` | FEAT-002 交易列表：筛选 + 只读表格 + 分页；筛选/页码/每页条数都在 URL 查询参数里（导航"交易"） |
| `/trades/new` | `OpenTradePage` | FEAT-001 录入开仓（BTO/STO）：现金汇总 + 开仓表单 + 成功确认（导航"录入开仓"） |
| `/cash` | `CashPage` | FEAT-007 资金：现金汇总、入金/出金表单、现金流水（导航"资金"） |
| `*` | `NotFoundPage` | 404 |

### 服务端数据与错误处理
- 服务端数据只用 TanStack Query；不把服务端数据复制到全局 store。queryKey 与请求函数放在同一个 `src/api/<feature>.ts`
- QueryClient 默认：网络错误/5xx 重试 1 次，4xx 不重试；`refetchOnWindowFocus: false`；mutation 不重试
- `apiRequest` 总是抛 `ApiError { code, message, status, details, path }`：
  - 后端返回契约统一错误格式 → 原样带出 `code/message/details/path`；页面按 `code` 分支，`message` 可直接显示，表单把 `details[].field` 映射到对应字段
  - 请求没到服务器 → `code: 'NETWORK_ERROR'`，`status: 0`
  - 响应不是 JSON 或错误体不符合契约（例如 Vite 代理在后端未启动时返回的 502）→ `code: 'INVALID_RESPONSE'`
- 每个查询在页面上处理 loading / error（显示错误信息 + 重试按钮，可用 `components/QueryError`）/ 空数据三种状态
- 写操作成功后 invalidate 该领域的根 key（如 `cashQueryKeys.all` = `["cash"]`），依赖它的组件（如 `CashSummaryCard`）自动刷新；以后任何改变现金的操作也要 invalidate `["cash"]`；任何新增/修改交易的操作还要 invalidate `optionTradeQueryKeys.lists` = `["option-trades","list"]`（FEAT-001 开仓已这样做）
- 列表页的筛选/分页状态以 URL 查询参数为唯一来源（`useSearchParams`），改变时 push 历史，后退可恢复；URL 中不合法的筛选值回填并在字段下报错、不发请求，不合法的 page/size 回退到默认值
- 表单提交失败：`VALIDATION_ERROR` / `BAD_REQUEST` 的 `details[].field` 用 `form.setFields` 显示到同名字段；无法对应字段的错误和业务错误（如 `CASH_INSUFFICIENT`，message 是中文可直接显示）显示在提交按钮上方；保留用户输入

### 显示约定
- 金额等小数：后端给字符串，前端只格式化不计算；格式化函数统一放 `src/utils/format.ts`
- 时间点（Instant）用 `formatInstantNY()` 显示为 `YYYY-MM-DD HH:mm:ss EST|EDT`，或 `formatDateTimeNY()` 显示为 `YYYY-MM-DD HH:mm`（列表用），均为 America/New_York；空值/非法值显示 `—`
- 金额用 `formatMoney(value, { signed? })`：纯字符串处理（不转浮点、不四舍五入），千分位、至少 2 位小数、负号 `−`(U+2212)，`signed` 时正数加 `+`；颜色用 `decimalSign()` 判断正负（正绿 `#389e0d`、负红 `#cf1322`）
- 用户录入的时间：原生 `datetime-local` 输入美东本地时间，原样以 `YYYY-MM-DDTHH:mm` 发给后端（`occurredAtLocal` 之类字段），前端不做时区换算；默认值用 `nowLocalDateTimeNY()`
- 交互元素和关键显示值加 `data-testid`，供 E2E 使用。已有：`nav-home`、`nav-cash`、`health-card`、`health-loading`、`health-status`、`health-version`、`health-time`、`health-error`、`health-retry`、`not-found`
- FEAT-007 资金页的 testid 列表见 `docs/requirements/FEAT-007-cash-account.md` 的 Frontend Implementation Notes；FEAT-001 录入开仓页见 `docs/requirements/FEAT-001-open-option-trade.md`；FEAT-002 交易列表见 `docs/requirements/FEAT-002-trade-list.md`
- 用户录入的日期（如到期日）用原生 `type="date"`，原样以 `YYYY-MM-DD` 发送
- 表单只做格式校验，文案尽量与后端 `details[].message` 一致；业务校验（担保、现金、持仓方向、multiplier）只在后端，422 的 `message` 原文显示
- 交易方向显示 `代码（中文）`：BTO 买入开仓、STO 卖出开仓、BTC 买入平仓、STC 卖出平仓，未知值原样显示
- 现金流水：已知 `sourceType` 显示中文（`DEPOSIT` 入金、`WITHDRAWAL` 出金、`OPTION_OPEN` 期权开仓），未知值原样显示；`frozenChange` 单独一列，0 显示 `—`
