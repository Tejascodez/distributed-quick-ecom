#!/usr/bin/env sh
# One-time Neon setup: creates the per-service databases and checks logical replication.
# Safe to re-run. Uses a throwaway psql container, so no local Postgres install is needed.
set -e
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

psql() {
  docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" postgres:18.6-alpine \
    psql "host=$DB_HOST port=${DB_PORT:-5432} user=$DB_USERNAME dbname=$1 sslmode=${DB_SSLMODE:-require}" -v ON_ERROR_STOP=1 -qtA -c "$2"
}

for db in auth_db store_db order_db inventory_db delivery_db; do
  if [ "$(psql neondb "SELECT 1 FROM pg_database WHERE datname = '$db'")" = "1" ]; then
    echo "exists   $db"
  else
    psql neondb "CREATE DATABASE $db" && echo "created  $db"
  fi
done

psql store_db "CREATE EXTENSION IF NOT EXISTS postgis"
echo "postgis  $(psql store_db "SELECT extversion FROM pg_extension WHERE extname = 'postgis'")"

wal=$(psql order_db "SHOW wal_level")
if [ "$wal" = "logical" ]; then
  echo "wal_level logical (Debezium outbox OK)"
else
  echo "wal_level=$wal: enable Neon console -> Project settings -> Logical Replication, then re-run" >&2
  exit 1
fi
