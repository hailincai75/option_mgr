# FEAT-012 测试报告

- **测试日期:** 2026-11-13（E2E 运行时美东约 10:00，星期五）
- **结论:** Verified
- **后端集成测试:** `backend/src/test/java/com/optionmgr/systemtest/Feat012ExpireWorthlessSystemTest.java`（38 个用例，固定/可调时钟 `MutableClock`，默认 2026-11-23 10:00 美东）
- **E2E 测试:** `e2e/tests/feat-012-expire-worthless.spec.ts`（11 个用例，真实时钟），辅助 `e2e/support/ny.ts`

## 运行结果
| 套件 | 命令 | 结果 |
|------|------|------|
| 后端构建（含全部单元测试与 systemtest） | `cd backend && gradlew.bat build` | BUILD SUCCESSFUL，615 个测试 0 失败（其中 systemtest 342 个，FEAT-012 38 个） |
| 前端检查 | `cd frontend && npm run lint && npm run typecheck && npm test` | 通过；Vitest 21 个文件 370 个测试全部通过 |
| E2E 全部 | `E2E_BACKEND_PORT=28080 E2E_FRONTEND_PORT=25173 npx playwright test` | 第 1 次：170 passed / 3 failed——feat-004 AC8 是我改的用例定位器抓到了 antd 正在淡出的旧错误（已修复）；feat-002 AC2、feat-033 AC5–AC7 单独重跑通过（满负载下的偶发超时，与 FEAT-012 无关）。第 2 次（修复后）：**173 passed，0 failed**（7.9 分钟） |
| E2E FEAT-012 | 同上，`tests/feat-012-expire-worthless.spec.ts` | 11 passed |

## E2E 的日期做法
- 真实时钟无法走到 2026-11-20 17:30，所以界面流程（验收 1–3、5、7、11–13）用**同样的合约和金额**，把到期日换成一定已过去的 **2026-11-06**（星期五），开仓时间相应提前到 10-27 ~ 10-30。"已过期 N 天"按运行当天计算；首页"本月"只在运行月份为 2026-11 时期望非零。
- 验收 4、9 的 E2E 按需求用相对运行当天美东日期 T 的日期，并按运行时刻（美东 16:00 前 / 16:00–17:30 / 17:30 起）选择断言分支（`support/ny.ts` 的 `stablePhase()`，距离边界不足 45 秒时先等过边界）。**本次运行在美东约 10:00，所以只走了"16:00 前 → 只有平仓"分支**；"到期待确认"和"今天到期可作废"两个分支由后端测试 `ac4_buttonsOnExpirationDay`（15:59 / 16:00 / 17:29 / 17:29:59 / 17:30 / 次日）和前端单元测试覆盖，E2E 需要在 16:00 之后再跑一次才会走到。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 持仓页按钮（到期后、周末之后） | Pass — `ac1_positionsAfterExpiry` | Pass — `AC1+AC2+AC13 …` | 4 行顺序 IXQZ / QRTV 25 P / QRTV 30 C / XYZ；都已过期 3 天（E2E 为按当天计算的 N 天，红色）；`closable=false`、`expiryPending=false`、`expirable=true`；只有"作废"按钮；`expiryContracts`、`expiryAt=2026-11-20T21:00:00Z`、`expiryAtLocal`；首页已过期未处理 4、冻结 13,500.00 |
| 2 | 空头 put 多批次作废 | Pass — `ac2_shortPutMultiLot` | Pass — `AC1+AC2+AC13 …` | 响应逐字段对照契约（`action=EXP`、`netAmount/cashChange="0.00"`、`frozenChange=-13500.00`、`realizedPnl=370.00`、`expiredDirection=SHORT`、`note=null`、`hasAuditLog=false`、两个 `coveredLots`）；数据库 `traded_at = 2026-11-20T21:00:00Z`；汇总 20,266.69 / 0.00 / 20,266.69；流水 7 条，首条 OPTION_EXPIRE、0.00、冻结 −13,500.00、余额 20,266.69、说明正确、`ledgerEntryId` 对应；交易列表 6 条，EXP 行在首，只有"删除"；已平仓 1 行（每张 +123.33、平仓 0.00 / 每张 0.00、+370.00 绿），展开 E1、E3；审计无新记录；按 id 查询与 POST 响应相同。E2E：确认框各项、没有输入框（Q84）、结果 +370.00（绿）/ −13,500.00 / 两行批次 |
| 3 | 多头 call、空头 call、多头 put 作废 | Pass — `ac3_otherDirections` | Pass — `AC3 …` | −85.65（LONG，红）、+22.34（冻结 —）、−40.00（E5 2 张）；三者 `ledgerEntryId=null`，流水仍 7 条；已平仓 共 4 笔 +266.69，顺序 QRTV 25 P / IXQZ / QRTV 30 C / XYZ；盈亏汇总按标的、按月；首页总计、本月、持仓 0、冻结 0.00、已过期 0；交易列表筛选 EXP → 4、STO → E4 E3 E1、EXP+BTO 多选；已平仓筛选 EXP → 4 +266.69、BTC → 无；日期筛选按 cutoff 的美东日期 |
| 4 | 到期日当天的按钮（16:00 / 17:30） | Pass — `ac4_buttonsOnExpirationDay`、`dstExpiration` | Pass — `AC4 E2E（真实时钟）…`（本次走 16:00 前分支） | 11-17：只平仓；15:59：今天到期、只平仓，作废被拒"到期日（2026-11-20）美东 17:30 起才能作废"、什么都不变；16:00:00：到期待确认；17:29、17:29:59：到期待确认、作废被拒；11-21：已过期 1 天、只作废；17:30:00：只作废，作废成功记在 21:00Z。夏令时 2027-03-19：cutoff 20:00Z、17:30 = 21:30Z。E2E：<T−1> 合约已过期 1 天只有作废，作废 −40.00 记在 T−1 16:00 |
| 5 | 晚几天才点：盈亏归到期月、冻结从 16:00 起释放、删除被 Q19 拒 | Pass — `ac5_lateClickAndQ19` | Pass — `AC5 删除作废被 Q19 拒绝…` | 出金 1,500 先被拒；作废记在 11-27 16:00，+95.00；首页本月 2026-12 0.00（0 笔）、总计 +95.00（1 笔）；按月只有 2026-11；再出金成功 3,595.00；流水顺序 出金 / 到期作废 / 开仓 / 入金；删除作废被拒"可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00"，不写审计；先删出金再删作废后恢复已过期 4 天。E2E：删除对话框显示原文与错误码、不关闭；删出金再删作废成功 |
| 6 | 先部分平仓，再作废剩余 | Pass — `ac6_partialCloseThenExpire` | —（后端覆盖） | BTC 2 张 +180.00；持仓 1 张 E3、冻结 4,500.00；作废 1 张 +130.00、−4,500.00；20,206.69 / 0.00 / 20,206.69；已平仓 XYZ 两行，作废在上 |
| 7 | 删除作废后重新作废 | Pass — `ac7_deleteAndReExpire`、`deleteExpiryWithoutLedger` | Pass — `AC7+AC13 …` | 恢复 20,266.69 / 13,500.00 / 6,766.69，持仓 3 张 2 批 +370.00；流水、已平仓、交易列表中消失，按 id 404；审计 DELETE、摘要 `到期作废 XYZ 2026-11-20 45 P 3 张`、before 全部字段、`after=null`、`changes=[]`；再作废新 id、`hasAuditLog=false`。E2E：确认框标题"删除到期作废 #6"、摘要 `到期作废 3 张 XYZ 2026-11-06 45 P，2026-11-06 16:00，0.00`、提示"删除后持仓恢复、冻结恢复，会写入审计记录。"；审计页展开修改前：方向到期作废、3、100、0.00、16:00、0.00、−13,500.00；新 EXP 行没有"历史" |
| 8 | 作废之后补录、修改、删除之前的交易 | Pass — `ac8_editCoveredNet`、`ac8_deleteOrShrinkCoveredOpen`、`ac8_backfillLaterOpenThenExpireAgain`、`ac8_backfillsRejected`、`ac8_expiryNotEditable` | —（后端覆盖） | 改净额 → +375.00、20,271.69；删除 E3 / E1 改 1 张 → `CLOSE_EXCEEDS_POSITION` 原文；补录较晚开仓 → 原作废不变、新批次可再作废 +110.00、两条 EXP 后作废的在上；删除较早那条 → `CLOSE_NOT_WHOLE_LOTS`（契约示例原文）；补录较早开仓 → `CLOSE_NOT_WHOLE_LOTS`"…可为 2 张、4 张或 5 张"；补录 BTC → `CLOSE_EXCEEDS_POSITION`"…超过当时持仓 1 张"；到期后平仓 → Q36 原提示；PUT 作废 → `EXPIRY_NOT_EDITABLE`"到期作废不能修改，记错请删除后重新作废"（请求体非法先 400，不存在 404）；所有拒绝都什么都不变、不写审计 |
| 9 | 成交时间不能晚于到期日 16:00；旧的 16:00 之后交易 | Pass — `ac9_closeUpToCutoffAllowed`×3、`ac9_afterCutoffRejected`×3、`ac9_openAtCutoffAndOldMessages`、`ac9_editTimeAroundCutoff`、`ac9_closeAtCutoffAfterExpiry`、`ac9_legacyLateTrade`、`ac9_legacyLateTradeNoteOnly` | Pass — `AC9 E2E（真实时钟）…`、`Q83 修改交易…`、`作废之后录入正好 16:00 的 BTC…` | 15:59、16:00、16:00:00 平仓允许（+210.00）；16:01、16:00:01、23:59 的平仓 / 开仓 → 400 `tradedAtLocal`"成交时间不能晚于到期日（2026-11-20）美东 16:00"；STO 16:00 成功（冻结 4,500.00）；次日平仓 / 开仓仍是原提示；修改 E3 到 16:01 被拒不写审计、16:00 成功写审计；作废后 16:00 的 BTC → `NO_POSITION_TO_CLOSE`。旧的 16:10 BTC（直接写库）：照常显示、不挡其它写操作；**按需求原检查次序**：请求 3 张（`expiryContracts`）→ `EXPIRY_BLOCKED_BY_LATE_TRADE` 原文，请求 1 张 → `POSITION_CHANGED`"持仓已变化（当前 3 张），请刷新后重试"；改到 15:50 后作废 1 张 E3 +130.00 / −4,500.00；只改备注（Q85）成功写审计；改到 16:05 被拒；删除它后可作废。E2E：录入页 STC T−1 16:01 的错误显示在成交时间字段下，改 16:00 成功 −20.00；修改对话框同样显示在成交时间字段下、不关闭 |
| 10 | 按账户隔离 | Pass — `ac10_accounts`、`expireContractOnlyInOtherAccount` | —（后端覆盖） | 默认账户作废不影响"小账户"（持仓、汇总、已平仓空、首页已过期 1）；小账户作废 +100.00；跨账户 DELETE / PUT / GET → 404 `NOT_FOUND`、什么都不变；合约只在别的账户有持仓 → `NO_POSITION_TO_EXPIRE` |
| 11 | 冻结按批次相加、multiplier ≠ 100、连带改正写入审计 | Pass — `ac11_lotsFrozenAndSideEffect` | Pass — `AC11 multiplier 1 …` | −24.70、+1.00、流水说明带"（multiplier 1）"；G1 改 2 张 → G1 冻结 24.69，作废只覆盖 G1、−24.69、+0.50，流水同步；G2 成为持仓 12.35；101.00 / 12.35 / 88.65；审计 `张数：1 → 2`、`冻结变动：+12.35 → +24.69`，连带变化 `到期作废 #<id> 冻结变动 −24.70 → −24.69`（action EXP）。E2E：确认框 `2 张（全部剩余）（multiplier 1）`、交易列表 `2（multiplier 1）`、流水说明 |
| 12 | 没有持仓、重复作废、对话框期间持仓变化 | Pass — `ac12_noPositionRepeatAndChanged`、`fullyClosedAndCheckOrder` | Pass — `AC12 …` | 50 P → `NO_POSITION_TO_EXPIRE` 原文；补录后请求 3 / 0 张 → `POSITION_CHANGED`"当前 4 张"；4 张 → +480.00 / −18,000.00；重复作废 → 没有持仓。E2E：打开对话框后另行补录，确认 → 原文 + 错误码、对话框不关闭、背后列表刷新为 4 张；重新打开 4 张作废成功 |
| 13 | 自动刷新 | —（前端） | Pass — `AC1+AC2+AC13 …`、`AC7+AC13 …` | 用 `window.__noReload` 标记确认没有整页刷新：作废后首页、资金、交易列表、已平仓、盈亏汇总、审计都显示新数字；删除后同样 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 夏令时到期日 2027-03-19：16:00:01 EDT 平仓被拒、16:00:00 开仓允许；17:30 前作废被拒；作废记在 `2027-03-19T20:00:00Z` | Pass | API `dstExpiration` |
| 到期日是假日（2026-11-26 感恩节）：作废仍记在该日 16:00（`21:00:00Z`） | Pass | API `holidayExpiration` |
| 作废后录入正好 16:00 的反方向开仓（多头 call 作废后 STO）成为新批次；删除该作废 → `POSITION_DIRECTION_CONFLICT`；新批次可再作废（SHORT +5.00）；同一时间 id 大的在列表上方 | Pass | API `openAtCutoffAfterExpiryThenDeleteExpiry` |
| 已全部平仓的合约作废 → `NO_POSITION_TO_EXPIRE`；未到期又没有持仓的合约先报 17:30 | Pass | API `fullyClosedAndCheckOrder` |
| 作废请求校验：缺张数"张数不能为空"、−1 / 1.5"张数必须是非负整数"、100001"张数过大"、缺标的"标的不能为空"、类型 `P` / JSON 语法错误 → `BAD_REQUEST`；字段校验先于 17:30 检查 | Pass | API `contractsValidation`、`requestValidation` |
| 标的大小写与空格、行权价 `45.000` 规范化；`note` / `tradedAtLocal` / `netAmount` / `multiplier` / `action` 被忽略 | Pass | API `normalizationAndIgnoredFields` |
| 开仓、平仓接口收到 `action: EXP` → 400 各自原文案 | Pass | API `expActionOnTradeEndpoints` |
| 删除无流水的作废（多头 call）：流水条数不变；审计 `before.frozenChange = 0.00` | Pass | API `deleteExpiryWithoutLedger` |
| 后端 500 / 网络失败：作废对话框显示错误、不关闭、什么都没保存 | Pass | E2E |
| 交易列表 EXP 行净额 `0.00` 不着色；流水 OPTION_EXPIRE 行只有"在交易列表中修改"链接，没有修改/删除/历史 | Pass | E2E |

## 对已有测试的修改（FEAT-012 改变了的行为）
- `Feat001OpenTradeValidationSystemTest.tradeDateIsNewYorkDate`：到期日 23:59 / 21:00 / 16:00:01 开仓改为断言被拒（16:00 提示），新增 16:00:00 允许。
- `Feat004CloseTradeSystemTest.ac8_expiry`、`expiryNewYorkDateBoundary`：到期日 16:30 / 23:59 / 16:00:01 平仓被拒，16:00 成功。
- `Feat005EditDeleteAuditSystemTest.expiryRulesOnEdit`：平仓时间改到到期日 23:59 被拒（不写审计），改到 16:00 成功。
- `Feat002TradeListSystemTest`、`Feat006ClosedTradesSystemTest`：方向筛选文案加 `EXP`。
- `Feat033MigrationSystemTest`：最新迁移版本不再写死，改为"等于 `db/migration` 中最大的 `V<n>`"。
- `HasAuditLogSystemTest.sameIdDifferentObjectType`：原来循环最多 500 次让交易 id 与入金 id 对齐；新增的 FEAT-012 测试在同一个内存库里录入了大量交易，两个序列相差超过 500 导致失败（测试夹具问题，不是产品缺陷）。改为把两个自增序列重置到同一个值再各录一条。
- E2E：`feat-004` AC8（16:30 → 被拒、16:00 成功）、AC9（16:00 起没有平仓按钮，按运行时刻分支）；`feat-003` AC6（16:00–17:30 时 DTE 单元格多一个"到期待确认"标签）；`feat-006` 文案含 `EXP`。
- 在 FEAT-001 / 002 / 004 / 005 / 006 / 033 的测试报告中相应位置加了"FEAT-012 已改变"的说明。

## 发现的 Bug
- 无。

## 疑问 / 需求不清楚的地方
- **作废的检查次序**（已确认，不再待定）：按需求原次序测试——"张数与页面一致"先于"旧的 16:00 之后交易"；后端与 API 契约均为该次序。验收 9 请求 3 张（`expiryContracts`）→ 晚于 16:00 的提示；补充：请求 1 张 → `POSITION_CHANGED`"持仓已变化（当前 3 张），请刷新后重试"。均通过。
- E2E 的"到期待确认""今天到期可作废"两个分支依赖运行时刻，本次只在美东上午运行；如需界面上实测这两个分支，请在美东 16:00–17:30 或 17:30 之后再跑一次 `feat-012` / `feat-003` / `feat-004`。
