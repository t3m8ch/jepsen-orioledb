#!/usr/bin/env bash
# A dedicated process group owns the collector and all timeout/psql/sleep children.
set -u
export LC_ALL=C PGCONNECT_TIMEOUT=2 PGOPTIONS='-c statement_timeout=2000'
interval=$1
initial_elapsed=$2
client=$3
port=$4
log=$5
pools_query="SELECT coalesce(json_agg(s), '[]'::json) FROM orioledb_page_stats() s"
undo_query=
if [[ ${6:-} == undo ]]; then
  undo_query="SELECT json_build_object('pools', ($pools_query), 'undo', (
    SELECT coalesce(json_agg(json_build_object(
      'undo_type', undo_type, 'capacity_bytes', capacity_bytes,
      'last_used_location', last_used_location::text, 'written_location', written_location::text)), '[]'::json)
    FROM orioledb_undo_buffer_stats()))"
fi
sample() {
  timeout --foreground --kill-after=1 3 "$client" -X -q -A -t --no-password \
    --host=127.0.0.1 --port="$port" --username=postgres --dbname=postgres \
    --set=ON_ERROR_STOP=1 --command="$1" 2>/dev/null
}
read -r initial_uptime _ < /proc/uptime
trap 'exit 0' TERM INT
while true; do
  read -r uptime _ < /proc/uptime
  elapsed=$(awk -v now="$uptime" -v start="$initial_uptime" -v initial="$initial_elapsed" 'BEGIN {printf "%.6f", initial + now - start}')
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  if [[ -n $undo_query ]] && data=$(sample "$undo_query"); then
    data=${data//$'\n'/ }
    # Unwrap the SQL JSON object to keep the existing top-level pools field.
    printf '{"timestamp":"%s","elapsed":%s,"available":true,%s}\n' "$timestamp" "$elapsed" "${data:1:-1}" >> "$log"
  elif pools=$(sample "$pools_query"); then
    # A missing or failing undo function must not discard usable B-tree data.
    printf '{"timestamp":"%s","elapsed":%s,"available":true,"pools":%s}\n' "$timestamp" "$elapsed" "${pools//$'\n'/ }" >> "$log"
  else
    printf '{"timestamp":"%s","elapsed":%s,"available":false,"error":"query-failed","pools":[]}\n' "$timestamp" "$elapsed" >> "$log"
  fi
  sleep "$interval" &
  wait $! || exit 0
done
