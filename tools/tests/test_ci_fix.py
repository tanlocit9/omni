import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
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
