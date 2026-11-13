# FEAT-038 允许卖出开仓 call（STO call），不冻结现金

**没有新接口、没有新字段、没有数据库迁移**；只是规则变化（Q16 被取代）：
- `POST /api/option-trades` 接受 `action = STO` + `optionType = CALL`。检查顺序：字段 → Q7（多空，含之后的交易）→ Q23（multiplier）→ 现金。**不做 Q18 担保检查、不冻结、不检查股票覆盖**（FEAT-011 之前用户自行负责）。本笔 `cashChange` = `+净额`、`frozenChange` = `"0.00"`；流水说明同 FEAT-001，如 `STO 1 张 IXQZ 2026-11-20 7.5 C`。可用现金为 0 时也能卖出 call。
- `SELL_CALL_NOT_SUPPORTED` 不再从任何接口返回。
- `POST /api/option-trades/closes` 用 BTC 平空头 call：规则同 FEAT-004；被平批次的 `frozen` 都是 `"0.00"`，所以 `frozenChange` = `"0.00"`；现金检查只看"本笔时点可用现金 ≥ 净额"并按 Q19 重放之后各时点。不足时 `CASH_INSUFFICIENT` 文案为 `可用现金不足：需要 30.00，可用 25.00`（没有"+ 释放冻结"部分）。
- `PUT /api/option-trades/{id}`：修改 STO call 时不做 Q18（Q45 只适用于 STO put）；其余检查不变。
- 持仓（`GET /api/option-positions`）：空头 call 就是 `direction = "SHORT"` 且 `optionType = "CALL"` 的持仓，`frozenCash` 为 `"0.00"`，批次 `originalFrozen` / `remainingFrozen` 为 `"0.00"`；`closeAction = "BTC"`、`allowedCloseContracts`、`closable` 与空头 put 相同。前端的"未冻结"标签（Q62）用 `direction == "SHORT" && optionType == "CALL"` 判断，不另加字段。冻结合计、现金汇总的冻结只来自空头 put。
- 交易列表、已平仓、盈亏汇总、首页：空头 call 照常出现，没有任何字段变化。

