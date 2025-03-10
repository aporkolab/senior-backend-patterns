# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the Senior Backend Patterns project.

## Index

| ID | Title | Status | Date |
|----|-------|--------|------|
| [ADR-001](0001-lock-free-circuit-breaker.md) | Lock-Free Circuit Breaker Implementation | Accepted | 2024-01-15 |
| [ADR-002](0002-token-bucket-vs-sliding-window.md) | Rate Limiter Algorithm Selection | Accepted | 2024-01-16 |
| [ADR-003](0003-outbox-skip-locked.md) | Outbox Pattern with SKIP LOCKED | Accepted | 2024-01-17 |
| [ADR-004](0004-dlq-failure-categorization.md) | Dead Letter Queue Failure Categorization | Accepted | 2024-01-18 |
| [ADR-005](0005-virtual-threads-async.md) | Virtual Threads for Async Pipeline | Accepted | 2024-01-19 |

## ADR Template

```markdown
# ADR-XXX: Title

## Status
Proposed | Accepted | Deprecated | Superseded

## Context
What is the issue that we're seeing that is motivating this decision?

## Decision
What is the change that we're proposing and/or doing?

## Consequences
What becomes easier or more difficult to do because of this change?
```

## References

- [ADR GitHub Organization](https://adr.github.io/)
- [Michael Nygard's ADR Article](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
