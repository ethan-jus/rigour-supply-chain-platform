#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DB_HOST="${DB_HOST:-82.157.4.176}"
DB_PORT="${DB_PORT:-13306}"
DB_USER="${DB_USER:-root}"
DB_URL_PARAMS="${DB_URL_PARAMS:-useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=PREFERRED&allowPublicKeyRetrieval=true}"
SERVICES="${SERVICES:-settings iam integration erp crm order}"
ACTION="${ACTION:-migrate}"

if [[ "$ACTION" != "info" && "$ACTION" != "validate" && "$ACTION" != "migrate" ]]; then
  printf 'ACTION must be one of: info, validate, migrate\n' >&2
  exit 2
fi

if [[ -z "${DB_PASSWORD:-}" ]]; then
  if [[ ! -t 0 ]]; then
    printf 'DB_PASSWORD is required when stdin is not a terminal.\n' >&2
    exit 2
  fi
  read -r -s -p "Database password for ${DB_USER}@${DB_HOST}:${DB_PORT}: " DB_PASSWORD
  printf '\n'
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"; unset DB_PASSWORD' EXIT

cat > "$TMP_DIR/FlywayServiceRunner.java" <<'JAVA'
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.MigrateResult;

public final class FlywayServiceRunner {
    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: FlywayServiceRunner <action> <schema> <jdbcUrl> <location>");
        }
        String action = args[0];
        String schema = args[1];
        String jdbcUrl = args[2];
        String location = args[3];
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"))
                .defaultSchema(schema)
                .schemas(schema)
                .locations("filesystem:" + location)
                .load();
        switch (action) {
            case "info" -> printInfo(schema, flyway.info());
            case "validate" -> {
                flyway.validate();
                System.out.printf("%s validate OK%n", schema);
            }
            case "migrate" -> {
                MigrateResult result = flyway.migrate();
                System.out.printf("%s migrate OK initial=%s target=%s migrations=%d%n",
                        schema, result.initialSchemaVersion, result.targetSchemaVersion, result.migrationsExecuted);
                printInfo(schema, flyway.info());
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private static void printInfo(String schema, MigrationInfoService info) {
        MigrationInfo current = info.current();
        System.out.printf("%s current=%s%n", schema, current == null ? "<none>" : current.getVersion());
        for (MigrationInfo migration : info.all()) {
            System.out.printf("%s %s %s %s%n",
                    schema, migration.getVersion(), migration.getState(), migration.getDescription());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
JAVA

module_pom() {
  case "$1" in
    settings) printf '%s\n' "$ROOT_DIR/services/rigour-business-settings-service/business-settings-service/pom.xml" ;;
    iam) printf '%s\n' "$ROOT_DIR/services/rigour-tenant-iam-service/iam-service/pom.xml" ;;
    integration) printf '%s\n' "$ROOT_DIR/services/rigour-integration-migration-service/integration-migration-service/pom.xml" ;;
    erp) printf '%s\n' "$ROOT_DIR/services/rigour-erp-core-service/erp-core-service/pom.xml" ;;
    crm) printf '%s\n' "$ROOT_DIR/services/rigour-merchant-crm-service/merchant-crm-service/pom.xml" ;;
    order) printf '%s\n' "$ROOT_DIR/services/rigour-order-center-service/order-center-service/pom.xml" ;;
    bi) printf '%s\n' "$ROOT_DIR/services/rigour-analytics-bi-service/analytics-bi-service/pom.xml" ;;
    *) printf 'Unknown service: %s\n' "$1" >&2; return 1 ;;
  esac
}

schema_name() {
  case "$1" in
    settings) printf '%s\n' "rigour_settings" ;;
    iam) printf '%s\n' "rigour_iam" ;;
    integration) printf '%s\n' "rigour_integration" ;;
    erp) printf '%s\n' "rigour_erp" ;;
    crm) printf '%s\n' "rigour_crm" ;;
    order) printf '%s\n' "rigour_order" ;;
    bi) printf '%s\n' "rigour_bi" ;;
    *) printf 'Unknown service: %s\n' "$1" >&2; return 1 ;;
  esac
}

migration_dir() {
  case "$1" in
    settings) printf '%s\n' "$ROOT_DIR/services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration" ;;
    iam) printf '%s\n' "$ROOT_DIR/services/rigour-tenant-iam-service/iam-service/src/main/resources/db/migration" ;;
    integration) printf '%s\n' "$ROOT_DIR/services/rigour-integration-migration-service/integration-migration-service/src/main/resources/db/migration" ;;
    erp) printf '%s\n' "$ROOT_DIR/services/rigour-erp-core-service/erp-core-service/src/main/resources/db/migration" ;;
    crm) printf '%s\n' "$ROOT_DIR/services/rigour-merchant-crm-service/merchant-crm-service/src/main/resources/db/migration" ;;
    order) printf '%s\n' "$ROOT_DIR/services/rigour-order-center-service/order-center-service/src/main/resources/db/migration" ;;
    bi) printf '%s\n' "$ROOT_DIR/services/rigour-analytics-bi-service/analytics-bi-service/src/main/resources/db/migration" ;;
    *) printf 'Unknown service: %s\n' "$1" >&2; return 1 ;;
  esac
}

export DB_USER DB_PASSWORD

for service in $SERVICES; do
  pom="$(module_pom "$service")"
  schema="$(schema_name "$service")"
  location="$(migration_dir "$service")"
  classpath_file="$TMP_DIR/${service}.classpath"
  jdbc_url="jdbc:mysql://${DB_HOST}:${DB_PORT}/${schema}?${DB_URL_PARAMS}"

  printf '==> %s (%s) %s\n' "$service" "$schema" "$ACTION"
  "$ROOT_DIR/mvnw" -q -f "$pom" dependency:build-classpath \
    -Dmdep.outputFile="$classpath_file" \
    -DincludeScope=runtime
  javac -cp "$(cat "$classpath_file")" "$TMP_DIR/FlywayServiceRunner.java"
  java -cp "$(cat "$classpath_file"):$TMP_DIR" FlywayServiceRunner "$ACTION" "$schema" "$jdbc_url" "$location"
done
