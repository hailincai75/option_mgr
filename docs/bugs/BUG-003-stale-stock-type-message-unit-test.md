# BUG-003: 前端单元测试仍断言旧的股票类型文案，`npm test` 失败

- **Status:** Closed
- **Severity:** Minor
- **Layer:** frontend
- **Related:** FEAT-041（FEAT-013）

## 复现步骤
1. `cd frontend && npm test`

## 期望结果
全部通过。FEAT-041 契约把股票类型筛选的文案改成了 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL`，前端实现（`stockListFilters`）和 `stockUtils.test.ts` 都已改成这个新文案。

## 实际结果
1 failed | 506 passed。失败的是 `src/components/position/assignValidation.test.ts` > "FEAT-013 links and filters" > "accepts ASG / ASSIGN_BUY in the list filters"。第 61 行还在期待旧文案 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY`，实际得到的是带 `、ASSIGN_SELL` 的新文案。

前端检查（`npm run lint && npm run typecheck && npm test`）因此不通过。页面行为本身是对的（E2E 通过），只需更新这个 dev 写的单元测试；QA 不改 dev 的单元测试。

## Root Cause & Fix
- 原因：FEAT-041 改了股票类型文案，frontend-dev 只更新了 `stockUtils.test.ts`，漏了 `assignValidation.test.ts` 第 61 行的同一断言。
- 修法：该行改为 `类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL`；grep `frontend/src` 已无其它旧文案。`npx vitest run src/components/position/assignValidation.test.ts` 14/14 通过。

## 复测
2026-11-13：frontend-dev 已更新 `assignValidation.test.ts:61`。`npx vitest run src/components/position/assignValidation.test.ts` 14/14 通过，关闭。
