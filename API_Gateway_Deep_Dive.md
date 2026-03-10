# API Gateway - Senior System Design Deep Dive

This standalone document focuses on API gateway design for senior-level system design interviews. It includes routing, auth, control plane versus data plane, resilience, and interview questions.

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
