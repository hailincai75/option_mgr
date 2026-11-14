# BUG-004: FEAT-016 的"手动设置"按钮让 3 个已有持仓页单元测试失败，`npm test` 不通过

- **Status:** Closed
- **Severity:** Minor
- **Layer:** frontend
- **Related:** FEAT-016（FEAT-003 / FEAT-013 / FEAT-014）

## 复现步骤
1. `cd frontend && npm test`

## 期望结果
全部通过（前端检查 `npm run lint && npm run typecheck && npm test` 是需求验证的一部分）。

## 实际结果
`Test Files 3 failed | 37 passed (40)`，`Tests 4 failed | 665 passed (669)`（lint、typecheck 通过）：

| 测试 | 位置 | 期望 | 实际 |
|------|------|------|------|
| Exercise.test.tsx › shows 行权 next to 平仓 before expiry… | 377 | `['position-close','position-exercise']` | 前面多了 `position-underlying-price-set`、`position-mark-set` |
| Exercise.test.tsx › treats exercisable as false… | 391 | `['position-expire']` | 同上多两个 |
| PutAssignment.test.tsx › shows 被指派 by `assignable`… | — | `['position-close','position-assign']` | 同上多两个 |
| PositionsPage.test.tsx › shows header values and every position… (AC 1) | 322 | 页面上除展开外只有 5 个 `position-close` | 每行多 `position-underlying-price-set`、`position-mark-set`（15 个） |

原因：FEAT-016 在每个期权持仓行的"标的价"和"Mark"单元格里加了"手动设置"按钮（`data-testid` 以 `position-` 开头），这几个旧测试列出了行内 / 页面上的**全部**按钮。页面行为符合 FEAT-016 需求（每行可手动设置），E2E 正常；需要 frontend-dev 更新这些单元测试（例如只列操作列的按钮，或把两个新按钮加进期望值）。QA 不改 dev 写的单元测试。

## 修复说明（frontend-dev）
- **原因**：FEAT-016 在每个期权持仓行的"标的价""Mark"单元格加了"手动设置"按钮（`position-underlying-price-set`、`position-mark-set`），这 3 个旧测试精确列出行内 / 页面的全部按钮，没有预期这两个新按钮。页面行为本身正确。
- **修法**（只改测试，检查意图不放宽，仍是精确等于、任何多出的按钮如编辑 / 删除都会失败）：
  - `Exercise.test.tsx` 的 `buttonsOf`、`PutAssignment.test.tsx` 的 `buttons`：先断言每行开头正好是 `['position-underlying-price-set', 'position-mark-set']`，再返回其余按钮（操作列），原有期望值不变。
  - `PositionsPage.test.tsx`（AC 1）：页面上除展开外的按钮期望改为每行依次 `position-underlying-price-set`、`position-mark-set`、`position-close`，共 5 行。
- **验证**：`npm run lint`、`npm run typecheck` 通过；`npx vitest run src/pages/Exercise.test.tsx src/pages/PutAssignment.test.tsx src/pages/PositionsPage.test.tsx` 3 个文件 55 条全部通过。

## 复测
2026-11-14：修复说明已核对。断言仍是精确相等：先断言每行开头正好是两个"手动设置"按钮，其余按钮（操作列）的期望值不变，没有放宽。前端 `npm run lint && npm run typecheck && npm test` 全部通过：Test Files 40 passed (40)，Tests 669 passed (669)。关闭。
