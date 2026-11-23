"""Summarize saved append and rw runs without starting the lab."""

import argparse
import json
import re
from datetime import datetime
from pathlib import Path
from statistics import median


def txn_stats(run):
    invoked = operations = committed = writes = 0
    with (run / "history.edn").open() as history:
        # Jepsen writes one EDN operation per line. Both workloads use
        # integer keys and a vector of :r, :w, or :append micro-operations.
        for line in history:
            if ":f :txn," not in line and ":f :read," not in line:
                continue
            write_count = line.count("[:w ") + line.count("[:append ")
            if ":type :invoke," in line:
                invoked += 1
                operations += line.count("[:r ") + write_count
            elif ":type :ok," in line:
                committed += 1
                writes += write_count
    return [f"{operations / invoked:.1f}" if invoked else "n/a", committed, writes]


def undo_stats(run):
    samples = {kind: [] for kind in ("row", "page", "system")}
    for path in run.glob("*/page-stats.jsonl"):
        with path.open() as source:
            for line in source:
                sample = json.loads(line)
                if sample.get("available"):
                    for item in sample.get("undo") or []:
                        capacity = item["capacity_bytes"]
                        if capacity > 0:
                            used = int(item["last_used_location"]) - int(item["written_location"])
                            samples[item["undo_type"]].append(100 * used / capacity)
    return [f"{median(v):.2f}/{max(v):.2f}" if v else "n/a" for v in samples.values()]


def recent_runs(store, count):
    candidates = []
    for run in store.glob("*/*/history.edn"):
        directory = run.parent
        if directory.is_symlink() or not re.search(r"-(append|rw)-", directory.parent.name):
            continue
        started = datetime.fromisoformat(directory.name)
        candidates.append((started, directory))
    if not candidates:
        raise ValueError(f"No saved append or rw histories in {store}")
    return [directory for _, directory in sorted(candidates)[-count:]]


def summarize(path):
    run = path.resolve(strict=True)
    if not re.search(r"-(append|rw)-", run.parent.name):
        raise ValueError(f"Expected an append or rw run directory: {run}")
    with (run / "jepsen.log").open() as log:
        limit = next((m[1] for line in log if (m := re.search(r":max-txn-length (\d+)", line))), "n/a")
    trace = run / "evictions.jsonl"
    evictions = "n/a"
    if trace.exists():
        with trace.open() as source:
            evictions = sum(json.loads(line)["event"] == "undo_ring_evict" for line in source)
    return [run.name, limit, *txn_stats(run), *undo_stats(run), evictions]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--last", type=int, metavar="N",
                        help="Show the latest N saved append or rw runs, oldest first")
    parser.add_argument("runs", nargs="*", type=Path, help="Run directories (default: jepsen/store/latest)")
    args = parser.parse_args()
    if args.last is not None:
        if args.last < 1:
            parser.error("--last must be a positive integer")
        if args.runs:
            parser.error("choose either --last or explicit run directories")
    store = Path(__file__).resolve().parents[1] / "jepsen/store"
    rows = [["Run", "Limit", "Mean ops/txn", "OK txns", "OK writes",
             "Row % med/max", "Page % med/max", "System % med/max", "Undo evictions"]]
    try:
        runs = recent_runs(store, args.last) if args.last else args.runs or [store / "latest"]
        rows.extend(summarize(run) for run in runs)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    widths = [max(len(str(value)) for value in column) for column in zip(*rows)]
    for row in rows:
        print("  ".join(str(value).ljust(width) for value, width in zip(row, widths)).rstrip())
