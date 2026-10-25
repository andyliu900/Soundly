#!/usr/bin/env python3
"""Generate Soundly's deterministic PCM WAV baseline fixtures.

The generated audio files are deliberately not committed: the 30 minute fixture is
large. This script and manifest make every fixture reproducible byte-for-byte.
"""

from __future__ import annotations

import hashlib
import gzip
import json
import math
import struct
import wave
from dataclasses import asdict, dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parent
OUTPUT_DIR = ROOT / "generated"
MANIFEST_PATH = ROOT / "fixtures-manifest.json"
GENERATOR_VERSION = 1


@dataclass(frozen=True)
class FixtureSpec:
    name: str
    description: str
    duration_seconds: int
    sample_rate_hz: int
    channels: int
    signal: str


SPECS = (
    FixtureSpec(
        name="short_mono.wav",
        description="3 second mono voice-like deterministic signal",
        duration_seconds=3,
        sample_rate_hz=16_000,
        channels=1,
        signal="voice_like",
    ),
    FixtureSpec(
        name="silence_mono.wav",
        description="3 second digital silence",
        duration_seconds=3,
        sample_rate_hz=16_000,
        channels=1,
        signal="silence",
    ),
    FixtureSpec(
        name="stereo.wav",
        description="3 second stereo signal with distinct left and right tones",
        duration_seconds=3,
        sample_rate_hz=44_100,
        channels=2,
        signal="stereo_tones",
    ),
    FixtureSpec(
        name="five_minutes_mono.wav",
        description="5 minute mono deterministic stress signal",
        duration_seconds=5 * 60,
        sample_rate_hz=8_000,
        channels=1,
        signal="stress",
    ),
    FixtureSpec(
        name="thirty_minutes_mono.wav",
        description="30 minute mono deterministic long-audio stress signal",
        duration_seconds=30 * 60,
        sample_rate_hz=8_000,
        channels=1,
        signal="stress",
    ),
)


def clamp_pcm16(value: float) -> int:
    return max(-32768, min(32767, round(value * 32767.0)))


def samples(spec: FixtureSpec):
    total_frames = spec.duration_seconds * spec.sample_rate_hz
    for frame in range(total_frames):
        time_seconds = frame / spec.sample_rate_hz
        if spec.signal == "silence":
            yield (0,)
        elif spec.signal == "voice_like":
            envelope = 0.72 + 0.28 * math.sin(2.0 * math.pi * 1.7 * time_seconds)
            signal = envelope * (
                0.78 * math.sin(2.0 * math.pi * 220.0 * time_seconds)
                + 0.17 * math.sin(2.0 * math.pi * 440.0 * time_seconds)
            )
            yield (clamp_pcm16(signal),)
        elif spec.signal == "stereo_tones":
            left = 0.82 * math.sin(2.0 * math.pi * 330.0 * time_seconds)
            right = 0.64 * math.sin(2.0 * math.pi * 550.0 * time_seconds)
            yield (clamp_pcm16(left), clamp_pcm16(right))
        elif spec.signal == "stress":
            # This non-silent one-second pattern repeats exactly. The decoded PCM
            # remains long while the transport asset stays small enough for adb.
            signal = 0.82 * math.sin(2.0 * math.pi * 233.0 * time_seconds)
            yield (clamp_pcm16(signal),)
        else:
            raise ValueError(f"Unknown signal: {spec.signal}")


def write_wav(path: Path, spec: FixtureSpec) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(spec.channels)
        output.setsampwidth(2)
        output.setframerate(spec.sample_rate_hz)
        block = bytearray()
        for frame in samples(spec):
            block.extend(struct.pack("<" + "h" * spec.channels, *frame))
            if len(block) >= 64 * 1024:
                output.writeframesraw(block)
                block.clear()
        if block:
            output.writeframesraw(block)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def write_reproducible_gzip(source: Path) -> Path:
    # Do not use a .gz extension: Android's asset packager transparently expands
    # it, which would make the instrumentation APK tens of megabytes again.
    target = source.with_suffix(source.suffix + ".fixture")
    with source.open("rb") as input_file, target.open("wb") as output_file:
        with gzip.GzipFile(
            filename="",
            mode="wb",
            fileobj=output_file,
            compresslevel=9,
            mtime=0,
        ) as compressed:
            while chunk := input_file.read(1024 * 1024):
                compressed.write(chunk)
    return target


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    records = []
    for spec in SPECS:
        target = OUTPUT_DIR / spec.name
        write_wav(target, spec)
        compressed = write_reproducible_gzip(target)
        records.append(
            {
                **asdict(spec),
                "relative_path": str(target.relative_to(ROOT)),
                "size_bytes": target.stat().st_size,
                "sha256": sha256(target),
                "benchmark_asset": str(compressed.relative_to(ROOT)),
                "benchmark_asset_size_bytes": compressed.stat().st_size,
                "benchmark_asset_sha256": sha256(compressed),
            }
        )

    corrupt = OUTPUT_DIR / "corrupt_audio.wav"
    corrupt.write_bytes(
        b"SOUNDLY_CORRUPT_FIXTURE\x00not-a-valid-riff-wave-file\xff\x7f"
    )
    compressed_corrupt = write_reproducible_gzip(corrupt)
    records.append(
        {
            "name": corrupt.name,
            "description": "Invalid bytes with a WAV extension",
            "duration_seconds": None,
            "sample_rate_hz": None,
            "channels": None,
            "signal": "corrupt",
            "relative_path": str(corrupt.relative_to(ROOT)),
            "size_bytes": corrupt.stat().st_size,
            "sha256": sha256(corrupt),
            "benchmark_asset": str(compressed_corrupt.relative_to(ROOT)),
            "benchmark_asset_size_bytes": compressed_corrupt.stat().st_size,
            "benchmark_asset_sha256": sha256(compressed_corrupt),
        }
    )

    manifest = {
        "schema_version": 1,
        "generator_version": GENERATOR_VERSION,
        "format": "PCM signed 16-bit little-endian WAV",
        "fixtures": records,
    }
    MANIFEST_PATH.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Generated {len(records)} fixtures in {OUTPUT_DIR}")
    print(f"Manifest: {MANIFEST_PATH}")


if __name__ == "__main__":
    main()
