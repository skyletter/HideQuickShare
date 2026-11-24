package com.eta.hidequickshare;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 从系统分享面板移除 Google「快速分享」。
 *
 * 实测（PJD110 / ColorOS 17 / API 37）：
 *   - 分享面板 ResolverActivity 跑在 android 包的 system:ui 进程；
 *   - LSPosed 只把 android 作用域注入 system_server，system:ui 不注入；
 *   - 所以过滤点放在 system_server 的 PackageManager 查询层：
 *     面板无论怎么实现，都必须通过 binder 向 system_server 查候选 Activity。
 *
 * 通道：
 *   1) ComputerEngine#queryIntentActivitiesInternal(List 返回)     主
 *   2) PackageManagerInternalBase#queryIntentActivities(List)
 *   3) IPackageManagerBase#queryIntentActivities(ParceledListSlice) binder 兜底
 *   4) ApplicationPackageManager#queryIntentActivitiesAsUser（若被注入到别的进程）
 *
 * 只在 action 为 SEND / SEND_MULTIPLE 时过滤，且只剔除 com.google.android.gms 的
 * .nearby.sharing.* 组件，不影响「打开方式」等其他流程。
 *
 * 本类运行在系统进程内，所有入口 catch(Throwable)，绝不外抛。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HideQuickShare";
    private static final String GMS_PKG = "com.google.android.gms";
    private static final String NEARBY_TOKEN = ".nearby.sharing.";

    /** 命中日志节流：前 3 次全记，之后每 100 次记一条 */
    private static final java.util.concurrent.atomic.AtomicInteger sHits =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean sInstalled = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) {
            return;
        }
        log("进入 pkg=" + lp.packageName + " proc=" + procName()
                + " pid=" + android.os.Process.myPid());
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        hookQueryMethods(lp.classLoader, "com.android.server.pm.ComputerEngine");
        hookQueryMethods(lp.classLoader, "com.android.server.pm.PackageManagerInternalBase");
        hookPmsBinder(lp.classLoader);
        hookResolveIntent(lp.classLoader);
        hookAppPm(lp.classLoader);
        hookResolver(lp.classLoader);
    }

    /** resolveIntent：某些调用方只取单个最佳匹配，不经过列表查询 */
    private void hookResolveIntent(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.pm.IPackageManagerBase", cl);
        if (c == null) {
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"resolveIntent".equals(m.getName())
                    || !ResolveInfo.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!isShareIntent(param.args.length > 0 ? param.args[0] : null)) {
                                return;
                            }
                            if (isQuickShare(param.getResult())) {
                                param.setResult(null);
                                countHit("resolveIntent 屏蔽");
                            }
                        } catch (Throwable t) {
                            log("resolveIntent 过滤异常: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook resolveIntent 失败: " + t);
            }
        }
        log("resolveIntent 挂上 " + n + " 个");
    }

    // ------------------------------------------------ system_server 查询引擎

    private void hookQueryMethods(ClassLoader cl, String className) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            log("找不到 " + className);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith("queryIntentActivities")) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            if (Modifier.isAbstract(m.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new QueryHook(className + "#" + m.getName()));
                n++;
            } catch (Throwable t) {
                log("hook " + className + "#" + m.getName() + " 失败: " + t);
            }
        }
        log(className + " 挂上 " + n + " 个查询");
    }

    // ------------------------------------------------ system_server binder 层

    private void hookPmsBinder(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.pm.IPackageManagerBase", cl);
        if (c == null) {
            log("找不到 IPackageManagerBase");
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith("queryIntentActivit")) {
                continue;
            }
            if (List.class.isAssignableFrom(m.getReturnType())) {
                continue; // 引擎层已处理
            }
            try {
                XposedBridge.hookMethod(m, new SliceHook(m.getName()));
                n++;
            } catch (Throwable t) {
                log("hook slice " + m.getName() + " 失败: " + t);
            }
        }
        log("binder 层挂上 " + n + " 个查询");
    }

    private static final class SliceHook extends XC_MethodHook {
        private final String mName;

        SliceHook(String name) {
            mName = name;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!isShareIntent(param.args.length > 0 ? param.args[0] : null)) {
                    return;
                }
                Object slice = param.getResult();
                if (slice == null) {
                    return;
                }
                Object raw = XposedHelpers.callMethod(slice, "getList");
                if (!(raw instanceof List)) {
                    return;
                }
                List<?> filtered = filterCopy((List<?>) raw);
                if (filtered == null) {
                    return;
                }
                Object newSlice = XposedHelpers.newInstance(slice.getClass(), filtered);
                param.setResult(newSlice);
                countHit("binder(" + mName + ") 重打包");
            } catch (Throwable t) {
                log("binder 过滤异常: " + t);
            }
        }
    }

    // ------------------------------------------------ 其他进程内的兜底通道

    private void hookAppPm(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManager", cl);
        if (c == null) {
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if (!"queryIntentActivitiesAsUser".equals(name)
                    && !"queryIntentActivityOptions".equals(name)) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new QueryHook("APM#" + name));
                n++;
            } catch (Throwable t) {
                log("hook APM " + name + " 失败: " + t);
            }
        }
        log("ApplicationPackageManager 挂上 " + n + " 个查询");
    }

    private void hookResolver(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.internal.app.ResolverListController", cl);
        if (c == null) {
            log("找不到 ResolverListController");
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"filterIneligibleActivities".equals(m.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new ControllerHook());
                n++;
            } catch (Throwable t) {
                log("hook Controller 失败: " + t);
            }
        }
        log("ResolverListController 挂上 " + n + " 个");
    }

    // ------------------------------------------------ 过滤逻辑

    private static final class QueryHook extends XC_MethodHook {
        private final String mName;

        QueryHook(String name) {
            mName = name;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!isShareIntent(param.args.length > 0 ? param.args[0] : null)) {
                    return;
                }
                if (!(param.getResult() instanceof List)) {
                    return;
                }
                List<?> filtered = filterCopy((List<?>) param.getResult());
                if (filtered != null) {
                    param.setResult(filtered);
                    countHit(mName);
                }
            } catch (Throwable t) {
                log(mName + " 过滤异常: " + t);
            }
        }
    }

    private static final class ControllerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            filterInPlace(param.args.length > 0 ? param.args[0] : null);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.getResult() instanceof List) {
                    List<?> filtered = filterCopy((List<?>) param.getResult());
                    if (filtered != null) {
                        param.setResult(filtered);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 返回过滤后的新列表；没有命中返回 null（避免无谓替换） */
    private static List<?> filterCopy(List<?> src) {
        int removed = 0;
        ArrayList<Object> out = new ArrayList<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            Object item = src.get(i);
            if (isQuickShare(item)) {
                removed++;
            } else {
                out.add(item);
            }
        }
        return removed > 0 ? out : null;
    }

    private static void filterInPlace(Object listObj) {
        if (!(listObj instanceof List)) {
            return;
        }
        List<?> list = (List<?>) listObj;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (isQuickShare(list.get(i))) {
                try {
                    list.remove(i);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isShareIntent(Object o) {
        if (!(o instanceof Intent)) {
            return false;
        }
        String action = ((Intent) o).getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    private static boolean isQuickShare(Object item) {
        if (item == null) {
            return false;
        }
        if (item instanceof ResolveInfo) {
            return matches(((ResolveInfo) item).activityInfo);
        }
        try {
            Object ri = XposedHelpers.callMethod(item, "getResolveInfoAt", 0);
            if (ri instanceof ResolveInfo) {
                return matches(((ResolveInfo) ri).activityInfo);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean matches(ActivityInfo ai) {
        if (ai == null || ai.packageName == null || ai.name == null) {
            return false;
        }
        return GMS_PKG.equals(ai.packageName) && ai.name.contains(NEARBY_TOKEN);
    }

    private static String procName() {
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[128];
            int len = in.read(buf);
            in.close();
            if (len > 0) {
                int end = 0;
                while (end < len && buf[end] != 0) {
                    end++;
                }
                return new String(buf, 0, end, "UTF-8");
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** 命中日志节流，避免每次打开面板刷屏 */
    private static void countHit(String what) {
        int n = sHits.incrementAndGet();
        if (n <= 3 || n % 100 == 0) {
            log(what + " 移除快速分享（累计 " + n + "）");
        }
    }
}
