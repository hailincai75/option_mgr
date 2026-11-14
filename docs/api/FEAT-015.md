# FEAT-015 到期批量作废

**兼容性**：只新增一个接口，没有新字段、新枚举值、新记录类型、新流水类型，也不需要数据库迁移。批量作废产生的记录与 `POST /api/option-trades/expirations`（FEAT-012）产生的**完全相同**（Q119）：每个持仓一条普通 `EXP` 记录，空头 put 再加一条 `OPTION_EXPIRE` 流水。没有"批次"记录或批次号，不写审计。所有读取接口（交易列表、持仓、已平仓、盈亏汇总、首页、现金汇总、流水、审计）都没有变化。删除仍然逐条用 `DELETE /api/option-trades/{id}`（FEAT-012）。

和其它业务接口一样，必须带请求头 `X-Account-Id`，只处理该账户的持仓（Q53）。

#### `POST /api/option-trades/expirations/batch`
一次作废多个持仓。每一项都做一次与 FEAT-012 完全相同的作废（同样的检查、同样的记录、同样的盈亏和冻结）。整批只有两种结果：全部成功，或者什么都不保存（Q116）。

请求体：
```json
{
  "items": [
    { "symbol": "XYZ",  "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",  "contracts": 2 },
    { "symbol": "XYZ",  "expirationDate": "2026-11-20", "strike": "42", "optionType": "PUT",  "contracts": 1 },
    { "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "30", "optionType": "CALL", "contracts": 1 },
    { "symbol": "IXQZ", "expirationDate": "2026-11-20", "strike": "9",  "optionType": "CALL", "contracts": 1 }
  ]
}
```

| 字段 | 类型 | 必填 | 规则 |
|------|------|------|------|
| `items` | array | 是 | 勾选的持仓，1–500 项。顺序不限，后端按下面的"处理顺序"重排。缺失、`null` 或 `[]`：`details` 为 `{"field": "items", "message": "请至少选择一个持仓"}`。超过 500 项：`{"field": "items", "message": "一次最多作废 500 个持仓"}` |
| `items[i].symbol` / `expirationDate` / `strike` / `optionType` / `contracts` | | 是 | 与 FEAT-012 的单个作废请求**完全相同**（合约规则同 FEAT-001；`contracts` 为持仓的 `expiryContracts`，原样传回，不让用户填写）。错误的 `field` 带下标，如 `items[2].strike`，文案同 FEAT-012 / FEAT-001 |
| 同一合约出现两次 | — | — | 按规范化之后的合约判断：标的规范化后相同、行权价按数值相等（`"45"` 与 `"45.00"` 视为同一个）、到期日和 C/P 相同。第二次及以后出现的每一项都报一条：`{"field": "items[3]", "message": "同一合约（XYZ 2026-11-20 45 P）不能出现两次"}` |
| 其它字段 | — | — | 顶层或每一项多带的字段（`note`、`tradedAtLocal`、`netAmount`、`multiplier`、`action` 等）一律忽略，同 FEAT-012 |

##### 检查与处理
1. **请求校验**（字段规则、空列表、超过上限、重复合约）：只要有一项不通过，就返回 400 `VALIDATION_ERROR`，`details` 列出所有问题（按 `field` 排序），什么都不保存，也不做下面的业务检查。JSON 无法解析、枚举 / 数字 / 日期无法转换时返回 400 `BAD_REQUEST`（同 FEAT-001）。
2. **处理顺序**（Q116，与持仓页的 Q35 顺序一致）：到期日升序 → 标的（规范化后，按字母升序） → 行权价数值升序 → Call 在 Put 前。处理顺序与请求中 `items` 的顺序无关。
3. **业务检查**：按处理顺序，对每一项做 FEAT-012 的检查 2–5（时间不早于 17:30 `EXPIRY_TOO_EARLY`、有持仓 `NO_POSITION_TO_EXPIRE`、张数一致 `POSITION_CHANGED`、没有旧的 16:00 之后交易 `EXPIRY_BLOCKED_BY_LATE_TRADE`）。判断口径、错误码和中文原文都与单个作废相同，每项最多报一个原因（第一个不通过的检查）。各项是不同的合约，检查结果互不影响。**遇到失败不停下**，所有项都检查完后再决定。
4. 有任何一项失败 → 422 `BATCH_EXPIRATION_REJECTED`，返回**所有**失败项（格式见下），什么都不保存。
5. 全部通过 → 在**同一个事务**中按处理顺序逐项保存 `EXP` 记录（和需要的 `OPTION_EXPIRE` 流水）。记录 id 按处理顺序递增，所以同一到期日的多条作废时间都是 cutoff（16:00），按 `(时间, id)` 重放时顺序就是处理顺序。然后对当前账户做一次 Q19 现金重放（FEAT-012 检查 6；作废只会释放冻结，实际不会失败）。重放失败时整批回滚，返回 422 `CASH_INSUFFICIENT`（同 FEAT-007，通用格式，不带 `failures`）。
6. 并发：与单个作废使用同一个账户级写锁，整批在锁内完成。对话框打开期间被其它操作改变的持仓，在第 3 步被 `POSITION_CHANGED` 或 `NO_POSITION_TO_EXPIRE` 拒绝。

##### 响应 `201 Created`
```json
{
  "count": 4,
  "realizedPnlTotal": "285.00",
  "frozenChangeTotal": "-13200.00",
  "expirations": [
    {
      "id": 9, "symbol": "IXQZ", "expirationDate": "2026-11-20", "strike": "9", "optionType": "CALL",
      "action": "EXP", "contracts": 1, "multiplier": 100, "netAmount": "0.00",
      "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
      "cashChange": "0.00", "frozenChange": "0.00", "contractLabel": "IXQZ 2026-11-20 9 C",
      "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
      "coveredLots": [
        { "tradeId": 6, "action": "STO", "tradedAt": "2026-11-05T15:00:00Z", "tradedAtLocal": "2026-11-05T10:00:00",
          "contracts": 1, "netAmount": "25.00", "frozen": "0.00" }
      ],
      "realizedPnl": "25.00", "hasAuditLog": false, "expiredDirection": "SHORT"
    },
    {
      "id": 10, "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "30", "optionType": "CALL",
      "action": "EXP", "contracts": 1, "multiplier": 100, "netAmount": "0.00",
      "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
      "cashChange": "0.00", "frozenChange": "0.00", "contractLabel": "QRTV 2026-11-20 30 C",
      "note": null, "ledgerEntryId": null, "createdAt": "2026-11-23T15:00:00Z",
      "coveredLots": [
        { "tradeId": 5, "action": "BTO", "tradedAt": "2026-11-04T15:00:00Z", "tradedAtLocal": "2026-11-04T10:00:00",
          "contracts": 1, "netAmount": "-60.00", "frozen": "0.00" }
      ],
      "realizedPnl": "-60.00", "hasAuditLog": false, "expiredDirection": "LONG"
    },
    {
      "id": 11, "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "42", "optionType": "PUT",
      "action": "EXP", "contracts": 1, "multiplier": 100, "netAmount": "0.00",
      "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
      "cashChange": "0.00", "frozenChange": "-4200.00", "contractLabel": "XYZ 2026-11-20 42 P",
      "note": null, "ledgerEntryId": 12, "createdAt": "2026-11-23T15:00:00Z",
      "coveredLots": [
        { "tradeId": 4, "action": "STO", "tradedAt": "2026-11-03T16:00:00Z", "tradedAtLocal": "2026-11-03T11:00:00",
          "contracts": 1, "netAmount": "80.00", "frozen": "4200.00" }
      ],
      "realizedPnl": "80.00", "hasAuditLog": false, "expiredDirection": "SHORT"
    },
    {
      "id": 12, "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "45", "optionType": "PUT",
      "action": "EXP", "contracts": 2, "multiplier": 100, "netAmount": "0.00",
      "tradedAt": "2026-11-20T21:00:00Z", "tradedAtLocal": "2026-11-20T16:00:00",
      "cashChange": "0.00", "frozenChange": "-9000.00", "contractLabel": "XYZ 2026-11-20 45 P",
      "note": null, "ledgerEntryId": 13, "createdAt": "2026-11-23T15:00:00Z",
      "coveredLots": [
        { "tradeId": 3, "action": "STO", "tradedAt": "2026-11-03T15:00:00Z", "tradedAtLocal": "2026-11-03T10:00:00",
          "contracts": 2, "netAmount": "240.00", "frozen": "9000.00" }
      ],
      "realizedPnl": "240.00", "hasAuditLog": false, "expiredDirection": "SHORT"
    }
  ]
}
```
（示例中的 id 只作说明，实际取值以库中为准；只保证 `expirations` 中的 id 按处理顺序递增。`ledgerEntryId` 与记录 id 不共用序列。）

| 字段 | 类型 | 说明 |
|------|------|------|
| `count` | number | 作废的持仓个数 = `items` 的项数 |
| `realizedPnlTotal` | string | 各项 `realizedPnl` 之和，scale 2（空头为正、多头为负） |
| `frozenChangeTotal` | string | 各项 `frozenChange` 之和，scale 2，≤ 0（释放冻结为负；没有释放时 `"0.00"`） |
| `expirations` | array | 每项一条作废记录，**按处理顺序**排列。每条的表示与 `POST /api/option-trades/expirations` 的 201 响应完全相同（即 `GET /api/option-trades/{id}` 的交易表示，含 `coveredLots`、`realizedPnl`、`frozenChange`、`expiredDirection`、`tradedAt` / `tradedAtLocal`） |

前端用 `expirations[]` 的 `symbol` / `expirationDate` / `strike` / `optionType`（或 `contractLabel`）对应到确认框的行，不要依赖下标。

每条记录产生的流水与单个作废相同（仅空头 put：`sourceType` `OPTION_EXPIRE`，`description` 如 `到期作废 2 张 XYZ 2026-11-20 45 P`）。

##### 错误

| code | HTTP | 何时 / `message` |
|------|------|------|
| `VALIDATION_ERROR` | 400 | 请求校验（见上表），`details[].field` 如 `items`、`items[0].contracts`、`items[3]` |
| `BAD_REQUEST` | 400 | 同 FEAT-001 |
| `BATCH_EXPIRATION_REJECTED` | 422 | 至少一项没有通过 FEAT-012 检查 2–5。`message`：`有 <n> 个持仓不能作废，整批未保存`。`details` 为 `[]`；失败项放在新增的顶层字段 `failures` 中（见下） |
| `CASH_INSUFFICIENT` | 422 | 同 FEAT-007（实际不会出现），没有 `failures` |

**`BATCH_EXPIRATION_REJECTED` 的 `failures`**：统一错误格式的扩展字段，**只有这个 code 才有**，其它错误都没有这个字段。全部字段与通用格式相同（`code`、`message`、`status`、`path`、`timestamp`、`details`），另外加 `failures`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `failures` | array | 每个失败项一条，至少 1 条，**按处理顺序**排列 |
| `failures[].index` | number | 该项在请求 `items` 中的下标（从 0 开始） |
| `failures[].symbol` / `expirationDate` / `strike` / `optionType` | | 该项的合约（规范化之后：标的大写，行权价去掉多余的 0，同持仓的表示） |
| `failures[].contractLabel` | string | 如 `"XYZ 2026-11-20 42 P"` |
| `failures[].code` | string | FEAT-012 的错误码：`EXPIRY_TOO_EARLY` / `NO_POSITION_TO_EXPIRE` / `POSITION_CHANGED` / `EXPIRY_BLOCKED_BY_LATE_TRADE` |
| `failures[].message` | string | 与单个作废对同一项返回的 `message` **一字不差**（中文，可直接显示在该行旁边） |

示例（验收 5：QRTV 有旧的 16:10 STC，XYZ 42 P 传了 2 张）：
```json
{
  "code": "BATCH_EXPIRATION_REJECTED",
  "message": "有 2 个持仓不能作废，整批未保存",
  "status": 422,
  "path": "/api/option-trades/expirations/batch",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [],
  "failures": [
    {
      "index": 2, "symbol": "QRTV", "expirationDate": "2026-11-20", "strike": "30", "optionType": "CALL",
      "contractLabel": "QRTV 2026-11-20 30 C",
      "code": "EXPIRY_BLOCKED_BY_LATE_TRADE",
      "message": "2026-11-20 16:10 的 STC 1 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它"
    },
    {
      "index": 1, "symbol": "XYZ", "expirationDate": "2026-11-20", "strike": "42", "optionType": "PUT",
      "contractLabel": "XYZ 2026-11-20 42 P",
      "code": "POSITION_CHANGED",
      "message": "持仓已变化（当前 1 张），请刷新后重试"
    }
  ]
}
```

示例（400，空列表和重复合约）：
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "path": "/api/option-trades/expirations/batch",
  "timestamp": "2026-11-23T15:00:00Z",
  "details": [ { "field": "items[1]", "message": "同一合约（XYZ 2026-11-20 45 P）不能出现两次" } ]
}
```

#### `GET /api/option-positions`
**没有新字段。** 批量作废使用 FEAT-012 已有的字段：
- 哪些行可以批量作废、按钮上的 N：`positions[].expirable === true` 的行（与单行"作废"按钮的口径完全相同）。到期日 16:00–17:30 之间 `expiryPending` 为 `true`、`expirable` 为 `false`，因此不能选。
- 请求的 `contracts`：`positions[].expiryContracts`。
- 确认框的"张数""开仓净额合计""将释放的冻结"：`expiryContracts`、`openingNetTotal`、`frozenCash`（同 FEAT-012 的确认框）。
- 确认框的行顺序：持仓接口已按 Q35 排序，与处理顺序一致。

#### 已知限制
- FEAT-012 的两条已知限制同样适用于每一项（`expiryContracts` 为 0 时返回 `NO_POSITION_TO_EXPIRE`；有旧的 16:00 之后交易时必须传 `expiryContracts`）。
- 每项只报第一个不通过的检查（同单个作废）。改正后重试时，同一项可能报出下一个原因。
