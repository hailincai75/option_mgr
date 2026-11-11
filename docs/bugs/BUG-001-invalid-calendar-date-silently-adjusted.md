# BUG-001: 不存在的日期（如 2026-02-30）被静默改成月末并保存

- **Status:** Closed
- **Severity:** Minor
- **Layer:** backend
- **Related:** FEAT-007

## 复现步骤
1. 空库，后端运行中。
2. `POST /api/cash/transfers`，请求体：
   ```json
   { "type": "DEPOSIT", "amount": "100.00", "occurredAtLocal": "2026-02-30T10:00", "remark": null }
   ```
3. 查看响应和 `GET /api/cash/ledger`。

自动化：`backend/src/test/java/com/optionmgr/systemtest/Feat007CashValidationSystemTest.java` →
`badTimeFormat("2026-02-30T10:00")` 和 `badTimeFormat("2026-04-31T10:00")`。运行：`cd backend && gradlew.bat test --tests "com.optionmgr.systemtest.*"`。

## 期望结果
按 API 契约，`occurredAtLocal` 格式/取值不对 → `400 BAD_REQUEST`，`details` 为 `[{ "field": "occurredAtLocal", ... }]`，不保存任何记录。
一个不存在的日历日期不应被猜成另一个日期——资金流水的时间点会影响 Q19 重放检查和每条余额。

## 实际结果
`201 Created`，记录被保存为另一天：
```json
{"id":122,"type":"DEPOSIT","amount":"100.00","occurredAt":"2026-02-28T15:00:00Z","remark":null,"ledgerEntryId":122,"createdAt":"2026-11-10T14:45:00Z"}
```
`2026-04-31T10:00` 同样返回 201，被改成 2026-04-30（参数化用例「时间格式不对 2026-04-31T10:00 → BAD_REQUEST occurredAtLocal」也失败）。

## 备注（给 dev 参考，不是修复要求）
`CashDtos.CreateTransferRequest.occurredAtLocal` 用 `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")` 解析，Jackson 的 `DateTimeFormatter.ofPattern` 默认 `ResolverStyle.SMART`，会把 2 月 30 日"修正"为 2 月 28 日。
前端用原生 `datetime-local` 输入，正常操作产生不了这种值，所以只影响直接调用 API 的情况（以后的导入、脚本等）。

## 原因与修法（backend-dev）
**原因：** `CashDtos.CreateTransferRequest.occurredAtLocal` 用 `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")`，Jackson 用 `DateTimeFormatter.ofPattern(...)` 建 formatter，默认 `ResolverStyle.SMART`：日期字段只要在 1–31 范围内就会被"修正"到当月最后一天（02-30 → 02-28，04-31 → 04-30），不报错。

**修法：**
- `common/time/MarketTime` 新增 `LOCAL_INPUT` formatter：`uuuu-MM-dd'T'HH:mm[:ss]` + `ResolverStyle.STRICT`（STRICT 下 `yyyy` 是 year-of-era，需要配合 era，所以改用 `uuuu`），以及 `MarketTime.parseLocal(String)`
- 新增 `common/time/MarketLocalDateTimeDeserializer`（Jackson），用 `parseLocal` 解析；失败抛带字段路径的 `InvalidFormatException`，由 `GlobalExceptionHandler` 转成 `400 BAD_REQUEST`，`details[].field = occurredAtLocal`（错误格式不变）
- `CashDtos` 的 `occurredAtLocal` 改为 `@JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)`。可接受的格式不变（到分或到秒，无小数秒、无时区后缀）。FEAT-001 的成交时间直接复用这个 deserializer
- 现在被拒绝的还有：不存在的日期（02-30、04-31、平年 02-29）、`24:00`、`10:60`、秒为 60

**回归测试：** `common/time/MarketLocalDateTimeParsingTest`（解析器与 deserializer 的单元测试，25 个用例）；`cash/web/CashControllerTest.unparseableValuesAreBadRequestNamingTheField` 增加 `2026-02-30T10:00`、`2026-04-31T10:00`、`2026-11-02T24:00`。QA 的 `Feat007CashValidationSystemTest.badTimeFormat` 全部通过。

## QA 复测（2026-11-11）
- `gradlew.bat clean build`（JDK 21）：BUILD SUCCESSFUL，140 个测试 0 失败；`Feat007CashValidationSystemTest.badTimeFormat` 全部通过
- 新增回归用例：`2025-02-29`、`2027-02-29`（平年）、`24:00`、`10:60`、`10:00:60`、`2026-13-01` → 400 `BAD_REQUEST`（field `occurredAtLocal`）；`2024-02-29T10:00`（闰日）→ 201，存为 `2024-02-29T15:00:00Z`；`2026-04-30T23:59:59` → `2026-05-01T03:59:59Z`
- E2E 全量复跑 27 passed
- 结论：已修复，Closed
