# AliClean — 支付宝首页净化 Xposed 模块

> **本项目与蚂蚁集团、支付宝官方无任何关联。** 这是一个第三方开源的 Xposed/LSPosed 界面清理模块，仅在**用户自己的设备**上、对**用户自己安装的客户端**做本地 UI 精简。使用前请务必阅读 [DISCLAIMER.md](DISCLAIMER.md)。

一个用于支付宝 Android 客户端的界面精简模块，去掉首页信息流推荐与底部电商类 Tab，让首页回归"工具"属性。

- 目标包名：`com.eg.android.AlipayGphone`
- 实测版本：`12.12.36.8000`（versionCode 212370，v3.1 主测）、`12.12.30.8000`（versionCode 212310，v2.1 验证）
- 框架：LSPosed / Vector（Xposed API 82）
- 模块包名：`com.solab.alipayclean`

---

## 下载

**[⬇ AliClean v3.1](https://github.com/279296927/alipay-home-clean/releases/latest)**

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
```

> 从论坛、网盘等非本仓库渠道获取的副本可能已被植入恶意代码。**下载后请务必核对 SHA-256。**

安装后模块**没有任何权限**（manifest 连 `INTERNET` 都没声明），可自行用 `aapt2 dump permissions` 或任意 APK 分析工具验证。

### 版本选择

| 客户端版本 | 推荐模块版本 | 说明 |
|---|---|---|
| `12.12.36.8000` 及以上 | **v3.1** | 已实测，动态签名探测 + 极速模式强制开启 |
| `12.12.30.8000` 及附近 | v3.0 或 v2.1 | v3.0 保留旧类名候选，但未在旧版回归实测 |
| 更早版本 | 自测 | hook 点可能完全不同，日志里会打印目标类全部方法签名供参考 |

v2.1 的历史产物仍在 [Releases](https://github.com/279296927/alipay-home-clean/releases) 里，可随时回退。

---

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

### 底部 Tab：5 → 3

| 原 Tab | Tab ID | 处理 |
|---|---|---|
| 首页 | `20000002` | 保留 |
| 理财 | `20001688` | **移除** |
| 视频 | `20002065` | **移除** |
| 消息 | `20000217` | 保留 |
| 我的 | `20000004` | 保留 |

### 首页宫格

移除"闪购极速版"（`appId=20000120`）。其余常用入口（生活缴费、转账、借呗、花呗、高德打车、我的快递、芝麻信用、菜鸟、市民中心、手机充值、医保…）全部保留，宫格保留 11 项。

### 首页信息流推荐区

移除 MultiTab 的三个服务端下发标签页：

| 标签标题 | itemId |
|---|---|
| 为你推荐 | `all` |
| 闪购外卖 | `EA_TAB` |
| 秒杀商品 | `GOODS_TAB` |

标签清空后整块信息流视图置 `GONE`，连带清空 feed 数据，避免残留首屏广告卡。

---

## Hook 点

v3.0 起改为**按签名动态探测**，方法名变化不再导致 hook 失效。

| 目标类 | 定位方式 | 时机 | 动作 |
|---|---|---|---|
| `com.alipay.android.launcher.TabLauncherFragment` | 固定名 `attachTab(boolean)` | before | 从 `widgetGroups` 摘除目标 Tab ID |
| `com.alipay.android.launcher.TabLauncherFragment` | 同上 | after | 打印最终 Tab 列表 |
| `com.alipay.android.launcher.tab.TabBarManager` | 固定名 `refreshTabBar()` | before | 按 `mTabId` 兜底再摘一次 |
| `...home.homegrid.HomeGridRecyclerAdapter` | **签名 `(List, boolean) → List`** | before | 净化宫格入参列表（12.12.30 是 `e`，12.12.36 是 `c`） |
| `...home.homegrid.HomeGridRecyclerAdapter` | **签名 `(List) → void`** | before | 兜底再净化（新版是 `n` / `q`） |
| `com.alipay.mobile.homefeeds.rg` | **类名候选 + 字段校验** | before | 净化 MultiTab 标签列表 `h`，清空则连 `m` 一起清空 |
| `com.alipay.mobile.homefeeds.rg` | 同上 | after | 标签为空则收起整块信息流 |
| `dalvik.system.DexClassLoader` | 构造器 | after | 捕获 Quinox 动态 bundle 的 ClassLoader |
| `android.app.Application` | 构造器 | after | 兜底挂载 + 心跳日志 |

信息流 Adapter 的类名候选列表为 `{com.alipay.mobile.homefeeds.rg, com.alipay.mobile.homefeeds.lg}`，且要求类同时存在 `h`（标签列表）与 `f`（MultiTabBarLayout）字段才认定命中——因为旧版 `lg` 在新版里变成了一个无关的 synthetic Runnable，纯按名字挂会挂错对象。

---

## 构建

```bash
cd hook
bash build.sh                                # 默认输出 out/AliClean-v3.1.apk
APKNAME=AliClean-v3.1.apk bash build.sh      # 显式指定
```

依赖（先用环境变量或参数覆盖，未设置时脚本使用下列默认值）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ANDROID_HOME` | `/opt/android-sdk` | SDK 根目录 |
| `AJAR` | `$SDK/platforms/android-34/android.jar` | 编译用 android.jar |
| `BT` | `$SDK/build-tools/34.0.0` | build-tools 目录（需 `d8` / `zipalign` / `apksigner`） |
| `AAPT2` | `/usr/bin/aapt2` | aapt2 可执行文件 |
| `ZIP` | `/usr/bin/zip` | zip 命令 |
| `XPJAR` | `hook/libs/api-82.jar` | Xposed API jar，**需自行放入**（不随仓库分发） |
| `KS` | `hook/keystore/debug.keystore` | 签名 keystore，**需自行生成**（不随仓库分发） |

例如：

```bash
export AJAR=/path/to/android.jar
export XPJAR=/path/to/api-82.jar
export KS=/path/to/my.keystore
bash build.sh
```

构建链：`javac` → `d8` → `aapt2 link` → `zip` 注入 dex/assets → `zipalign` → `apksigner`。

## 安装

```bash
cp out/AliClean-v3.1.apk /data/local/tmp/
chmod 644 /data/local/tmp/AliClean-v3.1.apk
pm install -r /data/local/tmp/AliClean-v3.1.apk
```

> 从 `/sdcard` 等 FUSE 路径直接 `pm install` 可能被 SELinux 拦截，先拷到 `/data/local/tmp/` 更稳。

安装后在 LSPosed / Vector 管理器中启用模块，作用域勾选**支付宝**，然后强制停止支付宝并重新打开。

## 自定义

源码顶部常量：

```java
static final Set<String>  DROP_TAB_IDS    // 默认 20001688, 20002065
static final Set<String>  DROP_APP_IDS    // 默认 20000120（闪购极速版）
static final String[]     DROP_APP_KEYS   // 默认 闪购 / 秒杀 / 限时秒 / 特卖 / 今日爆款
static final String[]     DROP_TAB_TITLES // 默认 为你推荐 / 推荐 / 闪购 / 秒杀
```

想恢复某个 Tab，从 `DROP_TAB_IDS` 里删掉对应 ID 重新构建即可。

新增要清理的宫格 App，往 `DROP_APP_IDS` 里加 `appId`，或往 `DROP_APP_KEYS` 里加名称关键词。

## 客户端升级后失效怎么办

v3.0 已针对方法名漂移做了动态探测，但支付宝偶尔会连签名一起改。此时模块不会静默失败，而是在日志里打印目标类的**全部方法签名**：

```
[AliClean] !! grid: no matching method, signatures are:
[AliClean]    c(java.util.List,boolean):java.util.ArrayList
[AliClean]    n(java.util.List):void
```

按日志里实际出现的签名去 `FEEDS_CLASSES` / `GRID_CLASS` 或探测条件里补一行即可。

## 日志排查

支付宝是多进程应用，日志里会混入其它进程内容，建议按主进程 pid 过滤：

```bash
PID=$(pidof com.eg.android.AlipayGphone | awk '{print $1}')
logcat -d | awk -v p="$PID" '$3==p' | grep -a 'AliClean'
```

正常启动应看到：

```
=== AliClean v3.0 attached, pkg=com.eg.android.AlipayGphone ===
hook TabLauncherFragment OK
attachTab hooked
refreshTabBar hooked
hook HomeGridRecyclerAdapter OK
grid transform hooked -> c(List,boolean):List
grid rebuild hooked -> n(List)
grid rebuild hooked -> q(List)
feeds class resolved: com.alipay.mobile.homefeeds.rg
hook MultiTabHomeAdapter(rg) OK
feeds bind hooked -> a(boolean)
[attachTab-after] tabs=3 :: 0:{id=20000002,text=首页} 1:{id=20000217,text=消息} 2:{id=20000004,text=我的}
[grid-rebuild:q] drop grid id=20000120 name=闪购极速版
[feeds-before:a] drop multitab title=为你推荐 itemId=all
[collapse] feed block GONE (multiTabs empty)
[heartbeat] loaders=2 hooked=[frag, grid, feeds]
```

若 `hooked` 里缺项，说明对应 hook 没挂上，把日志发到 Issue。

---

## 实现笔记

- Tab 的真实数据源是 `TabLauncherFragment.widgetGroups`（`List<IWidgetGroup>`）。`attachTab` 在循环里逐个 `new TabWidgetItemView / LifeTabWidgetItemView` 再 `tabHost.addTab`，在 `before` 里改列表可以一次性减 Tab，不会造成 `tabHost` 索引错乱。
- `TabBarManager.refreshTabBarInternal()` 会调用 `DynamicTabHelper.setTabsName(...)` 用服务端下发的名字覆盖本地文案（所以同一个 Tab ID 的显示名可能变成"视频"）。**按 ID 过滤比按文案过滤可靠。**
- 宫格真实数据 12 项，转换方法输出 13 项（内部追加"更多"），再经分页补 `viewType=3844` 空位。过滤要放在**入参**上，放在返回值上会打乱分页与"更多"的位置。
- 首页信息流是 `tabhomefeeds.multitab` 的 MultiTab + ViewPager2 结构，由 `homefeeds.rg`（旧版为 `lg`）这个 `RecyclerView.Adapter` 驱动：`h` = `ArrayList<TabBarItemModel>`，`m` = 各页 feed 控制器，`f` = `MultiTabBarLayout`，`c` = 持有 `itemView` 的 ViewHolder。
- 信息流的标签文案完全由服务端下发，在 APK 的 `strings` 里搜不到，只能按运行时 `title` 关键词匹配——这也是本项目用关键词而非硬编码列表的原因。
- 12.12.36 的重命名规律是**同包内短名互换**：`e(List,boolean)` → `c(List,boolean)`，`r(List)` → `n(List)` / `q(List)`，`lg` → `rg`。这种改动对按名字硬编码的 hook 是致命的，但对签名探测无影响。
- `com.alipay.mobile.homefeeds.rg` 里有 4 个同签名 `(boolean) → void` 方法（`a` / `k` / `l` / `o`），v3.0 全部挂上做冗余，任一触发即可完成过滤。

---

## 许可证

代码以 [MIT License](LICENSE) 发布。

本项目**不包含**任何来自支付宝客户端的反编译代码、资源文件或私有实现，仅包含自有 hook 代码与为实现互操作所必需的公开类名/方法名。

## 免责声明

**请先阅读 [DISCLAIMER.md](DISCLAIMER.md) 再使用或分发本模块。** 简版：

- 与蚂蚁集团/支付宝官方无关联，非官方产品，未获其认可或授权
- 仅在本地移除 UI 元素，不修改服务端数据，不触碰支付、风控、账户与安全相关逻辑，不绕过任何鉴权或付费
- 不收集、不上传任何数据，无网络请求
- 使用 Xposed 类框架修改第三方客户端可能违反该客户端的用户协议，存在账号风险与稳定性风险，**由使用者自行承担**
- 按"现状"提供，不附带任何明示或暗示担保
