package com.solab.alipayclean;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
 * 支付宝首页净化 v2.1
 * 目标：
 *  1) 底部 Tab 去掉「理财」(20001688) 与「视频」(20002065，LivingConstants.APPID)
 *  2) 首页宫格去掉「闪购极速版」(appId=20000120) 等电商推广入口
 *  3) 首页信息流 MultiTab 去掉「为你推荐」等推荐标签页
 * 保留：首页/消息/我的 三个 Tab 与其余常规功能
 */
public class HookEntry implements IXposedHookLoadPackage {

    static final String TAG = "[AliClean]";
    static final String PKG = "com.eg.android.AlipayGphone";

    /** 要移除的 Tab ID */
    static final Set<String> DROP_TAB_IDS = new HashSet<String>();
    /** 要移除的宫格 appId */
    static final Set<String> DROP_APP_IDS = new HashSet<String>();
    /** 要移除的宫格名称关键词 */
    static final String[] DROP_APP_KEYS = new String[]{"闪购", "秒杀", "限时秒", "特卖", "今日爆款"};
    /** 要移除的 MultiTab 标签标题关键词 */
    static final String[] DROP_TAB_TITLES = new String[]{"为你推荐", "推荐", "闪购", "秒杀"};

    static {
        DROP_TAB_IDS.add("20001688"); // 理财 / WEALTH
        DROP_TAB_IDS.add("20002065"); // 视频 / LIFE / LivingConstants.APPID
        DROP_APP_IDS.add("20000120"); // 闪购极速版
    }

    private static final Set<ClassLoader> LOADERS = new HashSet<ClassLoader>();
    private static final Set<String> HOOKED = new HashSet<String>();
    private static final Set<Object> DUMPED = new HashSet<Object>();

    static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        log("=== AliClean v2.1 attached, pkg=" + lpparam.packageName + " ===");
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
            Class<?> adapter = Class.forName("com.alipay.android.phone.home.homegrid.HomeGridRecyclerAdapter", false, cl);
            if (adapter != null && HOOKED.add("grid")) {
                hookGrid(adapter);
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> lg = Class.forName("com.alipay.mobile.homefeeds.lg", false, cl);
            if (lg != null && HOOKED.add("feeds")) {
                hookFeeds(lg);
            }
        } catch (Throwable ignored) {
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

        // 保险：TabBarManager 刷新前再从视图列表剔除一次
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

    /** 从 widgetGroups 里摘掉目标 Tab */
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

    /** 从 tabWidgetItemViewList 里摘掉目标 Tab 视图 */
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

    static void hookGrid(Class<?> adapter) {
        log("hook HomeGridRecyclerAdapter OK");

        // e(List, boolean)：原始列表 -> 布局列表，入口处先净化入参
        try {
            XposedHelpers.findAndHookMethod(adapter, "e", List.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object raw = param.args[0];
                        if (raw instanceof List) {
                            filterGridItems((List<?>) raw, "e-raw");
                        }
                    } catch (Throwable t) {
                        log("grid e filter err " + t);
                    }
                }
            });
            log("e(List,boolean) hooked");
        } catch (Throwable t) {
            log("hook e fail: " + t);
        }

        // r(List)：rebuildPagerData，兜底再净化一次
        try {
            XposedHelpers.findAndHookMethod(adapter, "r", List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object raw = param.args[0];
                        if (raw instanceof List) {
                            filterGridItems((List<?>) raw, "r-raw");
                        }
                    } catch (Throwable t) {
                        log("grid r filter err " + t);
                    }
                }
            });
            log("r(List) hooked");
        } catch (Throwable t) {
            log("hook r fail: " + t);
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

    static void hookFeeds(final Class<?> lgCls) {
        log("hook MultiTabHomeAdapter(lg) OK");
        try {
            XposedHelpers.findAndHookMethod(lgCls, "a", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object listObj = XposedHelpers.getObjectField(param.thisObject, "h");
                        if (listObj instanceof List) {
                            boolean empty = filterMultiTabs((List<?>) listObj, "lg.a-before");
                            if (empty) {
                                Object subObj = XposedHelpers.getObjectField(param.thisObject, "m");
                                if (subObj instanceof List) {
                                    int before = ((List<?>) subObj).size();
                                    ((List<?>) subObj).clear();
                                    if (before > 0) {
                                        log("[lg.a-before] cleared sub pages " + before + " -> 0");
                                    }
                                }
                            }
                        } else {
                            log("[lg.a] h is " + (listObj == null ? "null" : listObj.getClass().getName()));
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
                            dumpMultiTabs((List<?>) listObj, "lg.a-after");
                        }
                        Object barObj = XposedHelpers.getObjectField(param.thisObject, "f");
                        if (barObj instanceof ViewGroup && !DUMPED.contains(barObj)) {
                            DUMPED.add(barObj);
                            log("[bar] childCount=" + ((ViewGroup) barObj).getChildCount()
                                    + " visibility=" + ((View) barObj).getVisibility());
                        }
                        collapseIfEmpty(param.thisObject);
                    } catch (Throwable t) {
                        log("feeds after err " + t);
                    }
                }
            });
            log("lg.a(boolean) hooked");
        } catch (Throwable t) {
            log("hook lg.a fail: " + t);
        }

        // m 列表 = 各标签页内容（feed 页），同步过滤
        try {
            XposedHelpers.findAndHookMethod(lgCls, "b", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        log("[lg.b] " + param.args[0] + " -> " + param.getResult());
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** MultiTab 为空时把整块信息流收起（高度 0 + GONE） */
    static void collapseIfEmpty(Object lgObj) {
        try {
            Object hObj = XposedHelpers.getObjectField(lgObj, "h");
            boolean empty = !(hObj instanceof List) || ((List<?>) hObj).isEmpty();
            Object holder = XposedHelpers.getObjectField(lgObj, "c");
            if (holder == null) {
                return;
            }
            Object itemViewObj = XposedHelpers.getObjectField(holder, "itemView");
            if (!(itemViewObj instanceof View)) {
                return;
            }
            View itemView = (View) itemViewObj;
            if (itemView == null) {
                return;
            }
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