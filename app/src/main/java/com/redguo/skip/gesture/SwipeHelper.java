package com.redguo.skip.gesture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;

import androidx.annotation.RequiresApi;

/**
 * 模拟上滑手势。封装在后台线程上执行,主线程不阻塞。
 * <p>
 * 仅在 API 24+ 可用(canPerformGestures = true),本项目 minSdk = 24 满足。
 */
public final class SwipeHelper {

    private static final HandlerThread THREAD = new HandlerThread("redguo-swipe");
    static { THREAD.start(); }

    private static final Handler HANDLER = new Handler(THREAD.getLooper());

    private SwipeHelper() {}

    /**
     * 在屏幕中央执行一次快速上滑:从屏幕 75% 高度 → 25% 高度,耗时 250ms。
     * 这是红果 / 抖音类短视频切换下一集的常用手势幅度。
     */
    @RequiresApi(Build.VERSION_CODES.N)
    public static void swipeUp(AccessibilityService svc, Runnable onDone) {
        if (svc == null) return;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        Path path = new Path();
        float x = w / 2f;
        float yStart = h * 0.75f;
        float yEnd   = h * 0.25f;
        path.moveTo(x, yStart);
        path.lineTo(x, yEnd);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 250L, true);

        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        HANDLER.post(() ->
                svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (onDone != null) onDone.run();
                    }
                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (onDone != null) onDone.run();
                    }
                }, HANDLER));
    }
}
