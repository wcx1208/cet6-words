# -*- coding: utf-8 -*-
"""cet6-apk 一键构建交付：同步网页 -> 同步源码 -> gradle 构建 -> 拷贝 APK 到桌面"""
import io, os, shutil, subprocess, sys, re

SRC = r"C:\Users\wcx\Desktop\cet6-words\mywords-app\app"
DOCS = r"C:\Users\wcx\Desktop\cet6-words\mywords-app\docs"
ROOT = r"C:\Users\wcx\android-build\MyWords"
DST = r"C:\Users\wcx\Desktop\cet6-words\我的六级单词.apk"
JDK = r"C:\Users\wcx\android-build\jdk-17.0.20.1+1"

def main():
    # 0) 网页同步：docs/ 是唯一改动源，先刷进 assets/web 再打包（命令行构建路径的同步保障；
    #    Android Studio 构建由 app/build.gradle.kts 的 syncWebDocs 任务负责）
    for fn in ("index.html", "data.js"):
        shutil.copyfile(os.path.join(DOCS, fn),
                        os.path.join(SRC, "src", "main", "assets", "web", fn))
    print("[0/4] web synced (docs -> assets/web)")

    # 1) 同步（整目录覆盖，桌面源码为准）
    dst_app = os.path.join(ROOT, "app")
    if os.path.exists(dst_app): shutil.rmtree(dst_app)
    shutil.copytree(SRC, dst_app,
                    ignore=shutil.ignore_patterns("build", ".gradle"))
    print("[1/4] synced")

    # 2) 构建
    env = dict(os.environ,
               JAVA_HOME=r"C:\Users\wcx\android-build\jdk-17.0.20.1+1",
               PATH=r"C:\Users\wcx\android-build\jdk-17.0.20.1+1\bin"
                    r";C:\Users\wcx\android-build\gradle-8.7\bin;" + os.environ["PATH"])
    r = subprocess.run([r"C:\Users\wcx\android-build\gradle-8.7\bin\gradle.bat",
                        ":app:assembleDebug", "--console=plain", "--max-workers=2"],
                       cwd=ROOT, env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    errs = [l for l in (r.stdout + r.stderr).splitlines() if l.startswith("e:")]
    ok = "BUILD SUCCESSFUL" in (r.stdout + r.stderr)
    print(f"[2/4] build {'OK' if ok else 'FAILED'}")
    for e in errs[:8]: print(e)

    # 3) 交付
    apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if ok and os.path.exists(apk):
        shutil.copyfile(apk, DST)
        print(f"[3/4] delivered -> {DST} ({os.path.getsize(DST)} bytes)")
    else:
        print("[3/4] no APK"); sys.exit(1)

    # 4) 校验（WebView 单页架构：校验 assets/web 与内置音频）
    import zipfile
    z = zipfile.ZipFile(DST)
    names = z.namelist()
    checks = {"index.html": "assets/web/index.html" in names,
              "data.js": "assets/web/data.js" in names,
              "audio>=6000": sum(1 for n in names if n.startswith("assets/audio/")) >= 6000}
    print("[4/4] verify:", checks)
    if not all(checks.values()): sys.exit(1)

if __name__ == "__main__":
    main()
