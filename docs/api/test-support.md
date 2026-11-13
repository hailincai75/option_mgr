# 测试支持（仅 `e2e` profile，正常运行时不存在）

> ⚠️ **只供 E2E 测试使用。** 以下接口只在后端以 `e2e` profile 启动时存在（`SPRING_PROFILES_ACTIVE=e2e` 或 `--spring.profiles.active=e2e`）。默认运行、`test` profile 及任何其它情况下，这些路径和任何未知路径一样返回 404 `NOT_FOUND`。前端页面不得调用。
>
> `e2e` profile 强制使用内存 H2（`jdbc:h2:mem:e2e`）；如果最终生效的 `spring.datasource.url` 不是 `jdbc:h2:mem:...`（例如被 `SPRING_DATASOURCE_URL` 改成文件库），后端**拒绝启动**，所以不可能误清开发库 `backend/data/`。

#### `POST /api/test-support/reset`
删除所有业务数据，把数据库恢复成"刚完成 Flyway 迁移的新库"状态：
- 所有表（除 `flyway_schema_history`）恢复到启动时迁移完成那一刻的内容：业务表清空，迁移写入的初始数据（如 `cash_account` 唯一一行）保持/恢复原样
- 所有自增 id 重新从 1 开始（或从迁移初始数据的最大 id + 1 开始），所以重置后的写入与新库完全一致（如第一笔入金 `id = 1`、`ledgerEntryId = 1`，流水录入顺序从头开始）
- 在一个事务里执行，并持有所有现金写操作使用的 `cash_account` 行锁：不会与并发写操作交错（正在进行的写操作先完成，重置后到的写操作在重置之后执行）

无请求参数，无请求体。

响应 `204 No Content`（无响应体）。重复调用是安全的（幂等）。

错误：只有通用错误（如 `GET /api/test-support/reset` → 405 `METHOD_NOT_ALLOWED`；未启用 `e2e` profile → 404 `NOT_FOUND`）。

示例：
```
POST /api/test-support/reset
→ 204 No Content

GET /api/cash/summary
→ 200 {"totalCash": "0.00", "frozenCash": "0.00", "availableCash": "0.00"}
```

