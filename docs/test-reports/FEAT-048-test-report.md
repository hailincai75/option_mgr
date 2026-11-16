# FEAT-048 测试报告

- **测试日期:** 2026-11-15
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat048LotUnrealizedSystemTestBase.java、Feat048LotUnrealizedAcceptanceSystemTest.java（9 个）、Feat048LotUnrealizedBoundarySystemTest.java（11 个）
- **E2E 测试:** e2e/tests/feat-048-lot-unrealized-pnl.spec.ts（9 个）

场景按需求：今天 2026-11-16 星期一 10:00 拉取成功，虚构标的 ZZQ（另用虚构 ZZR / ZZS 做边界），假 CBOE（e2e test-support），未调用真实 CBOE / Finnhub。按契约说明，验收 7（现价 8.655）与验收 9（8.65）是两个独立的起始状态，各自从重置后的场景开始。

所有后端测试除了核对具体数字，还对每一行做契约不变量检查（`assertLotInvariants`）：
- 5 个新字段都在（为 null 时也不省略），原有批次字段都还在；
- 批次的状态 / 标签 = 所属持仓行（Q239）；
- 除最后一个批次外，市值 = 价格 × 大小 HALF_UP 到分，带方向符号；最后一个 = 行 − 其它之和（Q238 A）；
- 各批次市值之和、未实现之和都逐分等于持仓行；
- 未实现 = 剩余开仓净额 + 市值（期权）/ 市值 − 剩余成本（股票）；
- 百分比 = 未实现 × 100 ÷ |分母|，分母 0 → null（Q240）；
- 只有 1 个批次时批次 = 行。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 空头批次 A −20.00 / −1.66（−9.05%），B −20.00 / +17.34（+46.44%）；C 行不变 | Pass | Pass | E2E 还核对了列位置（`剩余开仓净额 → 市值 → 未实现盈亏`）和正绿负红 |
| 2 | 多头 L1 +17.00 / −8.65（−33.72%），L2 +34.00 / −7.30（−17.68%）；P 行不变 | Pass | Pass | |
| 3 | 股票 S1 +865.00 / −85.00（−8.95%），S2 +865.00 / +84.35（+10.81%）；行 +1,730.00 / −0.65 | Pass | Pass | API 另查顶部合计 `unrealized-summary` 不变；E2E 核对了列位置（`剩余成本` 之后） |
| 4 | BTC 1 张 9C 20.65 → 已平仓 −2.31（平掉 A）；C 行与唯一批次 B 都是 −20.00 / +17.34（+46.44%） | Pass | Pass | E2E 在页面上平仓，平仓前批次表已展开，平仓后展开中的批次表自动更新 |
| 5 | 手动 Mark 0.35 → 行 / A / B 的数字；清除后恢复验收 1 | Pass | Pass | E2E 在页面上设置、清除 |
| 6 | 7P 批次 `无报价`、已过期 8P 批次 `已到期，待处理`；拉取失败（行情未更新）后 A、B 仍为 −1.66 / +17.34 | Pass | Pass | E2E 核对灰字、`data-status`，不可计算时没有百分比元素 |
| 7 | 8.655，乙：S3 +874.16 / −5.84（−0.66%），S4 取余数 +856.84 / −3.16（−0.37%），行 +1,731.00 / −9.00 | Pass | Pass | API 断言 S4 ≠ 856.85（不是各自取整的结果） |
| 8 | 验收 4 之后卖出 50 股 440.00 → 行 150 股 +1,297.50 / +41.85；S1 50 股 475.00 → +432.50 / −42.50；S2 不变 | Pass | Pass | |
| 9 | 全部账户：A、B 同验收 1；S3 +873.65 / −6.35，S4 +856.35 / −3.65，乙行 −10.00（−0.57%）；无写操作按钮 | Pass | Pass | API：每个 ALL 行（含批次）与单账户行逐字相同；带 ALL 的平仓请求返回 400 `ALL_ACCOUNTS_READ_ONLY` |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| Q238 三个批次 + 手动价 12.3456：+407.40 / +407.40 / 最后取余数 +419.76（不是 419.75） | Pass | API + E2E（E2E 在页面上设手动价） |
| 同一时间的两笔按录入顺序排列（Q19），最后一笔取余数 | Pass | API + E2E |
| 股票 NO_QUOTE（标的无行情、清除手动价后）→ 批次全部 `无报价` | Pass | API + E2E |
| 剩余成本 0.00 的批次（转入 3 股 0.01、卖出 2 股）→ 百分比 null，页面显示 `—` | Pass | API + E2E。开仓净额 / 转入金额都必须 > 0，所以分母为 0 的情况只能用股票部分卖出构造 |
| 到期日当天（EXPIRES_TODAY）照常计算；次日批次变为 `已到期，待处理` | Pass | API |
| 周末不拉取，按库里最后报价计算 | Pass | API |
| 数据源已无该合约（MISSING_FROM_SOURCE）→ 用最后的 mark 计算 | Pass | API |
| mark 为 theo（bid 0、theo 0.25） | Pass | API |
| put 被指派得到的股票批次 ASSIGN_BUY 成本 785.00 → +865.00 / +80.00（+10.19%） | Pass | API |
| 补录更早的开仓 / 删除批次 → 重放后批次重新计算 | Pass | API |
| FEAT-043 调整到期日的响应 `position.lots` 带 5 个新字段，数字正确（mark 0.25），且与 GET 行相同 | Pass | API（乙持有 12-17 9C，使该合约有报价） |
| 契约形状：甲、乙、ALL、空账户 | Pass | API |
| 持仓接口的错误不变：ACCOUNT_REQUIRED / BAD_REQUEST / ACCOUNT_NOT_FOUND | Pass | API |

## 回归运行
- `gradlew.bat build`：BUILD SUCCESSFUL，1546 个测试，0 失败、0 跳过。
- 前端 `npm run lint`（0 警告）、`npm run typecheck`、`npm test`：54 个测试文件、795 个测试全部通过。
- E2E：`feat-048-lot-unrealized-pnl.spec.ts` 9 个通过；受影响的 spec 4 个，共 51 个测试全部通过：
  - feat-003-positions
  - feat-011-stock-trades
  - feat-013-put-assignment
  - feat-038-short-call
- 受影响 spec 的选取依据：在 `e2e/tests/` 中 grep 持仓页批次表的 testid（`position-lots|stock-position-lots|lot-row|lot-cell-|stock-lot-`）。
  - 选中：只有上面 4 个 spec 的断言用到持仓页的期权批次表（`lot-row`、`lot-cell-*`）或股票批次表（`stock-lot-row`、`stock-lot-*`）。
  - 未选：feat-004 / 006 / 012 / 014 / 040 / 041 / 046 的匹配是 `covered-lot-row`、`closed-lot-row`、`sold-lot-row` 等其它表；feat-017、feat-043 不涉及批次表。全量 E2E 留给里程碑。
- 已有测试无需修改：没有测试断言批次的完整字段列表。FEAT-043 中"调整前后批次相等"的断言在无行情（NO_QUOTE）下两边一致，仍成立。

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- 无。
