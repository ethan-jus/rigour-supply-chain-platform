#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_PARENT="$(cd "$ROOT_DIR/.." && pwd)"

DB_HOST="${DB_HOST:-82.157.4.176}"
DB_PORT="${DB_PORT:-13306}"
DB_USER="${DB_USER:-root}"
DB_URL="${DB_URL:-jdbc:mysql://${DB_HOST}:${DB_PORT}/?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=PREFERRED&allowPublicKeyRetrieval=true}"
DEFAULT_SQL_FILES="docs/CRM_ORDER_ERP_DHB_RESYNC_RESET.sql docs/CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql docs/CRM_ORDER_ERP_UNUSED_TABLE_DROP.sql"
SQL_FILES="${SQL_FILES:-$DEFAULT_SQL_FILES}"
CONFIRMATION="${CONFIRMATION:-}"

readonly_sql_file() {
  case "$(basename "$1")" in
    CRM_ORDER_ERP_DHB_RESYNC_PREFLIGHT_CHECK.sql|CRM_ORDER_ERP_DHB_SYNC_STAGE_VERIFY.sql)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

requires_confirmation=false
for sql_file in $SQL_FILES; do
  if ! readonly_sql_file "$sql_file"; then
    requires_confirmation=true
    break
  fi
done

if [[ "$requires_confirmation" == "true" && "$CONFIRMATION" != "RESET_CRM_ORDER_ERP_SETTINGS_DHB" ]]; then
  printf 'Refusing to run CRM/Order/ERP/Settings/DHB write maintenance SQL.\n' >&2
  printf 'Set CONFIRMATION=RESET_CRM_ORDER_ERP_SETTINGS_DHB after backup and sync pause/review.\n' >&2
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

MYSQL_CLIENT="${MYSQL_CLIENT:-}"
if [[ -z "$MYSQL_CLIENT" ]]; then
  if command -v mysql >/dev/null 2>&1; then
    MYSQL_CLIENT="$(command -v mysql)"
  elif [[ -x /usr/local/mysql/bin/mysql ]]; then
    MYSQL_CLIENT="/usr/local/mysql/bin/mysql"
  elif [[ -x /opt/homebrew/bin/mysql ]]; then
    MYSQL_CLIENT="/opt/homebrew/bin/mysql"
  fi
fi

resolved_files=()
for sql_file in $SQL_FILES; do
  if [[ "$sql_file" = /* ]]; then
    resolved_files+=("$sql_file")
  else
    resolved_files+=("$ROOT_DIR/$sql_file")
  fi
done

if [[ -n "$MYSQL_CLIENT" ]]; then
  export MYSQL_PWD="$DB_PASSWORD"
  trap 'unset DB_PASSWORD MYSQL_PWD' EXIT
  for sql_file in "${resolved_files[@]}"; do
    printf '==> %s\n' "$sql_file"
    "$MYSQL_CLIENT" \
      --host="$DB_HOST" \
      --port="$DB_PORT" \
      --user="$DB_USER" \
      --database=mysql \
      --default-character-set=utf8mb4 \
      --binary-mode \
      --batch \
      --raw \
      < "$sql_file"
  done
  exit 0
fi

CONNECTOR_JAR="$(find "$REPO_PARENT/rigour_repository/com/mysql/mysql-connector-j" \
  -name 'mysql-connector-j-*.jar' -type f 2>/dev/null | sort | tail -n 1)"

if [[ -z "$CONNECTOR_JAR" ]]; then
  printf 'Cannot find mysql-connector-j under %s/rigour_repository.\n' "$REPO_PARENT" >&2
  exit 2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"; unset DB_PASSWORD' EXIT

cat > "$TMP_DIR/SqlFileRunner.java" <<'JAVA'
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqlFileRunner {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("At least one SQL file is required");
        }
        String url = requiredEnv("DB_URL");
        String user = requiredEnv("DB_USER");
        String password = requiredEnv("DB_PASSWORD");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            for (String file : args) {
                Path path = Path.of(file).toAbsolutePath().normalize();
                System.out.println("==> " + path);
                for (String sql : splitSql(stripLineComments(Files.readString(path, StandardCharsets.UTF_8)))) {
                    String trimmed = sql.trim();
                    if (trimmed.isEmpty()) continue;
                    boolean hasResultSet = statement.execute(trimmed);
                    if (hasResultSet) {
                        printResult(statement.getResultSet());
                    } else {
                        System.out.println("OK rows=" + statement.getUpdateCount() + " :: " + preview(trimmed));
                    }
                }
            }
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String stripLineComments(String sql) throws IOException {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.lines().toList()) {
            String stripped = line.stripLeading();
            if (!stripped.startsWith("--")) {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        boolean backtick = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char prev = i == 0 ? 0 : sql.charAt(i - 1);
            if (ch == '\'' && !dbl && !backtick && prev != '\\') {
                single = !single;
            } else if (ch == '"' && !single && !backtick && prev != '\\') {
                dbl = !dbl;
            } else if (ch == '`' && !single && !dbl) {
                backtick = !backtick;
            }
            if (ch == ';' && !single && !dbl && !backtick) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        statements.add(current.toString());
        return statements;
    }

    private static void printResult(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                if (i > 1) row.append(" | ");
                row.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i));
            }
            System.out.println(row);
        }
    }

    private static String preview(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }
}
JAVA

javac -cp "$CONNECTOR_JAR" "$TMP_DIR/SqlFileRunner.java"

export DB_URL DB_USER DB_PASSWORD
java -cp "$CONNECTOR_JAR:$TMP_DIR" SqlFileRunner "${resolved_files[@]}"
