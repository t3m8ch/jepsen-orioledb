#!/bin/bash
set -eu

# The script is needed to distinguish between cases where a specific ps call
# is stuck and when the postgres process does not terminate after SIGKILL
# for an extended period.

next_log=0
while :; do
  if processes=$(timeout --foreground 1s ps -u postgres -o pid=,stat=,wchan:32=,comm=); then
    # Zombies cannot write to PGDATA.
    live=$(awk '$4 == "postgres" && $2 !~ /^Z/' <<<"$processes")
  else
    rc=$?
    case $rc in
    1) exit 0 ;; # No processes matched the user.
    124)
      echo 'PostgreSQL process probe: ps timed out' >&2
      exit 70
      ;;
    *) exit "$rc" ;;
    esac
  fi

  [[ -n $live ]] || exit 0
  if ((SECONDS >= next_log)); then
    printf 'Still waiting for postgres (PID STAT WCHAN COMMAND):\n%s\n' "$live" >&2
    next_log=$((SECONDS + 1))
  fi
  sleep 0.1
done
