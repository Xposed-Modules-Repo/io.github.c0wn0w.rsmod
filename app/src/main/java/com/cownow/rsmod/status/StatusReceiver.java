package com.cownow.rsmod.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 心跳备用通道：某些 ROM / targetSdk 上 ContentProvider 会被包可见性拦掉，
 * 这种情况下被注入的进程还会显式发一个广播过来。
 */
public class StatusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !StatusStore.ACTION_HEARTBEAT.equals(intent.getAction())) {
            return;
        }
        String pkg = intent.getStringExtra(StatusStore.EXTRA_PKG);
        String process = intent.getStringExtra(StatusStore.EXTRA_PROCESS);
        boolean self = intent.getBooleanExtra(StatusStore.EXTRA_SELF, false);
        String cfg = intent.getStringExtra(StatusStore.EXTRA_CFG);
        StatusStore.record(context, pkg, process, self, cfg);
    }
}
