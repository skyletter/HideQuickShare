# HideQuickShare

LSPosed 模块：把 Google「快速分享 / Quick Share」从系统分享面板里去掉。

适用于 **分享面板不由 `com.android.intentresolver` 实现**的 ROM（ColorOS / realme UI 等），这类面板跑在系统进程里，常见的 AOSP 版模块对它无效。

## 效果

分享面板第一格（图钉置顶）原本固定是「快速分享」，装完并重启后消失，其余分享目标不受影响。

## 为什么需要模块

| 手段 | 结果 |
|---|---|
| `pm disable com.google.android.gms/.nearby.sharing.main.MainActivity` | GMS 在 0.7 秒内调 `SetEnabledSetting(..., 1, ...)` 改回启用 |
| 改 GMS 的 `nearbysharing:service:state.xml` | 短暂有效，重启失效 |
| ColorOS 的 `nearby_sharing_component` / `gallery_pin_list` | 只影响置顶顺序，条目照样解析出来 |
| 守护脚本每 60 秒重新禁用 | 追不上 0.7 秒的自愈，图标闪烁 |

结论：别和 GMS 抢组件状态，直接在**系统解析分享目标**时把它过滤掉。

## 原理

实测（Android 17 / ColorOS 17）：

- 分享面板是 `android/com.android.internal.app.ResolverActivity`，运行在 **`system:ui`** 进程（不是 system_server，也不是 `com.android.intentresolver`）；
- **LSPosed 的 `android` 作用域只注入 system_server**，`system:ui` 不注入 —— 所以 hook `ResolverListController` 这类面板内代码的做法永远不触发；
- 面板无论怎么实现，都必须通过 binder 向 system_server 的 PackageManager 查询候选 Activity。

于是过滤点放在 system_server 的查询层，三条通道并联：

| 通道 | 类 / 方法 |
|---|---|
| 主 | `com.android.server.pm.ComputerEngine#queryIntentActivitiesInternal`（3 个重载） |
| 内部调用 | `com.android.server.pm.PackageManagerInternalBase#queryIntentActivities` |
| binder 兜底 | `com.android.server.pm.IPackageManagerBase#queryIntentActivity*`（`ParceledListSlice`） |
| 其他进程兜底 | `android.app.ApplicationPackageManager#queryIntentActivitiesAsUser` |

只处理 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`，且只剔除 `com.google.android.gms` 下类名含 `.nearby.sharing.` 的组件：

- 不影响「打开方式」等 VIEW 流程；
- 不修改 GMS 任何文件，账号 / Play 商店 / 定位 / 查找设备照常；
- 无守护进程、无轮询，重启后依旧生效。

## 兼容性

| 项目 | 实测值 |
|---|---|
| 机型 | PJD110（一加 / OPPO） |
| 系统 | ColorOS 17.0.0（Android 17 / API 37） |
| Root | KernelSU |
| 框架 | LSPosed 2.2.1 (7912) |
| GMS | 26.45.31 |

理论适用 Android 11+、分享面板继承 framework `ResolverActivity` 的 ROM。AOSP / Pixel 用 [CleanShare](https://github.com/hxreborn/CleanShare) 更合适（它针对 `com.android.intentresolver`）。

## 构建

```bash
# JDK 17 + Android SDK（platform 34 / build-tools 34）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

release 默认用 debug 签名，方便直接安装；正式分发请替换成自己的签名。

CI：推送到 `main` 或打 `v*` tag 会自动构建，tag 触发 Release（见 `.github/workflows/build.yml`）。

## 安装

```bash
adb install -r app-release.apk
```

1. LSPosed 管理器里启用「隐藏快速分享」；
2. 作用域**只勾「系统框架 / System Framework」**（`android`）；
3. **重启手机**（hook 在 system_server，不能热加载）。

## 验证

```bash
adb logcat | grep HideQuickShare        # 或看 LSPosed 日志
# 期望：
#   HideQuickShare: 进入 pkg=android proc=system_server ...
#   HideQuickShare: com.android.server.pm.ComputerEngine 挂上 3 个查询
#   HideQuickShare: ...queryIntentActivitiesInternal 移除快速分享

adb shell am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT test
```

## 卸载 / 回滚

LSPosed 里关掉模块 → 重启；或直接卸载 APK。都是内存 hook，无残留。

## 已知限制

- 过滤是全局的：任何 App 查询 `SEND` 目标都看不到快速分享（预期行为）。
- 每次打开分享面板会写几条 LSPosed 日志，暂未做节流。
- 系统 OTA 若改了 `ComputerEngine` 等类名，日志会报「找不到 …」，需要复核一次。
- 只对本机安装的 GMS 生效；多用户 / 工作资料需要各自启用。

## 相关项目

- [CleanShare](https://github.com/hxreborn/CleanShare)：AOSP `IntentResolver` 的分享面板清理（含 Quick Share 过滤），OEM 面板不适用。
- [HyperSelect](https://github.com/Yilink1/HyperSelect)：HyperOS 的分享 / 打开方式定制，同样在 `IPackageManagerBase` 层做过滤，可作为 API 102 写法的参考。

## 更新日志

- **v2.2**：新增 `resolveIntent` 通道（覆盖只取单个最佳匹配的调用方）；命中日志做节流（前 3 次 + 每 100 次）。
- **v2.1**：首个可用版本，在 system_server 的 PackageManager 查询层过滤。

## License

MIT
