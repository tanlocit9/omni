import importlib.util
import io
import json
import os
import subprocess
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "jobs_backlog", Path(__file__).resolve().parents[1] / "jobs_backlog.py"
)
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)

HEADER = "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID"


class JobsBacklogTests(unittest.TestCase):
    def test_real_cli_shape_preserves_inactive_and_active_partition_backlog(self):
        report = tool.parse_kafka(
            HEADER + "\ng jobs 0 20 30 10 - - -\ng other 1 2 2 0 worker /host client\n",
            "Consumer group 'g' has no active members.\n",
        )
        self.assertEqual(report["knownLag"], 10)
        self.assertEqual(report["inactiveGroups"], ["g"])
        self.assertFalse(report["partitions"][0]["hasActiveConsumer"])
        self.assertTrue(report["partitions"][1]["hasActiveConsumer"])

    def test_uncommitted_or_negative_lag_is_unknown_not_zero(self):
        for row in ("g jobs 0 - 30 - - - -", "g jobs 0 31 30 -1 - - -"):
            with self.subTest(row=row):
                report = tool.parse_kafka(HEADER + "\n" + row)
                self.assertEqual(report["unknownLagPartitions"], 1)
                self.assertIsNone(report["partitions"][0]["lag"])

    def test_missing_offsets_errors_and_malformed_output_are_not_clean_reports(self):
        for output in ("", HEADER, "unexpected output", "ERROR broker unavailable",
                       "Consumer group 'g' has no offsets."):
            with self.subTest(output=output), self.assertRaises(ValueError):
                tool.parse_kafka(output)

    def test_stderr_error_invalidates_otherwise_valid_offsets(self):
        with self.assertRaises(ValueError):
            tool.parse_kafka("g jobs 0 1 1 0 - - -", "ERROR timeout")

    def test_partial_db_failure_still_reports_kafka_without_claiming_complete(self):
        args = SimpleNamespace(stale_minutes=30, skip_db=False, skip_kafka=False)
        with patch.object(tool, "read_database", side_effect=RuntimeError("database down")), \
                patch.object(tool, "read_kafka", return_value={"knownLag": 9}):
            report = tool.collect(args)
        self.assertFalse(report["complete"])
        self.assertEqual(report["database"]["status"], "unavailable")
        self.assertEqual(report["kafka"]["knownLag"], 9)

    def test_timeout_and_missing_docker_are_unavailable(self):
        args = SimpleNamespace(stale_minutes=30, skip_db=False, skip_kafka=True)
        for error in (FileNotFoundError("docker missing"), subprocess.TimeoutExpired("docker", 30)):
            with self.subTest(error=error), \
                    patch.object(tool, "read_database", side_effect=error):
                report = tool.collect(args)
            self.assertEqual(report["database"]["status"], "unavailable")
            self.assertEqual(report["kafka"]["status"], "not_checked")

    def test_database_runs_read_only_without_startup_scripts_or_shell(self):
        args = SimpleNamespace(postgres_container="postgres", db_user="postgres",
                               database="omni", stale_minutes=30, limit=20)
        with patch.object(tool, "command", return_value=('{"unfinishedCount": 2}', "")) as run:
            self.assertEqual(tool.read_database(args)["unfinishedCount"], 2)
        command, sql = run.call_args.args
        self.assertIn("-X", command)
        self.assertIn("ON_ERROR_STOP=1", command)
        self.assertIn("REPEATABLE READ READ ONLY", sql)
        self.assertIn("statement_timeout='15s'", sql)
        self.assertNotIn("::jsonb", sql.replace("'[]'::jsonb", ""))

    def test_kafka_invocation_has_no_reset_or_delete(self):
        args = SimpleNamespace(kafka_container="local-kafka", bootstrap_server="localhost:9092")
        with patch.object(tool, "command", return_value=("g jobs 0 1 2 1 - - -", "")) as run:
            tool.read_kafka(args)
        command = run.call_args.args[0]
        self.assertIn("--describe", command)
        self.assertNotIn("--reset-offsets", command)
        self.assertNotIn("--delete", command)

    def test_json_output_and_nonzero_exit_when_unavailable(self):
        report = {"database": {"status": "unavailable"}, "kafka": {"status": "observed"}}
        output = io.StringIO()
        with patch.object(tool, "collect", return_value=report), redirect_stdout(output):
            result = tool.main(["--json"])
        self.assertEqual(result, 2)
        self.assertEqual(json.loads(output.getvalue()), report)

    def test_invalid_numeric_arguments_are_rejected_before_any_connection(self):
        for value in ("0", "-1", "43201", "31d", "1;DROP TABLE x"):
            with self.subTest(value=value), patch.object(tool, "collect") as collect, \
                    redirect_stdout(io.StringIO()), patch("sys.stderr", io.StringIO()):
                with self.assertRaises(SystemExit):
                    tool.main(["--stale-minutes", value])
                collect.assert_not_called()

    def test_text_report_exposes_stale_execution_and_outbox_attention(self):
        report = {
            "staleMinutes": 30, "note": "Observation only",
            "database": {
                "status": "observed", "unfinishedCount": 2, "staleCount": 1,
                "unknownAgeCount": 0, "sampleTruncated": True,
                "oldestExecutions": [{"id": "sample"}],
                "groups": [{"job_type": "SYNC_STOCK_PRICE", "status": "RUNNING",
                            "role": "child", "count": 2, "stale_count": 1,
                            "oldest_age_minutes": 90.125}],
                "outbox": [{"topic": "jobs", "status": "PUBLISHED", "count": 2,
                            "published_unfinished_execution_count": 2}],
            },
            "kafka": {"status": "not_checked"},
        }
        with patch.dict(os.environ, {"NO_COLOR": "1"}):
            text = tool.render(report)
        self.assertIn("unfinished: 2", text)
        self.assertIn("stale: 1", text)
        self.assertRegex(text, r"SYNC_STOCK_PRICE\s+RUNNING\s+child\s+2\s+1\s+90\.1")
        self.assertIn("published/nonterminal=2", text)
        self.assertIn("truncated=True", text)
        self.assertIn("KAFKA\n  not checked", text)

    def test_duration_aliases_preserve_minutes_and_json_contract(self):
        report = {"database": {"status": "not_checked"}, "kafka": {"status": "not_checked"}}
        for option, value, minutes in (
            ("--range", "2h", 120),
            ("--stale-minutes", "1d", 1440),
            ("--stale-minutes", "10001", 10001),
            ("--range", "30d", 43200),
        ):
            output = io.StringIO()
            with (
                self.subTest(option=option, value=value),
                patch.object(tool, "collect", return_value=report) as collect,
                redirect_stdout(output),
            ):
                self.assertEqual(tool.main([option, value, "--json"]), 0)
            self.assertEqual(collect.call_args.args[0].stale_minutes, minutes)
            self.assertEqual(json.loads(output.getvalue()), report)

    def test_commands_use_one_hidden_child_without_shell_and_propagate_failure(self):
        completed = SimpleNamespace(returncode=0, stdout="data", stderr="")
        with patch.object(tool.subprocess, "run", return_value=completed) as run:
            self.assertEqual(tool.command(["docker", "info"], "input"), ("data", ""))
        run.assert_called_once()
        self.assertNotIn("shell", run.call_args.kwargs)
        self.assertEqual(run.call_args.kwargs["input"], "input")
        self.assertEqual(run.call_args.kwargs["timeout"], 30)
        completed.returncode, completed.stderr = 1, "backend unavailable"
        with patch.object(tool.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(RuntimeError, "backend unavailable"):
                tool.command(["docker", "info"])


if __name__ == "__main__":
    unittest.main()
