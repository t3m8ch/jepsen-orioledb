# OrioleDB + Jepsen: Local Testbed

Five identical Debian 12 VMs running under libvirt/KVM, PostgreSQL 17 + OrioleDB built in
rootless Podman, and Jepsen running on the controlling Linux host. Jepsen currently uses
**only n1**. There is no replication, failover, or topology selection.
PostgreSQL runs directly in the VM, without a container.

## Host Requirements

The initial version targets Linux x86_64. You need:

- KVM with access to `/dev/kvm`, libvirt with a `qemu:///system` connection, QEMU,
  libvirt network support (usually dnsmasq), and `virsh`;
- rootless Podman with configured subuid/subgid;
- Git with `git subtree`, an OpenSSH client, Bash, Python 3, `bsdtar`, `curl`, `unzip`, and `gnuplot`;
- a C compiler, `make`, `pkg-config`, and libvirt headers for building the
  `vagrant-libvirt` plugin (the `libvirt-dev` / `libvirt-devel` package);
- mise 2026.11.0 or newer. The JDK, jq, Leiningen, Ruby, and Vagrant versions
  are pinned in `mise.toml`. Vagrant is installed as a Ruby gem through mise;
- approximately 8 GiB of RAM for the five VMs plus memory for the controlling JVM,
  and free disk space for the VMs and builds. The build uses up to four make jobs.

System services and KVM/libvirt permissions are configured using the distribution's tools.
Project tasks do not change system service settings or install system packages.
`vagrant-libvirt` manages the storage pool. If the `default` pool does not exist, the provider
creates it in `/var/lib/libvirt/images/vagrant`; this path is set in the `Vagrantfile`.
`vm:destroy` deletes the VMs but retains the pool and base box disk for the next run.
`vm:halt` shuts down all five VMs to free RAM while keeping their disks and data.
Start them again with `vm:up`.
The `192.168.77.0/24` network must be available on the host. Each VM has a separate libvirt
management interface and an isolated test network; synced folders are disabled.

## Shared Local Lab

All checkouts and Git worktrees for the same user share **one set of five VMs**.
Mise tasks use `${XDG_STATE_HOME:-$HOME/.local/state}/jepsen-orioledb/` for:

- `vagrant/`: Vagrant machine identities and SSH keys;
- `lab.lock`: an exclusive lock held for the full command, including the entire Jepsen test.

These are local runtime files, never committed to Git. VM disks remain in the system
libvirt storage pool. Build exports and test results stay in the invoking checkout.
Use the same `XDG_STATE_HOME` in all agent sessions; changing it selects separate state.

VM operations (including status, except `vm:ssh`), builds, cache verification/pruning,
and tests wait for this shared lock. `lab:run` holds it continuously from the build
through VM startup and the smoke test. This prevents other lab tasks from stopping
or provisioning the VMs or starting a competing test, and protects the shared Podman
build tag. SSH sessions remain available for debugging during a test.

Update every worktree to this task implementation before running agents concurrently:
older tasks still use checkout-local locks. Use the mise tasks for lab operations;
raw `vagrant` commands and direct script invocation bypass the shared wrapper.
Do not delete the shared state or lock file while commands are running.

## First Run

From the repository root:

```sh
mise trust
mise install
mise run vm:plugins
mise doctor project
mise run lab:run
```

Host checks are declared in `[doctor.checks.*]` in `mise.toml`.
`mise run doctor` remains an alias for `mise doctor project`.

`lab:run` sequentially obtains/builds the binary artifact, starts and provisions
**all five VMs**, and runs a short `append` test on n1 without faults.
The default isolation level is `read-committed`, with the expected consistency model of the same name:
OrioleDB does not yet support `serializable`.
Results remain in `jepsen/store/`. The VMs are kept after the test, and the database is cleaned up.
A consistency model violation causes Jepsen to exit with a nonzero status.

The steps can also be run separately:

```sh
mise run build
mise run vm:up
mise run vm:provision
mise run vm:status
mise run test:smoke
mise run test:run -- --time-limit 120 --concurrency 16 --rate 50 --nemesis kill,pause
mise run vm:halt
mise run vm:destroy
```

### Open a VM shell

Select any VM from `n1` through `n5`:

```sh
mise run vm:ssh -- n3
```

The task uses Vagrant's connection settings and generated SSH key. Missing or invalid
names show usage; unavailable VMs report their state and suggest `mise run vm:up`.
It uses the shared runtime state without taking the lab lock, so you can open a shell
while tests run. Other lab tasks can proceed while the shell is open; `vm:halt`,
`vm:reset`, or `vm:destroy` can interrupt the session. Commands you run in the shell
can affect a running test.

### Open the local database

Inside a provisioned VM shell, run:

```sh
lab-psql
```

This opens the lab build's psql client on `127.0.0.1:5432`, using database
`postgres` and user `postgres`, for either PostgreSQL or OrioleDB. Extra psql
arguments are supported, for example `lab-psql -c 'SELECT version();'`.
The local test database must already be running on that VM. Jepsen currently
starts it only on n1 during a test and removes it (including the client) at
teardown; provisioning alone does not start a database. The command reports
missing clients and unavailable databases with guidance.

The host test holds the lab lock, so open a separate SSH session using Vagrant's
connection settings before starting it; `vm:ssh` waits for the test to finish.
For VMs provisioned before this command was added, run `mise run vm:provision`.

### Restore VMs without recreating them

After `vm:up` / `vm:provision`, and **before running Jepsen**, save a clean baseline:

```sh
mise run vm:snapshot
# Run tests, then discard all changes inside the five VMs:
mise run vm:reset
```

Both commands hold the shared lab lock throughout. `vm:snapshot` shuts down all
five VMs, creates internal qcow2 snapshots named `jepsen-baseline` through libvirt,
and starts the VMs again without provisioning. Only disk state is captured, not RAM.
The baseline includes Vagrant's already-installed SSH key.

`vm:reset` checks all five snapshots and VM identities before changing anything,
forcibly powers off the VMs (including unresponsive guests), restores the snapshots,
and starts all five without provisioning. **All guest changes since the baseline
are lost**, including test data and guest logs. Host build caches and `jepsen/store/`
are retained. Reset can be repeated; it never creates a missing baseline implicitly.

After changing provisioning, prepare clean VMs and explicitly refresh the baseline:

```sh
mise run vm:provision
mise run vm:snapshot -- --replace
```

Provisioning does not remove old test files: after a test, reset to the old baseline
before provisioning, or recreate the VMs if no clean baseline exists. Snapshots
capture the current disk contents; saving one does not itself clean the guests.

Baseline identities are recorded in `baseline.json` alongside the shared Vagrant
state. A destroyed/recreated VM invalidates that record. An interrupted capture
leaves no usable complete baseline; retry with `--replace`. Capture and restore
are sequential across VMs, not a transaction across all five. On failure, some
VMs may remain stopped or already restored; correct the error and retry. Commands
use the configured `qemu:///system` connection; no extra snapshot plugin is needed.

Select the storage engine with `--engine postgres` or `--engine orioledb`:

```sh
mise run test:run -- --engine postgres --workload append --time-limit 30 --nemesis none
mise run test:run -- --engine orioledb --workload append --time-limit 30 --nemesis none
```

The default is `orioledb`, including `test:smoke`. Both modes use the same cached
PostgreSQL build (with OrioleDB patches); `postgres` runs without preloading or
creating the OrioleDB extension and uses standard `heap` tables. All workloads,
isolation options, and faults work with either engine. The selected engine is
saved as `:engine` in the test metadata and prefixes the test name in `jepsen/store/`.

Set OrioleDB buffer sizes before server startup with `--main-buffers` and
`--undo-buffers`. Each accepts a positive integer followed by `kB`, `MB`, `GB`, or
`TB`. Omitted options leave the server defaults in place. Both options are ignored
when using `--engine postgres`; they do not change PostgreSQL `shared_buffers`.

```sh
mise run test:run -- --main-buffers 8MB --undo-buffers 32MB --key-count 1000 --time-limit 600
```

The selected build enforces its own minimum buffer sizes, which can depend on
the configured number of server processes. PostgreSQL rejects sizes below those
limits at startup. Small buffers and a large key count alone do not establish
that page eviction occurred; verify eviction when interpreting the run.

Arguments after `--` are passed to Jepsen. Upstream SQL workloads are available,
for example `--workload append`, `rw`, `internal`, `internal-sim`, and `default-value`.
The isolation level and expected model are set using the standard `--isolation` and
`--expected-consistency-model` options. Available faults are `none`, `kill`, `pause`,
and `kill,pause`. After injecting faults, the generator clears them and resumes the workload
to verify recovery. `kill` means SIGKILL to PostgreSQL processes, not VM power loss.
The `--node` / `--nodes` options do not switch nodes: only n1 from Vagrant is used.
SSH access is taken from `vagrant ssh-config n1`, including the Vagrant-generated
key. JDBC connects to `192.168.77.11`.

```sh
mise exec -- lein -h  # Show the tool version and help
cd jepsen
mise exec -- lein run test --help
```

To view the results, run `lein run serve` from `jepsen/`.

## Project Layout

- `mise.toml`: tool versions, pinned build inputs, and tasks.
- `infra/Vagrantfile`: exactly five VMs, each with 2 vCPUs and 1536 MiB of RAM; the
  Debian box version and SHA256, networks, and provisioner invocation.
- `infra/provision.sh`: one script for all VMs, safe to run repeatedly. It installs runtime packages,
  process-management tools, users, and sudo. It also installs GCC,
  `wget`, and `ntpdate`, which are required by the standard upstream nemesis setup. It does not install PostgreSQL.
- `build/Containerfile`: the Debian build stage and a final stage containing only artifacts.
- `build/build.sh`: build, smoke test, and packaging inside the container.
- `scripts/build.sh` and `scripts/plugins.sh`: build invocation and plugin installation.
- `scripts/test.py`: runs Jepsen and parses the Vagrant SSH configuration using
  the Python standard library. Mise tasks invoke it through `scripts/lab.py`, which owns shared state and locking.
- `jepsen/project.clj`: a single Leiningen project. `:source-paths` directly includes
  both subtrees. Nested `project.clj` files are not read automatically. When updating
  upstream, compare its external dependencies with our `project.clj`.
- `jepsen/src/jepsen/orioledb/`: the CLI and DB adapter.
- `jepsen/resources/`: PostgreSQL configuration files.

Generated files are not committed: `.cache/`, `infra/.vagrant/`, `jepsen/target/`,
and `jepsen/store/`, plus the shared runtime state described above.

## Reproducibility and Build

`mise.toml` pins the OrioleDB and patched PostgreSQL SHAs, the Debian OCI image
by digest, the APT snapshot, package sets, and compilation parameters. The Debian release,
architecture, and snapshot are identical for the builder and VMs. The Vagrant box version
is pinned in the `Vagrantfile`. Floating branches are not used in normal builds.

To change the OrioleDB version, update `LAB_ORIOLEDB_SHA`, read `.pgtags` from **that
commit**, and set the corresponding PostgreSQL 17 SHA in `LAB_POSTGRES_SHA`.
The build script verifies this pair; OrioleDB also checks the patchset during compilation.
Dependency and image updates are explicit changes to tracked files.

The builder runs as an unprivileged user. PostgreSQL and contrib are installed
into `/opt/jepsen/pgsql`, and then OrioleDB is installed there using this build's
`pg_config`. The smoke test creates a cluster with locale C, loads the extension,
creates an OrioleDB table, and verifies the data after a restart. The source-build
container runs without network access (`RUN --network=none`). Sources and packages are downloaded
by the preceding Containerfile steps. Download retries handle transient Debian Snapshot errors.

The final `FROM scratch` stage contains only `pgsql.tar.gz`, `build-info.json`,
`SHA256SUMS`, `ldd.txt`, and `smoke.log`. `podman build --output type=local,dest=...`
exports these files to the host. After successful checksum verification, the result
replaces `.cache/artifacts/current/`. The full build log is stored in `.cache/build.log`.
`build-info.json` is also included in the installation archive. It contains revisions,
build parameters, and exact runtime package versions.

This provides reproducibility of inputs and experimental conditions. Bit-for-bit identity
of compilation results and the Jepsen concurrent schedule is not guaranteed.

## Binary Cache

Podman manages the cache. Compilation runs in `RUN`, so a repeated
`mise run build` uses the existing layers. Changing source SHAs, build args,
the Containerfile, or build.sh causes the affected steps to run again.
There is no custom cache key. The current result is tagged
`localhost/jepsen-orioledb:build`.

Before each test, the project builds and exports using the current parameters:
Podman uses the cache, and Jepsen receives binaries matching the configuration.
Exported file checksums are verified before installation in the VM.

```sh
mise run cache:list
mise run cache:inspect
mise run cache:verify
mise run cache:prune
```

`list` shows the testbed images, `inspect` shows details of the latest export, and
`verify` checks its checksums. `prune` invokes standard Podman cleanup
filtered by the testbed label and asks for confirmation. Untagged images are removed;
this may remove cache layers and trigger a full recompilation. Images from other
projects are not affected.

A particular build can be preserved with an additional tag, for example:

```sh
podman tag localhost/jepsen-orioledb:build localhost/jepsen-orioledb:experiment-1
```

After the next build, the `experiment-1` tag will continue to point to the saved
artifacts. Delete a specific saved build using the standard
`podman image rm localhost/jepsen-orioledb:experiment-1` command.

Mise tasks use `scripts/lab.py` to acquire the shared lab lock described above:
builds, tests, VM operations, and cache changes run sequentially. The wrapper holds
the lock until the command exits. `vm:ssh` passes `--no-lock` to reuse the shared
Vagrant state while allowing concurrent operations. Run operations through `mise run`.
Independent concurrent testbeds require a separate
host because the VM names and subnet are fixed here.

The old `.cache/sources/` and `.cache/artifacts/<old-hash>/` directories from the previous
implementation are no longer used and are not deleted automatically. A failed
build retains `.cache/build.log` and does not replace the previous successful export.

## Database Lifecycle and Results

Provisioning prepares Debian. The Jepsen DB adapter:

1. Checks the Debian release, architecture, and exact runtime versions against the build.
2. Uploads the archive and verifies its SHA256 on the node.
3. Installs the binaries and creates a fresh PGDATA using `initdb --locale=C`.
4. Configures the selected engine: OrioleDB preloading, extension, and default access method,
   or no preloading/extension and the `heap` default for PostgreSQL. Creating a table
   without `USING` verifies and logs the actual access method in both modes.
5. Implements `Kill` and `Pause` for the nemesis.
6. Provides logs, configuration files, and `build-info.json` through `jepsen.db/LogFiles`.
7. Deletes PGDATA and the installation after the standard diagnostic collection.

Jepsen stores the history, parameters, and results in its `store`. The test map
includes the artifact checksum; information about the installed build is copied from the node
through `LogFiles`. There is no custom run manifest.

PostgreSQL trust authentication is used on the trusted, isolated test network;
SSH uses the Vagrant key and sudo. Do not connect these VMs to a public network.

## Subtrees and Upstream

The following repositories were imported in full with `--squash`:

- `https://github.com/jepsen-io/sql`, prefix `vendor/jepsen-sql`, source commit
  `8bc137c722be0c5c4d2a2258d73dd875aa339fe8`;
- `https://github.com/jepsen-io/postgres`, prefix `vendor/jepsen-postgres`, commit
  `60480a0f09313ade150408befc3f58ae1e4ccbad`.

Local changes within the subtrees are committed normally and used directly by the next run,
without `lein install` or JAR publication.
With a clean working tree, update upstream explicitly:

```sh
mise run upstream:sql
mise run upstream:postgres
mise run check
```

Git stores the imported revisions in `git-subtree-*` trailers on the squash commits.
Regular builds and tests do not update the subtrees or modify the test sources.

## Checks Without VMs

```sh
mise run check
```

This checks shell syntax, Jepsen test construction, node/network selection,
and all Clojure namespaces. `cache:verify` checks binary cache integrity.
Full verification requires `mise run lab:run` on a host with KVM/libvirt.

### Page-pool occupancy

Opt in to periodic OrioleDB snapshots and automatic PNG plots:

```sh
mise run test:run -- --engine orioledb --workload append --time-limit 60 --page-stats-interval 1
```

`--page-stats-interval SECONDS` accepts finite positive durations (including
fractions), is saved in test metadata, and is disabled when omitted. It is ignored
with `--engine postgres`. Sampling runs on the database VM independently of
workload operations and correctness checking. Each query has a two-second server
statement timeout and a three-second client deadline (with a one-second forced
termination fallback); a fresh connection is used for each query. The interval is
the delay between completed attempts, so slow or unavailable queries reduce the
sampling frequency.

The node log `page-stats.jsonl` contains UTC timestamps, elapsed seconds, availability,
and every pool and column returned by `orioledb_page_stats()`. Elapsed time uses VM
uptime calibrated to Jepsen's elapsed origin when the collector starts (subject to
the short SSH startup delay); it does not depend on matching host/VM wall clocks.
The collector and its children stop before final log export, and each run starts
with a fresh log. Exported logs live under the node directory in the run store.

The run automatically renders `page-stats-main.png`, `page-stats-free-tree.png`,
and `page-stats-catalog.png` at the store root, with `busy_pages`, `dirty_pages`,
and `all_pages` against elapsed seconds. Failed queries and absent pools are gaps,
never zero occupancy. Empty logs or pools with no measurements produce no plot;
plotting errors are reported under `:page-stats` without changing the workload's
validity verdict. These are occupancy snapshots, not cumulative write or eviction
counters; falling occupancy alone does not establish eviction activity.

Add `--trace-page-writes` to enable global DEBUG1 logging before startup and
annotate these plots with observed page-write attempts. It automatically sets
`--page-stats-interval 1` unless an explicit interval is supplied. Both options
are saved in test metadata and ignored with `--engine postgres`; ordinary runs
retain their logging behavior.

Both `Wrote plain disk page` and `Wrote disk page` messages are exported to
`page-writes.jsonl` at the run root, including UTC timestamp, elapsed seconds,
node, PID, checkpoint and optional size. The original node `postgresql.log` is
retained. Events use each node's first sample UTC timestamp and elapsed value as
their time reference, not log download time. This assumes the VM wall clock
does not jump during the run; sampling itself still uses monotonic uptime.
Markers group events per second within the sampled time range; the JSONL retains
all events, including startup events outside that range. Markers share all pool
plots because the messages do not identify a pool. These observations are
post-attempt logging points, not proof of device persistence or successful fsync,
and do not count clean evictions. Empty streams add no markers. Export/plot
errors are diagnostic and do not change the correctness verdict.
