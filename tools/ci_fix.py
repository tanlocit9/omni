"""Plan bounded Python CI repairs from an owner-supplied GitHub jobs snapshot."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

try:
    from tools.cli_output import configure_output, table
    from tools.process_utils import command_environment, hidden_process_options
except ModuleNotFoundError as exc:
    if exc.name != "tools":
        raise
    from cli_output import configure_output, table
    from process_utils import command_environment, hidden_process_options

ROOT = Path(__file__).resolve().parents[1]
PROJECTS = {
    "analyzer": "apps/analyzer",
    "ingestor": "apps/ingestor",
    "query-service": "apps/query-service",
    "py-common": "libs/py-common",
}
STEPS = {"Sync Python dependencies", "Lint", "Test", "Build"}


def repair_plan(report: dict, head: str, root: Path = ROOT) -> list[dict]:
    if report.get("head_sha") != head:
        raise ValueError("CI snapshot does not match HEAD; fetch a fresh jobs snapshot")
    jobs = report.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("Expected a GitHub jobs array")
    failed = [job for job in jobs if job.get("conclusion") == "failure"]
    selected = {}
    for job in failed:
        if job.get("name") == "CI Quality Gate":
            continue  # Aggregate failure is not a separate repair command.
        match = re.fullmatch(r"Python / ([a-z-]+) / Verify ([a-z-]+)", job.get("name", ""))
        if not match or match[1] != match[2] or match[1] not in PROJECTS:
            raise ValueError(f"Unsupported failed job: {job.get('name')}")
        broken = [s["name"] for s in job.get("steps", []) if s.get("conclusion") == "failure"]
        if len(broken) != 1 or broken[0] not in STEPS:
            raise ValueError(f"Unsupported failed steps: {broken}")
        project = match[1]
        if project in selected:
            raise ValueError(f"Duplicate failed project: {project}")
        selected[project] = broken[0]
    if not selected:
        raise ValueError("No supported failed leaf jobs; nothing can be reported as repaired")
    plan = []
    for project, broken in sorted(selected.items()):
        config = json.loads((root / PROJECTS[project] / "project.json").read_text())
        targets = ["sync-ci"]
        if broken == "Lint":
            targets.append("lint-fix")
        targets += ["lint", "test", "build"]
        for target in targets:
            if target not in config.get("targets", {}):
                raise ValueError(f"Missing declared target: {project}:{target}")
            plan.append({"project": project, "target": target})
    return plan


def child_environment(project: str, directory: Path, target: str) -> dict[str, str]:
    env = command_environment()
    env.update(
        NX_NO_CLOUD="true",
        NX_DAEMON="false",
        UV_LOCKED="true",
        UV_PROJECT_ENVIRONMENT=str(directory / "environments" / project),
        PYTHONIOENCODING="utf-8",
        PYTHONUTF8="1",
    )
    env.setdefault("UV_CACHE_DIR", str(ROOT / ".agent" / "uv-cache"))
    env.setdefault("UV_PYTHON", (ROOT / ".python-version").read_text().strip())
    if target == "build":
        env.pop("UV_LOCKED", None)  # CI's frozen exporter rejects this variable.
    return env


def check_identity(root: Path = ROOT) -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        text=True,
        env=command_environment(),
        **hidden_process_options(),
    ).strip()


def print_plan(plan: list[dict], *, as_table: bool = False) -> None:
    commands = [
        f"nx run {item['project']}:{item['target']} --skip-nx-cache"
        for item in plan
    ]
    if as_table:
        configure_output()
        print(
            table(
                ["PROJECT", "TARGET", "COMMAND"],
                [
                    [item["project"], item["target"], command]
                    for item, command in zip(plan, commands)
                ],
            ),
            flush=True,
        )
    else:
        for command in commands:
            print(command, flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--apply", action="store_true", help="Run the printed bounded repair/check plan")
    parser.add_argument("--table", action="store_true", help="Display the repair plan as a table")
    args = parser.parse_args()
    head = check_identity()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    if not isinstance(report.get("run_id"), int) or report["run_id"] <= 0:
        raise ValueError("A positive GitHub workflow run_id is required")
    plan = repair_plan(report, head)
    increment = f"CI-REPAIR-{report['run_id']}-{head[:7]}"
    directory = ROOT / ".agent" / "check-results" / increment
    print_plan(plan, as_table=args.table)
    if not args.apply:
        print("Plan only; add --apply after verification authorization. No commands executed.")
        return 0
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "input.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    recorder = [sys.executable, str(ROOT / "tools" / "check_result.py")]
    init = [*recorder, "init", "--increment", increment]
    for item in plan:
        init += ["--require", f"other/{item['project']}-{item['target']}"]
    subprocess.run(
        init,
        cwd=ROOT,
        check=True,
        env=command_environment(),
        **hidden_process_options(),
    )
    failed = False
    for item in plan:
        if check_identity() != head:
            raise ValueError("HEAD changed during repair; stopping")
        project, target = item["project"], item["target"]
        command = [
            *recorder, "run", "--increment", increment, "--kind", "other",
            "--name", f"{project}-{target}", "--", "nx", "run",
            f"{project}:{target}", "--skip-nx-cache",
        ]
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=child_environment(project, directory, target),
            **hidden_process_options(),
        )
        if result.returncode:
            failed = True
            break  # Never run tests/build against a failed sync/lint or hide failure.
    conclusion = subprocess.run(
        [*recorder, "conclusion", "--increment", increment],
        cwd=ROOT,
        env=command_environment(),
        **hidden_process_options(),
    )
    return 1 if failed else conclusion.returncode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as exc:
        print(f"CI repair refused: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
