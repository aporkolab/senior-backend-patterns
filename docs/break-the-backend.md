# Break the backend

Launch your own copy of the existing demo, force the payment gateway to fail, watch its circuit open, and prove that it recovers. Then break notifications while payments keep working.

[Open this repository in Codespaces](https://codespaces.new/aporkolab/senior-backend-patterns)

Choose the **Break the backend** dev container. Its first build installs Java 21, Maven and Python 3, compiles the three demo applications, and starts PostgreSQL, Kafka and ZooKeeper. The Java applications start automatically whenever the container starts. Maven and image downloads can take several minutes on first launch. Wait for `./lab status` to show all three services as UP.

In the terminal:

```bash
./lab run
```

The command makes actual HTTP requests to the existing application. Orders pass through PostgreSQL and the transactional outbox, Kafka delivers the events, and the demo services execute the repository's circuit-breaker implementation. No paid external API, model key or remote demo server is involved. Creating a Codespace uses your GitHub Codespaces allowance; stop it when finished.

## The experiment

| Phase | Change | Checked observation |
| --- | --- | --- |
| Healthy | Both failure rates set to `0` | An order receives a completed payment and payment-confirmation notification. |
| Break | Payment failure rate set to `1` | Three gateway timeouts open the circuit. |
| Protect | Create one more order | Its payment gets the open-circuit fallback. |
| Recover | Payment failure rate back to `0`; wait for the 10-second open window | First successful probe leaves the circuit HALF_OPEN; the second closes it. |
| Isolate | Notification failure rate set to `1` | Payment still completes; its notification event is classified TRANSIENT in the DLQ. |
| Resume | Notification failure rate back to `0` | A new order receives a confirmation again. |

Each check uses its own order ID, so old records cannot satisfy a new run. An unexpected response, missing event or timeout returns a nonzero exit code. A complete run writes `.lab/last-run.json`; a new run removes any previous receipt before starting. Both failure rates return to zero even if you interrupt the command.

This demo intentionally simulates the payment gateway and notification delivery. The HTTP calls, persistence, events and circuit transitions are real; no money is charged and no email is sent. The notification demo sends transient failures directly to its DLQ, without replay or automatic retries. Recovery proves that **new** events work; it does not replay the failed message. Order status remains PENDING because this demo does not consume payment results back into the order record; the lab checks the payment API for payment status.

## Inspect it yourself

Open the **Ports** panel. Ports 8081, 8082 and 8083 are forwarded for orders, payments and notifications. Open one and append `/swagger-ui.html`, or use the complete clickable URLs printed by `./lab status`. Leave forwarded ports private: the failure controls are deliberately unauthenticated and belong in this disposable lab.

```bash
# Circuit state: CLOSED, OPEN or HALF_OPEN
curl -s http://localhost:8082/api/v1/payments/circuit-breaker/state

# Inspect failed notification events
curl -s http://localhost:8083/api/v1/notifications/dlq

# Follow the actual state transitions and event processing
tail -f .lab/payment.log .lab/notification.log
```

Follow the implementation in [PaymentService](../demo-app/payment-service/src/main/java/com/aporkolab/demo/payment/PaymentService.java), [CircuitBreaker](../circuit-breaker/src/main/java/com/aporkolab/patterns/resilience/circuitbreaker/CircuitBreaker.java), [OutboxProcessor](../demo-app/order-service/src/main/java/com/aporkolab/demo/order/OutboxProcessor.java), and [NotificationService](../demo-app/notification-service/src/main/java/com/aporkolab/demo/notification/NotificationService.java).

## Change something

Try changing `failureThreshold(3)` or `successThreshold(2)` in `PaymentService`. Predict which assertion will fail, then rebuild and rerun:

```bash
./lab stop
./lab build
./lab start
./lab run
```

The assertions deliberately describe the default configuration. If you change the thresholds, a failed check is the expected result until you update the experiment too.

`./lab stop` stops only the Java processes it started. It neither deletes database records nor stops other applications. Stop the Codespace to stop its infrastructure as well. The local runtime files and receipts stay under ignored `.lab/`.

## Local development and verification

The same configuration works with VS Code's **Dev Containers: Reopen in Container** command and Docker Compose. PostgreSQL and Kafka are isolated sidecars with no published host ports; there is no Docker socket mounted into the workspace. The slim lab does not start the existing demo's Grafana, Prometheus or Kafka UI stack.

```bash
# Check the lab client without a running backend
python3 -m unittest discover -s scripts/tests -v

# Exercise the underlying pattern tests
mvn -B -ntp -pl circuit-breaker,outbox-pattern,rate-limiter -am test

# Full HTTP/event-chain verification in the dev container
./lab run
```

If startup fails, read `.lab/order.log`, `.lab/payment.log` and `.lab/notification.log`. If Maven fails, rerun `./lab build` after the dependency-download or compilation error is resolved. A service that never becomes healthy fails startup instead of reporting a ready lab.

The **Break the backend lab** GitHub Actions workflow builds this same dev container and runs both the client checks and `./lab run` on changes to the lab or its services. It does not create a Codespace or publish a container image.
