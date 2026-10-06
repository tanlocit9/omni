#!/usr/bin/env python3
"""Enforce line and branch thresholds for named classes in a JaCoCo XML report.

This is intentionally separate from reusable project coverage targets. Increment
verification supplies the impacted class names after blast-radius analysis.
"""

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
    parser.add_argument("classes", nargs="+")
    return parser.parse_args()


def ratio(element: ET.Element, counter_type: str) -> float:
    counter = next(
        (
            item
            for item in element.findall("counter")
            if item.attrib.get("type") == counter_type
        ),
        None,
    )
    if counter is None:
        return 0.0
    covered = int(counter.attrib.get("covered", "0"))
    missed = int(counter.attrib.get("missed", "0"))
    total = covered + missed
    return covered / total if total else 1.0


def normalize(value: str) -> str:
    return value.replace(".", "/").replace("\\", "/").strip("/")


def main() -> int:
    args = parse_args()
    root = ET.parse(args.xml).getroot()
    indexed = {
        normalize(element.attrib["name"]): element
        for element in root.findall(".//class")
    }
    failed = False

    for requested in args.classes:
        name = normalize(requested)
        element = indexed.get(name)
        if element is None:
            print(f"COVERAGE MISSING class={requested}")
            failed = True
            continue

        line_rate = ratio(element, "LINE")
        branch_rate = ratio(element, "BRANCH")
        status = (
            "PASS"
            if line_rate >= args.line and branch_rate >= args.branch
            else "FAIL"
        )
        print(
            f"COVERAGE {status} class={requested} "
            f"line={line_rate:.2%} branch={branch_rate:.2%}"
        )
        failed |= status == "FAIL"

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
