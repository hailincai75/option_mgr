# BUG-002: 交易列表筛选无结果时不显示"共 0 条"

- **Status:** Closed
- **Severity:** Minor
- **Layer:** frontend
- **Related:** FEAT-002（验收 2）

## 复现步骤
1. 后端以 `e2e` profile 运行，按 FEAT-002 基准数据录入：入金 100,000.00，以及 T1–T6。
2. 打开 `/trades`，在"标的"中填 `AAP`，点"查询"。

自动化：`e2e/tests/feat-002-trade-list.spec.ts` →「AC2 标的精确匹配：aapl / " AAPL " → T4 T2 T1；AAP → 没有符合条件的交易、共 0 条、清除筛选；$AAPL → 字段错误」，第 173 行 `expect(page.getByText('共 0 条')).toBeVisible()`。
运行：`cd e2e && npx playwright test tests/feat-002-trade-list.spec.ts -g "AC2 标的精确匹配"`。

## 期望结果
按 FEAT-002 验收 2："标的填 `AAP` → 显示'没有符合条件的交易'，**'共 0 条'**，有'清除筛选'按钮"。

## 实际结果
- 能看到"没有符合条件的交易"（`trade-list-no-match`）和"清除筛选"按钮（`trade-list-no-match-clear`），表格没有行。这两项符合预期。
- 页面上**没有**"共 0 条"：`trade-list-total` 不存在。后端返回的是 `totalItems: 0`，没有问题。

`TradeListPage.tsx` 只在 `data.totalItems > 0` 时渲染分页区（包括 `trade-list-total`）。

## 备注
- 同一段逻辑也会让"暂无交易"（验收 8）时不显示"共 0 条"。验收 8 没有要求显示它，所以只按验收 2 报。
- 修法由 frontend-dev 决定：例如有筛选条件且无结果时也显示"共 0 条"（分页器和每页条数可以继续隐藏）。

## 原因（frontend-dev）
`frontend/src/pages/TradeListPage.tsx` 把"共 N 条"和分页器、每页条数放在同一个区域里，整个区域只在 `data.totalItems > 0` 时渲染，所以筛选无结果（`totalItems = 0`）时"共 0 条"也被隐藏了。

## 修法
- 分页区在 `totalItems > 0` **或有筛选条件**时渲染；`trade-list-total`（"共 N 条"）总是显示，分页器和每页条数仍只在 `totalItems > 0` 时显示。
- 结果：有筛选无结果 → "没有符合条件的交易" + "清除筛选" + "共 0 条"（验收 2）。完全无交易且无筛选 → 只显示"暂无交易" + "录入开仓"，不显示"共 0 条"（验收 8 没有要求，保持不变）。
- 单元测试 `frontend/src/pages/TradeListPage.test.tsx`：无结果（`AAP`、方向 BTC）时断言 `共 0 条`，且没有分页按钮和每页条数按钮；"暂无交易"时断言没有 `trade-list-total`。
- 验证：`npm run lint && npm run typecheck && npm test && npm run build` 全部通过（195 个测试）；`npx playwright test tests/feat-002-trade-list.spec.ts -g "AC2 标的精确匹配"` 通过。

## QA 复测（2026-11-11）
- `npx playwright test`：97 passed，其中 AC2 用例（含"共 0 条"）通过，AC8 空状态也通过。
- 结论：已修复，Closed。
