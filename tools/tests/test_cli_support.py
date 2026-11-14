import io
import os
import unittest
from unittest.mock import patch

from tools import cli_output, process_utils


class CliOutputTests(unittest.TestCase):
    def test_redirected_output_is_plain_and_explicit_color_has_visible_width(self):
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(cli_output.red("failure", stream=output), "failure")
        with patch.dict(os.environ, {"FORCE_COLOR": "1"}, clear=True):
            colored = cli_output.red("failure", stream=output)
            self.assertIn("\033[31m", colored)
            rendered = cli_output.table(["STATUS", "COUNT"], [[colored, 3]], stream=output)
        plain = cli_output.ANSI_RE.sub("", rendered).splitlines()
        self.assertEqual(plain[0].index("COUNT"), plain[2].index("3"))
        self.assertEqual(cli_output.visible_len(colored), len("failure"))

    def test_no_color_overrides_force_and_table_checks_row_shape(self):
        with patch.dict(os.environ, {"FORCE_COLOR": "1", "NO_COLOR": ""}, clear=True):
            self.assertEqual(cli_output.bold("title"), "title")
            self.assertNotIn("\033", cli_output.table(["NAME"], [["work"]]))
        with self.assertRaises(ValueError):
            cli_output.table(["NAME", "COUNT"], [["work"]])

    def test_output_configuration_works_with_captured_stream_without_shell(self):
        stream = io.StringIO()
        with patch("os.system") as shell:
            cli_output.configure_output(stream)
            cli_output.table(["NAME"], [["work"]], stream=stream)
        shell.assert_not_called()
        self.assertEqual(stream.getvalue(), "")

    def test_legacy_stream_gets_ascii_separator_without_encoding_error(self):
        class LegacyStream(io.StringIO):
            @property
            def encoding(self):
                return "ascii"

        stream = LegacyStream()
        with patch.dict(os.environ, {}, clear=True):
            rendered = cli_output.table(["NAME"], [["work"]], stream=stream)
        self.assertIn("----", rendered)
        rendered.encode("ascii")

    def test_windows_color_failure_stays_plain_without_shell_fallback(self):
        stream = io.StringIO()
        with (
            patch.dict(os.environ, {}, clear=True),
            patch.object(cli_output.sys, "platform", "win32"),
            patch.object(cli_output, "is_interactive", return_value=True),
            patch.object(cli_output, "_windows_ansi", return_value=False),
            patch("os.system") as shell,
        ):
            self.assertEqual(cli_output.red("failure", stream=stream), "failure")
        shell.assert_not_called()


class ProcessDefaultsTests(unittest.TestCase):
    def test_windows_defaults_are_local_and_preserve_explicit_overrides(self):
        original = {"NX_NO_CLOUD": "false", "NX_TUI": "", "OTHER": "keep"}
        with patch.object(process_utils.sys, "platform", "win32"):
            child = process_utils.command_environment(original)
        self.assertEqual(child["NX_NO_CLOUD"], "false")
        self.assertEqual(child["NX_TUI"], "")
        self.assertEqual(child["NX_NATIVE_COMMAND_RUNNER"], "false")
        self.assertNotIn("NX_NATIVE_COMMAND_RUNNER", original)
        self.assertIsNot(child, original)

    def test_non_windows_environment_is_unchanged_and_child_is_not_detached(self):
        original = {"OTHER": "keep"}
        with patch.object(process_utils.sys, "platform", "linux"):
            self.assertEqual(process_utils.command_environment(original), original)
            self.assertEqual(process_utils.hidden_process_options(), {"creationflags": 0})


if __name__ == "__main__":
    unittest.main()
