# Issue #9 validation

## Automated checks

- `mise run check`: shell syntax, Python compilation, 22 Python tests, Clojure tests and `lein check` passed. Existing reflection/autoboxing warnings remain in vendored code and the existing database SQL helper.
- Final `lein test`: 16 tests, 227 assertions, zero failures/errors.
- CLI red/green: the new positive-interval test initially failed with six assertions because the option was unknown; it now passes, including NaN/infinity/zero/negative rejection and metadata preservation.
- Actual Bash collector test covers multiline PostgreSQL JSON output, connection failure, bounded hung query, recovery, and termination while a query is blocked. The fake client uses `exec sleep` to model the single-process `psql` binary.
- Plot tests invoke real gnuplot and check PNG output, NaN gaps, missing pools, disabled collection, empty logs, and entirely unavailable data. Database LogFiles tests check stop-before-export and conditional log selection; nemesis wrapper tests cover setup failure cleanup and normal teardown.

## Review

Reviewed against `origin/master` (`923749db6bc1f7175515dbc442b400bca070ea88`) using the implement skill's code-review workflow, with independent Standards and Spec reviewers.

### Standards

Zero documented-standard violations or substantial baseline smells. English artifacts and Conventional Commits verified. Collector startup, failed setup, and shutdown reviewed.

### Spec

Review identified PostgreSQL `json_agg` multiline output as incompatible with JSONL; fixed by flattening structural newlines and verified by regression test and live runs. The initial live append run before this fix had no plots and was excluded from the final evidence. The timeout test initially modeled a shell plus child instead of psql; corrected to a single blocking process. `timeout --foreground` keeps queries in the collector process group for cleanup. Gnuplot NaN line gaps and Debian start-stop-daemon/setsid PID behavior were checked. No outstanding substantive requirement gaps found.

## Live runs

All runs were sequential via `mise run test:run`, which holds the shared repository lab lock. No VM reset, destruction, or interruption of another test was performed.

Common explicit settings: `--engine orioledb --isolation read-committed --concurrency 4 --rate 20 --key-count 100 --main-buffers 8MB --undo-buffers 32MB --page-stats-interval 1`. Remaining workload options use CLI defaults, fully preserved in each run's `test.edn`.

| Workload | Time limit | Nemesis | Store directory under `jepsen/store/` |
| --- | --- | --- | --- |
| append | 40 s | none | `orioledb-append-read-committed/2026-11-12T13:04:40.039208334+04:00` |
| rw | 40 s | none | `orioledb-rw-read-committed/2026-11-12T13:05:55.614130554+04:00` |
| internal | 60 s (+10 s recovery allowance) | kill,pause, 5 s interval | `orioledb-internal-read-committed/2026-11-12T13:07:04.374892482+04:00` |

Append and rw each exported 40 available snapshots, all three pools, all three
plots, and finished with `:valid? true`. Internal with kill/pause exported 70
snapshots (46 available, 24 unavailable); unavailable periods are visible as gaps
in every pool plot, and sampling resumed after recovery. Its workload verdict was
**`:valid? false`**: the internal checker reported changing repeated reads, e.g.
key 66 read as 32 then 76 within a successful transaction at elapsed 22.411 s.
This run used read-committed; no checker or history semantics were weakened to
suppress these findings. These observations do not by themselves establish an
OrioleDB bug or an eviction count.

All selected runs exported node `192.168.121.178/page-stats.jsonl` and
`page-stats-{main,free-tree,catalog}.png` at their store roots. Plots were opened
and visually inspected. Baseline plots were regenerated from the original
collected JSONL after disabling gnuplot enhanced text (to preserve underscores)
and moving the legend outside the data region.
