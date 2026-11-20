# FEAT-051 测试报告

- **测试日期:** 2026-11-20
- **结论:** Verified
- **后端集成测试:** backend/src/test/java/com/optionmgr/systemtest/Feat051CloseLotsSystemTestBase.java、Feat051CloseLotsAcceptanceSystemTest.java（11 个）、Feat051CloseLotsBoundarySystemTest.java（20 个）、Feat051CloseLotsMigrationSystemTest.java（1 个）
- **E2E 测试:** e2e/tests/feat-051-choose-lots-to-close.spec.ts（14 个）；共用工具 e2e/support/closeLots.ts

场景按需求：当前时间 2026-11-20 10:00（美东，星期五；后端时钟与浏览器时钟固定），虚构标的 ZZQ（迁移测试另用虚构 ZZR），虚构账户甲（id 1）/ 乙（id 2）。没有调用真实 CBOE / Finnhub。所有被拒的写操作都核对：所有读接口（现金、持仓、股票、盈亏汇总、流水、交易列表、股票交易、审计、已平仓）以及 `option_close_lot` 表内容前后完全相同。

## 验收标准结果
| # | 验收标准 | API 测试 | E2E 测试 | 备注 |
|---|---------|---------|---------|------|
| 1 | 表单列出 A（勾选、FIFO 默认）/ B（未勾），平仓张数：1，方向 BTC | Pass | Pass | API：`lots[].closeDefault` true / false（JSON 布尔），FEAT-048 字段仍在，`allowedCloseContracts` 保留 [1,2]；E2E：提示文字、FIFO 标签只有 1 个、空头 call 无冻结列 |
| 2 | 只选 B、15.10 → 被平批次 B、+22.24、1,040.58/0/1,040.58；剩 A；股票被覆盖 100 可用 100；已平仓 +37.34 / +22.24 展开为 B；按 ID 为 B | Pass | Pass | API 另查响应 = 按 ID = 列表行、`option_close_lot` 一行 (close, B, 账户 1)；E2E 确认标题"被平掉的开仓批次" |
| 3 | 不改默认 → A、+3.24、剩 B +37.34 | Pass | Pass | 剩余 B 变为 closeDefault |
| 4 | 空头 put 只选 P2 → 冻结 −800.00、+11.35、30,041.35/1,600.00/28,441.35、剩 P1；另起 P1+P2 → 3 张、−2,400.00、+31.00、30,031.00/0/30,031.00 | Pass | Pass | API 另查流水行冻结变动 −800.00；E2E 冻结列 1,600.00 / 800.00 |
| 5 | 校验：未开仓 / 不选 / 乙的批次 / 重复 / 已被平掉，原文、数据不变 | Pass | Pass | 不选：E2E 提交按钮不可用且不发请求，API 400 `lotTradeIds=请至少选择一笔开仓批次`；E2E 后端拒绝原文显示、勾选与输入保留 |
| 6 | B 改到 11-19 11:00 / 删 B → 拒绝并指出那笔 BTC 和 B；补录选 B 拒绝；改选 A 10.00 → +8.34，原平仓仍 B +22.24，1,030.58/0/1,030.58 | Pass | Pass | E2E 做了删除与修改时间两项（交易列表） |
| 7 | 修改所选批次 A → B：+22.24、剩 A、现金不变、审计"被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张"；场景 P P2 → P1：2 张、−1,600.00、+20.35、30,041.35/800.00/29,241.35 | Pass | Pass | API：`hasAuditLog` true、审计 `before/after.selectedLots`、P 场景 changes 依次为 被平批次 / 张数 / 冻结变动、流水行 id 不变；E2E：修改表单没有张数输入、审计页改动行与展开快照 |
| 8 | 上线前的数据升级后不变（场景 A + BTC 2 张 241.30 → L1、+457.40、80,817.40/20,000.00/60,817.40、剩 L2、流水与 ID 不变） | Pass | — | 用临时文件库模拟：应用写入旧式（FIFO）数据 → 删掉 V17/V18 的结果回到 V16 → 重启重跑 V17 + V18（Q60 备份 1 个）；两个账户全部读接口逐字相同，回填的 `option_close_lot` 与应用写入的相同；另含 Q28 取整的多批次平仓、乙的多头 STC。场景 A 的金额改用 ZZQ（虚构标的） |
| 9 | 录入交易页 BTC 1 张按 FIFO 平 A（同第 3 条）；全部账户无平仓按钮、已平仓账户甲、展开 B | Pass | Pass | API：FIFO 匹配结果也存为所选批次；ALL 下平仓 / 修改 / 删除 → `ALL_ACCOUNTS_READ_ONLY`，close-lot-choices → `ALL_ACCOUNTS_NOT_SUPPORTED`；E2E：录入交易页确认标题仍为"被平掉的开仓批次（FIFO）"，ALL 交易列表无修改 |

## 额外边界测试
| 场景 | 结果 | 备注 |
|------|------|------|
| 不连续：P1、P2、P3 只选 P1 + P3（请求顺序 [P3, P1]）→ 3 张、−2,400.00、+43.00，coveredLots 按开仓时间；剩 P2 标 FIFO 默认 | Pass | API + E2E |
| Q20 跟随所选批次：可用 51.00，BTC 900.00 选 P2（释放 800）→ CASH_INSUFFICIENT 原文；选 P1（释放 1,600）→ −870.00 | Pass | API |
| 多头 STC 只选较晚的 L2（2 张）→ +8.70、剩 L1 | Pass | API |
| 覆盖：选 LEGACY 批次释放 0 股（Q79）；选受检批次释放 100 股，LEGACY 批次保持未覆盖 | Pass | API |
| 覆盖 + 重放：BTC 选 B 后卖出 200 股；改选 A → COVERAGE_INSUFFICIENT，数据不变 | Pass | API |
| lotTradeIds 不带 / null = FIFO（缺张数 → 张数不能为空）；[] / null 元素 / 0 / −1 / "abc" / 1.5 / 1001 个 | Pass | API |
| 选择模式选默认批次与 FIFO 模式结果完全相同（除 createdAt） | Pass | API |
| 检查顺序：持仓 / 方向先于批次；a（不属于）先于 c（未开仓），即使是后一个元素；平仓记录 / 别的合约 / 不存在的 id → a；b 先于 d；d 先于 e；字段错误先于一切 | Pass | API |
| 到期日当天 16:01 → 字段错误，16:00 成功（Q83 不变） | Pass | API |
| 修改：同一集合不同顺序 → NO_CHANGES；只带 contracts → CLOSE_LOTS_INVALID；改时间使批次未开仓；[] → 400；不带 lotTradeIds 只改净额 → 批次不变 | Pass | API |
| 修改平仓选之后平仓已选的批次 → "修改后，… 已被更早的平仓平掉"；删除之后那笔后可以改选 2 笔，审计多批次用"、"连接；删除平仓的审计 before 带 selectedLots | Pass | API |
| 同一时间按录入顺序：先录入的平仓不能选之后录入的同一时间开仓；之后录入的新平仓可以 | Pass | API |
| 开仓改张数 → 张数与平仓不一致原文；只改净额 / 时间（不越过平仓）→ 允许，平仓盈亏随之变、没有 BTC 连带变化；补录更早开仓 → 允许，不改变已有平仓 | Pass | API |
| FIFO 模式作用于不连续的剩余批次（A、D）；FIFO 补录选到之后平仓的批次 → 第 4 步拒绝 | Pass | API |
| Q260：call 被指派作用于剩余批次 A；补录被指派平掉之后平仓已选的 P1 → "补录后，… 已被更早的被指派平掉"；到期作废作用于剩余批次 | Pass | API |
| close-lot-choices：selected、排除之后平仓已选的批次、?tradedAtLocal、空列表；abc / 404 / 别的账户 404 / 开仓 422 / 时间格式 400 | Pass | API |
| 按账户独立：乙选甲的批次 → 不属于本账户；乙修改甲的平仓 → 404 | Pass | API |
| 后端不可用时提交：显示错误、对话框与勾选保留 | Pass | E2E |

## 契约之外的测试假设如何确认
| 假设 | 结论 | 依据 |
|------|------|------|
| `[1.5]` → 400 `BAD_REQUEST` | Pass | 契约明文（"元素不是整数 → 400 BAD_REQUEST"），后端一致 |
| 被指派补录使之后平仓失效的文案以"补录后，"开头、原因为"已被更早的被指派平掉" | Pass | 契约（`<动作>` 为补录 / 修改 / 删除，原因列表含"已被更早的被指派平掉"）与 FEAT-013 中被指派属于补录，后端一致 |
| 多批次平仓因开仓张数改动失效时，文案指出哪一个批次 | 测试只断言开头与"张数与平仓不一致（所选批次共 3 张，平仓 2 张）"结尾 | 契约没有规定；后端行为是指出按开仓时间最早的所选批次（`ContractReplay` / `TradeRejectionMessages`）。如需固定请 backend-dev 写进契约 |

## 因 Q255（取消 Q39）/ Q256 而改变预期结果的已有测试
都按已批准的决定改，不是放宽断言；每处在测试代码里留了旧预期的注释。

| 测试 | 旧预期 | 新预期 |
|------|--------|--------|
| Feat004 `ac10` | 补录 BTC 1 张（FIFO）201、+229.35，之后那笔改平 b +260.00 | 422 `CLOSE_LOTS_INVALID`"补录后，2026-11-06 10:00 BTC 1 张 … 选中的批次 2026-11-03 10:30 STO 1 张 已被更早的平仓平掉"；改选 b 补录 +240.00，之后那笔仍 a +249.35；现金同前。补录 2 张仍 `NO_POSITION_TO_CLOSE`（不变） |
| Feat004 `backfillMakesLaterCloseExceed` | `CLOSE_EXCEEDS_POSITION` "…将超过当时持仓 1 张" | `CLOSE_LOTS_INVALID` "…BTC 3 张 … 选中的批次 2026-11-03 10:30 STO 2 张 已被更早的平仓平掉" |
| Feat004 `backdatedOpenBreaksLaterClose` | 补录更早 STO → `CLOSE_NOT_WHOLE_LOTS` | 允许；平仓仍 L1 +457.40；批次 [新开仓, L2]；80,917.40/40,000.00/40,917.40 |
| Feat004 `frozenCorrectionOnRematch` | 补录成功，之后那笔改平 c、冻结 −24.70 → −24.69 原地改正 | 补录（FIFO）→ `CLOSE_LOTS_INVALID`；改选 c → −24.69；之后那笔仍 a+b、−24.70、+0.90，流水不改 |
| Feat005 `ac3`（不在原清单） | 只带张数 1 → `CLOSE_NOT_WHOLE_LOTS`；张数 3 → 成功 | 两者都 `CLOSE_LOTS_INVALID`"平仓张数 N 张与所选批次张数之和 2 张不一致"；改用 `lotTradeIds` [L1, L2] 成功，审计先列 被平批次 |
| Feat005 `ac4` | L1 2→3 `CLOSE_NOT_WHOLE_LOTS`；2→1 成功并改平 L1+L2 | 两者都 `CLOSE_LOTS_INVALID`"…张数与平仓不一致（所选批次共 3 / 1 张，平仓 2 张）"，数据与审计不变 |
| Feat005 `ac5` | L1 挪到平仓之后 → `CLOSE_EXCEEDS_POSITION` | `CLOSE_LOTS_INVALID`"…选中的批次 2026-11-06 10:00 STO 2 张 在该平仓成交时还未开仓" |
| Feat005 `ac6` | 删 L1 → `CLOSE_EXCEEDS_POSITION`"（删除后那笔平仓将超过当时持仓 1 张）" | `CLOSE_LOTS_INVALID`"（所选批次 2026-11-03 10:30 STO 2 张）" |
| Feat005 `ac10` | F1 1→2 成功，BTC 冻结 −24.70 → −24.69 写入同一条审计的 sideEffects | `CLOSE_LOTS_INVALID`、不写审计；只改 F1 净额成功，sideEffects 为空 |
| Feat005 `deleteRematches`（不在原清单） | 删前一笔平仓后之后那笔改平 a +80.00 | 仍 b +180.00，a 恢复为持仓；删 b → `CLOSE_LOTS_INVALID`；删 a 允许 |
| Feat006 `ac5` | 补录 Y 后 X 改平 L2 +260.00（改 L2 后 +265.00），Y +229.35 | FIFO 补录被拒；Y 选 L2 +240.00（改 L2 后 +245.00），X 一直 L1 +249.35；合计不变 |
| HasAuditLogSystemTest `cascadeCorrectedRecordNotMarked`（不在原清单，验证时 `gradlew build` 发现） | 改开仓张数使 BTC 冻结被连带改正，平仓不标记 | BTC 不再被连带改正（改开仓张数被拒）；改用仍按 FIFO 重放的 put 被指派：改开仓净额使 ASG 股票成本 3,880.00 → 3,870.00 连带改正，ASG 不标记 hasAuditLog |
| Feat033MigrationSystemTest（不在原清单） | 最高版本只按 `V*.sql` 计算 | 同时计入 Java 迁移 `V*.class`（V18 是 Java 迁移） |
| CashSystemTestBase `emptyLedger`（测试清理） | — | 先 `DELETE FROM option_close_lot` 再删 option_trade（新外键） |
| e2e feat-004 | 张数单选 2/3（默认 2）、AC3 选 3、AC9 选 1；AC10 FIFO 补录成功、之后那笔改平 L2 +260.00 | 批次列表（L1 勾选、FIFO 默认、平仓张数：2）；勾选对应批次；AC10 FIFO 补录 `CLOSE_LOTS_INVALID`，改在持仓页选 L2 +240.00，之后那笔仍 L1 +249.35；新增确认标题检查（持仓页"被平掉的开仓批次"，录入交易页"（FIFO）"） |
| e2e feat-005 | AC3 修改平仓张数 1 被拒 / 3 成功；AC4+5、AC6、AC10 旧文案与连带变化 | AC3 无张数输入、批次列表、加勾 L2 → 3 张，审计含 被平批次；新增后端拒绝（CASH_INSUFFICIENT）保留勾选与输入；AC4+5 / AC6 / AC10 为新文案，AC10 改为只改净额无连带变化 |
| e2e feat-006 AC5 | 同 Feat006 `ac5` 旧预期 | 同 Feat006 `ac5` 新预期，Y 在持仓页对话框录入 |
| e2e feat-010 / 017 / 021 / 023 / 042 / 047 / 048 | `close-trade-contracts-<N>` 单选 | `chooseFifoLots(modal, N)`：勾选原来张数对应的最早整批次，断言不变 |

## 运行结果
- `gradlew.bat build`：BUILD SUCCESSFUL，1596 个测试全部通过
- 前端 `npm run lint && npm run typecheck && npm test`：通过，54 个文件 802 个测试
- E2E feat-051：14 passed
- E2E 受影响的 12 个 spec（feat-004、005、006、010、017、021、023、033、039、042、047、048）：135 passed（3 个 worker，端口 18080+ / 15173+）

受影响 spec 的选择依据（grep `e2e/tests/`）：`close-trade-contracts-`（feat-004、010、017、021、023、042、047、048）；平仓修改的 `trade-edit-contracts` 与 Q39 文案 `将不是整笔开仓 / 将超过当时持仓 / 删除后那笔平仓 / 改平`（feat-004、005、006）；使用持仓页平仓对话框默认批次提交（feat-033、feat-039）。feat-013 / feat-014 的 `CLOSE_NOT_WHOLE_LOTS` / `CLOSE_EXCEEDS_POSITION` 属于作废 / 被指派 / 行权（Q260 不变），未纳入。

## 发现的 Bug
- 无。验证中失败的都是测试代码问题（审计查询包含账户改名记录；E2E 在交易列表页读现金汇总；HasAuditLog 依赖已取消的 BTC 连带改正），已修正后通过。

## 疑问 / 需求不清楚的地方
- 多批次平仓因开仓张数改动失效时，提示指出哪一笔批次契约没有规定（后端指出最早的所选批次），建议 backend-dev 写进契约。
