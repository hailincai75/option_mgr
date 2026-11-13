# 列表行的 `hasAuditLog`（"历史"链接只在有审计记录时显示）

**新增字段，兼容变更**：没有新接口、没有删改字段；不认识该字段的客户端可以忽略它。数据库迁移 `V6__has_audit_log.sql` 只给 `option_trade`、`cash_transfer` 加了一列（见下），不影响任何已有字段的值。

前端在交易、已平仓、现金流水的行上显示"历史"链接（打开 `/audit?objectType=…&objectId=…`）。新增交易、入金/出金不写审计（Q42），所以多数行点进去是空的。后端在这些行上返回布尔字段 **`hasAuditLog`**：该行"历史"链接所指的对象（交易或入金/出金）**被修改过**时为 `true`，否则 `false`。

这是存在交易/入金出金记录上的标记（`has_audit_log` 列），不是读列表时去查审计表：
- 新增 → `false`。
- 修改（`PUT`）成功 → 被修改的那一条置为 `true`，与写审计记录在同一事务里。之后一直是 `true`（再改也不变）。
- 因修改而**连带改正**的其它交易（审计 `sideEffects` 里列出的，例如平仓的冻结变动）**不**置为 `true`——它们自己没有审计记录。
- 修改被拒绝（4xx，含 `NO_CHANGES`）→ 整个事务回滚，标记不变。
- 删除 → 行已不存在，无需处理。账户的审计（`ACCOUNT`）不涉及此字段。
- 升级到 V6 时回填：已有至少一条审计记录（`objectType` 相同：`OPTION_TRADE` / `CASH_TRANSFER`，`objectId` = 该行 id，且同一账户）的行置为 `true`，所以升级前就修改过的记录仍显示"历史"链接。

因此该值与"当前账户的审计记录里有该对象的记录"一致（审计记录只能由修改/删除写入，且不可删改，Q12）。

| 接口 | 行 | `hasAuditLog` 指向的对象 |
|------|----|------------------------|
| `GET /api/option-trades` | `items[]` | `OPTION_TRADE` + `items[].id` |
| `GET /api/option-trades/{id}`、`POST /api/option-trades`、`POST /api/option-trades/closes`、`PUT /api/option-trades/{id}` | 响应体（交易表示与列表项相同） | `OPTION_TRADE` + `id`（`POST` 恒为 `false`；`PUT` 成功恒为 `true`） |
| `GET /api/closed-trades` | `items[]` | `type: "OPTION"`：`OPTION_TRADE` + `items[].id`（**平仓交易**；被平的开仓批次的审计不算）；`type: "STOCK"`（FEAT-040）：`STOCK_TRADE` + `items[].id`（**卖出记录**；被卖出的买入 / 转入的审计不算） |
| `GET /api/cash/ledger` | `items[]` 中 `sourceType` 为 `DEPOSIT` / `WITHDRAWAL` 的 | `CASH_TRANSFER` + `items[].sourceId` |
| `GET /api/cash/ledger` | `items[]` 中 `sourceType` 为 `OPTION_OPEN` / `OPTION_CLOSE` / `OPTION_EXPIRE`（FEAT-012）/ `STOCK_BUY` / `STOCK_SELL`（FEAT-011）/ `OPTION_ASSIGN`（FEAT-013）的 | 没有"历史"链接，恒为 `false` |
| `GET /api/stock-trades`、`GET /api/stock-trades/{id}`、`POST` / `PUT /api/stock-trades…`（FEAT-011） | `items[]` / 响应体 | `STOCK_TRADE` + `id`（`POST` 恒为 `false`；`PUT` 成功恒为 `true`） |

规则：
- 已平仓的行取**平仓交易本身**的标记；只修改过被平的开仓批次不算。现金流水的入金/出金行取对应 `cash_transfer` 的标记。
- 修改与标记在同一事务里写入，所以 `PUT` 成功的响应、以及之后的列表查询都返回 `true`。
- 删除的交易/入金出金不会出现在列表里，所以不需要特殊处理。
- 实现：直接读记录上的标记，构建列表/响应时不查审计表。
- 不涉及：`POST`/`PUT /api/cash/transfers` 的响应、持仓、账户（这些地方没有"历史"链接）。

