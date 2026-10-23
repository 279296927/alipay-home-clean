#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在 README.md 的「效果」章节前插入极速模式小节（幂等）。"""
import io

P = "/root/gh/alipay-home-clean/README.md"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read()

MARK = "## 极速模式（v3.1 新增）"
if MARK in s:
    print("ALREADY PRESENT, skip")
    raise SystemExit(0)

SECTION = """---

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

anchor = "---\n\n## 效果\n\n"
idx = s.find(anchor)
if idx < 0:
    print("ERR: anchor not found")
    raise SystemExit(1)

s = s[:idx] + SECTION + s[idx + len(anchor):]
with io.open(P, "w", encoding="utf-8") as f:
    f.write(s)
print("INSERTED, lines =", s.count("\n") + 1)