# `:tn-runtime` — Engine Actor, Queues, MemoryGovernor & Lifecycle Coordinator

- **Owner Phase:** F04
- **Dependencies:** `:tn-core`, `:tn-store`
- **Responsibilities:**
  - `Runtime.start(config, ports): Outcome<AppGateway, StartError>`
  - Single-thread confined `Engine`, `Render`, and `Store` executors + bounded `1..4` thread CPU worker pool (`OwnerGuard` thread-confinement checks).
  - `MemoryGovernor`: category byte reservations (`64 MiB` model, `32 MiB` undo, `32 MiB` raster tiles, `32 MiB` derived, `16 MiB` queues, `96 MiB` graphics, `8 MiB` draft window), max 2 render snapshots, max 3 warm document sessions, and deterministic cancellation release.
  - Bounded queues with overflow policies: input samples (`4 MiB` / `64 KiB` batch), reserved terminal control (`64` slots), durable persist FIFO (`256` envelopes / `8 MiB`) + reserved `DurableAck` (`256` slots / `256 KiB`), latest view mailbox (`max 2`), and coalesced Main events (`256` / `2 MiB`) + error channel (`64` / `128 KiB`).
- **Verification:** `./gradlew :tn-runtime:testDebugUnitTest`
