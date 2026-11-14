# FEAT-016 测试报告

- **测试日期:** 2026-11-14
- **结论:** Verified（2026-11-14 复测：BUG-004、BUG-005 已修复，后端 build 与前端检查全部通过；10 条验收标准全部 Pass）
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/`
  - `Feat016AutoQuotesAcceptanceSystemTest`（AC1–AC10，12 个）
  - `Feat016AutoQuotesBoundarySystemTest`（边界与校验，20 个）
  - `Feat016RealHttpSystemTest`（真实 HTTP 传输：AC10 启动、key 只在请求头、Q147 启动拉取，4 个）
  - 共用：`Feat016QuoteSystemTestBase`、`QaFakeQuoteServer`（本地假 CBOE / Finnhub，不访问真实服务）
- **E2E 测试:** `e2e/tests/feat-016-auto-quotes.spec.ts`（11 个）

## 复测（2026-11-14，BUG-004 / BUG-005 修复后）
只改了测试文件，产品代码没有变化，所以 E2E 不重跑（沿用下方首轮结果：feat-016 11/11、feat-003 9/9）。

| 检查 | 结果 |
|------|------|
| `gradlew.bat build` | BUILD SUCCESSFUL：1205 个测试，0 失败，0 错误（含 FEAT-016 系统测试 36 个） |
| 前端 `npm run lint` / `npm run typecheck` / `npm test` | 全部通过：Test Files 40 passed，Tests 669 passed |

## 首轮运行结果
| 检查 | 结果 |
|------|------|
| `gradlew.bat build`（含 bootJar） | **失败**：1205 个测试 1 个失败：`MigrationV6HasAuditLogTest.backfillSetsTheFlagForRecordsWithTheirOwnAuditRecords`（BUG-005）；单独重跑稳定复现。FEAT-016 系统测试 36/36 通过 |
| 前端 `npm run lint` / `npm run typecheck` | 通过 |
| 前端 `npm test` | **失败**：669 个测试 4 个失败，在 3 个文件里：`Exercise.test.tsx`（2）、`PutAssignment.test.tsx`（1）、`PositionsPage.test.tsx`（1）（BUG-004） |
| E2E `feat-016-auto-quotes.spec.ts` | 11/11 通过。第一次运行有 3 个失败，都是测试代码问题，修正后只重跑这 3 个，全部通过：① 对话框可见性断言打在宽高为 0 的 `ant-modal-root` 上，改为断言 `getByRole('dialog')`（2 个）；② AC6 第二段的时间线写错：10:15 QRTV 已成功，10:31 本来就不该提示。已改为 10:30、10:45 全部失败、10:46 提示 `上次：2026-11-23 10:15 美东`，AC 原文的 10:00 / 10:31 时间线由系统测试 `ac6AllFailTopWarning` 覆盖 |
| E2E 受影响 spec `feat-003-positions.spec.ts` | 9/9 通过 |
| E2E `npx tsc --noEmit` | 通过 |

**受影响 spec 的选择（窄规则）：** frontend 改动 = `PositionTable` 加 4 列、`StockPositionTable` 加 2 列、`PositionsPage` 加 `MarketDataBar`。都是新增内容，没有改动已有的 testid 或文字。grep 依据：
- `grep -ln "标的价\|现价\|报价时间\|Bid / Ask\|无报价\|立即刷新\|market-data\|行情" e2e/tests/*.spec.ts` → 只有 feat-016。
- `grep -n "positions-page').getByRole\|positions-page').locator('th\|stock-positions').locator('th\|row(.*).getByRole('button'" e2e/tests/*.spec.ts` → 只有 `feat-003-positions.spec.ts:120`（断言持仓表全部表头）和 `:125`（断言持仓页没有"删除 / 编辑"按钮）。新列和新增的"手动设置 / 清除"按钮正好落在这两条断言的范围内。
- `grep -n "ant-alert\|getByRole('alert')" e2e/tests/*.spec.ts` → 只有 feat-042 一处，限定在自己的 testid 内，不受顶部行情提示影响。
- `grep -n "locator('button')" e2e/tests/*.spec.ts` → 没有结果。

所以只选 feat-016 + feat-003。feat-010 / 011 / 013 / 014 / 015 / 039 / 040 / 041 只按各自的 testid 读持仓行，只是"多显示几列"，留给里程碑全量 E2E。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 10:00 拉取：P1 / P3 = 52.40、1.20 / 1.30、1.25、11-23 09:45；P2 = 61.10、2.10 / 2.30、2.20；IXQZ 现价 61.10 | Pass | Pass | 两个账户看到的同一合约行情 JSON 完全相同；拉取前为无报价 + "从未成功获取"提示 |
| 2 | 3,400 个合约只存 2 个，P1 存 iv / delta / theta / theo；P1、P3 平仓后不再请求 QRTV | Pass | — | 页面对象的字段集合精确比对，不含 IV / Greeks |
| 3 | bid 0 / ask 0.05 / theo 0.03 → Mark 0.03；theo 也缺 → 无报价 | Pass | Pass | |
| 4 | IXQZ CBOE 500 + Finnhub 61.05 → 现价 61.05（Finnhub）；P2 无报价；P1、P3 = 1.25 | Pass | Pass | 悬停显示 `来源 Finnhub`，`data-source=FINNHUB`；P2 带 `行情未更新（从未成功）`（Q145） |
| 5 | P4 无报价；P1 从链中消失 → 1.25 + `数据源已无该合约（最后报价：11-23 09:45）` | Pass | Pass | |
| 6 | IXQZ 失败只标该行；全部失败超过 30 分钟出现顶部提示（10:30 不提示、10:31 提示），成功后消失 | Pass | Pass | E2E 第二段时间线见上方说明 |
| 7 | 16:20 另存收盘快照；之后到次日 09:30 不拉取、不提示；感恩节全天不拉取 | Pass | — | 只能通过测试支持接口检查，没有页面 |
| 8 | 手动 Mark 1.40、标的价 52.00（`手动 11-23 10:05`，B 的 P3 同样）；失败时保留；成功后恢复 1.25 / 52.40 | Pass | Pass | 在页面上设置、清除，以及非法价格 0 的提示 |
| 9 | 立即刷新 → 1.35，按钮禁用到 10:03；10:02:30 绕过页面请求不发 CBOE 请求 | Pass | Pass | 悬停提示 `1 分钟内只能刷新一次`；改时钟后重新加载页面，按钮恢复可用；后端报错时显示 `data-kind=error` |
| 10 | 无 key、CBOE 不可达、从未成功：正常启动，全部无报价，顶部"从未成功获取"提示；STO 2027-01-15 45P 照常保存 | Pass | Pass | 后端用真实 HTTP 传输启动（`fetch-enabled=true`，CBOE 指向 `127.0.0.1:9`，`.env` 不存在）；未在本机手工 `bootRun`（不碰项目 `.env`） |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 指数标的 SPX / SPXW 不请求、不计入跟踪；无报价、无失败标签；手动覆盖一直保留（Q149） | Pass | API + E2E |
| 只有指数持仓：跟踪 0 个，没有顶部提示 | Pass | |
| 四要素歧义（`QRTV…` 与 `B1QRTV…`）：从未报价的保持无报价，`failureDetail` 含 ambiguous；曾有报价的转为 MISSING_FROM_SOURCE，手动 mark 保留 | Pass | |
| OCC 匹配：root 以标的结尾（B1QRTV）可以匹配；调整合约 root（QRTV1）不干扰；行权价 47.5 | Pass | |
| Mark 格式：1.255、0.035、1.50；ask 为 0 时用 THEO；BigDecimal 中间价 0.075（真实 HTTP） | Pass | |
| CBOE 没有 `current_price` → 标的价用 Finnhub，链仍算成功 | Pass | |
| 响应格式变化（非 JSON、缺 timestamp、缺 options、timestamp 格式错）及 Finnhub c=0 → INVALID_RESPONSE，不影响其它标的 | Pass | |
| Q148：自动 mark 为空时手动值保留；链失败时保留；CBOE 失败但 Finnhub 成功时替换手动标的价 | Pass | |
| 覆盖校验：缺价格、≤0、超过 4 位小数、过大、空白标的、行权价 0；非数字、日期错、类型错、缺 query 参数；未持有返回 422；清除幂等；strike 50.0 与 50 相同 | Pass | |
| 定时时刻：09:29 / 09:30 / 10:00:30 / 10:07 / 16:00 / 16:05 / 16:15；周末不拉；11-27 提前收盘日 16:20 仍为 CLOSE | Pass | |
| 收盘快照只含本次成功更新的标的和合约；同一天再次 CLOSE 覆盖；立即刷新不产生快照 | Pass | |
| 顶部提示时段：16:00（含）提示、16:01 不提示；次日从 09:30 起算，10:00 不提示、10:01 提示 | Pass | |
| 立即刷新：周六也能刷新；定时拉取不重置冷却；没有跟踪标的时 `results` 为空；reset 清空冷却；不需要 `X-Account-Id` | Pass | |
| Q150：到期日当天仍拉取；次日不再请求只剩过期持仓的标的（定时和立即刷新都不请求）；同一标的还有未过期持仓时，过期合约即使仍在链中也不更新 | Pass | API + E2E |
| Q151：过期且曾有报价 → MISSING_FROM_SOURCE + `已到期（最后报价：11-20 09:45）`；过期前已从链中消失的，过期后标签也改为"已到期"；标的价显示最后的值，没有失败标签 | Pass | API + E2E |
| Finnhub key 只在 `X-Finnhub-Token` 请求头里，不出现在 URL、响应、`failureDetail`、日志中；CBOE 请求路径正确 | Pass | 本地假服务 |
| Q147：交易日 10:00 启动 → 后台拉一次；感恩节启动 → 不拉取 | Pass | |
| 录入、平仓不受行情失败影响（规则 9） | Pass | |

## 发现的 Bug
（两个都已修复，2026-11-14 复测通过，已关闭）
- BUG-004（frontend）：FEAT-016 每行新增的"手动设置"按钮让 3 个旧单元测试（Exercise / PutAssignment / PositionsPage）列出的按钮清单不再相等，`npm test` 失败。页面行为符合需求，只需更新这些测试。
- BUG-005（backend）：`MigrationV6HasAuditLogTest` 仍过滤 `before-V15` 备份文件名，新增 V16 迁移后应为 `before-V16`，导致 `gradlew.bat build` 失败。

## 疑问 / 需求不清楚的地方
- 没有跟踪标的时，一次 run 也算"成功"（契约如此）。因此空库启动时如果做了一次启动拉取，之后再加持仓，Q146 的"从未成功获取"提示不会出现，要到交易时段超过 30 分钟才提示。这符合契约，记录在这里供确认。
- `stored` 接口说的"未补零的原值"与示例里的 `"1.2"`（输入 1.20）不一致；测试按数值比较，不受影响。
