# Proxy and Reverse Proxy - Senior System Design Deep Dive

This standalone document focuses on proxy and reverse proxy design for senior-level system design interviews. It includes forward versus reverse proxy behavior, layer 4 versus layer 7 concerns, security, and interview questions.

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
