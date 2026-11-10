# API 契约

由 backend-dev 维护，frontend-dev 和 qa 只读。每个需求实现前先在这里写好 API。

## 通用约定
- Base path：`/api`
- 金额字段为字符串（如 `"349.35"`），日期为 ISO-8601（`2026-12-18`），时间为 UTC Instant
- 统一错误格式：（由 backend-dev 在第一个需求中定义）

## Endpoints
（按需求编号分节，例如 `### FEAT-001`）
