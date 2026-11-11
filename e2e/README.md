# E2E（Playwright）

由 QA 维护。只跑 Chromium。

## 运行
```powershell
# 1. 构建后端 boot jar（JDK 21）
cd backend
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'; .\gradlew.bat bootJar
# 2. 第一次运行先安装依赖和浏览器
cd ..\e2e
npm install
npx playwright install chromium
# 3. 运行
npx playwright test
```
- 不需要手动启动后端。8080 上**不能**有开发用的后端（见下面的"安全"）。
- 前端：如果 5173 上已有 Vite dev server 就直接用，没有的话 Playwright 会自动 `npm run dev`（在 `frontend/` 下），跑完再停掉。
- 如果 JDK 不在默认路径，用环境变量 `E2E_JAVA_HOME` 指定。
- 后端日志写在 `e2e/.backend-logs/`；失败用例的 trace 和截图在 `test-results/`，HTML 报告在 `playwright-report/`（这几个目录都被 git 忽略）。

## 每个测试从空库开始的做法
- `support/global-setup.ts` 用 boot jar 以 Spring profile **`e2e`** 启动**一个**后端（8080）。这个 profile 由后端强制使用内存 H2，数据源不是 `jdbc:h2:mem:` 时后端会拒绝启动。整轮测试跑完后由 global teardown 停掉它（进程号记在 `.backend-logs/backend.pid`）。
- `support/fixtures.ts` 里的自动 fixture 在**每个测试前**调用 `POST /api/test-support/reset`，要求返回 204，然后确认 `GET /api/cash/summary` 的 `totalCash` 是 `0.00`。重置后的库和新库一样，id 从 1 开始。
- 所有测试共用一个库，所以串行运行（`workers: 1`）。
- "后端不可用"那个用例会自己停掉后端再重启，重启后同样是 `e2e` profile。

## 安全
- 如果 8080 已经被占用，先探测 `POST /api/test-support/reset`：
  - 返回 **404**，说明不是 `e2e` 后端（可能是开发库 `backend/data/` 上的后端）。整轮测试立即失败，一条数据都不写（实测 7 秒内退出，原有数据不变）。
  - 返回 204，说明已经有一个 `e2e` 后端在跑，就直接复用它，跑完也不停它。
- 重置接口只在 `e2e` profile 下存在，见 `docs/api/api-contract.md` 的"测试支持"一节。
