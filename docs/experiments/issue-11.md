# Page-write observation validation

The implementation uses the two existing DEBUG1 messages in OrioleDB
`47b822246687cf2e16f677496f6a1d1330dc2912`, verified against
`src/btree/io.c` lines 1645 and 1718 at that SHA. PostgreSQL is pinned to
`48a9a8ff96b01ad2db32b2782e2a4f0cff78097c`. The installed VM
`build-info.json` matched the exported artifact.

Invocation:

```sh
mise run test:run -- --trace-page-writes --main-buffers 8MB --undo-buffers 32MB --workload append --key-count 10000 --key-dist uniform --time-limit 180 --concurrency 8 --rate 300 --nemesis none
```

Read-only inspection of the running VM confirmed effective values:
main buffers 1024 × 8kB (8MiB), undo buffers 4096 × 8kB (32MiB),
`log_min_messages=debug1`, `log_timezone=UTC`, and
`log_line_prefix='%m [%p] '`. Sampling defaults to one second.

A preliminary invocation without `--key-dist uniform` failed in the upstream
Elle generator before workload execution (`bound must be finite and positive`);
it is not counted as validation.

Each event's elapsed time is the first node sample's elapsed value plus the
difference between server-log UTC time and that sample's UTC timestamp.
The VM clock is the reference for both timestamps; download time is irrelevant.
Monotonic sampling is unchanged. Clock steps during a run remain a limitation.

These are post-attempt observations, with no pool identity or fsync guarantee.

Review: the supporting tdd/code-review skills were unavailable in the installed
skill catalogs. Explicit review checked opt-in behavior, engine handling,
fresh per-run raw logs, both parser variants, malformed-line rejection,
time calibration, full event retention, dense marker grouping and diagnostic-only
checker errors. The renderer's series options were inspected: it does not
support per-series axes, so markers use points at the occupancy plot's top value.

During workload execution, an explicit checkpoint was requested at
2026-11-12T16:25:43Z (VM UTC):
```sh
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i /home/t3m8ch/.local/state/jepsen-orioledb/vagrant/machines/n1/libvirt/private_key vagrant@192.168.121.178 "date -u +%FT%TZ; sudo -u postgres /opt/jepsen/pgsql/bin/psql -h 127.0.0.1 -c CHECKPOINT; sudo grep -c 'Wrote .*disk page:' /var/log/jepsen/postgresql.log"
```
It returned CHECKPOINT and 103 observed messages immediately afterward.
This is a controlled checkpoint experiment, not evidence of buffer-pressure eviction.

Final run: `jepsen/store/orioledb-append-read-committed/2026-11-12T20:24:04.505779944+04:00/`.
The run returned exit 0 and overall `:valid? true` (workload, exceptions,
performance and page-stats checkers valid). There are exactly **103** raw
DEBUG page-write messages and **103** JSONL events, all from PID 128747.
All fall inside the sample range 1.734831–181.574831 seconds:
event range 92.586868171–92.678868171 seconds. They form one displayed
one-second marker group. Raw files are
`192.168.121.178/postgresql.log`, `192.168.121.178/page-stats.jsonl`,
`192.168.121.178/build-info.json`, and root `page-writes.jsonl`.
Cross-check used an independent Python regex over the raw log and counted
JSONL records, then compared every event elapsed time to sample endpoints.
The graph was visually inspected; all three occupancy series remain visible.

Repository checks: 22 Python tests pass; Clojure suite and namespace checks pass
(the upstream rw namespace emits its existing primitive boxing warning).
Additional tests exercise both parser variants, multiple PIDs, malformed input,
time alignment, default/explicit sampling, engine config, empty streams,
real PNG rendering, annotation grouping and preservation of occupancy series.
