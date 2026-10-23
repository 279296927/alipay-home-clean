package com.solab.alipayclean;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 支付宝首页净化 v3.0
 *
 * v3.0 相对 v2.1 的变化：
 *   - 支付宝升级到 12.12.36.8000 后内部类与方法大量重命名，v2.1 的三个 hook 全部 NoSuchMethodError。
 *   - 宫格：HomeGridRecyclerAdapter.e(List,boolean) -> c(List,boolean)；r(List) -> n(List) / q(List)
 *   - 信息流：com.alipay.mobile.homefeeds.lg -> rg（lg 在新版变成了一个无关的 synthetic Runnable）
 *   - 新增动态签名探测：方法名变了也能按"参数表 + 返回类型"在目标类里自动找到入口，
 *     不必每升级一次就改一遍源码。
 *
 * 目标：
 *  1) 底部 Tab 去掉「理财」(20001688) 与「视频」(20002065，LivingConstants.APPID)
 *  2) 首页宫格去掉「闪购极速版」(appId=20000120) 等电商推广入口
 *  3) 首页信息流 MultiTab 去掉「为你推荐」等推荐标签页，并收起整块
 */
public class HookEntry implements IXposedHookLoadPackage {

    static final String TAG = "[AliClean]";
    static final String PKG = "com.eg.android.AlipayGphone";

    /** 要移除的 Tab ID */
    static final Set<String> DROP_TAB_IDS = new HashSet<String>();
    /** 要移除的宫格 appId */
    static final Set<String> DROP_APP_IDS = new HashSet<String>();
    /** 宫格名称关键词 */
    static final String[] DROP_APP_KEYS = new String[]{"闪购", "秒杀", "限时秒", "特卖", "今日爆款"};
    /** MultiTab 标签标题关键词 */
    static final String[] DROP_TAB_TITLES = new String[]{"为你推荐", "推荐", "闪购", "秒杀"};

    /** 宫格适配器类名（各版本一致） */
    static final String GRID_CLASS = "com.alipay.android.phone.home.homegrid.HomeGridRecyclerAdapter";
    /** 信息流适配器候选类名：新版本在前，旧版本在后 */
    static final String[] FEEDS_CLASSES = new String[]{
            "com.alipay.mobile.homefeeds.rg",   // 12.12.36+
            "com.alipay.mobile.homefeeds.lg",   // 12.12.30
    };

    static {
        DROP_TAB_IDS.add("20001688"); // 理财 / WEALTH
        DROP_TAB_IDS.add("20002065"); // 视频 / LIFE / LivingConstants.APPID
        DROP_APP_IDS.add("20000120"); // 闪购极速版
    }

    private static final Set<ClassLoader> LOADERS = new HashSet<ClassLoader>();
    private static final Set<String> HOOKED = new HashSet<String>();

    static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        log("=== AliClean v3.0 attached, pkg=" + lpparam.packageName + " ===");
        tryHookLoader(lpparam.classLoader);

        try {
            Class<?> dcl = Class.forName("dalvik.system.DexClassLoader");
            XposedBridge.hookAllConstructors(dcl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object o = param.thisObject;
                    if (o instanceof ClassLoader) {
                        tryHookLoader((ClassLoader) o);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook DexClassLoader fail: " + t);
        }

        try {
            XposedBridge.hookAllConstructors(Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Application app = (Application) param.thisObject;
                    tryHookLoader(app.getClassLoader());
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            log("[heartbeat] loaders=" + LOADERS.size() + " hooked=" + HOOKED);
                        }
                    }, 15000);
                }
            });
        } catch (Throwable t) {
            log("hook Application fail: " + t);
        }
    }

    static synchronized void tryHookLoader(ClassLoader cl) {
        if (cl == null || LOADERS.contains(cl)) {
            return;
        }
        LOADERS.add(cl);

        try {
            Class<?> frag = Class.forName("com.alipay.android.launcher.TabLauncherFragment", false, cl);
            if (frag != null && HOOKED.add("frag")) {
                hookTabFragment(frag);
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> adapter = Class.forName(GRID_CLASS, false, cl);
            if (adapter != null && HOOKED.add("grid")) {
                hookGrid(adapter);
            }
        } catch (Throwable ignored) {
        }

        for (String name : FEEDS_CLASSES) {
            if (HOOKED.contains("feeds")) {
                break;
            }
            try {
                Class<?> feeds = Class.forName(name, false, cl);
                if (feeds != null && hasField(feeds, "h") && hasField(feeds, "f")) {
                    HOOKED.add("feeds");
                    log("feeds class resolved: " + name);
                    hookFeeds(feeds);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= Tab 净化 =================

    static void hookTabFragment(final Class<?> frag) {
        log("hook TabLauncherFragment OK");
        try {
            XposedHelpers.findAndHookMethod(frag, "attachTab", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object listObj = XposedHelpers.getObjectField(param.thisObject, "widgetGroups");
                        if (listObj instanceof List) {
                            filterWidgetGroups((List<?>) listObj, "attachTab-before");
                        }
                    } catch (Throwable t) {
                        log("attachTab filter err " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        dumpTabs(param.thisObject, "attachTab-after");
                    } catch (Throwable t) {
                        log("dumpTabs err " + t);
                    }
                }
            });
            log("attachTab hooked");
        } catch (Throwable t) {
            log("hook attachTab fail: " + t);
        }

        try {
            final Class<?> mgrCls = frag.getClassLoader().loadClass("com.alipay.android.launcher.tab.TabBarManager");
            XposedHelpers.findAndHookMethod(mgrCls, "refreshTabBar", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object vm = XposedHelpers.getObjectField(param.thisObject, "tabBarViewModel");
                        if (vm == null) {
                            return;
                        }
                        Object listObj = XposedHelpers.getObjectField(vm, "tabWidgetItemViewList");
                        if (listObj instanceof List) {
                            filterTabItemViews((List<?>) listObj, "refreshTabBar-before");
                        }
                    } catch (Throwable t) {
                        log("refreshTabBar filter err " + t);
                    }
                }
            });
            log("refreshTabBar hooked");
        } catch (Throwable t) {
            log("hook refreshTabBar fail: " + t);
        }
    }

    static void filterWidgetGroups(List<?> list, String from) {
        Iterator<?> it = list.iterator();
        int removed = 0;
        while (it.hasNext()) {
            Object g = it.next();
            if (g == null) {
                continue;
            }
            String id = null;
            try {
                Object r = XposedHelpers.callMethod(g, "getId");
                if (r != null) {
                    id = r.toString();
                }
            } catch (Throwable ignored) {
            }
            if (id != null && DROP_TAB_IDS.contains(id)) {
                it.remove();
                removed++;
                log("[" + from + "] drop widgetGroup id=" + id);
            }
        }
        if (removed > 0) {
            log("[" + from + "] widgetGroups now " + list.size());
        }
    }

    static void filterTabItemViews(List<?> list, String from) {
        Iterator<?> it = list.iterator();
        int removed = 0;
        while (it.hasNext()) {
            Object v = it.next();
            if (v == null) {
                continue;
            }
            String id = null;
            try {
                id = (String) XposedHelpers.getObjectField(v, "mTabId");
            } catch (Throwable ignored) {
            }
            if (id != null && DROP_TAB_IDS.contains(id)) {
                it.remove();
                removed++;
                log("[" + from + "] drop tabView id=" + id);
            }
        }
        if (removed > 0) {
            log("[" + from + "] tabViews now " + list.size());
        }
    }

    static void dumpTabs(Object fragment, String from) {
        try {
            Object vm = XposedHelpers.getObjectField(fragment, "tabBarViewModel");
            if (vm == null) {
                return;
            }
            Object listObj = XposedHelpers.getObjectField(vm, "tabWidgetItemViewList");
            if (!(listObj instanceof List)) {
                return;
            }
            List<?> list = (List<?>) listObj;
            StringBuilder sb = new StringBuilder("[" + from + "] tabs=" + list.size() + " :: ");
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item == null) {
                    continue;
                }
                String id = null;
                String text = null;
                try {
                    id = (String) XposedHelpers.getObjectField(item, "mTabId");
                } catch (Throwable ignored) {
                }
                try {
                    Object tv = XposedHelpers.getObjectField(item, "mTextView");
                    if (tv instanceof TextView) {
                        CharSequence cs = ((TextView) tv).getText();
                        text = cs == null ? "" : cs.toString();
                    }
                } catch (Throwable ignored) {
                }
                sb.append(i).append(":{id=").append(id).append(",text=").append(text).append("} ");
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpTabs fail " + t);
        }
    }

    // ================= 首页宫格净化 =================

    /**
     * 动态定位宫格入口：所有 (List, boolean) -> List 的方法都挂上。
     * 12.12.30 是 e(List,boolean)，12.12.36 是 c(List,boolean)。
     * 另外再挂 (List) -> void 的 rebuild / refresh 入口作兜底（n/q）。
     */
    static void hookGrid(Class<?> adapter) {
        log("hook HomeGridRecyclerAdapter OK");
        int n = 0;

        for (Method m : adapter.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2) {
                continue;
            }
            if (!List.class.isAssignableFrom(ps[0]) || ps[1] != boolean.class) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object raw = param.args[0];
                            if (raw instanceof List) {
                                filterGridItems((List<?>) raw, "grid-transform-raw");
                            }
                        } catch (Throwable t) {
                            log("grid filter err " + t);
                        }
                    }
                });
                n++;
                log("grid transform hooked -> " + m.getName() + "(List,boolean):List");
            } catch (Throwable t) {
                log("hook grid method fail " + m.getName() + " : " + t);
            }
        }

        for (Method m : adapter.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1) {
                continue;
            }
            if (!List.class.isAssignableFrom(ps[0]) || m.getReturnType() != void.class) {
                continue;
            }
            final String name = m.getName();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object raw = param.args[0];
                            if (raw instanceof List) {
                                filterGridItems((List<?>) raw, "grid-rebuild:" + name);
                            }
                        } catch (Throwable t) {
                            log("grid rebuild filter err " + t);
                        }
                    }
                });
                n++;
                log("grid rebuild hooked -> " + name + "(List)");
            } catch (Throwable t) {
                log("hook grid rebuild fail " + name + " : " + t);
            }
        }

        if (n == 0) {
            log("!! grid: no matching method, signatures are:");
            for (Method m : adapter.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("   ").append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("):").append(m.getReturnType().getSimpleName());
                log(sb.toString());
            }
        }
    }

    static void filterGridItems(List<?> list, String from) {
        int removed = 0;
        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object o = it.next();
            if (o == null) {
                continue;
            }
            String appId = null;
            String appName = null;
            try {
                appId = (String) XposedHelpers.getObjectField(o, "appId");
            } catch (Throwable ignored) {
            }
            try {
                appName = (String) XposedHelpers.getObjectField(o, "appName");
            } catch (Throwable ignored) {
            }
            if (appId == null) {
                try {
                    Object r = XposedHelpers.callMethod(o, "getAppId");
                    if (r != null) {
                        appId = r.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (appName == null) {
                try {
                    Object r = XposedHelpers.callMethod(o, "getAppName");
                    if (r != null) {
                        appName = r.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean drop = appId != null && DROP_APP_IDS.contains(appId);
            if (!drop && appName != null) {
                for (String k : DROP_APP_KEYS) {
                    if (appName.contains(k)) {
                        drop = true;
                        break;
                    }
                }
            }
            if (drop) {
                it.remove();
                removed++;
                log("[" + from + "] drop grid id=" + appId + " name=" + appName);
            }
        }
        if (removed > 0) {
            log("[" + from + "] grid now " + list.size());
        }
    }

    // ================= 首页信息流 MultiTab 净化 =================

    static void hookFeeds(final Class<?> feedsCls) {
        log("hook MultiTabHomeAdapter(" + feedsCls.getSimpleName() + ") OK");
        int n = 0;

        for (Method m : feedsCls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != boolean.class || m.getReturnType() != void.class) {
                continue;
            }
            final String name = m.getName();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object listObj = XposedHelpers.getObjectField(param.thisObject, "h");
                            if (listObj instanceof List) {
                                boolean empty = filterMultiTabs((List<?>) listObj, "feeds-before:" + name);
                                if (empty) {
                                    Object subObj = XposedHelpers.getObjectField(param.thisObject, "m");
                                    if (subObj instanceof List) {
                                        int before = ((List<?>) subObj).size();
                                        ((List<?>) subObj).clear();
                                        if (before > 0) {
                                            log("[feeds-before] cleared sub pages " + before + " -> 0");
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            log("feeds filter err " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object listObj = XposedHelpers.getObjectField(param.thisObject, "h");
                            if (listObj instanceof List) {
                                dumpMultiTabs((List<?>) listObj, "feeds-after:" + name);
                            }
                            collapseIfEmpty(param.thisObject);
                        } catch (Throwable t) {
                            log("feeds after err " + t);
                        }
                    }
                });
                n++;
                log("feeds bind hooked -> " + name + "(boolean)");
            } catch (Throwable t) {
                log("hook feeds method fail " + name + " : " + t);
            }
        }

        if (n == 0) {
            log("!! feeds: no boolean(void) entry, signatures are:");
            for (Method m : feedsCls.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("   ").append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("):").append(m.getReturnType().getSimpleName());
                log(sb.toString());
            }
        }
    }

    static boolean hasField(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void collapseIfEmpty(Object feedsObj) {
        try {
            Object hObj = XposedHelpers.getObjectField(feedsObj, "h");
            boolean empty = !(hObj instanceof List) || ((List<?>) hObj).isEmpty();
            Object holder = XposedHelpers.getObjectField(feedsObj, "c");
            if (holder == null) {
                return;
            }
            Object itemViewObj = XposedHelpers.getObjectField(holder, "itemView");
            if (!(itemViewObj instanceof View)) {
                return;
            }
            View itemView = (View) itemViewObj;
            if (empty) {
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    itemView.setLayoutParams(lp);
                }
                if (itemView.getVisibility() != View.GONE) {
                    itemView.setVisibility(View.GONE);
                    log("[collapse] feed block GONE (multiTabs empty)");
                }
            }
        } catch (Throwable t) {
            log("collapse err " + t);
        }
    }

    static boolean filterMultiTabs(List<?> list, String from) {
        int removed = 0;
        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object m = it.next();
            if (m == null) {
                continue;
            }
            String title = null;
            String itemId = null;
            try {
                title = (String) XposedHelpers.getObjectField(m, "title");
            } catch (Throwable ignored) {
            }
            try {
                itemId = (String) XposedHelpers.getObjectField(m, "itemId");
            } catch (Throwable ignored) {
            }
            boolean drop = false;
            if (title != null) {
                for (String k : DROP_TAB_TITLES) {
                    if (title.contains(k)) {
                        drop = true;
                        break;
                    }
                }
            }
            if (drop) {
                it.remove();
                removed++;
                log("[" + from + "] drop multitab title=" + title + " itemId=" + itemId);
            }
        }
        if (removed > 0) {
            log("[" + from + "] multitabs now " + list.size());
        }
        return list.isEmpty();
    }

    static void dumpMultiTabs(List<?> list, String from) {
        StringBuilder sb = new StringBuilder("[" + from + "] multitabs=" + list.size() + " :: ");
        for (int i = 0; i < list.size() && i < 20; i++) {
            Object m = list.get(i);
            if (m == null) {
                continue;
            }
            String title = null;
            String itemId = null;
            try {
                title = (String) XposedHelpers.getObjectField(m, "title");
            } catch (Throwable ignored) {
            }
            try {
                itemId = (String) XposedHelpers.getObjectField(m, "itemId");
            } catch (Throwable ignored) {
            }
            sb.append(i).append(":{id=").append(itemId).append(",title=").append(title).append("} ");
        }
        log(sb.toString());
    }
}