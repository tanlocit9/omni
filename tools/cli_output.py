"""Shared human-readable CLI output; importing this module has no side effects."""

from __future__ import annotations

import os
import re
import sys

ANSI_RE = re.compile(r"\033\[[0-9;]*m")


def configure_output(stream=None):
    """Configure an owned CLI stream, without launching a shell for ANSI support."""
    stream = sys.stdout if stream is None else stream
    reconfigure = getattr(stream, "reconfigure", None)
    if reconfigure is not None:
        try:
            reconfigure(encoding="utf-8", errors="backslashreplace")
        except (OSError, ValueError):
            pass  # Captured or already closed streams may not be reconfigurable.


def is_interactive(stream=None):
    stream = sys.stdout if stream is None else stream
    try:
        return bool(stream.isatty())
    except (AttributeError, OSError, ValueError):
        return False


def _windows_ansi(stream):
    """Enable virtual terminal processing directly, rather than invoking cmd.exe."""
    try:
        import ctypes
        import msvcrt
        from ctypes import wintypes

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetConsoleMode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.GetConsoleMode.restype = wintypes.BOOL
        kernel.SetConsoleMode.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.SetConsoleMode.restype = wintypes.BOOL
        handle = msvcrt.get_osfhandle(stream.fileno())
        mode = wintypes.DWORD()
        return bool(
            kernel.GetConsoleMode(handle, ctypes.byref(mode))
            and kernel.SetConsoleMode(handle, mode.value | 0x0004)
        )
    except (AttributeError, ImportError, OSError, ValueError):
        return False


def _uses_color(stream):
    if "NO_COLOR" in os.environ:
        return False
    forced = os.environ.get("FORCE_COLOR")
    if forced is not None:
        return forced not in {"", "0"}
    if not is_interactive(stream):
        return False
    return sys.platform != "win32" or _windows_ansi(stream)


def color(text, code, *, stream=None):
    stream = sys.stdout if stream is None else stream
    return f"\033[{code}m{text}\033[0m" if _uses_color(stream) else str(text)


def red(text, *, stream=None):
    return color(text, 31, stream=stream)


def green(text, *, stream=None):
    return color(text, 32, stream=stream)


def yellow(text, *, stream=None):
    return color(text, 33, stream=stream)


def bold(text, *, stream=None):
    return color(text, 1, stream=stream)


def dim(text, *, stream=None):
    return color(text, 2, stream=stream)


def visible_len(text):
    return len(ANSI_RE.sub("", str(text)))


def table(headers, rows, *, stream=None):
    """Align plain or ANSI-colored cells without changing any machine output."""
    stream = sys.stdout if stream is None else stream
    headers, rows = list(headers), [list(row) for row in rows]
    if not rows:
        return dim("  (none)", stream=stream)
    if not headers or any(len(row) != len(headers) for row in rows):
        raise ValueError("Table rows must match the header width")
    widths = [
        max(visible_len(row[index]) for row in [headers, *rows])
        for index in range(len(headers))
    ]

    def formatted(row):
        return "  " + "  ".join(
            str(cell) + " " * (width - visible_len(cell))
            for cell, width in zip(row, widths)
        )

    separator = "─"
    try:
        separator.encode(getattr(stream, "encoding", None) or "utf-8")
    except (LookupError, UnicodeEncodeError):
        separator = "-"
    return "\n".join(
        [
            bold(formatted(headers), stream=stream),
            "  " + "  ".join(separator * width for width in widths),
            *[formatted(row) for row in rows],
        ]
    )
