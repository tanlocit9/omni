"""Local CLI subprocess defaults; no probes or global environment mutation."""

from __future__ import annotations

import os
import subprocess
import sys


def hidden_process_options():
    """Hide the immediate Windows child without introducing another launcher."""
    return {
        "creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)
        if sys.platform == "win32"
        else 0
    }


def command_environment(base=None):
    """Keep Windows descendants noninteractive while retaining caller overrides."""
    environment = dict(os.environ if base is None else base)
    if sys.platform == "win32":
        environment.setdefault("NX_NO_CLOUD", "true")
        environment.setdefault("NX_NATIVE_COMMAND_RUNNER", "false")
        environment.setdefault("NX_TUI", "false")
    return environment
