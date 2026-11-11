# Option Manager — 项目总规则

所有会话和所有 agent 都必须遵守本文件。

## 项目是什么
个人期权交易管理系统：记录期权交易、管理持仓、计算盈亏和 Greeks、跟踪到期/行权/指派事件、按策略（covered call、cash-secured put、垂直价差、iron condor 等）分组查看。
**本系统只做管理与分析，不向任何券商下真实订单。** 接入券商只允许只读数据或 paper trading，且必须由 PM 写进需求并经人工批准。

## 技术栈（可按需修改，修改后同步本节）
### Backend（`backend/`）
- Java 21, Spring Boot 3.x, Gradle (Kotlin DSL)
- 数据库：开发与测试用 H2，以后可换 PostgreSQL；schema 用 Flyway 管理
- REST API，JSON；API 文档用 springdoc-openapi 生成
- 测试：JUnit 5, AssertJ, Spring Boot Test
- 金额与价格一律用 `BigDecimal`，禁止 `double`/`float`；JSON 中金额用字符串传输，避免精度丢失
- 时间：交易时间存 `Instant`，到期日用 `LocalDate`，市场时区为 `America/New_York`

### Frontend（`frontend/`）
- React + TypeScript（strict 模式）+ Vite
- UI 组件：Ant Design（表格、表单多，适合交易管理类界面）
- 服务端数据：TanStack Query；路由：React Router
- 金额计算与显示：前端不做盈亏计算，只显示后端返回的值；格式化用统一的 util
- 测试：Vitest + React Testing Library
- 开发时 Vite proxy 把 `/api` 转发到 `http://localhost:8080`

### 系统测试（QA）
- 后端集成测试：`backend/src/test/java/com/optionmgr/systemtest/`
- 端到端测试：Playwright，放在 `e2e/`

## 常用命令（Windows）
| 用途 | 命令 |
|------|------|
| 后端构建 | `cd backend && gradlew.bat build` |
| 后端运行 | `cd backend && gradlew.bat bootRun`（端口 8080） |
| 前端安装 | `cd frontend && npm install` |
| 前端运行 | `cd frontend && npm run dev`（端口 5173） |
| 前端检查 | `cd frontend && npm run lint && npm run typecheck && npm test` |
| E2E | `cd e2e && npx playwright test`（需前后端都在运行） |

## 目录约定
```
backend/                   Spring Boot 工程，包名根 com.optionmgr（backend-dev 负责）
frontend/                  React 工程（frontend-dev 负责）
e2e/                       Playwright 端到端测试（qa 负责）
docs/backlog.md            PM 维护的需求列表与优先级
docs/requirements/         PM 写：FEAT-XXX-<short-name>.md（一功能一文件）
docs/api/api-contract.md   API 契约（backend-dev 负责，frontend-dev 只读）
docs/architecture.md       架构说明（backend-dev 写后端部分，frontend-dev 写前端部分）
docs/test-reports/         QA 写：FEAT-XXX-test-report.md
docs/bugs/                 QA 写：BUG-XXX-<short-name>.md，标明 backend / frontend
```

## 角色
| Agent | 负责 | 可以修改 |
|-------|------|---------|
| pm | 需求、backlog | docs/requirements/, docs/backlog.md |
| backend-dev | 后端实现、API 契约 | backend/, docs/api/, docs/architecture.md |
| frontend-dev | 前端实现 | frontend/, docs/architecture.md |
| qa | 系统测试、E2E、bug 单 | backend/src/test/java/com/optionmgr/systemtest/, e2e/, docs/test-reports/, docs/bugs/ |

## 交接流程（契约优先）
1. **pm** 写需求，状态 `Draft`；需求里写清"需要哪些数据/操作"和"页面上要看到什么"。我审核后改为 `Approved`。
2. **backend-dev** 先在 `docs/api/api-contract.md` 写出该需求的 API（路径、请求、响应、错误码、示例 JSON），再实现并测试。完成后在需求文件勾选 `Backend: Done`。
3. **frontend-dev** 按 API 契约实现页面。契约有问题时提出来，不自行改契约、不改后端。完成后勾选 `Frontend: Done`。
4. 前后端都完成 → 需求状态改为 `Implemented`（由最后完成的那个 dev 改）。
5. **qa** 测试：后端集成测试 + Playwright E2E，对照验收标准逐条验证。全部通过改为 `Verified`，否则写 bug（注明 backend 或 frontend）并改为 `Failed`。
6. 对应的 dev 修完 bug → `Implemented` → QA 复测。

需求状态：`Draft → Approved → Implemented → Verified`（或 `Failed → Implemented`）
**只有人（我）能把 `Draft` 改成 `Approved`。** agent 不得自行批准需求。
纯后端或纯前端的需求，在需求文件里写明，另一侧直接标 `N/A`。

## 领域规则（所有角色都要懂）
- 一张美股期权合约 = 100 股（multiplier 字段保留，不写死）
- 合约唯一标识：标的 + 到期日 + 行权价 + Call/Put
- 方向：Buy to Open / Sell to Open / Buy to Close / Sell to Close
- 平仓盈亏要计入手续费；成本法默认 FIFO
- 平仓必须整笔平掉最早的一笔或几笔开仓批次，不支持部分平仓（见 backlog Q21）
- 到期事件：过期作废、被行权（exercise）、被指派（assignment），指派后生成股票持仓
- 边界情况必须考虑：一笔平仓覆盖多笔开仓、同一合约多次开仓、到期日当天、周末与美股假日、标的拆股
- 所有业务计算（盈亏、成本、保证金）只在后端做，前端不重复实现

## 工作方式
- 小步实现：一次只做一个需求，每个需求可以独立构建和测试
- 每步完成后对应的构建/检查命令必须通过
- 不确定的需求细节：停下来问我，不要猜
- 不提交任何密钥、账号、真实交易数据；`.env` 与 `data/` 不进 git
