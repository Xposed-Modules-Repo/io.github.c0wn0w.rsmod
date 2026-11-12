package com.cownow.rsmod;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;

/**
 * 纯 JVM 侧自检：确认从 main2.java 搬过来的补丁字节确实解码成预期的 ARM64 指令，
 * 以及 RVA / 掩码解析正确。跑法：./gradlew :app:testDebugUnitTest
 */
public class NativePatchMathTest {

    private static byte[] bytes(String field) throws Exception {
        Field f = NativePatcher.class.getDeclaredField(field);
        f.setAccessible(true);
        return (byte[]) f.get(null);
    }

    private static long leU32(byte[] b) {
        return ((b[3] & 0xFFL) << 24) | ((b[2] & 0xFFL) << 16) | ((b[1] & 0xFFL) << 8) | (b[0] & 0xFFL);
    }

    @Test
    public void retIsRetInstruction() throws Exception {
        assertEquals(0xD65F03C0L, leU32(bytes("RET_BYTES")));
    }

    @Test
    public void trueBodyIsMovW0OneThenRet() throws Exception {
        byte[] b = bytes("TRUE_BYTES");
        assertEquals(8, b.length);
        assertEquals(0x52800020L, leU32(new byte[]{b[0], b[1], b[2], b[3]})); // mov w0,#1
        assertEquals(0xD65F03C0L, leU32(new byte[]{b[4], b[5], b[6], b[7]})); // ret
    }

    @Test
    public void bigBodyIsMovzW0_9999ThenRet() throws Exception {
        byte[] b = bytes("BIG_BYTES");
        long movz = leU32(new byte[]{b[0], b[1], b[2], b[3]});
        assertEquals(0x5284E1E0L, movz);
        assertEquals(9999L, (movz >> 5) & 0xFFFFL); // imm16 == 9999
        assertEquals(0xD65F03C0L, leU32(new byte[]{b[4], b[5], b[6], b[7]}));
    }

    @Test
    public void probeBytesMatchScript() throws Exception {
        Field f = NativePatcher.class.getDeclaredField("PROBE_ORIG");
        f.setAccessible(true);
        byte[] probe = (byte[]) f.get(null);
        assertArrayEquals(new byte[]{-12, 15, 30, -8, -13, 123, 1, -87}, probe);
        // 前 4 字节即 get_Get_Shield 首指令
        assertEquals(0xF81E0FF4L, leU32(new byte[]{probe[0], probe[1], probe[2], probe[3]}));
    }

    @Test
    public void defaultRvasAndMaskLineUp() {
        assertEquals(NativePatcher.DEFAULT_RVAS.length, NativePatcher.TARGET_NAMES.length);
        assertEquals(9, NativePatcher.DEFAULT_RVAS.length);
        boolean[] mask = Config.maskToArray(Config.DEFAULT_MASK);
        assertTrue(mask[0]);
        assertFalse(mask[1]);
        assertFalse(mask[2]);
        assertTrue(mask[3]);
        assertEquals(0xadd01c, NativePatcher.DEFAULT_RVAS[0]);
        assertEquals(0xbe5970, NativePatcher.DEFAULT_RVAS[8]);
    }

    @Test
    public void rvaListParsing() {
        int[] parsed = Config.parseRvaList("0xadd01c, 0xbe5a94, 0xbe5d64, 0xeb383c, 0xeb36c0,"
                + " 0xadcd88, 0xadc6e4, 0xadc76c, 0xbe5970");
        assertNotNull(parsed);
        assertEquals(0xbe5970, parsed[8]);

        int[] plain = Config.parseRvaList("add01c be5a94 be5d64 eb383c eb36c0 adcd88 adc6e4 adc76c be5970");
        assertNotNull(plain);
        assertEquals(0xadd01c, plain[0]);

        assertEquals(null, Config.parseRvaList("0x1,0x2"));
        assertEquals(null, Config.parseRvaList("zz,1,2,3,4,5,6,7,8"));
    }
}
