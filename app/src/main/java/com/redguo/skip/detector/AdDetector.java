package com.redguo.skip.detector;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告 / 视频状态检测器。纯本地、纯启发式,不上传任何东西。
 * <p>
 * 设计思路:
 * - 不写死任何控件 ID(红果短剧每次改版 ID 会变)。
 * - 通过屏幕文本的特征词识别:出现"广告 / 广告中 / 跳过广告 / X秒后可跳过"等,
 *   就认为当前在广告播放页;当这些特征全部消失、且能再次看到"评论 / 点赞 / 收藏"
 *   这类视频内容区特征,就认为广告结束。
 */
public final class AdDetector {

    // 红果短剧广告期的典型文案(多版本兼容,尽量宽松匹配)
    private static final String[] AD_KEYWORDS = {
            "广告", "广告中", "跳过广告", "秒后跳过", "可跳过",
            "了解详情", "立即下载", "立即体验", "领取奖励"
    };

    // 视频内容区典型文案(看到这些,说明回到正片了)
    private static final String[] VIDEO_CONTENT_KEYWORDS = {
            "评论", "点赞", "收藏", "分享", "关注", "选集", "剧集",
            "下一集", "全集", "播放"
    };

    // 「广告结束、请上滑」过渡提示文案。红果短剧 / 抖音类 App 在广告结束后
    // 会出现一个独立的「上滑继续观看」层,这层往往不在 AccessibilityNodeInfo 树里
    // (动画层 / 自绘 View),所以用 screenText 不一定能拿到——但万一抓到了,
    // 就直接当广告结束,不必死等广告文案彻底消失。
    private static final String[] AD_FINISHED_PROMPTS = {
            "上滑", "继续观看", "下一集", "短剧"
    };

    /**
     * 是否检测到「广告结束、请上滑」过渡提示。
     * 返回 true 时,调用方应直接走 swipe 路径,不必再等广告文案消失。
     */
    public boolean hasAdFinishedPrompt(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;
        for (String kw : AD_FINISHED_PROMPTS) {
            if (screenText.contains(kw)) return true;
        }
        return false;
    }

    // 目标 App 包名前缀(以 isTargetPackage 的 startsWith 判定)
    private static final String[] TARGET_PACKAGES = {
            "com.phoenix.read"
    };

    /** 只对红果相关的包做分析,其它 App 一律忽略。 */
    public boolean isTargetPackage(String pkg) {
        if (pkg == null) return false;
        for (String t : TARGET_PACKAGES) {
            if (pkg.startsWith(t)) return true;
        }
        return false;
    }

    /**
     * 汇总当前屏幕所有可见文本。
     * 深度遍历 AccessibilityNodeInfo 树,所有非空 text / contentDescription 都收进来。
     */
    public String collectScreenText(AccessibilityNodeInfo root) {
        if (root == null) return "";
        StringBuilder sb = new StringBuilder(1024);
        collect(root, sb);
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append('\n');
        }
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0) {
            sb.append(cd).append('\n');
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            collect(node.getChild(i), sb);
        }
    }

    /** 判断当前屏幕是否处于广告页。 */
    public boolean isAdScreen(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;
        for (String kw : AD_KEYWORDS) {
            if (screenText.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 判断当前是否已经回到视频内容页。
     * 条件:不处于广告页 且 出现至少 2 个视频内容区特征词。
     * (要求 ≥2 是为了避免单纯的"分享"按钮误判)
     */
    public boolean isVideoContentScreen(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;
        if (isAdScreen(screenText)) return false;
        int hit = 0;
        for (String kw : VIDEO_CONTENT_KEYWORDS) {
            if (screenText.contains(kw)) hit++;
        }
        return hit >= 2;
    }

    /**
     * 提取屏幕上形如 "3秒后可跳过" / "X秒" 的倒计时数字。
     * 如果能解析到倒计时,可以选择等倒计时归零再滑,体验更顺;
     * 解析不到也无所谓,直接走"看到视频内容区"的兜底分支。
     */
    public int parseAdCountdownSeconds(String screenText) {
        if (screenText == null) return -1;
        // 匹配 "X秒" 或 "X秒后可跳过" 这种
        Pattern p = Pattern.compile("(\\d{1,2})\\s*秒");
        java.util.regex.Matcher m = p.matcher(screenText);
        int min = Integer.MAX_VALUE;
        boolean found = false;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v < min) {
                    min = v;
                    found = true;
                }
            } catch (NumberFormatException ignore) {}
        }
        return found ? min : -1;
    }
}
