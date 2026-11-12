package com.cownow.rsmod;

import com.cownow.rsmod.util.HookUtil;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * main1.java 的 TOGGLE3 段：TapTap 合规 / 防沉迷 / 支付限额全部放行。
 *
 * 原脚本干了两件事：
 *   1) 把判定方法全部 before 拦截并塞返回值（isAccessAllowed / block / showPending / restrictCheck / checkPayLimit）；
 *   2) block / showPending / attach / start / onComplianceBlocked 之后排一次 doForceApprove()，
 *      直接把单例里的审批状态改成“已通过”，并回调 Activity.onComplianceApproved()。
 *
 * 脚本注释提到“用 before + setResult 直接跳过原方法体，避免原方法把状态清空后疯狂 block”，这里保持同样策略。
 */
public final class ComplianceBypass {

    public static final String CLS_MGR = "com.gsj.pay.TapTapComplianceManager";
    public static final String CLS_ACT = "com.turbochilli.rollingsky.AppActivity";
    public static final String CLS_TAPTAP_CMPL = "com.taptap.sdk.compliance.TapTapCompliance";
    public static final String CLS_ANTI = "com.antiaddiction.sdk.AntiAddictionKit";
    public static final String FAKE_OPENID = "lspatch_bypass";

    private static volatile ClassLoader gameCl;

    private ComplianceBypass() {
    }

    public static void install(ClassLoader cl) {
        gameCl = cl;
        Class<?> mgr = XposedHelpers.findClassIfExists(CLS_MGR, cl);
        if (mgr != null) {
            // 准入判定：永远 true
            HookUtil.hookAll(mgr, "isAccessAllowed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });

            // 拦截式阻断：吞掉原方法体，再补一次强制审批
            XC_MethodHook swallow = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookUtil.setNeutral(param);
                    scheduleForceApprove();
                }
            };
            HookUtil.hookAll(mgr, "block", swallow);
            HookUtil.hookAll(mgr, "showPending", swallow);

            // 挂载 / 启动后补审批
            XC_MethodHook after = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleForceApprove();
                }
            };
            HookUtil.hookAll(mgr, "attach", after);
            HookUtil.hookAll(mgr, "start", after);
            HookUtil.log("compliance manager hooked");
        }

        Class<?> act = XposedHelpers.findClassIfExists(CLS_ACT, cl);
        if (act != null) {
            HookUtil.hookAll(act, "onComplianceBlocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleForceApprove();
                }
            });
        }

        // TapTap 官方合规 SDK 的支付限额检查：空实现即可（保持脚本语义）
        Class<?> tapCmpl = XposedHelpers.findClassIfExists(CLS_TAPTAP_CMPL, cl);
        if (tapCmpl != null) {
            HookUtil.hookAll(tapCmpl, "checkPaymentLimit", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookUtil.setNeutral(param);
                }
            });
        }

        // 防沉迷 SDK：不限制、不拦支付
        Class<?> anti = XposedHelpers.findClassIfExists(CLS_ANTI, cl);
        if (anti != null) {
            XC_MethodHook noLimit = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookUtil.setFalse(param);
                }
            };
            HookUtil.hookAll(anti, "restrictCheck", noLimit);
            HookUtil.hookAll(anti, "checkPayLimit", noLimit);
        }

        scheduleForceApprove();
    }

    /** 原脚本 scheduleForceApprove()：立即、800ms、2500ms 各刷一次。 */
    public static void scheduleForceApprove() {
        try {
            HookUtil.onMain(new Runnable() {
                @Override
                public void run() {
                    doForceApprove();
                }
            });
            HookUtil.onMainDelayed(new Runnable() {
                @Override
                public void run() {
                    doForceApprove();
                }
            }, 800L);
            HookUtil.onMainDelayed(new Runnable() {
                @Override
                public void run() {
                    doForceApprove();
                }
            }, 2500L);
        } catch (Throwable t) {
            HookUtil.log("scheduleForceApprove " + t);
        }
    }

    /** 原脚本 doForceApprove()：反射改写单例状态 + 通知 Activity。必须跑在主线程。 */
    public static void doForceApprove() {
        try {
            Class<?> cmg = XposedHelpers.findClassIfExists(CLS_MGR, gameCl);
            if (cmg == null) {
                return;
            }
            Object inst = HookUtil.callStatic(cmg, "getInstance");
            if (inst == null) {
                return;
            }

            HookUtil.setField(inst, "accessAllowed", Boolean.TRUE);
            HookUtil.setField(inst, "approvedOpenId", FAKE_OPENID);
            HookUtil.setField(inst, "startupOpenId", FAKE_OPENID);
            HookUtil.setField(inst, "hostResumed", Boolean.TRUE);
            HookUtil.setField(inst, "hostFocused", Boolean.TRUE);
            HookUtil.setField(inst, "loginInProgress", Boolean.FALSE);
            HookUtil.setField(inst, "complianceInProgress", Boolean.FALSE);
            HookUtil.setField(inst, "approvedWhilePaused", Boolean.FALSE);

            Object gate = HookUtil.getField(inst, "gate");
            if (gate != null) {
                HookUtil.call(gate, "setVisibility", Integer.valueOf(8));
            }

            Object wr = HookUtil.getField(inst, "activityRef");
            Object activity = null;
            if (wr != null) {
                activity = HookUtil.call(wr, "get");
            }
            if (activity == null) {
                return;
            }
            HookUtil.call(activity, "onComplianceApproved");
            HookUtil.log("force approve ok");
        } catch (Throwable t) {
            HookUtil.log("doForceApprove " + t);
        }
    }
}
