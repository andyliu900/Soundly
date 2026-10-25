#!/usr/bin/env python3
"""Extract a reproducible javap view of the classes packaged in a Soundly AAR."""

from __future__ import annotations

import argparse
import subprocess
import tempfile
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument(
        "--variant",
        choices=("debug", "release"),
        default="debug",
        help="AAR variant to inspect (release verifies the minified publication)",
    )
    parser.add_argument(
        "--supported-api-only",
        action="store_true",
        help="Inspect only the documented SDK 2 host boundary",
    )
    args = parser.parse_args()

    project = args.project.resolve()
    subprocess.run(
        [
            "bash",
            str(project / "gradlew"),
            f":soundly-sdk:assemble{args.variant.capitalize()}",
        ],
        cwd=project,
        check=True,
    )
    aar_relative = Path(
        f"soundly-sdk/build/outputs/aar/soundly-sdk-{args.variant}.aar"
    )
    aar = project / aar_relative
    if not aar.exists():
        raise RuntimeError(f"AAR not found: {aar}")

    with tempfile.TemporaryDirectory(prefix="soundly-api-") as temporary:
        temporary_path = Path(temporary)
        with zipfile.ZipFile(aar) as archive:
            archive.extract("classes.jar", temporary_path)
        classes_jar = temporary_path / "classes.jar"
        with zipfile.ZipFile(classes_jar) as classes:
            names = sorted(
                name[:-6].replace("/", ".")
                for name in classes.namelist()
                if name.endswith(".class")
                and (args.supported_api_only or "$" not in name)
                and not name.endswith("/R.class")
                and ".R$" not in name
                and not name.endswith("/BuildConfig.class")
            )
        if args.supported_api_only:
            names = [
                name
                for name in names
                if name.startswith("com.ideacode.soundly_sdk.api.")
                or name == "com.ideacode.soundly_sdk.processor.AudioProcessingService"
            ]

        sections = [
            f"# Soundly packaged public bytecode API: {args.label}",
            f"# AAR: {aar_relative.as_posix()}",
            "# Generated with: javap -public -constants",
            f"# Supported API only: {str(args.supported_api_only).lower()}",
            "",
        ]
        public_count = 0
        for class_name in names:
            result = subprocess.run(
                [
                    "javap",
                    "-public",
                    "-constants",
                    "-classpath",
                    str(classes_jar),
                    class_name,
                ],
                text=True,
                capture_output=True,
            )
            declaration = next(
                (
                    line.strip()
                    for line in result.stdout.splitlines()
                    if " class " in line or " interface " in line or " enum " in line
                ),
                "",
            )
            if result.returncode == 0 and declaration.startswith("public "):
                sections.append(result.stdout.rstrip())
                sections.append("")
                public_count += 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(sections), encoding="utf-8")
    print(f"Wrote {public_count} public class entries to {args.output}")


if __name__ == "__main__":
    main()
