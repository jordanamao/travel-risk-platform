#!/usr/bin/env sh
set -eu

if [ -n "${DATABASE_URL:-}" ] && [ -z "${JDBC_DATABASE_URL:-}" ]; then
  case "$DATABASE_URL" in
    postgres://*|postgresql://*)
      db_url="${DATABASE_URL#postgres://}"
      db_url="${db_url#postgresql://}"
      credentials="${db_url%%@*}"
      host_and_database="${db_url#*@}"

      export JDBC_DATABASE_USERNAME="${JDBC_DATABASE_USERNAME:-${credentials%%:*}}"
      export JDBC_DATABASE_PASSWORD="${JDBC_DATABASE_PASSWORD:-${credentials#*:}}"
      export JDBC_DATABASE_URL="jdbc:postgresql://${host_and_database}"
      ;;
  esac
fi

exec java -jar app.jar "$@"
