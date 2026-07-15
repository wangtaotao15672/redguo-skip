package com.redguo.skip.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import com.redguo.skip.config.AppConfig;
import com.redguo.skip.detector.AdDetector;
import com.redguo.skip.gesture.SwipeHelper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心服务。
 * <p>
 * 状态机:
 * <pre>
 *   IDLE ──看到广告文案──▶ IN_AD
 *   IN_AD ──广告文案消失 & 视频内容区出现──▶ WAITING_BUFFER
 *   WAITING_BUFFER ──延迟 N ms──▶ SWIPING
 *   SWIPING ──派发上滑手势──▶ IDLE (下一集)
 * </pre>
 *
 * 设计取舍:
 * - 用屏幕文本判定状态,而不依赖任何控件 ID,改版后照样能用。
 * - 每次窗口变化都重读整棵树一次,计算量小(几十 ms),无压力。
 * - "正在滑动" 用 AtomicBoolean 互斥,避免上一次手势还没完成又触发下一次。
 */
public class AdSkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "RedGuoSkip";

    /** swipe 完成后,多少毫秒内不再触发新 swipe(防 feed 流死循环) */
    private static final long SWIPE_COOLDOWN_MS = 3_000L;

    private enum State { IDLE, IN_AD, WAITING_BUFFER, SWIPING }

    private final AdDetector detector = new AdDetector();
    private volatile State state = State.IDLE;
    private final AtomicBoolean swipeInFlight = new AtomicBoolean(false);

    /**
     * 上次 swipe 完成时刻(elapsedRealtime 毫秒)。用作"刚滑完 N 秒内别再派新 swipe"
     * 的冷却,避免在 feed / 视频流卡片里误触发「上滑」造成死循环。
     */
    private volatile long lastSwipeCompletedAt = 0L;

    /**
     * 一旦识别到「广告结束提示(上滑/继续观看)」并决定 swipe,就置 true。
     * 在 WAITING_BUFFER 期间,即使屏幕文本里残留 "广告" 关键词,
     * 也不再回滚到 IN_AD,避免反复 cancel pending swipe 导致 swipe 永远派不出去。
     * swipe 真正完成(completed 或 cancelled)后重置为 false。
     */
    private volatile boolean swipeCommitted = false;

    /** 上一次派发滑动的延迟任务,用于状态被打断时取消。 */
    @Nullable private Runnable pendingSwipe;

    /** 上一次记过日志的前台包名,用于切换 app 时打一次,避免每个 touch 都刷屏。 */
    @Nullable private String lastLoggedPkg;

    /**
     * 单例主线程 Handler。绝对不要在 schedule/cancel 里 new Handler,
     * 否则多个 Handler 共享同一个 Looper 时 removeCallbacks 会有 race,
     * 旧 Runnable 漏网,1ms 内派出多个 swipe。
     */
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        state = State.IDLE;
        AppConfig cfg = AppConfig.get(this);
        Log.i(TAG, "service connected | autoSwipe=" + cfg.isAutoSwipeEnabled()
                + " delayMs=" + cfg.getSwipeDelayMs()
                + " allowNetwork=" + cfg.isNetworkAllowed());
    }

    @Override
    public void onDestroy() {
        state = State.IDLE;
        swipeCommitted = false;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        state = State.IDLE;
        swipeCommitted = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgStr = pkg.toString();

        // 前台 app 切换时打一次,用来确认白名单是否命中
        if (!pkgStr.equals(lastLoggedPkg)) {
            lastLoggedPkg = pkgStr;
            Log.i(TAG, "fg pkg = " + pkgStr + " | whitelist hit = " + detector.isTargetPackage(pkgStr));
        }

        if (!detector.isTargetPackage(pkgStr)) {
            // 离开目标 App → 复位
            if (state != State.IDLE) state = State.IDLE;
            return;
        }

        // 收所有窗口的文本(主活动窗口 + 系统覆盖 / 弹窗 / 独立 Window)
        // 红果短剧广告结束后的「上滑继续观看短剧」提示层可能挂在独立 Window 上,
        // 单看 getRootInActiveWindow() 抓不到。
        String screenText = collectAllWindowsText();
        if (screenText.isEmpty()) return;

        boolean adOn = detector.isAdScreen(screenText);
        boolean videoOn = detector.isVideoContentScreen(screenText);
        boolean promptOn = detector.hasAdFinishedPrompt(screenText);
        int adCountdown = detector.parseAdCountdownSeconds(screenText);

        switch (state) {
            case IDLE:
                if (adOn) {
                    Log.i(TAG, "ad started | countdown=" + adCountdown
                            + "s | text=\"" + truncate(screenText, 200) + "\"");
                    state = State.IN_AD;
                } else if (promptOn) {
                    // 没识别到广告文案,但「上滑继续观看」过渡提示已出现 → 直接 swipe
                    Log.i(TAG, "swipe prompt detected in IDLE, trigger swipe | text=\""
                            + truncate(screenText, 200) + "\"");
                    state = State.WAITING_BUFFER;
                    swipeCommitted = true;  // 锁住,避免 ad 残留把 swipe 撤了
                    scheduleSwipeIfNeeded();
                }
                break;

            case IN_AD:
                if ((!adOn && videoOn) || promptOn) {
                    Log.i(TAG, "ad finished, back to video content"
                            + (promptOn ? " (by prompt)" : "")
                            + " | text=\"" + truncate(screenText, 200) + "\"");
                    state = State.WAITING_BUFFER;
                    if (promptOn) swipeCommitted = true;  // 锁住:不让 adOn 残留把 swipe 撤了
                    scheduleSwipeIfNeeded();
                }
                break;

            case WAITING_BUFFER:
                if (swipeCommitted) {
                    // 已经决定 swipe,即使 ad 关键词残留也保持 WAITING_BUFFER 等 swipe 完成
                    if (!videoOn) {
                        Log.d(TAG, "page transitioning, hold for committed swipe");
                    }
                } else if (adOn) {
                    // 期间若又出现广告,回到 IN_AD(广告套广告的情况)
                    Log.i(TAG, "nested ad detected, fall back to IN_AD");
                    cancelPendingSwipe();
                    state = State.IN_AD;
                } else if (!videoOn) {
                    // 页面变化中(比如出现剧集选择弹窗),保持等待
                    Log.d(TAG, "page transitioning, keep waiting");
                } else {
                    scheduleSwipeIfNeeded();
                }
                break;

            case SWIPING:
                // 手势派发中,等 onCompleted 回调重置
                break;
        }
    }

    /** 截断屏幕文本,避免日志里出现几 KB 字符串。 */
    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "...(+" + (s.length() - n) + " chars)" : s;
    }

    /**
     * 合并当前所有 Window(主活动窗口 + 系统覆盖 / 弹窗 / 独立 Window)的文本。
     * 一些 App(尤其是带自绘 View 或悬浮层的)会把关键提示挂在独立 Window 里,
     * 只看 getRootInActiveWindow() 会漏。
     */
    private String collectAllWindowsText() {
        StringBuilder sb = new StringBuilder(2048);
        java.util.HashSet<AccessibilityNodeInfo> seen = new java.util.HashSet<>();

        AccessibilityNodeInfo mainRoot = getRootInActiveWindow();
        if (mainRoot != null) {
            detector.appendScreenText(mainRoot, sb);
            seen.add(mainRoot);
        }

        for (AccessibilityWindowInfo win : getWindows()) {
            if (win == null) continue;
            AccessibilityNodeInfo root = win.getRoot();
            if (root == null || seen.contains(root)) continue;
            detector.appendScreenText(root, sb);
            seen.add(root);
        }
        return sb.toString();
    }

    private void scheduleSwipeIfNeeded() {
        if (!AppConfig.get(this).isAutoSwipeEnabled()) {
            Log.i(TAG, "auto swipe disabled in config, stay in WAITING_BUFFER");
            return;
        }
        if (swipeInFlight.get()) return;
        // 已有 pending 任务在排队,不要再排新的(修 v5 的 1ms 派发 3 个 bug)
        if (pendingSwipe != null) return;
        // 刚滑完 N 秒内不再派新 swipe,防止启发式误判在 feed 流里持续触发
        long sinceLast = android.os.SystemClock.elapsedRealtime() - lastSwipeCompletedAt;
        if (lastSwipeCompletedAt != 0 && sinceLast < SWIPE_COOLDOWN_MS) {
            Log.d(TAG, "swipe cooldown " + (SWIPE_COOLDOWN_MS - sinceLast) + "ms remaining");
            return;
        }

        int delayMs = AppConfig.get(this).getSwipeDelayMs();
        Runnable task = () -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                state = State.SWIPING;
                swipeInFlight.set(true);
                SwipeHelper.swipeUp(this, () -> {
                    swipeInFlight.set(false);
                    state = State.IDLE;
                    swipeCommitted = false;  // 解锁,允许下一个广告周期
                    pendingSwipe = null;     // 清掉,下一次可以再排
                    lastSwipeCompletedAt = android.os.SystemClock.elapsedRealtime();
                    Log.i(TAG, "swipe done, back to IDLE");
                });
            }
        };
        pendingSwipe = task;
        mainHandler.postDelayed(task, delayMs);
        Log.i(TAG, "swipe scheduled in " + delayMs + "ms");
    }

    private void cancelPendingSwipe() {
        if (pendingSwipe != null) {
            mainHandler.removeCallbacks(pendingSwipe);
            pendingSwipe = null;
            Log.i(TAG, "pending swipe cancelled");
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 不需要 sticky,AccessibilityService 自带生命周期
        return START_NOT_STICKY;
    }
}
