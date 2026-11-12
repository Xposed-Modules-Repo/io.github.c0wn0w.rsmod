package com.cownow.rsmod.status;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 被注入进程侧的“我起来了 + 我用的配置来源”上报。
 *
 * 两条通道：
 *   1) ContentResolver.call 打模块的 StatusProvider（首选，纯 IPC）
 *   2) 显式广播 + 显式组件名 打 StatusReceiver（包可见性受限时兜底）
 * 两条都失败也不影响补丁本身，只影响设置页的状态显示。
 */
public final class HookReporter {

    /** 本进程是否被框架注入（由 XposedInit 在自身作用域被勾选时置位）。 */
    public static volatile boolean selfInjected = false;

    private HookReporter() {
    }

    public static void report(Context ctx, String pkg, String process, boolean self) {
        report(ctx, pkg, process, self, "");
    }

    public static void report(Context ctx, String pkg, String process, boolean self, String cfg) {
        if (ctx == null) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putString(StatusStore.EXTRA_PKG, pkg);
        extras.putString(StatusStore.EXTRA_PROCESS, process);
        extras.putBoolean(StatusStore.EXTRA_SELF, self);
        extras.putString(StatusStore.EXTRA_CFG, cfg == null ? "" : cfg);

        try {
            ctx.getContentResolver().call(StatusStore.HEARTBEAT_URI,
                    StatusStore.METHOD_HEARTBEAT, process, extras);
            return;
        } catch (Throwable ignored) {
        }

        try {
            Intent i = new Intent(StatusStore.ACTION_HEARTBEAT);
            i.setComponent(new ComponentName(StatusStore.MODULE_PKG, StatusStore.RECEIVER));
            i.putExtra(StatusStore.EXTRA_PKG, pkg);
            i.putExtra(StatusStore.EXTRA_PROCESS, process);
            i.putExtra(StatusStore.EXTRA_SELF, self);
            i.putExtra(StatusStore.EXTRA_CFG, cfg == null ? "" : cfg);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** 当前进程名，取不到就返回包名。 */
    public static String processName(Context ctx) {
        try {
            String p = android.app.Application.getProcessName();
            if (p != null && !p.isEmpty()) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        return ctx == null ? "" : ctx.getPackageName();
    }
}
