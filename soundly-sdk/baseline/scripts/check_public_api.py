#!/usr/bin/env python3
"""Fail when the minified release AAR differs from the frozen 2.0 bytecode API."""

from __future__ import annotations

import argparse
import difflib
import subprocess
import tempfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    args = parser.parse_args()

    project = args.project.resolve()
    baseline = args.baseline.resolve()
    if not baseline.is_file():
        raise SystemExit(f"API baseline not found: {baseline}")

    extractor = (
        project / "soundly-sdk/baseline/scripts/extract_public_api.py"
    )
    with tempfile.TemporaryDirectory(prefix="soundly-api-check-") as temporary:
        current = Path(temporary) / "api-current.txt"
        subprocess.run(
            [
                "python3",
                str(extractor),
                "--project",
                str(project),
                "--output",
                str(current),
                "--label",
                "2.0.0",
                "--variant",
                "release",
                "--supported-api-only",
            ],
            check=True,
        )
        expected_lines = baseline.read_text(encoding="utf-8").splitlines()
        current_lines = current.read_text(encoding="utf-8").splitlines()
        if expected_lines != current_lines:
            diff = "\n".join(
                difflib.unified_diff(
                    expected_lines,
                    current_lines,
                    fromfile=str(baseline),
                    tofile="current release AAR",
                    lineterm="",
                )
            )
            raise SystemExit(f"Public API compatibility check failed:\n{diff}")

    print(f"Public API matches {baseline}")


if __name__ == "__main__":
    main()
