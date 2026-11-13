-- Test only (MigrationFromM1Test; V7 so it runs after the real V6): a migration that fails, to check that the application does not start and the
-- pre-migration backup restores the database.
INSERT INTO simulated_failure_missing_table (id) VALUES (1);
