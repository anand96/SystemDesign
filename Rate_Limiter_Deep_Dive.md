# Rate Limiter - Senior System Design Deep Dive

This standalone document focuses on rate limiter design for senior-level system design interviews. It includes problem framing, algorithms, distributed design, failure modes, and interview questions.

## How to Think Like a Senior Engineer in a System Design Interview

For each subsystem, drive the conversation through the same lens:

- Clarify the problem, success metrics, and non-goals.
- Estimate scale: requests per second, data volume, fanout, object size, peak load, regional distribution.
- Define the API or contract first.
- Identify the critical path and failure domains.
- Choose the consistency model intentionally.
- Explain the data model, sharding strategy, replication, and hot-key behavior.
- Cover the control plane and data plane separately.
- Discuss operability: observability, deployment, rollback, incident response.
- Explain tradeoffs, not just the final answer.
- State what you would build now versus what you would postpone.

## Cross-Cutting Questions You Should Ask Before Designing Anything

1. What are the functional requirements and non-functional requirements?
2. What is the expected peak QPS, data size, and read/write ratio?
3. Is low latency more important than strong consistency?
4. Is the workload bursty, steady, or highly seasonal?
5. What are the availability and durability targets?
6. Do we need regional isolation or multi-region active-active behavior?
7. What are the tenant boundaries and fairness requirements?
8. What are the security constraints: auth, encryption, compliance, data residency?
9. How will clients retry, back off, and handle partial failure?
10. What metrics and alerts tell us the design is healthy?

## 1. Rate Limiter

### What Problem It Solves

A rate limiter controls how frequently a client, service, user, API key, IP, or tenant can perform an action over time. Its goals usually include:

- Protecting downstream services from overload.
- Enforcing fairness between clients or tenants.
- Reducing abuse, scraping, brute force attacks, and noisy neighbor problems.
- Managing cost by controlling expensive operations.
- Smoothing bursts so the backend sees traffic it can safely absorb.

### Typical Requirements

Functional requirements:

- Support limits per key, IP, user, tenant, route, or method.
- Support multiple windows, for example `100 requests/minute` and `10 requests/second`.
- Support hierarchical quotas, for example per-user under per-tenant.
- Return allow/deny decisions with remaining quota metadata.
- Support dynamic policy changes without restarts.

Non-functional requirements:

- Very low decision latency, often sub-millisecond to low single-digit milliseconds.
- High availability. The limiter itself must not become the outage.
- Predictable behavior under bursty load.
- Correct enough for the business. Exactness is expensive.

### High-Level Architecture

```text
Client -> Edge/API Gateway -> Rate Limiter Service -> State Store
                                   |
                                   +-> Config Store / Policy Store
                                   |
                                   +-> Metrics / Logs / Traces
```

Core components:

- Decision engine: computes allow/deny.
- Policy store: stores limits, dimensions, exemptions, and rollout state.
- State store: tracks counters, tokens, or in-flight concurrency.
- Enforcement point: gateway, service mesh sidecar, or application middleware.
- Telemetry pipeline: exposes drops, burst behavior, fairness, and saturation.

### Common Algorithms

#### Fixed Window Counter

Idea:

- Count requests in discrete windows like minute buckets.
- Allow if `count < limit`.

Pros:

- Very simple and cheap.
- Easy to implement in Redis or in-memory.

Cons:

- Boundary problem: a client can send `limit` requests at the end of one window and `limit` more at the start of the next.

Use when:

- Some burstiness is acceptable.
- You need simplicity and low cost.

#### Sliding Log

Idea:

- Store timestamps of recent requests.
- For each decision, remove expired timestamps and count the remainder.

Pros:

- Most accurate window semantics.

Cons:

- High memory and CPU cost at high QPS.

Use when:

- Limits are strict and QPS per key is manageable.

#### Sliding Window Counter

Idea:

- Track counts for current and previous windows.
- Interpolate effective count based on overlap.

Pros:

- Good balance between accuracy and cost.

Cons:

- More complex than fixed window.

Use when:

- You want smoother behavior without the memory footprint of a sliding log.

#### Token Bucket

Idea:

- Tokens are refilled at a steady rate.
- Requests consume tokens.
- Bucket size controls burst tolerance.

Pros:

- Excellent for burst absorption.
- Intuitive mental model.

Cons:

- Requires precise refill logic or approximation.

Use when:

- You want to allow bursts while enforcing long-term rate.

#### Leaky Bucket

Idea:

- Requests are queued or shaped so output rate is smooth.

Pros:

- Good for traffic shaping.

Cons:

- Can introduce waiting rather than immediate rejection.

Use when:

- Smoothing is more important than instant deny.

#### Concurrency Limiter

Idea:

- Limit the number of in-flight requests instead of requests per time window.

Pros:

- Protects scarce backends such as DB connection pools or thread pools.

Cons:

- Requires accurate release of permits on timeout, failure, and cancellation.

Use when:

- Backend saturation depends more on active work than arrival rate.

### API and Data Model

Example decision API:

```http
POST /v1/check
{
  "key": "tenant:123:user:456",
  "resource": "POST:/payments",
  "cost": 1,
  "timestamp_ms": 1710000000000
}
```

Example response:

```json
{
  "allowed": true,
  "limit": 100,
  "remaining": 42,
  "reset_at_ms": 1710000060000,
  "retry_after_ms": 0
}
```

Policy model often includes:

- Key dimensions: user, tenant, IP, API key, region, route.
- Rate: refill rate or count per window.
- Burst size.
- Priority tier.
- Cost weighting: expensive endpoints may consume more tokens.
- Override rules and temporary exemptions.

### State Storage Choices

#### In-Process Memory

Pros:

- Fastest possible decision.
- Zero network hop.

Cons:

- Does not work well across replicas unless limits are local only.
- Restarts lose state.

Best for:

- Local protection of a single instance.
- Pre-filtering before consulting a shared limiter.

#### Central Store Like Redis

Pros:

- Shared global-ish view across replicas.
- Atomic operations via Lua scripts or transactions.

Cons:

- Adds network latency.
- Central dependency can become hot or overloaded.

Best for:

- Per-user or per-tenant limits enforced across many stateless app servers.

#### Hybrid Local + Global

Pattern:

- Each instance keeps a local token cache or local shadow limits.
- It periodically syncs or borrows quota from a shared backend.

Pros:

- Reduces load and latency on the central store.
- Degrades gracefully.

Cons:

- Becomes approximate.
- Requires careful handling of oversubscription.

This is often the most practical production design.

### Distributed Design Considerations

#### Sharding

Shard state by a stable limiter key:

- `hash(user_id) % N`
- `hash(tenant_id, route) % N`

Watch for:

- Hot keys for large tenants or popular APIs.
- Poor cardinality distribution.
- Resharding complexity.

Mitigations:

- Salt heavy keys into sub-buckets.
- Use hierarchical enforcement: tenant-level plus local instance-level.
- Use weighted fair sharing rather than a single flat global counter.

#### Atomicity

A distributed limiter needs atomic read-modify-write behavior. Typical approaches:

- Redis `INCR` with TTL for simple windows.
- Lua scripts for token bucket updates and decision logic in one round-trip.
- Compare-and-swap in a strongly consistent KV store for stricter semantics.

#### Accuracy vs Availability

You must choose:

- Fail-open: allow traffic if limiter is unavailable. Better availability, weaker protection.
- Fail-closed: reject traffic if limiter is unavailable. Better protection, riskier for user experience.
- Partial fallback: fall back to coarse local limits when the shared limiter fails.

Senior answer:

- State explicitly which endpoints fail-open and which fail-closed. Login, payment, or abuse-sensitive APIs often choose differently from read-only APIs.

### Multi-Region Design

Options:

#### Region-Local Limits

- Each region enforces independently.
- Fast and highly available.
- Not globally exact.

Good when:

- Traffic is sticky to one region.
- Small cross-region overages are acceptable.

#### Global Shared State

- All regions consult one globally replicated store.
- Better global correctness.
- Higher latency and more failure coupling.

Good when:

- Strict quotas matter, such as paid API plans with hard caps.

#### Quota Partitioning

- Split global quota across regions, for example `60% us-east`, `40% eu-west`.
- Rebalance periodically.

Good when:

- You need bounded overrun without cross-region calls on every request.

### Common Failure Modes

- Counter store outage causes universal throttling or universal allow, depending on policy.
- Clock skew breaks window boundaries or refill logic.
- Hot tenant overloads a single shard.
- Retry storms bypass intended traffic shaping if retries are not counted.
- Missing idempotency means a denied client may keep retrying aggressively.
- Async config propagation leads to inconsistent policy decisions across nodes.

### Performance and Capacity Planning

Ask:

- How many decisions per second?
- What is the cardinality of limiter keys?
- How many active keys per window?
- What percentage are reads versus writes?

Rules of thumb:

- Sliding logs grow with request volume.
- Token buckets grow with active key cardinality.
- Global limiters often need batching, local caches, or token borrowing to avoid store saturation.

### Observability

Track:

- Allow rate, deny rate, and shadow deny rate.
- Decision latency p50/p95/p99.
- Per-policy hit rate.
- Hot key frequency.
- Backend store saturation.
- Config propagation delay.
- False positives and false negatives where measurable.

Useful alerts:

- Sudden global deny increase.
- High limiter backend latency.
- Sharp increase in fail-open path.
- One tenant consuming disproportionate quota.

### Security and Abuse Considerations

- IP-based limits are weak behind NAT or botnets.
- User-based limits require robust authentication and anti-account farming.
- API key limits need secure key management and rotation.
- Limits should consider route sensitivity. Password reset and OTP flows need tighter abuse controls.
- Attackers probe for per-node inconsistency. Global enforcement gaps matter.

### Practical Design Recommendation

For a large public API:

- Enforce coarse local token buckets at the gateway for immediate protection.
- Enforce shared per-tenant quotas using Redis or a low-latency distributed KV.
- Keep policy in a separate config store with staged rollout.
- Support shadow mode before hard enforcement.
- Use explicit `Retry-After` semantics and count retries.

### High-Level Interview Questions

1. Design a rate limiter for a multi-tenant public API with free and paid tiers.
2. How would you implement a global quota across multiple regions?
3. How would you support both short-term burst control and monthly billing caps?
4. What would fail-open versus fail-closed look like for login and payments?
5. How would you prevent one tenant from dominating shared limiter capacity?
6. How would you design weighted requests where some operations cost 10x more?
7. How would you migrate from per-instance local limiting to distributed limiting?

## Common Senior-Level Tradeoffs to State Explicitly

- Exactness versus availability.
- Latency versus durability.
- Simplicity versus flexibility.
- Local autonomy versus global coordination.
- Shared infrastructure efficiency versus tenant isolation.
- Fast rollout versus safe rollout.
- Stateless design versus warm state and caches.

Interviewers often care less about whether you picked Redis, Kafka, or Envoy specifically and more about whether you can explain:

- Why that class of system fits.
- What breaks first at scale.
- Which correctness guarantees are real.
- How you would operate it during incidents.

## Final Interview Checklist

Before ending your answer, make sure you covered:

1. Requirements and scale.
2. API or interface.
3. High-level architecture.
4. Data model and partitioning.
5. Critical algorithms.
6. Consistency and failure handling.
7. Security and abuse resistance.
8. Observability and SLOs.
9. Capacity bottlenecks and future scaling path.
10. Clear tradeoffs and alternative designs.
