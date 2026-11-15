# FEAT-019: 组合 Greeks 汇总

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-018（每行 Greeks 持仓值，已 Verified）；FEAT-016（标的价、手动覆盖）；FEAT-017（"未计入"提示的做法）；FEAT-011（股票持仓）、FEAT-033
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
作为一个期权交易者，我想按标的看到我所有期权和股票加起来的 Greeks，以及整个账户的合计，尤其是"Delta 美元敞口"，以便一眼知道：标的涨跌时我整体相当于持有多少股、多少美元的股票，每天时间流逝大约赚 / 亏多少。

## 白话解释（页面上作 `?` 悬停说明，沿用 Q169 的做法）
- **组合 Greeks**：把同一标的的所有期权持仓 Greeks（FEAT-018 的持仓值）和股票加在一起，看这个标的整体的敏感度。
- **股票 Delta**：每股股票的 Delta 固定为 1，持有 100 股 = Delta +100；股票没有 Gamma / Theta / Vega。
- **净 Delta（股数）**：期权 Delta + 股票 Delta = 这个标的整体相当于持有多少股（正 = 涨了赚，负 = 跌了赚）。
- **Delta 美元敞口** = 净 Delta 股数 × 标的价 ≈ 相当于持有多少美元的这只股票。例：净 Delta +87.21、标的价 52.40 → 约 +4,569.80，即标的涨 1%，大约赚 45.70。
- 不同标的的"股数"不能直接相加（1 股 QRTV ≠ 1 股 IXQZ），美元敞口、Theta、Vega 都是美元，可以跨标的相加（Q177）。

## 已定的口径（只引用）
- Q137：Greeks 只来自 CBOE。Q163 / Q164：行的持仓值口径与空头取反。Q166：行的持仓值 2 位小数、HALF_UP。
- Q140 / Q141 / Q151：标的价可手动覆盖；拉取失败保留上次值；只剩过期合约的标的显示最后标的价。
- Q156 / Q171：已过期未处理不计算；到期日当天正常。Q160：只合计能算的，注明"N 个未计入"。Q170：手动 mark 时 Greeks 仍按 CBOE。Q172：单个字段缺失显示 `—`。
- Q165：合计放在本需求。Q161：场景只用 multiplier 100。
- 本需求的 Q174–Q182 已定（均按建议），结论见下方规则和页面中的引用。

## 规则
1. **分组**（Q174）：当前账户中，有期权或股票持仓的每个标的一组（只有股票、只有期权都算）。不随持仓页到期过滤变化。
2. **期权部分**（Q176）：该标的所有可计算期权行的 Delta / Gamma / Theta / Vega 持仓值（FEAT-018）分别相加。
3. **股票 Delta** = 该标的持股数 × 1；不需要报价，股票无现价也照常计入。
4. **净 Delta** = 期权 Delta 合计 + 股票 Delta。
5. **Delta 美元敞口** = 净 Delta × 标的价（持仓页显示的标的价，含手动覆盖、行情未更新时保留的上次价，Q178）。手动标的价时标注 `按手动标的价`。标的价为空时显示 `无标的价`，不计入全组合（Q179）。
6. **排除**（Q180）：
   - FEAT-018 状态为 `无报价` 或 `已到期，待处理` 的期权行不计入，该组注明 `N 个持仓无报价 / 已到期待处理，未计入`（文字格式同 FEAT-017 规则 7）。
   - 某行单个 Greek 缺失（Q172）→ 只在该 Greek 上不计入，注明如 `Gamma、Vega：1 个持仓缺值，未计入`。
   - 一个标的的期权行全部被排除 → 期权部分显示 `—`；有股票时股票 Delta、净 Delta（= 股票 Delta）、美元敞口照常。
7. **全组合合计**（Q177）：各标的 Delta 美元敞口、Theta、Vega 分别相加；不显示跨标的的 Delta 股数和 Gamma 合计。规则 5 / 6 排除的部分不计入，合计处汇总提示（如 `1 个持仓无报价，未计入`、`1 个标的无标的价，美元敞口未计入`）。
8. **精度**（Q181）：用页面上已取整的行值（2 位）相加；美元敞口 = 净 Delta × 标的价，四舍五入到分（HALF_UP）；全组合 = 各标的取整后的值相加。所有值带显式正负号（0 为 `0.00`），美元敞口带千分位。
9. 所有计算只在后端做；前端只显示。

## 需要的数据和操作（给 backend）
- 当前账户的按标的汇总列表，每个标的：标的代码；期权 Delta / Gamma / Theta / Vega 合计（规则 2、6）；股票 Delta（规则 3）；净 Delta（规则 4）；标的价及是否手动；Delta 美元敞口或"无标的价"状态（规则 5）；该组未计入的个数与原因、缺值的 Greek 与个数（规则 6）。
- 全组合：Delta 美元敞口、Theta、Vega 合计；未计入的持仓个数（分原因）、无标的价的标的个数（规则 7）。
- 只读、实时计算，不需要新的存储和写操作；行情刷新、手动覆盖、交易录入后重新查询即得到新值。

## 页面与交互（给 frontend）
- **位置**（Q175）：持仓页，跟随 FEAT-018 的 `显示 Greeks` 开关（Q173，默认关闭）；打开后在期权持仓表上方显示区块 `按标的 Greeks 汇总`。
- **表格**：每个标的一行，列：`标的`、`期权 Delta`、`股票 Delta`、`净 Delta`、`标的价`、`Delta 美元敞口`、`Gamma`、`Theta`、`Vega`。规则 5 的 `按手动标的价` / `无标的价`、规则 6 的未计入 / 缺值文字以灰色小字显示在该行。
- **合计行**：表格底部 `全组合`：只填 `Delta 美元敞口`、`Theta`、`Vega`，其它列留空；规则 7 的提示灰字显示在合计行下方。
- **说明**：`净 Delta`、`Delta 美元敞口`、`股票 Delta` 列头有 `?`，悬停显示上面白话解释的对应一句；区块上方一行灰字 `按标的汇总当前账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考`。
- 切换账户、立即刷新、设置 / 清除手动值、写操作后随持仓重新查询更新（沿用 FEAT-017 / 018 的刷新方式）。

## 验收标准
场景（所有条目共用）：沿用 FEAT-018 场景——今天 2026-11-23（星期一）10:00 拉取成功，标的价 QRTV 52.40、IXQZ 61.10，multiplier 100，CBOE 值同 FEAT-018 表格。FEAT-018 已验证的行持仓值：

| 持仓 | Delta | Gamma | Theta | Vega |
|------|-------|-------|-------|------|
| A-P1 STO 1 QRTV 2026-12-18 50P | +30.21 | −5.12 | +4.56 | −7.11 |
| A-P2 BTO 2 QRTV 2026-12-18 55C | +57.00 | +9.60 | −7.80 | +13.00 |
| A-P3 STO 1 IXQZ 2026-12-18 65C | −18.35 | −3.13 | +2.47 | −5.03 |
| A-P4 STO 1 QRTV 2026-12-18 47.5P | 无报价 | | | |
| B-P5 STO 3 QRTV 2026-12-18 50P | +90.63 | −15.36 | +13.68 | −21.33 |

股票：账户 A 持 IXQZ 100 股；账户 B 持 QRTV 100 股。验收均指 `显示 Greeks` 打开后。

1. **Given** 场景 **When** 打开账户 A 持仓页 **Then** QRTV 行：期权 Delta `+87.21`、股票 Delta `0.00`、净 Delta `+87.21`、标的价 `52.40`、美元敞口 `+4,569.80`、Gamma `+4.48`、Theta `−3.24`、Vega `+5.89`，行下 `1 个持仓无报价，未计入`。
2. **Given** 场景 **Then** 账户 A 的 IXQZ 行：期权 Delta `−18.35`、股票 Delta `+100.00`、净 Delta `+81.65`、标的价 `61.10`、美元敞口 `+4,988.82`、Gamma `−3.13`、Theta `+2.47`、Vega `−5.03`，无未计入提示。
3. **Given** 场景 **Then** 账户 A 全组合：美元敞口 `+9,558.62`、Theta `−0.77`、Vega `+0.86`，净 Delta / Gamma 列为空；下方 `1 个持仓无报价，未计入`。
4. **Given** 场景 **When** 切换到账户 B **Then** 只有 QRTV 一行：期权 Delta `+90.63`、股票 Delta `+100.00`、净 Delta `+190.63`、美元敞口 `+9,989.01`、Gamma `−15.36`、Theta `+13.68`、Vega `−21.33`；全组合 `+9,989.01` / `+13.68` / `−21.33`，无提示。
5. **Given** 场景 **When** 10:05 把 QRTV 标的价手动设为 52.00 **Then** 账户 A 的 QRTV 美元敞口 `+4,534.92`（标注 `按手动标的价`），全组合 `+9,523.74`；账户 B 的 QRTV `+9,912.76`；各 Greeks 不变。**When** 10:15 QRTV 拉取失败 **Then** 仍为上述值；**When** 清除手动值 **Then** 恢复验收 1–4 的值，标注消失。
6. **Given** 场景 **When** 10:15 拉取成功，P2 的 gamma 和 vega 为 null，其余不变 **Then** 账户 A 的 QRTV：Gamma `−5.12`、Vega `−7.11`，行下另注 `Gamma、Vega：1 个持仓缺值，未计入`；Delta、Theta、美元敞口同验收 1；全组合 Vega `−12.14`，Theta `−0.77`。
7. **Given** 场景，账户 A 另有 P6：STO 1 张 QRTV 2026-11-20 45P（已过期未处理）和 P7：STO 1 张 QRTV 2026-11-23 52P（今天到期，delta −0.4500 / gamma 0.1200 / theta −0.0800 / vega 0.0150） **When** 查看 **Then** QRTV 行：期权 Delta `+132.21`、净 Delta `+132.21`、美元敞口 `+6,927.80`、Gamma `−7.52`、Theta `+4.76`、Vega `+4.39`，行下 `1 个持仓无报价、1 个持仓已到期待处理，未计入`；全组合 `+11,916.62` / `+7.23` / `−0.64`。
8. **Given** 行情库为空（IXQZ 从未拉取成功、无手动标的价），账户 C 只持 IXQZ 股票 100 股 **When** 打开账户 C 持仓页 **Then** IXQZ 行：期权各列 `—`、股票 Delta `+100.00`、净 Delta `+100.00`、标的价和美元敞口 `无标的价`；全组合 `0.00` / `0.00` / `0.00`，下方 `1 个标的无标的价，美元敞口未计入`。**When** 手动设 IXQZ 标的价 60.00 **Then** 美元敞口 `+6,000.00`（`按手动标的价`），全组合 `+6,000.00`，提示消失。
9. **Given** 场景 **When** 在账户 A STC 2 张 P2 **Then** QRTV 行：期权 Delta `+30.21`、美元敞口 `+1,583.00`、Gamma `−5.12`、Theta `+4.56`、Vega `−7.11`；全组合 `+6,571.82` / `+7.03` / `−12.14`。**When** 再 BTC P3 并卖出全部 IXQZ 股票 **Then** IXQZ 行消失。
10. **Given** 场景，`显示 Greeks` 关闭 **Then** 持仓页没有 `按标的 Greeks 汇总` 区块。**When** 打开开关并悬停 `Delta 美元敞口` 列头的 `?` **Then** 显示美元敞口的白话解释；**When** 到期过滤选"今天到期" **Then** 汇总与验收 1–3 相同。

## 边界情况
- **covered call**：股票和卖出的 call 在同一组相抵（验收 2）；被指派生成的股票自动进入该标的的股票 Delta。
- **只剩已到期待处理期权、没有股票的标的**：期权部分 `—`（规则 6），美元敞口 `—`（净 Delta 无可算部分），只显示未计入提示；处理后该组消失。
- **指数期权**（Q149）：期权行都 `无报价`、通常也无标的价 → 规则 6；可手动 mark，但 Greeks 仍为 `无报价`（Q170），不计入。
- **手动 mark**：不影响 Greeks 和美元敞口（Q170）；只有手动标的价影响美元敞口（规则 5）。
- **净 Delta 为 0**：美元敞口 `0.00`。
- **拆股调整合约**：无报价 → 规则 6；拆股见 FEAT-028。
- **同一标的多个账户**：各账户各算各的（验收 1 vs 4）。

## Out of Scope
- 跨账户合并（FEAT-035，Q174）；按策略组的 Greeks（M4）。
- Gamma 美元化、"标的涨 1% 时盈亏"等情景分析（Q182）；Beta 加权 Delta（例如换算成 SPY 等价）。
- 股票的 Gamma / Theta / Vega（恒为 0，不显示）；Rho（Q167）。
- 首页显示；Greeks 历史 / 图表；非 100 multiplier 场景（Q161）。

## Open Questions
无（Q174–Q182 已定）。

## Implementation Notes

### Backend
- **契约**：`docs/api/FEAT-019.md`（新接口 `GET /api/positions/greeks-summary`，要求 `X-Account-Id`，无参数）。契约定稿后实现未改契约。
- **新增文件**：
  - `backend/src/main/java/com/optionmgr/valuation/domain/GreeksSummary.java`：纯 Java 领域计算（分组、排除 / 缺值计数、净 Delta、`exposureStatus` 判定、取整、提示文字）
  - `backend/src/main/java/com/optionmgr/valuation/web/GreeksSummaryController.java`
  - 测试 `backend/src/test/java/com/optionmgr/valuation/domain/GreeksSummaryTest.java`（12 个）、`backend/src/test/java/com/optionmgr/valuation/web/GreeksSummaryControllerTest.java`（9 个，验收 1–9 + 空账户 / 错误 + 与持仓行一致）
- **修改**：`valuation/service/UnrealizedValuationService.java` 加 `greeksSummary(accountId)`；`docs/architecture.md` 加"组合 Greeks 汇总"一节。
- **设计决定**：
  - 每个期权行调用与持仓接口同一个 `UnrealizedValuationService.greeks(...)`，一次请求只取一个 `QuoteBook`；标的价用 `book.underlying(symbol)`，与持仓行 `underlyingQuote.price` 同源（含手动、失败保留、Q151）。
  - Q181：用已取整的行持仓值精确相加；敞口 = 净 Delta × 标的价 `setScale(2, HALF_UP)`；全组合为各组 scale-2 值相加。
  - 某 Greek 无任何行贡献 → `null`（页面 `—`）；只剩已到期待处理期权、无股票 → `NO_DELTA`，不计入"无标的价"。
  - 全组合也给 `missingLabel`（Delta / Theta / Vega），验收 6 的全组合下方有 `Vega：1 个持仓缺值，未计入`（协调方已确认）。
  - `excludedLabel` 复用 FEAT-017 `UnrealizedSummary.excludedLabel`。
- **已知限制**：汇总与持仓表是两个请求，若之间恰好行情刷新可能短暂不一致（与 FEAT-017 合计相同）；跨账户合并留给 FEAT-035。

### Frontend
- **新增**：
  - `frontend/src/api/greeksSummary.ts`（`getGreeksSummary`、`greeksSummaryQueryKeys.all = ['greeks-summary']`）；`src/api/types.ts` 加 `GreeksSummaryResponse` / `GreeksSummaryGroup` / `GreeksSummaryPortfolio` / `GreeksExposureStatus`
  - `frontend/src/components/greeks/GreeksSummaryCard.tsx`：持仓页 `按标的 Greeks 汇总` 区块（`useAccountQuery`；loading / 错误 + `重新加载汇总` / 空态 `暂无持仓`）
  - `frontend/src/components/greeks/greeksSummaryLabels.ts`：标题、说明、`?` 白话解释文案，带符号格式（`formatMoney(…, { signed: true })`）、标的价 / 敞口 / 行提示 / 全组合提示的显示规则（只格式化，不计算）
  - 测试：`src/components/greeks/greeksSummaryLabels.test.ts`、`src/pages/GreeksSummary.test.tsx`（验收 1–6、8–10 与 NO_DELTA、空账户、错误重试，mock 后端按契约示例）、`src/components/quote/marketDataRefresh.test.ts`
- **修改**：
  - `PositionsPage.tsx`：`显示 Greeks` 打开时在开关行与期权持仓表之间挂载 `GreeksSummaryCard`（关闭时不挂载，不发请求）；不受到期过滤影响
  - `GreeksCells.tsx`：抽出 `HelpIcon`（圆圈 `?` + tooltip），FEAT-018 列头与本区块共用（FEAT-018 testid 不变）
  - 失效：`refreshAfterChange`、`refreshQuotes`、作废 / 批量作废 / 调整到期日对话框失败路径，凡失效 `unrealized-summary` 处都同时失效 `greeks-summary`（交易、股票、入出金、到期处理、立即刷新、手动值设置 / 清除后汇总随持仓重新查询）
  - 旧测试同步：`tradeFormSupport.test.ts` 的 queryKey 精确列表加 `['greeks-summary']`；`Greeks.test.tsx` 假后端加汇总接口应答
- **显示**：列 `标的`、`期权 Delta`、`股票 Delta?`、`净 Delta?`、`标的价`、`Delta 美元敞口?`、`Gamma`、`Theta`、`Vega`；数值显式符号 + 千分位（0 为 `0.00`），`null` 为灰色 `—`；标的价原样（`null` 显示后端 `underlyingPriceLabel`）；敞口 `NO_UNDERLYING_PRICE` 显示 `exposureLabel`、`NO_DELTA` 显示 `—`；`标的` 下方按 `manualPriceNote`、`excludedLabel`、`missingLabel` 顺序灰色小字；表格底部 `全组合` 只填美元敞口 / Theta / Vega，下一行依次灰字 `excludedLabel`、`noUnderlyingPriceLabel`、`missingLabel`。标题旁 `?` 解释"组合 Greeks"与跨标的相加。testid 见 `docs/architecture.md` "FEAT-019 testid"。
- **已知限制**：手动标的价的标注按契约用 `manualPriceNote`（仅 `exposureStatus = OK` 时有）；标的价列只在外层 span 上有 `data-manual`，不另显示文字，所以 `NO_DELTA` 组的手动标的价页面上无标注。汇总与持仓表是两个请求，同一批失效里各自重新查询。
