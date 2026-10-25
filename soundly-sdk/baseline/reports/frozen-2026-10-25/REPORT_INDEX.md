# Soundly SDK 基线报告索引

| 交付物 | 文件 | 状态 |
|---|---|---|
| 固定测试摘要 | `TEST_BASELINE.md`、`test-baseline.json` | 30/30 单元测试、24/24 Android 16 真机测试通过 |
| 原始测试报告 | `raw/*.xml` | 10 份单元测试 JUnit XML、1 份真机 JUnit XML |
| 旧/新性能与输出对照 | `BASELINE_COMPARISON.md`、`sdk-comparison.json` | 6 个输入、12 条独立记录；4 个成功输出逐字节相同；30 分钟两版均 OOM |
| 旧版 AAR API | `api-git-head-legacy.txt` | 54 个顶层字节码类条目 |
| 当前 AAR API | `api-current-worktree.txt` | 151 个顶层字节码类条目 |
| 人工整理 API | `../../PUBLIC_API_INVENTORY.md` | 已区分设计 API、兼容 API 与实际字节码表面 |
| 音频样本清单 | `../../fixtures/fixtures-manifest.json` | 6 个固定样本，包含规格、大小与 SHA-256 |

本目录冻结工作项 1 的证据。30 分钟 OOM 是基线发现，不是通过项；它将作为工作项 14 的验收前对照。
