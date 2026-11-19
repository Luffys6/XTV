package com.family.quizlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * 守护服务（前台服务）：
 *  - 每秒累计一次"屏幕亮着"的秒数（弹题期间暂停累计）
 *  - 累计到设定间隔（默认 10 分钟）→ 弹出答题悬浮窗
 *  - 开机自启动时若标记了 bootQuizPending，则开机直接弹题
 */
public class WatchMonitorService extends Service {

    private static final String TAG = "WatchMonitor";
    private static final String CHANNEL_ID = "watch";

    /** 供 MainActivity 展示运行状态 */
    public static volatile boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean screenOn = true;
    private boolean ticking = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            screenOn = Intent.ACTION_SCREEN_ON.equals(action);
        }
    };

    /** 每天第一次 tick 时校对日期（检测系统时间回拨） */
    private int lastTouchDay = -1;
    /** 上一拍悬浮窗是否还开着：关掉的那一拍要做"下一轮计时"清零 */
    private boolean overlayWasActive = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int day = (int) License.today();
            if (day != lastTouchDay) {
                lastTouchDay = day;
                License.touch(WatchMonitorService.this);
            }
            // 悬浮窗刚关闭（答完题解锁 / 家长通道验证通过 / 其它方式关掉）：
            // 本段计时清零，从这一刻开始重新计下一轮，避免残留秒数让孩子刚解锁就又被弹题。
            if (QuizOverlay.isActive()) {
                overlayWasActive = true;
            } else if (overlayWasActive) {
                overlayWasActive = false;
                Store.setWatchedSeconds(WatchMonitorService.this, 0L);
            }
            // 总开关关闭 / 授权到期时：不计时、不弹题（服务保持运行，随时可再开启）
            if (Store.enabled(WatchMonitorService.this) && License.isUsable(WatchMonitorService.this)
                    && screenOn && !QuizOverlay.isActive()) {
                long total = Store.watchedSeconds(WatchMonitorService.this) + 1;
                long limit = Store.intervalMinutes(WatchMonitorService.this) * 60L;
                if (total >= limit) {
                    Store.setWatchedSeconds(WatchMonitorService.this, 0L);
                    showQuiz();
                } else {
                    Store.setWatchedSeconds(WatchMonitorService.this, total);
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
        // 首次安装（开机自启也算首次使用）时发放 30 天免费试用
        License.init(this);
        // 开机自启时也让手机管理面板可用
        HttpAdminServer.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        startForeground(1, buildNotification());
        if (!ticking) {
            ticking = true;
            handler.post(tick);
        }
        boolean pendingBoot = Store.bootQuizPending(this);
        Store.setBootQuizPending(this, false);
        if (pendingBoot && Store.enabled(this)) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showQuiz();
                }
            }, 1500);
        }
        // 服务拉起时检查"每日离线缓存更新"（当天已更新过则为空操作）
        AiQuestionClient.dailyRefreshAsync(this);
        return START_STICKY;
    }

    private void showQuiz() {
        if (!License.isUsable(this)) {
            Log.i(TAG, "使用期限已到，跳过弹题");
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            QuizOverlay.start(this);
        } else {
            Log.w(TAG, "无悬浮窗权限，无法弹题。请在 adb 执行：appops set " + getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow");
        }
    }

    private Notification buildNotification() {
        String title = "学习小卫士运行中";
        String text = "看电视到点弹题，答对 " + Store.requiredCorrect(this) + " 题解锁";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "学习小卫士守护", NotificationManager.IMPORTANCE_MIN);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, WatchMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, WatchMonitorService.class));
    }
}
