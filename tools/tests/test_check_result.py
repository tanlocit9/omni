import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "check_result.py"
SPEC = importlib.util.spec_from_file_location("check_result", MODULE_PATH)
check_result = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(check_result)


class CheckResultTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.root_patch = patch.object(check_result, "ROOT", self.root)
        self.result_patch = patch.object(
            check_result, "RESULT_ROOT", self.root / ".agent" / "check-results"
        )
        self.root_patch.start()
        self.result_patch.start()

    def tearDown(self):
        self.result_patch.stop()
        self.root_patch.stop()
        self.temporary.cleanup()

    def init(self, increment="P3-I5"):
        args = SimpleNamespace(
            increment=increment,
            require=["test/analyzer-tests", "lint/analyzer-lint"],
        )
        with redirect_stdout(io.StringIO()):
            check_result.command_init(args)

    def import_log(self, kind, name, text, exit_code=None, parser="nx"):
        directory = check_result.result_dir("P3-I5")
        path = directory / "logs" / f"{name}.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        args = SimpleNamespace(
            increment="P3-I5",
            kind=kind,
            name=name,
            format=parser,
            exit_code=exit_code,
            log=str(path),
        )
        with redirect_stdout(io.StringIO()):
            return check_result.command_import(args)

    def test_exit_code_results_produce_pass(self):
        self.init()
        self.assertEqual(self.import_log("test", "analyzer-tests", "anything", 0), 0)
        self.assertEqual(self.import_log("lint", "analyzer-lint", "anything", 0), 0)

        summary = check_result.build_summary("P3-I5")

        self.assertEqual(summary["conclusion"], "pass")
        self.assertEqual(summary["counts"]["pass"], 2)
        self.assertEqual({item["source"] for item in summary["checks"]}, {"exit_code"})

    def test_missing_required_check_is_incomplete(self):
        self.init()
        self.import_log("test", "analyzer-tests", "anything", 0)

        summary = check_result.build_summary("P3-I5")

        self.assertEqual(summary["conclusion"], "incomplete")
        self.assertEqual(summary["counts"]["missing"], 1)

    def test_failed_exit_code_overrides_success_looking_log(self):
        self.init()
        self.import_log(
            "test", "analyzer-tests", "Successfully ran target test", 1
        )
        self.import_log("lint", "analyzer-lint", "anything", 0)

        summary = check_result.build_summary("P3-I5")

        self.assertEqual(summary["conclusion"], "fail")
        failed = next(item for item in summary["checks"] if item["status"] == "fail")
        self.assertEqual(failed["exit_code"], 1)

    def test_parsers_are_conservative(self):
        self.assertEqual(check_result.parse_log("12 passed in 1.2s", "pytest")[0], "pass")
        self.assertEqual(check_result.parse_log("1 failed, 11 passed", "pytest")[0], "fail")
        self.assertEqual(check_result.parse_log("ordinary output", "pytest")[0], "unknown")
        self.assertEqual(
            check_result.parse_log(
                "Successfully ran target lint for project analyzer", "nx"
            )[0],
            "pass",
        )
        self.assertEqual(
            check_result.parse_log("NX Running target lint failed", "nx")[0],
            "fail",
        )

    def test_changed_log_invalidates_record(self):
        self.init()
        self.import_log("test", "analyzer-tests", "anything", 0)
        self.import_log("lint", "analyzer-lint", "anything", 0)
        log = check_result.result_dir("P3-I5") / "logs" / "analyzer-tests.log"
        log.write_text("changed", encoding="utf-8")

        summary = check_result.build_summary("P3-I5")

        self.assertEqual(summary["conclusion"], "incomplete")
        changed = next(item for item in summary["checks"] if item["name"] == "analyzer-tests")
        self.assertEqual(changed["source"], "changed_log")

    def test_summary_file_contains_only_compact_results(self):
        self.init()
        self.import_log("test", "analyzer-tests", "large raw output", 0)
        self.import_log("lint", "analyzer-lint", "large raw output", 0)
        args = SimpleNamespace(increment="P3-I5")

        with redirect_stdout(io.StringIO()):
            check_result.command_summarize(args)

        summary_path = check_result.result_dir("P3-I5") / "summary.json"
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        self.assertEqual(summary["conclusion"], "pass")
        self.assertNotIn("large raw output", summary_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
