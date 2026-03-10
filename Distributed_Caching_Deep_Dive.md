# Distributed Caching - Senior System Design Deep Dive

This standalone document focuses on distributed caching design for senior-level system design interviews. It includes caching patterns, invalidation, consistency tradeoffs, hot-key handling, and interview questions.

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
