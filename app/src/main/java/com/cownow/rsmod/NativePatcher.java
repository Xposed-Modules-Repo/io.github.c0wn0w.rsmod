package com.cownow.rsmod;

import android.content.Context;

import com.cownow.rsmod.util.HookUtil;
import com.cownow.rsmod.util.MemoryMaps;
import com.cownow.rsmod.util.UnsafeMem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * main2.java 的移植：在游戏进程里对 libil2cpp.so 的 .text 做原地指令补丁。
 *
 * 与原脚本的差异只在于“宿主环境”：
 *   - pluginPath/rva.txt  -> 固定候选路径列表（见 RVA_FILES），或模块设置里写的 RVA
 *   - log(TAG,..)/toast   -> XposedBridge.log + 应用内 Toast
 *   - 脚本一次性执行        -> 后台线程轮询等待 libil2cpp 加载完成（最多 120s）
 * 补丁字节、RVA、校验首指令、探测段全部沿用 main2.java 的原始数值。
 */
public final class NativePatcher implements Runnable {

    public static final String SO_NAME = "libil2cpp.so";

    // 默认 RVA（当前 APK 版本）；rva.txt 或模块设置可覆盖
    public static final int[] DEFAULT_RVAS = new int[]{
            0xadd01c, 0xbe5a94, 0xbe5d64,
            0xeb383c, 0xeb36c0, 0xadcd88,
            0xadc6e4, 0xadc76c, 0xbe5970
    };

    public static final String[] TARGET_NAMES = new String[]{
            "PropsShield::TakeShield",
            "PropUtil::Set_Shield",
            "PropUtil::Set_Levitate",
            "PropFloating::ResetFloating",
            "PropFloating::OnTriggerExit",
            "PropsShield::resetShield",
            "PropsShield::get_IsUseingShield",
            "PropsShield::get_IsTrigger",
            "PropUtil::get_Get_Shield"
    };

    /**
     * 各目标函数在本次版本的首指令，仅用于“提示” RVA 是否仍有效；0 表示未知。
     * 注意：main2.java 里这份常量其实是死代码（循环里算的是补丁体自己的首指令），
     * 所以默认只打日志；打开 strictVerify 才会因不一致而跳过写入。
     */
    private static final long[] EXPECT_HEAD = new long[]{
            0xF40F1EF8L,  // TakeShield
            0xFA0F1BF8L,  // Set_Shield
            0xFA0F1BF8L,  // Set_Levitate
            0xF40F1EF8L,  // ResetFloating
            0xA9BE53F5L,  // OnTriggerExit
            0xF40F1EF8L,  // resetShield
            0L,           // get_IsUseingShield（脚本未给首指令）
            0L,           // get_IsTrigger     （脚本未给首指令）
            0xF81E0FF4L   // get_Get_Shield    （取自 PROBE_ORIG 前 4 字节）
    };

    // arm64 补丁体（小端）
    private static final byte[] RET_BYTES = new byte[]{-64, 3, 95, -42};                     // ret
    private static final byte[] TRUE_BYTES = new byte[]{32, 0, -128, 82, -64, 3, 95, -42};   // mov w0,#1 ; ret
    private static final byte[] BIG_BYTES = new byte[]{-32, -31, -124, 82, -64, 3, 95, -42}; // movz w0,#9999 ; ret
    private static final int SHIELD_FAKE = 9999;

    // 定位校验点：PropUtil::get_Get_Shield
    private static final int PROBE_RVA = 0xbe5970;
    private static final byte[] PROBE_ORIG = new byte[]{-12, 15, 30, -8, -13, 123, 1, -87};
    private static final long RET_U32 = 0xD65F03C0L;

    private static final int WAIT_LOOPS = 120;
    private static final long WAIT_GAP_MS = 1000L;

    /** rva.txt 候选路径：按顺序取第一个存在的（行内格式：9 个十六进制，逗号或空格分隔）。 */
    public static final String[] RVA_FILES = new String[]{
            "/sdcard/Rsmod/rva.txt",
            "/storage/emulated/0/Rsmod/rva.txt",
            "/sdcard/Android/data/" + Config.GAME_PKG + "/files/rva.txt",
            "/data/data/" + Config.GAME_PKG + "/files/rva.txt",
            "/data/user/0/" + Config.GAME_PKG + "/files/rva.txt",
    };

    private final Context ctx;

    private NativePatcher(Context ctx) {
        this.ctx = ctx;
    }

    public static void start(Context ctx) {
        Thread t = new Thread(new NativePatcher(ctx), "RsMod-Native");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
        HookUtil.log("native patcher thread started");
    }

    @Override
    public void run() {
        try {
            if (!UnsafeMem.init()) {
                HookUtil.log("Unsafe 不可用: " + UnsafeMem.error());
                HookUtil.toast(ctx, "RsMod: Unsafe 不可用");
                return;
            }
            int[] rvas = resolveRvas();
            boolean[] enabled = Config.maskToArray(Config.nativeMask);

            long base = waitForBase();
            if (base == 0L) {
                HookUtil.log("未定位到 il2cpp，maps:" + MemoryMaps.describe(SO_NAME));
                HookUtil.toast(ctx, "RsMod: 未定位到 il2cpp");
                return;
            }
            HookUtil.log("il2cpp base=0x" + Long.toHexString(base));

            int changed = 0;
            int skipped = 0;
            for (int i = 0; i < rvas.length; i++) {
                if (!enabled[i]) {
                    HookUtil.log("[关闭] " + TARGET_NAMES[i]);
                    skipped++;
                    continue;
                }
                if (patchOne(base, rvas, i)) {
                    changed++;
                } else {
                    skipped++;
                }
            }

            dumpHeads(base, rvas);
            HookUtil.log("patch done changed=" + changed + " skipped=" + skipped);
            HookUtil.toast(ctx, "RsMod: " + changed + " hooks success");
        } catch (Throwable t) {
            HookUtil.log("native patcher fatal " + t);
        }
    }

    private long waitForBase() {
        for (int i = 0; i < WAIT_LOOPS; i++) {
            long cand = MemoryMaps.decBase(SO_NAME);
            if (cand != 0L) {
                byte[] probe = UnsafeMem.read(cand + PROBE_RVA, PROBE_ORIG.length);
                boolean ok = bytesEqual(probe, PROBE_ORIG) || UnsafeMem.readU32(cand + PROBE_RVA) == RET_U32;
                if (ok) {
                    return cand;
                }
                HookUtil.log("候选中枢 0x" + Long.toHexString(cand) + " 探测不匹配，继续等待");
            }
            try {
                Thread.sleep(WAIT_GAP_MS);
            } catch (InterruptedException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private boolean patchOne(long base, int[] rvas, int idx) {
        long addr = base + rvas[idx];
        byte[] patch = patchFor(idx);
        long head = leU32(patch);
        long cur = UnsafeMem.readU32(addr);
        if (cur < 0) {
            HookUtil.log("[FAIL] 读取失败 " + TARGET_NAMES[idx] + " @" + Long.toHexString(addr));
            return false;
        }
        if (cur == head) {
            HookUtil.log("[已补丁] " + TARGET_NAMES[idx]);
            return false;
        }
        long expect = EXPECT_HEAD[idx];
        if (expect != 0L && cur != expect) {
            String msg = TARGET_NAMES[idx] + " 首指令与脚本记录不一致 cur=0x"
                    + Long.toHexString(cur) + " expect=0x" + Long.toHexString(expect);
            if (Config.strictVerify) {
                HookUtil.log("[SKIP] " + msg + "（strictVerify 开启，游戏版本可能已变，改 rva.txt）");
                return false;
            }
            HookUtil.log("[WARN] " + msg + "（按脚本原策略继续写入）");
        }
        if (rvas[idx] < 0x1000 || rvas[idx] > 0x2a00000L || cur == 0L) {
            HookUtil.log("[SKIP] " + TARGET_NAMES[idx] + " 偏移越界 rva=0x" + Integer.toHexString(rvas[idx]));
            return false;
        }
        if (!UnsafeMem.write(addr, patch)) {
            HookUtil.log("[FAIL] 写入失败 " + TARGET_NAMES[idx] + " " + UnsafeMem.error());
            return false;
        }
        long after = UnsafeMem.readU32(addr);
        boolean ok = after == head;
        HookUtil.log((ok ? "[OK] " : "[VERIFY-FAIL] ") + TARGET_NAMES[idx]
                + " rva=0x" + Integer.toHexString(rvas[idx])
                + " " + patchDesc(idx)
                + " head=0x" + Long.toHexString(after));
        return ok;
    }

    private void dumpHeads(long base, int[] rvas) {
        StringBuilder sb = new StringBuilder("final heads:");
        for (int i = 0; i < rvas.length; i++) {
            sb.append("\n   ").append(TARGET_NAMES[i]).append(" = 0x")
                    .append(Long.toHexString(UnsafeMem.readU32(base + rvas[i])));
        }
        HookUtil.log(sb.toString());
    }

    // ---------------- 补丁体选择 ----------------

    private static byte[] patchFor(int idx) {
        String nm = TARGET_NAMES[idx];
        if (nm.contains("get_Get_Shield")) {
            return BIG_BYTES;                 // 数量 -> 9999
        }
        if (nm.contains("get_IsUseingShield") || nm.contains("get_IsTrigger")) {
            return TRUE_BYTES;                // bool -> true
        }
        return RET_BYTES;                     // void -> ret
    }

    private static String patchDesc(int idx) {
        String nm = TARGET_NAMES[idx];
        if (nm.contains("get_Get_Shield")) {
            return "movz w0,#" + SHIELD_FAKE + " ; ret";
        }
        if (nm.contains("get_IsUseingShield") || nm.contains("get_IsTrigger")) {
            return "mov w0,#1 ; ret";
        }
        return "ret";
    }

    // ---------------- RVA 来源 ----------------

    private int[] resolveRvas() {
        // 1) 模块设置里填的 RVA 优先级最高
        int[] fromPrefs = Config.parseRvaList(Config.nativeRva);
        if (fromPrefs != null) {
            HookUtil.log("RVA 来源: 模块设置");
            return fromPrefs;
        }
        // 2) rva.txt
        for (String path : RVA_FILES) {
            int[] parsed = readRvaFile(path);
            if (parsed != null) {
                HookUtil.log("RVA 来源: " + path);
                return parsed;
            }
        }
        HookUtil.log("RVA 来源: 内置默认值（当前 APK 版本）");
        return DEFAULT_RVAS.clone();
    }

    private int[] readRvaFile(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            return null;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String ln = br.readLine();
            if (ln == null) {
                return null;
            }
            int[] parsed = Config.parseRvaList(ln);
            if (parsed == null) {
                HookUtil.log("rva.txt 项数不足或格式错误: " + path);
            }
            return parsed;
        } catch (Throwable t) {
            HookUtil.log("读 rva.txt 失败 " + path + " " + t);
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------- 小工具 ----------------

    private static long leU32(byte[] b) {
        return ((b[3] & 0xFFL) << 24) | ((b[2] & 0xFFL) << 16) | ((b[1] & 0xFFL) << 8) | (b[0] & 0xFFL);
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
