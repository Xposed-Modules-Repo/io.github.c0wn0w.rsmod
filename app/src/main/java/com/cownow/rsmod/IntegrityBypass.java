package com.cownow.rsmod;

import android.os.Process;

import com.cownow.rsmod.util.HookUtil;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * main1.java 的 TOGGLE1 段：MD5 校验 / DLL 指纹 / 自杀式 killProcess 保护。
 *
 * 原脚本：
 *   hookMethodBefore(cMD5, "CheckMd5Code", cb -> ret(cb, null));
 *   hookMethodAfter (cMD5, "getFileMD5",  cb -> ret(cb, ORIG_DLL_MD5));
 *   hookMethodBefore(Process, "killProcess", cb -> { if (arg0 == myPid) ret(cb, null); });
 */
public final class IntegrityBypass {

    // MD5check.MD5_CODE —— 脚本里写死的“正版” libil2cpp 指纹
    public static final String ORIG_DLL_MD5 = "cbcea0fb227b8d425f5d7ba93952e69";
    public static final String CLS_MD5CHECK = "com.turbochilli.rollingsky.util.MD5check";

    private IntegrityBypass() {
    }

    public static void install(final ClassLoader cl) {
        final Class<?> md5 = XposedHelpers.findClassIfExists(CLS_MD5CHECK, cl);
        if (md5 == null) {
            HookUtil.log("MD5check 缺失，跳过完整性绕过");
            return;
        }

        // 校验入口直接放行（返回类型自适配：boolean -> true / void -> null）
        HookUtil.hookAll(md5, "CheckMd5Code", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                HookUtil.setNeutral(param);
                HookUtil.log("CheckMd5Code bypassed");
            }
        });

        // 文件指纹一律返回脚本记录的原始 MD5，阻止“比对失败 -> 自杀”
        HookUtil.hookAll(md5, "getFileMD5", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                HookUtil.setResultTyped(param, ORIG_DLL_MD5);
            }
        });

        // 自己杀自己的调用吞掉；其他 pid 的 killProcess 保持原行为
        Class<?> process = XposedHelpers.findClassIfExists("android.os.Process", cl);
        if (process != null && HookUtil.hookExact(process, "killProcess", new Class<?>[]{int.class}, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object a0 = param.args[0];
                if (a0 instanceof Integer && ((Integer) a0) == Process.myPid()) {
                    param.setResult(null);
                    HookUtil.log("killProcess(self) blocked");
                }
            }
        })) {
            HookUtil.log("killProcess guard installed");
        }
    }
}
