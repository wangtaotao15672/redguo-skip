# 三种打包方式

最终交付在 `redguo-skip/` 目录。要打成可安装的 APK,有三种方式:

## 路径 A:GitHub Actions 云编译(推荐,5 分钟出 APK)

如果你有 GitHub 账号,这步完全不需要 Android Studio。

1. 在 [github.com/new](https://github.com/new) 新建一个 **空仓库**,名字随便,例如 `redguo-skip`
2. 本地把项目源码 push 上去:
   ```bash
   cd redguo-skip
   git init && git add -A && git commit -m "init"
   git remote add origin git@github.com:<你的用户名>/redguo-skip.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库页面 → Actions → 看到 "Build APK" workflow
4. 第一次进去时点击 **"Run workflow"** 按钮
5. 等待 3-5 分钟,在 Artifacts 区下载 `redguo-skip-debug` zip
6. 解压得到 `app-debug.apk`,传到手机点击安装即可

Actions 已经预下载好 Linux 上的 JDK 17 + Android SDK + Gradle,完全在你工作环境之外跑,**不受公司网络限制**。

## 路径 B:用现成的 Android Studio

需要你随便一台能 Google 的机器(家里电脑、公司另一个网络、个人 PC)。

1. 装 [Android Studio](https://developer.android.com/studio)
2. 打开工程,等 Gradle sync 完成(首次会装 SDK 34)
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 在 `app/build/outputs/apk/debug/app-debug.apk` 找到产物

## 路径 C:命令行直接打

在能联网的机器上:

```bash
cd redguo-skip
# 需要 JDK 17
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

需要环境:
- JDK 17 或更新
- `ANDROID_HOME` 指向一个装好 platform 34 + build-tools 34 的 Android SDK

---

# 安装 APK 后使用步骤

1. 在手机上打开 App(可能要在"未知来源"里允许安装)
2. 第一次会提示"前往开启无障碍服务"
3. 跳到系统无障碍设置 → 找到 "红果广告自动跳过" → 启用
4. 回 App 顶部变 "运行中" 即生效
5. 打开红果短剧,广告结束后会自动上滑到下一集

如果想看效果是不是真的在自动上滑,可以打开红果短剧,不退出,直接看几段广告。

# 可能需要的微调

- **包名白名单**: 首次拿到你的红果短剧真实包名后,改 `AdDetector.java` 的 `TARGET_PACKAGES`
  - 调试包名方法(USB 调试 + ADB):`adb shell dumpsys window | grep mCurrentFocus`
- **滑动灵敏度**: 在 App 主界面拖动 "滑动延迟" 滑块
- **关闭自动跳**: 主界面有"广告结束后自动上滑"开关
