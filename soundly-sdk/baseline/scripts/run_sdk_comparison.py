#!/usr/bin/env python3
"""Benchmark Git HEAD and the current SDK against identical audio fixtures."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


BENCHMARK_CLASS = "com.ideacode.soundly_sdk.baseline.SdkBaselineBenchmarkTest"
METHODS = ("allFixtures",)
EXPECTED_FIXTURES = (
    "short_mono.wav",
    "silence_mono.wav",
    "stereo.wav",
    "five_minutes_mono.wav",
    "thirty_minutes_mono.wav",
    "corrupt_audio.wav",
)
RESULT_PATTERN = re.compile(r"SOUNDLY_BASELINE_RESULT=(\{.*\})")


def command(args: list[str], cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(args), flush=True)
    return subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True)


def install_probe(
    project: Path,
    probe: Path,
    fixtures: Path,
    test_application_id: str,
) -> tuple[Path, list[Path], Path, str]:
    target = project / "soundly-sdk/src/androidTest/java/com/ideacode/soundly_sdk/baseline/SdkBaselineBenchmarkTest.kt"
    if target.exists():
        raise RuntimeError(f"Refusing to overwrite existing probe: {target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(probe, target)

    assets = project / "soundly-sdk/src/androidTest/assets"
    assets.mkdir(parents=True, exist_ok=True)
    copied = []
    for fixture in fixtures.glob("*.fixture"):
        if fixture.is_file():
            destination = assets / fixture.name
            if destination.exists():
                raise RuntimeError(f"Refusing to overwrite existing fixture: {destination}")
            shutil.copy2(fixture, destination)
            copied.append(destination)
    build_file = project / "soundly-sdk/build.gradle.kts"
    original_build = build_file.read_text(encoding="utf-8")
    marker = "defaultConfig {"
    if marker not in original_build:
        raise RuntimeError(f"Cannot locate Android defaultConfig in {build_file}")
    instrumented_build = original_build.replace(
        marker,
        marker + f'\n        testApplicationId = "{test_application_id}"',
        1,
    )
    build_file.write_text(instrumented_build, encoding="utf-8")
    return target, copied, build_file, original_build


def remove_probe(
    target: Path,
    copied: list[Path],
    build_file: Path,
    original_build: str,
) -> None:
    build_file.write_text(original_build, encoding="utf-8")
    target.unlink(missing_ok=True)
    for fixture in copied:
        fixture.unlink(missing_ok=True)
    for directory in (target.parent, *target.parents):
        if directory.name in {"java", "androidTest", "src", "soundly-sdk"}:
            break
        try:
            directory.rmdir()
        except OSError:
            break


def parse_result(project: Path) -> dict | None:
    result_root = project / "soundly-sdk/build/outputs/androidTest-results/connected/debug"
    xml_files = sorted(result_root.glob("TEST-*.xml"), key=lambda item: item.stat().st_mtime, reverse=True)
    for xml_file in xml_files:
        root = ET.parse(xml_file).getroot()
        chunks = [root.findtext("system-out") or "", root.findtext("system-err") or ""]
        for test_case in root.findall("testcase"):
            chunks.extend([test_case.findtext("system-out") or "", test_case.findtext("system-err") or ""])
        match = RESULT_PATTERN.search("\n".join(chunks))
        if match:
            return json.loads(match.group(1))
    return None


def parse_report_results(project: Path) -> list[dict]:
    result_root = project / "soundly-sdk/build/outputs/androidTest-results/connected/debug"
    logcats = sorted(
        result_root.glob("**/logcat-*SdkBaselineBenchmarkTest-allFixtures.txt"),
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    for logcat in logcats:
        records = []
        for match in RESULT_PATTERN.finditer(logcat.read_text(encoding="utf-8", errors="replace")):
            try:
                records.append(json.loads(match.group(1)))
            except json.JSONDecodeError:
                continue
        if records:
            return records
    return []


def parse_device_results(serial: str) -> list[dict]:
    result = subprocess.run(
        [
            "adb",
            "-s",
            serial,
            "shell",
            "cat",
            "/data/local/tmp/soundly-baseline-result.b64",
        ],
        text=True,
        capture_output=True,
    )
    if result.returncode != 0 or not result.stdout.strip():
        return []
    parsed = []
    for line in result.stdout.splitlines():
        try:
            decoded = base64.b64decode(line.strip()).decode("utf-8")
            parsed.append(json.loads(decoded))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            continue
    return parsed


def benchmark(label: str, project: Path, probe: Path, fixtures: Path, serial: str) -> list[dict]:
    package_suffix = "current" if label == "current-worktree" else "legacy"
    target, copied, build_file, original_build = install_probe(
        project,
        probe,
        fixtures,
        f"com.ideacode.soundlybaseline.{package_suffix}",
    )
    results = []
    environment = {**os.environ, "ANDROID_SERIAL": serial}
    try:
        clean = command(
            ["bash", str(project / "gradlew"), ":soundly-sdk:clean"],
            project,
            environment,
        )
        if clean.returncode != 0:
            raise RuntimeError("Unable to clean benchmark build outputs:\n" + clean.stderr)
        for method in METHODS:
            subprocess.run(
                [
                    "adb",
                    "-s",
                    serial,
                    "shell",
                    "rm",
                    "-f",
                    "/data/local/tmp/soundly-baseline-result.b64",
                ],
                capture_output=True,
            )
            run = command(
                [
                    "bash",
                    str(project / "gradlew"),
                    ":soundly-sdk:connectedDebugAndroidTest",
                    f"-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_CLASS}#{method}",
                ],
                project,
                environment,
            )
            parsed_records = parse_device_results(serial) or parse_report_results(project)
            if not parsed_records:
                fallback = parse_result(project)
                if fallback:
                    parsed_records = [fallback]
            seen = {record.get("fixture") for record in parsed_records}
            for fixture in EXPECTED_FIXTURES:
                if fixture not in seen:
                    parsed_records.append(
                        {
                            "fixture": fixture,
                            "outcome": "harness_or_process_failure",
                            "gradle_exit_code": run.returncode,
                            "diagnostic": (run.stdout + "\n" + run.stderr)[-4000:],
                        }
                    )
            for parsed in parsed_records:
                parsed["sdk_baseline"] = label
                results.append(parsed)
                print(json.dumps(parsed, ensure_ascii=False), flush=True)
    finally:
        remove_probe(target, copied, build_file, original_build)
    return results


def add_comparison(records: list[dict]) -> list[dict]:
    indexed = {(item["sdk_baseline"], item["fixture"]): item for item in records}
    comparisons = []
    fixture_names = sorted({item["fixture"] for item in records})
    for fixture in fixture_names:
        old = indexed.get(("git-head-legacy", fixture))
        current = indexed.get(("current-worktree", fixture))
        comparison = {"fixture": fixture, "old": old, "current": current}
        if old and current:
            comparison["output_identical"] = (
                old.get("output_sha256") is not None
                and old.get("output_sha256") == current.get("output_sha256")
            )
            if old.get("elapsed_ms") and current.get("elapsed_ms"):
                comparison["elapsed_delta_ms"] = current["elapsed_ms"] - old["elapsed_ms"]
            if old.get("peak_pss_kb") and current.get("peak_pss_kb"):
                comparison["peak_pss_delta_kb"] = current["peak_pss_kb"] - old["peak_pss_kb"]
        comparisons.append(comparison)
    return comparisons


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device-serial", required=True)
    parser.add_argument("--old-ref", default="HEAD")
    parser.add_argument(
        "--only",
        choices=("both", "current", "legacy"),
        default="both",
        help="Run both baselines or only one side of the comparison",
    )
    args = parser.parse_args()

    sdk_dir = Path(__file__).resolve().parents[2]
    root = sdk_dir.parent
    baseline = sdk_dir / "baseline"
    fixtures = baseline / "fixtures/generated"
    probe = baseline / "benchmark/src/com/ideacode/soundly_sdk/baseline/SdkBaselineBenchmarkTest.kt"
    if not fixtures.exists():
        raise RuntimeError("Generate fixtures before running the comparison")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def device_property(name: str) -> str:
        return subprocess.run(
            ["adb", "-s", args.device_serial, "shell", "getprop", name],
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()

    device = {
        "type": "Android device",
        "manufacturer": device_property("ro.product.manufacturer"),
        "model": device_property("ro.product.model"),
        "android_api": int(device_property("ro.build.version.sdk")),
    }
    records = []
    if args.only in {"both", "current"}:
        records += benchmark("current-worktree", root, probe, fixtures, args.device_serial)
    else:
        for record in parse_report_results(root):
            record["sdk_baseline"] = "current-worktree"
            records.append(record)

    if args.only in {"both", "legacy"}:
        with tempfile.TemporaryDirectory(prefix="soundly-old-sdk-") as temporary:
            old_project = Path(temporary) / "Soundly"
            subprocess.run(
                ["git", "worktree", "add", "--detach", str(old_project), args.old_ref],
                cwd=root,
                check=True,
            )
            try:
                records += benchmark("git-head-legacy", old_project, probe, fixtures, args.device_serial)
            finally:
                subprocess.run(
                    ["git", "worktree", "remove", "--force", str(old_project)],
                    cwd=root,
                    check=False,
                )

    payload = {
        "schema_version": 1,
        "old_ref": subprocess.run(
            ["git", "rev-parse", args.old_ref], cwd=root, text=True, capture_output=True, check=True
        ).stdout.strip(),
        "device_serial": args.device_serial,
        "device": device,
        "preset": "RAW",
        "memory_metric": "peak process PSS sampled every 20 ms with android.os.Debug.getPss()",
        "records": records,
        "comparisons": add_comparison(records),
    }
    (output / "sdk-comparison.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Wrote comparison to {output / 'sdk-comparison.json'}")


if __name__ == "__main__":
    main()
