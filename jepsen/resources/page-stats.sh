#!/usr/bin/env bash
# A dedicated process group owns the collector and all timeout/psql/sleep children.
set -u
export LC_ALL=C PGCONNECT_TIMEOUT=2 PGOPTIONS='-c statement_timeout=2000'
interval=$1
initial_elapsed=$2
client=$3
port=$4
log=$5
read -r initial_uptime _ < /proc/uptime
trap 'exit 0' TERM INT
while true; do
  read -r uptime _ < /proc/uptime
  elapsed=$(awk -v now="$uptime" -v start="$initial_uptime" -v initial="$initial_elapsed" 'BEGIN {printf "%.6f", initial + now - start}')
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  if pools=$(timeout --kill-after=1 3 "$client" -X -q -A -t --no-password \
      --host=127.0.0.1 --port="$port" --username=postgres --dbname=postgres \
      --set=ON_ERROR_STOP=1 --command='SELECT coalesce(json_agg(s), '\''[]'\''::json) FROM orioledb_page_stats() s' 2>/dev/null); then
    printf '{"timestamp":"%s","elapsed":%s,"available":true,"pools":%s}\n' "$timestamp" "$elapsed" "$pools" >> "$log"
  else
    printf '{"timestamp":"%s","elapsed":%s,"available":false,"error":"query-failed","pools":[]}\n' "$timestamp" "$elapsed" >> "$log"
  fi
  sleep "$interval" &
  wait $! || exit 0
done
