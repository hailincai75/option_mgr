# BUG-007: FEAT-018 开关是 button，PositionsPage 单元测试"页面上只有这些按钮"失败

- **Status:** Fixed
- **Severity:** Minor
- **Layer:** frontend
- **Related:** FEAT-018（回归：FEAT-003 / FEAT-004 / FEAT-016 的单元测试）

## 复现步骤
1. `cd frontend && npm test`（或 `npx vitest run src/pages/PositionsPage.test.tsx`）

## 期望结果
全部前端单元测试通过（FEAT-018 验证时要求前端全量 `npm test` 通过）。

## 实际结果
Vitest 44 个文件、706 个测试中 1 个失败：

```
FAIL  src/pages/PositionsPage.test.tsx > PositionsPage > shows header values and every position in backend order (AC 1)
AssertionError: expected [ 'positions-greeks-toggle', …(15) ] to deeply equal [ …(15) ]
+   "positions-greeks-toggle",
❯ src/pages/PositionsPage.test.tsx:323:62
```

该测试把 `positions-page` 卡片里所有 `<button>`（除 `position-expand` 外）列出来，断言只有每行的 `position-underlying-price-set`、`position-mark-set`、`position-close`。FEAT-018 在同一卡片内加了 `显示 Greeks` 开关（antd `Switch` 渲染为 `<button role="switch" data-testid="positions-greeks-toggle">`），所以多出一个。

功能本身正常（开关是需求 Q173 要求的，E2E 中默认关闭、打开 / 关闭都符合验收）；这是 dev 的单元测试没有随 FEAT-018 更新。QA 不能改 dev 的单元测试，请 frontend-dev 更新该断言（例如把 `positions-greeks-toggle` 放进期望列表，或排除 `role="switch"`），保留"没有编辑 / 删除按钮"这一检查。

## 原因与修复（frontend-dev）
- **原因**：FEAT-018 在 `positions-page` 卡片内、期权表上方加了 `显示 Greeks` 开关（antd `Switch`，渲染为 `<button role="switch" data-testid="positions-greeks-toggle">`）；`PositionsPage.test.tsx` AC 1 用例精确列出卡片内所有按钮，没有随 FEAT-018 更新。功能代码无问题。
- **修复**：更新该断言，期望列表开头明确加入 `positions-greeks-toggle`，其后仍是每行 `position-underlying-price-set`、`position-mark-set`、`position-close`（列表仍精确相等，所以仍检查没有编辑 / 删除按钮）；另断言该开关 `role="switch"`、默认 `aria-checked="false"`（Q173）。
- **验证**：`npx vitest run src/pages/PositionsPage.test.tsx` 12 个测试全部通过；`npm run lint`、`npm run typecheck` 通过。

## 复测（QA，2026-11-14）
frontend-dev 只修改了 `PositionsPage.test.tsx`。复测前端全量 `npm run lint && npm run typecheck && npm test`：lint、typecheck 通过；Vitest 44 个文件、706 个测试全部通过。状态改为 Closed。
