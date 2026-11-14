# FEAT-009 美股交易日历（Finnhub 休市日 + Jollyday 规则兜底、剩余交易日、到期日选择器）

Q34、Q53、Q82、Q120、Q122–Q127 均已定。本节只写**新增或不同**的部分；其它接口、字段、文案不变，除非下表标为"行为变化"。示例标的均为虚构。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/trading-calendar/non-trading-days` | 新接口 | — |
| `GET /api/trading-calendar/status` | 新接口 | — |
| `GET /api/option-positions` | `positions[].remainingTradingDays` | — |
| `POST /api/option-trades`（BTO / STO） | — | 新拒绝 422 `EXPIRATION_NOT_TRADING_DAY` |
| 平仓、作废、批量作废、被指派、行权、修改、删除 | — | 无（不检查交易日，Q126） |
| 测试支持（`e2e` profile） | `POST /api/test-support/trading-calendar/fetch`、`PUT` / `DELETE /api/test-support/clock` | `POST /api/test-support/reset` 也清空日历数据与拉取状态、恢复时钟 |
| 股票持仓、现金、交易列表、已平仓、盈亏汇总、首页 | — | — |

**账户请求头**：交易日历全系统一份（Q53），`/api/trading-calendar/**` **不要求** `X-Account-Id`（与 `/api/health`、`/api/accounts/**` 一样列为例外）。带了也忽略、不校验，所以前端统一加请求头也没问题。

#### 概念
- **今天**：注入 `Clock` 当前时间的美东日期（同 FEAT-003 `asOfDate`）。
- **Finnhub 数据**：`GET https://finnhub.io/api/v1/stock/market-holiday?exchange=US`，key 只放请求头 `X-Finnhub-Token`。返回的每条 `{eventName, atDate, tradingHour}` 存一行（`postMarket` 忽略，不存）。
- **覆盖年份**：已存 Finnhub 数据中出现过的年份（如 2023–2027）。覆盖年份以 Finnhub 为准；其余年份用 Jollyday（`de.focus-shift:jollyday`）NYSE 规则推算。某年第一次出现在 Finnhub 响应中，就自动改用 Finnhub。
- **全天休市日**：覆盖年份内 `tradingHour` 为空（`""` 或缺失）的日期；其余年份为 Jollyday NYSE 假日。`tradingHour` 非空（如 `"09:30-13:00"` 提前收盘）**是交易日**（Q122）。
- **交易日**：星期一至星期五，且不是全天休市日。
- **合并（Q127）**：拉取成功时，响应中出现的每个年份**整年替换**（该年的旧行全部删除，换成响应中该年的行）；响应中没有的年份保留已存数据。一次成功拉取在一个事务里完成。
- **拉取时机**：后端启动后立即拉一次；之后每天美东 08:00 拉一次（cron `0 0 8 * * *`，时区 `America/New_York`）。失败不重试，等下一次。连接超时 5 秒、读取超时 10 秒。
- **拉取失败**：无 key（`.env` 和环境变量都没有 `FINNHUB_API_KEY`，或为空白）、网络错误 / 超时、非 200 响应、响应格式不对（不是 JSON、没有 `data` 数组、某条 `atDate` 不是合法日期）→ 不改任何已存休市日，只记录状态。`exchange` / `timezone` 不校验。
- **key 读取**：环境变量 `FINNHUB_API_KEY` 优先；否则读项目根目录 `.env`（后端工作目录为 `backend/` 时读 `../.env`，为项目根时读 `./.env`）。key 不出现在 URL、日志、异常信息、数据库或任何 API 响应中。
- **剩余交易日（Q125）**：区间 (今天, 到期日] 内的交易日数（不含今天、含到期日）。到期日当天为 `0`；已过期（到期日 < 今天）为 `null`。到期日本身不是交易日的旧数据照公式算（如今天 2026-11-16，到期 2026-11-21 星期六 → 11-17…11-20 共 `4`）。

---

#### `GET /api/trading-calendar/non-trading-days`
返回日期范围内（含两端）的全部非交易日（周末 + 全天休市日），供开仓表单到期日选择器禁用。前端翻到某月时查该月（如 `from=2026-11-01&to=2026-11-30`）；也可以一次查更长范围以预取。

Query 参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `from` | 是 | `YYYY-MM-DD`，起日（含） |
| `to` | 是 | `YYYY-MM-DD`，止日（含）。`to − from` 最多 **366 天**（即最多 367 个日期，足够一整年 + 一天的余量） |

允许任意年份（过去、未来，如 2030），超出 Finnhub 覆盖的年份按规则推算。

请求示例：
```
GET /api/trading-calendar/non-trading-days?from=2025-01-06&to=2025-01-12
```

响应 `200 OK`：
```json
{
  "from": "2025-01-06",
  "to": "2025-01-12",
  "days": [
    { "date": "2025-01-09", "type": "HOLIDAY", "name": "National Mourning Day", "source": "FINNHUB" },
    { "date": "2025-01-11", "type": "WEEKEND", "name": "星期六", "source": null },
    { "date": "2025-01-12", "type": "WEEKEND", "name": "星期日", "source": null }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `from` / `to` | string | 回显请求的范围 |
| `days` | array | 范围内全部非交易日，按 `date` 升序，每个日期最多一条。没有时 `[]` |
| `days[].date` | string | `YYYY-MM-DD` |
| `days[].type` | string | `WEEKEND`（星期六 / 星期日）/ `HOLIDAY`（工作日的全天休市） |
| `days[].name` | string | 原因：`HOLIDAY` 为 Finnhub `eventName`（原样）或 Jollyday 名称（英文，如 `Good Friday`、`Thanksgiving Day`，见下）；`WEEKEND` 为 `星期六` / `星期日` |
| `days[].source` | string \| null | `HOLIDAY`：`FINNHUB`（该年是覆盖年份）/ `RULES`（Jollyday 推算）；`WEEKEND`：`null` |

- 周末优先：Finnhub 若列出落在周末的日期（实际不会，NYSE 公布的是调休后的工作日），仍按 `WEEKEND` 返回一条。
- 提前收盘日（如 2026-11-27 `09:30-13:00`）不出现在结果中。
- Jollyday 名称：使用 Jollyday 英文（`Locale.ENGLISH`）描述，规则年份的 Good Friday 为 `Good Friday`。前端只显示，不按名称分支。

再举两例（场景数据，Finnhub 覆盖 2023–2027）：
```
GET /api/trading-calendar/non-trading-days?from=2028-04-10&to=2028-04-16
→ 200 {"from":"2028-04-10","to":"2028-04-16","days":[
     {"date":"2028-04-14","type":"HOLIDAY","name":"Good Friday","source":"RULES"},
     {"date":"2028-04-15","type":"WEEKEND","name":"星期六","source":null},
     {"date":"2028-04-16","type":"WEEKEND","name":"星期日","source":null}]}
```
之后某次拉取返回了含 `2028-04-14 Good Friday` 的 2028 数据 → 同一查询 2028-04-14 变为 `"source":"FINNHUB"`（`name` 取 Finnhub 的 `eventName`）。

错误：

| code | HTTP | 何时 |
|------|------|------|
| `BAD_REQUEST` | 400 | 缺少 `from` / `to`，或不是合法日期（如 `2026-02-30`、`2026/11/01`）。`details[].field` 为参数名，`message`：缺少时 `请填写日期`，格式不对时 `日期格式不正确（YYYY-MM-DD）` |
| `VALIDATION_ERROR` | 400 | 能解析但不满足规则：`from` 晚于 `to` → field `to`，`开始日期不能晚于结束日期`；范围超过 366 天 → field `to`，`日期范围最多 366 天` |

有 `BAD_REQUEST` 问题时先报它（所有字段一起），不再报 `VALIDATION_ERROR`。

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/trading-calendar/non-trading-days",
  "timestamp": "2026-11-23T15:00:00.123Z",
  "details": [ { "field": "to", "message": "开始日期不能晚于结束日期" } ]
}
```

---

#### `GET /api/trading-calendar/status`
休市日数据的拉取状态，供持仓页和开仓表单上方的"休市日数据未更新"提示（需求规则 6）。无参数。不含 key，也不透露是否配置了 key 以外的任何敏感信息。

响应 `200 OK`（最近一次拉取失败，之前成功过）：
```json
{
  "stale": true,
  "warning": "休市日数据未更新（上次成功：2026-11-20 08:00 美东）",
  "lastAttemptAt": "2026-11-21T13:00:00Z",
  "lastAttemptResult": "HTTP_ERROR",
  "lastFailureDetail": "HTTP 500",
  "lastSuccessAt": "2026-11-20T13:00:00Z",
  "lastSuccessAtLocal": "2026-11-20T08:00:00",
  "coveredYears": [2023, 2024, 2025, 2026, 2027]
}
```

从未成功（如没有 key）：
```json
{
  "stale": true,
  "warning": "休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）",
  "lastAttemptAt": "2026-11-23T15:00:00Z",
  "lastAttemptResult": "NO_KEY",
  "lastFailureDetail": null,
  "lastSuccessAt": null,
  "lastSuccessAtLocal": null,
  "coveredYears": []
}
```

正常：
```json
{
  "stale": false,
  "warning": null,
  "lastAttemptAt": "2026-11-23T13:00:00Z",
  "lastAttemptResult": "SUCCESS",
  "lastFailureDetail": null,
  "lastSuccessAt": "2026-11-23T13:00:00Z",
  "lastSuccessAtLocal": "2026-11-23T08:00:00",
  "coveredYears": [2023, 2024, 2025, 2026, 2027]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `stale` | boolean | `true` = 最近一次拉取不成功（`lastAttemptResult` 不是 `SUCCESS` 也不是 `null`）。前端只按它决定是否显示提示 |
| `warning` | string \| null | `stale` 时为可直接显示的完整提示文字（两种之一，见上；时间为 `lastSuccessAt` 的美东时间，格式 `YYYY-MM-DD HH:mm`）；否则 `null`。前端原样显示，不自己拼 |
| `lastAttemptAt` | string \| null | 最近一次尝试的时间（Instant）；从未尝试过为 `null` |
| `lastAttemptResult` | string \| null | `SUCCESS` / `NO_KEY` / `NETWORK_ERROR`（连接失败、超时）/ `HTTP_ERROR`（非 200）/ `INVALID_RESPONSE`（格式不对）；从未尝试过为 `null`（此时 `stale = false`） |
| `lastFailureDetail` | string \| null | 失败的简短说明，只用于排查（如 `HTTP 500`、`connect timed out`、`missing data array`）；绝不含 key、URL 查询串或响应全文。成功或 `NO_KEY` 时为 `null` |
| `lastSuccessAt` | string \| null | 最近一次成功的时间（Instant）；从未成功为 `null` |
| `lastSuccessAtLocal` | string \| null | 同一时间的美东本地时间 `YYYY-MM-DDTHH:mm:ss` |
| `coveredYears` | number[] | 当前 Finnhub 覆盖的年份，升序；没有时 `[]`（全部年份按规则推算） |

拉取状态和休市日一样存数据库，重启后保留。启动时那次拉取在后台进行，完成之前 `/status` 返回的是上次运行留下的状态（新库为 `lastAttemptResult = null`、`stale = false`）。

错误：只有通用错误。

---

#### `GET /api/option-positions`（FEAT-003，新增字段）
`positions[]` 新增：

| 字段 | 类型 | 说明 |
|------|------|------|
| `remainingTradingDays` | number \| null | 剩余交易日（Q125），区间 (`asOfDate`, `expirationDate`]。`expirationStatus = EXPIRES_TODAY` 时为 `0`；`EXPIRED` 时为 `null`（页面显示 `—`）。与账户无关 |

`dte`、`expirationStatus`、排序、FEAT-010 的到期筛选都不变。股票持仓（`GET /api/stock-positions`）不加。

示例（场景：今天 2026-11-23，11-26 感恩节休市，11-27 提前收盘）：
```json
{
  "asOfDate": "2026-11-23",
  "positionCount": 2,
  "frozenTotal": "9500.00",
  "positions": [
    { "symbol": "QRTV", "expirationDate": "2026-11-27", "strike": "50", "optionType": "PUT", "contractLabel": "QRTV 2026-11-27 50 P",
      "direction": "SHORT", "contracts": 1, "dte": 4, "remainingTradingDays": 3, "expirationStatus": "ACTIVE", "daysPastExpiration": 0, "…": "其它字段不变" },
    { "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "45", "optionType": "PUT", "contractLabel": "QRTV 2026-12-18 45 P",
      "direction": "SHORT", "contracts": 1, "dte": 25, "remainingTradingDays": 18, "expirationStatus": "ACTIVE", "daysPastExpiration": 0, "…": "其它字段不变" }
  ]
}
```
今天 2026-11-30、P1 未处理：P1 `dte = -3`、`expirationStatus = "EXPIRED"`、`daysPastExpiration = 3`、`remainingTradingDays = null`。

---

#### `POST /api/option-trades`（FEAT-001，行为变化）
新增开仓（BTO / STO）时，`expirationDate` 不是交易日 → 拒绝，不保存，现金不变。

| code | HTTP | 何时 |
|------|------|------|
| `EXPIRATION_NOT_TRADING_DAY` | 422 | 到期日是周末或全天休市日（按请求时的日历：覆盖年份用 Finnhub 已存数据，其余年份用规则） |

`message`（中文，可直接显示）：`到期日 <expirationDate> 不是美股交易日（<原因>）`，原因同 `non-trading-days` 的 `name`：
- `到期日 2027-03-26 不是美股交易日（Good Friday）`
- `到期日 2026-11-21 不是美股交易日（星期六）`

`details` 为 `[]`。检查顺序：在全部 `VALIDATION_ERROR` / `BAD_REQUEST` 字段校验（含 16:00 截止）通过之后、其它 422（方向冲突、multiplier、现金、覆盖）之前。

```json
{
  "code": "EXPIRATION_NOT_TRADING_DAY",
  "message": "到期日 2027-03-26 不是美股交易日（Good Friday）",
  "status": 422,
  "path": "/api/option-trades",
  "timestamp": "2026-11-23T15:00:00.123Z",
  "details": []
}
```

不检查（Q126）：`PUT /api/option-trades/{id}`（合约不能改，Q43）、平仓、作废、批量作废、被指派、行权、删除，以及已存数据；测试支持 `POST /api/test-support/legacy-option-trades` 也不检查。

---

#### 测试支持（仅 `e2e` profile；其它 profile 404）
`e2e` profile 下**不访问真实 Finnhub**：启动拉取和每日定时拉取都关闭，key 不读取。拉取状态从新库状态开始（`lastAttemptResult = null`）。以下接口不要求 `X-Account-Id`。

##### `POST /api/test-support/trading-calendar/fetch`
用给定的假响应走一遍**与真实拉取完全相同**的处理（解析、合并、状态记录），尝试时间 = 当前时钟（含下面的假时钟）。请求体三选一：

```json
{ "httpStatus": 200, "body": { "data": [ { "eventName": "Thanksgiving Day", "atDate": "2026-11-26", "tradingHour": "", "postMarket": "" },
                                         { "eventName": "Thanksgiving Day", "atDate": "2026-11-27", "tradingHour": "09:30-13:00", "postMarket": "" } ],
                               "exchange": "US", "timezone": "America/New_York" } }
```
```json
{ "httpStatus": 500, "body": null }
```
```json
{ "noKey": true }
```
- `httpStatus` 非 200 → `HTTP_ERROR`；`body` 不符合格式 → `INVALID_RESPONSE`；`noKey: true` → `NO_KEY`。
- 响应 `200 OK`，响应体同 `GET /api/trading-calendar/status`（处理后的状态）。

##### `PUT /api/test-support/clock`、`DELETE /api/test-support/clock`
固定后端"现在"（影响所有使用 `Clock` 的逻辑：今天、DTE、剩余交易日、16:00 截止、成交时间不能晚于现在等）。
- `PUT` 请求体 `{"now": "2026-11-23T15:00:00Z"}`（Instant）→ `204`。时钟固定不走动。
- `DELETE` → `204`，恢复系统时间。
- `POST /api/test-support/reset` 也恢复系统时间，并清空休市日数据与拉取状态（恢复为新库）。

##### 配置属性（后端测试 / 系统测试用的 seam，`optionmgr.finnhub.*`）
| 属性 | 默认（正常运行） | `test` / `e2e` profile | 说明 |
|------|-----------------|------------------------|------|
| `optionmgr.finnhub.fetch-enabled` | `true` | `false` | 启动后拉一次 + 每天美东 08:00。`false` 时没有任何自动拉取（不读 key、不访问网络）。测试可设 `true` 打开启动拉取，或直接调用 `HolidayFetchService.fetchNow()`（走与真实拉取相同的取 key → 请求 → 处理流程） |
| `optionmgr.finnhub.base-url` | `https://finnhub.io` | `http://127.0.0.1:9`（不可达） | 请求 `<base-url>/api/v1/stock/market-holiday?exchange=US`；测试指向本地假 HTTP 服务 |
| `optionmgr.finnhub.api-key` | 未设置 | 未设置 | key 优先级：**此属性 > 环境变量 `FINNHUB_API_KEY` > `.env` 文件**。只用于测试放假 key；不要写真实 key |
| `optionmgr.finnhub.read-system-env` | `true` | `false` | 是否读环境变量 `FINNHUB_API_KEY`（测试中关闭，避免读到真实 key） |
| `optionmgr.finnhub.env-files` | `.env, ../.env` | 不存在的路径 | 依次查找、读第一个存在的 `.env`；测试可指向临时文件 |

所以 `test` profile 下默认：没有自动拉取、不读真实 key、不访问 Finnhub；日历 = 规则 + 测试自己注入的数据（`fetchNow()` 配假服务，或 `HolidayFetchService.process(FetchAttempt)` 直接给出假响应）。启动时拉取在后台线程进行，测试应轮询 `TradingCalendarService.status()` / `GET /api/trading-calendar/status` 直到 `lastAttemptResult` 非 `null`。

---

#### 数据库迁移 `V13__trading_calendar.sql`
- `market_holiday`：`holiday_date DATE` 主键、`event_name VARCHAR(200) NOT NULL`、`trading_hour VARCHAR(50) NULL`（空串存 `NULL`）、`fetched_at TIMESTAMP NOT NULL`。只存 Finnhub 数据（规则年份不落库，实时推算）。
- `market_holiday_fetch_status`：单行；`last_attempt_at`、`last_attempt_result`、`last_failure_detail`、`last_success_at`，新库各列为 `NULL`。
- 不改任何已有表和数据。测试资源 `backend/src/test/resources/db/failing-migration/V13__simulated_failure.sql` 改名为 `V14__simulated_failure.sql`（并改引用它的测试）。

#### 已知限制
- 临时休市检测与提醒在 FEAT-042；调整到期日在 FEAT-043。
- 到期时刻、16:00 / 17:30 规则不随提前收盘或假日变化（Q122、Q124）。
- 每天只拉一次；页面不能手动刷新（Out of Scope）。
