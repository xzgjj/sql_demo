#!/usr/bin/env python3
import argparse
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_MODULES = ["mini-mvcc", "mini-proxy", "order-api"]
BUILD_OUTPUT_PATHS = [
    "target",
    "mini-mvcc/target",
    "mini-proxy/target",
    "order-api/target",
    "web-console/dist",
    "tools/__pycache__",
]
DEFAULT_MAX_LOG_SIZE_MB = 20
DEFAULT_MAX_LOG_FILES_PER_PREFIX = 10
EXPECTED_IGNORED = [
    "CLAUDE.md",
    "notes.txt",
    "implementation_plan.md",
    "diff.md",
    "AGENT_EXECUTION_PROTOCOL.md",
    "doc/",
    "test/",
    "tests/",
    "target/",
    "web-console/node_modules/",
    "web-console/dist/",
]


def path_inside_root(path):
    root = ROOT.resolve()
    target = path.resolve()
    try:
        target.relative_to(root)
        return True
    except ValueError:
        return False


def format_bytes(size):
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.2f} MB"
    if size >= 1024:
        return f"{size / 1024:.2f} KB"
    return f"{size} B"


def run_command(command, timeout=120):
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
        return completed.returncode, completed.stdout.strip()
    except FileNotFoundError as exc:
        return 127, str(exc)
    except subprocess.TimeoutExpired as exc:
        return 124, f"timeout after {exc.timeout}s"


def tool_version(name, args):
    exe = shutil.which(name)
    if exe is None:
        return {"tool": name, "found": False, "version": None}
    code, output = run_command([exe, *args], timeout=30)
    first_line = output.splitlines()[0] if output else ""
    return {"tool": name, "found": code == 0, "version": first_line}


def check_file(path):
    target = ROOT / path
    return target.exists()


def clean_build_outputs(apply):
    results = []
    for rel in BUILD_OUTPUT_PATHS:
        target = ROOT / rel
        entry = {"path": rel, "exists": target.exists(), "action": "skip", "ok": True}
        if not target.exists():
            results.append(entry)
            continue
        if not path_inside_root(target):
            entry.update({"action": "refuse", "ok": False, "reason": "outside project root"})
            results.append(entry)
            continue
        if target.is_symlink():
            entry.update({"action": "refuse", "ok": False, "reason": "symlink target is not cleaned"})
            results.append(entry)
            continue
        if not apply:
            entry["action"] = "would-remove"
            results.append(entry)
            continue
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
        entry["action"] = "removed"
        results.append(entry)
    return results


def log_prefix(path):
    name = path.name
    for suffix in (".out.log", ".err.log", ".log"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    parts = name.rsplit("-", 1)
    if len(parts) == 2 and parts[1].isdigit():
        return parts[0]
    return name


def collect_log_files():
    files = []
    for directory in (ROOT, ROOT / "logs"):
        if directory.exists():
            files.extend(path for path in directory.glob("*.log") if path.is_file())
    return sorted(set(files), key=lambda p: str(p.relative_to(ROOT)).lower())


def analyze_logs(max_size_mb, max_files_per_prefix):
    max_bytes = max_size_mb * 1024 * 1024
    files = collect_log_files()
    oversized = [
        {
            "path": str(path.relative_to(ROOT)),
            "size": path.stat().st_size,
            "sizeText": format_bytes(path.stat().st_size),
        }
        for path in files
        if path.stat().st_size > max_bytes
    ]

    groups = {}
    for path in files:
        directory = str(path.parent.relative_to(ROOT)) if path.parent != ROOT else "."
        key = (directory, log_prefix(path))
        groups.setdefault(key, []).append(path)

    overflow = []
    for (directory, prefix), paths in groups.items():
        ordered = sorted(paths, key=lambda p: p.stat().st_mtime, reverse=True)
        for path in ordered[max_files_per_prefix:]:
            overflow.append({
                "directory": directory,
                "prefix": prefix,
                "path": str(path.relative_to(ROOT)),
                "size": path.stat().st_size,
                "sizeText": format_bytes(path.stat().st_size),
            })

    return {
        "maxSizeMb": max_size_mb,
        "maxFilesPerPrefix": max_files_per_prefix,
        "totalFiles": len(files),
        "oversized": oversized,
        "overflow": overflow,
    }


def archive_overflow_logs(overflow):
    archive_dir = ROOT / "logs" / "archive"
    archive_dir.mkdir(parents=True, exist_ok=True)
    moved = []
    for item in overflow:
        source = ROOT / item["path"]
        if not source.exists():
            continue
        if not path_inside_root(source) or source.is_symlink():
            moved.append({"path": item["path"], "ok": False, "reason": "refused unsafe path"})
            continue
        destination = archive_dir / source.name
        if destination.exists():
            destination = archive_dir / f"{source.stem}-{int(source.stat().st_mtime)}{source.suffix}"
        shutil.move(str(source), str(destination))
        moved.append({
            "path": item["path"],
            "ok": True,
            "destination": str(destination.relative_to(ROOT)),
        })
    return moved


def check_log_policy(max_size_mb=DEFAULT_MAX_LOG_SIZE_MB,
                     max_files_per_prefix=DEFAULT_MAX_LOG_FILES_PER_PREFIX):
    report = analyze_logs(max_size_mb, max_files_per_prefix)
    violations = len(report["oversized"]) + len(report["overflow"])
    if violations:
        return False, (
            f"log policy violations: oversized={len(report['oversized'])}, "
            f"overflow={len(report['overflow'])}"
        )
    return True, (
        f"log policy ok ({report['totalFiles']} files, "
        f"max {max_size_mb} MB, max {max_files_per_prefix}/prefix)"
    )


def check_parent_pom():
    pom = ROOT / "pom.xml"
    if not pom.exists():
        return False, "missing root pom.xml"
    try:
        tree = ET.parse(pom)
    except ET.ParseError as exc:
        return False, f"invalid pom.xml: {exc}"

    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [
        element.text
        for element in tree.findall("./m:modules/m:module", namespace)
        if element.text
    ]
    missing = [module for module in EXPECTED_MODULES if module not in modules]
    if missing:
        return False, f"missing modules in pom.xml: {', '.join(missing)}"
    return True, "root pom.xml modules ok"


def check_module_layout(module):
    required = [
        ROOT / module / "pom.xml",
        ROOT / module / "src" / "main" / "java",
        ROOT / module / "src" / "test" / "java",
    ]
    missing = [str(path.relative_to(ROOT)) for path in required if not path.exists()]
    if missing:
        return False, f"{module} missing: {', '.join(missing)}"
    return True, f"{module} layout ok"


def check_git_ignores():
    if shutil.which("git") is None:
        return False, "git not found"
    failures = []
    for pattern in EXPECTED_IGNORED:
        code, output = run_command(["git", "check-ignore", "-v", pattern], timeout=30)
        if code != 0:
            failures.append(pattern)
    if failures:
        return False, f"not ignored: {', '.join(failures)}"
    return True, "ignore rules ok"


def check_tracked_sensitive_paths():
    if shutil.which("git") is None:
        return True, "git not found; skipped tracked path check"
    code, output = run_command(["git", "ls-files"], timeout=30)
    if code != 0:
        return False, output
    blocked_prefixes = ("doc/", "test/", "tests/")
    blocked_files = {
        "CLAUDE.md",
        "notes.txt",
        "implementation_plan.md",
        "diff.md",
        "AGENT_EXECUTION_PROTOCOL.md",
    }
    tracked = output.splitlines()
    violations = [
        item for item in tracked
        if item in blocked_files or item.startswith(blocked_prefixes)
    ]
    if violations:
        return False, f"blocked tracked paths: {', '.join(violations)}"
    return True, "tracked path policy ok"


PROXY_EXPECTED_SOURCES = [
    "MiniProxyServer.java",
    "ProxyConfig.java",
    "ProxyChannelInitializer.java",
    "ProxyFrontendHandler.java",
    "BackendRelayHandler.java",
    "SqlType.java",
    "ParsedSql.java",
    "SqlParserImpl.java",
    "RoutePlan.java",
    "SqlRouterImpl.java",
    "ConnectionState.java",
    "ProxySession.java",
    "DataSourceId.java",
    "BackendConnection.java",
    "BackendConnectionPool.java",
    "BackendConnectionPoolImpl.java",
    "BackendAuthHandler.java",
    "RouteTableLookup.java",
    "AltRouteType.java",
    "ProxyManagementServer.java",
    "RouteDecisionLog.java",
    "protocol/MySqlPacket.java",
    "protocol/MySqlPacketEncoder.java",
    "protocol/MySqlPacketDecoder.java",
    "protocol/CapabilityFlags.java",
    "protocol/HandshakeV10.java",
    "protocol/HandshakeResponse41.java",
    "protocol/ResponsePackets.java",
    "protocol/AuthNativePassword.java",
]


def check_proxy_sources():
    src_root = ROOT / "mini-proxy" / "src" / "main" / "java" / "com" / "minidb" / "proxy"
    missing = [f for f in PROXY_EXPECTED_SOURCES if not (src_root / f).exists()]
    if missing:
        return False, f"missing proxy sources: {', '.join(missing)}"
    return True, f"{len(PROXY_EXPECTED_SOURCES)} proxy source files ok"


def check_proxy_runtime_contract():
    checks = []
    handshake = ROOT / "mini-proxy" / "src" / "main" / "java" / "com" / "minidb" / "proxy" / "protocol" / "HandshakeV10.java"
    backend_auth = ROOT / "mini-proxy" / "src" / "main" / "java" / "com" / "minidb" / "proxy" / "BackendAuthHandler.java"
    proxy_profile = ROOT / "order-api" / "src" / "main" / "resources" / "application-proxy.yml"
    compose = ROOT / "docker-compose.yml"

    handshake_text = handshake.read_text(encoding="utf-8") if handshake.exists() else ""
    backend_auth_text = backend_auth.read_text(encoding="utf-8") if backend_auth.exists() else ""
    proxy_profile_text = proxy_profile.read_text(encoding="utf-8") if proxy_profile.exists() else ""
    compose_text = compose.read_text(encoding="utf-8") if compose.exists() else ""

    checks.append(("handshake uses NUL-terminated username parser", "readNullTerminatedString(payload)" in handshake_text))
    checks.append(("backend auth conditionally sends database", "CLIENT_CONNECT_WITH_DB" in backend_auth_text and "database.isBlank()" in backend_auth_text))
    checks.append(("order-api proxy profile exists", proxy_profile.exists()))
    checks.append(("proxy profile disables server prepared statements", "useServerPrepStmts=false" in proxy_profile_text))
    checks.append(("docker MySQL 8.4 native auth flag is current", "--default-authentication-plugin" not in compose_text))

    failed = [name for name, ok in checks if not ok]
    if failed:
        return False, "proxy runtime contract failed: " + ", ".join(failed)
    return True, "proxy runtime contract ok"


def check_proxy_smoke_tool():
    script = ROOT / "tools" / "proxy_smoke.py"
    if not script.exists():
        return False, "tools/proxy_smoke.py missing"
    code, output = run_command([sys.executable, str(script), "--json"], timeout=30)
    if code != 0:
        return False, output
    try:
        payload = json.loads(output)
    except json.JSONDecodeError as exc:
        return False, f"invalid proxy_smoke JSON: {exc}"
    checks = payload.get("checks", [])
    if not checks or any(not item.get("ok") for item in checks):
        return False, "proxy_smoke dry-run checks failed"
    return True, "proxy smoke dry-run ok"


ORDER_EXPECTED_SOURCES = [
    "OrderApiApplication.java",
    "OrderApiModule.java",
    "OrderStatus.java",
    "PaymentStatus.java",
    "FulfillmentStatus.java",
    "IdempotencyStatus.java",
    "BusinessException.java",
    "dto/CreateOrderRequest.java",
    "dto/CreateOrderResponse.java",
    "dto/PaymentCallbackRequest.java",
    "dto/CancelOrderRequest.java",
    "dto/ClaimTaskRequest.java",
    "dto/ShipOrderRequest.java",
    "dto/ResolveExceptionRequest.java",
    "dto/ApiResponse.java",
    "service/IdempotencyService.java",
    "service/InventoryService.java",
    "service/OrderService.java",
    "service/PaymentService.java",
    "service/FulfillmentService.java",
    "service/OutboxProcessor.java",
    "service/OrderExpiryScheduler.java",
    "service/ProductService.java",
    "service/ExceptionService.java",
    "service/ConsoleService.java",
    "web/OrderController.java",
    "web/PaymentController.java",
    "web/FulfillmentController.java",
    "web/ProductController.java",
    "web/ExceptionController.java",
    "web/ConsoleController.java",
    "web/GlobalExceptionHandler.java",
]


def check_order_sources():
    src_root = ROOT / "order-api" / "src" / "main" / "java" / "com" / "minidb" / "order"
    missing = [f for f in ORDER_EXPECTED_SOURCES if not (src_root / f).exists()]
    if missing:
        return False, f"missing order sources: {', '.join(missing)}"
    return True, f"{len(ORDER_EXPECTED_SOURCES)} order source files ok"


def run_order_unit_tests():
    mvn = shutil.which("mvn")
    if mvn is None:
        return False, "mvn not found"
    code, output = run_command(
        [mvn, "-pl", "order-api", "-am", "test", "-B"],
        timeout=180
    )
    if code != 0:
        last_lines = output.splitlines()[-5:] if output else ["unknown error"]
        return False, f"order unit tests failed: {'; '.join(last_lines)}"
    # Parse test count
    count = 0
    for line in output.splitlines():
        if "Tests run:" in line:
            try:
                count = int(line.split("Tests run:")[1].split(",")[0].strip())
            except: pass
    return True, f"order unit tests passed ({count} tests)"


WEB_EXPECTED_SOURCES = [
    "package.json",
    "package-lock.json",
    "index.html",
    "vite.config.ts",
    "tsconfig.json",
    "eslint.config.js",
    "src/main.tsx",
    "src/App.tsx",
    "src/api.ts",
    "src/i18n.ts",
    "src/styles.css",
    "src/App.test.tsx",
    "src/test-setup.ts",
]


def check_web_console_sources():
    root = ROOT / "web-console"
    missing = [f for f in WEB_EXPECTED_SOURCES if not (root / f).exists()]
    if missing:
        return False, f"missing web-console files: {', '.join(missing)}"
    return True, f"{len(WEB_EXPECTED_SOURCES)} web-console files ok"


def run_web_console_command(script):
    npm = shutil.which("npm")
    if npm is None:
        return False, "npm not found"
    code, output = subprocess_run_in(ROOT / "web-console", [npm, "run", script], timeout=180)
    if code != 0:
        last_lines = output.splitlines()[-8:] if output else ["unknown error"]
        return False, f"npm run {script} failed: {'; '.join(last_lines)}"
    return True, f"npm run {script} passed"


def subprocess_run_in(cwd, command, timeout=120):
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
        return completed.returncode, completed.stdout.strip()
    except FileNotFoundError as exc:
        return 127, str(exc)
    except subprocess.TimeoutExpired as exc:
        return 124, f"timeout after {exc.timeout}s"


def run_maven_verify(strict):
    mvn = shutil.which("mvn")
    if mvn is None:
        message = "mvn not found; skipped Maven verify"
        return (False if strict else True), message
    code, output = run_command(
        [mvn, "-B", "verify", "-Dtest=!*IntegrationTest"], timeout=300
    )
    if code != 0:
        return False, output
    return True, "mvn -B verify passed"


def print_clean_report(results, apply):
    action = "apply" if apply else "dry-run"
    print(f"MiniDB-Lab clean build outputs ({action})")
    for item in results:
        status = "PASS" if item["ok"] else "FAIL"
        detail = item["action"]
        if item.get("reason"):
            detail += f" ({item['reason']})"
        print(f"  [{status}] {item['path']}: {detail}")


def print_log_report(report, moved=None, apply=False):
    mode = "apply" if apply else "check"
    print(f"MiniDB-Lab log policy ({mode})")
    print(f"  max size: {report['maxSizeMb']} MB")
    print(f"  max files per prefix: {report['maxFilesPerPrefix']}")
    print(f"  scanned files: {report['totalFiles']}")
    if not report["oversized"] and not report["overflow"]:
        print("  [PASS] logs within policy")
    for item in report["oversized"]:
        print(f"  [FAIL] oversize {item['path']}: {item['sizeText']}")
    for item in report["overflow"]:
        print(f"  [FAIL] overflow {item['path']}: prefix={item['prefix']}")
    if moved is not None:
        for item in moved:
            status = "PASS" if item["ok"] else "FAIL"
            detail = item.get("destination") or item.get("reason", "")
            print(f"  [{status}] archive {item['path']}: {detail}")


def main():
    parser = argparse.ArgumentParser(description="MiniDB-Lab local verification")
    parser.add_argument(
        "command",
        nargs="?",
        choices=("verify", "clean", "log-check"),
        default="verify",
        help="command to run: verify (default), clean, or log-check",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="fail when required build tools are missing or Maven verify fails",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print machine-readable JSON report",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="apply clean/log archive actions; default is dry-run/check only",
    )
    parser.add_argument(
        "--max-log-size-mb",
        type=int,
        default=DEFAULT_MAX_LOG_SIZE_MB,
        help=f"maximum allowed log file size in MB (default: {DEFAULT_MAX_LOG_SIZE_MB})",
    )
    parser.add_argument(
        "--max-log-files",
        type=int,
        default=DEFAULT_MAX_LOG_FILES_PER_PREFIX,
        help=f"maximum log files per prefix (default: {DEFAULT_MAX_LOG_FILES_PER_PREFIX})",
    )
    args = parser.parse_args()
    if args.max_log_size_mb < 1 or args.max_log_files < 1:
        parser.error("--max-log-size-mb and --max-log-files must be positive integers")

    if args.command == "clean":
        results = clean_build_outputs(args.apply)
        report = {"root": str(ROOT), "apply": args.apply, "results": results}
        if args.json:
            print(json.dumps(report, ensure_ascii=False, indent=2))
        else:
            print_clean_report(results, args.apply)
        return 1 if any(not item["ok"] for item in results) else 0

    if args.command == "log-check":
        report = analyze_logs(args.max_log_size_mb, args.max_log_files)
        moved = archive_overflow_logs(report["overflow"]) if args.apply else None
        full_report = {"root": str(ROOT), "apply": args.apply, "logPolicy": report, "moved": moved}
        if args.json:
            print(json.dumps(full_report, ensure_ascii=False, indent=2))
        else:
            print_log_report(report, moved, args.apply)
        remaining_violations = len(report["oversized"])
        if not args.apply:
            remaining_violations += len(report["overflow"])
        failed_moves = [item for item in (moved or []) if not item["ok"]]
        remaining_violations += len(failed_moves)
        return 1 if remaining_violations else 0

    checks = []
    tools = [
        tool_version("java", ["-version"]),
        tool_version("mvn", ["-version"]),
        tool_version("docker", ["--version"]),
        tool_version("mysql", ["--version"]),
        tool_version("node", ["--version"]),
        tool_version("npm", ["--version"]),
        tool_version("git", ["--version"]),
    ]

    for path in ["README.md", ".gitignore", "pom.xml"]:
        ok = check_file(path)
        checks.append({"name": f"file:{path}", "ok": ok, "detail": "exists" if ok else "missing"})

    ok, detail = check_parent_pom()
    checks.append({"name": "pom:root-modules", "ok": ok, "detail": detail})

    for module in EXPECTED_MODULES:
        ok, detail = check_module_layout(module)
        checks.append({"name": f"layout:{module}", "ok": ok, "detail": detail})

    ok, detail = check_git_ignores()
    checks.append({"name": "git:ignore-rules", "ok": ok, "detail": detail})

    ok, detail = check_tracked_sensitive_paths()
    checks.append({"name": "git:tracked-path-policy", "ok": ok, "detail": detail})

    ok = check_file("docker-compose.yml")
    checks.append({"name": "file:docker-compose.yml", "ok": ok, "detail": "exists" if ok else "missing"})

    ok, detail = check_proxy_sources()
    checks.append({"name": "proxy:source-files", "ok": ok, "detail": detail})

    ok, detail = check_proxy_runtime_contract()
    checks.append({"name": "proxy:runtime-contract", "ok": ok, "detail": detail})

    ok, detail = check_proxy_smoke_tool()
    checks.append({"name": "proxy:smoke-tool", "ok": ok, "detail": detail})

    ok, detail = check_order_sources()
    checks.append({"name": "order:source-files", "ok": ok, "detail": detail})

    ok, detail = run_order_unit_tests()
    checks.append({"name": "order:unit-tests", "ok": ok, "detail": detail})

    ok, detail = check_web_console_sources()
    checks.append({"name": "web-console:source-files", "ok": ok, "detail": detail})

    ok, detail = check_log_policy()
    checks.append({"name": "logs:policy", "ok": ok, "detail": detail})

    for script in ("lint", "test"):
        ok, detail = run_web_console_command(script)
        checks.append({"name": f"web-console:{script}", "ok": ok, "detail": detail})

    ok, detail = run_maven_verify(args.strict)
    checks.append({"name": "build:maven-verify", "ok": ok, "detail": detail})

    report = {
        "root": str(ROOT),
        "strict": args.strict,
        "tools": tools,
        "checks": checks,
    }

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("MiniDB-Lab local verification")
        print(f"Root: {ROOT}")
        print("\nTools:")
        for tool in tools:
            status = "FOUND" if tool["found"] else "MISSING"
            print(f"  [{status}] {tool['tool']}: {tool['version'] or '-'}")
        print("\nChecks:")
        for check in checks:
            status = "PASS" if check["ok"] else "FAIL"
            print(f"  [{status}] {check['name']}: {check['detail']}")

    failed = [check for check in checks if not check["ok"]]
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
