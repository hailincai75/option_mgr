# API 契约

由 backend-dev 维护，frontend-dev 和 qa 只读。每个需求实现前先在这里写好 API。

## 通用约定
- Base path：`/api`；请求与响应体均为 `application/json`（UTF-8）
- **金额/价格/数量类小数**：JSON 字符串，十进制普通写法，不用科学计数法（如 `"349.35"`、`"1000"`）。后端统一把所有 `BigDecimal` 序列化成字符串；请求中金额也应传字符串（传数字也能解析，但不推荐，可能在前端丢精度）。字符串保留后端给出的 scale，前端不要自行四舍五入后再回传
- **日期**（到期日等 `LocalDate`）：ISO-8601 `YYYY-MM-DD`，如 `"2026-12-18"`
- **时间点**（交易时间等 `Instant`）：ISO-8601 UTC，带 `Z` 后缀，如 `"2026-11-10T14:30:00Z"`；小数秒位数不固定（0~9 位），解析时不要假设位数
- 字段名 camelCase；值为空时字段输出 `null`（不省略）
- 在线文档：运行后端后访问 `/swagger-ui.html`（OpenAPI JSON：`/v3/api-docs`）。以本文件为准，两者冲突时报告给 backend-dev
- **账户（FEAT-033 起）**：除 `/api/health`、`/api/accounts/**`、`/api/test-support/**`、`/api/trading-calendar/**`（FEAT-009，全系统一份）、`/api/market-data/**`（FEAT-016，全系统一份）外，所有接口都必须带请求头 `X-Account-Id: <账户 id>`，只读写该账户的数据；规则与错误见下方「FEAT-033 多账户」。本文件中 FEAT-007 ~ FEAT-008 的示例省略了这个请求头。**FEAT-035 起**还可取 `ALL`（全部账户，只读），只被 7 个 GET 接受（**FEAT-046 起 11 个**，加交易列表、股票交易、已平仓、流水；**FEAT-047 起 13 个**，加按标的盈亏摘要、组合 Greeks 汇总），其它接口 → 400 `ALL_ACCOUNTS_READ_ONLY` / `ALL_ACCOUNTS_NOT_SUPPORTED`，见 [FEAT-035.md](FEAT-035.md)、[FEAT-046.md](FEAT-046.md)、[FEAT-047.md](FEAT-047.md)

## 统一错误格式
所有 `/api/**` 接口出错时（HTTP 4xx/5xx）返回同一结构：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/trades",
  "timestamp": "2026-11-10T14:30:00.123Z",
  "details": [
    { "field": "price", "message": "must be greater than 0" },
    { "field": "symbol", "message": "must not be blank" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | string | 机器可读错误码，见下表。前端按 `code` 分支处理，不要解析 `message` |
| `message` | string | 给人看的简要说明（英文）。可以显示给用户，但不保证措辞稳定 |
| `status` | number | 与 HTTP 状态码相同 |
| `path` | string | 出错请求的路径（不含 query string） |
| `timestamp` | string | 出错时间，ISO-8601 UTC Instant |
| `details` | array | 字段级错误列表，没有时为空数组 `[]`（永不为 `null`）。每项：`field`（请求体属性名或参数名；嵌套属性用点号，如 `legs[0].strike`）、`message`（该字段的问题）。按 `field` 排序 |

服务器内部错误（500）不返回堆栈、异常类名或内部信息，`message` 固定为 `"Internal server error"`；详细信息只记在服务端日志。

### 通用错误码
| code | HTTP | 何时出现 |
|------|------|---------|
| `VALIDATION_ERROR` | 400 | 请求体/参数能解析但不满足校验规则（必填、正数、格式等）；`details` 列出每个字段 |
| `BAD_REQUEST` | 400 | 请求无法解析：JSON 格式错误、类型不对（如路径参数要数字却给了 `abc`；请求体字段的值无法转换，如金额给了 `"abc"`、枚举值不存在、时间格式不对）、缺少必填 query 参数。类型不对/缺参数时 `details` 会指出字段（请求体字段用属性路径，如 `amount`）；JSON 语法错误时 `details` 为空 |
| `NOT_FOUND` | 404 | 路径不存在，或资源（如某笔交易 id）不存在 |
| `METHOD_NOT_ALLOWED` | 405 | 路径存在但不支持该 HTTP 方法 |
| `CONFLICT` | 409 | 与现有数据冲突（如重复创建、并发修改） |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` 不是 `application/json` |
| `BUSINESS_RULE_VIOLATION` | 422 | 请求格式正确，但违反业务规则（如平仓数量超过持仓） |
| `INTERNAL_ERROR` | 500 | 未预期的服务器错误 |

各需求如需更具体的业务错误码（例如 `POSITION_INSUFFICIENT`），在该需求的 Endpoints 小节里列出，并注明对应的 HTTP 状态；未列出的情况一律使用上表的通用码。

### 错误示例
404，路径不存在：
```json
{
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "status": 404,
  "path": "/api/nope",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": []
}
```

400，路径参数类型错误：
```json
{
  "code": "BAD_REQUEST",
  "message": "Invalid parameter value",
  "status": 400,
  "path": "/api/trades/abc",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": [ { "field": "id", "message": "must be a valid long" } ]
}
```

500：
```json
{
  "code": "INTERNAL_ERROR",
  "message": "Internal server error",
  "status": 500,
  "path": "/api/positions",
  "timestamp": "2026-11-10T22:09:09.171Z",
  "details": []
}
```

## Endpoints
（按需求编号分节，例如 `### FEAT-001`）

### 基础设施

#### `GET /api/health`
服务存活检查，供前端/E2E 判断后端是否就绪。无请求参数，无请求体。

响应 `200 OK`：
```json
{
  "status": "UP",
  "version": "0.0.1-SNAPSHOT",
  "time": "2026-11-10T22:09:08.676Z"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | 固定 `"UP"`（服务能响应即为 UP；目前不检查数据库） |
| `version` | string | 后端构建版本；构建信息缺失时（如直接在 IDE 运行）为 `"dev"` |
| `time` | string | 服务器当前时间，ISO-8601 UTC Instant |

错误：只有通用错误（如 `DELETE /api/health` → 405 `METHOD_NOT_ALLOWED`）。


## 各功能的接口（按功能拆分，每个文件一个功能）

只读你需要的那几个文件。新功能的契约写成新文件 `FEAT-XXX.md`，并在下表加一行。

| 文件 | 内容 |
|---|---|
| [test-support.md](test-support.md) | 测试支持（仅 `e2e` profile，正常运行时不存在） |
| [FEAT-007.md](FEAT-007.md) | FEAT-007 资金账户（入金/出金、现金汇总、现金流水） |
| [FEAT-001.md](FEAT-001.md) | FEAT-001 录入开仓期权交易（BTO / STO） |
| [FEAT-002.md](FEAT-002.md) | FEAT-002 交易列表与筛选 |
| [FEAT-003.md](FEAT-003.md) | FEAT-003 期权持仓汇总 |
| [FEAT-004.md](FEAT-004.md) | FEAT-004 录入平仓交易（BTC / STC） |
| [FEAT-005.md](FEAT-005.md) | FEAT-005 修改 / 删除交易与入金出金（含审计） |
| [FEAT-006.md](FEAT-006.md) | FEAT-006 已实现盈亏 / 已平仓记录 |
| [FEAT-008.md](FEAT-008.md) | FEAT-008 盈亏汇总与首页概览 |
| [FEAT-033.md](FEAT-033.md) | FEAT-033 多账户（**不兼容变更：所有业务接口都要求指明账户**） |
| [FEAT-038.md](FEAT-038.md) | FEAT-038 允许卖出开仓 call（STO call），不冻结现金 |
| [has-audit-log.md](has-audit-log.md) | 列表行的 `hasAuditLog`（"历史"链接只在有审计记录时显示） |
| [FEAT-012.md](FEAT-012.md) | FEAT-012 到期作废（`EXP`）、到期日 16:00 / 17:30 |
| [FEAT-011.md](FEAT-011.md) | FEAT-011 股票交易与股票持仓 |
| [FEAT-040.md](FEAT-040.md) | FEAT-040 股票已实现盈亏并入已平仓、盈亏汇总、首页 |
| [FEAT-013.md](FEAT-013.md) | FEAT-013 卖出 put 被指派（`ASG`） |
| [FEAT-041.md](FEAT-041.md) | FEAT-041 卖出 call 被指派（`ASG` 扩展到空头 call） |
| [FEAT-014.md](FEAT-014.md) | FEAT-014 主动行权（`EXR`，多头 call / put；股票类型 `EXERCISE_BUY` / `EXERCISE_SELL`，流水 `OPTION_EXERCISE`） |
| [FEAT-015.md](FEAT-015.md) | FEAT-015 到期批量作废（`POST /api/option-trades/expirations/batch`，全部成功或全部不保存；422 `BATCH_EXPIRATION_REJECTED` 带 `failures` 扩展字段） |
| [FEAT-039.md](FEAT-039.md) | FEAT-039 covered call 股票覆盖检查与锁定（**行为变化：STO call、股票卖出及多种修改 / 删除可能被 `COVERAGE_INSUFFICIENT` 拒绝**） |
| [FEAT-009.md](FEAT-009.md) | FEAT-009 美股交易日历（`GET /api/trading-calendar/non-trading-days`、`/status`；持仓新增 `remainingTradingDays`；**行为变化：开仓到期日非交易日被 422 `EXPIRATION_NOT_TRADING_DAY` 拒绝**；测试支持假拉取与假时钟） |
| [FEAT-042.md](FEAT-042.md) | FEAT-042 临时休市检测与提醒（`GET /api/adhoc-closure-alerts` 按账户；`GET /api/trading-calendar/adhoc-closures` 全系统、FEAT-043 复用；持仓新增 `expiresOnAdhocClosure`、`adhocClosureEventName`；拉取成功后检测，测试支持无新增接口） |
| [FEAT-043.md](FEAT-043.md) | FEAT-043 临时休市时调整到期日（`POST /api/option-trades/expiration-adjustments`；持仓新增 `expirationAdjustable` 等；审计新 `operation` `ADJUST_EXPIRATION` 与 `expirationAdjustment` 字段，`objectId` 筛选也匹配调整涉及的交易；测试支持 `legacy-option-trades` 扩展到 put / BTO；迁移 V15） |
| [FEAT-016.md](FEAT-016.md) | FEAT-016 自动行情（CBOE 延迟报价 + Finnhub 股价兜底；`GET /api/market-data/status`、`POST /api/market-data/refresh`（60 秒冷却，冷却中 200 `performed:false`）、手动覆盖 `PUT` / `DELETE /api/market-data/overrides/**`，均不要求 `X-Account-Id`；指数标的、已过期合约不拉取（Q150）；期权持仓新增 `underlyingQuote` / `optionQuote`，股票持仓新增 `quote`（纯新增）；测试支持假数据源 / 请求记录 / 定时 tick / 存储查看；迁移 V16，failing-migration fixture 改 V17） |
| [FEAT-017.md](FEAT-017.md) | FEAT-017 未实现盈亏与市值（期权 / 股票持仓行新增 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel`（纯新增）；新接口 `GET /api/positions/unrealized-summary`（按账户，期权 / 股票 / 总计三组 + `excludedLabel`，不受到期过滤影响）；无迁移） |
| [FEAT-018.md](FEAT-018.md) | FEAT-018 单合约 Greeks（期权持仓行新增 `greeks` 对象：`status` / `label`、`ivPercent`、Delta / Gamma / Theta / Vega 的每张原值（4 位）与持仓值（2 位）、CBOE `quoteTime`、`manualMarkNote`（纯新增，FEAT-043 调整响应的 `position` 同样带）；不返回 Rho；无新端点、无迁移） |
| [FEAT-019.md](FEAT-019.md) | FEAT-019 组合 Greeks 汇总（新接口 `GET /api/positions/greeks-summary`，按账户、不受到期过滤：按标的的期权 Delta / 股票 Delta / 净 Delta / Gamma / Theta / Vega、标的价与是否手动、Delta 美元敞口（`exposureStatus` `OK` / `NO_UNDERLYING_PRICE` / `NO_DELTA`）、未计入 / 缺值提示；全组合美元敞口 / Theta / Vega 与提示；用 FEAT-018 已取整行值相加（Q181）；纯新增，无迁移） |
| [FEAT-021.md](FEAT-021.md) | FEAT-021 按标的分组查看（**后端 N/A**：前端复用 `GET /api/option-positions` + `GET /api/stock-positions` 按 `symbol` 分组，组内保持期权接口的返回顺序；列出每列的来源字段；无新接口、字段、迁移） |
| [FEAT-023.md](FEAT-023.md) | FEAT-023 按标的盈亏（新接口 `GET /api/positions/underlying-summary`，按账户、不受到期过滤：每个有持仓的标的一组，已实现（全部时间，与盈亏汇总 `bySymbol` 同源，含期权 / 股票拆分）、市值 / 未实现（FEAT-017 `OK` 行之和，全不能算为 `null`）、合计盈亏、净权利金（期权持仓开仓净额之和，无期权为 `null`）、未计入计数与 `excludedLabel`；行上市值 / 未实现仍用持仓接口的 FEAT-017 字段；纯新增，无迁移） |
| [FEAT-035.md](FEAT-035.md) | FEAT-035 全部账户合并查看（一）（请求头 `X-Account-Id: ALL`，只读；接受的 7 个 GET：`dashboard`、`cash/summary`（新增 `accounts[]`）、`option-positions` / `stock-positions`（行新增 `accountId`、`accountName`，同序按账户 id；股票 `positionCount` 与首页 `stockPositions.positionCount` 为跨账户不同标的数）、`positions/unrealized-summary`、`pnl-summary`（跨账户合并）、`adhoc-closure-alerts`（新增 `byAccount[]`）；其它接口收到 `ALL`：写 → 400 `ALL_ACCOUNTS_READ_ONLY`，读 → 400 `ALL_ACCOUNTS_NOT_SUPPORTED`；行情 / 账户接口不受影响；无新端点、无迁移） |
| [FEAT-046.md](FEAT-046.md) | FEAT-046 全部账户合并查看（二）（`GET /api/option-trades`、`/api/stock-trades`、`/api/closed-trades`、`/api/cash/ledger` 接受 `X-Account-Id: ALL`：行新增 `accountId`、`accountName`（期权交易 / 股票记录表示在所有接口都带，单账户也有值），筛选与错误不变、无账户筛选（Q230），原排序 + `accountId`（Q229），对合并后的整个列表分页，已平仓合计为全部账户之和，流水 `balanceAfter` 为该行账户自己的余额（Q228）；`/{id}` 与审计仍 `ALL_ACCOUNTS_NOT_SUPPORTED`；恢复盈亏汇总 = 已平仓合计的一致性；无新端点、无迁移） |
| [FEAT-047.md](FEAT-047.md) | FEAT-047 全部账户合并查看（三）（`GET /api/positions/underlying-summary` 接受 `ALL`：每个"账户 + 标的"一组（Q224 A），`groups[]` 新增 `accountId`、`accountName`（单账户也有值），按 `symbol` → `accountId`，每组数字与单账户逐字相同；`GET /api/positions/greeks-summary` 接受 `ALL`：同一标的跨账户合并为一组（Q224 B），结构不变、无账户字段（Q234 A），美元敞口 = 合并净 Delta × 标的价再取整（Q233 A）；卡片"已实现"链接先切到该组账户（Q235 B，纯前端）；接受 `ALL` 的 GET 共 13 个；无新端点、无错误码、无迁移） |
| [FEAT-048.md](FEAT-048.md) | FEAT-048 持仓批次的未实现盈亏（`GET /api/option-positions`、`GET /api/stock-positions`（含 `ALL`）及 FEAT-043 调整响应 `position` 的 `lots[]` 新增 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel`（纯新增，格式同 FEAT-017 行）；状态 / 标签跟随所属行（Q239），旧报价照算；期权分母 `remainingNetAmount` 的绝对值、股票分母 `remainingCost`（Q240）；除最后一个批次外各自 HALF_UP 到分，最后一个 = 行市值 − 其它之和，批次之和始终等于行（Q238 A）；行、合计接口不变；无新端点、无错误码、无迁移） |
| [FEAT-051.md](FEAT-051.md) | FEAT-051 平仓时指定批次（**行为变化：Q39 自动重新匹配取消**，每笔 BTC / STC 保存所选批次（Q255 A），补录 / 修改 / 删除使已有平仓的所选批次不成立 → 422 `CLOSE_LOTS_INVALID`；`POST /api/option-trades/closes` 新增 `lotTradeIds`（不带 = 原 FIFO 按张数，Q257），带了时 `contracts` 可省略；`PUT /api/option-trades/{id}` 平仓可改 `lotTradeIds`（Q256），审计 `selectedLots`；新接口 `GET /api/option-trades/{id}/close-lot-choices`；持仓 `lots[].closeDefault`；迁移 V17 新表 `option_close_lot` + V18 Java 回填（按现有 FIFO 结果，数字不变），failing-migration fixture 改 V19） |
