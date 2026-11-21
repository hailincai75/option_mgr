# BUG-008: Yahoo 握手 getcrumb 返回 HTTP 406，行情刷新全部失败

- **Status:** Closed（Fixed → QA 复测通过，2026-11-20）
- **Severity:** Critical（option-source = yahoo 时所有标的都拿不到行情）
- **Layer:** backend
- **Related:** FEAT-052（commit d71cac7，已 Verified 后在真实环境发现）

## 复现步骤（真实症状）
1. 在用户自己的后端（`optionmgr.quotes.transport=http`、`option-source=yahoo`，真实 Yahoo）有期权或股票持仓。
2. 点击持仓页的刷新行情（`POST /api/market-data/refresh`），或等定时拉取。

## 期望结果
会话握手成功（cookie 页 → `GET /v1/test/getcrumb` 得到 crumb → `GET /v7/finance/options/{SYMBOL}`），各标的 `yahooResult = SUCCESS`，持仓显示 Yahoo 报价和 Greeks。

## 实际结果
每次刷新所有标的都失败：`result = FAILED`、`yahooResult = HANDSHAKE_FAILED`，failureDetail 为 `Yahoo handshake failed (getcrumb HTTP 406)`。页面没有任何 Yahoo 报价。

## 根本原因
`HttpQuoteTransport` 的通用 `get(...)` 对所有请求固定带 `Accept: application/json`（为 CBOE / Finnhub 写的）。Yahoo 的 getcrumb 响应是 `text/plain`，收到只接受 JSON 的 `Accept` 时回 `406 Not Acceptable`，握手因此失败。FEAT-052 的可行性探测用的是 curl，curl 默认带 `Accept: */*`，所以探测时没有暴露。

## 修复（backend-dev）
- Yahoo 的所有请求（cookie 页、getcrumb、options）改为带 `Accept: */*` 和 `Accept-Language: en-US,en;q=0.9`；CBOE 和 Finnhub 仍带 `Accept: application/json`。
- `docs/api/FEAT-052.md` 增加请求头表，写明各请求的 `Accept` 及修订原因。
- `HttpQuoteTransportTest`：假 getcrumb 在 `Accept` 不允许 `text/plain` 时回 406；并断言 Yahoo 请求带 `*/*`，CBOE / Finnhub 带 `application/json`。

## 测试缺口（为什么之前没发现）
单元测试 `HttpQuoteTransportTest` 和 QA 的 `Feat052YahooRealHttpSystemTest` 都用本地假 Yahoo，但假服务器不看 `Accept` 请求头，不管带什么都返回 crumb，只检查了 cookie、User-Agent、crumb 和 `date` 参数。`Feat052YahooQuotesAcceptance/BoundarySystemTest` 和 E2E 用的是 in-memory 传输，根本不经过 HTTP 请求头。所以"真实 Yahoo 对请求头有要求"这一点，自动化测试完全没有模拟到。

QA 补救：
- `Feat052YahooRealHttpSystemTest` 的假 Yahoo 加上同样的规则：getcrumb 在 `Accept` 不允许 `text/plain`（不含 `*/*`、`text/*`、`text/plain`，或 q=0）时回 406。
- 断言真实 HTTP 传输发出的每个 Yahoo 请求（首次握手、401 后重新握手、options）都带 `Accept: */*` 和 `Accept-Language: en-US,en;q=0.9`。
- 新增假服务自检：`Accept: application/json` 访问假 getcrumb 得到 406，`*/*` 得到 200 和 crumb。这样可以证明，修复前的代码在这个测试里会握手失败。

剩余风险：假服务只模拟了已知的 `Accept` 规则；真实 Yahoo 的其他请求头 / 反爬规则变化，仍只能在真实环境中发现（自动化测试不访问真实 Yahoo）。

## 复测（QA，2026-11-20）
- `gradlew.bat test --tests com.optionmgr.systemtest.Feat052* --tests com.optionmgr.quote.service.HttpQuoteTransportTest`：54 个测试全部通过。其中 HttpQuoteTransportTest 11 个，Feat052 Acceptance 15 个、Boundary 25 个，RealHttp 3 个（含新增自检）。
- `gradlew.bat build`：BUILD SUCCESSFUL，1682 个测试，0 失败、0 跳过。
- 未跑 E2E：E2E 使用 `application-e2e.yml` 的 `transport: in-memory`，不经过 HTTP 请求头；前端未改动。

状态改为 Closed。
