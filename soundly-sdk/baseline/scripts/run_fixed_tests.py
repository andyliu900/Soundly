#!/usr/bin/env python3
"""Run and freeze the Soundly SDK unit and connected-device test reports."""

from __future__ import annotations

import argparse
import json
import platform
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def run(command: list[str], cwd: Path) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def aggregate(xml_files: list[Path]) -> dict[str, int | float]:
    result: dict[str, int | float] = {
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time_seconds": 0.0,
    }
    for xml_file in xml_files:
        root = ET.parse(xml_file).getroot()
        result["tests"] += int(root.attrib.get("tests", 0))
        result["failures"] += int(root.attrib.get("failures", 0))
        result["errors"] += int(root.attrib.get("errors", 0))
        result["skipped"] += int(root.attrib.get("skipped", 0))
        result["time_seconds"] += float(root.attrib.get("time", 0.0))
    result["time_seconds"] = round(float(result["time_seconds"]), 3)
    return result


def copy_reports(files: list[Path], destination: Path, prefix: str) -> list[str]:
    copied = []
    destination.mkdir(parents=True, exist_ok=True)
    for index, source in enumerate(files, start=1):
        name = f"{prefix}-{index:02d}-{source.name}"
        shutil.copy2(source, destination / name)
        copied.append(name)
    return copied


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Directory that will receive immutable report copies",
    )
    parser.add_argument("--device-serial", help="Optional adb device serial")
    args = parser.parse_args()

    sdk_dir = Path(__file__).resolve().parents[2]
    root = sdk_dir.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    gradle = ["bash", str(root / "gradlew")]
    if args.device_serial:
        gradle.append(f"-Pandroid.testInstrumentationRunnerArguments.deviceSerial={args.device_serial}")

    run(gradle + [":soundly-sdk:testDebugUnitTest"], root)
    unit_files = sorted(
        (sdk_dir / "build/test-results/testDebugUnitTest").glob("TEST-*.xml")
    )
    if not unit_files:
        raise RuntimeError("No unit-test XML reports were produced")

    connected_command = ["bash", str(root / "gradlew"), ":soundly-sdk:connectedDebugAndroidTest"]
    environment = None
    if args.device_serial:
        environment = {**dict(__import__("os").environ), "ANDROID_SERIAL": args.device_serial}
    print("+", " ".join(connected_command), flush=True)
    subprocess.run(connected_command, cwd=root, env=environment, check=True)
    instrumented_files = sorted(
        (sdk_dir / "build/outputs/androidTest-results/connected/debug").glob("TEST-*.xml")
    )
    if not instrumented_files:
        raise RuntimeError("No connected-test XML reports were produced")

    unit_summary = aggregate(unit_files)
    instrumented_summary = aggregate(instrumented_files)
    unit_names = copy_reports(unit_files, output / "raw", "unit")
    instrumented_names = copy_reports(
        instrumented_files, output / "raw", "instrumented"
    )

    adb = ["adb"]
    if args.device_serial:
        adb += ["-s", args.device_serial]
    device = subprocess.run(
        adb + ["shell", "getprop", "ro.product.manufacturer"],
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    model = subprocess.run(
        adb + ["shell", "getprop", "ro.product.model"],
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    api_level = subprocess.run(
        adb + ["shell", "getprop", "ro.build.version.sdk"],
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()

    metadata = {
        "schema_version": 1,
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "git_head": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True, check=True
        ).stdout.strip(),
        "git_worktree_dirty": bool(
            subprocess.run(
                ["git", "status", "--porcelain"], cwd=root, text=True, capture_output=True, check=True
            ).stdout.strip()
        ),
        "host": {"system": platform.platform(), "python": sys.version.split()[0]},
        "device": {
            "serial": args.device_serial,
            "manufacturer": device,
            "model": model,
            "android_api": api_level,
        },
        "unit_tests": {**unit_summary, "reports": unit_names},
        "instrumented_tests": {
            **instrumented_summary,
            "reports": instrumented_names,
        },
    }
    (output / "test-baseline.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    markdown = f"""# Soundly SDK 固定测试基线

- 捕获时间（UTC）：`{metadata['captured_at_utc']}`
- Git HEAD：`{metadata['git_head']}`
- 工作树包含未提交改动：`{str(metadata['git_worktree_dirty']).lower()}`
- 设备：`{device} {model}`，Android API `{api_level}`
- 单元测试：{unit_summary['tests']} 个，通过 {unit_summary['tests'] - unit_summary['failures'] - unit_summary['errors']} 个，失败 {unit_summary['failures'] + unit_summary['errors']} 个，跳过 {unit_summary['skipped']} 个
- 连接设备仪器测试：{instrumented_summary['tests']} 个，通过 {instrumented_summary['tests'] - instrumented_summary['failures'] - instrumented_summary['errors'] - instrumented_summary['skipped']} 个，失败 {instrumented_summary['failures'] + instrumented_summary['errors']} 个，跳过 {instrumented_summary['skipped']} 个

机器可读元数据见 `test-baseline.json`，原始 JUnit XML 位于 `raw/`。报告只证明本次捕获时的代码状态，不表示工作项 14 或工作项 20 已完成。
"""
    (output / "TEST_BASELINE.md").write_text(markdown, encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
