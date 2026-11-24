# 设备实测记录

测试机：PJD110 / ColorOS 17.0.0 (PJD110_17.0.0.100(CN01)) / Android 17 (API 37) / arm64-v8a / KernelSU / LSPosed 2.2.1 (7912) / GMS 26.45.31。

## 1. 分享面板宿主进程

```bash
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT test
adb shell dumpsys window | grep mCurrentFocus
# mCurrentFocus=Window{... u0 android/com.android.internal.app.ResolverActivity}

adb shell dumpsys activity processes | grep -A2 ResolverActivity
# ProcessRecord{... 26505:system:ui/1000}  ← 宿主是 system:ui，不是 system_server
```

`framework-res` 的清单里 `<application android:process="system">`，OEM 可以再改；ColorOS 17 把它放在了 `system:ui`。

## 2. LSPosed 注入范围

给模块加 scope `android` + `system`，重启 framework 后：

```
(system)[com.eta.hidequickshare,...] HideQuickShare: 进入 pkg=android proc=system_server pid=6263
```

只有 system_server 有日志，`system:ui` 一条都没有 —— 说明面板进程拿不到模块，面板内 hook 全部无效。

## 3. 目标组件

```bash
adb shell cmd package query-activities -a android.intent.action.SEND -t text/plain | grep -B3 com.google.android.gms
# name=com.google.android.gms.nearby.sharing.main.MainActivity
# packageName=com.google.android.gms  processName=com.google.android.gms.ui
# 接收 SEND / SEND_MULTIPLE / com.google.android.gms.SHARE_NEARBY
```

## 4. 最终 hook 点（system_server）

```bash
# services.jar 解包后 dexdump：
com.android.server.pm.ComputerEngine#queryIntentActivitiesInternal (List)
com.android.server.pm.PackageManagerInternalBase#queryIntentActivities (List)
com.android.server.pm.IPackageManagerBase#queryIntentActivities (ParceledListSlice)
```

命中日志：

```
HideQuickShare: com.android.server.pm.ComputerEngine#queryIntentActivitiesInternal 移除快速分享
```

## 5. 其他

- 无线调试端口重启后会变；调试期间可用 `adb tcpip 5555` 撑过 framework 重启，记得收尾 `setprop service.adb.tcp.port -1; stop adbd; start adbd`。
- LSPosed 配置库：`/data/adb/lspd/config/modules_config.db`（`modules` / `modules_state` / `scope` 三张表），可直接写入后重启 lspd + framework 生效。
