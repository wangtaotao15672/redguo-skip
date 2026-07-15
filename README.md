# 红果跳广告 (RedGuoSkip)

一个完全离线运行、用于在 **红果短剧** 广告播放结束后自动上滑切换下一集的 Android 应用。

## 特性

| 需求 | 实现 |
|---|---|
| 广告结束后自动上滑 | AccessibilityService + 屏幕文本启发式判定 + `dispatchGesture` 模拟上滑 |
| 默认不联网运行 | 核心代码路径 0 个网络调用;`INTERNET` 权限仅在"联网检查更新"被开启时才会被使用 |
| 联网仅用于更新 | `UpdateChecker` 用 JDK 自带 `HttpURLConnection`,主动发起前会再次检查开关 |
| 架构清晰 | 单 Activity + 单一 Service + Detector/Gesture/Update/Config 四个职责单一的模块 |

## 架构

```
┌──────────────────────────────────────────────────────┐
│                MainActivity (UI)                     │
│   状态展示 / 开关 / 滑动延迟 / 手动检查更新           │
└──────────────────┬───────────────────────────────────┘
                   │ AppConfig (SharedPreferences)
                   ▼
┌──────────────────────────────────────────────────────┐
│       AdSkipAccessibilityService (后台常驻)          │
│  状态机: IDLE → IN_AD → WAITING_BUFFER → SWIPING     │
└──┬────────────────┬────────────────┬─────────────────┘
   │                │                │
   ▼                ▼                ▼
┌─────────┐   ┌─────────────┐   ┌──────────────┐
│AdDetector│  │SwipeHelper  │   │UpdateChecker │
│屏幕文本  │  │dispatchGes  │   │HttpURLConnec │
│启发式    │  │ture 模拟上滑 │   │一次 GET 解析  │
└─────────┘   └─────────────┘   └──────────────┘
```

## 模块职责

- **`config.AppConfig`** — 所有开关、延迟、最新版本号都在这里读写,**没有任何 IO**
- **`detector.AdDetector`** — 通过屏幕文本判断"广告期 / 视频内容期",不依赖任何控件 ID
- **`gesture.SwipeHelper`** — 封装 `dispatchGesture`,在后台 HandlerThread 派发
- **`service.AdSkipAccessibilityService`** — 状态机主循环,只对红果相关包名做处理
- **`update.UpdateChecker`** — 唯一的联网入口。开关关闭时根本不会发起请求
- **`ui.MainActivity`** — 单 Activity,不持有任何网络/手势相关状态

## 编译与运行

```bash
# 需要 Android SDK + JDK 17
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

安装后:

1. 打开 App
2. 点 **"前往开启无障碍服务"** → 找到 "红果广告自动跳过" → 开启
3. 回到 App,顶部显示 **"运行中"** 即可
4. (可选)打开 "允许联网" → 点击 "立即检查更新"

## 检测原理

不写死任何控件 ID,仅依赖屏幕文本:

| 阶段 | 屏幕文本特征 |
|---|---|
| 广告期 | 包含 "广告 / 跳过广告 / X秒后可跳过 / 了解详情" 等 |
| 视频内容期 | 不含广告词,且至少出现 2 个 "评论 / 点赞 / 收藏 / 选集" 等 |
| 切换时机 | 视频内容期首次出现 → 等待 N ms(给视频缓冲)→ 上滑 |

> 为什么是 ≥2 个视频特征词?单个 "分享" 按钮可能在很多页面出现,两个及以上基本只在视频内容区。

## 安全 / 隐私

- **默认离线**:开关关闭时,网络请求根本不会发出,系统层面也用 `usesCleartextTraffic="false"` 禁掉明文 HTTP
- **最小权限**:只申请 `INTERNET`、`ACCESS_NETWORK_STATE`,不申请存储/电话/位置/相机等敏感权限
- **白名单包名**:无障碍服务只对写死的几个红果相关包名触发,其它 App 一律忽略

## 切换目标 App

`AdDetector.TARGET_PACKAGES` 当前是 `com.phoenix.read`(以 `startsWith` 判定)。如果要换到其它 App,改成对应包名前缀即可,可通过 `adb shell dumpsys window | grep mCurrentFocus` 拿到。

## 后续可扩展点

- [ ] 接入前台服务 + 通知,让用户随时知道当前状态
- [ ] 把 `swipe_delay_ms` 做成倒计时自适应(广告剩余 X 秒就等 X 秒)
- [ ] 支持"自动跳过"按钮:检测到 "X秒后可跳过" 时主动点击
- [ ] 把 `UpdateChecker` 的 URL 抽成可在 UI 配置的项
