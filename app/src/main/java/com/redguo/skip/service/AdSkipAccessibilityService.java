package com.redguo.skip.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private enum State { IDLE, IN_AD, WAITING_BUFFER, SWIPING }

    private final AdDetector detector = new AdDetector();
    private volatile State state = State.IDLE;
    private final AtomicBoolean swipeInFlight = new AtomicBoolean(false);

    /** 上一次派发滑动的延迟任务,用于状态被打断时取消。 */
    @Nullable private Runnable pendingSwipe;

    /** 上一次记过日志的前台包名,用于切换 app 时打一次,避免每个 touch 都刷屏。 */
    @Nullable private String lastLoggedPkg;

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
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        state = State.IDLE;
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

        // 取根节点 → 提取屏幕文本
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String screenText = detector.collectScreenText(root);

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
                    scheduleSwipeIfNeeded();
                }
                break;

            case IN_AD:
                if ((!adOn && videoOn) || promptOn) {
                    Log.i(TAG, "ad finished, back to video content"
                            + (promptOn ? " (by prompt)" : "")
                            + " | text=\"" + truncate(screenText, 200) + "\"");
                    state = State.WAITING_BUFFER;
                    scheduleSwipeIfNeeded();
                }
                break;

            case WAITING_BUFFER:
                // 期间若又出现广告,回到 IN_AD(广告套广告的情况)
                if (adOn) {
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

    private void scheduleSwipeIfNeeded() {
        if (!AppConfig.get(this).isAutoSwipeEnabled()) {
            Log.i(TAG, "auto swipe disabled in config, stay in WAITING_BUFFER");
            return;
        }
        if (swipeInFlight.get()) return;

        // 取消上一次还没执行的延迟任务(防止状态抖动时重复入队)
        cancelPendingSwipe();

        int delayMs = AppConfig.get(this).getSwipeDelayMs();
        Runnable task = () -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                state = State.SWIPING;
                swipeInFlight.set(true);
                SwipeHelper.swipeUp(this, () -> {
                    swipeInFlight.set(false);
                    state = State.IDLE;
                    Log.i(TAG, "swipe done, back to IDLE");
                });
            }
        };
        pendingSwipe = task;
        getMainThreadHandler().postDelayed(task, delayMs);
    }

    /** 拿到主线程 Handler,统一在 UI 线程上 post swipe 任务。 */
    private android.os.Handler getMainThreadHandler() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    private void cancelPendingSwipe() {
        if (pendingSwipe != null) {
            getMainThreadHandler().removeCallbacks(pendingSwipe);
            pendingSwipe = null;
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 不需要 sticky,AccessibilityService 自带生命周期
        return START_NOT_STICKY;
    }
}
