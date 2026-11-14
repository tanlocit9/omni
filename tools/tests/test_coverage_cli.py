"""Coverage tables must not change threshold decisions or machine output."""

import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


def load_tool(name):
    path = Path(__file__).resolve().parents[1] / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


python_coverage = load_tool("check_python_coverage")
jacoco_coverage = load_tool("check_jacoco_coverage")


class InteractiveOutput(io.StringIO):
    def isatty(self):
        return True


class CoverageCliTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.report = Path(self.temporary.name) / "coverage.xml"

    def run_tool(self, tool, xml, *arguments, interactive=False):
        self.report.write_text(xml, encoding="utf-8")
        output = InteractiveOutput() if interactive else io.StringIO()
        argv = ["coverage", "--xml", str(self.report), *arguments]
        with (
            patch("sys.argv", argv),
            patch.dict(os.environ, {"NO_COLOR": "1"}),
            redirect_stdout(output),
        ):
            result = tool.main()
        return result, output.getvalue()

    def test_python_machine_output_stays_plain_even_on_a_terminal(self):
        result, output = self.run_tool(
            python_coverage,
            '<coverage><class filename="pkg/item.py" line-rate="0.8" '
            'branch-rate="0.8"/></coverage>',
            "app/pkg/item.py",
            interactive=True,
        )
        self.assertEqual(result, 0)
        self.assertEqual(
            output,
            "COVERAGE PASS file=app/pkg/item.py line=80.00% branch=80.00%\n",
        )

    def test_python_table_keeps_failure_and_ambiguous_match_evidence(self):
        xml = (
            '<coverage><class filename="pkg/fail.py" line-rate="0.9" '
            'branch-rate="0.79"/><class filename="a/shared.py" '
            'line-rate="1" branch-rate="1"/><class filename="b/shared.py" '
            'line-rate="1" branch-rate="1"/></coverage>'
        )
        result, output = self.run_tool(
            python_coverage, xml, "--table", "pkg/fail.py", "shared.py"
        )
        self.assertEqual(result, 1)
        for expected in (
            "STATUS", "FILE", "MATCHES", "FAIL", "79.00%", "MISSING", "shared.py"
        ):
            self.assertIn(expected, output)
        self.assertNotIn("COVERAGE ", output)
        self.assertNotIn("\033[", output)

    def test_python_missing_and_custom_thresholds_keep_machine_semantics(self):
        xml = (
            '<coverage><class filename="pkg/item.py" line-rate="0.9" '
            'branch-rate="0.9"/></coverage>'
        )
        result, output = self.run_tool(
            python_coverage, xml, "--line", "0.95", "pkg/item.py", "absent.py"
        )
        self.assertEqual(result, 1)
        self.assertEqual(
            output,
            "COVERAGE FAIL file=pkg/item.py line=90.00% branch=90.00%\n"
            "COVERAGE MISSING file=absent.py matches=0\n",
        )

    def test_jacoco_zero_total_branch_counter_keeps_existing_pass_semantics(self):
        xml = (
            '<report><class name="pkg/Ready"><counter type="LINE" covered="8" '
            'missed="2"/><counter type="BRANCH" covered="0" missed="0"/>'
            '</class></report>'
        )
        result, output = self.run_tool(
            jacoco_coverage, xml, "pkg.Ready", interactive=True
        )
        self.assertEqual(result, 0)
        self.assertEqual(
            output,
            "COVERAGE PASS class=pkg.Ready line=80.00% branch=100.00%\n",
        )

    def test_jacoco_missing_counter_still_fails_in_table_mode(self):
        xml = (
            '<report><class name="pkg/Incomplete"><counter type="LINE" '
            'covered="10" missed="0"/></class></report>'
        )
        result, output = self.run_tool(
            jacoco_coverage, xml, "--table", "pkg.Incomplete", "pkg.Missing"
        )
        self.assertEqual(result, 1)
        for expected in (
            "STATUS", "CLASS", "FAIL", "0.00%", "MISSING", "pkg.Missing"
        ):
            self.assertIn(expected, output)
        self.assertNotIn("COVERAGE ", output)
        self.assertNotIn("\033[", output)

    def test_jacoco_custom_branch_threshold_is_not_relaxed_by_table_mode(self):
        xml = (
            '<report><class name="pkg/Ready"><counter type="LINE" covered="10" '
            'missed="0"/><counter type="BRANCH" covered="8" missed="2"/>'
            '</class></report>'
        )
        result, output = self.run_tool(
            jacoco_coverage, xml, "--table", "--branch", "0.9", "pkg.Ready"
        )
        self.assertEqual(result, 1)
        self.assertIn("FAIL", output)
        self.assertIn("80.00%", output)


if __name__ == "__main__":
    unittest.main()
