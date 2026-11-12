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

## Follow-up: sustained buffer pressure without manual checkpoint

The first experiment above validates checkpoint annotations only: its main pool
was far from full. This second experiment uses the same supported 8MiB main /
32MiB undo settings, but removes artificial micro-operation delays and uses
primary-key lookups and the standard on-conflict append tactic to sustain writes.
No manual CHECKPOINT, external data generator, configuration reload or source
change was used.

Exact invocation:

```sh
mise run test:run -- --trace-page-writes --main-buffers 8MB --undo-buffers 32MB --workload append --key-count 1000000 --key-dist uniform --key-types primary --upsert-types on-conflict --mop-delay 0 --time-limit 600 --concurrency 16 --rate 2000 --nemesis none
```

Read-only SSH inspections saved UTC times, pool statistics,
`pg_stat_checkpointer`, `pg_stat_user_tables`, `pg_stat_activity`,
checkpoint settings, directory bytes and the server process list. At
16:33:00Z, main occupancy was 851/1024 and 5,716 raw events were already
present; both checkpoint counters were zero. At 16:34:19Z, occupancy was
1015/1024 and there were 52,108 events, still with no checkpoints.
At 16:35:35Z, occupancy was 1022/1024 and 110,347 events, still with
zero checkpoint counters. At 16:36:54Z, 168,575 events preceded any
checkpoint (timed/requested counters both zero).

At 16:34:33Z, `pg_total_relation_size` for txn0..txn5 returned
2,252,800; 2,252,800; 2,195,456; 2,236,416; 2,277,376; 2,277,376 bytes:
13,492,224 total, exceeding the 8,388,608-byte main pool. Table statistics
at that time reported 208,015 inserts and 20,744 updates. These are
measured table sizes and write statistics, not an inference from key-count.

The first observed page writes at 16:32:41.621Z came from PID 132112,
identified independently by both pg_stat_activity and ps as OrioleDB background
writer 0; PostgreSQL checkpointer PID was 132109.
Pinned source `src/workers/bgwriter.c` lines 155–175 starts pool
maintenance when free pages fall below 5% or dirty pages exceed 50%.
The measurements crossed both thresholds. This supports buffer-pressure
maintenance with observed page writes; DEBUG messages do not themselves
count evictions, identify a pool, or prove fsync/device persistence.
The log's `checkpoint 1` field is a checkpoint identifier, not evidence
that the emitting process is executing a checkpoint.


Final evidence:

- Run store: `jepsen/store/orioledb-append-read-committed/2026-11-12T20:31:55.984795866+04:00/`.
- 600 seconds of standard workload; **502,976 successful transactions**,
  **617,766 append micro-operations in successful transactions**, zero failed
  or indeterminate transactions. Counts were read independently from
  `history.edn` and transaction totals agree with checker statistics.
- Peak sampled main busy/all: **1024/1024 (100%)**. The six measured
  table sizes totaled **28,581,888 bytes** at 16:41:16Z (3.41 main pools);
  table statistics then reported 458,978 inserts and 125,241 updates.
- **368,230 parsed events = 368,230 raw matching log messages**.
  First event elapsed 39.393052791s; last 601.689052791s.
  Sample range 1.701218–601.451218s; 368,100 events are within range.
  The final 130 events occurred after the last sample and remain in JSONL;
  they are not drawn outside the sampled range.
- **173,944 events precede the first automatic checkpoint**, which started
  at 16:37:01.982Z. It completed at 16:38:07.612Z. A second timed
  checkpoint started at 16:42:01.705Z near workload completion.
  Events during/after these checkpoints are retained but not asserted to
  originate from eviction. Full PID counts and checkpoint log lines are
  in `issue11-pressure-counts.json`; 709 events in the complete log
  are from checkpointer PID 132109, and 144,184 from OrioleDB
  background writer PID 132112. The remaining events include workload backends.
- Installed `build-info.json` again matches the exported pinned artifact:
  OrioleDB `47b822246687cf2e16f677496f6a1d1330dc2912`,
  PostgreSQL `48a9a8ff96b01ad2db32b2782e2a4f0cff78097c`.
  The saved PostgreSQL config confirms unchanged small buffers, UTC/PID
  logging and debug1. Time alignment uses the same per-node sample anchor
  as the first experiment.
- The initial runner terminated with signal TERM (exit 143) during analysis,
  after completing workload and exporting logs. Its initial exit status is
  **not** a successful validity verdict. The complete history was subsequently
  checked using the standard offline analyzer, without rerunning workload:
  `lein run analyze --test store/orioledb-append-read-committed/2026-11-12T20:31:55.984795866+04:00`
  from `jepsen/`, with the same LAB_ARTIFACT/LAB_SSH_*/LAB_DATA_HOST
  environment. The saved `issue11-pressure-analyze.sh` records exact values.
  This completed with exit 0 and `results.edn` reports overall
  **:valid? true**, including workload, page-stats, exceptions and performance.
- Annotated `page-stats-main.png` was visually inspected: the rise to full
  occupancy, sustained dirty occupancy and grouped write markers remain
  legible. All raw logs/events, history, results, three PNGs, read-only
  monitor snapshots, size probes, independent cross-check script and analyzer
  logs are preserved in the parent thread storage under
  `issue-11-results/buffer-pressure/`. First-experiment artifacts are unchanged.
- Only experiment documentation changed for this follow-up; feature code and
  workload implementations are unchanged. Repository checks from the
  implementation remain applicable (22 Python tests; 20 Clojure tests /
  258 assertions and namespace checks passed). Evidence review checked raw
  counts, history counts, PID attribution, table byte totals, occupancy peak,
  automatic checkpoint boundaries and actual result-file validity.

Before-first-checkpoint PID distribution (independent UTC comparison):

```json
{
  "132112": 88469,
  "133247": 4,
  "132228": 5194,
  "132221": 5626,
  "132226": 5504,
  "132235": 5068,
  "132232": 4985,
  "132233": 5281,
  "132230": 5252,
  "132227": 5338,
  "132222": 5327,
  "132234": 5115,
  "132220": 5741,
  "132231": 5237,
  "132224": 5735,
  "132225": 5501,
  "132229": 5256,
  "132223": 5287,
  "134103": 23,
  "135177": 1
}
```
