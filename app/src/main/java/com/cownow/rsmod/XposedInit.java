package com.cownow.rsmod;

import android.app.Application;
import android.content.Context;

import com.cownow.rsmod.status.HookReporter;
import com.cownow.rsmod.status.StatusStore;
import com.cownow.rsmod.util.HookUtil;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（assets/xposed_init 里声明的类）。
 *
 * 生命周期对应 bsh 脚本的“进程启动即执行”：
 *   脚本加载          -> handleLoadPackage
 *   脚本里的类解析/挂 hook -> Application.attach 之后（此时 Application Context 可用，Toast 才不炸）
 *
 * 另外维护一份“注入状态”：LSPosed 只按作用域注入，模块自己的进程默认拿不到 Xposed 类，
 * 所以状态不能靠 Class.forName 判断，而是被注入的进程（游戏 / 自身）各上报一次心跳，见 status 包。
 */
public final class XposedInit implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static volatile boolean installed = false;
    private static String modulePath = "";

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath == null ? "" : startupParam.modulePath;
        if (!modulePath.isEmpty()) {
            HookUtil.log("module apk: " + modulePath);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 模块自身被注入（作用域里勾了 RsMod）：只上报状态，绝不装游戏 hook
        if (StatusStore.MODULE_PKG.equals(lpparam.packageName)) {
            HookReporter.selfInjected = true;
            HookUtil.log("module self-injected, process=" + lpparam.processName);
            hookAttach(lpparam, true);
            return;
        }

        if (!Config.GAME_PKG.equals(lpparam.packageName)) {
            return;
        }
        Config.load();
        if (!isTargetProcess(lpparam)) {
            HookUtil.log("skip process " + lpparam.processName);
            return;
        }
        HookUtil.log("handleLoadPackage " + lpparam.packageName + " / " + lpparam.processName
                + " cfg=" + Config.source);
        hookAttach(lpparam, false);
    }

    /**
     * 在 Application.attach 之后拿到可用 Context：
     * 自身 -> 上报“模块已注入”；游戏 -> 上报“游戏已注入”并装 hook。
     */
    private void hookAttach(XC_LoadPackage.LoadPackageParam lpparam, final boolean self) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                    "attach", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = param.args.length > 0 && param.args[0] instanceof Context
                                    ? (Context) param.args[0] : null;
                            if (ctx == null) {
                                return;
                            }
                            if (self) {
                                HookReporter.selfInjected = true;
                                HookUtil.log("self heartbeat sent");
                                HookReporter.report(ctx, ctx.getPackageName(),
                                        HookReporter.processName(ctx), true, "self");
                                return;
                            }
                            if (installed) {
                                return;
                            }
                            installed = true;
                            install(ctx, lpparam.classLoader);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private boolean isTargetProcess(XC_LoadPackage.LoadPackageParam lp) {
        if (Config.allProcesses) {
            return true;
        }
        if (lp.processName == null) {
            return true;
        }
        return Config.GAME_PKG.equals(lp.processName);
    }

    private void install(Context ctx, ClassLoader cl) {
        // 关键一步：先把配置读准（prefs 读不到就走 Provider），再决定装哪些 hook
        try {
            Config.load(ctx);
        } catch (Throwable t) {
            HookUtil.log("Config.load error " + t);
        }
        HookUtil.log("config source=" + Config.source
                + " integrity=" + Config.integrity
                + " pay=" + Config.pay + " compliance=" + Config.compliance
                + " pushRewardById=" + Config.pushRewardById
                + " native=" + Config.nativePatch + " mask=" + Config.nativeMask);

        if (Config.SRC_DEFAULTS.equals(Config.source)) {
            // 两条读取通道都失败：此时用的是脚本默认值（全开），必须让用户看见
            HookUtil.log("!! 配置读取失败（prefs 不可读且 Provider 无响应），已回退默认值");
            HookUtil.toast(ctx, "RsMod: 配置读取失败，已使用默认值");
        }

        if (Config.integrity) {
            try {
                IntegrityBypass.install(cl);
            } catch (Throwable t) {
                HookUtil.log("IntegrityBypass error " + t);
            }
        }

        if (Config.pay) {
            try {
                PayBypass.install(cl);
            } catch (Throwable t) {
                HookUtil.log("PayBypass error " + t);
            }
        }

        if (Config.compliance) {
            try {
                ComplianceBypass.install(cl);
            } catch (Throwable t) {
                HookUtil.log("ComplianceBypass error " + t);
            }
        }

        if (Config.nativePatch) {
            try {
                NativePatcher.start(ctx);
            } catch (Throwable t) {
                HookUtil.log("NativePatcher error " + t);
            }
        }

        HookUtil.log("install end");

        // 上报“已注入 + 本次实际使用的配置来源”，设置页据此显示状态
        try {
            HookReporter.report(ctx, ctx.getPackageName(),
                    HookReporter.processName(ctx), false, Config.source);
        } catch (Throwable t) {
            HookUtil.log("report fail " + t);
        }
    }
}
