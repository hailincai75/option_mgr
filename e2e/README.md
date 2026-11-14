# E2E（Playwright）

由 QA 维护。只跑 Chromium。

## 运行
```powershell
# 1. 构建后端 boot jar（JDK 21）
cd backend
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'; .\gradlew.bat bootJar
# 2. 第一次运行先安装依赖和浏览器（前端也要 npm install，测试用 frontend/node_modules 里的 Vite）
cd ..\e2e
npm install
npx playwright install chromium
# 3. 运行（默认 3 个 worker 并行）
npx playwright test
# 串行（1 个 worker）
$env:E2E_WORKERS='1'; npx playwright test
```
- 不需要手动启动前端或后端，也不会用 8080 / 5173：开发者自己的应用可以同时在跑。
- 如果 JDK 不在默认路径，用环境变量 `E2E_JAVA_HOME` 指定。
- 后端和 Vite 的日志写在 `e2e/.backend-logs/`；失败用例的 trace 和截图在 `test-results/`，HTML 报告在 `playwright-report/`（这几个目录都被 git 忽略）。

## 并行：每个 worker 一套独立的前后端
- worker 数：`E2E_WORKERS`（默认 3）。按 spec 文件并行（`fullyParallel: false`）：同一个 spec 里的测试仍在同一个 worker 里按顺序跑。
- 第 i 个 worker 槽位（`TEST_PARALLEL_INDEX`，worker 失败重启后不变）用：
  - 后端：端口 `E2E_BACKEND_PORT + i`（默认 18080 + i），boot jar + Spring profile **`e2e`**。这个 profile 由后端强制使用内存 H2（数据源不是 `jdbc:h2:mem:` 时拒绝启动），每个 JVM 一个独立的库，所以数据、可设置的时钟、重置接口天然隔离。
  - 前端：端口 `E2E_FRONTEND_PORT + i`（默认 15173 + i）上自己启动的 Vite dev server，`/api` 代理通过 `OPTION_MGR_API_TARGET` 指向这个 worker 的后端；它就是这个 worker 的 `baseURL`。测试里直接调后端的请求（`support/backend.ts` 的 `BACKEND_URL`）也指向这个 worker 的后端。
- 这些进程由 worker 级 fixture（`support/fixtures.ts` 的 `workerStack`）在第一次用到时启动，整轮只启动一次；只跑一个 spec 时只启动一套。进程号记在 `.backend-logs/backend-<port>.pid` / `vite-<port>.pid`，整轮结束由 global teardown 全部停掉。

## 每个测试从空库开始的做法
- `support/fixtures.ts` 里的自动 fixture 在**每个测试前**对本 worker 的后端调用 `POST /api/test-support/reset`，要求返回 204，然后确认 `GET /api/cash/summary` 的 `totalCash` 是 `0.00`。重置后的库和新库一样，id 从 1 开始。
- "后端不可用"那几个用例会停掉本 worker 的后端再重启，重启后同样是 `e2e` profile，不影响其他 worker。

## 安全
- 后端端口已经被占用时，先探测 `POST /api/test-support/reset`：
  - 返回 **404**，说明不是 `e2e` 后端（可能是开发库 `backend/data/` 上的后端）。整轮测试立即失败，一条数据都不写。
  - 返回 204，说明已经有一个 `e2e` 后端在跑，就直接复用它，跑完也不停它。
- 前端端口被不是本轮启动的进程占用时直接失败（它可能代理到开发用的后端）。
- 重置接口只在 `e2e` profile 下存在，见 `docs/api/api-contract.md` 的"测试支持"一节。
