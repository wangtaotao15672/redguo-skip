package com.redguo.skip.gesture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * 模拟上滑手势。封装在后台线程上执行,主线程不阻塞。
 * <p>
 * 仅在 API 24+ 可用(canPerformGestures = true),本项目 minSdk = 24 满足。
 */
public final class SwipeHelper {

    private static final String TAG = "RedGuoSkip";

    private static final HandlerThread THREAD = new HandlerThread("redguo-swipe");
    static { THREAD.start(); }

    private static final Handler HANDLER = new Handler(THREAD.getLooper());

    private SwipeHelper() {}

    /**
     * 在屏幕中央执行一次快速上滑:从屏幕 90% 高度 → 10% 高度,耗时 180ms。
     * 更大的幅度 + 更短的时间,更像"flick"手势,被视频类 App 识别为"切下一集"的概率更高。
     */
    @RequiresApi(Build.VERSION_CODES.N)
    public static void swipeUp(AccessibilityService svc, Runnable onDone) {
        if (svc == null) return;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        Path path = new Path();
        float x = w / 2f;
        float yStart = h * 0.90f;
        float yEnd   = h * 0.10f;
        path.moveTo(x, yStart);
        path.lineTo(x, yEnd);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 180L, true);

        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        Log.i(TAG, "swipe dispatched | screen=" + w + "x" + h
                + " from y=" + (int) yStart + " to y=" + (int) yEnd);

        HANDLER.post(() ->
                svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.i(TAG, "swipe completed");
                        if (onDone != null) onDone.run();
                    }
                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.w(TAG, "swipe cancelled by system");
                        if (onDone != null) onDone.run();
                    }
                }, HANDLER));
    }
}
