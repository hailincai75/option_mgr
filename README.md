# Option Manager

个人期权交易管理系统（Spring Boot 后端 + React 前端），用 Claude Code 的四个 subagent 开发：
pm / backend-dev / frontend-dev / qa。规则和流程见 CLAUDE.md。

## 开发流程
1. `pm` 写需求（Draft）→ 我审核改为 Approved
2. `backend-dev` 先写 API 契约，再实现后端
3. `frontend-dev` 按契约实现页面
4. 前后端完成 → Implemented
5. `qa` 做 API 集成测试 + E2E → Verified 或 Failed（附 bug）
