---
name: cet6-apk
description: 构建、更新、交付「我的六级单词」安卓 App（我的六级单词.apk，源码在 Desktop\cet6-words\mywords-app）。Use whenever the user mentions 更新apk, 打包app, 构建apk, 交付apk, rebuild the vocab app, or wants any change to the CET-6 vocab Android app shipped to their phone — even if they just say "更新一下app" or "改完了打包".
---

# cet6-apk：六级单词 App 构建交付

一键脚本已封装全部流程与坑。**默认直接运行脚本**，只有失败时才读本文件排查。

## 快速构建

```bash
python "E:\skills\library\cet6-apk\scripts\build_apk.py"
```

脚本依次完成：同步网页（docs → assets/web）→ 同步 `mywords-app/app` → 构建副本 → gradle assembleDebug → 校验 → 拷贝 APK 到 `Desktop\cet6-words\我的六级单词.apk`。成功标准：输出 `BUILD SUCCESSFUL` 且 0 个 `e:` 行、校验三项全 True。

## 改词库 / 改界面 / 改代码后

1. 网页（词库、界面）只改 `mywords-app/docs/index.html` 与 `docs/data.js`；Kotlin 改 `app/src/main/java/com/wcx/mywords/*.kt`；
2. 跑上面的构建脚本即可——第 0 步自动把 docs/ 同步进 assets/web，Android Studio 构建则由 app/build.gradle.kts 的 syncWebDocs 任务负责。

## 环境与已知坑（排障时读）

- 构建链：JDK17 `C:\Users\wcx\android-build\jdk-17.0.20.1+1`，Gradle 8.7 `C:\Users\wcx\android-build\gradle-8.7`，Android SDK `C:\Users\wcx\android-build\android-sdk`（platform 34 / build-tools 34.0.0）。
- 构建副本 `C:\Users\wcx\android-build\MyWords` 与桌面源码**可能漂移**——永远以桌面 `mywords-app/app` 为准，脚本每次整目录覆盖。
- **AGP 拒绝中文路径**：必须在 ASCII 路径 `C:\Users\wcx\android-build\MyWords` 编译。
- **shell 可能是 Git Bash**：Windows 反斜杠路径会被吃，用正斜杠；`gradle.bat` 可直接调用。
- **虚拟内存提交不足会导致 daemon 崩溃**（症状：日志戛然而止/`daemon disappeared`/`hs_err_pid*.log`）。`gradle.properties` 已锁 `-Xmx2600m` + `--max-workers=2`；若仍 OOM，先 `taskkill //F //IM java.exe` 释放再重试。
- **诡异大量 unresolved reference 多为级联假错**（一处语法错让 import 区失效）：先 `grep -E "^e:" build_out.txt | head` 看第一条真实语法错，修它，别逐条追 unresolved。
- import 区历次升级易出**重复声明**（如 `fun SetScreen(@Composable\nfun SetScreen(`）——build 失败先 grep 这类双行。
- 静态括号检查：`python static_check.py`（字符级，含字符串内括号会误报，仅供参考）。
- 版本号在 `app/build.gradle.kts` 的 versionCode/versionName。

## 交付后

APK 在桌面 `cet6-words` 文件夹。发手机：ZCode 远控场景用「电脑 http.server + 手机浏览器下载」（见对话历史）或微信文件传输助手（USB 不在手时最稳）。
