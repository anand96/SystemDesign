# Senior System Design Deep Dive

This document is designed for a senior software engineer preparing for high-bar system design interviews. It focuses on five core infrastructure building blocks that appear repeatedly in large-scale systems:

1. Rate Limiter
2. Messaging Queue
3. Distributed Caching
4. API Gateway
5. Proxy and Reverse Proxy

The goal is not just to define them. The goal is to explain how to design, operate, scale, and reason about them end to end.

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

---

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

---

## 2. Messaging Queue

### What Problem It Solves

A messaging queue decouples producers from consumers so that systems can communicate asynchronously. This enables:

- Load leveling and burst absorption.
- Reliable handoff between services.
- Asynchronous workflows and background processing.
- Fanout to multiple consumers.
- Replay and recovery from downstream outages.

Messaging systems can be designed for different goals:

- Low-latency task distribution.
- High-throughput event streaming.
- Durable audit logs.
- Ordered workflows.

### Core Requirements

Functional requirements:

- Producers publish messages.
- Consumers receive messages.
- Support ack and retry behavior.
- Support retention, replay, and dead-letter handling.
- Support ordering within a scope.

Non-functional requirements:

- High durability.
- High throughput and predictable latency.
- Horizontal scalability.
- Backpressure and consumer isolation.

### Core Concepts

- Message: the unit of data.
- Topic or queue: logical destination.
- Partition or shard: scalability and ordering unit.
- Producer: writes messages.
- Consumer: reads and processes messages.
- Offset or cursor: position in the log.
- Ack: marks successful processing.
- Retention: how long data remains stored.
- DLQ: dead-letter queue for repeatedly failing messages.

### Queue vs Log

Not all messaging systems behave the same:

- Traditional queue: message is generally consumed and removed from active visibility after ack.
- Append-only log: message remains for retention period; consumers track offsets independently.

Senior distinction:

- If multiple consumer groups need independent replay, a log abstraction is often better.
- If the workload is task dispatch with work stealing, a queue abstraction may be simpler.

### High-Level Architecture

```text
Producers -> Broker Layer -> Persistent Storage
                     |
                     +-> Consumer Groups
                     +-> Metadata / Controller
                     +-> Metrics / Admin APIs
```

Important separation:

- Data plane: ingest, store, fetch, ack.
- Control plane: topic creation, partition assignment, ACLs, quotas, leader election.

### Delivery Semantics

#### At-Most-Once

- Message may be lost.
- Consumer sees each message zero or one time.

Use when:

- Loss is acceptable and duplicates are expensive.

#### At-Least-Once

- Messages may be delivered more than once.
- Consumer must be idempotent.

Use when:

- Reliability matters more than duplicates.

This is the most common practical choice.

#### Exactly-Once

- Each message affects downstream state once.

Reality:

- Exactly-once is usually scoped and expensive.
- It often means coordinated producer idempotency, transactional writes, deduplication, and careful consumer state management.

Senior answer:

- Explain exact boundaries. "Exactly-once end to end" across arbitrary external systems is rarely real without strong constraints.

### Ordering

Global ordering does not scale well. Prefer:

- Per-partition ordering.
- Per-key ordering, such as `user_id` or `order_id`.

Design implication:

- Partition by the entity whose events must stay ordered.
- Accept that unrelated entities will be processed in parallel and out of global order.

### Producer Design

Key concerns:

- Batching for throughput.
- Compression for network and storage savings.
- Idempotent producer IDs and sequence numbers to avoid duplicate appends.
- Retry policy that does not reorder messages unexpectedly.
- Partitioning strategy: random, round-robin, or key-based.

Tradeoff:

- Larger batches improve throughput but increase latency.

### Consumer Design

Key concerns:

- Pull versus push model.
- Prefetching and flow control.
- Parallelism within a consumer group.
- Offset commit timing.
- Idempotent processing.

Offset commit strategies:

- Commit before processing: risks data loss.
- Commit after processing: safer, can produce duplicates on crash.
- Commit transactionally with downstream state when possible.

### Broker Internals

#### Storage Model

Many high-throughput brokers use append-only segmented logs:

- Writes append to active segment.
- Older segments are rolled and retained.
- Sequential I/O is efficient.
- OS page cache helps read throughput.

Benefits:

- Fast append.
- Replay support.
- Efficient sequential disk access.

#### Replication

Common pattern:

- Partition leader handles writes.
- Followers replicate the log.
- Acks can wait for leader only or leader plus replicas.

Tradeoff:

- Waiting for replicas improves durability but adds latency.

Questions to answer:

- How many replicas?
- What is the minimum in-sync replica requirement?
- What happens on leader failure?

#### Metadata and Coordination

Need a metadata service to track:

- Topic definitions.
- Partition leadership.
- Consumer group membership.
- ACLs and quotas.

Failure here can stall the cluster even if the data nodes are healthy.

### Partitioning and Scalability

Partitioning is the primary scale lever:

- More partitions increase parallelism and throughput.
- Too many partitions increase metadata overhead, rebalance cost, and open-file pressure.

Partition key design is critical:

- `order_id` preserves per-order ordering.
- `tenant_id` preserves fairness per tenant.
- Bad partition keys create hot partitions.

Mitigations for hot partitions:

- Split the hottest tenant into sub-keys if strict ordering allows it.
- Separate premium tenants onto isolated topics or partitions.
- Use dynamic quotas and producer throttling.

### Backpressure and Flow Control

Without backpressure, queues become outage amplifiers.

Mechanisms:

- Consumer pull limits.
- Max in-flight bytes or messages.
- Broker-side quotas per producer or tenant.
- Retry backoff and DLQ after repeated failures.
- Downstream concurrency limits.

Important insight:

- A queue does not remove work. It stores work until some layer must absorb it.

### Retries, DLQs, and Poison Pills

Retry design should answer:

- How many attempts?
- Where is retry state stored?
- Is retry immediate, delayed, or exponential backoff?
- When do we send to DLQ?

Poison pill pattern:

- A malformed or permanently failing message keeps failing forever.

Mitigations:

- Max retry count.
- Schema validation at ingress.
- DLQ with operator workflow.
- Message versioning and compatible readers.

### Schema Management

Messages evolve. You need:

- Explicit schema versioning.
- Backward and forward compatibility policy.
- Optional fields rather than destructive changes.
- Validation at producer boundaries.

Without schema discipline, queues become long-lived compatibility traps.

### Multi-Region Design

Options:

#### Region-Local Queue

- Lowest latency.
- Simpler failure isolation.

Cons:

- Harder global replay and cross-region ordering.

#### Cross-Region Replication

- Replicate topics asynchronously between regions.

Cons:

- Conflict, duplicate, and ordering complexity.
- Higher recovery complexity.

Practical rule:

- Keep event ownership region-local when possible.
- Replicate for DR or analytics, not for synchronous write-path dependence.

### Failure Modes

- Producer retries create duplicates.
- Consumer crashes after side effect but before offset commit.
- Rebalance causes temporary pauses or duplicate processing.
- Slow consumers increase lag until storage fills.
- Leader election stalls writes briefly.
- Corrupt or incompatible schema blocks consumers.
- DLQ grows without operational ownership.

### Observability

Track:

- Produce latency and error rate.
- Consumer lag by partition and group.
- Queue depth or retained bytes.
- Ack latency.
- Retry rate and DLQ rate.
- Partition skew.
- Broker disk usage and page cache hit behavior.
- Rebalance frequency and duration.

Alerts:

- Lag increasing faster than consumer drain rate.
- One partition is much hotter than peers.
- Replication falls behind.
- DLQ volume spikes.
- Consumer group flapping.

### Security

- Encrypt in transit and at rest.
- Use ACLs per producer and consumer group.
- Prevent one tenant from flooding the broker.
- Scrub PII where replay or long retention exists.
- Audit admin operations like topic deletion or retention changes.

### Practical Design Recommendation

For general event-driven microservices:

- Use partitioned append-only logs for scalable event distribution.
- Require idempotent consumers.
- Keep per-entity ordering only where truly needed.
- Add delayed retry queues and DLQ handling with operator ownership.
- Set quotas on producers so the broker does not become a shared failure domain.

### High-Level Interview Questions

1. Design a queue for order processing with guaranteed per-order ordering.
2. How would you support both work queues and replayable event streams?
3. How would you design exactly-once semantics for payment processing?
4. What happens when consumers fall behind for hours?
5. How would you isolate a noisy producer tenant?
6. How would you design delayed retries and DLQ processing?
7. How would you run the queue across regions without corrupting ordering assumptions?

---

## 3. Distributed Caching

### What Problem It Solves

A distributed cache stores frequently used data in a faster layer than the primary database or service. It improves:

- Read latency.
- Database offload.
- Throughput.
- Resilience to transient backend slowness.

It can also store:

- Computation results.
- Session state.
- Feature flags.
- Rate limiter state.
- Materialized fragments for APIs or pages.

### What a Cache Is Not

A cache is not a source of truth unless explicitly designed that way. Treating it as authoritative without durability and consistency planning creates subtle correctness bugs.

### Core Requirements

- Fast reads and writes.
- TTL or invalidation support.
- Horizontal scaling.
- Predictable eviction behavior.
- Operational safety under cache loss.

### Caching Patterns

#### Cache-Aside

Flow:

1. Read cache.
2. On miss, read DB.
3. Populate cache.

Pros:

- Simple and common.
- Application controls what gets cached.

Cons:

- Stale data until invalidation or TTL expiry.
- Cache stampede risk on miss.

#### Read-Through

- Application asks cache.
- Cache fetches backend on miss.

Pros:

- Centralized loading logic.

Cons:

- Tighter coupling between cache layer and data source.

#### Write-Through

- Writes go to cache and backing store synchronously.

Pros:

- Cache is warmer and more consistent for reads.

Cons:

- Write latency includes cache update.

#### Write-Behind

- Write to cache first, flush to DB asynchronously.

Pros:

- Low write latency.

Cons:

- Risk of data loss and ordering bugs.

Use carefully:

- Better for derived or reconstructable data than canonical user data.

#### Refresh-Ahead

- Refresh keys before expiry based on access patterns.

Pros:

- Reduces misses for hot objects.

Cons:

- Can waste work on soon-to-cool keys.

### High-Level Architecture

```text
Clients / Services
      |
      +-> Local In-Process Cache
      |
      +-> Distributed Cache Cluster
      |
      +-> Database / Source of Truth
```

A multi-tier cache is common:

- L1: process-local cache for nanosecond to microsecond access.
- L2: remote shared cache such as Redis or Memcached.
- DB: canonical source.

### Data Modeling

Need to define:

- Cache key design.
- Value serialization format.
- TTL policy.
- Namespace or tenant isolation.
- Max object size.

Cache key examples:

- `user_profile:v3:user:123`
- `feed:v2:tenant:55:user:99:page:1`

Good keys are:

- Stable.
- Explicitly versioned.
- Easy to invalidate by prefix or version bump when needed.

### TTL and Invalidation

This is the hardest part of caching.

Strategies:

#### Time-Based Expiration

- Every key has a TTL.

Pros:

- Simple.

Cons:

- Data can be stale until expiration.

#### Event-Driven Invalidation

- On data change, publish invalidation events.

Pros:

- More current.

Cons:

- Harder to make reliable at scale.

#### Versioned Keys

- Encode object version in the key.

Pros:

- Avoids race conditions from deleting and repopulating old values.

Cons:

- Old versions must expire or be cleaned eventually.

Senior answer:

- Use a mix: short TTL plus event-driven invalidation for important data, plus versioned keys for schema or object evolution.

### Consistency Models

Most caches are eventually consistent with the source of truth.

Important cases:

- Read-after-write consistency.
- Monotonic reads.
- Cross-region consistency.

If read-after-write matters:

- Bypass cache briefly after writes.
- Invalidate synchronously on write.
- Use write-through if acceptable.
- Track per-request versions and reject stale cached objects.

### Partitioning and Replication

#### Sharding

Distribute keys across nodes using:

- Client-side consistent hashing.
- Proxy-based routing.
- Native cluster hash slots.

Tradeoffs:

- Client-side routing reduces central bottlenecks but complicates clients.
- Proxy routing simplifies clients but adds a middle layer.

#### Replication

Need to decide:

- Is the cache okay to lose entirely?
- Do we need replicas for availability?
- Is replication sync or async?

Many caches choose async replication:

- Better latency.
- Possible stale reads after failover.

### Eviction and Admission Policies

When memory fills, what leaves?

Policies:

- LRU: evict least recently used.
- LFU: evict least frequently used.
- FIFO: simple but often inferior.
- TTL-priority blend: honor expiry plus recency/frequency.

Admission is also important:

- Admit every object.
- Admit only after repeated access.
- TinyLFU-like approaches protect the cache from one-hit wonders.

Senior distinction:

- Eviction decides what to remove.
- Admission decides what should enter at all.

### Hot Keys and Cache Stampede

#### Hot Keys

Problem:

- One key receives disproportionate traffic and melts one shard.

Mitigations:

- Replicate hot objects.
- Add request coalescing.
- Introduce local L1 caches.
- Split data structure if logically possible.

#### Cache Stampede

Problem:

- Many clients miss the same key and all hit the backend simultaneously.

Mitigations:

- Single-flight or request collapsing.
- Probabilistic early refresh.
- Stale-while-revalidate.
- Jittered TTLs.
- Background refresh for hot keys.

#### Penetration and Avalanche

- Penetration: repeated misses for nonexistent keys.
  Mitigation: negative caching, Bloom filters.
- Avalanche: many keys expire together.
  Mitigation: TTL jitter, staggered warming.

### Persistence and Warmup

Questions:

- Is it acceptable to lose cache state on restart?
- How do we warm hot keys after failover?
- Should we persist snapshots or AOF-like logs?

Practical answers:

- Stateless caches are simpler.
- Durable caches help restart time but complicate operations.
- Prewarming top keys and staggered traffic ramp-up is often necessary for large systems.

### Multi-Region Design

Patterns:

- Region-local caches with independent TTLs.
- Global invalidation events.
- Replicate source-of-truth changes rather than cache entries.

Avoid:

- Synchronous cross-region cache read paths for latency-sensitive workloads.

### Failure Modes

- Cache outage causes a database thundering herd.
- Stale data persists because invalidation failed silently.
- Inconsistent serialization breaks readers after deploy.
- Eviction policy removes hot working set unexpectedly.
- Large objects fragment memory or consume network bandwidth.
- Hot keys overwhelm a shard even if cluster-wide capacity looks healthy.

### Observability

Track:

- Hit ratio by keyspace, not only globally.
- Read and write latency.
- Eviction rate.
- Memory usage and fragmentation.
- Top keys and hot shard distribution.
- Backend load with and without cache.
- Stampede events and miss amplification.

Alert on:

- Hit ratio collapse.
- Sudden eviction spike.
- DB QPS surge after cache node loss.
- Replication lag if replicas exist.

### Security

- Encrypt sensitive data if the cache holds secrets or PII.
- Use ACLs and tenant keyspace isolation.
- Avoid using raw user input directly as keys without normalization.
- Consider cache poisoning risks when content derives from untrusted inputs.

### Practical Design Recommendation

For a high-read API:

- Use L1 in-process caching for very hot tiny objects.
- Use L2 distributed cache for shared state.
- Use cache-aside with TTL jitter and request coalescing.
- Version keys and send invalidation events on source-of-truth updates.
- Design for cache loss so the database degrades gracefully instead of collapsing.

### High-Level Interview Questions

1. Design a distributed cache for user profiles with 99.9% read hit target.
2. How would you prevent a cache stampede on a hot product page?
3. How would you keep cache and database consistent after writes?
4. When would you use write-through instead of cache-aside?
5. How would you isolate tenants in a shared cache cluster?
6. What happens when the cache cluster is lost entirely?
7. How would you design a multi-region caching strategy for globally distributed users?

---

## 4. API Gateway

### What Problem It Solves

An API gateway is the entry point that sits in front of backend services and centralizes cross-cutting concerns. It can provide:

- Request routing.
- Authentication and authorization.
- TLS termination.
- Rate limiting and quota enforcement.
- Request and response transformation.
- API versioning.
- Observability.
- Canary routing and traffic shaping.

It is most useful when many clients need a stable interface while backend services evolve independently.

### What It Should Not Become

An API gateway should not become:

- A giant monolith of business logic.
- The only place where service-to-service policy exists.
- A universal fix for poor downstream service design.

If too much application logic moves into the gateway, it becomes a bottleneck and a deployment risk.

### Core Requirements

- Very high availability and low latency.
- Dynamic routing updates without restart.
- Security-first handling of auth, TLS, and policy.
- Multi-tenant isolation.
- Rich telemetry and policy auditability.

### High-Level Architecture

```text
Clients -> Edge Load Balancer -> API Gateway Fleet -> Backend Services
                                   |
                                   +-> Auth Provider / JWKS / Policy Engine
                                   +-> Service Discovery / Control Plane
                                   +-> Rate Limiter / Cache / WAF
                                   +-> Logs / Metrics / Traces
```

Important separation:

- Data plane: receives and forwards live traffic.
- Control plane: distributes routes, certs, policies, auth config, and rollout rules.

### Request Lifecycle

Typical path:

1. Accept TCP/TLS connection.
2. Terminate TLS or pass through depending on design.
3. Authenticate client.
4. Authorize route and scope.
5. Apply quotas, rate limits, and WAF rules.
6. Route to upstream using service discovery.
7. Apply retries, timeouts, circuit breaking, and load balancing.
8. Transform response if required.
9. Emit logs, metrics, and traces.

### Common Responsibilities

#### Authentication

- Validate JWTs or opaque tokens.
- Enforce mTLS for trusted clients.
- Integrate with API keys, OAuth, or internal identity systems.

Concerns:

- Key rotation.
- Token caching.
- Clock skew.
- Fail behavior when identity provider is degraded.

#### Authorization

- Route-level scope checks.
- Tenant boundary enforcement.
- Policy engine integration.

Important:

- Coarse-grained auth at gateway is useful.
- Fine-grained business authorization often still belongs in services.

#### Routing

- Host-based, path-based, method-based, or header-based routing.
- Weighted routing for canaries.
- Region or tenant-aware routing.

#### Resilience

- Timeouts.
- Retries only for safe operations and idempotent requests.
- Circuit breakers.
- Outlier detection.
- Load shedding under overload.

#### Transformation

- Header normalization.
- Protocol translation, for example REST to gRPC.
- Response aggregation in limited cases.

Warning:

- Heavy transformations increase latency and coupling.

### Gateway vs Load Balancer vs Service Mesh

- Load balancer: spreads traffic across endpoints, often layer 4 or basic layer 7.
- API gateway: client-facing policy and API management layer.
- Service mesh: east-west traffic controls between services.

Senior answer:

- Use the gateway for north-south concerns.
- Use the service mesh for east-west concerns.
- Avoid duplicating policy blindly in both places.

### Data Plane Design Choices

#### Centralized Gateway Fleet

Pros:

- Easier to manage.
- Unified policy.

Cons:

- Shared bottleneck.
- Blast radius if misconfigured.

#### Sidecar or Node-Local Gateways

Pros:

- Lower local hop latency.
- Better isolation in some internal environments.

Cons:

- Operational complexity and config distribution overhead.

Public APIs typically use centralized edge gateways backed by robust control planes.

### Control Plane Design

The control plane typically manages:

- Route definitions.
- Upstream cluster membership.
- Certificates and secrets.
- Auth config and policy bundles.
- Canary and rollout settings.
- Rate limit policies.

Requirements:

- Strong validation before rollout.
- Versioned config.
- Fast rollback.
- Incremental propagation.
- Safe default behavior on partial propagation failure.

### Performance Considerations

Gateway latency tax comes from:

- TLS handshake and cryptography.
- External auth checks.
- Header parsing and transformations.
- Retry logic.
- Logging and tracing overhead.

Optimizations:

- Keep hot auth keys cached.
- Use connection pooling upstream.
- Prefer async/non-blocking I/O in high-concurrency gateways.
- Avoid synchronous calls to remote policy services on every request.

### Security Considerations

- TLS termination and certificate rotation.
- DDoS and WAF integration.
- Header spoofing prevention.
- Request size limits and schema validation.
- Tenant-aware auth and routing.
- Audit trails for config changes.

Common pitfall:

- Trusting internal headers from clients instead of only from trusted upstream hops.

### Reliability and Failure Modes

- Bad config rollout breaks all traffic.
- Auth provider outage blocks valid traffic.
- Retry amplification overloads downstream.
- Long timeouts hold connections and exhaust resources.
- One hot route consumes shared gateway worker capacity.
- Control plane/data plane version skew produces inconsistent behavior.

Mitigations:

- Staged rollout and shadow validation.
- Local token or key caches for auth.
- Per-route resource controls.
- Strict timeout budgets.
- Fail-open versus fail-closed decisions by endpoint sensitivity.

### Observability

Track:

- Request rate by route, status, tenant, and upstream.
- End-to-end and hop latency.
- Upstream error rates.
- Auth failures.
- Rate limit rejects.
- Retry volume.
- Config version distribution across nodes.
- Saturation: CPU, memory, open connections, queue depth.

Useful questions:

- Is the gateway adding more latency than the backend?
- Are retries helping or amplifying pain?
- Which tenants or routes dominate capacity?

### Practical Design Recommendation

For a large public API platform:

- Put a stateless gateway fleet behind an edge load balancer.
- Keep policy, service discovery, and cert management in a validated control plane.
- Use local auth validation where possible rather than remote auth calls per request.
- Add per-route limits, circuit breakers, and observability by tenant.
- Keep business logic out of the gateway except for narrow edge concerns.

### High-Level Interview Questions

1. Design an API gateway for mobile and web clients using hundreds of microservices.
2. How would you support dynamic routing and canary deployments safely?
3. How would you handle authentication if the identity provider is slow or unavailable?
4. What belongs in the gateway versus in the services?
5. How would you implement tenant-aware rate limits and quotas at the gateway?
6. How would you prevent the gateway from becoming a single bottleneck?
7. How would you evolve the gateway for internal gRPC and external REST clients simultaneously?

---

## 5. Proxy and Reverse Proxy

### Core Definitions

#### Forward Proxy

A forward proxy sits between clients and the internet or another external network. The client is configured to send requests through the proxy.

Typical uses:

- Outbound access control.
- Anonymity or IP masking.
- Content filtering.
- Corporate egress policy.
- Caching of outbound content.

#### Reverse Proxy

A reverse proxy sits in front of servers. Clients think they are talking directly to the service, but the reverse proxy receives and forwards the request.

Typical uses:

- Load balancing.
- TLS termination.
- Caching.
- Compression.
- WAF and DDoS filtering.
- Routing to many backend services.
- Hiding internal topology.

### Mental Model

Who chooses the proxy?

- Forward proxy: chosen or configured by the client.
- Reverse proxy: chosen by the server operator.

Who is being represented?

- Forward proxy represents the client.
- Reverse proxy represents the server.

### High-Level Architecture

Forward proxy path:

```text
Client -> Forward Proxy -> Internet / External Service
```

Reverse proxy path:

```text
Client -> Reverse Proxy -> Application Servers
```

### Layer 4 vs Layer 7

#### Layer 4 Proxy

- Operates on TCP/UDP connections.
- Makes routing decisions using IP and port.

Pros:

- Faster and simpler.
- Works for arbitrary protocols.

Cons:

- Less visibility into HTTP semantics.

#### Layer 7 Proxy

- Understands HTTP, headers, paths, cookies, and methods.

Pros:

- Rich routing, auth, caching, compression, and WAF integration.

Cons:

- More CPU and memory overhead.
- Greater complexity.

### Reverse Proxy Responsibilities

#### Load Balancing

Algorithms:

- Round robin.
- Least connections.
- Weighted round robin.
- Consistent hashing.
- Latency-aware or outlier-aware routing.

Choice depends on:

- Session stickiness requirements.
- Heterogeneous backend capacity.
- Cache locality.

#### TLS Termination

Benefits:

- Centralized certificate management.
- Offload CPU-heavy crypto from apps.
- Simplified backend configuration.

Risk:

- Traffic between proxy and backend must still be protected if the network is not fully trusted.

#### Caching and Compression

Reverse proxies can cache:

- Static assets.
- Public GET responses.

They can also compress:

- Text responses with gzip or Brotli.

Need to consider:

- Cache-control headers.
- Vary semantics.
- Personalized content leakage.

#### Request Routing

- Route by host, path, header, or cookie.
- Support blue-green or canary traffic.
- Support path rewriting.

### Forward Proxy Responsibilities

Forward proxies typically handle:

- Egress ACLs.
- Domain filtering.
- Malware scanning.
- Centralized logging.
- TLS `CONNECT` tunneling.

In enterprises, they also help:

- Prevent data exfiltration.
- Restrict unauthorized destinations.
- Enforce compliance policies.

### Transparent vs Explicit Proxy

#### Explicit Proxy

- Client knows about the proxy and is configured to use it.

Pros:

- Clear routing model.

Cons:

- Requires client configuration.

#### Transparent Proxy

- Traffic is intercepted without explicit client configuration.

Pros:

- Easier centralized enforcement.

Cons:

- Harder debugging.
- More surprising behavior.
- TLS and protocol semantics become more delicate.

### HTTP CONNECT and TLS

Forward proxies often support `CONNECT`:

- Client asks proxy to open a tunnel to a destination.
- After the tunnel is established, encrypted TLS traffic flows through.

Important interview angle:

- Distinguish plain HTTP proxying from HTTPS tunneling.
- If the proxy performs TLS interception, that requires trusted enterprise certificates and raises security and privacy concerns.

### Reverse Proxy and Application Architecture

A reverse proxy is often the first server-side hop and may sit before:

- Web servers.
- API gateways.
- Application servers.
- Service mesh ingress.

It can be used for:

- Connection pooling and keep-alive management.
- Slow client shielding.
- Request buffering.
- Static file serving.

### Failure Modes

#### Forward Proxy

- Proxy outage blocks all outbound traffic.
- DNS resolution policy mismatch causes confusing failures.
- TLS interception breaks certificate validation.
- Proxy logs may expose sensitive destinations or metadata.

#### Reverse Proxy

- Proxy overload drops healthy backend traffic.
- Misrouting sends traffic to the wrong service version.
- Cached personalized responses leak between users.
- Incorrect timeout settings produce request pileups.
- Header forwarding mistakes break auth, client IP, or tracing.

### Important Headers and Trust Boundaries

Reverse proxies commonly manage:

- `X-Forwarded-For`
- `X-Forwarded-Proto`
- `Forwarded`
- Trace or correlation headers

Security rule:

- Only trust forwarded headers from known proxy hops.
- Strip or overwrite spoofed versions from external clients.

### Operational Considerations

- Connection limits and idle timeouts.
- Buffer sizes and large request handling.
- Health checks and outlier detection.
- Zero-downtime config reload.
- Access logs versus privacy needs.
- Certificate rotation.

### Choosing Between Them

Use a forward proxy when:

- You want to control or audit client outbound access.
- Clients should appear to originate from a managed egress point.

Use a reverse proxy when:

- You want to expose services safely and efficiently.
- You need routing, load balancing, TLS termination, or edge caching.

Use both when:

- Clients in a corporate environment access external or internal services through managed egress, while those services themselves are fronted by reverse proxies.

### Practical Design Recommendation

For internet-facing applications:

- Use reverse proxies at the edge for TLS termination, routing, compression, and request protection.
- Use layer 4 load balancing before layer 7 proxies if connection scale is very high.
- Trust proxy headers only from controlled hops.
- Keep caching rules conservative for authenticated or personalized traffic.

### High-Level Interview Questions

1. Explain the difference between a forward proxy and a reverse proxy with examples.
2. When would you choose layer 4 proxying over layer 7?
3. How would you design a reverse proxy for TLS termination and path-based routing?
4. What risks exist when a reverse proxy caches authenticated responses?
5. How would a corporate forward proxy handle HTTPS traffic?
6. How would you preserve real client IP through multiple proxy layers safely?
7. What are the failure modes if the reverse proxy is misconfigured or overloaded?

---

## End-to-End Design Scenarios That Combine These Components

### Scenario 1: Public API Platform

Components:

- Reverse proxy at the edge.
- API gateway for auth, routing, and quotas.
- Rate limiter for per-tenant fairness.
- Distributed cache for hot metadata and auth keys.
- Messaging queue for async jobs such as billing events and webhooks.

Senior discussion points:

- Separate online request path from asynchronous side effects.
- Apply coarse edge protection first, then fine-grained tenant quotas.
- Cache public metadata aggressively; cache sensitive objects carefully.
- Use queues to decouple webhook delivery and retry handling.

### Scenario 2: E-Commerce Checkout

Components:

- API gateway for client traffic.
- Reverse proxy/load balancer at the edge.
- Queue for order events, payment processing retries, inventory updates.
- Cache for catalog, inventory snapshots, pricing fragments, and sessions.
- Rate limiter for abuse protection on login, cart, and checkout APIs.

Senior discussion points:

- Differentiate availability requirements between browse flows and payment flows.
- Explain idempotency for order placement and payment retries.
- Keep cache consistency tight for inventory-related reads.
- Put stricter fail-closed decisions around security-sensitive endpoints.

### Scenario 3: Chat or Notification Platform

Components:

- API gateway for client auth and routing.
- Queue or log for fanout and offline delivery.
- Cache for presence, user metadata, and hot conversation state.
- Rate limiter for spam prevention.
- Reverse proxy for websocket or HTTP ingress.

Senior discussion points:

- Distinguish online low-latency path from offline durable path.
- Discuss per-user versus per-conversation ordering.
- Handle reconnect storms and hot partitions.
- Consider regional locality for latency-sensitive sessions.

---

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
