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
└── <feature>/         以后的功能，例如 trade/、position/
    ├── web/           @RestController + 请求/响应 DTO（record），只做参数校验和映射
    ├── service/       事务边界、编排
    ├── domain/        纯 Java 领域模型与计算（盈亏、成本、到期处理），不依赖 Spring，可直接单元测试
    └── repository/    Spring Data JPA 接口与实体
```
规则：controller 不直接用 repository；domain 不 import `org.springframework.*`；金额一律 `BigDecimal` 并显式指定 scale 与 `RoundingMode`。

### 配置
- `src/main/resources/application.yml`：端口 8080；H2 文件库 `backend/data/optionmgr.mv.db`（相对运行目录，`data/` 被 git 忽略；`MODE=PostgreSQL` 方便以后迁移），`AUTO_SERVER=TRUE` 允许外部工具同时连接
- 测试：`build.gradle.kts` 为所有测试设置 `spring.profiles.active=test`，`application-test.yml` 换成内存库 `jdbc:h2:mem:optionmgr-test`，不碰开发数据。QA 的 `systemtest/` 同样适用
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
    │   └── <feature>.ts  每个领域一个文件：请求函数 + queryKey（health.ts；cash.ts: getCashSummary/getCashLedger/createCashTransfer、cashQueryKeys）
    ├── pages/            路由级页面组件（HomePage、CashPage、NotFoundPage）
    ├── components/       跨页面复用组件：QueryError（查询错误 + 重试）
    │   └── cash/         CashSummaryCard（可复用现金汇总）、CashTransferForm + cashTransferValidation、CashLedgerTable
    ├── utils/format.ts   统一的显示格式化（时间、金额）
    └── test/             setup.ts（jest-dom、matchMedia / ResizeObserver polyfill、清理 mock）、render.tsx（renderWithProviders、jsonResponse、mockFetch）
```
测试文件与被测文件放在一起：`*.test.ts(x)`。

### 路由
| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | `HomePage` | 调 `GET /api/health` 显示后端状态、版本、服务器时间（纽约时区） |
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
- 写操作成功后 invalidate 该领域的根 key（如 `cashQueryKeys.all` = `["cash"]`），依赖它的组件（如 `CashSummaryCard`）自动刷新；以后任何改变现金的操作（FEAT-001 开仓等）也要 invalidate `["cash"]`
- 表单提交失败：`VALIDATION_ERROR` / `BAD_REQUEST` 的 `details[].field` 用 `form.setFields` 显示到同名字段；无法对应字段的错误和业务错误（如 `CASH_INSUFFICIENT`，message 是中文可直接显示）显示在提交按钮上方；保留用户输入

### 显示约定
- 金额等小数：后端给字符串，前端只格式化不计算；格式化函数统一放 `src/utils/format.ts`
- 时间点（Instant）用 `formatInstantNY()` 显示为 `YYYY-MM-DD HH:mm:ss EST|EDT`，或 `formatDateTimeNY()` 显示为 `YYYY-MM-DD HH:mm`（列表用），均为 America/New_York；空值/非法值显示 `—`
- 金额用 `formatMoney(value, { signed? })`：纯字符串处理（不转浮点、不四舍五入），千分位、至少 2 位小数、负号 `−`(U+2212)，`signed` 时正数加 `+`；颜色用 `decimalSign()` 判断正负（正绿 `#389e0d`、负红 `#cf1322`）
- 用户录入的时间：原生 `datetime-local` 输入美东本地时间，原样以 `YYYY-MM-DDTHH:mm` 发给后端（`occurredAtLocal` 之类字段），前端不做时区换算；默认值用 `nowLocalDateTimeNY()`
- 交互元素和关键显示值加 `data-testid`，供 E2E 使用。已有：`nav-home`、`nav-cash`、`health-card`、`health-loading`、`health-status`、`health-version`、`health-time`、`health-error`、`health-retry`、`not-found`
- FEAT-007 资金页的 testid 列表见 `docs/requirements/FEAT-007-cash-account.md` 的 Frontend Implementation Notes
