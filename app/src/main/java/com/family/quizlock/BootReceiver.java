package com.family.quizlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：标记"开机即弹题"，然后拉起守护服务。
 * 对应原版"断电重启也躲不掉，一开机题目就出现"。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Store.setBootQuizPending(context, true);
            WatchMonitorService.start(context);
        }
    }
}
