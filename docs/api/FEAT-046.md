# FEAT-046 全部账户合并查看（二）：交易列表、股票交易、已平仓、资金流水接受 `ALL`

> **FEAT-047 修订**：下文列为"仍不接受 `ALL`"的 `greeks-summary`、`underlying-summary` 自 FEAT-047 起接受 `ALL`（共 13 个 GET）。见 [FEAT-047.md](FEAT-047.md)。

口径：FEAT-035 的全部（`X-Account-Id: ALL`、`AccountScope`、只读保护、Q53 / Q214–Q217 / Q225 / Q226），以及 Q228–Q232（均按 PM 建议：Q228 A、Q229 A、Q230 A、Q231 B、Q232 A）。本节只写**新增或不同**的部分。示例账户（甲账户 id 1、乙账户 id 2）和标的（QRTV、IXQZ）均为虚构，数字与需求场景 L 一致（今天 2026-11-23 星期一 10:00 美东）。

**兼容性一览**（全部是纯新增：新的可接受请求头值、新的行字段；没有新端点、没有数据库迁移；单账户请求的已有字段、数字、排序、分页、错误都**不变**）

| 接口 | 新增（兼容） | 行为变化 |
|------|-------------|---------|
| `GET /api/option-trades`（FEAT-002） | 接受 `ALL`；`items[]` 新字段 `accountId`、`accountName` | `ALL` 时不再返回 400 `ALL_ACCOUNTS_NOT_SUPPORTED` |
| `GET /api/stock-trades`（FEAT-011） | 接受 `ALL`；`items[]` 新字段 `accountId`、`accountName` | 同上 |
| `GET /api/closed-trades`（FEAT-006 / 040） | 接受 `ALL`；`items[]` 新字段 `accountId`、`accountName` | 同上 |
| `GET /api/cash/ledger`（FEAT-007） | 接受 `ALL`；`items[]` 新字段 `accountId`、`accountName` | 同上；`balanceAfter` 口径不变（该行所在账户自己的余额，Q228） |
| 期权交易表示、股票记录表示（所有返回它们的接口：`GET /{id}`、`POST`、`PUT`、平仓、作废、批量作废、指派、行权……） | 新字段 `accountId`、`accountName`（与列表项保持"完全相同"，FEAT-002 / FEAT-011） | — |
| `GET /api/pnl-summary`（FEAT-008 / 040） | — | 恢复与 `GET /api/closed-trades` 的一致性（`ALL` 下也成立） |

**仍不接受 `ALL`**（400 `ALL_ACCOUNTS_NOT_SUPPORTED`，与 FEAT-035 相同）：`GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}`（见下"按 id 查询"）、`GET /api/audit-log`（永久，Q225）、`GET /api/positions/greeks-summary`（FEAT-047）、`GET /api/positions/underlying-summary`（FEAT-035 需求问题 1）。所有写请求仍 400 `ALL_ACCOUNTS_READ_ONLY`。

---

#### 设计

- **沿用 FEAT-035 的方式**：4 个 controller 方法把参数从 `AccountRef` 改为 `AccountScope`，加进"接受 `ALL`"的列表（FEAT-035 的 7 个 → 11 个）。前端页面查询代码不变，只是不再显示占位（请求头已由统一封装按当前账户加上）。
- **计算不变（Q53）**：每行的数字（`cashChange`、`frozenChange`、`realizedPnl`、`coveredLots`、`soldLots`、`soldCost`、`balanceAfter`、`hasAuditLog`……）都按该行所在账户、用与 `X-Account-Id: <id>` 相同的代码算出，然后才合并。没有跨账户 FIFO、跨账户余额。
- **行上的账户（Q214）**：`accountId`（number）、`accountName`（string，请求时的当前名称）。单账户请求也有值（就是该账户），前端类型不用分两套。已平仓行的 `coveredLots[]` / `soldLots[]`、交易的 `coveredLots[]` 不加账户字段（与所在行同一账户，规则 2）。
- **筛选（规则 4、Q230）**：query 参数、校验、错误码、错误文案、执行顺序全部不变，作用于全部账户的记录。**没有账户筛选参数**。
- **排序（Q229 A）**：各接口原排序不变；原排序完全相同的两行按 `accountId` 升序。
  - 期权交易 id、股票记录 id、流水 id 都是**全系统一个序列**（FEAT-033：id 全局，不按账户重新编号），所以"同一时间后录入的在上"在全部账户下就是按 id 倒序，跨账户也成立；原排序完全相同只可能出现在同一张表的同一个 id，即不会出现。`accountId` 只是为让排序在定义上完整，不会影响实际结果。
  - 已平仓：`tradedAt` 倒序 → 同一时间 `STOCK` 在 `OPTION` 上（Q92）→ 同类 `id` 倒序 → `accountId` 升序。
- **分页（规则 5）**：对**合并后的整个有序列表**分页，`page` / `size` 规则、默认值、超过最后一页返回 `items: []` 都不变；`totalItems` / `totalPages` 为合并后的。不是每个账户各自分页再拼接（见下"分页如何计算"）。
- **合计（Q216）**：已平仓 `filteredCount` / `filteredRealizedPnlTotal` 是符合筛选条件的全部账户全部行（不只是当前页）的笔数与 `realizedPnl` 之和（scale 2 精确相加，不再取整）。
- **流水余额（Q228 A）**：`balanceAfter` 仍是**该行所在账户**按 `(occurredAt, id)` 累计到该行（含）的总现金，与切到该账户时同一行的值完全相同。合并后的列表里相邻两行的余额可能属于不同账户，不是连续余额。全部账户的总现金看 `GET /api/cash/summary`（`ALL`，FEAT-035）。
- **按 id 查询（`GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}`）仍不接受 `ALL`**：前端只在修改对话框用它们（已核对：页面上没有其它调用），全部账户下修改入口已隐藏（Q217）；"历史"入口按 Q231 B 先切换到该行账户再打开审计页，不需要按 id 查询。保持 `ALL_ACCOUNTS_NOT_SUPPORTED` 可防止误用。
- **"历史"（Q231 B）**：行上的 `hasAuditLog` 不变（has-audit-log.md：记录上的标记）。前端只在 `hasAuditLog = true` 的行显示"历史"，点击时先把当前账户切换为该行 `accountId`，再打开 `/audit?objectType=…&objectId=…`；审计请求用 `X-Account-Id: <accountId>`（`GET /api/audit-log` 不接受 `ALL`）。对象与 has-audit-log.md 的表相同：期权交易 / 已平仓期权行 `OPTION_TRADE` + `id`；股票记录 / 已平仓股票行 `STOCK_TRADE` + `id`；流水的入金 / 出金行 `CASH_TRANSFER` + `sourceId`。
- **写入口（Q217、Q232 A）**：后端不变（写请求带 `ALL` 一律 `ALL_ACCOUNTS_READ_ONLY`，什么都不改）。行上的 `ledgerEntryId`、`sourceId` 等仍返回，前端在全部账户下不显示修改 / 删除 / `在交易列表中修改` / `在股票交易中修改`。
- **新增 / 空账户**：`ALL` = 请求时存在的全部账户；空账户不贡献任何行，4 个列表的条数、合计都不变（验收 10）。只有一个账户时 `ALL` 仍有效（结果即那个账户）。

---

#### 分页如何计算（实现要求）

| 接口 | 单账户现状 | `ALL` 的做法 |
|------|-----------|-------------|
| `GET /api/option-trades` | JPA Specification + `PageRequest`（数据库分页，排序 `tradedAt desc, id desc`） | **同一个查询**，条件 `account_id = :id` 换成 `account_id IN (:ids)`（`AccountScope.ids()`），排序追加 `accountId asc`，仍由数据库对合并结果分页并给出 `totalElements`。取到本页行后，按账户分组调用现有 `views(accountId, rows)`（每账户的指派批次、`hasAuditLog` 等按该账户取），再按本页原顺序放回，补上 `accountId` / `accountName` |
| `GET /api/stock-trades` | 同上（`tradedAt desc, id desc`） | 同上：`account_id IN (:ids)` 的单个分页查询；本页行按账户分组调用 `views(accountId, rows)`（FIFO 重放仍按"账户 + 标的"，`soldLots` 不跨账户） |
| `GET /api/closed-trades` | 内存中取全部符合条件的行（期权平仓 + 股票卖出，按账户 FIFO 重放）→ 排序 → 求合计 → 截取本页 | 对 scope 中每个账户调用现有 `all(accountId, filter)`，拼接后用原比较器 + `accountId` 稳定排序，**再**对合并结果求 `filteredCount` / `filteredRealizedPnlTotal`、截取本页 |
| `GET /api/cash/ledger` | 内存中按 `(occurredAt, id)` 重放该账户全部流水得到 `balanceAfter` → 倒序 → 截取本页 | 每个账户**各自**重放得到带余额的行（Q228 A），拼接后按 `occurredAt desc, id desc, accountId asc` 排序，再截取本页；本页入金 / 出金行的 `hasAuditLog` 按账户分组取标记 |

结论：分页始终基于"全部账户、全部符合条件的行"的一个有序序列：`totalItems` = 各账户符合条件条数之和，`totalPages = ceil(totalItems / size)`（0 条时为 0），第 `page` 页 = 该序列第 `(page−1)×size` 起的 `size` 条。因为期权交易、股票记录用数据库分页、另两个本来就在内存里全量排序，跨页不会重复或遗漏。不做专门性能优化（Q226）。

---

#### `GET /api/option-trades`（`ALL`）

参数、校验、错误同 FEAT-002（含 FEAT-012 起的 `EXP` 等）。每项为期权交易表示（字段不变）+ `accountId`、`accountName`。

场景 L 中期权交易 id（从空库录入）：#4→1、#5→2、#6→3、#7→4、#8→5、#9→6、#10→7、#11→8；流水 id：#1→1、#2→2、#4→3、#5→4、#6→5、#7→6、#8→7、#9→8、#10→9、#11→10、#12→11、#13→12；股票记录 id：#3→1、#12→2、#13→3。

示例（验收 10：每页 5 条第 2 页）：
```
GET /api/option-trades?page=2&size=5
X-Account-Id: ALL
```
```json
{
  "items": [
    {
      "accountId": 1, "accountName": "甲账户",
      "id": 3, "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "48", "optionType": "PUT",
      "action": "STO", "contracts": 1, "multiplier": 100, "netAmount": "100.00",
      "tradedAt": "2026-11-02T15:00:00Z", "tradedAtLocal": "2026-11-02T10:00:00",
      "cashChange": "100.00", "frozenChange": "4800.00",
      "contractLabel": "QRTV 2026-12-18 48 P", "note": null, "ledgerEntryId": 5,
      "coveredLots": null, "realizedPnl": null, "hasAuditLog": false, "…": "…"
    },
    {
      "accountId": 2, "accountName": "乙账户",
      "id": 2, "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "55", "optionType": "CALL",
      "action": "STC", "contracts": 1, "multiplier": 100, "netAmount": "80.00",
      "tradedAt": "2026-10-20T14:00:00Z", "tradedAtLocal": "2026-10-20T10:00:00",
      "cashChange": "80.00", "frozenChange": "0.00",
      "contractLabel": "QRTV 2026-12-18 55 C", "note": null, "ledgerEntryId": 4,
      "coveredLots": [
        { "tradeId": 1, "action": "BTO", "tradedAt": "2026-10-15T14:00:00Z", "tradedAtLocal": "2026-10-15T10:00:00",
          "contracts": 1, "netAmount": "-50.00", "frozen": "0.00" }
      ],
      "realizedPnl": "30.00", "hasAuditLog": false, "…": "…"
    },
    {
      "accountId": 2, "accountName": "乙账户",
      "id": 1, "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "55", "optionType": "CALL",
      "action": "BTO", "contracts": 1, "multiplier": 100, "netAmount": "50.00",
      "tradedAt": "2026-10-15T14:00:00Z", "tradedAtLocal": "2026-10-15T10:00:00",
      "cashChange": "-50.00", "frozenChange": "0.00",
      "contractLabel": "QRTV 2026-12-18 55 C", "note": null, "ledgerEntryId": 3,
      "coveredLots": null, "realizedPnl": null, "hasAuditLog": false, "…": "…"
    }
  ],
  "page": 2,
  "size": 5,
  "totalItems": 8,
  "totalPages": 2
}
```
（`"…"` 表示其它字段同单账户，如 `createdAt` 及 FEAT-013 / 014 的指派、行权字段。）第 1 页（`size=5`）依次为 id 8（#11 甲）、7（#10 乙，`hasAuditLog: true`）、6（#9 甲）、5（#8 甲）、4（#7 乙）。

单账户 `X-Account-Id: 1`：只有 id 8、6、5、3，`totalItems` 4，每行 `accountId: 1`、`accountName: "甲账户"`，其它不变。

---

#### `GET /api/stock-trades`（`ALL`）

参数、校验、错误同 FEAT-011（`symbol`、多值 `type`、`startDate` / `endDate`、`page` / `size`）。每项为股票记录表示 + `accountId`、`accountName`。

示例（验收 3）：
```json
{
  "items": [
    {
      "accountId": 2, "accountName": "乙账户",
      "id": 3, "type": "SELL", "symbol": "QRTV", "shares": 50, "amount": "2450.00", "cashChange": "2450.00",
      "pricePerShare": "49.0000", "tradedAt": "2026-11-18T15:00:00Z", "tradedAtLocal": "2026-11-18T10:00:00",
      "tradeDate": null, "note": null, "ledgerEntryId": 12, "hasAuditLog": false,
      "soldCost": "2400.00", "realizedPnl": "50.00",
      "soldLots": [
        { "tradeId": 2, "type": "BUY", "tradedAt": "2026-11-17T15:00:00Z", "tradedAtLocal": "2026-11-17T10:00:00",
          "tradeDate": null, "shares": 50, "cost": "2400.00" }
      ],
      "…": "…"
    },
    {
      "accountId": 2, "accountName": "乙账户",
      "id": 2, "type": "BUY", "symbol": "QRTV", "shares": 100, "amount": "4800.00", "cashChange": "-4800.00",
      "pricePerShare": "48.0000", "tradedAt": "2026-11-17T15:00:00Z", "tradedAtLocal": "2026-11-17T10:00:00",
      "tradeDate": null, "note": null, "ledgerEntryId": 11, "hasAuditLog": false,
      "soldCost": null, "realizedPnl": null, "soldLots": null, "…": "…"
    },
    {
      "accountId": 1, "accountName": "甲账户",
      "id": 1, "type": "TRANSFER_IN", "symbol": "IXQZ", "shares": 100, "amount": "5880.00", "cashChange": "0.00",
      "pricePerShare": "58.8000", "tradedAt": "2026-10-01T04:00:00Z", "tradedAtLocal": "2026-10-01T00:00:00",
      "tradeDate": "2026-10-01", "note": null, "ledgerEntryId": null, "hasAuditLog": false,
      "soldCost": null, "realizedPnl": null, "soldLots": null, "…": "…"
    }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 3,
  "totalPages": 1
}
```
`?symbol=IXQZ` → 只有 id 1（`totalItems` 1）；`?type=SELL` → 只有 id 3。

---

#### `GET /api/closed-trades`（`ALL`）

参数（含 FEAT-040 的 `type`、Q90 期权专用条件）、校验、错误同 FEAT-006 / 040。每项 + `accountId`、`accountName`；行仍由 `type` + `id` 唯一确定（两类 id 各自全局唯一，不会跨账户重复）。`filteredCount` / `filteredRealizedPnlTotal` 为全部账户符合条件的全部行之和。

示例（验收 4，不带筛选）：
```json
{
  "items": [
    {
      "accountId": 2, "accountName": "乙账户",
      "type": "STOCK", "id": 3, "tradedAt": "2026-11-18T15:00:00Z", "tradedAtLocal": "2026-11-18T10:00:00",
      "symbol": "QRTV", "expirationDate": null, "strike": null, "optionType": null, "contractLabel": null,
      "action": "SELL", "closedDirection": null, "contracts": null, "multiplier": null, "shares": 50,
      "openingNetTotal": "-2400.00", "openingNetPerContract": null, "costPerShare": "48.0000",
      "closeNetAmount": "2450.00", "closeNetPerContract": null, "pricePerShare": "49.0000",
      "realizedPnl": "50.00", "note": null, "coveredLots": null,
      "soldLots": [
        { "tradeId": 2, "type": "BUY", "tradedAt": "2026-11-17T15:00:00Z", "tradedAtLocal": "2026-11-17T10:00:00",
          "tradeDate": null, "shares": 50, "netAmount": "-2400.00" }
      ],
      "hasAuditLog": false, "…": "…"
    },
    {
      "accountId": 1, "accountName": "甲账户",
      "type": "OPTION", "id": 5, "tradedAt": "2026-11-10T15:00:00Z", "tradedAtLocal": "2026-11-10T10:00:00",
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "48", "optionType": "PUT",
      "contractLabel": "QRTV 2026-12-18 48 P", "action": "BTC", "closedDirection": "SHORT",
      "contracts": 1, "multiplier": 100, "shares": null,
      "openingNetTotal": "100.00", "openingNetPerContract": "100.00", "costPerShare": null,
      "closeNetAmount": "-40.00", "closeNetPerContract": "-40.00", "pricePerShare": null,
      "realizedPnl": "60.00", "note": null,
      "coveredLots": [
        { "tradeId": 3, "action": "STO", "tradedAt": "2026-11-02T15:00:00Z", "tradedAtLocal": "2026-11-02T10:00:00",
          "contracts": 1, "netAmount": "100.00", "frozen": "4800.00" }
      ],
      "soldLots": null, "hasAuditLog": false, "…": "…"
    },
    {
      "accountId": 2, "accountName": "乙账户",
      "type": "OPTION", "id": 2, "tradedAt": "2026-10-20T14:00:00Z", "tradedAtLocal": "2026-10-20T10:00:00",
      "symbol": "QRTV", "expirationDate": "2026-12-18", "strike": "55", "optionType": "CALL",
      "contractLabel": "QRTV 2026-12-18 55 C", "action": "STC", "closedDirection": "LONG",
      "contracts": 1, "multiplier": 100, "shares": null,
      "openingNetTotal": "-50.00", "openingNetPerContract": "-50.00", "costPerShare": null,
      "closeNetAmount": "80.00", "closeNetPerContract": "80.00", "pricePerShare": null,
      "realizedPnl": "30.00", "note": null,
      "coveredLots": [
        { "tradeId": 1, "action": "BTO", "tradedAt": "2026-10-15T14:00:00Z", "tradedAtLocal": "2026-10-15T10:00:00",
          "contracts": 1, "netAmount": "-50.00", "frozen": "0.00" }
      ],
      "soldLots": null, "hasAuditLog": false, "…": "…"
    }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 3,
  "totalPages": 1,
  "filteredCount": 3,
  "filteredRealizedPnlTotal": "140.00"
}
```
- `?type=STOCK` → `filteredCount` 1、`"50.00"`；`?type=OPTION` → 2、`"90.00"`；`?startDate=2026-11-01&endDate=2026-11-30` → STOCK 3、OPTION 5，2、`"110.00"`；`?symbol=QRTV` → 3、`"140.00"`；`?startDate=2026-10-01&endDate=2026-10-31` → 只有 OPTION 2，1、`"30.00"`。
- 单账户 `X-Account-Id: 1` → 只有 OPTION 5，1、`"60.00"`。

**一致性（恢复 FEAT-008 / 040，规则 6）**：对同一 scope（单账户或 `ALL`）与同一日期范围，`GET /api/pnl-summary` 的 `closedCount` / `realizedPnlTotal` = `GET /api/closed-trades`（不带 `type` 及其它条件）的 `filteredCount` / `filteredRealizedPnlTotal`；`option` / `stock` 拆分 = 分别带 `type=OPTION` / `type=STOCK` 的合计；`bySymbol` 某行 = 带 `symbol=<该标的>` 的合计。两者都是各账户按自己的规则算出后相加，所以在 `ALL` 下同样成立。FEAT-035 中"`ALL` 下暂不适用"的说明作废，后端测试恢复这项交叉核对。

---

#### `GET /api/cash/ledger`（`ALL`）

参数（`page`、`size`）、校验、错误同 FEAT-007。每项 + `accountId`、`accountName`；`balanceAfter` 为该行所在账户自己的累计总现金（Q228 A）。`totalItems` = 各账户流水条数之和。

示例（验收 7，节选第 1–5 行与最后 2 行；`size=20`，共 12 条）：
```json
{
  "items": [
    { "accountId": 2, "accountName": "乙账户", "id": 12, "occurredAt": "2026-11-18T15:00:00Z",
      "sourceType": "STOCK_SELL", "sourceId": 3, "amount": "2450.00", "balanceAfter": "17860.00",
      "frozenChange": "0.00", "description": "…", "remark": null, "hasAuditLog": false },
    { "accountId": 2, "accountName": "乙账户", "id": 11, "occurredAt": "2026-11-17T15:00:00Z",
      "sourceType": "STOCK_BUY", "sourceId": 2, "amount": "-4800.00", "balanceAfter": "15410.00",
      "frozenChange": "0.00", "description": "…", "remark": null, "hasAuditLog": false },
    { "accountId": 1, "accountName": "甲账户", "id": 10, "occurredAt": "2026-11-16T15:30:00Z",
      "sourceType": "OPTION_OPEN", "sourceId": 8, "amount": "84.35", "balanceAfter": "50263.05",
      "frozenChange": "0.00", "description": "STO 1 张 IXQZ 2026-11-27 65 C", "remark": null, "hasAuditLog": false },
    { "accountId": 2, "accountName": "乙账户", "id": 9, "occurredAt": "2026-11-16T15:00:00Z",
      "sourceType": "OPTION_OPEN", "sourceId": 7, "amount": "120.00", "balanceAfter": "20210.00",
      "frozenChange": "5000.00", "description": "STO 1 张 QRTV 2026-12-18 50 P", "remark": "同价位", "hasAuditLog": false },
    { "accountId": 1, "accountName": "甲账户", "id": 8, "occurredAt": "2026-11-16T15:00:00Z",
      "sourceType": "OPTION_OPEN", "sourceId": 6, "amount": "118.70", "balanceAfter": "50178.70",
      "frozenChange": "5000.00", "description": "STO 1 张 QRTV 2026-12-18 50 P", "remark": null, "hasAuditLog": false },
    { "…": "id 7、6、5、4、3（#8、#7、#6、#5、#4）" },
    { "accountId": 2, "accountName": "乙账户", "id": 2, "occurredAt": "2026-10-01T13:30:00Z",
      "sourceType": "DEPOSIT", "sourceId": 2, "amount": "20000.00", "balanceAfter": "20000.00",
      "frozenChange": "0.00", "description": null, "remark": null, "hasAuditLog": false },
    { "accountId": 1, "accountName": "甲账户", "id": 1, "occurredAt": "2026-10-01T13:30:00Z",
      "sourceType": "DEPOSIT", "sourceId": 1, "amount": "50000.00", "balanceAfter": "50000.00",
      "frozenChange": "0.00", "description": null, "remark": null, "hasAuditLog": false }
  ],
  "page": 1,
  "size": 20,
  "totalItems": 12,
  "totalPages": 1
}
```
（`description` 的股票流水格式见 FEAT-011；期权流水行的 `remark` = 交易备注，#10 改备注后为 `"同价位"`；期权流水行 `hasAuditLog` 恒为 `false`，"历史"在交易列表的 #10 行上。）其余中间行余额：#8 甲 `50060.00`、#7 乙 `20090.00`、#6 甲 `50100.00`、#5 乙 `20030.00`、#4 乙 `19950.00`。

单账户 `X-Account-Id: 1`：5 条（id 10、8、7、5、1），第 1 行 `balanceAfter` `"50263.05"`，与 `ALL` 下这些行的余额相同。

---

#### 错误

与单账户完全相同（各接口的 `VALIDATION_ERROR` / `BAD_REQUEST`），另有 FEAT-035 的：

| 情况 | HTTP | code |
|------|------|------|
| `GET /api/option-trades/{id}`、`GET /api/stock-trades/{id}`、`GET /api/audit-log`、`greeks-summary`、`underlying-summary` 带 `ALL` | 400 | `ALL_ACCOUNTS_NOT_SUPPORTED` |
| 任何写请求带 `ALL`（如 `PUT /api/option-trades/7`、`DELETE /api/stock-trades/3`） | 400 | `ALL_ACCOUNTS_READ_ONLY` |

请求头检查在参数校验之前（FEAT-035）：`ALL` + 不支持的接口直接报上表错误；`ALL` + 本需求的 4 个接口 + 不合法参数 → 与单账户相同的参数错误。

---

#### 验收标准的数字从哪里来

| 验收 | 页面上的数字 | 来源 |
|------|------------|------|
| 1 | `共 8 条`、行顺序、账户、净额 / 冻结 | `GET /api/option-trades`（`ALL`）：`totalItems`、行顺序、`accountName`、`cashChange`、`frozenChange`；无写按钮是前端（Q217） |
| 2 | 标的 / 合约 / 方向筛选后的行与条数；切到乙账户 | 同一接口带 `symbol` / `symbol+expirationDate+strike+optionType` / `action=STO`；切换后请求头 `2`，参数不变（Q55） |
| 3 | 股票交易 3 条、展开批次、筛选 | `GET /api/stock-trades`（`ALL`）的 `items[]`（`cashChange`、`soldLots[].shares` / `cost`、转入 `amount`）、`?symbol=IXQZ`、`?type=SELL` |
| 4 | `共 3 笔平仓`、`+140.00`、行顺序、类型 / 日期筛选 | `GET /api/closed-trades`（`ALL`）的 `filteredCount`、`filteredRealizedPnlTotal`、`items[]`；`type=STOCK` / `type=OPTION` / `startDate`+`endDate` |
| 5 | 盈亏汇总与已平仓一致；点 QRTV / 2026-10 | `GET /api/pnl-summary`（`ALL`）的总计、`bySymbol`、`byMonth`；跳转后 `GET /api/closed-trades?symbol=QRTV`、`?startDate=2026-10-01&endDate=2026-10-31`（`ALL`） |
| 6 | 首页本月 → 已平仓；已平仓合约 → 交易列表；持仓股票 → 股票交易 | `GET /api/dashboard`（`ALL`）`currentMonthRealizedPnl` 与 `asOfDate`；`GET /api/closed-trades?startDate=2026-11-01&endDate=2026-11-23`；`GET /api/option-trades?symbol=QRTV&expirationDate=2026-12-18&strike=48&optionType=PUT`；`GET /api/stock-trades?symbol=IXQZ`（都是 `ALL`） |
| 7 | 流水 12 条、各行余额、汇总 | `GET /api/cash/ledger`（`ALL`）的 `totalItems`、`items[].accountName` / `amount` / `balanceAfter`；汇总 `GET /api/cash/summary`（`ALL`，FEAT-035） |
| 8 | 只有 #10 有 `历史`；点后切到乙账户看审计 | `GET /api/option-trades`（`ALL`）的 `hasAuditLog`、`accountId`；`GET /api/audit-log?objectType=OPTION_TRADE&objectId=7`（`X-Account-Id: 2`） |
| 9 | 切回甲账户 | 4 个接口 `X-Account-Id: 1`：交易 4 条、已平仓 1 笔 `60.00`、流水 5 条首行 `50263.05` |
| 10 | 后端：数字、分页、拒绝写、审计不支持、空账户 | 上面 4 个接口；`?page=2&size=5` → id 3、2、1，`totalItems` 8、`totalPages` 2；`PUT /api/option-trades/7`、`DELETE /api/stock-trades/3`（`ALL`）→ 400 `ALL_ACCOUNTS_READ_ONLY` 且数据不变；`GET /api/audit-log`（`ALL`）→ 400 `ALL_ACCOUNTS_NOT_SUPPORTED`；新增账户后重读数字不变 |

**核对（按现有规则重算，全部对得上）**：
- 甲现金：50,000.00 + 100.00 − 40.00 + 118.70 + 84.35 = 50,263.05；冻结 #9 5,000.00（#6 的 4,800.00 已随 #8 释放；#11 有 100 股 IXQZ 覆盖，不冻结）；可用 45,263.05。
- 乙现金：20,000.00 − 50.00 + 80.00 + 60.00 + 120.00 − 4,800.00 + 2,450.00 = 17,860.00；冻结 #7 4,500.00（11-20 已过期未处理，仍冻结，Q34）+ #10 5,000.00 = 9,500.00；可用 8,360.00。#12 买入时可用 10,710.00 ≥ 4,800.00（Q68）。合计 68,123.05 / 14,500.00 / 53,623.05（50,263.05 + 17,860.00；45,263.05 + 8,360.00）。**更正（实现阶段）**：此前这里与需求验收 7 / 场景 L 写的 `70,123.05 / 55,623.05` 是加法错误（多了 2,000.00），两个账户各自的数字无误；已交 PM 改需求，`GET /api/cash/summary`（`ALL`）按现有规则返回 `68123.05` / `14500.00` / `53623.05`。
- 交易列表 8 条：#4–#11（#3、#12、#13 是股票）。顺序按时间倒序，同一时间 id 大的在上：11-16 10:00 #10（id 7）> #9（id 6）；11-02 #7（id 4）> #6（id 3）。每页 5 条：第 2 页 #6、#5、#4。
- 筛选：`qrtv` 7 条；合约 QRTV 12-18 50P：#10、#9；STO：#6、#7、#9、#10、#11 共 5 条；乙 + STO：#10、#7。
- 股票：#13 卖 50 股，从 #12（100 股 4,800.00）扣一部分，成本 4,800.00 × 50 ÷ 100 = 2,400.00，盈亏 +50.00。
- 已平仓：#5 +30.00（10-20）、#8 +60.00（11-10）、#13 +50.00（11-18）；合计 +140.00；期权 +90.00；11 月 +110.00；#7 已过期但未作废，不在已平仓。
- 流水 12 条（13 条记录减去不产生流水的转入 #3）。同一时间：11-16 10:00 #10（流水 id 9）在 #9（id 8）上；10-01 09:30 #2（id 2）在 #1（id 1）上。余额：#10 乙 20,000 − 50 + 80 + 60 + 120 = 20,210.00；#9 甲 50,000 + 100 − 40 + 118.70 = 50,178.70。
- 甲单账户：交易 #11、#9、#8、#6；已平仓 #8 +60.00；流水 #1、#6、#8、#9、#11 共 5 条。

---

#### 需求问题（交给 PM，不影响契约）

1. **验收 2 的几个 When 是否叠加**：`方向只选 STO` 的结果（5 条、含 IXQZ 的 #11）只有在**清空**前面的标的 `qrtv` / 合约筛选后才成立；若叠加在合约筛选上应为 #10、#9（2 条），叠加在 `qrtv` 上应为 4 条。建议 PM 把该步写成"清空筛选后，方向只选 STO"。之后"切到乙账户 → #10、#7"在 STO 单独筛选下正确。
2. 其余验收数字核对无误（见上）。场景 L 中 `#11` 的到期日 2026-11-27 是感恩节次日（提前收盘日，仍是交易日），不影响本需求。

---

#### 实现说明（backend）
- 4 个 controller 方法改用 `AccountScope`；`AccountRefArgumentResolver` 不需要改（`/{id}` 与审计仍用 `AccountRef`，继续拒绝 `ALL`）。
- 期权交易 / 股票记录：`OptionTradeSpecifications.matching` / `StockTradeSpecifications.matching` 增加按账户集合的重载（`account_id IN`）；`LIST_ORDER` 追加 `accountId asc`（对单账户无影响）。视图与表示加 `accountId` / `accountName`（名称来自 `AccountScope.Member`，单账户与写接口从账户服务取）。
- 已平仓、流水：逐账户调用现有单账户方法，拼接、稳定排序（`AccountRows`：原比较器 + `accountId`），再合计 / 分页。
- 盈亏汇总：恢复"`pnl-summary` = `closed-trades` 合计"的交叉测试，单账户与 `ALL` 各一组。
- 测试：每条验收至少一个测试；另测分页跨页不重复不遗漏（`size` 1–3 逐页拼接 = 不分页结果）、空账户不改变数字、`/{id}` 与审计带 `ALL` 仍 400、写接口带 `ALL` 被拒且数据不变。

#### 已知限制
- 4 个列表在 `ALL` 下没有账户筛选（Q230）；只看一个账户用切换器。
- 流水 `balanceAfter` 不是跨账户连续余额（Q228 A），相邻行余额可能属于不同账户。
