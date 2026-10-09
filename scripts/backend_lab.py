#!/usr/bin/env python3
"""Run the existing three-service demo and assert its real HTTP/Kafka behavior."""

import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / ".lab"
SERVICES = {"order": 8081, "payment": 8082, "notification": 8083}
MODULES = ",".join(f"demo-app/{name}-service" for name in SERVICES)


class LabError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise LabError(message)


def request(service, path, body=None, *, allow_missing=False):
    url = f"http://127.0.0.1:{SERVICES[service]}{path}"
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        detail = error.read(500).decode(errors="replace")
        raise LabError(f"{req.get_method()} {url}: HTTP {error.code}: {detail}") from error
    except (OSError, ValueError) as error:
        raise LabError(f"{req.get_method()} {url}: {error}") from error


def wait_for(description, probe, *, timeout=30, interval=0.25):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = probe()
        if last:
            return last
        time.sleep(interval)
    raise LabError(f"Timed out waiting for {description} ({timeout}s). Last result: {last!r}")


def rate(service, value):
    prefix = "/api/v1/payments/circuit-breaker" if service == "payment" else "/api/v1/notifications"
    result = request(service, f"{prefix}/failure-rate", {"rate": value})
    require(result.get("failureRate") == value, f"{service} did not accept failure rate {value}")


def circuit():
    return request("payment", "/api/v1/payments/circuit-breaker/state")["state"]


def create_order(label):
    order = request("order", "/api/v1/orders?clientId=backend-lab", {
        "customerId": f"lab-{label}-{uuid.uuid4().hex[:8]}",
        "productId": "resilience-workshop", "quantity": 1, "amount": 19.95,
    })
    require(isinstance(order, dict) and bool(order.get("id")), "Order API returned no order ID")
    return str(order["id"])


def payment(order_id, expected):
    result = wait_for(f"payment result for {order_id}", lambda: request(
        "payment", f"/api/v1/payments/{order_id}", allow_missing=True))
    require(result.get("orderId") == order_id, "Payment belongs to another order")
    require(result.get("status") == expected,
            f"Expected {expected} for {order_id}, received {result}")
    return result


def notification(order_id):
    return wait_for(f"payment confirmation for {order_id}", lambda: next((item for item in
        request("notification", "/api/v1/notifications")
        if item.get("subject") == "Payment Confirmation" and order_id in item.get("body", "")), None))


def health(name):
    result = request(name, "/actuator/health")
    require(result.get("status") == "UP", f"{name} is not healthy: {result}")
    return result


def jar_path(name):
    return ROOT / "demo-app" / f"{name}-service" / "target" / f"{name}-service-1.0.0.jar"


def owned_pid(name):
    """Never signal a reused PID or an unrelated Java process."""
    try:
        pid = int((STATE / f"{name}.pid").read_text())
        command = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
        return pid if b"-jar" in command and str(jar_path(name)).encode() in command else None
    except (OSError, ValueError):
        return None


def build():
    require(shutil.which("mvn"), "Maven is missing. Open this repository in its dev container.")
    subprocess.run(["mvn", "-B", "-ntp", "-pl", MODULES, "-am", "package", "-DskipTests"],
                   cwd=ROOT, check=True)
    for name in SERVICES:
        require(jar_path(name).is_file(), f"Build produced no JAR for {name}")


def start_services():
    require(shutil.which("java"), "Java 21 is required. Open the dev container first.")
    if any(not jar_path(name).is_file() for name in SERVICES):
        build()
    STATE.mkdir(exist_ok=True)
    for name in SERVICES:
        if owned_pid(name):
            continue
        # Refuse to adopt a different instance listening on the lab ports.
        try:
            health(name)
        except LabError:
            pass
        else:
            raise LabError(f"Port {SERVICES[name]} already serves an unmanaged application.")
        with (STATE / f"{name}.log").open("a") as log:
            process = subprocess.Popen(["java", "-Xms64m", "-Xmx384m", "-jar", str(jar_path(name))],
                                       cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        (STATE / f"{name}.pid").write_text(str(process.pid))

    def ready():
        for name in SERVICES:
            require(owned_pid(name), f"{name} exited; inspect .lab/{name}.log")
            try:
                health(name)
            except LabError:
                return False
        return True

    wait_for("all three healthy services", ready, timeout=180, interval=1)
    status()


def stop_services():
    for name in SERVICES:
        pid = owned_pid(name)
        if pid:
            os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 20
    while any(owned_pid(name) for name in SERVICES) and time.monotonic() < deadline:
        time.sleep(0.2)
    remaining = [name for name in SERVICES if owned_pid(name)]
    require(not remaining, f"Still shutting down: {', '.join(remaining)}. Check .lab/*.log.")
    print("Lab Java services stopped. Database and Kafka data are unchanged.")


def status():
    for name, port in SERVICES.items():
        health(name)
        codespace = os.environ.get("CODESPACE_NAME")
        domain = os.environ.get("GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN", "app.github.dev")
        origin = f"https://{codespace}-{port}.{domain}" if codespace else f"http://localhost:{port}"
        print(f"UP  {name:12} {origin}/swagger-ui.html")
    print("\nRun ./lab run to break and recover your local backend. Guide: docs/break-the-backend.md")


def exercise():
    """Failures are set to 0/1 so every assertion has a deterministic expectation."""
    status()
    checks = []

    def passed(description, order_id=None):
        checks.append({"check": description, "orderId": order_id})
        print(f"PASS  {description}" + (f"  [{order_id}]" if order_id else ""), flush=True)

    try:
        rate("payment", 0)
        rate("notification", 0)
        # A previous interrupted run may have left the breaker OPEN. It has no reset API.
        deadline = time.monotonic() + 35
        while circuit() != "CLOSED":
            require(time.monotonic() < deadline, "Circuit did not recover before the exercise")
            order_id = create_order("prepare")
            wait_for("preparation payment", lambda: request(
                "payment", f"/api/v1/payments/{order_id}", allow_missing=True))
            time.sleep(1)

        order_id = create_order("healthy")
        payment(order_id, "COMPLETED")
        notification(order_id)
        passed("Healthy order crossed PostgreSQL outbox, Kafka, payment and notification", order_id)

        rate("payment", 1)
        for index in range(3):
            order_id = create_order(f"failure-{index + 1}")
            result = payment(order_id, "FAILED")
            require(result.get("message") == "Payment gateway timeout", "Expected gateway failure, not fallback")
        require(circuit() == "OPEN", "Three gateway failures did not OPEN the circuit")
        passed("Three real gateway failures opened the circuit")

        order_id = create_order("open-fallback")
        result = payment(order_id, "FAILED")
        require(result.get("message") == "Payment service temporarily unavailable",
                "Expected OPEN-circuit fallback, not another gateway call")
        passed("OPEN circuit returned the fallback", order_id)

        rate("payment", 0)
        print("WAIT  Allowing the demo's 10-second OPEN window to expire...", flush=True)
        time.sleep(10.5)
        order_id = create_order("probe-one")
        payment(order_id, "COMPLETED")
        require(circuit() == "HALF_OPEN", "First successful probe did not leave circuit HALF_OPEN")
        passed("First successful recovery probe left the circuit HALF_OPEN", order_id)
        order_id = create_order("probe-two")
        payment(order_id, "COMPLETED")
        require(circuit() == "CLOSED", "Second successful probe did not CLOSE the circuit")
        passed("Second successful probe closed the circuit", order_id)

        rate("notification", 1)
        order_id = create_order("dlq")
        payment(order_id, "COMPLETED")
        failed = wait_for(f"payment event in DLQ for {order_id}", lambda: next((item for item in
            request("notification", "/api/v1/notifications/dlq")
            if item.get("key") == order_id and item.get("originalTopic") == "payment-events"), None))
        require(failed.get("failureType") == "TRANSIENT", f"Unexpected DLQ classification: {failed}")
        passed("Notification failure isolated in DLQ; payment still completed", order_id)

        rate("notification", 0)
        order_id = create_order("recovered")
        payment(order_id, "COMPLETED")
        notification(order_id)
        passed("New order delivered after notification recovery", order_id)
        STATE.mkdir(exist_ok=True)
        (STATE / "last-run.json").write_text(json.dumps({"status": "passed", "checks": checks}, indent=2) + "\n")
        print("\nAll lab checks passed. Receipt: .lab/last-run.json")
    finally:
        # Restore healthy controls on success, assertion failure, or Ctrl-C.
        for service in ("payment", "notification"):
            try:
                rate(service, 0)
            except LabError as error:
                print(f"Could not restore {service} failure rate: {error}", file=sys.stderr)


@contextlib.contextmanager
def lock():
    STATE.mkdir(exist_ok=True)
    with (STATE / "control.lock").open("w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise LabError("Another lab command is already changing this environment") from error
        yield


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("build", "start", "status", "run", "stop"))
    args = parser.parse_args(argv)
    commands = {"build": build, "start": start_services, "status": status, "run": exercise, "stop": stop_services}
    try:
        with lock():
            if args.command == "run":
                (STATE / "last-run.json").unlink(missing_ok=True)
            commands[args.command]()
    except (LabError, subprocess.CalledProcessError) as error:
        print(f"LAB FAILED: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nLab interrupted.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
