package com.family.quizlock;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Activity 已在栈顶时 am start 会走这里（不走 onCreate），
        // adb 传的配置/年级/难度参数也要生效
        handleAdbIntent();
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        QuestionBank.ensure(this);
        handleAdbIntent();
        // 每日离线缓存更新 + 预生成（未配置 AI 时为空操作）
        AiQuestionClient.dailyRefreshAsync(this);
        AiQuestionClient.prefetchAsync(this);

        // 启动局域网管理面板（电视自己当服务器，不需要云端）
        HttpAdminServer.start(this);

        // 使用期限：首次安装自动发放 30 天免费试用；每次启动记录当天日期（检测时间回拨）
        License.init(this);

        // 首次使用：强制设置家长密码（无内置默认密码）
        if (!Store.hasPassword(this)) {
            showSetupPasswordDialog();
        } else if (License.needRenew(this)) {
            // 到期 / 即将到期：每次启动提醒联系管理员续期
            showLicenseDialog(License.isUsable(this) ? "使用期限即将到期" : "使用期限已到");
        }

        // ① 悬浮窗权限
        findViewById(R.id.btnOverlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        // 很多电视没有这个设置页，只能 adb 授权
                        toast("电视上没有该设置页，请用 adb 授权：\nappops set com.family.quizlock SYSTEM_ALERT_WINDOW allow");
                    }
                } else {
                    toast("悬浮窗权限已授予");
                }
            }
        });

        // ② 启动 / 停止守护服务
        findViewById(R.id.btnService).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (WatchMonitorService.running) {
                    WatchMonitorService.stop(MainActivity.this);
                    toast("守护服务已停止");
                } else {
                    WatchMonitorService.start(MainActivity.this);
                    toast("守护服务已启动");
                }
                refresh();
            }
        });

        // 弹题间隔（步进 5 分钟，范围 5~30）
        findViewById(R.id.btnIntervalMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setIntervalMinutes(MainActivity.this, Store.intervalMinutes(MainActivity.this) - 5);
                refresh();
            }
        });
        findViewById(R.id.btnIntervalPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setIntervalMinutes(MainActivity.this, Store.intervalMinutes(MainActivity.this) + 5);
                refresh();
            }
        });

        // 需答对题数（范围 1~10，最少 1 题）
        findViewById(R.id.btnRequiredMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setRequiredCorrect(MainActivity.this, Store.requiredCorrect(MainActivity.this) - 1);
                refresh();
            }
        });
        findViewById(R.id.btnRequiredPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setRequiredCorrect(MainActivity.this, Store.requiredCorrect(MainActivity.this) + 1);
                refresh();
            }
        });

        // ③ 开启 / 关闭答题守护（需家长密码）
        findViewById(R.id.btnToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToggleDialog();
            }
        });

        // 年级选择（1~9 年级，AI 出题按年级适配难度）
        findViewById(R.id.btnGradeMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setGrade(MainActivity.this, Store.grade(MainActivity.this) - 1);
                QuestionBank.onGradeChanged(MainActivity.this);
                AiQuestionClient.prefetchDebounced(MainActivity.this);
                refresh();
            }
        });
        findViewById(R.id.btnGradePlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setGrade(MainActivity.this, Store.grade(MainActivity.this) + 1);
                QuestionBank.onGradeChanged(MainActivity.this);
                AiQuestionClient.prefetchDebounced(MainActivity.this);
                refresh();
            }
        });

        // 学期切换（上/下学期题库独立，AI 也按学期出题）
        findViewById(R.id.btnSemesterToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setSemester(MainActivity.this, Store.semester(MainActivity.this) >= 2 ? 1 : 2);
                QuestionBank.onSemesterChanged(MainActivity.this);
                AiQuestionClient.prefetchDebounced(MainActivity.this);
                refresh();
            }
        });

        // 题目难度（1 易 / 2 中 / 3 难，按年级各存一份）
        findViewById(R.id.btnDiffMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setDifficulty(MainActivity.this, Store.difficulty(MainActivity.this) - 1);
                QuestionBank.onDifficultyChanged(MainActivity.this);
                AiQuestionClient.prefetchDebounced(MainActivity.this);
                refresh();
            }
        });
        findViewById(R.id.btnDiffPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setDifficulty(MainActivity.this, Store.difficulty(MainActivity.this) + 1);
                QuestionBank.onDifficultyChanged(MainActivity.this);
                AiQuestionClient.prefetchDebounced(MainActivity.this);
                refresh();
            }
        });

        // 立即测试弹题
        findViewById(R.id.btnQuizNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Store.enabled(MainActivity.this)) {
                    toast("答题守护已关闭，请先输入密码开启");
                    return;
                }
                if (!License.isUsable(MainActivity.this)) {
                    toast("使用期限已到（" + License.expireText(MainActivity.this) + "），请先续期");
                    showLicenseDialog("使用期限已到");
                    return;
                }
                if (Settings.canDrawOverlays(MainActivity.this)) {
                    QuizOverlay.start(MainActivity.this);
                } else {
                    toast("请先授予悬浮窗权限");
                }
            }
        });

        // 错题本：电视上不做列表（遥控器翻着累），改为手机扫码查看 / 移出 / 清空
        findViewById(R.id.btnWrongQr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!HttpAdminServer.isRunning()) {
                    toast("管理面板未启动，请先启动守护服务");
                    return;
                }
                showQrFullscreen("扫码看错题本",
                        "手机连同一个 WiFi → 扫码 → 输入家长密码 → 自动打开错题本\n可逐题移出，也可一键清空",
                        HttpAdminServer.url() + "#wrong");
            }
        });

        // 使用期限：查看设备码 / 输入激活码续期（未启用授权限制时提示永久使用）
        findViewById(R.id.btnLicense).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!License.ENFORCED) {
                    toast("当前版本未启用使用期限限制：永久使用，无需激活");
                    return;
                }
                showLicenseDialog(null);
            }
        });

        // 清理 AI 缓存（释放内存和存储空间；下次启动会自动重新生成当天缓存）
        findViewById(R.id.btnClearCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuestionBank.clearAiCache(MainActivity.this);
                toast("题库已恢复为内置题（AI 出的题已清空，启动 App 时会重新补充）");
                refresh();
            }
        });

        // 扫码卡片：按 OK 弹出全屏大二维码（电视离得远，小图不好扫）
        final View qrCard = findViewById(R.id.qrCard);
        qrCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!HttpAdminServer.isRunning()) {
                    toast("管理面板未启动，请先启动守护服务");
                    return;
                }
                showQrFullscreen("扫码连接管理面板",
                        "手机连同一个 WiFi → 微信扫一扫 / 相机扫码 → 浏览器打开 → 输入家长密码",
                        HttpAdminServer.url());
            }
        });
        qrCard.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.bg_qr_card_focus : R.drawable.bg_qr_card);
            }
        });

        // 遥控器支持：进入页面自动聚焦第一个按钮，开机后直接按 OK 即可
        findViewById(R.id.btnOverlay).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        License.touch(this);
        refresh();
        // 每秒刷新状态（服务运行状态/已观看时长），离开页面时停止
        uiHandler.postDelayed(uiRefresher, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiRefresher);
    }

    private final Handler uiHandler = new Handler();
    private final Runnable uiRefresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        // 每次刷新都 touch：开机时钟未校准时试用处于"待启动"，一旦系统时间校准（NTP/联网），
        // 1 秒内自动开始 30 天试用，状态灯同步变绿
        License.touch(this);
        boolean overlay = Settings.canDrawOverlays(this);
        long watched = Store.watchedSeconds(this);

        // 三个指示灯：正常绿字，异常红字（一眼看出哪里没配好）
        boolean on = Store.enabled(this);
        setChip((TextView) findViewById(R.id.tvStLock), "答题守护",
                on ? "已开启" : "已关闭", on);
        setChip((TextView) findViewById(R.id.tvStService), "守护服务",
                WatchMonitorService.running ? "运行中" : "未运行", WatchMonitorService.running);
        setChip((TextView) findViewById(R.id.tvStOverlay), "悬浮窗权限",
                overlay ? "已授予" : "未授予", overlay);
        boolean usable = License.isUsable(this);
        // 到期前 7 天内用黄字提示，正常绿、到期红
        int left = License.daysLeft(this);
        boolean warn = usable && !License.isPermanent(this) && left >= 0 && left <= 7;
        setChip((TextView) findViewById(R.id.tvStLicense), "使用期限",
                License.summary(this), usable, warn);
        // 永久使用（未启用授权限制，或已激活永久码）时，"使用期限 / 续期"入口没有意义，直接隐藏
        findViewById(R.id.btnLicense).setVisibility(
                License.isPermanent(this) ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tvWatched)).setText(
                "本段已观看 " + (watched / 60) + " 分 " + (watched % 60) + " 秒");

        ((TextView) findViewById(R.id.tvInterval)).setText(Store.intervalMinutes(this) + " 分钟");
        ((TextView) findViewById(R.id.tvRequired)).setText("答对 " + Store.requiredCorrect(this) + " 题");
        ((TextView) findViewById(R.id.tvGrade)).setText(
                Store.grade(this) <= 6
                        ? "小学 " + Store.grade(this) + " 年级"
                        : "初中 " + (Store.grade(this) - 6) + " 年级");
        ((TextView) findViewById(R.id.tvSemester)).setText(Store.semesterName(Store.semester(this)));
        ((TextView) findViewById(R.id.tvDiff)).setText(Store.difficultyName(Store.difficulty(this)));
        String aiError = Store.aiLastError(this);
        String aiOk = Store.aiLastOk(this);
        ((TextView) findViewById(R.id.tvAiStatus)).setText(AiQuestionClient.isConfigured(this)
                ? "AI 出题：已接入（" + AiQuestionClient.gradeLabel(Store.grade(this))
                        + Store.semesterName(Store.semester(this))
                        + "，题库 " + QuestionBank.size() + " 题）"
                        + (aiOk.isEmpty() ? "" : "\n最近一次成功：" + aiOk)
                        + (aiError.isEmpty() ? "" : "\n最近一次失败：" + shortError(aiError))
                : "AI 出题：未接入，使用内置题库");
        int localTotal = QuestionBank.size() + QuestionBank.customCount();
        int total = Store.statTotal(this);
        int correct = Store.statCorrect(this);
        int rate = total == 0 ? 0 : Math.round(correct * 100f / total);
        String aiMix = QuestionBank.aiSourceBreakdown(this);
        ((TextView) findViewById(R.id.tvStats)).setText(
                "本地题库 " + localTotal + " 题（内置 " + QuestionBank.builtinCount()
                        + " · AI " + QuestionBank.aiCount()
                        + (aiMix.isEmpty() ? "" : "：" + aiMix)
                        + " · 自建 " + QuestionBank.customCount() + "）\n"
                        + "错题本 " + Store.wrongBook(this).size() + " 题 · 今日答错 "
                        + Store.wrongToday(this) + " 题\n"
                        + "累计答题 " + total + " 道 · 答对 " + correct
                        + " 道 · 正确率 " + rate + "%");

        // 局域网管理面板：显示二维码（手机扫码直达）+ 地址文字
        View qrCard = findViewById(R.id.qrCard);
        TextView admin = findViewById(R.id.tvAdmin);
        if (HttpAdminServer.isRunning()) {
            qrCard.setVisibility(View.VISIBLE);
            String url = HttpAdminServer.url();
            admin.setText(url);
            admin.setTextColor(0xFF81C784);
            refreshQr(url);
        } else {
            qrCard.setVisibility(View.GONE);
            admin.setText("面板未启动（端口 8099 可能被占用）");
            admin.setTextColor(0xFFEF9A9A);
        }
    }

    // ---------- 二维码：生成一次，地址变化时才重画 ----------

    private static final int QR_SMALL_PX = 560;   // 卡片内小图的实际像素（显示时缩放）
    private static final int QR_BIG_PX = 1100;    // 全屏放大图

    private String qrUrl;          // 当前已生成二维码对应的地址
    private Bitmap qrSmallBmp;     // 卡片缩略图缓存
    private boolean qrEncoding;    // 避免每秒刷新时重复触发编码

    /** 地址变化才重新生成，生成过程放到后台线程（每秒刷新不卡界面） */
    /**
     * 使用期限弹窗：显示设备码 + 输入 12 位激活码续期。
     * title 为 null 时按当前状态自动取标题。
     */
    private void showLicenseDialog(String title) {
        if (!License.ENFORCED) {
            toast("当前版本未启用使用期限限制：永久使用，无需激活");
            return;
        }
        boolean usable = License.isUsable(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(26), dp(14), dp(26), 0);

        TextView state = new TextView(this);
        state.setTextSize(17);
        state.setTextColor(usable ? 0xFF66BB6A : 0xFFEF5350);
        if (License.isTrialPending(this)) {
            state.setText("30 天免费试用待启动：正在等待系统时间校准（开机后联网校时自动开始）");
        } else if (!License.isActivated(this)) {
            state.setText("尚未激活：请把下面的设备码发给管理员，换取 12 位激活码");
        } else if (License.isTampered(this)) {
            // 仅提示，不再要求重新激活：防回拨由"取历史最大日期"保证，回拨延长不了使用期
            state.setText("检测到设备日期曾回退过（多因开机未校时）。已按历史最大日期计算剩余天数，不影响使用");
        } else if (License.isPermanent(this)) {
            state.setText("已永久授权，无需续期");
        } else if (License.isTrial(this)) {
            if (License.isUsable(this)) {
                state.setText("免费试用中 · 到期日 " + License.expireText(this)
                        + "（" + License.summary(this) + "）\n试用结束后请输入激活码继续使用");
            } else {
                state.setText("30 天免费试用已到期：请把下面的设备码发给管理员换取激活码");
            }
        } else {
            state.setText("到期日 " + License.expireText(this) + " · " + License.summary(this));
        }
        box.addView(state);

        TextView devLabel = new TextView(this);
        devLabel.setText("设备码（发给管理员换激活码）");
        devLabel.setTextColor(0xFF9AA6B8);
        devLabel.setTextSize(14);
        devLabel.setPadding(0, dp(16), 0, dp(2));
        box.addView(devLabel);

        TextView dev = new TextView(this);
        dev.setText(License.deviceCode(this));
        dev.setTextSize(30);
        dev.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        dev.setTextColor(0xFFFFD54F);
        dev.setLetterSpacing(0.18f);
        dev.setTextIsSelectable(true);
        box.addView(dev);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入 12 位激活码（月卡）");
        input.setTextSize(18);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(14);
        box.addView(input, ilp);

        TextView tip = new TextView(this);
        tip.setText("激活码与这台电视绑定，换设备无效；续期只需再输一次新码，不用重装。");
        tip.setTextColor(0xFF9AA6B8);
        tip.setTextSize(13);
        tip.setPadding(0, dp(10), 0, 0);
        box.addView(tip);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title != null ? title : "使用期限 / 续期")
                .setView(box)
                .setPositiveButton("激活", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = input.getText().toString().trim();
                if (code.isEmpty()) {
                    toast("请输入激活码");
                    return;
                }
                if (License.activate(MainActivity.this, code)) {
                    toast("激活成功，到期：" + License.expireText(MainActivity.this));
                    dialog.dismiss();
                    refresh();
                } else {
                    toast("激活码无效：请核对设备码和 12 位激活码");
                    input.setText("");
                }
            }
        });
    }

    private void refreshQr(final String url) {
        if (url.equals(qrUrl)) {
            if (qrSmallBmp != null) {
                ImageView iv = findViewById(R.id.ivQr);
                if (iv.getDrawable() == null) iv.setImageBitmap(qrSmallBmp);
            }
            return;
        }
        if (qrEncoding) return;
        qrEncoding = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = QrCode.encode(url, QR_SMALL_PX);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        qrEncoding = false;
                        if (bmp == null) return;
                        qrUrl = url;
                        qrSmallBmp = bmp;
                        ((ImageView) findViewById(R.id.ivQr)).setImageBitmap(bmp);
                    }
                });
            }
        }).start();
    }

    /** 电视离得远，扫小图吃力：按 OK 弹出全屏大二维码（管理面板 / 错题本共用） */
    private void showQrFullscreen(String dialogTitle, String tipText, String url) {

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setBackgroundColor(0xFF101622);
        int pad = dp(40);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(dialogTitle);
        title.setTextSize(30);
        title.setTextColor(0xFFF2F5FA);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFFFFFFFF);
        // 二维码边长按屏幕短边的 45% 自适应：电视上够大，3 米外也好扫
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int qrSide = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.45f);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qrSide, qrSide);
        qlp.topMargin = dp(28);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        box.addView(iv, qlp);

        TextView addr = new TextView(this);
        addr.setText(url == null ? "面板未启动，请先在主界面启动守护服务" : url);
        addr.setTextSize(28);
        addr.setTextColor(url == null ? 0xFFEF9A9A : 0xFF81C784);
        addr.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(26);
        box.addView(addr, alp);

        TextView tip = new TextView(this);
        tip.setText(tipText);
        tip.setTextSize(16);
        tip.setTextColor(0xFF9AA6B8);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        box.addView(tip, tlp);

        final Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(18);
        close.setAllCaps(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(220), dp(60));
        clp.topMargin = dp(30);
        box.addView(close, clp);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0xFF101622));
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
        close.requestFocus();

        if (url != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap big = QrCode.encode(url, QR_BIG_PX);
                    if (big == null) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(big);
                        }
                    });
                }
            }).start();
        }
    }

    /** 状态指示灯：文字绿色=正常，红色=异常；背景为同色淡底 + 细边 */
    private void setChip(TextView tv, String title, String value, boolean ok) {
        setChip(tv, title, value, ok, false);
    }

    /** ok=true 绿；ok=false 红；warn=true 橙黄（快到期的预警） */
    private void setChip(TextView tv, String title, String value, boolean ok, boolean warn) {
        String text = "●  " + title + " · " + value;
        if (text.equals(String.valueOf(tv.getText()))) return;   // 每秒刷新时避免重复重建背景
        tv.setText(text);
        int color = warn ? 0xFFFFB74D : (ok ? 0xFF66BB6A : 0xFFEF5350);
        tv.setTextColor(color);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(22));                       // 胶囊造型
        gd.setColor(warn ? 0x1FFFB74D : (ok ? 0x1F66BB6A : 0x1FEF5350));
        gd.setStroke(dp(1), warn ? 0x66FFB74D : (ok ? 0x6666BB6A : 0x66EF5350));
        tv.setBackground(gd);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 家长密码弹窗：验证通过后切换答题守护 开/关 */
    private void showToggleDialog() {
        if (!Store.hasPassword(this)) {
            showSetupPasswordDialog();
            return;
        }
        final EditText input = new EditText(this);
        // 密文显示，防止孩子偷看
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint("输入家长密码（数字）");
        new AlertDialog.Builder(this)
                .setTitle(Store.enabled(this) ? "关闭答题守护" : "开启答题守护")
                .setMessage("请输入家长密码（adb 修改：am start --es password \"新密码\"）")
                .setView(input)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        if (pwd.equals(Store.password(MainActivity.this))) {
                            boolean next = !Store.enabled(MainActivity.this);
                            Store.setEnabled(MainActivity.this, next);
                            toast(next ? "答题守护已开启" : "答题守护已关闭");
                        } else {
                            toast("密码错误");
                        }
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 首次使用设置家长密码：6~8 位数字，两次输入一致；此密码同时用于家长通道和局域网管理面板 */
    private void showSetupPasswordDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText pwd1 = new EditText(this);
        pwd1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pwd1.setHint("设置 6~8 位数字密码");
        box.addView(pwd1);

        final EditText pwd2 = new EditText(this);
        pwd2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pwd2.setHint("再次输入确认");
        box.addView(pwd2);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("首次使用：设置家长密码")
                .setMessage("用于家长通道、开启/关闭答题守护、手机局域网管理面板。\n请设置 6~8 位数字，记牢（忘记只能清除 App 数据重设）。")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("确定", null)
                .create();
        dialog.show();
        // 自定义点击：校验不通过时不关闭弹窗；校验通过后必须主动 dismiss，否则弹窗会一直挂着
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String a = pwd1.getText().toString().trim();
                String b = pwd2.getText().toString().trim();
                if (a.length() < 6 || a.length() > 8) {
                    toast("密码需为 6~8 位数字");
                    return;
                }
                if (!a.equals(b)) {
                    toast("两次输入不一致");
                    pwd2.setText("");
                    pwd2.requestFocus();
                    return;
                }
                Store.setPassword(MainActivity.this, a);
                toast("家长密码已设置");
                dialog.dismiss();
                refresh();
            }
        });
    }

    /** TV 上遥控器打字不便，AI 配置通过 adb 启动参数注入：
     *  adb shell am start -n com.family.quizlock/.MainActivity --es api_key "xxx" --es ai_model "glm-4-flash"
     *  备用平台（可选，主平台整体过载时兜底，默认硅基流动）：
     *  --es api_key2 "硅基流动Key" --es ai_model2 "Qwen/Qwen3-8B" --es ai_base_url2 "https://api.siliconflow.cn/v1/chat/completions" */
    private void handleAdbIntent() {
        Intent i = getIntent();
        if (i == null) return;
        boolean changed = false;
        if (i.hasExtra("api_key")) {
            Store.setAiKey(this, i.getStringExtra("api_key"));
            changed = true;
        }
        if (i.hasExtra("ai_model")) {
            Store.setAiModel(this, i.getStringExtra("ai_model"));
            changed = true;
        }
        if (i.hasExtra("ai_base_url")) {
            Store.setAiBaseUrl(this, i.getStringExtra("ai_base_url"));
            changed = true;
        }
        if (i.hasExtra("api_key2")) {
            Store.setAiKey2(this, i.getStringExtra("api_key2"));
            changed = true;
        }
        if (i.hasExtra("ai_model2")) {
            Store.setAiModel2(this, i.getStringExtra("ai_model2"));
            changed = true;
        }
        if (i.hasExtra("ai_base_url2")) {
            Store.setAiBaseUrl2(this, i.getStringExtra("ai_base_url2"));
            changed = true;
        }
        if (i.hasExtra("grade")) {
            Store.setGrade(this, i.getIntExtra("grade", Store.grade(this)));
            QuestionBank.onGradeChanged(this);
            AiQuestionClient.prefetchDebounced(this);
            changed = true;
        }
        if (i.hasExtra("difficulty")) {
            Store.setDifficulty(this, i.getIntExtra("difficulty", Store.difficulty(this)));
            QuestionBank.onDifficultyChanged(this);
            AiQuestionClient.prefetchDebounced(this);
            changed = true;
        }
        if (i.hasExtra("semester")) {
            Store.setSemester(this, i.getIntExtra("semester", Store.semester(this)));
            QuestionBank.onSemesterChanged(this);
            AiQuestionClient.prefetchDebounced(this);
            changed = true;
        }
        if (i.hasExtra("password")) {
            Store.setPassword(this, i.getStringExtra("password"));
            changed = true;
        }
        if (i.hasExtra("enabled")) {
            Store.setEnabled(this, i.getBooleanExtra("enabled", true));
            changed = true;
        }
        if (i.hasExtra("license")) {
            boolean ok = License.activate(this, i.getStringExtra("license"));
            toast(ok ? "激活成功，到期：" + License.expireText(this) : "激活码无效");
        }
        if (changed) {
            Log.i("QuizLock", "adb 配置已写入：key 长度=" + Store.aiKey(this).length()
                    + "，model=" + Store.aiModel(this)
                    + "，base_url=" + Store.aiBaseUrl(this)
                    + "，备用平台 key 长度=" + Store.aiKey2(this).length()
                    + "，备用 model=" + Store.aiModel2(this));
            toast("AI/年级配置已保存");
        } else {
            Log.i("QuizLock", "本次启动未携带配置参数（key 长度=" + Store.aiKey(this).length() + "）");
        }
    }

    /** AI 失败原因太长（HTTP 报文），截断成一行短提示 */
    private static String shortError(String msg) {
        if (msg == null) return "";
        String s = msg.replace("\n", " ").trim();
        if (s.contains("429") || s.contains("1305")) return "模型限流，稍后自动重试";
        if (s.toLowerCase().contains("timeout")) return "请求超时，稍后自动重试";
        if (s.contains("401") || s.contains("1000")) return "API Key 无效";
        if (s.contains("403") || s.contains("1220")) return "当前 Key 无该模型权限";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
