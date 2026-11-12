package com.cownow.rsmod;

import com.cownow.rsmod.util.HookUtil;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * main1.java 的 TOGGLE2 段：GzPay / TaptapPay 支付直接回调成功。
 *
 * 原脚本调用链路：
 *   payOfProductID / InitPurchaseLogic / QueryProductDetails
 *     -> Global_LastPid = arg0
 *     -> UnitySendMessage("GzPay", "paySuccess", "")
 *     -> UnitySendMessage("GzPay", "payPushReward", "")
 *     -> (TOGGLE4) UnitySendMessage("GzPay", "payPushRewardOfInAppID", pid)
 *     -> ret(cb, null)
 */
public final class PayBypass {

    public static final String CLS_GZPAY = "com.gsj.GzPay";
    public static final String CLS_TAPPAY = "com.gsj.pay.TaptapPay";
    public static final String CLS_UNITY = "com.unity3d.player.UnityPlayer";

    private static volatile String lastPid = "";
    private static Method unitySend;

    private PayBypass() {
    }

    public static void install(ClassLoader cl) {
        resolveUnitySender(cl);

        final XC_MethodHook payHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object pid = param.args != null && param.args.length > 0 ? param.args[0] : null;
                lastPid = pid == null ? "" : pid.toString();
                fakePay(lastPid);
                HookUtil.setNeutral(param);
            }
        };

        // 兼容两家支付 SDK：同名方法各挂一遍
        hookPay(CLS_GZPAY, cl, "payOfProductID", payHook);
        hookPay(CLS_TAPPAY, cl, "payOfProductID", payHook);

        final Class<?> taptap = XposedHelpers.findClassIfExists(CLS_TAPPAY, cl);
        if (taptap != null) {
            XC_MethodHook replay = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    fakePay(lastPid);
                    HookUtil.setNeutral(param);
                }
            };
            HookUtil.hookAll(taptap, "InitPurchaseLogic", replay);
            HookUtil.hookAll(taptap, "QueryProductDetails", replay);
        }
    }

    private static void hookPay(String cls, ClassLoader cl, String method, XC_MethodHook hook) {
        Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
        if (c == null) {
            HookUtil.log("支付类缺失: " + cls);
            return;
        }
        int n = HookUtil.hookAll(c, method, hook);
        HookUtil.log("pay hook " + cls + "#" + method + " x" + n);
    }

    /** 原脚本 sendUnity()：反射 UnityPlayer.UnitySendMessage(String,String,String)。 */
    private static void resolveUnitySender(ClassLoader cl) {
        Class<?> up = XposedHelpers.findClassIfExists(CLS_UNITY, cl);
        if (up == null) {
            HookUtil.log("UnityPlayer 缺失，无法回发支付消息");
            return;
        }
        Method best = XposedHelpers.findMethodExactIfExists(up, "UnitySendMessage",
                String.class, String.class, String.class);
        if (best == null) {
            for (Method m : up.getDeclaredMethods()) {
                if (m.getName().equals("UnitySendMessage") && m.getParameterCount() == 3) {
                    best = m;
                    break;
                }
            }
        }
        if (best != null) {
            try {
                best.setAccessible(true);
                unitySend = best;
                HookUtil.log("UnitySendMessage ready: " + best);
            } catch (Throwable t) {
                HookUtil.log("UnitySendMessage setAccessible fail " + t);
            }
        }
    }

    public static void sendUnity(String obj, String method, String arg) {
        Method m = unitySend;
        if (m == null) {
            return;
        }
        try {
            m.invoke(null, obj, method, arg);
        } catch (Throwable t) {
            HookUtil.log("UnitySendMessage(" + obj + "," + method + ") FAIL " + t);
        }
    }

    /** 伪造支付成功：直接通知 Unity 侧结算，跳过真实计费。 */
    public static void fakePay(String pid) {
        String p = pid == null ? "" : pid;
        sendUnity("GzPay", "paySuccess", "");
        sendUnity("GzPay", "payPushReward", "");
        if (Config.pushRewardById && p.length() > 0) {
            sendUnity("GzPay", "payPushRewardOfInAppID", p);
        }
        HookUtil.log("fakePay pid=" + p);
    }

    public static String lastPid() {
        return lastPid;
    }
}
