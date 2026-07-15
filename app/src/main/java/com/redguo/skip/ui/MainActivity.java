package com.redguo.skip.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.redguo.skip.BuildConfig;
import com.redguo.skip.R;
import com.redguo.skip.config.AppConfig;
import com.redguo.skip.service.AdSkipAccessibilityService;
import com.redguo.skip.update.UpdateChecker;

import java.util.Locale;

/**
 * 单 Activity,纯本地 UI:
 * - 顶部展示服务状态
 * - 中部:行为开关 + 滑动延迟
 * - 底部:网络 / 更新
 *
 * 没有任何联网行为,除非用户主动点击"立即检查更新"。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvServiceStatus;
    private TextView tvNetworkStatus;
    private Switch swAutoSwipe;
    private Switch swAllowNetwork;
    private SeekBar sbDelay;
    private TextView tvDelayValue;
    private Button btnCheckUpdate;
    private TextView tvUpdateResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvNetworkStatus = findViewById(R.id.tv_network_status);
        swAutoSwipe = findViewById(R.id.sw_auto_swipe);
        swAllowNetwork = findViewById(R.id.sw_allow_network);
        sbDelay = findViewById(R.id.sb_delay);
        tvDelayValue = findViewById(R.id.tv_delay_value);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        tvUpdateResult = findViewById(R.id.tv_update_result);

        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_what_is_this).setOnClickListener(this::showExplainDialog);

        AppConfig cfg = AppConfig.get(this);

        swAutoSwipe.setChecked(cfg.isAutoSwipeEnabled());
        swAutoSwipe.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            cfg.setAutoSwipeEnabled(checked);
        });

        swAllowNetwork.setChecked(cfg.isNetworkAllowed());
        swAllowNetwork.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            cfg.setNetworkAllowed(checked);
            refreshNetworkLabel();
        });

        sbDelay.setMax(50);                  // 0 ~ 50 * 100 = 0 ~ 5000ms
        int delayMs = cfg.getSwipeDelayMs();
        sbDelay.setProgress(Math.max(0, Math.min(50, (delayMs - 500) / 100)));
        tvDelayValue.setText(String.format(Locale.CHINA, "%d ms", delayMs));
        sbDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int ms = 500 + progress * 100;        // 500 ~ 5000ms
                tvDelayValue.setText(String.format(Locale.CHINA, "%d ms", ms));
                if (fromUser) cfg.setSwipeDelayMs(ms);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnCheckUpdate.setOnClickListener(v -> doCheckUpdate());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatus();
        refreshNetworkLabel();
    }

    private void refreshServiceStatus() {
        boolean on = isAccessibilityServiceEnabled();
        tvServiceStatus.setText(on ? R.string.status_service_on : R.string.status_service_off);
        tvServiceStatus.setTextColor(getResources().getColor(
                on ? R.color.brand : R.color.text_secondary, getTheme()));
    }

    private void refreshNetworkLabel() {
        boolean on = AppConfig.get(this).isNetworkAllowed();
        tvNetworkStatus.setText(on ? R.string.label_network_on : R.string.label_network_off);
        btnCheckUpdate.setEnabled(on);
        tvUpdateResult.setText(on ? R.string.hint_check_update : R.string.msg_offline);
    }

    /** 检查本应用的无障碍服务是否已被用户在系统里启用 */
    private boolean isAccessibilityServiceEnabled() {
        int expected = 0;
        String svc = getPackageName() + "/" + AdSkipAccessibilityService.class.getName();
        try {
            expected = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        if (expected != 1) return false;
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(list);
        while (splitter.hasNext()) {
            if (svc.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private void doCheckUpdate() {
        tvUpdateResult.setText(R.string.msg_checking);
        UpdateChecker.checkAsync(this, r -> runOnUiThread(() -> {
            if (!r.online) {
                tvUpdateResult.setText(R.string.msg_offline);
                return;
            }
            if (r.error != null) {
                tvUpdateResult.setText(getString(R.string.msg_update_failed, r.error));
                return;
            }
            String remote = r.remoteVersion == null ? "?" : r.remoteVersion;
            String current = BuildConfig.VERSION_NAME;
            String localLast = AppConfig.get(this).getLastKnownVersion();
            if (!remote.equals(localLast) && !remote.equals(current)) {
                AppConfig.get(this).setLastKnownVersion(remote);
                StringBuilder sb = new StringBuilder();
                sb.append(getString(R.string.msg_new_version_found, remote, current));
                if (r.downloadUrl != null) {
                    sb.append('\n').append(getString(R.string.msg_update_url, r.downloadUrl));
                }
                if (r.notes != null) sb.append("\n更新说明:").append(r.notes);
                tvUpdateResult.setText(sb);
            } else {
                tvUpdateResult.setText(R.string.msg_up_to_date);
            }
        }));
    }

    private void showExplainDialog(View v) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.hint_root_explain)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
