# FEAT-033 多账户（**不兼容变更：所有业务接口都要求指明账户**）

> **FEAT-035 修订**：`X-Account-Id` 新取值 `ALL`（全部账户，只读，只被 7 个 GET 接受）；其它接口收到 `ALL` → 400 `ALL_ACCOUNTS_READ_ONLY`（写）/ `ALL_ACCOUNTS_NOT_SUPPORTED`（读，含审计，Q225）。见 [FEAT-035.md](FEAT-035.md)。

> ⚠️ **不兼容变更（FEAT-033）**：从本版本起，下面列出的**全部现有业务接口**都必须带请求头 `X-Account-Id`。不带 → 400 `ACCOUNT_REQUIRED`，什么都不读、不写。路径、请求体、响应体、其它错误都**不变**；前面 FEAT-007 ~ FEAT-008 各小节的说明全部改为"在该账户内"成立。

#### 账户上下文：`X-Account-Id` 请求头
- 值为账户 id（十进制整数），如 `X-Account-Id: 1`。
- **需要**此请求头的接口（账户范围内的读写）：
  - `/api/cash/**`（现金汇总、入金/出金新增/修改/删除、流水）
  - `/api/option-trades/**`（开仓、平仓、按 id 查询、列表、修改、删除）
  - `/api/option-positions`、`/api/closed-trades`、`/api/pnl-summary`、`/api/dashboard`
  - `/api/audit-log`
  - FEAT-011 起：`/api/stock-trades/**`、`/api/stock-positions`
- **不需要**（带了也忽略）：`/api/health`、`/api/accounts/**`、`/api/test-support/**`。
- 选择请求头而不是路径前缀的原因：已有路径、query 参数（地址栏里的筛选链接，Q55 要求不带账户）全部不变；前端只需在统一的请求封装里按"当前账户"加一个头。前端的 TanStack Query key 需要包含账户 id，切换账户时自动按新账户重新查询。
- 错误（在请求体校验之前检查）：

| 情况 | HTTP | code | `details` |
|------|------|------|-----------|
| 没带 `X-Account-Id`（或为空白） | 400 | `ACCOUNT_REQUIRED` | `[{"field": "X-Account-Id", "message": "请指定账户"}]` |
| 不是整数 | 400 | `BAD_REQUEST` | `[{"field": "X-Account-Id", "message": "账户 id 必须是整数"}]` |
| 账户不存在 | 404 | `ACCOUNT_NOT_FOUND` | `[]`，`message`：`Account 99 not found` |

- **账户隔离（Q53、Q54）**：所有计算（现金/冻结/可用、Q18/Q19/Q20、持仓、Q7/Q23/Q21、FIFO 匹配、已实现盈亏、汇总、首页、审计）只在该账户内进行。属于**其它账户**的交易、入金/出金：按 id 查询、修改、删除一律 404 `NOT_FOUND`（与不存在相同，不透露它在别的账户）。记录的账户不能修改。
- 同一合约在不同账户是互不相干的持仓（可以一多一空）。
- id（交易、入金/出金、流水、审计）仍是**全局**递增；同一时间的录入顺序在账户内按 id 比较。
- 现金写操作按账户串行（每个账户一把锁），不同账户之间互不阻塞。

示例：
```
GET /api/cash/summary
X-Account-Id: 2
→ 200 {"totalCash": "0.00", "frozenCash": "0.00", "availableCash": "0.00"}

GET /api/cash/summary
→ 400 {"code": "ACCOUNT_REQUIRED", "message": "Account required", "status": 400, "path": "/api/cash/summary",
       "timestamp": "...", "details": [{"field": "X-Account-Id", "message": "请指定账户"}]}

GET /api/option-trades
X-Account-Id: 99
→ 404 {"code": "ACCOUNT_NOT_FOUND", "message": "Account 99 not found", "status": 404, "path": "/api/option-trades",
       "timestamp": "...", "details": []}
```

#### 账户
| 字段 | 规则 |
|------|------|
| `id` | 系统生成。已有数据所在的"默认账户"为 `1`；空库也有 `1`（Q52） |
| `name` | 必填；去首尾空白后 1–30 个字符（按 Java `String.length()`）；**不区分大小写唯一**（去首尾空白、转小写后比较） |
| `brokerNote` | 可选，去首尾空白后最多 50 个字符；`null`/空白保存为 `null`。自由文字，**不是账号**（Q51） |
| `createdAt` | 服务器时间，ISO-8601 UTC Instant |

#### `GET /api/accounts`
全部账户，按 `id` 升序。响应 `200 OK`：
```json
[
  { "id": 1, "name": "默认账户", "brokerNote": null, "createdAt": "2026-11-12T15:00:00Z" },
  { "id": 2, "name": "小账户", "brokerNote": "券商 B", "createdAt": "2026-11-12T15:05:00Z" }
]
```

#### `GET /api/accounts/{id}`
一个账户，响应体同上一项的元素。不存在 → 404 `ACCOUNT_NOT_FOUND`。

#### `POST /api/accounts`
新增账户。请求体：
```json
{ "name": " 小账户 ", "brokerNote": "券商 B" }
```
响应 `201 Created`：新账户（`name` 已去首尾空白）。新账户从零开始：现金 0.00、没有任何记录。同时在**新账户**的审计里写一条（`operation` = `CREATE`，`objectType` = `ACCOUNT`）。

#### `PUT /api/accounts/{id}`
修改名称、券商备注（整体替换：`brokerNote` 不传 = 清空）。请求体同 `POST`。响应 `200 OK`：修改后的账户。在**该账户**的审计里写一条（`UPDATE`，`ACCOUNT`，改动如 `名称：默认账户 → 主账户`）。内容与原来相同 → 422 `NO_CHANGES`（`没有修改`）。不提供删除（Q58）。

账户接口的错误：

| code | HTTP | 何时 |
|------|------|------|
| `VALIDATION_ERROR` | 400 | `name`：`账户名称不能为空`、`账户名称最多 30 个字符`、`账户名称已存在`（与其它账户不区分大小写重复）；`brokerNote`：`券商备注最多 50 个字符` |
| `ACCOUNT_NOT_FOUND` | 404 | `PUT` / `GET` 的账户不存在 |
| `NO_CHANGES` | 422 | `PUT` 内容与原来相同 |

#### 审计记录的变化
- `objectType` 新增 `ACCOUNT`（账户）；`operation` 新增 `CREATE`（只用于账户新增）。
- 每条审计属于一个账户：交易、入金/出金的审计属于记录所在账户；账户的审计属于该账户本身。`GET /api/audit-log` 只返回 `X-Account-Id` 账户的记录（可用 `objectType=ACCOUNT&objectId=<id>` 筛选）。
- 账户审计的 `before` / `after`：`{"id", "name", "brokerNote"}`；`CREATE` 时 `before` 为 `null`、`changes` 为 `[]`；`UPDATE` 的 `changes` 比较 `name`（名称）、`brokerNote`（券商备注）。`summary` 为 `账户 <名称>`（修改前的名称；新增时为新名称）。

#### 已有数据与升级（迁移 `V5__accounts.sql`）
- 升级时新建账户 `1`"默认账户"（券商备注为空），**全部**已有的入金/出金、流水、交易、审计归入账户 `1`；所有 id、录入顺序、时间、金额、冻结变动、说明、备注、审计内容都不变。在 `X-Account-Id: 1` 下所有接口的结果与升级前完全相同。
- 应用启动时如果有待执行的迁移且数据库是本地文件 H2，先用 H2 的 `BACKUP TO` 在数据库文件所在目录（`data/`）生成带时间戳的备份 `optionmgr-backup-before-V5-<yyyyMMdd-HHmmss>.zip`，成功后才迁移；备份失败则不迁移、不启动（Q60）。内存库（测试、E2E）不备份。
- `POST /api/test-support/reset` 之后：只有账户 `1`"默认账户"，没有任何数据；下一个新账户的 id 为 `2`。

