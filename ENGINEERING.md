# Engineering principles

Apply these principles to Battle Cats. Project-specific requirements in
[AGENTS.md](AGENTS.md) and the Battlecode guidance in
[CONTRIBUTING.md](CONTRIBUTING.md) take precedence where they disagree.

## Architecture and layering (from LLVM)

- Define one explicit intermediate representation as the contract between stages. Stages talk only through it, never through each other's internals, so any stage can be swapped or reused.
- Give that representation a readable text form that can be dumped, diffed and loaded. Then each stage can be tested on its own: feed in a text input, check the text output.
- Run a verifier on the representation's invariants between stages, at least in debug and test builds, so a broken stage is caught where it broke instead of three stages later.
- Make each pass or stage do one transformation, and state what it needs as input and what it guarantees about its output.
- Build everything as a library. Command-line tools, servers and UIs are thin drivers over the libraries, holding no logic of their own.
- Dependencies point one way, from higher layers to lower ones. A lower layer never imports a higher one.

## Module and API design

- Make modules deep: a small interface hiding substantial work. Don't add wrappers that only forward calls.
- Keep interfaces small. One or two methods (like Go's `io.Reader`) compose better than ten.
- Accept the most general input type you need and return a concrete type.
- Make the zero or default value of a type usable and safe wherever possible.
- Model the data before writing functions. Choose types so that invalid combinations can't be constructed, for example an enum with data per variant instead of several booleans and optional fields.
- Parse untrusted input into a precise type once, at the boundary. Code inside the boundary takes that type and doesn't re-check.
- Keep logic pure and push I/O, clocks, randomness and global state to the edges, passed in as parameters. The pure core then needs no mocks to test.
- Assume anything observable will be depended on: output order, error text, timing. Expose only what you're willing to keep stable.
- Don't abstract until there are three real uses. If an abstraction is fighting you, inline it and start again.
- Before deleting code that looks pointless, find out why it was added (git blame, the commit, the linked issue).

## Simplicity

- Prefer clear code over clever code. Optimize for a reader who doesn't know the context.
- Prefer the standard library and the language's usual idioms. Add a dependency only when it saves real work; for a few lines, copy them.
- Build the simplest version that works end to end, then improve it based on measurement and real use.
- Don't build for requirements nobody has asked for.
- To change behavior, first refactor so the change is easy (in a separate commit if possible), then make the change.

## Testing (from SQLite, TigerBeetle, FoundationDB, Go)

- Treat the test suite as part of the product. A bug fix isn't done until a test reproduces the bug and now passes.
- Test the failure paths, not just the happy path. Inject failures: make each allocation, file write, network call or syscall fail in turn and check the system stays consistent.
- Simulate crashes at every write point and check that recovery gives a valid state.
- Fuzz parsers and anything that takes external input.
- Aim for branch coverage of core logic, and test boundary values: empty, one, max, off by one, overflow.
- Make time, randomness, scheduling and I/O injectable, so the whole system can run deterministically from a seed and any failure can be replayed exactly.
- Put assertions in production code for preconditions, postconditions and invariants. If an assertion fails, crash rather than continue with corrupt state.
- Give every loop, queue, buffer and retry an explicit upper bound.
- Write table-driven tests: a list of cases (name, input, expected) run through one loop.
- Test protocol and HTTP code against in-process servers and recorders (like Go's `httptest`), not mocks of the HTTP library.
- Test behavior through public interfaces so tests survive refactors. Prefer real or fake collaborators to mocks that check call sequences.
- Keep tests fast and hermetic: no shared state, no order dependence, no real network or wall clock.

## Errors and failure

- Treat errors as values. Handle each error where it happens or return it with context added (what was being attempted, with which input). Never swallow one silently.
- Keep errors matchable by type or kind, not by string comparison.
- Decide which layer owns each kind of failure. Lower layers report; the layer that knows the policy decides whether to retry, fall back or fail.
- Put a timeout or deadline on every network call and every wait, and pass deadlines and cancellation down the call chain.
- Retry only idempotent operations, with exponential backoff, jitter and a cap. Use idempotency keys for operations that must not run twice.
- Fail fast on invalid configuration and startup errors, before serving traffic.

## Concurrency (from Tokio, Swift concurrency, ScyllaDB/Seastar)

- Use structured concurrency. A spawned task belongs to a scope (task group, `JoinSet`, nursery) that waits for it. When the scope exits, its children are cancelled and their errors reach the parent. No fire-and-forget tasks.
- Give mutable state a single owner, such as an actor or a task reached through a channel, instead of sharing it under locks.
- With actors or async code, assume state may have changed across every `await`. Re-check conditions after the await instead of relying on values read before it.
- Never block an async runtime thread. Move blocking I/O and heavy CPU work to a dedicated blocking pool.
- Make code safe to cancel at any `await`: don't leave half-applied state if the future is dropped or the task cancelled.
- Use bounded channels and queues so that backpressure reaches the producer, rather than letting memory grow without limit.
- For high throughput, partition data so that each core or worker owns its shard outright. Cross shards only by passing messages, not with locks or atomics on shared data.

## Performance

- Measure before optimizing, and optimize the measured bottleneck. Benchmark before and after.
- Do back-of-envelope estimates early. Memory, SSD, network and cross-region latencies differ by orders of magnitude.
- Prefer contiguous arrays to pointer-chasing structures. When a hot loop reads one field of many records, store that field contiguously (struct of arrays).
- Avoid allocating on hot paths. Preallocate, reuse buffers or use pools.
- Batch small operations (writes, syscalls, network round trips).
- Pick algorithms by their complexity at real input sizes. A simple O(n) scan often beats a clever structure for small n.

## Data and storage

- State the guarantees explicitly: consistency level, durability (when is data really on disk), ordering, and at-least-once vs. at-most-once delivery.
- Keep schema and wire-format changes backward compatible. Add the new field or column, migrate readers and writers, and only then remove the old one.
- Store timestamps in UTC with explicit units and zones. Use monotonic clocks for measuring durations.

## Code hygiene

- If something is hard to name, the abstraction is probably wrong. Names state what a thing is or does, with units where relevant (`timeout_ms`).
- Comments explain why the code is the way it is: constraints, trade-offs, non-obvious invariants. Don't narrate what the code does.
- Keep commits small and focused on one change, with a message that explains why.
- Keep unrelated cleanups out of a functional change; send them separately.
- Delete dead code instead of commenting it out. Version control keeps the history.
