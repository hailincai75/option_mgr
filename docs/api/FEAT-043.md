# FEAT-043 临时休市时调整合约到期日（Q43 的例外）

> **FEAT-035 修订**：调整响应的 `position` 与持仓行同一结构，也带 `accountId`、`accountName`；`X-Account-Id: ALL` 时调整请求 → 400 `ALL_ACCOUNTS_READ_ONLY`。见 [FEAT-035.md](FEAT-035.md)。
>
> **FEAT-048 修订**：`position.lots[]` 同样新增批次的 `marketValue`、`unrealizedPnl`、`unrealizedPnlPercent`、`unrealizedStatus`、`unrealizedLabel`。见 [FEAT-048.md](FEAT-048.md)。
>
> **FEAT-051 修订**：`position.lots[]` 同样新增 `closeDefault`。调整只改 `expiration_date`，平仓保存的所选批次（按交易 id）不受影响。见 [FEAT-051.md](FEAT-051.md)。

建立在 FEAT-042（临时休市记录、`AdhocClosureService.findActive`、持仓 `expiresOnAdhocClosure`）、FEAT-009（交易日判断）、FEAT-005（审计、重放、`hasAuditLog`）之上。Q43 例外、Q83、Q126、Q129–Q133 已定。本节只写**新增或不同**的部分；示例标的均为虚构。

**兼容性一览**

| 接口 | 新增（兼容） | **行为变化** |
|------|-------------|-------------|
| `POST /api/option-trades/expiration-adjustments` | 新接口（按账户） | — |
| `GET /api/option-positions` | `positions[].expirationAdjustable`、`expirationAdjustmentRecordCount`、`defaultNewExpirationDate`、`newExpirationDateMin`、`newExpirationDateMax` | — |
| `GET /api/audit-log` | `operation` 新值 `ADJUST_EXPIRATION`；`items[].expirationAdjustment`（其它记录为 `null`） | `objectType=OPTION_TRADE&objectId=X` 也返回 `tradeIds` 含 X 的调整记录（见下） |
| `GET /api/option-trades`、`/api/closed-trades`、`/api/option-positions`、`/api/adhoc-closure-alerts` | — | 调整后记录的 `expirationDate` / `contractLabel` 变为新日期；被调整的记录 `hasAuditLog = true`；提醒个数减少 |
| 测试支持 `POST /api/test-support/legacy-option-trades` | 也接受 BTO / STO 的 put 与 BTO call | STO call 行为不变 |

---

#### 概念
- **合约**：`(账户, 标的, 到期日, 行权价, C/P)`。调整只改当前账户（Q53）。
- **该合约的记录**：当前账户中该合约所有未删除的 `option_trade` 行（开仓 BTO / STO、平仓 BTC / STC，以及 `EXP` / `ASG` / `EXR`）。
- **已结束**：该合约有任何 `EXP`、`ASG`、`EXR` 记录（含部分被指派 / 行权，Q133）。
- **最后成交日**：该合约记录中最晚 `tradedAt` 的美东日期。
- **新日期范围**（Q131）：`最后成交日 ≤ 新到期日 < 当前到期日`，且新到期日是交易日（FEAT-009，按请求时的日历）。可以早于今天。
- **调整过**：`expiration_adjustment` 表里有一行的"新合约"（账户 + 标的 + `new_expiration_date` + 行权价 + C/P）就是调整过的合约，不能再调（Q131）。正常情况下它的到期日已是交易日，第 3 步就会拒绝；这一行用于防止新日期之后也被 FEAT-042 记为临时休市的情况。调整后在新合约上录入的平仓等新记录不影响此判断。
- **默认新日期**：临时休市日之前的最近一个交易日（FEAT-009）。

---

#### `POST /api/option-trades/expiration-adjustments`
把当前账户中一个合约的全部记录的到期日改为新日期。**要求 `X-Account-Id`**。一个事务完成，持有 `cash_account` 行锁（与其它写操作串行）。

请求体：
```json
{
  "symbol": "QRTV",
  "expirationDate": "2026-12-04",
  "strike": "50",
  "optionType": "PUT",
  "newExpirationDate": "2026-12-03",
  "note": "临时休市，交易所改为 12-03 到期"
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `symbol` / `expirationDate` / `strike` / `optionType` | | 是 | **当前**合约，规则与 FEAT-001 完全相同（标的规范化、行权价按数值比较，错误文案相同）。前端原样传持仓行的值 |
| `newExpirationDate` | string | 是 | `YYYY-MM-DD`。缺失 → `VALIDATION_ERROR`（`新到期日不能为空`）；格式不对 → `BAD_REQUEST` |
| `note` | string | 否 | 最多 500 字符（超出 → `VALIDATION_ERROR`，`备注最多 500 个字符`）；`null` / 空白 = 无备注；去首尾空白后写进审计 |
| 其它字段 | — | — | 带了一律忽略 |

检查顺序（任一失败即整笔拒绝，什么都不改、不写审计）：

| # | code | HTTP | 何时 / `message` |
|---|------|------|------------------|
| 1 | `VALIDATION_ERROR` / `BAD_REQUEST` | 400 | 字段校验；账户错误同 FEAT-033（`ACCOUNT_REQUIRED` / `BAD_REQUEST` 400、`ACCOUNT_NOT_FOUND` 404） |
| 2 | `NOT_FOUND` | 404 | 当前账户没有该合约的任何记录：`该账户没有合约 QRTV 2026-12-04 50 P 的记录` |
| 3 | `EXPIRATION_NOT_ADHOC_CLOSURE` | 422 | `expirationDate` 不是 `ACTIVE` 临时休市日（`AdhocClosureService.findActive` 为空；已撤销的也算不是）：`只能调整到期日为临时休市日的合约`（需求 3.1） |
| 4 | `EXPIRATION_ALREADY_ADJUSTED` | 422 | 该合约是调整过的合约（见概念）：`该合约已调整过到期日，不能再次调整`（Q131） |
| 5 | `CONTRACT_ALREADY_SETTLED` | 422 | 已结束：`合约已作废 / 被指派 / 行权，不能调整到期日`（需求 3.2，Q133） |
| 6 | `NEW_EXPIRATION_NOT_TRADING_DAY` | 422 | 新到期日是周末或全天休市日：`新到期日 2026-12-05 不是美股交易日`（需求 3.3） |
| 7 | `NEW_EXPIRATION_OUT_OF_RANGE` | 422 | 新到期日 ≥ 当前到期日：`新到期日必须早于当前到期日 2026-12-04`；新到期日 < 最后成交日：`新到期日不能早于该合约最后一笔记录的成交日期 2026-11-25`（先查前者）（需求 3.4） |
| 8 | `TRADE_AFTER_NEW_EXPIRATION` | 422 | 某条记录 `tradedAt` > 新到期日美东 16:00（只可能在最后成交日 = 新到期日时）；指出最早的那一笔：`交易 #3 BTC 的成交时间 2026-12-03 16:30 晚于新到期日 2026-12-03 16:00`（需求 3.5，Q83） |
| 9 | `TARGET_CONTRACT_EXISTS` | 422 | 当前账户中"标的 + 新到期日 + 行权价 + C/P"已有任何记录：`该账户已有合约 QRTV 2026-12-03 50 P 的记录，不能合并`（需求 3.6，Q132） |
| 10 | FEAT-005 / FEAT-039 的重放错误码（`POSITION_DIRECTION_CONFLICT`、`MULTIPLIER_MISMATCH`、`NO_POSITION_TO_CLOSE`、`CLOSE_EXCEEDS_POSITION`、`CLOSE_NOT_WHOLE_LOTS`、`CASH_INSUFFICIENT`、`COVERAGE_INSUFFICIENT`） | 422 | 修改后按 Q19 重放（含 FEAT-039 覆盖检查）不通过（Q130）。文案同 FEAT-005，前缀为 `调整后`，如 `调整后该合约（QRTV 2026-12-03 50 P）在 …`。金额不变、记录相对顺序不变，正常数据下不会出现，是防御性检查 |

所有 422 的 `details` 为 `[]`。

成功后（同一事务）：
- 该合约所有记录的 `expiration_date` 改为新日期；时间、张数、净额、multiplier、批次、备注、覆盖状态、流水金额与冻结变动都不变（Q130）。现金流水的 `description` 若含合约文字，改为新合约文字（如 `卖出开仓 1 张 QRTV 2026-12-03 50 P`）。
- 写一条 `ADJUST_EXPIRATION` 审计（见下）；被修改的记录 `has_audit_log = true`。
- 写一行 `expiration_adjustment`（"只能调一次"）。
- 不改 FEAT-042 的临时休市记录；之后的提醒 / 标签按新到期日实时计算（Q129）。

响应 `200 OK`（验收 3）：
```json
{
  "auditId": 1,
  "symbol": "QRTV",
  "strike": "50",
  "optionType": "PUT",
  "oldExpirationDate": "2026-12-04",
  "newExpirationDate": "2026-12-03",
  "contractLabel": "QRTV 2026-12-03 50 P",
  "tradeIds": [1, 2, 3],
  "position": {
    "symbol": "QRTV", "expirationDate": "2026-12-03", "strike": "50", "optionType": "PUT",
    "contractLabel": "QRTV 2026-12-03 50 P", "direction": "SHORT", "contracts": 1, "multiplier": 100,
    "frozenCash": "5000.00", "dte": 2, "remainingTradingDays": 2, "expirationStatus": "ACTIVE",
    "expiresOnAdhocClosure": false, "adhocClosureEventName": null,
    "expirationAdjustable": false, "expirationAdjustmentRecordCount": 0,
    "defaultNewExpirationDate": null, "newExpirationDateMin": null, "newExpirationDateMax": null,
    "…": "其它字段同 GET /api/option-positions"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `auditId` | number | 新审计记录 id |
| `symbol` / `strike` / `optionType` | | 合约（规范化后；`strike` 去尾零） |
| `oldExpirationDate` / `newExpirationDate` | string | `YYYY-MM-DD` |
| `contractLabel` | string | 新合约文字 |
| `tradeIds` | number[] | 被修改的记录 id，升序 |
| `position` | object \| null | 新合约的持仓，表示与 `GET /api/option-positions` 的 `positions[]` 相同；合约已全部平仓（净张数 0）时为 `null` |

前端成功后让 `option-positions`、`adhoc-closure-alerts`、`option-trades`、`closed-trades`、`audit-log`、现金流水一起失效重查。

错误示例：
```json
{
  "code": "NEW_EXPIRATION_NOT_TRADING_DAY",
  "message": "新到期日 2026-12-05 不是美股交易日",
  "status": 422,
  "path": "/api/option-trades/expiration-adjustments",
  "timestamp": "2026-12-01T15:00:00.123Z",
  "details": []
}
```

---

#### `GET /api/option-positions`（新增字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `expirationAdjustable` | boolean | 显示 `调整到期日` 按钮：`expiresOnAdhocClosure = true`（需求 3.1）且合约未结束（3.2，部分被指派 / 行权后仍是持仓，此时为 `false`）且不是调整过的合约。其余检查（3.3–3.7）在提交时做 |
| `expirationAdjustmentRecordCount` | number | 将被修改的记录条数（该合约当前账户全部记录，含已被平掉的开仓和平仓；对话框 `N 条开仓 / 平仓记录`）。`expirationAdjustable = false` 时为 `0` |
| `defaultNewExpirationDate` | string \| null | 新到期日默认值 = 临时休市日前最近的交易日；不可调整时 `null` |
| `newExpirationDateMin` | string \| null | 可选范围下限（含）= 最后成交日；不可调整时 `null` |
| `newExpirationDateMax` | string \| null | 可选范围上限（含）= 当前到期日前一天（日历日）；不可调整时 `null` |

日期选择器：范围外的日期、以及 FEAT-009 `GET /api/trading-calendar/non-trading-days` 里的日期都禁用。休市名称用已有的 `adhocClosureEventName`。`defaultNewExpirationDate` 可能早于 `newExpirationDateMin`（最后一笔记录就在默认日之后的情况），此时前端仍显示默认值，提交时由第 7 步拒绝。

示例（验收 1、2，今天 2026-12-01）：
```json
{ "symbol": "QRTV", "expirationDate": "2026-12-04", "strike": "50", "optionType": "PUT",
  "contractLabel": "QRTV 2026-12-04 50 P", "direction": "SHORT", "contracts": 1,
  "expiresOnAdhocClosure": true, "adhocClosureEventName": "National Day of Mourning",
  "expirationAdjustable": true, "expirationAdjustmentRecordCount": 3,
  "defaultNewExpirationDate": "2026-12-03", "newExpirationDateMin": "2026-11-25", "newExpirationDateMax": "2026-12-03",
  "…": "其它字段不变" }
```
P3（QRTV 2026-12-18 45 P）：`expirationAdjustable: false`、`expirationAdjustmentRecordCount: 0`，三个日期 `null`。

FEAT-042 的 `去调整`：仍滚动到第一个 `expiresOnAdhocClosure = true` 且 `expirationDate` = 提醒 `date` 的行（该行有按钮时即可点 `调整到期日`）。

---

#### 审计 `GET /api/audit-log`
每次调整写**一条**记录：

```json
{
  "id": 1,
  "operatedAt": "2026-12-01T15:00:00Z",
  "operation": "ADJUST_EXPIRATION",
  "objectType": "OPTION_TRADE",
  "objectId": 1,
  "summary": "调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）",
  "before": { "symbol": "QRTV", "expirationDate": "2026-12-04", "strike": "50", "optionType": "PUT", "contractLabel": "QRTV 2026-12-04 50 P", "tradeIds": [1, 2, 3] },
  "after":  { "symbol": "QRTV", "expirationDate": "2026-12-03", "strike": "50", "optionType": "PUT", "contractLabel": "QRTV 2026-12-03 50 P", "tradeIds": [1, 2, 3] },
  "changes": [
    { "field": "expirationDate", "label": "到期日", "before": "2026-12-04", "after": "2026-12-03", "text": "到期日：2026-12-04 → 2026-12-03" }
  ],
  "sideEffects": [],
  "expirationAdjustment": {
    "symbol": "QRTV", "strike": "50", "optionType": "PUT",
    "oldExpirationDate": "2026-12-04", "newExpirationDate": "2026-12-03",
    "adhocClosureEventName": "National Day of Mourning",
    "tradeIds": [1, 2, 3],
    "note": "临时休市，交易所改为 12-03 到期"
  }
}
```

| 字段 | 说明 |
|------|------|
| `operation` | 新值 `ADJUST_EXPIRATION`（页面显示 `调整到期日`）。不能撤销，没有对应的删除 |
| `objectType` / `objectId` | `OPTION_TRADE` + `tradeIds` 中最小的 id |
| `summary` | `调整到期日 <标的> <行权价> <C\|P>：<原到期日> → <新到期日>（<N> 条记录）` |
| `before` / `after` | 合约（原 / 新到期日）与 `tradeIds`。不含单笔交易内容（交易其它字段都没变） |
| `changes` | 只有 `expirationDate` 一项 |
| `sideEffects` | 恒为 `[]`（金额、冻结不变，Q130） |
| `expirationAdjustment` | **新字段**，所有审计项都有：调整记录为上面的对象，其它记录为 `null`。`adhocClosureEventName` = 调整时临时休市的 `eventName`；`note` 为 `null` 或备注原文 |

筛选：`objectType=OPTION_TRADE&objectId=X` 返回 `objectId = X` 的记录**以及** `tradeIds` 含 X 的调整记录，所以每条被修改记录的"历史"都能看到这次调整。不加按 `operation` 筛选的参数。
之后删除其中一条记录（FEAT-005）：调整审计保留不变（`tradeIds` 里仍有该 id）。

---

#### 之后的到期处理
一律按记录上的（新）到期日：16:00 截止、17:30 可作废、作废记在新到期日 16:00（Q63、Q83），DTE、剩余交易日、`expirationStatus` 都按新日期。没有任何地方再引用原到期日（只在审计和 `expiration_adjustment` 里保留）。

---

#### 测试支持（`e2e` profile）
- 不新增接口。用 FEAT-009 的 `PUT /api/test-support/clock`、`POST /api/test-support/trading-calendar/fetch`（成功注入后 FEAT-042 检测记录临时休市，如在样本中加入 `{"eventName": "National Day of Mourning", "atDate": "2026-12-04", "tradingHour": ""}`）。建议顺序：设时钟 → 正常开仓（此时 12-04 仍是交易日）→ 注入拉取。
- **扩展** `POST /api/test-support/legacy-option-trades`（验收 8、9 的 2025-01-09 合约：正常开仓被 Q126 拒绝）：`action` 接受 `BTO` / `STO`，`optionType` 接受 `CALL` / `PUT`；其它方向（BTC / STC 等）→ 400 `VALIDATION_ERROR`（field `action`，文案同 `POST /api/option-trades`：`本功能只支持开仓（BTO / STO），平仓请使用平仓功能`；**实现阶段更正**，原写 `只能预置开仓`）。STO call 行为同 FEAT-039（`LEGACY` + 自动纳入）；其它组合做 `POST /api/option-trades` 除"到期日是交易日"（Q126）以外的全部检查（STO put 仍查 Q18 现金担保，需先入金），正常保存。请求 / 响应同 `POST /api/option-trades`，需要 `X-Account-Id`。
- `POST /api/test-support/reset`：新表由重置快照机制自动纳入，重置后为空。
- 后端测试 seam：领域类 `ExpirationAdjustmentRules`（纯 Java）可单测检查 3–9 的判断与顺序；系统测试可用 `OptionTradeService.open(accountId, command, true)` 预置旧合约。

---

#### 数据库迁移 `V15__expiration_adjustment.sql`
- `expiration_adjustment`：`id` 自增主键、`account_id` FK、`symbol`、`strike DECIMAL`、`option_type`、`old_expiration_date DATE`、`new_expiration_date DATE`、`adjusted_at TIMESTAMP WITH TIME ZONE`、`audit_record_id` FK；唯一约束 `(account_id, symbol, new_expiration_date, strike, option_type)`（只调一次）。
- `audit_record_object`：`audit_record_id` FK、`object_type VARCHAR(20)`、`object_id BIGINT`，主键三列；记录调整审计涉及的全部交易 id，供 `objectId` 筛选。
- `audit_record.operation` 加宽为 `VARCHAR(20)`；`ck_audit_operation` 增加 `'ADJUST_EXPIRATION'`。
- `audit_record` 新列 `detail_json VARCHAR(10000) NULL`（**实现阶段补充**）：存审计项的 `expirationAdjustment` 对象；其它记录为 `NULL`。
- `audit_record_object`、`expiration_adjustment` 有指向 `audit_record`（及 `account`）的外键：测试清理数据时先删这两张表再删 `audit_record` / `account`（`POST /api/test-support/reset` 已自动处理）。
- 不回填，不改已有数据。测试资源 `db/failing-migration/V15__simulated_failure.sql` 改名为 `V16__simulated_failure.sql`，并更新引用它的测试与写死的备份名。

#### 已知限制
- 不能撤销；选错新日期只能删除全部记录后重录（Q131）。
- 只调整当前账户；其它账户的同一合约各自调整。
- 临时休市记录之后被撤销（Q127）：已调整的记录不回退。
- 不支持批量、不支持非临时休市日的到期日（Out of Scope）。
