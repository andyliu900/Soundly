# Soundly SDK 基线冻结

此目录只服务于工作项 1：固定输入、测试结果与 SDK 1.x/当前工作树的可比较证据。它不表示长音频流式处理或发布验收已经完成。

## 基线身份

- 旧 SDK：Git `HEAD`，提交 `5f3b8cf75522ef925a0f9195d7e55101f0fbd908`。
- 当前 SDK：同一提交上的本地工作树，包含尚未提交的 SDK 2.0 改造。
- 固定设备：OPPO PHY110，Android API 36。
- 冻结日期：2026-10-25。

## 目录

- `fixtures/generate_audio_fixtures.py`：确定性生成 6 个输入样本。
- `fixtures/fixtures-manifest.json`：样本规格、大小与 SHA-256。
- `benchmark/`：只在基准命令执行时临时注入的兼容性探针，不计入正式 30+24 测试。
- `scripts/run_fixed_tests.py`：执行并复制 JUnit XML，生成机器可读摘要。
- `scripts/extract_public_api.py`：从 AAR 的 `classes.jar` 提取 `javap -public` 结果。
- `scripts/run_sdk_comparison.py`：在同一设备、同一输入、同一 RAW 预设下比较旧版与当前版。
- `reports/frozen-2026-10-25/`：本次冻结的固定报告。

## 重建样本

```bash
python3 soundly-sdk/baseline/fixtures/generate_audio_fixtures.py
```

大音频文件位于 `fixtures/generated/` 并被 Git 忽略。生成器输出包括短音频、静音、立体声、损坏文件、5 分钟和 30 分钟音频。基准传输副本使用 `.fixture` 扩展名保存可复现的 gzip 数据，防止 Android 构建工具自动展开 `.gz`。

## 重跑固定测试

```bash
python3 soundly-sdk/baseline/scripts/run_fixed_tests.py \
  --output soundly-sdk/baseline/reports/frozen-2026-10-25 \
  --device-serial 38a3e1da
```

## 重跑旧版/当前版对照

```bash
python3 soundly-sdk/baseline/scripts/run_sdk_comparison.py \
  --output soundly-sdk/baseline/reports/frozen-2026-10-25 \
  --device-serial 38a3e1da \
  --old-ref HEAD
```

脚本使用临时 Git worktree 构建旧 SDK，并在退出时移除临时探针、样本和 worktree。它不会回退当前工作树。

## 判定规则

- 测试结果以保存的 JUnit XML 为准，而不是 Gradle 控制台摘要。
- 输出一致性以输出 WAV 的 SHA-256 为准。
- 耗时使用 `SystemClock.elapsedRealtime()`，只包围 SDK 处理调用，不包含样本解压。
- 峰值内存使用 `Debug.getPss()` 每 20 ms 采样；报告同时保留初始 PSS 和峰值增量。
- 损坏文件的预期结果是可控失败，不是成功输出。
- 安装器失败、探针失败与 SDK 处理失败分别记录，禁止混为同一种失败。
