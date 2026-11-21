# 我的六级单词

六级（CET-6）备考背单词应用：**安卓离线版 + 网页版**同源，6081 词全例句带中文翻译，四种学习模式 + 智能递进，FSRS-6 间隔重复调度。

> 目标 600 分 · 从 345 分起步的全程备考系统 · 考期 2027 年 6 月

---

## 📲 下载安装（安卓）

[**下载最新版 v3.6.3**](https://github.com/wcx1208/cet6-words/releases/latest) · Android 8.0+ · 约 41 MB

| 版本 | 界面 | 说明 |
|---|---|---|
| **v3.6.3**（推荐） | 与网页版完全同一套 | 稳定性与性能修复：并发竞态/计数原子化/资源泄漏、壁图拆存与滑杆省流、Fisher-Yates 均匀洗牌、词表滚动减负 |
| 更早版本 | v3.6.2 / v3.6.1 / v3.6 / v3.5 / v3.4 / v3.0 / v2.9 均保留在 Releases |

三版同包名，覆盖安装即可；v3.0 起会把 v2.9 的学习进度与打卡记录自动迁移过来。

### 近期主要更新
- **翻译特训**：30 句六级高频话题训练句（六大话题筛选，中译英自评三档，进度持久化）
- **写作句型库**：五类功能句型 26 条 + 高分词替换 15 组
- **英文释义**：按中文释义词性对齐，错位义项已剔除
- **例句离线发音包**：设置页一键下载，6079 句全部离线可听

**完全离线**：6081 词、6090 条真人美音、界面资源全部内置，飞行模式下照常使用。

## 🌐 网页版

**https://wcx1208.github.io/cet6-words/**

源码在 `docs/` 目录（GitHub Pages）。手机浏览器可"添加到主屏幕"当 App 用，进度存浏览器本地。

安卓版的界面就是这份 `docs/index.html` 的原样内嵌（见 `app/src/main/assets/web/`）：`docs/` 是唯一改动源，Gradle 构建期自动同步进 assets（`syncWebDocs` 任务），命令行构建脚本也会先同步再打包，两端永远一致。

## ✨ 功能

### 词库

- **6081 词**：手册精选 + 不背单词 CET6 词书 + ECDICT 考纲查漏（多源去重合并）
- **例句 100% 覆盖**：真题例句优先，其余来自 Tatoeba 开放语料；例句均配中文翻译
- **4 级难度**：核心 2228 / 基础 2208 / 拓展 1557 / 进阶 88（BNC 词频 + 考纲标签客观定档）
- 每条含：音标 · 中英释义 · 例句+翻译 · 易混词辨析（形近/近义）· 词根记忆法 · 搭配

### 学习

- **四种模式**：卡片认词 / 释义选择 / 拼写默写 / 听写
- **FSRS-6**：21 参数幂律遗忘曲线，精确维护记忆稳定性 S 与难度 D（[py-fsrs](https://github.com/open-spaced-repetition/py-fsrs) 移植）
- **不限量学习**：不设每日新词上限，随时学随时停
- 错词本 · 目标环 · 连续打卡 · 12 周热力图 · 跨天不断学 · 撤销栈 · 进度导入导出

### 发音

单词：**内置真人美音（6090 条）→ 在线有道 → 百度 TTS**
例句：**百度神经语音**，听过的句子自动缓存，整包可在设置页一键下载（6079 句全部离线可听）

安卓版通过 JS Bridge 直接播放 APK 内的 mp3，网页版在线播放。

### 个性化

- 壁纸：6 套渐变 + 相册自定义（自动压缩存储），壁纸强度 / 界面不透明双滑杆
- 177 条英文激励名言
- 深色模式（跟随系统 / 手动切换），安卓版状态栏同步变色

## 🗂 项目结构

```
app/src/main/java/com/wcx/mywords/
└── MainActivity.kt    WebView 外壳：加载 assets/web、发音桥接、进度迁移

app/src/main/assets/
├── web/index.html     与 docs/index.html 同一份界面（构建期自动同步）
├── web/data.js        6081 词条数据
└── audio/us/          6090 条真人美音 mp3

docs/                  网页版（GitHub Pages）
legacy-compose/        v2.9 及更早的原生 Compose 界面源码（不参与编译）
```

## 🔨 构建

用 Android Studio 打开本目录（AGP 8.4.2 / Kotlin 1.9.24 / compileSdk 34 / JDK 17），或命令行：

```
gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 项目路径不能含中文（AGP 限制）。

## 📚 数据来源

| 来源 | 用途 | 许可 |
|---|---|---|
| [kajweb/dict](https://github.com/kajweb/dict) | 不背单词 CET6 词书（释义/例句/记忆法） | — |
| [ECDICT](https://github.com/skywind3000/ECDICT) | 音标 · 柯林斯星级 · BNC 词频 · 考纲标签 | MIT |
| [Tatoeba](https://tatoeba.org) | 例句补充 | CC-BY |
| [py-fsrs](https://github.com/open-spaced-repetition/py-fsrs) | 复习算法参考 | MIT |

## 📄 备考配套打印资料

同系列（本地文件，不在本仓库）：《六级核心词汇全程手册》《六级345到600全程作战手册》
