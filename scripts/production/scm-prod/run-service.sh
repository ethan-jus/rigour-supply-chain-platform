#!/usr/bin/env bash
set -euo pipefail

service="${1:?service is required}"
case "$service" in
  foundation) heap=128 ;;
  gateway|crm|hr) heap=192 ;;
  iam|integration|erp|order|bi) heap=256 ;;
  *) echo "Unsupported scm-prod service" >&2; exit 1 ;;
esac

: "${NACOS_SERVER_ADDR:?}" "${NACOS_NAMESPACE:?}" "${NACOS_USERNAME:?}" "${NACOS_PASSWORD:?}"
: "${RIGOUR_CONTEXT_TRUST_KEY_V1:?}"
if [[ "$service" != gateway ]]; then
  : "${DB_URL:?}" "${DB_USERNAME:?}" "${DB_PASSWORD:?}"
  : "${REDIS_HOST:?}" "${REDIS_PORT:?}" "${REDIS_PASSWORD:?}"
fi
case "$service" in
  integration|erp|order)
    : "${RIGOUR_COS_REGION:?}" "${RIGOUR_COS_BUCKET:?}"
    : "${RIGOUR_COS_SECRET_ID:?}" "${RIGOUR_COS_SECRET_KEY:?}"
    ;;
esac

export RIGOUR_LOCAL_SECRETS_ENABLED=false
exec /usr/local/jdk21/bin/java \
  -Xms128m "-Xmx${heap}m" -Xss512k -XX:+UseSerialGC \
  -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m \
  -XX:MaxDirectMemorySize=32m -XX:ActiveProcessorCount=2 \
  -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai \
  -Duser.home=/root/rg_scdp \
  -jar "/root/rg_scdp/${service}-server.jar" \
  --spring.profiles.active=prod --spring.flyway.enabled=false \
  "--logging.file.name=/root/rg_scdp/logs/${service}.log" \
  --logging.logback.rollingpolicy.max-file-size=20MB \
  --logging.logback.rollingpolicy.max-history=5 \
  --logging.logback.rollingpolicy.total-size-cap=100MB
