"""Client regression checks. These do not substitute for ./lab run against the demo."""

from contextlib import redirect_stdout, redirect_stderr
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("backend_lab", Path(__file__).parents[1] / "backend_lab.py")
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.respond(None)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.respond(body)

    def respond(self, body):
        self.server.calls.append((self.command, self.path, body))
        status, response = self.server.responses.pop(0)
        data = json.dumps(response).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.responses = []
        self.server.calls = []
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.ports = patch.dict(lab.SERVICES, {name: self.server.server_port for name in lab.SERVICES})
        self.ports.start()

    def tearDown(self):
        self.ports.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_actual_http_order_and_eventual_payment(self):
        self.server.responses = [(201, {"id": "order-1"}), (404, {}),
                                 (200, {"orderId": "order-1", "status": "COMPLETED"})]
        order_id = lab.create_order("test")
        result = lab.payment(order_id, "COMPLETED")
        self.assertEqual("COMPLETED", result["status"])
        method, path, body = self.server.calls[0]
        self.assertEqual(("POST", "/api/v1/orders?clientId=backend-lab"), (method, path))
        self.assertTrue(body["customerId"].startswith("lab-test-"))
        self.assertEqual(3, len(self.server.calls))

    def test_failed_payment_cannot_pass_a_success_check(self):
        self.server.responses = [(200, {"orderId": "order-1", "status": "FAILED"})]
        with self.assertRaisesRegex(lab.LabError, "Expected COMPLETED"):
            lab.payment("order-1", "COMPLETED")

    def test_other_orders_cannot_satisfy_payment_assertion(self):
        self.server.responses = [(200, {"orderId": "old-order", "status": "COMPLETED"})]
        with self.assertRaisesRegex(lab.LabError, "another order"):
            lab.payment("new-order", "COMPLETED")

    def test_server_failure_is_not_treated_as_eventual_consistency(self):
        self.server.responses = [(500, {"message": "database unavailable"})]
        with self.assertRaisesRegex(lab.LabError, "HTTP 500.*database unavailable"):
            lab.payment("order-1", "COMPLETED")
        self.assertEqual(1, len(self.server.calls))

    def test_missing_service_health_is_an_error(self):
        self.server.responses = [(404, {})]
        with self.assertRaisesRegex(lab.LabError, "HTTP 404"):
            lab.health("order")

    def test_unhealthy_service_does_not_report_ready(self):
        self.server.responses = [(200, {"status": "DOWN"})]
        with self.assertRaisesRegex(lab.LabError, "not healthy"):
            lab.health("order")


class LifecycleTests(unittest.TestCase):
    def test_wait_is_bounded_for_a_missing_event(self):
        with self.assertRaisesRegex(lab.LabError, "Timed out.*missing event"):
            lab.wait_for("missing event", lambda: None, timeout=0.01, interval=0.002)

    def test_failed_experiment_restores_both_failure_controls(self):
        with patch.object(lab, "status"), patch.object(lab, "rate") as rate, \
             patch.object(lab, "circuit", return_value="CLOSED"), \
             patch.object(lab, "create_order", side_effect=lab.LabError("bad order")):
            with self.assertRaisesRegex(lab.LabError, "bad order"):
                lab.exercise()
        self.assertEqual([("payment", 0), ("notification", 0)],
                         [call.args for call in rate.call_args_list[-2:]])

    def test_failed_run_removes_a_stale_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(lab, "STATE", Path(directory)), \
             patch.object(lab, "status", side_effect=lab.LabError("offline")), \
             redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
            receipt = Path(directory) / "last-run.json"
            receipt.write_text('{"status":"passed"}')
            self.assertEqual(1, lab.main(["run"]))
            self.assertFalse(receipt.exists())

    def test_reused_pid_is_not_owned_by_the_lab(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(lab, "STATE", Path(directory)):
            (Path(directory) / "payment.pid").write_text(str(os.getpid()))
            self.assertIsNone(lab.owned_pid("payment"))


if __name__ == "__main__":
    unittest.main()
