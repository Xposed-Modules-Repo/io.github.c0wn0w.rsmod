import java.io.*;
import java.lang.reflect.*;

// 默认 RVA（当前 APK 版本）；若插件目录下存在 rva.txt 则以其为准
int[] TARGET_RVAS = new int[] {
    0xadd01c, 0xbe5a94, 0xbe5d64,
    0xeb383c, 0xeb36c0, 0xadcd88,
    0xadc6e4, 0xadc76c, 0xbe5970
};

boolean[] ENABLED = new boolean[] { true, false, false, true, true, true, true, true, true };

// 从 rva.txt 覆盖（格式：一行 6 个十六进制，逗号或空格分隔）
void loadRvaOverride() {
    File rf = new File(pluginPath + "/rva.txt");
    if (!rf.exists()) { return; }
    BufferedReader rr = new BufferedReader(new FileReader(rf));
    String ln = rr.readLine();
    rr.close();
    if (ln == null) return;
    String[] toks = ln.trim().split("[,\\s]+");
    if (toks.length < TARGET_RVAS.length) { log(TAG, "rva.txt 项数不足(" + toks.length + "/" + TARGET_RVAS.length + ")，忽略"); return; }
    int[] nr = new int[TARGET_RVAS.length];
    for (int k = 0; k < TARGET_RVAS.length; k++) {
        String tk = toks[k].trim();
        if (tk.startsWith("0x") || tk.startsWith("0X")) tk = tk.substring(2);
        nr[k] = Integer.parseInt(tk, 16);
    }
    TARGET_RVAS = nr;
}

String[] TARGET_NAMES = new String[] {
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

// 各目标函数在本次版本的首指令（用于校验 RVA 是否仍然有效）
// 游戏更新后若这里对不上，插件只会告警跳过，不会写坏进程
long[] EXPECT_HEAD = new long[] {
    0xF40F1EF8L,  // TakeShield        str x20,[sp,#-0x10]!
    0xFA0F1BF8L,  // Set_Shield        str x26,[sp,#-0x10]!
    0xFA0F1BF8L,  // Set_Levitate      str x26,[sp,#-0x10]!
    0xF40F1EF8L,  // ResetFloating     str x20,[sp,#-0x10]!
    0xA9BE53F5L,  // OnTriggerExit     stp x21,x20,[sp,#-0x20]!
    0xF40F1EF8L   // resetShield       str x20,[sp,#-0x10]!
};

long RET_U32 = 0xD65F03C0L;                        // arm64 ret
byte[] RET_BYTES = new byte[] { -64, 3, 95, -42 }; // 小端 C0 03 5F D6
// mov w0,#1 ; ret  （bool 返回值必须显式置 1，光写 ret 的话 w0 是脏值）
byte[] TRUE_BYTES = new byte[] { 32, 0, -128, 82, -64, 3, 95, -42 };
// movz w0,#9999 ; ret  （护盾数量显示成 9999）
int    SHIELD_FAKE   = 9999;
byte[] BIG_BYTES = new byte[] { -32, -31, -124, 82, -64, 3, 95, -42 };

// 该目标用哪种补丁体
byte[] pickPatch(int idx) {
    String nm = TARGET_NAMES[idx];
    if (nm.indexOf("get_Get_Shield") >= 0) return BIG_BYTES;         // 数量 -> 9999
    if (nm.indexOf("get_IsUseingShield") >= 0
        || nm.indexOf("get_IsTrigger") >= 0) return TRUE_BYTES;     // bool -> true
    return RET_BYTES;                                               // void -> ret
}

String patchDesc(int idx) {
    String nm = TARGET_NAMES[idx];
    if (nm.indexOf("get_Get_Shield") >= 0) return "mov w0,#" + SHIELD_FAKE + ";ret";
    if (nm.indexOf("get_IsUseingShield") >= 0
        || nm.indexOf("get_IsTrigger") >= 0) return "mov w0,#1;ret";
    return "ret";
}

long PROBE_RVA = 0xbe5970L;                        // PropUtil::get_Get_Shield
byte[] PROBE_ORIG = new byte[] { -12, 15, 30, -8, -13, 123, 1, -87 };

// ---------- Unsafe ----------
Object gU = null;
Method gGetByte = null;
Method gPutByte = null;
String gErr = "";

void initUnsafe() {
    try {
        Class uc = Class.forName("sun.misc.Unsafe");
        Field uf = uc.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        gU = uf.get(null);
        gGetByte = uc.getMethod("getByte", new Class[] { Long.TYPE });
        gPutByte = uc.getMethod("putByte", new Class[] { Long.TYPE, Byte.TYPE });
    } catch (Throwable e) {
        gErr = "initUnsafe:" + e;
    }
}

byte[] readAt(long addr, int n) {
    if (gGetByte == null) return null;
    try {
        byte[] buf = new byte[n];
        for (int i = 0; i < n; i++) {
            Object o = gGetByte.invoke(gU, new Object[] { Long.valueOf(addr + i) });
            buf[i] = ((Number) o).byteValue();
        }
        return buf;
    } catch (Throwable e) {
        gErr = "R:" + e;
        return null;
    }
}

long readU32(long addr) {
    byte[] q = readAt(addr, 4);
    if (q == null) return -1L;
    return ((q[3] & 0xFFL) << 24) | ((q[2] & 0xFFL) << 16) | ((q[1] & 0xFFL) << 8) | (q[0] & 0xFFL);
}

boolean writeAt(long addr, byte[] data) {
    if (gPutByte == null) return false;
    try {
        for (int i = 0; i < data.length; i++) {
            gPutByte.invoke(gU, new Object[] { Long.valueOf(addr + i), Byte.valueOf(data[i]) });
        }
        return true;
    } catch (Throwable e) {
        gErr = "W:" + e;
        return false;
    }
}

boolean bytesEqual(byte[] arrX, byte[] arrY) {
    if (arrX == null || arrY == null || arrX.length != arrY.length) return false;
    for (int k = 0; k < arrX.length; k++) if (arrX[k] != arrY[k]) return false;
    return true;
}

// ---------- 找 libil2cpp 解密码段 ----------
long findDecBase() {
    long bestBase = 0L;
    long bestSize = 0L;
    BufferedReader br = null;
    try {
        br = new BufferedReader(new FileReader("/proc/self/maps"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.indexOf("libil2cpp.so") < 0) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String[] se = parts[0].split("-");
            if (se.length != 2) continue;
            long segStart = Long.parseLong(se[0], 16);
            long segEnd = Long.parseLong(se[1], 16);
            String perm = parts[1];
            if (perm.indexOf('x') >= 0 && perm.indexOf('w') >= 0 && (segEnd - segStart) > bestSize) {
                bestSize = segEnd - segStart;
                bestBase = segStart;
            }
        }
    } catch (Throwable e) {
        gErr = "maps:" + e;
    } finally {
        if (br != null) try { br.close(); } catch (Throwable x) {}
    }
    return bestBase;
}

// ================= 主流程 =================
loadRvaOverride();
initUnsafe();

if (gGetByte == null) {
    log("无法获取 Unsafe");
} else {
    long base = 0L;
    long cand = 0L;
    for (int i = 0; i < 120; i++) {
        cand = findDecBase();
        if (cand != 0L) {
            byte[] probeBytes = readAt(cand + PROBE_RVA, 8);
            if (bytesEqual(probeBytes, PROBE_ORIG) || readU32(cand + PROBE_RVA) == RET_U32) {
                base = cand;
                break;
            }
        }
        try { Thread.sleep(1000); } catch (Throwable x2) {}
    }

    if (base == 0L) {
        toast("RsMod: 未定位到 il2cpp");
    } else {
        int changed = 0;
        int skipped = 0;
        for (int tgtIdx = 0; tgtIdx < TARGET_RVAS.length; tgtIdx++) {
            if (!ENABLED[tgtIdx]) { log(TAG, "[关闭] " + TARGET_NAMES[tgtIdx] + "（见 ENABLED 说明）"); continue; }
            long tgtAddr = base + TARGET_RVAS[tgtIdx];
            byte[] patchData = pickPatch(tgtIdx);
            long expectHead = ((patchData[3] & 0xFFL) << 24) | ((patchData[2] & 0xFFL) << 16)
                            | ((patchData[1] & 0xFFL) << 8) | (patchData[0] & 0xFFL);
            long curInsn = readU32(tgtAddr);
            if (curInsn < 0) { continue; }
            if (curInsn == expectHead) { continue; }
            long offs = TARGET_RVAS[tgtIdx];
            if (offs < 0x1000 || offs > 0x2a00000L || curInsn == 0L) {
                skipped++;
                continue;
            }
            if (writeAt(tgtAddr, patchData)) {
                changed++;
            } else {
                log("[FAIL] " + TARGET_NAMES[tgtIdx] + " " + gErr);
            }
        }
        for (int vIdx = 0; vIdx < TARGET_RVAS.length; vIdx++) {
            log(TAG, "   " + TARGET_NAMES[vIdx] + " = 0x" + Long.toHexString(readU32(base + TARGET_RVAS[vIdx])));
        }
        else toast("RsMod: " + changed + " hooks success");
    }
}
