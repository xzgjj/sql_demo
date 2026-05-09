#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import urllib.parse
import urllib.request


DEFAULT_PROXY_HOST = os.getenv("MINIDB_PROXY_HOST", "127.0.0.1")
DEFAULT_PROXY_PORT = int(os.getenv("MINIDB_PROXY_PORT", "3306"))
DEFAULT_MYSQL_USER = os.getenv("MINIDB_PROXY_USERNAME", "proxy")
DEFAULT_MYSQL_PASSWORD = os.getenv("MINIDB_PROXY_PASSWORD", "proxy123")
DEFAULT_DATABASE = os.getenv("MINIDB_PRIMARY_DATABASE", "minidb")
DEFAULT_ORDER_API = os.getenv("MINIDB_ORDER_API_URL", "http://localhost:8080")


def ok(name, detail):
    return {"name": name, "ok": True, "detail": detail}


def fail(name, detail):
    return {"name": name, "ok": False, "detail": detail}


def tcp_probe(host, port, timeout):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return ok("proxy:tcp", f"{host}:{port} reachable")
    except OSError as exc:
        return fail("proxy:tcp", f"{host}:{port} unreachable: {exc}")


def run_mysql_select(args):
    mysql = shutil.which("mysql")
    if mysql is None:
        return fail("mysql:client", "mysql executable not found")

    command = [
        mysql,
        "--protocol=TCP",
        f"--host={args.proxy_host}",
        f"--port={args.proxy_port}",
        f"--user={args.mysql_user}",
        f"--password={args.mysql_password}",
        f"--database={args.database}",
        "--batch",
        "--skip-column-names",
        "--execute=SELECT 1",
    ]
    completed = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=args.timeout,
        check=False,
    )
    output = completed.stdout.strip()
    if completed.returncode == 0 and output == "1":
        return ok("mysql:select-1", "SELECT 1 returned 1 through proxy")
    return fail("mysql:select-1", output or f"mysql exited {completed.returncode}")


def http_get_json(url, timeout):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body)
    except Exception as exc:
        raise RuntimeError(str(exc)) from exc


def run_order_api_health(args):
    url = args.order_api.rstrip("/") + "/actuator/health"
    try:
        payload = http_get_json(url, args.timeout)
    except RuntimeError as exc:
        return fail("order-api:health", str(exc))
    status = payload.get("status")
    if status == "UP":
        return ok("order-api:health", "UP")
    return fail("order-api:health", f"unexpected health payload: {payload}")


def run_route_preview(args, sql, expected_text, name):
    query = urllib.parse.urlencode({"sql": sql})
    url = args.order_api.rstrip("/") + "/api/proxy/routes/preview?" + query
    try:
        payload = http_get_json(url, args.timeout)
    except RuntimeError as exc:
        return fail(name, str(exc))
    text = json.dumps(payload, ensure_ascii=False)
    if expected_text in text:
        return ok(name, f"response contains {expected_text}")
    return fail(name, f"response did not contain {expected_text}: {text}")


def dry_run_report(args):
    return [
        ok("dry-run:proxy", f"would probe {args.proxy_host}:{args.proxy_port}"),
        ok("dry-run:mysql", f"would run SELECT 1 as {args.mysql_user} on {args.database}"),
        ok("dry-run:order-api", f"would probe {args.order_api.rstrip('/')}/actuator/health"),
        ok("dry-run:route-preview", "would preview routed and missing-shard-key SQL"),
    ]


def main():
    parser = argparse.ArgumentParser(
        description="MiniDB-Lab proxy smoke check. Default is dry-run and non-destructive."
    )
    parser.add_argument("--execute", action="store_true",
                        help="run real network checks; without this flag only prints dry-run checks")
    parser.add_argument("--json", action="store_true", help="print JSON report")
    parser.add_argument("--proxy-host", default=DEFAULT_PROXY_HOST)
    parser.add_argument("--proxy-port", type=int, default=DEFAULT_PROXY_PORT)
    parser.add_argument("--mysql-user", default=DEFAULT_MYSQL_USER)
    parser.add_argument("--mysql-password", default=DEFAULT_MYSQL_PASSWORD)
    parser.add_argument("--database", default=DEFAULT_DATABASE)
    parser.add_argument("--order-api", default=DEFAULT_ORDER_API)
    parser.add_argument("--timeout", type=int, default=10)
    args = parser.parse_args()

    if args.timeout < 1:
        parser.error("--timeout must be positive")

    if not args.execute:
        checks = dry_run_report(args)
    else:
        checks = [
            tcp_probe(args.proxy_host, args.proxy_port, args.timeout),
            run_mysql_select(args),
            run_order_api_health(args),
            run_route_preview(
                args,
                "SELECT * FROM orders WHERE user_id = 1001",
                "user_id",
                "route-preview:user-id",
            ),
            run_route_preview(
                args,
                "SELECT * FROM orders WHERE id = 1",
                "MISSING_SHARD_KEY",
                "route-preview:missing-shard-key",
            ),
        ]

    report = {"execute": args.execute, "checks": checks}
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        mode = "execute" if args.execute else "dry-run"
        print(f"MiniDB-Lab proxy smoke ({mode})")
        for item in checks:
            status = "PASS" if item["ok"] else "FAIL"
            print(f"  [{status}] {item['name']}: {item['detail']}")

    return 1 if any(not item["ok"] for item in checks) else 0


if __name__ == "__main__":
    sys.exit(main())
