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
]


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


def run_maven_verify(strict):
    if shutil.which("mvn") is None:
        message = "mvn not found; skipped Maven verify"
        return (False if strict else True), message
    code, output = run_command(["mvn", "-B", "verify"], timeout=300)
    if code != 0:
        return False, output
    return True, "mvn -B verify passed"


def main():
    parser = argparse.ArgumentParser(description="MiniDB-Lab local verification")
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
    args = parser.parse_args()

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
