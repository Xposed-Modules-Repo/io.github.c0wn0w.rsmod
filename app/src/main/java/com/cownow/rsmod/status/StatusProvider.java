package com.cownow.rsmod.status;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import com.cownow.rsmod.Config;

/**
 * 模块进程里的两个接口：
 *   - heartbeat：被注入的进程上报“我起来了 + 我用的配置来源”
 *   - config   ：游戏进程读不到 XSharedPreferences 时，用这里同步取一次真实配置
 *
 * heartbeat 用 Binder.getCallingUid() 反查调用方包名，只认游戏和模块自身；
 * config 只读、只回配置，额外放行 shell/root 方便用 adb 排查。
 */
public class StatusProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (StatusStore.METHOD_CONFIG.equals(method)) {
            return serveConfig();
        }
        return serveHeartbeat(method, arg, extras);
    }

    private Bundle serveConfig() {
        Bundle out;
        int uid = Binder.getCallingUid();
        if (uid == Process.SHELL_UID || uid == Process.ROOT_UID || uid == Process.SYSTEM_UID) {
            return Config.readForProvider(getContext());
        }
        String caller = callerPackage();
        if (caller == null
                || (!StatusStore.MODULE_PKG.equals(caller) && !StatusStore.GAME_PKG.equals(caller))) {
            out = new Bundle();
            out.putBoolean("ok", false);
            out.putString("reason", "unauthorized caller");
            return out;
        }
        return Config.readForProvider(getContext());
    }

    private Bundle serveHeartbeat(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!StatusStore.METHOD_HEARTBEAT.equals(method)) {
            out.putBoolean("ok", false);
            return out;
        }
        String caller = callerPackage();
        if (caller == null) {
            out.putBoolean("ok", false);
            out.putString("reason", "unknown caller");
            return out;
        }
        if (!StatusStore.MODULE_PKG.equals(caller) && !StatusStore.GAME_PKG.equals(caller)) {
            out.putBoolean("ok", false);
            out.putString("reason", "unauthorized caller " + caller);
            return out;
        }

        String process = arg;
        String cfg = "";
        if (extras != null) {
            if (process == null) {
                process = extras.getString(StatusStore.EXTRA_PROCESS);
            }
            cfg = extras.getString(StatusStore.EXTRA_CFG);
        }
        StatusStore.record(getContext(), caller, process,
                StatusStore.MODULE_PKG.equals(caller), cfg);

        out.putBoolean("ok", true);
        out.putString("pkg", caller);
        out.putLong("time", System.currentTimeMillis());
        return out;
    }

    /** 由调用方 uid 反查包名；系统/shell 调用返回 null。 */
    private String callerPackage() {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        int uid;
        try {
            uid = Binder.getCallingUid();
        } catch (Throwable t) {
            return null;
        }
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID || uid == Process.ROOT_UID) {
            return null;
        }
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) {
                return null;
            }
            for (String p : pkgs) {
                if (StatusStore.MODULE_PKG.equals(p) || StatusStore.GAME_PKG.equals(p)) {
                    return p;
                }
            }
            return pkgs.length > 0 ? pkgs[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
