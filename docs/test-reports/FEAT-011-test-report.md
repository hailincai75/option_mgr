# FEAT-011 测试报告

- **测试日期:** 2026-11-13（E2E 用真实时钟，运行时美东约 11:50–12:20，星期五）
- **结论:** Verified
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/Feat011StockTradesSystemTest.java`（29 个用例，真实 HTTP 端口，固定时钟 2026-11-13 10:00 美东 = `2026-11-13T15:00:00Z`）；共用清理 `CashSystemTestBase.emptyLedger()` 增加 `DELETE FROM stock_trade`（在删除非默认账户之前，`stock_trade` 对 `account` 有外键）
- **E2E 测试:** `e2e/tests/feat-011-stock-trades.spec.ts`（17 个用例）；更新 `e2e/tests/feat-038-short-call.spec.ts` 的 Q61 提示文字

## 运行结果
| 套件 | 命令 | 结果 |
|------|------|------|
| 后端构建（含全部单元测试与 systemtest） | `cd backend && gradlew.bat build` | BUILD SUCCESSFUL，671 个测试 0 失败（systemtest 371 个，其中 FEAT-011 29 个） |
| 前端检查 | `cd frontend && npm run lint && npm run typecheck && npm test` | 通过；Vitest 23 个文件 444 个测试全部通过 |
| E2E 全部 | `E2E_BACKEND_PORT=28080 E2E_FRONTEND_PORT=25173 npx playwright test` | **190 passed，0 failed**（9.5 分钟） |
| E2E FEAT-011 | 同上，`tests/feat-011-stock-trades.spec.ts` | 17 passed。前两次单独运行中的失败都是我写的用例问题（antd Radio.Button 的 testid 在隐藏 input 上；淡出中的旧字段错误；两处我自己算错的期望值 −75.49 / 共 0 个标的，正确为 −75.16 / 共 1 个标的 IXQZ 100 股 750.33），已修正，与实现无关 |

E2E 测试支持：`POST /api/test-support/reset` 按 JDBC 元数据清所有表，已确认会清空 `stock_trade`（含非默认账户的记录），重置后第一条股票记录 id = 1（用例"测试支持：reset 清空股票交易"）。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 录入与股票持仓 | Pass — `ac1EntryAndPositions` | Pass — `AC1 持仓页…`、`AC1 …暂无股票持仓` | S1 `tradedAt=2026-10-01T04:00:00Z`、`tradedAtLocal=…T00:00:00`、`tradeDate`、每股 7.5000、现金变动 0.00、`ledgerEntryId=null`；S1 前后现金都是 10,000.00；期权持仓 0；股票持仓 2 个：IXQZ 150 / 1,125.49 / 7.5033、XYZ 200 / 666.67 / 3.3334；批次 `买入 #2 2026-11-03 10:00 200→150 1,125.49`、`买入 #3 … 300→200 666.67`（S1 已卖完不显示）。E2E：期权空状态、`共 2 个标的`、说明一行、展开批次 |
| 2 | 列表、已实现盈亏、资金流水 | Pass — `ac2ListPnlLedger`、`noteFlowsToLedgerAndLots` | Pass — `AC2 股票页…` | 5 行顺序、金额/现金变动/每股/盈亏与需求表一致；S4 被卖出批次 `转入 #1 100 股 750.00`、`买入 #2 50 股 375.16`，成本 1,125.16，+74.84；S5 `买入 #3 100 股 333.33` +66.67；全部 `hasAuditLog=false`、没有"历史"；按 id 查询与列表项相同；流水 5 条（余额 9,099.35 / 8,699.35 / 7,499.35 / 8,499.35 / 10,000.00，说明 `卖出 100 股 XYZ` 等），`sourceId`↔`ledgerEntryId` 对应、冻结 0.00、流水行无"历史"；没有转入流水。E2E：颜色（卖出绿、买入红、转入不着色）、只有卖出可展开、资金页 4 条股票行无修改/删除、有"在股票交易中修改" |
| 3 | 列表筛选 | Pass — `ac3Filters`、`emptyListLateNightAndWeekend` | Pass — `AC3 筛选…`、`AC3 空状态与分页…` | `ixqz` → S4 S2 S1；SELL → S5 S4；TRANSFER_IN → S1；BUY+TRANSFER_IN（逗号、重复参数、空项）→ S3 S2 S1；11-04~11-05 → S4 S3；只填结束 10-31 → S1；`$IXQZ` / 11 字符 → `VALIDATION_ERROR`；未知类型、坏日期 → `BAD_REQUEST`；开始晚于结束 → `endDate`；分页/size/page 校验同 FEAT-002；深夜 23:30 成交按美东日期筛选。E2E：地址栏、刷新保留、后退/前进恢复、`$IXQZ` 在标的下报错且不发请求、非法 URL 参数不崩溃、无结果 + 清除筛选、空状态、21 条分 2 页、每页 50、超过最后一页跳到最后一页 |
| 4 | 部分卖出后再部分卖出（Q69、Q87） | Pass — `ac4RepeatedPartialSales`、`sellOutThenBuyAgain` | —（后端覆盖；E2E 录入用例覆盖部分卖出显示） | S6 成本 333.34、+86.66，XYZ 剩 100 / 333.33 / 3.3333；S7 成本 333.33、−33.33，XYZ 消失；三次成本合计 1,000.00、盈亏合计 +120.00 |
| 5 | 买入的现金检查；冻结的现金不能买股票 | Pass — `ac5CashCheckWithFrozenPut`、`ac5BackfilledBuyLaterPointNegative`、`stockBuyReducesAvailableForPutAndWithdrawal`、`sameTimeCashOrder` | Pass — `录入被拒：冻结的现金不能买股票…` | 600.00 被拒原文 `可用现金不足：需要 600.00；2026-11-03 10:00 时可用现金将变为 −80.00`、什么都不变；520.00 成功 500.00 / 500.00 / 0.00；出金 1.00 被拒；入金改 999.00 被拒 `…修改后 2026-11-03 10:00 时可用现金将变为 −1.00`、删除入金也被拒、不写审计；补录买入 1,200.00 被拒 `…2026-11-07 10:00 时可用现金将变为 −100.65`。跨功能：股票买入后 STO put 担保被拒、出金被拒、较早的出金因之后的股票买入被拒；卖出股票释放现金后可 STO put，删除这笔卖出被拒 `删除后 2026-11-06 10:00 … −480.00`。E2E：错误原文与错误码、输入保留、汇总不变 |
| 6 | 不能卖空、补录卖出 | Pass — `ac6NoShortSelling` | Pass — `录入被拒…`（持股不足、补录卖出） | 151 股、早于买入、没有的标的、补录 200 股 → 各自原文；150 股成功后 IXQZ 消失 |
| 7 | 转入按日期参与 FIFO、日期/时间不能晚于当前 | Pass — `ac7TransferInByDate`、`q86SameDayOrderingAndDst` | Pass — `录入格式校验…`（运行时刻 + 1 天） | 当天 09:30 卖出用到当天转入，+50.00，1,550.00；转入明天 → `tradeDate` `日期不能晚于今天`；今天成功（`05:00:00Z`）；10:01 买入/卖出 → `tradedAtLocal` `成交时间不能晚于当前时间`；正好 10:00 允许。Q86：前一天 23:59 的买入排在转入前，同日 00:00 的买入（先录入）排在转入前、00:01 的排在后；EDT/EST 换算 04:00Z / 05:00Z |
| 8 | 修改 | Pass — `ac8EditAmount`、`ac8EditTransferIn`、`ac8EditTransferCost`、`ac8RejectedEdits`、`editIdentitySameIgnoredNoteAndCash` | Pass — `AC8 修改 S2 金额…`、`AC8 修改转入日期…` | 改金额：9,100.00、S4 1,125.00 / +75.00、IXQZ 7.5000、流水原地更新（同 id）、审计 `金额：1,500.65 → 1,500.00`、before/after 完整、`sideEffects=[]`、只有 S2 `hasAuditLog`；改转入日期：S4 只扣 S2 1,125.49 / +74.51、批次顺序变为 S2(50, 375.16) 再 S1、7.5011、审计 `日期：2026-10-01 → 2026-11-04`；改总成本 → +24.84、审计 `总成本：750.00 → 800.00`；改小股数、改卖出时间、没有改动（含数值相等/秒/空白备注）、改标的/类型 → 原文，不写审计；同样的标的/类型被忽略；修改导致之后出金为负被拒。E2E：对话框只读类型/标的与提示、预填、"没有修改"与拒绝原文显示在对话框中且不关闭、保留输入；成功后不刷新页面持仓/资金/审计都更新；"历史"只在 S2，进入审计页按该记录筛选、展开修改前/后 |
| 9 | 删除 | Pass — `ac9DeleteTransferIn`、`ac9RejectedDeletes` | Pass — `AC9 删除…`、`审计：未被修改过的…` | 删除 S1：S4 +74.51、IXQZ 50 / 375.16 / 7.5032、现金与流水不变、审计 DELETE `转入 IXQZ 100 股`、before 字段完整；再 GET/PUT/DELETE → 404。删除 S2、S3 → 持股原文；出金 8,000 后删除 S4 → `…删除后 2026-11-07 10:00 … −100.65`；删除 S5 成功 699.35、XYZ 300 / 1,000.00、流水删除。审计按"股票交易"筛选可见已删除记录。E2E：摘要 `卖出 150 股 IXQZ，2026-11-05 10:00，+1,200.00` / `转入 100 股 IXQZ，2026-10-01，总成本 750.00`、警告文字、拒绝原文且对话框不关闭 |
| 10 | 同一时间按录入顺序 | Pass — `ac10SameTimeEntryOrder` | —（后端覆盖） | 先卖被拒（0 股）；买入 + 卖出同一时刻成功 +20.00，列表与流水中卖出在上；把买入改到 10:01 → `修改后 2026-11-10 10:00 的卖出 QRTV 100 股将超过当时持股 0 股` |
| 11 | 格式校验 | Pass — `ac11Validation`、`q88PricePerShareRounding`、`accountHeader` | Pass — `录入格式校验…` | 每个字段的空/非法/越界文案与契约一致；多个错误同时返回；`type` 未知、金额/股数非数字、时间/日期格式 → `BAD_REQUEST`；` ixqz ` 合并为 IXQZ 160 / 1,205.49；`BRK.B`、10,000,000 股、9,999,999,999,999.99、500 字备注合法。E2E：前端各字段下的文案与后端一致、未选类型、不合法时不发 POST |
| 12 | 按账户隔离 | Pass — `ac12AccountIsolation` | Pass — `AC12 按账户隔离…` | 小账户只看到 QRTV、卖出 IXQZ 被拒 0 股、用 S2 的 id GET/PUT/DELETE → 404；小账户转入 IXQZ 后默认账户仍 150 股；默认账户访问小账户的记录 → 404 |
| 13 | 与期权功能互不影响 | Pass — `ac13NoOptionInteraction`、`optionNumbersUnchangedByStocks`、`auditFilter` | Pass — `AC13 …`；`feat-038` AC1 已更新为新文案 | STO 5 张 IXQZ 9 C 成功、冻结 0.00、9,249.35；之后卖出 150 股成功；已平仓 0、盈亏汇总空、首页已实现 0.00（0 笔）、首页持仓只数期权 1；期权列表只有期权、股票列表只有股票；加入股票记录前后已平仓/盈亏汇总/首页已实现/期权持仓/期权列表的 JSON 完全相同。E2E：Q61 新文案、"未冻结"、已平仓 `共 0 笔平仓` `0.00`、`暂无平仓记录`、首页 `0.00`/`0`/持仓 1 |
| 14 | 持仓页"卖出"与跳转 | —（前端） | Pass — `AC14 …` | 卖出预选类型、预填 IXQZ；50 股 400.00 → +24.84、成本 375.16；IXQZ 100 / 750.33；点标的 → `/stocks?symbol=IXQZ` 筛选；列表中点标的同样筛选；资金页 S2 流水"在股票交易中修改" → `/stocks` 能找到 S2 |
| 15 | 自动刷新 | —（前端） | Pass — `AC8 修改 S2 金额…`、`录入：…`、`AC9 删除…` | 用导航链接切换页面（不整页刷新）：新增/修改/删除后股票列表、股票持仓、现金汇总、资金流水、审计记录都显示新数据 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 周末（星期日 03:00）成交允许，不检查交易日/时段 | Pass | API `emptyListLateNightAndWeekend` |
| 深夜 23:30 美东成交按美东日期（11-05）筛选，UTC 已是 11-06 | Pass | API |
| 卖光后再买入是新持仓，FIFO 从新批次开始 | Pass | API `sellOutThenBuyAgain` |
| 同一时刻已有出金在前、新股票买入在后，按录入顺序检查现金 | Pass | API `sameTimeCashOrder` |
| 卖出改小实收导致之后出金为负 → `修改后 … −0.01` | Pass | API |
| 空白备注存为 null；备注进入流水 `remark` 与持仓批次 `note` | Pass | API `noteFlowsToLedgerAndLots`；E2E 列表备注列 |
| 每股 4 位 HALF_UP（7.5033、3.3333、6.6667、1000000.0000） | Pass | API `q88PricePerShareRounding`、`ac11Validation` |
| 缺 `X-Account-Id` → `ACCOUNT_REQUIRED`；不存在的账户 → `ACCOUNT_NOT_FOUND` | Pass | API `accountHeader` |
| 审计只给 `objectId` → `VALIDATION_ERROR`；`objectType=STOCK_TRADE` 只返回股票交易 | Pass | API `auditFilter` |
| 后端 500：股票页、持仓页股票部分显示错误提示；录入提交显示错误并保留输入；恢复后正常 | Pass | E2E（`page.route` 模拟） |
| 未修改过的股票记录按对象筛选审计 → "股票交易 #3 录入后没有被修改或删除过" | Pass | E2E |
| `reset` 清空所有账户的股票记录、id 从 1 开始 | Pass | E2E |

## 发现的 Bug
无。

## 观察 / 疑问（不影响结论）
- 前端控制台有 antd 警告 ``[antd: Table] `expandable.expandIcon` is deprecated. Please use `components.ExpandIcon` instead.``（股票列表、持仓表格）。不影响功能，供 frontend-dev 参考。
- 录入股票交易成功后表单清空，**类型也被清空**（需求说"类型无默认值"，未要求保留上一次类型）。连续录入多笔需要每次重新选类型，如果希望保留请告诉 PM。
- 资金页"在股票交易中修改"链接到不带筛选的 `/stocks`（frontend 已知限制）；验收 14 只要求"能找到 S2"，已满足。
- 买入现金不足的文案总是 `需要 X；<时间> 时可用现金将变为 Z`（即使不足的就是本笔时点），而出金在本笔时点不足时是 `需要 X，可用 Y`。按验收 5 原文实现，契约已注明，这里只是记录两者不同。

## FEAT-040 后续变化（2026-11-13）
- 上表验收 13 中"已平仓 0、盈亏汇总空、首页已实现 0.00（0 笔）"以及"加入股票记录前后已平仓/盈亏汇总/首页已实现的 JSON 完全相同"已被 FEAT-040 验收 15 取代：现在已平仓、盈亏汇总、首页包含股票卖出（场景 S + STO 5 张 IXQZ 9 C：`共 2 笔平仓` `+141.51`，首页 `+141.51（2 笔）`、期权持仓 1、股票持仓 2 个标的）。系统测试 `ac13NoOptionInteraction`、`optionNumbersUnchangedByStocks`（改为验证 `type=OPTION` 与汇总 `option` 拆分不变、合计包含股票）和 E2E `AC13 …` 已按新口径改写。
- 股票页说明（验收 2 的 E2E）改为"……股票已实现盈亏同时计入已平仓页、盈亏汇总和首页"（FEAT-040 验收 17），E2E 已更新。
