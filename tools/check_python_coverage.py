#!/usr/bin/env python3
"""Enforce line and branch thresholds for each named file in coverage.py XML."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", required=True, type=Path)
    parser.add_argument("--line", type=float, default=0.80)
    parser.add_argument("--branch", type=float, default=0.80)
    parser.add_argument("files", nargs="+")
    return parser.parse_args()


def normalized(value: str) -> str:
    return value.replace("\\", "/").lstrip("./")


def main() -> int:
    args = parse_args()
    root = ET.parse(args.xml).getroot()
    classes = {
        normalized(element.attrib["filename"]): element
        for element in root.findall(".//class")
    }
    failed = False
    for requested in args.files:
        name = normalized(requested)
        aliases = {name}
        for source_root in ("app/", "py_common/"):
            if name.startswith(source_root):
                aliases.add(name.removeprefix(source_root))
        matches = [
            element
            for filename, element in classes.items()
            if any(
                filename == alias or filename.endswith(f"/{alias}")
                for alias in aliases
            )
        ]
        if len(matches) != 1:
            print(f"COVERAGE MISSING file={name} matches={len(matches)}")
            failed = True
            continue
        element = matches[0]
        line_rate = float(element.attrib.get("line-rate", "0"))
        branch_rate = float(element.attrib.get("branch-rate", "0"))
        status = "PASS" if line_rate >= args.line and branch_rate >= args.branch else "FAIL"
        print(
            f"COVERAGE {status} file={name} "
            f"line={line_rate:.2%} branch={branch_rate:.2%}"
        )
        failed |= status == "FAIL"
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
