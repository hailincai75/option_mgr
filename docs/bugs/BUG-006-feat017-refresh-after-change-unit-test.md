# BUG-006: FEAT-017 让 `refreshAfterChange` 多失效了 `unrealized-summary`，旧单元测试没改，`npm test` 失败

- **Status:** Closed
- **Severity:** Minor
- **Layer:** frontend
- **Related:** FEAT-017（FEAT-005 / FEAT-006 / FEAT-011 的 `refreshAfterChange`）

## 复现步骤
1. `cd frontend && npm test`

## 期望结果
全部通过。前端检查 `npm run lint && npm run typecheck && npm test` 是需求验证的一部分。

## 实际结果
lint 和 typecheck 都通过；Vitest 结果为 `Test Files 1 failed | 41 passed (42)`，`Tests 1 failed | 684 passed (685)`。

失败的测试：`src/components/trade/tradeFormSupport.test.ts` › `refreshAfterChange` › `invalidates cash, trades, positions, audit log and closed trades and stocks (FEAT-005, FEAT-006, FEAT-011)`（第 9 行）。

```
AssertionError: expected [ [ 'cash' ], …(10) ] to deeply equal [ [ 'cash' ], …(9) ]
     [ "option-positions" ],
+    [ "unrealized-summary" ],
     [ "audit-log" ],
```

原因：FEAT-017 按 docs/architecture.md 的要求（"凡失效 `option-positions` 的地方都同时失效 `unrealized-summary`"），在 `refreshAfterChange` 里紧跟 `['option-positions']` 加了 `['unrealized-summary']`。页面行为是对的：E2E 中平仓、作废后合计都会更新。但这个旧测试精确列出了全部 queryKey，没有包含新加的 key。需要 frontend-dev 更新这个测试，在 `['option-positions']` 后面加上 `['unrealized-summary']`，并且仍保持精确等于。QA 不改 dev 写的单元测试。

## 修复（frontend-dev）
- **原因**：FEAT-017 在 `refreshAfterChange` 里紧跟 `['option-positions']` 新增失效 `['unrealized-summary']`（行为正确），但 `tradeFormSupport.test.ts` 精确列出全部 queryKey，没有同步更新；当时只跑了自己新建 / 修改的测试文件，没有 grep 到这个精确列表。
- **修法**：`frontend/src/components/trade/tradeFormSupport.test.ts` 的期望值在 `['option-positions']` 后加 `['unrealized-summary']`，仍为精确 `toEqual`；测试名补上 FEAT-017。
- **排查其它精确列表**：grep 了所有 spy `invalidateQueries` 和列出 `option-positions` / `stock-positions` key 的测试，只有这一处（`marketDataRefresh`、各作废 / 调整到期日对话框没有精确列出 key 的单元测试）。
- **验证**：`npx vitest run src/components/trade/tradeFormSupport.test.ts` 通过；`npm run lint`、`npm run typecheck` 通过。

## QA 复测（2026-11-14）
`npx vitest run src/components/trade/tradeFormSupport.test.ts`：1 / 1 通过。Closed。
（同一时间跑的全量 `npm test` 中，另有 12 个失败在 `UnrealizedPnl.test.tsx` / `unrealizedLabels.test.ts`，原因是 frontend-dev 正在按用户新决定把所有市值都改为带显式正负号，测试和代码暂时不一致。这与本 bug 无关，记在 FEAT-017 测试报告里。）
