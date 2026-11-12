package com.cownow.rsmod;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.cownow.rsmod.status.HookReporter;
import com.cownow.rsmod.status.StatusStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块设置界面（Material 3）。
 *
 * 界面值写到模块自己的 SharedPreferences（"rsmod"），游戏进程里由 XSharedPreferences 读取；
 * 布局见 res/layout/activity_main.xml，开关行见 res/layout/item_switch_row.xml。
 */
public class MainActivity extends AppCompatActivity {

    private MaterialSwitch swIntegrity;
    private MaterialSwitch swPay;
    private MaterialSwitch swCompliance;
    private MaterialSwitch swPushById;
    private MaterialSwitch swNative;
    private MaterialSwitch swAllProcesses;
    private MaterialSwitch swStrict;

    private TextInputLayout tilMask;
    private TextInputLayout tilRva;
    private TextInputEditText etMask;
    private TextInputEditText etRva;

    private Chip chipStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Config.uiPrefs(this);
        // 首次打开就把默认值落盘并打上 cfg_written：游戏侧 prefs 通道才认这份文件
        Config.ensureWritten(this);

        bindRows();
        bindAdvanced();
        bindStatus();

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_reset).setOnClickListener(v -> confirmReset());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportRva());

        ((TextView) findViewById(R.id.tv_targets)).setText(targetTable());
        ((TextView) findViewById(R.id.tv_paths)).setText(pathTable());
    }

    // ---------------- 开关行 ----------------

    private void bindRows() {
        LinearLayout apk = findViewById(R.id.group_apk);
        swIntegrity = addRow(apk, R.drawable.ic_shield,
                R.string.ui_row_integrity, R.string.ui_row_integrity_sub,
                prefs.getBoolean(Config.K_INTEGRITY, true));
        swPay = addRow(apk, R.drawable.ic_pay,
                R.string.ui_row_pay, R.string.ui_row_pay_sub,
                prefs.getBoolean(Config.K_PAY, true));
        swCompliance = addRow(apk, R.drawable.ic_timer,
                R.string.ui_row_compliance, R.string.ui_row_compliance_sub,
                prefs.getBoolean(Config.K_COMPLIANCE, true));
        swPushById = addRow(apk, R.drawable.ic_gift,
                R.string.ui_row_push, R.string.ui_row_push_sub,
                prefs.getBoolean(Config.K_PUSH_BY_ID, false));

        LinearLayout hero = findViewById(R.id.group_hero);
        swNative = addRow(hero, R.drawable.ic_power,
                R.string.ui_row_native, R.string.ui_row_native_sub,
                prefs.getBoolean(Config.K_NATIVE, true));
        swAllProcesses = addRow(hero, R.drawable.ic_layers,
                R.string.ui_row_allproc, R.string.ui_row_allproc_sub,
                prefs.getBoolean(Config.K_ALL_PROCESSES, false));
        swStrict = addRow(hero, R.drawable.ic_lock,
                R.string.ui_row_strict, R.string.ui_row_strict_sub,
                prefs.getBoolean(Config.K_STRICT_VERIFY, false));
    }

    private MaterialSwitch addRow(LinearLayout group, int iconRes, int titleRes, int subtitleRes, boolean checked) {
        View row = getLayoutInflater().inflate(R.layout.item_switch_row, group, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(subtitleRes);

        final MaterialSwitch sw = row.findViewById(R.id.row_switch);
        sw.setChecked(checked);

        // 整行可点，点一下切换开关（Material 列表常见交互）
        row.setOnClickListener(v -> sw.toggle());
        group.addView(row);
        return sw;
    }

    // ---------------- 输入区 ----------------

    private void bindAdvanced() {
        tilMask = findViewById(R.id.til_mask);
        tilRva = findViewById(R.id.til_rva);
        etMask = findViewById(R.id.et_mask);
        etRva = findViewById(R.id.et_rva);

        etMask.setText(prefs.getString(Config.K_NATIVE_MASK, Config.DEFAULT_MASK));
        etRva.setText(prefs.getString(Config.K_NATIVE_RVA, ""));

        etMask.addTextChangedListener(afterEdit(() -> tilMask.setError(null)));
        etRva.addTextChangedListener(afterEdit(() -> tilRva.setError(null)));
    }

    /**
     * 状态判定（LSPosed 是按作用域注入的，模块进程里 Class.forName 拿不到 Xposed 类）：
     *   1) 游戏进程心跳 —— 最直接的“模块真的在游戏里生效”证据
     *   2) 自身注入 / 本进程存在 XposedBridge —— 说明框架把模块注入进了本应用
     *   3) 都没有 —— 未检测到，给出提示
     */
    private void bindStatus() {
        chipStatus = findViewById(R.id.chip_status);
        TextView detail = findViewById(R.id.tv_status_detail);

        long gameTime = StatusStore.gameTime(this);
        String cfg = StatusStore.gameCfg(this);
        boolean cfgFailed = Config.SRC_DEFAULTS.equals(cfg);
        boolean framework = isFrameworkPresent();
        boolean self = HookReporter.selfInjected || StatusStore.selfTime(this) > 0;

        if (gameTime > 0 && !cfgFailed) {
            applyChip(true, R.string.ui_status_active);
            detail.setText(getString(R.string.ui_status_detail_game,
                    formatTime(gameTime), cfgLabel(cfg)));
        } else if (gameTime > 0) {
            // 已注入但读配置失败：开关怎么点都不会生效，必须显式报出来
            applyChip(false, R.string.ui_status_config_failed);
            detail.setText(getString(R.string.ui_status_detail_game_bad, formatTime(gameTime)));
        } else if (self || framework) {
            applyChip(true, R.string.ui_status_active);
            detail.setText(R.string.ui_status_detail_self);
        } else {
            applyChip(false, R.string.ui_status_unknown);
            detail.setText(R.string.ui_status_detail_none);
        }
    }

    private String cfgLabel(String cfg) {
        if (Config.SRC_PROVIDER.equals(cfg)) {
            return getString(R.string.ui_cfg_provider);
        }
        if (Config.SRC_PREFS.equals(cfg)) {
            return getString(R.string.ui_cfg_prefs);
        }
        if (Config.SRC_DEFAULTS.equals(cfg)) {
            return getString(R.string.ui_cfg_defaults);
        }
        return getString(R.string.ui_cfg_unknown);
    }

    private void applyChip(boolean active, int textRes) {
        chipStatus.setText(textRes);

        int bgAttr = active
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError;
        int fgAttr = active
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnError;
        int bg = MaterialColors.getColor(chipStatus, bgAttr, Color.GRAY);
        int fg = MaterialColors.getColor(chipStatus, fgAttr, Color.DKGRAY);

        chipStatus.setChipBackgroundColor(ColorStateList.valueOf(bg));
        chipStatus.setTextColor(fg);
        chipStatus.setChipIconResource(active ? R.drawable.ic_check : R.drawable.ic_close);
        chipStatus.setChipIconTint(ColorStateList.valueOf(fg));
    }

    /** 传统 Xposed / 模块自身也在作用域时，本进程里能直接看到框架类。 */
    private boolean isFrameworkPresent() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String formatTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从游戏切回来时刷新：心跳由被注入的游戏进程写入
        if (chipStatus != null) {
            bindStatus();
        }
    }

    // ---------------- 动作 ----------------

    private void save() {
        String mask = text(etMask).trim();
        if (!mask.matches("[01]{" + NativePatcher.DEFAULT_RVAS.length + "}")) {
            tilMask.setError(getString(R.string.ui_mask_error));
            etMask.requestFocus();
            return;
        }
        tilMask.setError(null);

        String rva = text(etRva).trim();
        if (!rva.isEmpty() && Config.parseRvaList(rva) == null) {
            tilRva.setError(getString(R.string.ui_rva_error));
            etRva.requestFocus();
            return;
        }
        tilRva.setError(null);

        prefs.edit()
                .putBoolean(Config.K_CFG_WRITTEN, true)
                .putBoolean(Config.K_INTEGRITY, swIntegrity.isChecked())
                .putBoolean(Config.K_PAY, swPay.isChecked())
                .putBoolean(Config.K_COMPLIANCE, swCompliance.isChecked())
                .putBoolean(Config.K_PUSH_BY_ID, swPushById.isChecked())
                .putBoolean(Config.K_NATIVE, swNative.isChecked())
                .putBoolean(Config.K_ALL_PROCESSES, swAllProcesses.isChecked())
                .putBoolean(Config.K_STRICT_VERIFY, swStrict.isChecked())
                .putString(Config.K_NATIVE_MASK, mask)
                .putString(Config.K_NATIVE_RVA, rva)
                .apply();

        snack(getString(R.string.ui_saved));
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ui_reset_title)
                .setMessage(R.string.ui_reset_message)
                .setNegativeButton(R.string.ui_cancel, null)
                .setPositiveButton(R.string.ui_confirm, (d, w) -> {
                    prefs.edit().clear().apply();
                    Config.ensureWritten(this);
                    recreate();
                })
                .show();
    }

    private void exportRva() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            snack(getString(R.string.ui_no_ext_dir));
            return;
        }
        File f = new File(dir, "rva.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NativePatcher.DEFAULT_RVAS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0x").append(Integer.toHexString(NativePatcher.DEFAULT_RVAS[i]));
        }
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(sb.toString());
            w.write('\n');
        } catch (Throwable t) {
            snack(getString(R.string.ui_export_fail, String.valueOf(t)));
            return;
        }
        snack(getString(R.string.ui_export_done, f.getAbsolutePath()));
    }

    // ---------------- 文本块 ----------------

    private String targetTable() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NativePatcher.TARGET_NAMES.length; i++) {
            sb.append(i).append("  ")
                    .append(String.format(Locale.US, "%-33s", NativePatcher.TARGET_NAMES[i]))
                    .append("0x").append(Integer.toHexString(NativePatcher.DEFAULT_RVAS[i]));
            if (i != NativePatcher.TARGET_NAMES.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String pathTable() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NativePatcher.RVA_FILES.length; i++) {
            sb.append(NativePatcher.RVA_FILES[i]);
            if (i != NativePatcher.RVA_FILES.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    private void snack(String msg) {
        Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG).show();
    }

    private static String text(TextInputEditText et) {
        Editable e = et.getText();
        return e == null ? "" : e.toString();
    }

    private static TextWatcher afterEdit(Runnable r) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                r.run();
            }
        };
    }
}
