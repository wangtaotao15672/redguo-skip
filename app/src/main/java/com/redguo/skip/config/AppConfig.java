package com.redguo.skip.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置中心。所有状态都用 SharedPreferences 持久化,不写文件、不联网。
 * <p>
 * 默认行为:完全离线、启用自动上滑、滑动前等待 1500ms(给视频缓冲留时间)。
 */
public final class AppConfig {

    private static final String FILE = "redguo_skip_cfg";

    private static final String KEY_ALLOW_NETWORK = "allow_network";
    private static final String KEY_AUTO_SWIPE = "auto_swipe";
    private static final String KEY_SWIPE_DELAY_MS = "swipe_delay_ms";
    private static final String KEY_LAST_VERSION = "last_known_version";

    private static volatile AppConfig INSTANCE;

    private final SharedPreferences sp;

    private AppConfig(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static AppConfig get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AppConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AppConfig(ctx);
                }
            }
        }
        return INSTANCE;
    }

    // -------- 开关 --------

    /** 用户是否显式同意联网。默认 false。 */
    public boolean isNetworkAllowed() {
        return sp.getBoolean(KEY_ALLOW_NETWORK, false);
    }

    public void setNetworkAllowed(boolean allowed) {
        sp.edit().putBoolean(KEY_ALLOW_NETWORK, allowed).apply();
    }

    /** 广告结束后是否自动上滑。默认 true。 */
    public boolean isAutoSwipeEnabled() {
        return sp.getBoolean(KEY_AUTO_SWIPE, true);
    }

    public void setAutoSwipeEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_AUTO_SWIPE, enabled).apply();
    }

    /** 滑动前等待时间(毫秒)。默认 1500ms。 */
    public int getSwipeDelayMs() {
        return sp.getInt(KEY_SWIPE_DELAY_MS, 1500);
    }

    public void setSwipeDelayMs(int ms) {
        sp.edit().putInt(KEY_SWIPE_DELAY_MS, ms).apply();
    }

    public String getLastKnownVersion() {
        return sp.getString(KEY_LAST_VERSION, "");
    }

    public void setLastKnownVersion(String v) {
        sp.edit().putString(KEY_LAST_VERSION, v).apply();
    }
}
