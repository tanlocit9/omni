#!/usr/bin/env python3
"""Record manual verification runs and expose a compact agent conclusion."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
RESULT_ROOT = ROOT / ".agent" / "check-results"
VALID_KINDS = {"test", "lint", "build", "format", "integration", "other"}
SAFE_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
EXIT_INCOMPLETE = 2
EXIT_FAILED = 3
EXIT_INVALID = 4


class CheckResultError(ValueError):
    pass


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def validate_value(value: str, label: str) -> str:
    if not SAFE_VALUE.fullmatch(value):
        raise CheckResultError(f"invalid {label}")
    return value


def check_key(kind: str, name: str) -> str:
    if kind not in VALID_KINDS:
        raise CheckResultError("invalid kind")
    validate_value(name, "name")
    return f"{kind}/{name}"


def result_dir(increment: str) -> Path:
    validate_value(increment, "increment")
    return RESULT_ROOT / increment


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CheckResultError(f"cannot read {path.name}") from exc


def append_record(directory: Path, record: dict[str, Any]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "runs.jsonl").open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(record, separators=(",", ":")) + "\n")


def read_records(directory: Path) -> list[dict[str, Any]]:
    path = directory / "runs.jsonl"
    if not path.exists():
        return []
    records = []
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError
                records.append(value)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise CheckResultError("invalid runs.jsonl") from exc
    return records


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command_init(args: argparse.Namespace) -> int:
    required = []
    seen = set()
    for item in args.require:
        if "/" not in item:
            raise CheckResultError("required check must use kind/name")
        kind, name = item.split("/", 1)
        key = check_key(kind, name)
        if key in seen:
            raise CheckResultError("duplicate required check")
        seen.add(key)
        required.append({"kind": kind, "name": name})
    manifest = {"schema_version": 1, "increment": args.increment, "required": required}
    atomic_json(result_dir(args.increment) / "required.json", manifest)
    print(f"READY {args.increment} required={len(required)}")
    return 0


def log_path(directory: Path, name: str) -> Path:
    validate_value(name, "name")
    return directory / "logs" / f"{name}.log"


def command_run(args: argparse.Namespace) -> int:
    if not args.command:
        raise CheckResultError("missing command after --")
    check_key(args.kind, args.name)
    directory = result_dir(args.increment)
    output_path = log_path(directory, args.name)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    started_at = now_iso()
    started = time.monotonic()
    with output_path.open("w", encoding="utf-8", errors="replace", newline="\n") as log:
        process = subprocess.Popen(
            args.command,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
        exit_code = process.wait()
    record = {
        "schema_version": 1,
        "increment": args.increment,
        "kind": args.kind,
        "name": args.name,
        "command": args.command,
        "started_at": started_at,
        "finished_at": now_iso(),
        "duration_ms": round((time.monotonic() - started) * 1000),
        "exit_code": exit_code,
        "status": "pass" if exit_code == 0 else "fail",
        "evidence_source": "exit_code",
        "log": output_path.relative_to(ROOT).as_posix(),
        "log_sha256": file_hash(output_path),
    }
    append_record(directory, record)
    return exit_code


def parse_log(text: str, parser: str) -> tuple[str, str]:
    normalized = text.replace("\x1b", "")
    if parser == "pytest":
        failure = re.search(r"(?:^|\s)(\d+) (?:failed|error(?:s)?)(?:,|\s|$)", normalized, re.I)
        success = re.search(r"(?:^|\s)(\d+) passed(?:,|\s|$)", normalized, re.I)
        if failure and int(failure.group(1)) > 0:
            return "fail", failure.group(0).strip()
        if success:
            return "pass", success.group(0).strip()
    elif parser == "eslint":
        count = re.search(r"(?:^|\s)(\d+) problems? \((\d+) errors?[,)]", normalized, re.I)
        if count:
            return ("fail" if int(count.group(2)) else "pass"), count.group(0).strip()
        if re.search(r"\berror\b", normalized, re.I):
            return "fail", "ESLint error marker"
    elif parser == "nx":
        if re.search(r"Running target .+ failed|Failed tasks?:|NX\s+.*failed", normalized, re.I):
            return "fail", "Nx failure summary"
        match = re.search(r"Successfully ran target[^\r\n]*", normalized, re.I)
        if match:
            return "pass", match.group(0).strip()
    return "unknown", "unrecognized or inconclusive log"


def resolve_log(raw_path: str, directory: Path) -> Path:
    path = Path(raw_path)
    path = path.resolve() if path.is_absolute() else (ROOT / path).resolve()
    allowed = (directory / "logs").resolve()
    if path != allowed and allowed not in path.parents:
        raise CheckResultError("log must be inside the increment logs directory")
    if not path.is_file():
        raise CheckResultError("log does not exist")
    return path


def command_import(args: argparse.Namespace) -> int:
    check_key(args.kind, args.name)
    directory = result_dir(args.increment)
    path = resolve_log(args.log, directory)
    observed = "exit code supplied"
    if args.exit_code is not None:
        status = "pass" if args.exit_code == 0 else "fail"
        source = "exit_code"
    else:
        text = path.read_text(encoding="utf-8", errors="replace")
        status, observed = parse_log(text, args.format)
        source = "parsed_log"
    record = {
        "schema_version": 1,
        "increment": args.increment,
        "kind": args.kind,
        "name": args.name,
        "status": status,
        "evidence_source": source,
        "parser": args.format,
        "exit_code": args.exit_code,
        "observed": observed[:200],
        "imported_at": now_iso(),
        "log": path.relative_to(ROOT).as_posix(),
        "log_sha256": file_hash(path),
    }
    append_record(directory, record)
    print(f"IMPORTED {args.increment} {args.kind}/{args.name} status={status} source={source}")
    return 0 if status == "pass" else EXIT_FAILED if status == "fail" else EXIT_INCOMPLETE


def load_manifest(directory: Path, increment: str) -> tuple[dict[str, Any], Path]:
    path = directory / "required.json"
    manifest = load_json(path)
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1 or manifest.get("increment") != increment:
        raise CheckResultError("invalid required.json")
    required = manifest.get("required")
    if not isinstance(required, list) or not required:
        raise CheckResultError("required checks are missing")
    seen = set()
    for item in required:
        if not isinstance(item, dict):
            raise CheckResultError("invalid required check")
        key = check_key(item.get("kind", ""), item.get("name", ""))
        if key in seen:
            raise CheckResultError("duplicate required check")
        seen.add(key)
    return manifest, path


def build_summary(increment: str) -> dict[str, Any]:
    directory = result_dir(increment)
    manifest, manifest_path = load_manifest(directory, increment)
    latest: dict[str, dict[str, Any]] = {}
    for record in read_records(directory):
        if record.get("increment") != increment:
            raise CheckResultError("record increment mismatch")
        key = check_key(record.get("kind", ""), record.get("name", ""))
        if record.get("status") not in {"pass", "fail", "unknown"}:
            raise CheckResultError("invalid record status")
        log = (ROOT / record.get("log", "")).resolve()
        if not log.is_file() or file_hash(log) != record.get("log_sha256"):
            record = dict(record)
            record["status"] = "unknown"
            record["evidence_source"] = "changed_log"
        latest[key] = record
    checks = []
    counts = {"required": len(manifest["required"]), "pass": 0, "fail": 0, "unknown": 0, "missing": 0}
    for item in manifest["required"]:
        key = check_key(item["kind"], item["name"])
        record = latest.get(key)
        status = "missing" if record is None else record["status"]
        counts[status] += 1
        checks.append({
            "kind": item["kind"],
            "name": item["name"],
            "status": status,
            "source": "none" if record is None else record["evidence_source"],
            "exit_code": None if record is None else record.get("exit_code"),
        })
    conclusion = "fail" if counts["fail"] else "incomplete" if counts["unknown"] or counts["missing"] else "pass"
    return {
        "schema_version": 1,
        "increment": increment,
        "conclusion": conclusion,
        "counts": counts,
        "checks": checks,
        "required_sha256": file_hash(manifest_path),
        "generated_at": now_iso(),
    }


def command_summarize(args: argparse.Namespace) -> int:
    summary = build_summary(args.increment)
    atomic_json(result_dir(args.increment) / "summary.json", summary)
    counts = summary["counts"]
    print(f"{summary['conclusion'].upper()} {args.increment} required={counts['required']} pass={counts['pass']} fail={counts['fail']} unknown={counts['unknown']} missing={counts['missing']}")
    return status_exit(summary["conclusion"])


def status_exit(status: str) -> int:
    return 0 if status == "pass" else EXIT_FAILED if status == "fail" else EXIT_INCOMPLETE


def command_conclusion(args: argparse.Namespace) -> int:
    summary = build_summary(args.increment)
    atomic_json(result_dir(args.increment) / "summary.json", summary)
    counts = summary["counts"]
    prefix = f"{summary['conclusion'].upper()} {args.increment}"
    if summary["conclusion"] == "pass":
        sources = sorted({item["source"] for item in summary["checks"]})
        print(f"{prefix} required={counts['required']} pass={counts['pass']} fail=0 unknown=0 missing=0 sources={','.join(sources)}")
    else:
        item = next(check for check in summary["checks"] if check["status"] in ({"fail"} if summary["conclusion"] == "fail" else {"unknown", "missing"}))
        detail = f"check={item['kind']}/{item['name']} source={item['source']}"
        if item["exit_code"] is not None:
            detail += f" code={item['exit_code']}"
        print(f"{prefix} {detail}")
    return status_exit(summary["conclusion"])


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="action", required=True)

    init = commands.add_parser("init", help="define required checks")
    init.add_argument("--increment", required=True)
    init.add_argument("--require", action="append", default=[], required=True, metavar="KIND/NAME")
    init.set_defaults(handler=command_init)

    run = commands.add_parser("run", help="run and record a manually selected command")
    run.add_argument("--increment", required=True)
    run.add_argument("--kind", required=True, choices=sorted(VALID_KINDS))
    run.add_argument("--name", required=True)
    run.add_argument("command", nargs=argparse.REMAINDER)
    run.set_defaults(handler=command_run)

    imported = commands.add_parser("import-log", help="record an existing saved log")
    imported.add_argument("--increment", required=True)
    imported.add_argument("--kind", required=True, choices=sorted(VALID_KINDS))
    imported.add_argument("--name", required=True)
    imported.add_argument("--format", required=True, choices=["nx", "pytest", "eslint", "generic"])
    imported.add_argument("--exit-code", type=int)
    imported.add_argument("--log", required=True)
    imported.set_defaults(handler=command_import)

    summarize = commands.add_parser("summarize", help="write and display the compact summary")
    summarize.add_argument("--increment", required=True)
    summarize.set_defaults(handler=command_summarize)

    conclusion = commands.add_parser("conclusion", help="print one agent-facing conclusion line")
    conclusion.add_argument("--increment", required=True)
    conclusion.set_defaults(handler=command_conclusion)
    return result


def main() -> int:
    try:
        args = parser().parse_args()
        if getattr(args, "command", None) and args.command[0] == "--":
            args.command = args.command[1:]
        return args.handler(args)
    except CheckResultError as exc:
        increment = getattr(locals().get("args", None), "increment", "unknown")
        print(f"INVALID {increment} reason={str(exc).replace(' ', '_')}")
        return EXIT_INVALID
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
