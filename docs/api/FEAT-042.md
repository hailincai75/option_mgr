# FEAT-042 临时休市检测与提醒

建立在 FEAT-009 之上（Finnhub 拉取、`market_holiday`、Q127 整年替换、Jollyday 规则年份、测试支持假拉取与假时钟）。Q53、Q124、Q127–Q129 已定。本节只写**新增或不同**的部分；示例标的均为虚构。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `GET /api/adhoc-closure-alerts` | 新接口（按账户，首页和持仓页共用） | — |
| `GET /api/trading-calendar/adhoc-closures` | 新接口（全系统一份，FEAT-043 复用） | — |
| `GET /api/option-positions` | `positions[].expiresOnAdhocClosure`、`positions[].adhocClosureEventName` | — |
| FEAT-009 拉取（定时 / 启动 / 测试支持 `POST /api/test-support/trading-calendar/fetch`） | 成功后做临时休市检测（同一事务） | 响应体不变 |
| `POST /api/test-support/reset` | 也清空临时休市记录（自动，见下） | — |
| `GET /api/dashboard`、`non-trading-days`、`status`、开仓 422、作废 / 指派 / 行权 / DTE / 剩余交易日 | — | — （Q124：一律按录入的到期日） |

---

#### 概念
- **今天**：同 FEAT-009（注入 `Clock` 的美东日期）。
- **全天休市（Finnhub）**：拉取响应中 `tradingHour` 为空（`""` 或缺失）的条目。提前收盘（`tradingHour` 非空）不算（Q122）。
- **检测（需求规则 1）**：每次**成功**拉取，在 FEAT-009 Q127 合并的同一事务里、**写入新数据之前**，对响应中每个全天休市日期 `d`（星期一至五）判断，满足其一即为**临时休市**：
  1. `d` 不是 Jollyday NYSE 的**每年固定假日**（Q134；取 observed 日期）。Jollyday 中只发生一次的特殊日（如 2025-01-09 国家哀悼日）**不算**"规则产生"；
  2. 合并前 `d` 的年份是覆盖年份（`market_holiday` 有该年的行），但 `d` 不是已存的全天休市日（没有这一行，或那一行是提前收盘）。
  - 新库第一次成功拉取时没有已存数据，第 2 条自然不成立，只用第 1 条。
  - **Q134 与库行为**：`jollyday` 2.22.1 的 NYSE 日历把 2025-01-09（`National Day of Mourning (death of President Jimmy Carter)`）作为只在 2025 年有效的特殊日（`validFrom = validTo = 2025`）。判定"每年固定"：同一 Jollyday 假日（properties key，如 `CHRISTMAS`）在前一年或后一年也出现。所以 2025-01-09 在第一次拉取时被记录为临时休市。
  - **FEAT-009 不变**：2025-01-09 仍是非交易日（`non-trading-days`、剩余交易日照旧），正常开仓到期日 2025-01-09 仍被 422 `EXPIRATION_NOT_TRADING_DAY` 拒绝（Q126，在任何拉取之前也是）。
  - 已用固定样本（`backend/src/test/resources/finnhub/market-holiday-us-2023-2027.json`，按 NYSE 公布日历构造）锁定：2023–2027 每年 Jollyday 的全天休市与 Finnhub 的完全一致；常规假日（含 Juneteenth、observed 日期、2027-12-24）都判为每年固定；对这份数据第一次拉取只记录 2025-01-09。
  - 周末日期不记录。
  - 拉取失败（`NO_KEY` / `NETWORK_ERROR` / `HTTP_ERROR` / `INVALID_RESPONSE`）：不检测，记录不变。
- **记录（需求规则 2）**：每个日期最多一条，全系统一份（Q53）：`date`、`eventName`、`detectedOn`（检测到时那次拉取尝试时间的美东日期）、`status`（`ACTIVE` 有效 / `REVOKED` 已撤销）、`revokedOn`。
  - 已有 `ACTIVE` 记录的日期再次被拉到：不新增，`detectedOn` 不变；`eventName` 更新为本次响应的 `eventName`（与 `non-trading-days` 的 `name` 保持一致）。
  - **撤销（Q127）**：某次成功拉取的响应包含该记录的年份，但该日期不再是全天休市（被删除或改为提前收盘）→ `status = REVOKED`，`revokedOn` = 本次拉取的美东日期。响应不包含该年份时（年份滚出窗口），Finnhub 已存数据保留，记录也不变。
  - **撤销后又出现**：同一日期之后又作为全天休市出现在响应中 → 恢复为 `ACTIVE`，`revokedOn` 置 `null`，`detectedOn` 保持第一次检测的日期（需求"同一日期只记一次、不改检测日期"）。
- **受影响持仓（需求规则 3）**：某账户中 `expirationDate` = 某个 `ACTIVE` 临时休市日的期权持仓（FEAT-003 定义：合约净张数 ≠ 0，所以平完 / 作废 / 被指派 / 行权后不再是持仓）。一个合约多个批次算 1 个持仓。已过期未处理的持仓也算。FEAT-043 调整到期日后到期日不再相等，自然不算。股票持仓不算。
- 提醒、标签都在请求时实时计算，不存库；所以平仓、作废、删除交易 / 删除作废等任何改动后，下一次查询立即反映（Q129 自动消失；删除后持仓重新出现则再次计入）。

---

#### `GET /api/adhoc-closure-alerts`
当前账户的临时休市提醒（需求规则 4），**首页和持仓页共用**。无参数。**要求 `X-Account-Id`**。只返回有受影响持仓的 `ACTIVE` 临时休市日；没有时 `alerts: []`，页面不显示提醒（Q129）。没有关闭 / 忽略接口（Q128）。

请求示例：
```
GET /api/adhoc-closure-alerts
X-Account-Id: 3
```

响应 `200 OK`（验收 6，账户 C）：
```json
{
  "alerts": [
    {
      "date": "2025-01-09",
      "eventName": "National Mourning Day",
      "affectedPositionCount": 1,
      "message": "2025-01-09 临时休市（National Mourning Day），你有 1 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日"
    },
    {
      "date": "2026-12-04",
      "eventName": "National Day of Mourning",
      "affectedPositionCount": 1,
      "message": "2026-12-04 临时休市（National Day of Mourning），你有 1 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `alerts` | array | 按 `date` 升序；只含 `affectedPositionCount > 0` 的日期。没有时 `[]` |
| `alerts[].date` | string | 临时休市日 `YYYY-MM-DD` |
| `alerts[].eventName` | string | Finnhub `eventName`（原样） |
| `alerts[].affectedPositionCount` | number | 该账户受影响持仓个数（≥ 1） |
| `alerts[].message` | string | 可直接显示的整行文字（不含行末 `去调整` 链接）：`<date> 临时休市（<eventName>），你有 <n> 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日`。前端原样显示，不自己拼 |

`去调整`：前端行为（首页跳转持仓页；持仓页滚动到第一个 `expiresOnAdhocClosure = true` 且 `expirationDate` = 该行 `date` 的持仓行）。

错误：账户错误同 FEAT-033（400 `ACCOUNT_REQUIRED`、400 `BAD_REQUEST`、404 `ACCOUNT_NOT_FOUND`）；其余只有通用错误。

---

#### `GET /api/trading-calendar/adhoc-closures`
全系统的临时休市记录（含已撤销），供排查与 **FEAT-043 复用**（判断某个到期日是否为"检测到的临时休市"）。与其它 `/api/trading-calendar/**` 一样**不要求** `X-Account-Id`（带了也忽略）。

Query 参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `status` | 否 | `ACTIVE` 或 `REVOKED`（区分大小写）；不传返回全部。其它值 → `BAD_REQUEST`（`details[].field = "status"`） |

请求示例：
```
GET /api/trading-calendar/adhoc-closures
```

响应 `200 OK`（验收 8 之后）：
```json
{
  "closures": [
    { "date": "2025-01-09", "eventName": "National Mourning Day", "detectedOn": "2026-11-30", "status": "ACTIVE", "revokedOn": null },
    { "date": "2026-12-04", "eventName": "National Day of Mourning", "detectedOn": "2026-12-01", "status": "REVOKED", "revokedOn": "2026-12-02" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `closures` | array | 按 `date` 升序；没有时 `[]` |
| `closures[].date` | string | `YYYY-MM-DD` |
| `closures[].eventName` | string | 最近一次拉到的 Finnhub `eventName` |
| `closures[].detectedOn` | string | 第一次检测到的美东日期，之后不变 |
| `closures[].status` | string | `ACTIVE`（有效，参与提醒 / 标签）/ `REVOKED`（已撤销，Q127，不提醒） |
| `closures[].revokedOn` | string \| null | 撤销时那次拉取的美东日期；`ACTIVE` 时为 `null` |

**给 FEAT-043 的后端接口**（Java，非 HTTP）：`calendar/service/AdhocClosureService`
- `List<AdhocClosure> list(Optional<AdhocClosureStatus>)`（同上接口）
- `Optional<AdhocClosure> findActive(LocalDate date)` —— "该日期是否为有效的临时休市"，FEAT-043 判断能否调整、持仓标签都用它
- 受影响持仓计算（`affectedPositions(accountId)`）同一处实现，提醒与持仓标签共用

错误：`status` 值不对 → 400 `BAD_REQUEST`；其余只有通用错误。

---

#### `GET /api/option-positions`（FEAT-003，新增字段）
`positions[]` 新增：

| 字段 | 类型 | 说明 |
|------|------|------|
| `expiresOnAdhocClosure` | boolean | 该持仓 `expirationDate` 是一个 `ACTIVE` 临时休市日（即受影响持仓）。为 `true` 时到期日旁显示红色标签 `临时休市`；FEAT-043 在这些行上加"调整到期日" |
| `adhocClosureEventName` | string \| null | `expiresOnAdhocClosure` 时为该临时休市的 `eventName`（标签悬停显示）；否则 `null` |

`dte`、`remainingTradingDays`、`expirationStatus`、排序、作废 / 指派 / 行权按钮条件都不变（Q124，按录入的到期日）。股票持仓不加。

示例（验收 3，账户 A，今天 2026-12-01）：
```json
{
  "asOfDate": "2026-12-01",
  "positionCount": 3,
  "positions": [
    { "symbol": "IXQZ", "expirationDate": "2026-12-04", "strike": "9", "optionType": "CALL", "contractLabel": "IXQZ 2026-12-04 9 C",
      "direction": "LONG", "contracts": 2, "dte": 3, "remainingTradingDays": 2, "expirationStatus": "ACTIVE",
      "expiresOnAdhocClosure": true, "adhocClosureEventName": "National Day of Mourning", "…": "其它字段不变" },
    { "symbol": "QRTV", "expirationDate": "2026-12-04", "strike": "50", "optionType": "PUT", "contractLabel": "QRTV 2026-12-04 50 P",
      "direction": "SHORT", "contracts": 1, "dte": 3, "remainingTradingDays": 2, "expirationStatus": "ACTIVE",
      "expiresOnAdhocClosure": true, "adhocClosureEventName": "National Day of Mourning", "…": "其它字段不变" },
    { "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "45", "optionType": "PUT", "contractLabel": "QRTV 2026-12-18 45 P",
      "direction": "SHORT", "contracts": 1, "dte": 17, "remainingTradingDays": 12, "expirationStatus": "ACTIVE",
      "expiresOnAdhocClosure": false, "adhocClosureEventName": null, "…": "其它字段不变" }
  ]
}
```
（`remainingTradingDays` 按 FEAT-009：12-04 已是非交易日，所以 (12-01, 12-04] = 12-02、12-03 共 2。示例中 `frozenTotal` 等省略。）

---

#### 提醒数据怎么到页面
- **首页**：`GET /api/dashboard`（不变）+ `GET /api/adhoc-closure-alerts`，`alerts` 非空时在顶部按顺序每条一行显示 `message` + `去调整`。
- **持仓页**：`GET /api/adhoc-closure-alerts`（顶部提醒）+ `GET /api/option-positions`（行标签用 `expiresOnAdhocClosure` / `adhocClosureEventName`）。两者在同一次请求时刻一致（同一 service 计算）。
- 前端在切换账户、以及任何会改变期权持仓的写操作（开仓、平仓、作废、批量作废、指派、行权、修改 / 删除交易）成功后，让 `adhoc-closure-alerts` 与 `option-positions` 一起失效重查；不判断日期、不自己计数。
- 拉取失败不影响已有记录（验收 9）：提醒照常，另由 FEAT-009 `GET /api/trading-calendar/status` 的 `warning` 显示"休市日数据未更新"。

---

#### 测试支持（`e2e` profile）
**不新增接口**，全部用 FEAT-009 已有的：
- `PUT /api/test-support/clock` 设"今天"（`detectedOn` / `revokedOn` 取拉取时的时钟美东日期）→ `POST /api/test-support/trading-calendar/fetch` 注入假 Finnhub 响应。成功的注入与真实拉取走同一处理，**包含临时休市检测**；失败的注入（`httpStatus` 非 200、`noKey`、格式不对）不检测。响应体仍是 status（不变），检测结果用 `GET /api/trading-calendar/adhoc-closures` 查。
- `POST /api/test-support/reset`：新表 `adhoc_closure` 由重置快照机制自动纳入，重置后为空。
- 验收 6 的"FEAT-009 之前录入"的 2025-01-09 卖出 put：正常开仓接口会被 Q126 拒绝；`legacy-option-trades` 目前只接受 STO CALL（FEAT-039）。后端系统测试（进程内）可直接调用 `OptionTradeService.open(accountId, command, true)`（legacy，不查交易日和覆盖）；E2E 如需要，另行扩展测试支持（见需求 Implementation Notes）。
- 后端测试 seam：`HolidayFetchService.process(FetchAttempt)` / `fetchNow()` + `calendar/FakeFinnhub` 同 FEAT-009；`AdhocClosureDetector`（domain，纯 Java）可单测：输入（响应全天休市日、合并前已存数据、`HolidayRules`、现有记录、今天）→ 新增 / 更新 / 撤销 / 恢复。
- Jollyday 基线：`JollydayHolidayRulesTest`、`JollydayFinnhubAgreementTest` 锁定 `jollyday` 2.22.1：2025-01-09 为一次性特殊日（不是每年固定），2023–2027 其它常规假日都是每年固定；对固定样本第一次拉取只记录 2025-01-09。升级库行为变化时测试失败。

---

#### 数据库迁移 `V14__adhoc_closure.sql`
- `adhoc_closure`：`closure_date DATE` 主键、`event_name VARCHAR(200) NOT NULL`、`detected_on DATE NOT NULL`、`status VARCHAR(20) NOT NULL`（`ACTIVE` / `REVOKED`）、`revoked_on DATE NULL`。无外键（与账户无关）。
- 不回填：升级后第一次成功拉取时按检测规则检测（已存数据作为"合并前"数据），已存的 2025-01-09 会在那次被记录（检测日期 = 那次拉取的日期）。
- 测试资源 `db/failing-migration/V14__simulated_failure.sql`（始终比最新真实迁移大 1）改名为 `V15__simulated_failure.sql`，并更新引用它的测试与写死的备份名（`before-V14`）。

#### 已知限制
- 只检测 Finnhub 覆盖年份内的日期；规则年份（2028+）没有临时休市概念。
- 提醒不区分持仓是否已过期（如 2025-01-09 的旧持仓仍提醒，需求边界情况）。
- 调整到期日在 FEAT-043；本需求不改任何到期、作废、DTE 行为（Q124）。
- 不提供列表页、通知（Out of Scope）。
