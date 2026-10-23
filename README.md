# AliClean — 支付宝首页净化 Xposed 模块

> **本项目与蚂蚁集团、支付宝官方无任何关联。** 这是一个第三方开源的 Xposed/LSPosed 界面清理模块，仅在**用户自己的设备**上、对**用户自己安装的客户端**做本地 UI 精简。使用前请务必阅读 [DISCLAIMER.md](DISCLAIMER.md)。

一个用于支付宝 Android 客户端的界面精简模块，去掉首页信息流推荐与底部电商类 Tab，让首页回归"工具"属性。

- 目标包名：`com.eg.android.AlipayGphone`
- 测试版本：`12.12.30.8000`（versionCode 212310）
- 框架：LSPosed / Vector（Xposed API 82）
- 模块包名：`com.solab.alipayclean`

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

移除"闪购极速版"（`appId=20000120`）。其余常用入口（生活缴费、转账、借呗、花呗、高德打车、我的快递、芝麻信用、菜鸟、市民中心、飞猪旅行、手机营业厅）全部保留。

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

| 目标类 | 方法 | 时机 | 动作 |
|---|---|---|---|
| `com.alipay.android.launcher.TabLauncherFragment` | `attachTab(boolean)` | before | 从 `widgetGroups` 摘除目标 Tab ID |
| `com.alipay.android.launcher.TabLauncherFragment` | `attachTab(boolean)` | after | 打印最终 Tab 列表 |
| `com.alipay.android.launcher.tab.TabBarManager` | `refreshTabBar()` | before | 按 `mTabId` 兜底再摘一次 |
| `com.alipay.android.phone.home.homegrid.HomeGridRecyclerAdapter` | `e(List, boolean)` | before | 净化宫格入参列表 |
| `com.alipay.android.phone.home.homegrid.HomeGridRecyclerAdapter` | `r(List)` | before | 兜底再净化 |
| `com.alipay.mobile.homefeeds.lg` | `a(boolean)` | before | 净化 MultiTab 标签列表 `h`，清空则连 `m` 一起清空 |
| `com.alipay.mobile.homefeeds.lg` | `a(boolean)` | after | 标签为空则收起整块信息流 |
| `dalvik.system.DexClassLoader` | 构造器 | after | 捕获 Quinox 动态 bundle 的 ClassLoader |
| `android.app.Application` | 构造器 | after | 兜底挂载 + 心跳日志 |

---

## 构建

```bash
cd hook
bash build.sh                    # 默认输出 out/AliClean-v1.0.apk
APKNAME=AliClean-v2.1.apk bash build.sh
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
cp out/AliClean-v2.1.apk /data/local/tmp/
chmod 644 /data/local/tmp/AliClean-v2.1.apk
pm install -r /data/local/tmp/AliClean-v2.1.apk
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

## 日志排查

支付宝是多进程应用，日志里会混入其它进程内容，建议按主进程 pid 过滤：

```bash
PID=$(pidof com.eg.android.AlipayGphone | awk '{print $1}')
logcat -d | awk -v p="$PID" '$3==p' | grep -a 'AliClean'
```

---

## 实现笔记

- Tab 的真实数据源是 `TabLauncherFragment.widgetGroups`（`List<IWidgetGroup>`）。`attachTab` 在循环里逐个 `new TabWidgetItemView / LifeTabWidgetItemView` 再 `tabHost.addTab`，在 `before` 里改列表可以一次性减 Tab，不会造成 `tabHost` 索引错乱。
- `TabBarManager.refreshTabBarInternal()` 会调用 `DynamicTabHelper.setTabsName(...)` 用服务端下发的名字覆盖本地文案（所以同一个 Tab ID 的显示名可能变成"视频"）。**按 ID 过滤比按文案过滤可靠。**
- 宫格真实数据 12 项，`e(List, boolean)` 输出 13 项（内部追加"更多"），再经分页补 `viewType=3844` 空位。过滤要放在 `e` 的**入参**上，放在返回值上会打乱分页与"更多"的位置。
- 首页信息流是 `tabhomefeeds.multitab` 的 MultiTab + ViewPager2 结构，由 `homefeeds.lg` 这个 `RecyclerView.Adapter` 驱动：`h` = `ArrayList<TabBarItemModel>`，`m` = 各页 feed 控制器，`f` = `MultiTabBarLayout`。
- 信息流的标签文案完全由服务端下发，在 APK 的 `strings` 里搜不到，只能按运行时 `title` 关键词匹配——这也是本项目用关键词而非硬编码列表的原因。

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
