-- Read-only inventory and plan generation. No ALTER/UPDATE/DELETE is executed by this file.
-- Execute on the intended MySQL server with mysql; save and review each result set.
-- Generated collision queries must return zero rows before any conversion.
-- Take a restorable backup and stop writers before manually applying reviewed DDL.
SET @rigour_schemas = '^rigour_(settings|iam|integration|erp|crm|order|hr|bi|sales_work|ai)$';
SET SESSION group_concat_max_len = 1048576;

SELECT @@hostname AS mysql_host, @@port AS mysql_port, VERSION() AS mysql_version;

-- Actual defaults and deviations (binary/numeric/date columns have NULL collation and are excluded).
SELECT schema_name, default_character_set_name, default_collation_name
FROM information_schema.schemata WHERE schema_name REGEXP @rigour_schemas;
SELECT table_schema, table_name, table_collation
FROM information_schema.tables
WHERE table_schema REGEXP @rigour_schemas AND table_type='BASE TABLE'
  AND table_collation <> 'utf8mb4_general_ci';
SELECT table_schema, table_name, column_name, column_type, collation_name
FROM information_schema.columns
WHERE table_schema REGEXP @rigour_schemas AND collation_name IS NOT NULL
  AND collation_name <> 'utf8mb4_general_ci';

-- Generate duplicate checks for every unique index containing text, including prefix/composite keys.
-- NULL-containing unique keys permit duplicates; exclude them from the collision check.
-- Result queries reveal duplicate counts only, not the business values themselves.
SELECT CONCAT(
  'SELECT ', QUOTE(CONCAT(s.table_schema, '.', s.table_name, '.', s.index_name)),
  ' AS checked_index, COUNT(*) AS duplicate_count FROM `', REPLACE(s.table_schema,'`','``'),
  '`.`', REPLACE(s.table_name,'`','``'), '` WHERE ',
  GROUP_CONCAT(CONCAT('`', REPLACE(s.column_name,'`','``'), '` IS NOT NULL')
               ORDER BY s.seq_in_index SEPARATOR ' AND '),
  ' GROUP BY ',
  GROUP_CONCAT(
    CONCAT(IF(c.character_set_name IS NOT NULL, 'CONVERT(', ''),
           IF(s.sub_part IS NOT NULL, 'LEFT(', ''), '`', REPLACE(s.column_name,'`','``'), '`',
           IF(s.sub_part IS NOT NULL, CONCAT(', ', s.sub_part, ')'), ''),
           IF(c.character_set_name IS NOT NULL, ' USING utf8mb4) COLLATE utf8mb4_general_ci', ''))
    ORDER BY s.seq_in_index SEPARATOR ', '),
  ' HAVING COUNT(*) > 1;') AS collision_check_sql
FROM information_schema.statistics s
LEFT JOIN information_schema.columns c
  ON c.table_schema=s.table_schema AND c.table_name=s.table_name AND c.column_name=s.column_name
WHERE s.table_schema REGEXP @rigour_schemas AND s.non_unique=0
GROUP BY s.table_schema, s.table_name, s.index_name
HAVING SUM(c.character_set_name IS NOT NULL)>0 AND SUM(s.column_name IS NULL)=0;

-- Functional indexes cannot be checked by the simple column-based generator. Review these separately.
SELECT table_schema, table_name, index_name
FROM information_schema.statistics
WHERE table_schema REGEXP @rigour_schemas AND non_unique=0 AND column_name IS NULL;

-- Text foreign keys require coordinated changes at both ends. Save SHOW CREATE TABLE for these tables.
SELECT k.constraint_schema, k.table_name, k.constraint_name, k.column_name,
       k.referenced_table_schema, k.referenced_table_name, k.referenced_column_name
FROM information_schema.key_column_usage k
JOIN information_schema.columns c
  ON c.table_schema=k.table_schema AND c.table_name=k.table_name AND c.column_name=k.column_name
WHERE (k.table_schema REGEXP @rigour_schemas OR k.referenced_table_schema REGEXP @rigour_schemas)
  AND k.referenced_table_name IS NOT NULL AND c.collation_name IS NOT NULL;

-- Default changes alone do not convert existing tables/columns.
SELECT CONCAT('ALTER DATABASE `', REPLACE(schema_name,'`','``'),
              '` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;') AS proposed_schema_ddl
FROM information_schema.schemata
WHERE schema_name REGEXP @rigour_schemas AND default_collation_name <> 'utf8mb4_general_ci';

-- Proposed table DDL is only text. Do not pipe this result directly to mysql.
-- Review all unique checks, foreign keys, index sizes, column types and table rebuild cost first.
SELECT CONCAT('ALTER TABLE `', REPLACE(t.table_schema,'`','``'), '`.`', REPLACE(t.table_name,'`','``'),
              '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;') AS proposed_table_ddl
FROM information_schema.tables t
WHERE t.table_schema REGEXP @rigour_schemas AND t.table_type='BASE TABLE'
  AND (t.table_collation <> 'utf8mb4_general_ci' OR EXISTS (
    SELECT 1 FROM information_schema.columns c
    WHERE c.table_schema=t.table_schema AND c.table_name=t.table_name
      AND c.collation_name IS NOT NULL AND c.collation_name <> 'utf8mb4_general_ci'))
ORDER BY t.table_schema, t.table_name;
