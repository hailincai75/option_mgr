# FEAT-018: 单合约 Greeks

- **Status:** Verified
- **Priority:** P1
- **Depends on:** FEAT-016（自动行情，已存 iv / delta / gamma / theta / vega / rho）；FEAT-017（持仓页列与标签沿用）；FEAT-003、FEAT-033
- **Scope:**
  - [x] Backend: Done
  - [x] Frontend: Done

## 背景 / 用户故事
作为一个期权交易者，我想在持仓页看到每个期权持仓的 IV 和 Greeks（每张合约的原值，以及按我的张数和方向换算后的持仓值），以便知道这笔仓位对标的涨跌、时间流逝、波动率变化有多敏感。

## Greeks 是什么（通俗解释，页面上作列头悬停说明，Q169）
CBOE 给的都是"**买入 1 股**"视角的值（1 张 = 100 股）。
- **IV（隐含波动率）**：市场预期标的未来一年的波动幅度（年化百分比）；越高，期权越贵。
- **Delta**：标的涨 1 美元，期权每股价格大约变多少。持仓值 ≈ 这笔仓位相当于多少股标的（正 = 看涨，负 = 看跌）。
- **Gamma**：标的涨 1 美元，Delta 变多少。越大说明仓位方向变化越快；临近到期、平值附近最大。
- **Theta**：其它不变，过一天期权每股价格大约变多少（时间价值流失）。持仓值 = 每天大约赚 / 亏多少美元；卖方通常为正。
- **Vega**：IV 上升 1 个百分点，期权每股价格大约变多少。持仓值为美元。
- **Rho**：利率上升 1 个百分点，期权价格变多少；短期期权影响很小（不显示，Q167）。

## 已定的口径（只引用）
- Q137：IV / Greeks 直接取 CBOE，后端不做 Black-Scholes，不配置无风险利率。
- Q139 / Q140 / Q148：mark 与手动覆盖由 FEAT-016 定义；Greeks 只来自 CBOE，**不能手动覆盖**（手动 mark 时的显示见 Q170）。
- Q141 / Q144 / Q145：拉取失败保留上次成功的报价（含 IV / Greeks），不清空。
- Q143：数据源已无该合约 → 保留最后报价。Q149：指数期权不拉取 → 无报价。Q150 / Q151 / Q156：到期日 < 美东今天的合约冻结。
- Q152：方向约定（多头 +、空头 −）。Q157：只看当前账户。Q161：场景只用 multiplier 100，计算仍用字段值。
- 本需求的 Q163–Q173 已定，结论见下方规则和页面中的引用。

## 规则
1. **来源**：每个期权持仓行的 IV、Delta、Gamma、Theta、Vega 取该合约在库里最新存的 CBOE 值（FEAT-016），同一合约在不同账户共用一份。行情未更新、数据源已无该合约时用保留的最后值（Q141 / Q143）。
2. **每张原值**：CBOE 原值，不按方向翻转（Q164）。IV 显示为百分比：0.3512 → `35.12%`。
3. **持仓值**（Q163）：Delta / Gamma / Theta / Vega 各自 = 每张原值 × 张数 × multiplier × 方向（多头 +1、空头 −1，Q164）。含义：Delta 持仓值 = 等价股数；Theta = 美元 / 天；Vega = 美元 / IV 每 1 个百分点；Gamma = 标的涨 1 美元时等价股数的变化。IV 没有持仓值。
4. **精度**（Q166）：每张原值四舍五入到 4 位小数；持仓值四舍五入到 2 位（HALF_UP，负数远离 0）；用未取整的原值计算持仓值；IV 百分比 2 位。持仓值带显式正负号（0 为 `0.00`）。
5. **无法显示**：
   - 该合约库里从未有过 CBOE 报价（从未匹配、指数期权 Q149）→ 所有 Greeks 列显示 `无报价`，即使有手动 mark（Q170）。
   - 已过期未处理（到期日 < 美东今天）→ 显示 `已到期，待处理`（Q171）；到期日当天正常显示。
   - 有报价但某个字段为空（CBOE 返回 null 或格式错误）→ 该字段的每张值和持仓值都显示 `—`，其它字段照常（Q172）。
6. **手动 mark**（Q170）：合约 mark 为手动值时，Greeks 仍显示 CBOE 值，并标注 `Greeks 按 CBOE 报价（MM-DD HH:mm），未随手动 Mark 调整`（时间 = 该合约 CBOE 报价时间，美东）。
7. **合计范围**（Q165）：本需求只做每行；不做账户 / 按标的合计，不改 FEAT-017 的合计行。按标的、全组合汇总及股票 Delta 在 FEAT-019。
8. 所有计算只在后端做；前端只显示后端的值和标签。

## 需要的数据和操作（给 backend）
- 期权持仓每行加：IV、Delta、Gamma、Theta、Vega 的每张原值（规则 2、4）和 Delta / Gamma / Theta / Vega 的持仓值（规则 3、4）；Greeks 状态（正常 / 无报价 / 已到期待处理，规则 5）与对应文字；Greeks 的 CBOE 报价时间；手动 mark 时的标注文字（规则 6）。不返回 Rho（Q167）。
- 不需要写操作、不需要新的存储（数据 FEAT-016 已存）；行情刷新后重新查询即得到新值。
- 股票行不加 Greeks（FEAT-019）。

## 页面与交互（给 frontend）
- **期权持仓表**（Q173）：表格上方加开关 `显示 Greeks`（默认关闭，关闭时表格与现在相同）；打开后在 `未实现盈亏` 之后加 5 列 `IV`、`Delta`、`Gamma`、`Theta`、`Vega`（验收均指打开后）。Delta–Vega 每格上面是持仓值（带符号，如 `+30.21`），下面灰色小字是每张原值（如 `−0.3021`）；IV 列只显示百分比。不可显示时整组列显示后端给的文字（`无报价` / `已到期，待处理`），灰色；单个字段缺失显示 `—`。
- **手动 mark 标注**：规则 6 的文字以灰色小字显示在该行 Greeks 区域（或 IV 单元格下方），不遮挡数值。
- **说明**（Q169）：每个 Greeks 列头有 `?` 图标，悬停显示上面"Greeks 是什么"的对应一句话（含持仓值的含义）；表格上方一行灰字 `IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考`（100 取 multiplier 字段，此处示意）。
- 立即刷新、切换账户、写操作后随持仓重新查询更新（沿用 FEAT-016 / 017 的刷新方式）。

## 验收标准
场景（所有条目共用）：今天 2026-11-23（星期一）10:00 拉取成功，报价时间 09:45，标的均为虚构，multiplier 100。假 CBOE 值：

| 合约 | bid / ask | iv | delta | gamma | theta | vega |
|------|-----------|----|-------|-------|-------|------|
| QRTV 2026-12-18 50P | 1.20 / 1.30 | 0.3512 | −0.3021 | 0.0512 | −0.0456 | 0.0711 |
| QRTV 2026-12-18 55C | 1.80 / 1.90 | 0.3320 | 0.2850 | 0.0480 | −0.0390 | 0.0650 |
| IXQZ 2026-12-18 65C | 0.80 / 0.90 | 0.2900 | 0.18345 | 0.03125 | −0.0247 | 0.0503 |

账户 A：P1 STO 1 张 QRTV 50P；P2 BTO 2 张 QRTV 55C；P3 STO 1 张 IXQZ 65C；P4 STO 1 张 QRTV 2026-12-18 47.5P（链中从无该合约）。账户 B：P5 STO 3 张 QRTV 50P。

1. **Given** 场景 **When** 打开账户 A 持仓页 **Then** P1：IV `35.12%`；Delta `+30.21`（`−0.3021`）；Gamma `−5.12`（`0.0512`）；Theta `+4.56`（`−0.0456`）；Vega `−7.11`（`0.0711`）；没有 Rho 列。
2. **Given** 场景 **Then** P2：IV `33.20%`；Delta `+57.00`（`0.2850`）；Gamma `+9.60`（`0.0480`）；Theta `−7.80`（`−0.0390`）；Vega `+13.00`（`0.0650`）。
3. **Given** 场景 **Then** P3：IV `29.00%`；Delta `−18.35`（`0.1835`）；Gamma `−3.13`（`0.0313`）；Theta `+2.47`（`−0.0247`）；Vega `−5.03`（`0.0503`）。
4. **Given** 场景 **Then** P4 的 IV、Delta、Gamma、Theta、Vega 都显示 `无报价`；FEAT-017 的合计行与本需求前相同，页面没有 Greeks 合计。
5. **Given** 场景 **When** 切换到账户 B **Then** P5：IV `35.12%`；Delta `+90.63`；Gamma `−15.36`；Theta `+13.68`；Vega `−21.33`（每张原值同 P1）。
6. **Given** 场景 **When** 10:15 拉取成功，P1 变为 iv 0.3600 / delta −0.3300 / gamma 0.0530 / theta −0.0470 / vega 0.0720 **Then** P1 显示 `36.00%`、`+33.00`、`−5.30`、`+4.70`、`−7.20`。**When** 10:30 起 QRTV 拉取失败（行上 `行情未更新（上次：11-23 10:15）`） **Then** P1 仍显示上述 10:15 的值。
7. **Given** 场景 **When** 把 P1 的 Mark 手动设为 1.40 **Then** P1 的 Greeks 不变（同验收 1），并显示 `Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整`；清除手动值后该标注消失。**When** 把 P4 的 Mark 手动设为 0.30 **Then** P4 的 Greeks 仍为 `无报价`。
8. **Given** 场景 **When** 10:15 拉取成功，P2 的 bid / ask / iv / delta / theta 正常，gamma 和 vega 为 null **Then** P2 的 Gamma、Vega 列（持仓值和每张值）显示 `—`，IV、Delta、Theta 正常。
9. **Given** 场景，账户 A 另有 P6：STO 1 张 QRTV 2026-11-20 45P（已过期未处理，库里最后 delta −0.0100）和 P7：STO 1 张 QRTV 2026-11-23 52P（今天到期，delta −0.4500） **When** 查看 **Then** P6 的 Greeks 列显示 `已到期，待处理`；P7 Delta `+45.00`（`−0.4500`）。
10. **Given** 场景，`显示 Greeks` 关闭 **Then** 期权持仓表没有 Greeks 列。**When** 打开开关并悬停 `Theta` 列头的 `?` **Then** 显示 Theta 的通俗解释（含"持仓值 = 每天大约赚 / 亏多少美元"）；表格上方显示规则说明灰字。

## 边界情况
- **同一合约多个账户**：共用 CBOE 值，各按自己的张数和方向算持仓值（验收 5）。
- **数据源已无该合约**（如到期日当天收盘后从链中消失，Q143）：显示最后保存的 Greeks，沿用 FEAT-016 的标签。
- **mark 来自 theo**（bid 为 0）：Greeks 照常显示 CBOE 值。
- **深度实值 / 临近到期**：CBOE 可能给 Delta ±1.0000、Gamma 0 或 IV 为 null，按原值或规则 5 的 `—` 显示，不做修正。
- **CBOE 单位**：按 theta = 每日、vega = IV 每 1 个百分点、iv 为小数理解；backend 契约阶段用真实响应核实，若不符请提出，PM 再与用户确认。
- **拆股调整合约 / 指数期权**：无报价 → `无报价`（规则 5）。

## Out of Scope
- 按标的 / 全组合 / 账户的 Greeks 合计、股票 Delta、Delta 美元敞口（FEAT-019）；策略组 Greeks（M4）。
- 后端自算 Greeks、Black-Scholes、无风险利率（Q137）；手动录入 Greeks。
- 随手动 mark 重新估算 Greeks；已过期合约的 Greeks。
- Greeks 历史 / 走势图；首页显示 Greeks；合并查看（FEAT-035）。
- 非 100 multiplier 的场景（Q161）。

## Open Questions
无（Q163–Q173 已定）。

## Implementation Notes

### Backend
- **新增**：`backend/src/main/java/com/optionmgr/valuation/domain/` 下 `GreeksStatus`（`OK` / `NO_QUOTE` / `EXPIRED_PENDING` + 标签）、`RowGreeks`（含 `Greek(perContract, position)`）、`GreeksValuation`（单行计算：判定顺序、取整、方向、手动 mark 标注）。
- **修改**：`valuation/service/UnrealizedValuationService` 加静态 `greeks(...)`（由行的 `optionQuote` 判定是否从未报价、手动标志、报价时间，由库里的 `OptionState` 取 iv / Greeks）；`quote/service/QuoteViews.QuoteBook` 加 `stored(ContractKey)`；`trade/web/OptionPositionController` 的 `PositionResponse` 末尾加 `greeks`（`GreeksResponse` record），FEAT-043 调整响应的 `position` 因共用 `toResponse` 也带。纯新增字段，无迁移、无新端点。
- **测试**：`valuation/domain/GreeksValuationTest`（11 个：验收 1–9 的数字、0 无符号、Delta 1 / Gamma 0、multiplier 字段）、`valuation/web/OptionGreeksControllerTest`（6 个：验收 1–9 经 HTTP，in-memory 假 CBOE + 可移动时钟）。
- **设计决定**（契约阶段定、已接受）：一个嵌套对象 `greeks`；JSON 正数不带 `+`，符号由前端加；`NO_QUOTE` 看 FEAT-016 `contractStatus = NEVER_QUOTED`（不看 mark，与 FEAT-017 不同）；`EXPIRED_PENDING` 优先；`manualMarkNote` 只在 `OK` 且手动 mark 时给出；`EXPIRED_PENDING` 时仍给最后的 `quoteTime`。
- **已知限制**：Greeks 入库时已 HALF_UP 到 8 位小数（FEAT-016），再取 4 位存在理论上的双重取整，实际无影响；已过期未处理合约不显示 Greeks。

### Frontend
- **新增**：`frontend/src/components/greeks/`：`greeksLabels.ts`（`greeksDisplay` 显示规则、五列标题与 `?` 悬停说明、口径说明、`显示 Greeks` 开关 localStorage 读写）、`GreeksCells.tsx`（`GreeksHeader`、`GreeksIvCell`（含手动 mark 标注）、`GreekValueCell`）；`utils/format.ts` 加 `formatPlainPercent`（IV `35.12%`）；`api/types.ts` 加 `GreeksStatus`、`OptionGreeks`，`OptionPosition.greeks`（可选，兼容旧 fixture）。
- **修改**：`components/position/PositionTable.tsx` 加 `showGreeks`，打开时在 `未实现盈亏` 后插入 IV / Delta / Gamma / Theta / Vega 五列；`pages/PositionsPage.tsx` 在期权表上方加 `显示 Greeks` 开关（默认关闭）和打开时的灰字口径说明。
- **显示**：持仓值带显式符号（`+30.21` / `−5.12` / `0.00`，不着色），下面灰色小字每张原值（`−0.3021`）；IV 只显示百分比；`status ≠ OK` 五列显示后端 `label`；单字段 `null` → 该格两行 `—`；`manualMarkNote` 在 IV 值下方灰色小字整句显示。前端不做任何 Greeks 计算。
- **开关记忆**：localStorage（`option-mgr.showGreeks`，try/catch），切换账户 / 刷新页面后保持；新浏览器默认关闭。
- **测试**：`components/greeks/greeksLabels.test.ts`（显示规则、文案、存储）、`pages/Greeks.test.tsx`（验收 1–5、7–10 的页面显示，mock 后端按契约示例）、`utils/format.test.ts` 加 `formatPlainPercent`。testid 见 `docs/architecture.md` 前端"FEAT-018 testid"。
- **已知限制**：antd 表格 measure 行会再渲染一份列头，按 testid 取列头 / `?` 需取第一个；手动 mark 标注较长时 IV 列会变宽（限宽 200px 换行）。
