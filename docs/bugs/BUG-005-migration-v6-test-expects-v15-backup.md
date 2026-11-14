# BUG-005: MigrationV6HasAuditLogTest 仍期待 `before-V15` 备份文件，`gradlew.bat build` 失败

- **Status:** Closed
- **Severity:** Minor
- **Layer:** backend
- **Related:** FEAT-016（迁移 V16）

## 复现步骤
1. `cd backend && gradlew.bat test --tests "com.optionmgr.account.MigrationV6HasAuditLogTest"`（单独运行也稳定复现）

## 期望结果
通过；`gradlew.bat build` 全部后端测试通过。

## 实际结果
`backfillSetsTheFlagForRecordsWithTheirOwnAuditRecords()` 失败：

```
java.lang.AssertionError: Expected size: 1 but was: 0 in: []
  at com.optionmgr.account.MigrationV6HasAuditLogTest.backfillSetsTheFlagForRecordsWithTheirOwnAuditRecords(MigrationV6HasAuditLogTest.java:112)
```

第 111 行过滤 `v5db-backup-before-V15-`，但迁移前备份按**目标版本**命名（`DatabaseBackupBeforeMigration`：`<db>-backup-before-V<target>-…`），FEAT-016 新增 V16 后文件名变为 `v5db-backup-before-V16-…`。契约要求同步更新的 `MigrationFromM1Test` 已改成 V16 / V17，这个测试漏改了。全部后端测试 1205 个中只有这 1 个失败；FEAT-016 的 36 个系统测试全部通过。需要 backend-dev 把第 111 行改为 `before-V16-`。QA 不改 dev 写的单元测试。

## 修复（backend-dev）
- **原因**：迁移前备份按目标版本（最新迁移）命名，测试里写死了版本号。FEAT-016 新增 V16 时只改了 `MigrationFromM1Test`，漏了 `MigrationV6HasAuditLogTest`。
- **修法**：新增测试工具 `backend/src/test/java/com/optionmgr/account/MigrationVersions.java`，从 classpath 读 `db/migration` 中最大的版本号（`latest()`，备份名前缀 `backupPrefix(db)`）以及 `db/failing-migration` 测试迁移的版本号（`failing()`）。`MigrationV6HasAuditLogTest` 和 `MigrationFromM1Test` 中所有 `before-V<n>-` 都改为用它，不再写死版本号，以后新增迁移不用改这两个测试。
- **回归保护**：`MigrationFromM1Test.ac2_failingMigrationDoesNotStartAndTheBackupRestoresTheM1Database` 新增断言：测试用失败迁移的版本号必须大于最新的真实迁移。忘记给 fixture 改名时，会直接报出这个原因，而不是一个难懂的失败。
- **验证**：`gradlew.bat test --tests "com.optionmgr.account.MigrationV6HasAuditLogTest" --tests "com.optionmgr.account.MigrationFromM1Test"` 通过（5 个测试）。
- 需求状态没有改：BUG-004（frontend）还没有修复说明。

## 复测
2026-11-14：修复说明已核对。备份文件名改为从 classpath 读取最新迁移版本，不再写死；另外新增了 failing-migration 版本号的回归断言。`gradlew.bat build` BUILD SUCCESSFUL，1205 个测试 0 失败 0 错误（含 `MigrationV6HasAuditLogTest`、`MigrationFromM1Test` 和 FEAT-016 的 36 个系统测试）。关闭。
