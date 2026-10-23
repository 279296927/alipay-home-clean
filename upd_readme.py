#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 README.md 从 v3.0 更新到 v3.1。"""
import io, re

P = "/root/gh/alipay-home-clean/README.md"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read()
orig = s

# 1) 实测版本行
s = s.replace(
    "- 实测版本：`12.12.36.8000`（versionCode 212370，v3.0 主测）、`12.12.30.8000`（versionCode 212310，v2.1 验证）",
    "- 实测版本：`12.12.36.8000`（versionCode 212370，v3.1 主测）、`12.12.30.8000`（versionCode 212310，v2.1 验证）")

# 2) 下载区块
old_dl = """**[⬇ AliClean v3.0](https://github.com/279296927/alipay-home-clean/releases/latest)**

| 校验 | 值 |
|---|---|
| 文件 | `AliClean-v3.0.apk`（16.5 KB） |
| SHA-256 | `fb7b2e668f5867018c5e17a570e3579c8f6f3cd366571e9019d1606c6b5f7959` |
| MD5 | `c3f75606dac80e0fff1bffd8245b8051` |
| versionCode / versionName | `4` / `3.0` |
| 签名证书 SHA-256 | `350d6c7d07d242bb3e39f77e548534b81494b3bff18015112f335bc861f478a1` |

```bash
sha256sum AliClean-v3.0.apk
# 期望: fb7b2e668f5867018c5e17a570e3579c8f6f3cd366571e9019d1606c6b5f7959
```"""

new_dl = """**[⬇ AliClean v3.1](https://github.com/279296927/alipay-home-clean/releases/latest)**

| 校验 | 值 |
|---|---|
| 文件 | `AliClean-v3.1.apk`（16.5 KB） |
| SHA-256 | `2c081f41c8e08b3015d0e659ff811283869d2a0417d4cdfc2959e982d02c3754` |
| MD5 | `f55ef646cd0ecf321f8aab8bc0207481` |
| versionCode / versionName | `5` / `3.1` |
| 签名证书 SHA-256 | `350d6c7d07d242bb3e39f77e548534b81494b3bff18015112f335bc861f478a1` |

```bash
sha256sum AliClean-v3.1.apk
# 期望: 2c081f41c8e08b3015d0e659ff811283869d2a0417d4cdfc2959e982d02c3754
```"""

if old_dl in s:
    s = s.replace(old_dl, new_dl)
else:
    print("WARN: download block not matched")

# 3) 版本选择表
old_tbl = """| `12.12.36.8000` 及以上 | **v3.0** | 已实测，动态签名探测适配 |
| `12.12.30.8000` 及附近 | v3.0（预期可用）或 v2.1 | v3.0 保留旧类名候选，但未在旧版回归实测 |"""
new_tbl = """| `12.12.36.8000` 及以上 | **v3.1** | 已实测，动态签名探测 + 极速模式强制开启 |
| `12.12.30.8000` 及附近 | v3.0 或 v2.1 | v3.0 保留旧类名候选，但未在旧版回归实测 |"""
if old_tbl in s:
    s = s.replace(old_tbl, new_tbl)
else:
    print("WARN: version table not matched")

# 4) 构建命令
s = s.replace("bash build.sh                                # 默认输出 out/AliClean-v3.0.apk",
              "bash build.sh                                # 默认输出 out/AliClean-v3.1.apk")
s = s.replace("APKNAME=AliClean-v3.0.apk bash build.sh      # 显式指定",
              "APKNAME=AliClean-v3.1.apk bash build.sh      # 显式指定")
s = s.replace("cp out/AliClean-v3.0.apk /data/local/tmp/",
              "cp out/AliClean-v3.1.apk /data/local/tmp/")
s = s.replace("chmod 644 /data/local/tmp/AliClean-v3.0.apk",
              "chmod 644 /data/local/tmp/AliClean-v3.1.apk")
s = s.replace("pm install -r /data/local/tmp/AliClean-v3.0.apk",
              "pm install -r /data/local/tmp/AliClean-v3.1.apk")

# 5) 在「效果」章节前插入极速模式小节
anchor = "---\n\n## 效果\n\n"
jisu_section = """---
## 极速模式（v3.1 新增）

「设置 → 通用 → 极速模式」并不是纯本地开关。真正的判定在 `TaskGradeController#getGradeResult`：

```java
taskStrategyInfo.strategy =
    ("T".equals(TaskDegradeLowDeviceUtil.getDeviceGradeInfo())
     && taskGradeController.isJiSuPatternSwitchOpen) ? "low" : "high";
```

只有 `strategy == "low"` 时极速模式才真正生效。`getDeviceGradeInfo()` 读 SharedPreferences 的 `task_grade_device_low`：`"T"` 判低端机，`"F"` / `"E"` 判非低端机。

中高端设备上通常是 `jisu_pattern_state_key=T`（开关开着）但 `task_grade_device_low=F`（设备被判非低端），于是 `strategy` 恒为 `"high"`，开关形同虚设。

v3.1 在**读取侧**拦截，共四层：

| 层 | 目标 | 动作 |
|---|---|---|
| 1 | `SharedPreferencesImpl.getString` | `task_grade_device_low` / `task_grade_device_low_new` / `jisu_pattern_state_key` 恒返回 `"T"` |
| 1 | `SharedPreferencesImpl.getInt` | `task_grade_device_score` / `task_grade_device_score_new` 恒返回 `0` |
| 2 | `TaskDegradeLowDeviceUtil.getDeviceGradeInfo()` | 恒返回 `"T"` |
| 3 | `TaskGradeController.getJiSuPatternValue()` | 恒返回 `true` |
| 4 | `TaskGradeController.getDevLevelAndCurRunningState()` | 返回值 `deviceLevel` 强制写成 `"low"` |

**不改机型、不改 CPU、不写任何文件**。支付宝自己留了 `mockToLowPhone()` 会一次性写盘 8 个键冒充低端机，但写盘卸载后会残留；本模块全部走读取侧，进程退出即完全恢复。

生效后日志里会出现：

```
[jisu] SP.getString(task_grade_device_low) F -> T
[jisu] deviceLevel high -> low
```

---

## 效果
"""
if anchor in s:
    s = s.replace(anchor, jisu_section, 1)
else:
    print("WARN: effect anchor not matched")

# 6) 把「效果」标题降级说明
s = s.replace("## 效果\n### 底部 Tab：5 → 3", "### 底部 Tab：5 → 3", 1)

if s == orig:
    print("NO CHANGE")
else:
    with io.open(P, "w", encoding="utf-8") as f:
        f.write(s)
    print("UPDATED, lines =", s.count("\n") + 1)