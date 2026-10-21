-- Read-only inspection. Run against the production MySQL server; no data is modified.
SELECT @@hostname AS mysql_host, @@port AS mysql_port, VERSION() AS mysql_version,
       DATABASE() AS selected_database;

-- Presence of the old production catalog and the newly imported catalog.
SELECT table_schema, table_name, engine, table_collation
FROM information_schema.tables
WHERE table_schema IN ('rigour_iam', 'rigour_scdp_iam') AND table_name='iam_signing_key';

-- Full actual names/types; no signing-key contents are selected.
SELECT ordinal_position, column_name, column_type, is_nullable, column_key,
       datetime_precision, collation_name
FROM information_schema.columns
WHERE table_schema='rigour_scdp_iam' AND table_name='iam_signing_key'
ORDER BY ordinal_position;

-- Compare with the current IAM source definition. A zero-row result means these column checks pass.
SELECT e.column_name AS expected_column, e.column_type AS expected_type,
       e.is_nullable AS expected_nullable, c.column_type AS actual_type,
       c.is_nullable AS actual_nullable
FROM (
    SELECT 'id' AS column_name, 'binary(16)' AS column_type, 'NO' AS is_nullable
    UNION ALL SELECT 'kid', 'varchar(100)', 'NO'
    UNION ALL SELECT 'algorithm', 'varchar(16)', 'NO'
    UNION ALL SELECT 'key_use', 'varchar(16)', 'NO'
    UNION ALL SELECT 'public_jwk_json', 'json', 'NO'
    UNION ALL SELECT 'private_key_ref', 'varchar(255)', 'NO'
    UNION ALL SELECT 'status', 'varchar(32)', 'NO'
    UNION ALL SELECT 'not_before', 'datetime(6)', 'NO'
    UNION ALL SELECT 'not_after', 'datetime(6)', 'NO'
    UNION ALL SELECT 'activated_at', 'datetime(6)', 'YES'
    UNION ALL SELECT 'retired_at', 'datetime(6)', 'YES'
    UNION ALL SELECT 'created_at', 'datetime(6)', 'NO'
    UNION ALL SELECT 'created_by', 'binary(16)', 'YES'
) e
LEFT JOIN information_schema.columns c
  ON c.table_schema='rigour_scdp_iam' AND c.table_name='iam_signing_key'
 AND c.column_name=e.column_name
WHERE c.column_name IS NULL OR c.column_type <> e.column_type OR c.is_nullable <> e.is_nullable;

SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema='rigour_scdp_iam' AND table_name='iam_signing_key'
ORDER BY index_name, seq_in_index;

-- Suspicious type inference in other imported IAM tables; review against their source DDL.
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema='rigour_scdp_iam'
  AND ((column_name='id' AND data_type IN ('text', 'longtext', 'mediumtext'))
       OR (column_name IN ('created_at', 'updated_at', 'not_before', 'not_after', 'activated_at')
           AND data_type='date'))
ORDER BY table_name, ordinal_position;

-- Tables without a primary key are review candidates, not automatically proven invalid.
SELECT t.table_name
FROM information_schema.tables t
WHERE t.table_schema='rigour_scdp_iam' AND t.table_type='BASE TABLE'
  AND NOT EXISTS (
      SELECT 1 FROM information_schema.table_constraints k
      WHERE k.table_schema=t.table_schema AND k.table_name=t.table_name AND k.constraint_type='PRIMARY KEY'
  )
ORDER BY t.table_name;
