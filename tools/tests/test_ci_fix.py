import importlib.util
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("ci_fix", Path(__file__).resolve().parents[1] / "ci_fix.py")
ci_fix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci_fix)


def job(project="ingestor", step="Test", conclusion="failure"):
    return {
        "name": f"Python / {project} / Verify {project}",
        "conclusion": conclusion,
        "steps": [{"name": step, "conclusion": conclusion}],
    }


class CiFixTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path in ci_fix.PROJECTS.values():
            directory = self.root / path
            directory.mkdir(parents=True)
            (directory / "project.json").write_text(json.dumps({
                "targets": {target: {} for target in ["sync-ci", "lint-fix", "lint", "test", "build"]}
            }))

    def plan(self, jobs, **overrides):
        report = {"head_sha": "exact-head", "jobs": jobs, **overrides}
        return ci_fix.repair_plan(report, "exact-head", self.root)

    def test_supported_failures_are_bounded_and_aggregate_does_not_duplicate(self):
        plan = self.plan([job(), job("py-common", "Lint"), {"name": "CI Quality Gate", "conclusion": "failure"}])
        self.assertEqual([p["target"] for p in plan if p["project"] == "ingestor"], ["sync-ci", "lint", "test", "build"])
        self.assertEqual([p["target"] for p in plan if p["project"] == "py-common"], ["sync-ci", "lint-fix", "lint", "test", "build"])

    def test_successful_jobs_are_not_rerun(self):
        self.assertEqual({p["project"] for p in self.plan([job(), job("analyzer", conclusion="success")])}, {"ingestor"})

    def test_stale_snapshot_is_refused_before_commands(self):
        with self.assertRaisesRegex(ValueError, "match HEAD"):
            self.plan([job()], head_sha="other-head")

    def test_unknown_jobs_and_injected_commands_are_never_executable(self):
        for invalid in [job("ingestor;echo-secret"), job(step="echo secret"), {"name": "Java / platform", "conclusion": "failure"}]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.plan([job(), invalid])

    def test_missing_targets_duplicate_jobs_and_multiple_failed_steps_fail_closed(self):
        duplicate_steps = job()
        duplicate_steps["steps"].append({"name": "Build", "conclusion": "failure"})
        for jobs in [[job(), job()], [duplicate_steps]]:
            with self.assertRaises(ValueError):
                self.plan(jobs)
        (self.root / "apps/ingestor/project.json").write_text('{"targets": {}}')
        with self.assertRaisesRegex(ValueError, "Missing declared target"):
            self.plan([job()])

    def test_empty_or_aggregate_only_failure_is_not_a_pass(self):
        for jobs in [[], [{"name": "CI Quality Gate", "conclusion": "failure"}]]:
            with self.assertRaisesRegex(ValueError, "No supported failed"):
                self.plan(jobs)
        with self.assertRaisesRegex(ValueError, "jobs array"):
            self.plan(None)

    def test_environment_isolated_and_build_only_removes_locked_flag(self):
        with patch.dict(os.environ, {"UV_LOCKED": "true", "NX_NO_CLOUD": "false"}):
            test = ci_fix.child_environment("ingestor", self.root, "test")
            build = ci_fix.child_environment("ingestor", self.root, "build")
            shared = ci_fix.child_environment("py-common", self.root, "test")
            self.assertEqual(test["UV_LOCKED"], "true")
            self.assertNotIn("UV_LOCKED", build)
            self.assertNotEqual(test["UV_PROJECT_ENVIRONMENT"], shared["UV_PROJECT_ENVIRONMENT"])
            self.assertEqual(test["NX_NO_CLOUD"], "true")
            self.assertEqual(os.environ["UV_LOCKED"], "true")
            self.assertEqual(os.environ["NX_NO_CLOUD"], "false")

    def test_identity_lookup_is_one_hidden_child_with_shared_environment(self):
        with (
            patch.object(ci_fix, "command_environment", return_value={"PATH": "bin"}),
            patch.object(ci_fix, "hidden_process_options", return_value={"creationflags": 32}),
            patch.object(ci_fix.subprocess, "check_output", return_value="abc123\n") as lookup,
        ):
            self.assertEqual(ci_fix.check_identity(self.root), "abc123")
        lookup.assert_called_once_with(
            ["git", "rev-parse", "HEAD"], cwd=self.root, text=True,
            env={"PATH": "bin"}, creationflags=32,
        )

    def test_default_plan_keeps_copyable_command_lines(self):
        plan = [{"project": "ingestor", "target": "test"}]
        output = io.StringIO()
        with redirect_stdout(output), patch.object(ci_fix, "configure_output") as configure:
            ci_fix.print_plan(plan)
        self.assertEqual(output.getvalue(), "nx run ingestor:test --skip-nx-cache\n")
        configure.assert_not_called()

    def test_table_plan_uses_shared_renderer_without_running_commands(self):
        plan = [{"project": "ingestor", "target": "test"}]
        output = io.StringIO()
        with (
            redirect_stdout(output),
            patch.object(ci_fix, "configure_output") as configure,
            patch.object(ci_fix, "table", return_value="rendered plan") as render,
            patch.object(ci_fix.subprocess, "run") as run,
        ):
            ci_fix.print_plan(plan, as_table=True)
        configure.assert_called_once_with()
        render.assert_called_once_with(
            ["PROJECT", "TARGET", "COMMAND"],
            [["ingestor", "test", "nx run ingestor:test --skip-nx-cache"]],
        )
        run.assert_not_called()
        self.assertEqual(output.getvalue(), "rendered plan\n")

    def test_plan_only_never_starts_recorder_or_project_operations(self):
        report = self.root / "jobs.json"
        report.write_text(json.dumps({"run_id": 7}), encoding="utf-8")
        with (
            patch.object(ci_fix.sys, "argv", ["ci_fix", "--report", str(report), "--table"]),
            patch.object(ci_fix, "check_identity", return_value="exact-head"),
            patch.object(ci_fix, "repair_plan", return_value=[]),
            patch.object(ci_fix, "print_plan") as print_plan,
            patch.object(ci_fix.subprocess, "run") as run,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(ci_fix.main(), 0)
        print_plan.assert_called_once_with([], as_table=True)
        run.assert_not_called()

    def test_apply_hides_all_recorders_and_stops_after_first_failed_target(self):
        report = self.root / "jobs.json"
        report.write_text(json.dumps({"run_id": 7}), encoding="utf-8")
        plan = [
            {"project": "ingestor", "target": "sync-ci"},
            {"project": "ingestor", "target": "test"},
        ]
        with (
            patch.object(ci_fix, "ROOT", self.root),
            patch.object(ci_fix.sys, "argv", ["ci_fix", "--report", str(report), "--apply"]),
            patch.object(ci_fix, "check_identity", return_value="exact-head"),
            patch.object(ci_fix, "repair_plan", return_value=plan),
            patch.object(ci_fix, "command_environment", return_value={"TOOL": "1"}),
            patch.object(ci_fix, "child_environment", return_value={"PROJECT": "1"}),
            patch.object(ci_fix, "hidden_process_options", return_value={"creationflags": 32}),
            patch.object(ci_fix.subprocess, "run", side_effect=[
                SimpleNamespace(returncode=0),
                SimpleNamespace(returncode=1),
                SimpleNamespace(returncode=3),
            ]) as run,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(ci_fix.main(), 1)
        self.assertEqual(run.call_count, 3)
        for call in run.call_args_list:
            self.assertEqual(call.kwargs["creationflags"], 32)
            self.assertEqual(call.kwargs["cwd"], self.root)
        self.assertEqual(run.call_args_list[0].kwargs["env"], {"TOOL": "1"})
        self.assertEqual(run.call_args_list[1].kwargs["env"], {"PROJECT": "1"})
        self.assertEqual(run.call_args_list[2].kwargs["env"], {"TOOL": "1"})
        self.assertIn("ingestor:sync-ci", run.call_args_list[1].args[0])
        self.assertNotIn("ingestor:test", run.call_args_list[1].args[0])
        self.assertIn("conclusion", run.call_args_list[2].args[0])
