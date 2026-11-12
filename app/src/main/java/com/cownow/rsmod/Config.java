package com.cownow.rsmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 原 main1.java 顶部那段开关常量（TOGGLE1..TOGGLE4）现在由模块自带的设置界面写 SharedPreferences。
 *
 * 游戏进程里读配置有两条路：
 *   1) XSharedPreferences 直接读模块 prefs（快，但 LSPosed / 分区存储下可能读不到）
 *   2) 读不到（或没写过 sentinel）时，通过模块自己的 ContentProvider 同步取一次配置
 * 以前只有第 1 条，且失败时静默退回“全开”的默认值 —— 表现就是“开关关了重进游戏照样生效”。
 * 现在把读取来源记进 Config.source，日志和设置页都会显示到底是哪条路生效的。
 */
public final class Config {

    public static final String MODULE_PKG = "com.cownow.rsmod";
    public static final String GAME_PKG = "com.turbochilli.rollingsky";
    public static final String PREFS_NAME = "rsmod";

    public static final String DEFAULT_MASK = "100111111";

    // ---- key ----
    public static final String K_INTEGRITY = "integrity";          // 原 TOGGLE1
    public static final String K_PAY = "pay";                      // 原 TOGGLE2
    public static final String K_COMPLIANCE = "compliance";        // 原 TOGGLE3
    public static final String K_PUSH_BY_ID = "push_reward_by_id"; // 原 TOGGLE4
    public static final String K_NATIVE = "native";                // main2 总开关
    public static final String K_NATIVE_MASK = "native_mask";      // 9 位 0/1
    public static final String K_NATIVE_RVA = "native_rva";        // 逗号分隔的 9 个 hex
    public static final String K_ALL_PROCESSES = "all_processes";
    public static final String K_STRICT_VERIFY = "strict_verify";
    /** 设置页至少保存过一次的标记：没有它就说明 prefs 没写过，不能拿默认值当真。 */
    public static final String K_CFG_WRITTEN = "cfg_written";

    // ---- 配置来源 ----
    public static final String SRC_PREFS = "prefs";
    public static final String SRC_PROVIDER = "provider";
    public static final String SRC_DEFAULTS = "defaults";

    // ---- 游戏进程内的有效值 ----
    public static boolean integrity = true;
    public static boolean pay = true;
    public static boolean compliance = true;
    public static boolean pushRewardById = false;
    public static boolean nativePatch = true;
    public static boolean allProcesses = false;
    public static boolean strictVerify = false;
    public static String nativeMask = DEFAULT_MASK;
    public static String nativeRva = "";
    public static String source = SRC_DEFAULTS;

    private Config() {
    }

    /** 只读 prefs（不碰 IPC），用于 handleLoadPackage 里判断进程过滤这类早期逻辑。 */
    public static void load() {
        resetToDefaults();
        if (readPrefs()) {
            source = SRC_PREFS;
        } else {
            source = SRC_DEFAULTS;
        }
        normalize();
    }

    /** 游戏进程里带 Context 调用：prefs 读不到就找模块 Provider 要。 */
    public static void load(Context ctx) {
        resetToDefaults();

        if (readPrefs()) {
            source = SRC_PREFS;
            normalize();
            return;
        }

        Bundle b = queryProvider(ctx);
        if (applyBundle(b)) {
            source = SRC_PROVIDER;
            normalize();
            return;
        }

        source = SRC_DEFAULTS;
        normalize();
    }

    private static void resetToDefaults() {
        integrity = true;
        pay = true;
        compliance = true;
        pushRewardById = false;
        nativePatch = true;
        allProcesses = false;
        strictVerify = false;
        nativeMask = DEFAULT_MASK;
        nativeRva = "";
        source = SRC_DEFAULTS;
    }

    /**
     * 读模块 prefs。只有「文件可读且带有 cfg_written 标记」才算成功，
     * 否则视为不可用（继续走 Provider），避免把“读不到”误当成“用户全开”。
     */
    private static boolean readPrefs() {
        try {
            XSharedPreferences p = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
            if (p.getFile() == null || !p.getFile().canRead()) {
                return false;
            }
            p.reload();
            if (!p.getBoolean(K_CFG_WRITTEN, false)) {
                return false;
            }
            integrity = p.getBoolean(K_INTEGRITY, true);
            pay = p.getBoolean(K_PAY, true);
            compliance = p.getBoolean(K_COMPLIANCE, true);
            pushRewardById = p.getBoolean(K_PUSH_BY_ID, false);
            nativePatch = p.getBoolean(K_NATIVE, true);
            allProcesses = p.getBoolean(K_ALL_PROCESSES, false);
            strictVerify = p.getBoolean(K_STRICT_VERIFY, false);
            nativeMask = p.getString(K_NATIVE_MASK, DEFAULT_MASK);
            nativeRva = p.getString(K_NATIVE_RVA, "");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bundle queryProvider(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getContentResolver().call(
                    com.cownow.rsmod.status.StatusStore.HEARTBEAT_URI,
                    com.cownow.rsmod.status.StatusStore.METHOD_CONFIG, null, new Bundle());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean applyBundle(Bundle b) {
        if (b == null || !b.getBoolean("ok", false)) {
            return false;
        }
        integrity = b.getBoolean(K_INTEGRITY, true);
        pay = b.getBoolean(K_PAY, true);
        compliance = b.getBoolean(K_COMPLIANCE, true);
        pushRewardById = b.getBoolean(K_PUSH_BY_ID, false);
        nativePatch = b.getBoolean(K_NATIVE, true);
        allProcesses = b.getBoolean(K_ALL_PROCESSES, false);
        strictVerify = b.getBoolean(K_STRICT_VERIFY, false);
        nativeMask = b.getString(K_NATIVE_MASK, DEFAULT_MASK);
        nativeRva = b.getString(K_NATIVE_RVA, "");
        return true;
    }

    /** Provider 侧用：直接从模块自己的 prefs 生成配置（模块进程内一定读得到）。 */
    public static Bundle readForProvider(Context ctx) {
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        if (ctx == null) {
            b.putBoolean("ok", false);
            return b;
        }
        SharedPreferences p = uiPrefs(ctx);
        b.putBoolean(K_INTEGRITY, p.getBoolean(K_INTEGRITY, true));
        b.putBoolean(K_PAY, p.getBoolean(K_PAY, true));
        b.putBoolean(K_COMPLIANCE, p.getBoolean(K_COMPLIANCE, true));
        b.putBoolean(K_PUSH_BY_ID, p.getBoolean(K_PUSH_BY_ID, false));
        b.putBoolean(K_NATIVE, p.getBoolean(K_NATIVE, true));
        b.putBoolean(K_ALL_PROCESSES, p.getBoolean(K_ALL_PROCESSES, false));
        b.putBoolean(K_STRICT_VERIFY, p.getBoolean(K_STRICT_VERIFY, false));
        b.putString(K_NATIVE_MASK, p.getString(K_NATIVE_MASK, DEFAULT_MASK));
        b.putString(K_NATIVE_RVA, p.getString(K_NATIVE_RVA, ""));
        b.putBoolean(K_CFG_WRITTEN, p.getBoolean(K_CFG_WRITTEN, true));
        return b;
    }

    /** 设置页启动时调用：把当前值（含默认值）落盘并打上 cfg_written，让游戏侧 prefs 通道可用。 */
    public static void ensureWritten(Context ctx) {
        SharedPreferences p = uiPrefs(ctx);
        if (p.getBoolean(K_CFG_WRITTEN, false)) {
            return;
        }
        p.edit()
                .putBoolean(K_CFG_WRITTEN, true)
                .putBoolean(K_INTEGRITY, p.getBoolean(K_INTEGRITY, true))
                .putBoolean(K_PAY, p.getBoolean(K_PAY, true))
                .putBoolean(K_COMPLIANCE, p.getBoolean(K_COMPLIANCE, true))
                .putBoolean(K_PUSH_BY_ID, p.getBoolean(K_PUSH_BY_ID, false))
                .putBoolean(K_NATIVE, p.getBoolean(K_NATIVE, true))
                .putBoolean(K_ALL_PROCESSES, p.getBoolean(K_ALL_PROCESSES, false))
                .putBoolean(K_STRICT_VERIFY, p.getBoolean(K_STRICT_VERIFY, false))
                .putString(K_NATIVE_MASK, p.getString(K_NATIVE_MASK, DEFAULT_MASK))
                .putString(K_NATIVE_RVA, p.getString(K_NATIVE_RVA, ""))
                .apply();
    }

    /** 设置界面里调用，进程内普通 prefs。 */
    public static SharedPreferences uiPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void normalize() {
        if (nativeMask == null || nativeMask.length() != NativePatcher.DEFAULT_RVAS.length) {
            nativeMask = DEFAULT_MASK;
        }
        for (int i = 0; i < nativeMask.length(); i++) {
            char ch = nativeMask.charAt(i);
            if (ch != '0' && ch != '1') {
                nativeMask = DEFAULT_MASK;
                break;
            }
        }
        if (nativeRva == null) {
            nativeRva = "";
        }
    }

    public static boolean[] maskToArray(String mask) {
        boolean[] out = new boolean[NativePatcher.DEFAULT_RVAS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = i < mask.length() && mask.charAt(i) == '1';
        }
        return out;
    }

    /** 把 "0xadd01c,0xbe5a94,..." 解析成 int[]，失败返回 null。 */
    public static int[] parseRvaList(String s) {
        if (s == null) {
            return null;
        }
        String[] toks = s.trim().split("[,\\s;]+");
        if (toks.length < NativePatcher.DEFAULT_RVAS.length) {
            return null;
        }
        int[] out = new int[NativePatcher.DEFAULT_RVAS.length];
        for (int i = 0; i < out.length; i++) {
            String tk = toks[i].trim();
            if (tk.startsWith("0x") || tk.startsWith("0X")) {
                tk = tk.substring(2);
            }
            try {
                out[i] = Integer.parseInt(tk, 16);
            } catch (Throwable t) {
                return null;
            }
        }
        return out;
    }
}
