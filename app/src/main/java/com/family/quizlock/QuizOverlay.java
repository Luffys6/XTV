package com.family.quizlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 全屏悬浮窗答题界面：
 *  - 盖在电视任何画面之上（含桌面/其他 App），按 Home 也躲不掉
 *  - 纯代码构建 UI，遥控器 D-pad 可上下移动焦点，也支持触屏
 *  - 默认答对 5 题解锁继续看；答错自动追加题目
 *  - 底部"家长通道"：输入家长密码验证，防止孩子借机退出
 */
public final class QuizOverlay {

    private static final int COLOR_MASK = 0xEE101622;   // 全屏遮罩
    private static final int COLOR_PANEL = 0xFF1A2332;  // 面板底色
    private static final int COLOR_GOOD = 0xFF66BB6A;
    private static final int COLOR_BAD = 0xFFEF5350;
    private static final int COLOR_TEXT = 0xFFF2F5FA;
    private static final int COLOR_SUB = 0xFF9AA6B8;
    /** 题卡来源标记配色：本地/智谱/硅基流动一眼区分 */
    private static final int COLOR_SRC_LOCAL = 0xFF8FA3BC;
    private static final int COLOR_SRC_ZHIPU = 0xFF6FB6FF;
    private static final int COLOR_SRC_SILICON = 0xFFFFB35C;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    private static WindowManager wm;
    private static FrameLayout root;
    /** 来源图标（🏠/🔷/🔶/🤖）与配色：拼进标题行，跟"答题时间到"同一行显示 */
    private static String badgeIcon = "";
    private static int badgeColor = COLOR_SRC_LOCAL;
    private static TextView titleView;
    private static TextView questionView;
    private static TextView feedbackView;
    private static LinearLayout optionsBox;
    private static LinearLayout panelRef;
    private static ScrollView scrollerRef;

    private static Question current;
    private static int correctCount;
    private static int required = 5;
    private static boolean parentMode;
    private static List<Boolean> lastCorrects = new ArrayList<Boolean>();
    private static int selectedIndex = -1;
    /** 底部"家长通道"按钮（在选项区外，方向键下边界时手动移入） */
    private static AppCompatButton parentBtnRef;

    private QuizOverlay() {
    }

    public static boolean isActive() {
        return root != null;
    }

    public static void start(final Context ctx) {
        if (isActive()) return;
        final Context c = ctx.getApplicationContext();
        required = Store.requiredCorrect(c);
        correctCount = 0;
        parentMode = false;

        WindowManager manager = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        wm = manager;

        FrameLayout rootLayout = new FrameLayout(c) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                        || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP)) {
                    // 触摸模式兜底：飞鼠/触摸屏用过一次后系统进入触摸模式，
                    // 方向键的系统焦点搜索会失效（焦点进不了选项）。这里手动移动。
                    if (moveOptionFocus(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) {
                        return true;
                    }
                }
                // 返回键：家长通道输密码/无密码提示时，按返回直接返回答题，不退出
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_BACK && parentMode) {
                    resumeQuestion(c);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        rootLayout.setBackgroundColor(COLOR_MASK);

        // 面板放进 ScrollView：题目/解析再长也不会超出屏幕，底部"下一题"按钮可滚动到
        // fillViewport + CENTER_VERTICAL：内容短时仍然垂直居中，观感不变
        ScrollView scroller = new ScrollView(c);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        // ScrollView 构造时默认自聚焦，会抢走窗口初始焦点导致选项无高亮、遥控器失灵，
        // 关掉它的可聚焦性，让焦点直接落到第一个选项上
        scroller.setFocusable(false);
        scroller.setFocusableInTouchMode(false);
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        scLp.topMargin = dp(c, 24);
        scLp.bottomMargin = dp(c, 24);
        scLp.leftMargin = dp(c, 90);
        scLp.rightMargin = dp(c, 90);
        rootLayout.addView(scroller, scLp);
        scrollerRef = scroller;

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 48), dp(c, 36), dp(c, 48), dp(c, 28));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(c, 20));
        panelBg.setColor(COLOR_PANEL);
        panelBg.setStroke(dp(c, 1), 0xFF2A3A52);
        box.setBackground(panelBg);
        column.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 初始焦点先落在面板本身（无任何高亮），选项一个都不预选。
        // 孩子按方向键才移进选项、看到焦点框；触摸则直接点选项即出结果。
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        panelRef = box;

        // 来源图标不再单独占一行：拼到标题行最前面（🏠 本地 / 🔷 智谱 / 🔶 硅基流动），
        // 平台与模型名只打 logcat。标题由 setTitleWithBadge() 渲染。
        titleView = new AppCompatTextView(c);
        titleView.setTextSize(23);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(c, 6);
        box.addView(titleView, titleLp);

        questionView = new AppCompatTextView(c);
        questionView.setTextSize(26);
        questionView.setTextColor(COLOR_TEXT);
        questionView.setLineSpacing(dp(c, 5), 1f);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(c, 22);
        box.addView(questionView, qLp);

        feedbackView = new AppCompatTextView(c);
        feedbackView.setTextSize(18);
        feedbackView.setTextColor(COLOR_SUB);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(c, 12);
        box.addView(feedbackView, fLp);

        optionsBox = new LinearLayout(c);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        oLp.topMargin = dp(c, 16);
        box.addView(optionsBox, oLp);

        AppCompatButton parentBtn = new AppCompatButton(c);
        parentBtn.setText("家长通道");
        parentBtn.setTextSize(16);
        parentBtn.setAllCaps(false);
        parentBtn.setGravity(Gravity.CENTER);
        parentBtn.setFocusable(true);
        parentBtn.setFocusableInTouchMode(true);
        parentBtn.setPadding(dp(c, 32), dp(c, 6), dp(c, 32), dp(c, 6));
        applyBtnBg(c, parentBtn, BTN_GHOST, false);
        parentBtnRef = parentBtn;
        parentBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyBtnBg(c, (AppCompatButton) v, BTN_GHOST, hasFocus);
            }
        });
        parentBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parentMode = true;
                titleView.setText("家长验证通道");
                setFeedback("请输入家长密码以退出（防止孩子误入）", COLOR_SUB);
                showParentPassword(c);
            }
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 48));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(c, 16);
        box.addView(parentBtn, pLp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        manager.addView(rootLayout, params);
        root = rootLayout;
        showQuestion(c);
    }

    private static void showQuestion(final Context c) {
        if (parentMode) {
            showParentPassword(c);
            return;
        }
        current = QuestionBank.randomQuestion(c);
        renderCurrent(c);
    }

    /** 误点家长通道后取消：回到刚才那道题继续答，不换题、不丢进度 */
    private static void resumeQuestion(final Context c) {
        parentMode = false;
        if (current == null) {
            showQuestion(c);
            return;
        }
        renderCurrent(c);
    }

    /**
     * 题卡来源标记：只显示图标不显示文字（家长要求：本地=🏠、智谱=🔷、硅基流动=🔶）。
     * 图标不单独占行，由 setTitleWithBadge() 拼到标题最前面；平台/模型名只打 logcat。
     */
    private static void renderSource(Question q) {
        if (q == null) return;
        String detail;
        String icon;
        int color;
        if (q.isLocal()) {
            icon = "🏠";
            detail = Question.SRC_CUSTOM.equals(q.source) ? "家长自建题" : "本地题库 · 内置题";
            color = COLOR_SRC_LOCAL;
        } else if (q.source.contains("智谱")) {
            icon = "🔷";
            detail = "智谱 · " + shortModel(q.model);
            color = COLOR_SRC_ZHIPU;
        } else if (q.source.contains("硅基")) {
            icon = "🔶";
            detail = "硅基流动 · " + shortModel(q.model);
            color = COLOR_SRC_SILICON;
        } else {
            icon = "🤖";
            detail = q.source.isEmpty() ? "AI 出题"
                    : q.source + (q.model.isEmpty() ? "" : " · " + shortModel(q.model));
            color = COLOR_SUB;
        }
        badgeIcon = icon;
        badgeColor = color;
        // 便于家长/开发者核对：logcat 里能查到每一道题的真实出处
        android.util.Log.i("QuizOverlay", "题卡来源：" + detail + "（" + q.id + "）");
    }

    /** 标题行：来源图标和标题文字同一行，只给图标着色（本地灰 / 智谱蓝 / 硅基橙） */
    private static void setTitleWithBadge(String text) {
        if (titleView == null) return;
        if (badgeIcon == null || badgeIcon.isEmpty()) {
            titleView.setText(text);
            return;
        }
        SpannableString ss = new SpannableString(badgeIcon + "  " + text);
        ss.setSpan(new ForegroundColorSpan(badgeColor), 0, badgeIcon.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleView.setText(ss);
    }

    /** 模型 id 去掉供应商前缀（Qwen/Qwen3-8B → Qwen3-8B），避免标记行太长 */
    private static String shortModel(String model) {
        if (model == null || model.isEmpty()) return "";
        int slash = model.lastIndexOf('/');
        return slash >= 0 && slash < model.length() - 1 ? model.substring(slash + 1) : model;
    }

    /** 渲染当前题（题干 + 打乱后的选项），不换题 */
    private static void renderCurrent(final Context c) {
        Question q = current;
        if (q == null) return;
        if (parentBtnRef != null) parentBtnRef.setVisibility(View.VISIBLE);
        // 来源图标拼进标题行（🏠 本地 / 🔷 智谱 / 🔶 硅基），不再单独占一行
        renderSource(q);
        setTitleWithBadge("答题时间到！答对 " + required + " 题继续看（已完成 " + correctCount + " 题）");
        setFeedback("", COLOR_SUB);
        questionView.setText("【" + QuestionBank.subjectName(q.subject) + "】" + q.q);
        if (scrollerRef != null) scrollerRef.scrollTo(0, 0);

        // 选项打乱后再渲染，防止正确答案固定在 A
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < q.options.size(); i++) order.add(i);
        Collections.shuffle(order, RND);
        List<String> texts = new ArrayList<String>();
        List<Boolean> corrects = new ArrayList<Boolean>();
        for (int idx : order) {
            texts.add(q.options.get(idx));
            corrects.add(idx == q.answer);
        }
        renderOptions(c, texts, corrects);
    }

    /** 家长验证：输入家长密码（与"开启/关闭答题守护"同一密码），遥控器数字键可直接输入 */
    private static void showParentPassword(final Context c) {
        if (parentBtnRef != null) parentBtnRef.setVisibility(View.GONE);
        if (Store.password(c).isEmpty()) {
            // 还没设密码：引导回主界面设置，不提供任何退出后门
            optionsBox.removeAllViews();
            questionView.setText("家长密码尚未设置");
            setFeedback("请先在电视上打开\"学习小卫士\"完成密码设置", COLOR_BAD);
            AppCompatButton back = makeButton(c, "返回继续答题", BTN_PRIMARY, 22);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resumeQuestion(c);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 66));
            lp.topMargin = dp(c, 20);
            optionsBox.addView(back, lp);
            focusWhenReady(back);
            return;
        }
        optionsBox.removeAllViews();
        questionView.setText("家长验证：请输入家长密码");
        setFeedback("误点家长通道？点下面的「取消」直接继续答题", COLOR_SUB);

        final AppCompatEditText input = new AppCompatEditText(c);
        // 密文显示：防止孩子站在电视前偷看密码
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint("输入家长密码（数字）");
        input.setTextSize(22);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_SUB);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        GradientDrawable igd = new GradientDrawable();
        igd.setCornerRadius(dp(c, 14));
        igd.setColor(0xFF2C3B55);
        igd.setStroke(dp(c, 1), 0xFF46587A);
        input.setBackground(igd);
        input.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
        optionsBox.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 66)));

        AppCompatButton ok = makeButton(c, "验证并退出", BTN_PRIMARY, 22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 66));
        lp.topMargin = dp(c, 20);
        optionsBox.addView(ok, lp);

        // 误点家长通道：一键取消，回到刚才那道题继续答（不动已答进度）
        AppCompatButton cancel = makeButton(c, "取消，继续答题", BTN_GHOST, 20);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resumeQuestion(c);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 60));
        clp.topMargin = dp(c, 12);
        optionsBox.addView(cancel, clp);

        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pwd = input.getText() == null ? "" : input.getText().toString().trim();
                if (pwd.equals(Store.password(c))) {
                    setFeedback("✅ 密码正确，已退出", COLOR_GOOD);
                    HANDLER.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            finish(c, true);
                        }
                    }, 500);
                } else {
                    setFeedback("❌ 密码错误，请重试（点「取消」可返回答题）", COLOR_BAD);
                    input.setText("");
                }
            }
        });

        // 焦点先落在输入框，遥控器数字键直接输入；按"下"移到验证按钮按 OK
        focusWhenReady(input);
    }

    // ---------- 统一按钮样式：全 App 只有"默认 / 选中"两种状态 ----------

    /** 选项按钮：深蓝灰底，选中时提亮 + 亮蓝描边 */
    private static final int BTN_OPTION = 0;
    /** 主操作按钮：蓝底白字（下一题 / 继续看电视 / 验证并退出 / 返回） */
    private static final int BTN_PRIMARY = 1;
    /** 次要按钮：透明底细描边（家长通道 / 取消） */
    private static final int BTN_GHOST = 2;

    /** 统一创建按钮：默认态背景 + 选中态（焦点）监听，所有按钮外观一致。
     *  触摸模式第一击直接点击（focusableInTouchMode=false），避免"点两下才生效"。 */
    private static AppCompatButton makeButton(final Context c, String label, final int style, float size) {
        AppCompatButton b = new AppCompatButton(c);
        b.setText(label);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setPadding(dp(c, 24), dp(c, 10), dp(c, 24), dp(c, 10));
        applyBtnBg(c, b, style, false);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (v.isEnabled()) {
                    applyBtnBg(c, (AppCompatButton) v, style, hasFocus);
                }
            }
        });
        return b;
    }

    /** 按钮背景：默认态 / 选中态（焦点）两种，全局统一。
     *  选中态 = 亮蓝底 + 白色粗描边，和默认态反差大，遥控器导航一眼可辨。 */
    private static void applyBtnBg(Context c, AppCompatButton btn, int style, boolean selected) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(c, 14));
        if (selected) {
            // 统一选中态：亮蓝填充 + 白色粗描边，三种风格一致
            gd.setColor(0xFF4C86E8);
            gd.setStroke(dp(c, 3), 0xFFFFFFFF);
            btn.setTextColor(0xFFFFFFFF);
        } else if (style == BTN_PRIMARY) {
            // 默认就是蓝色主按钮，未选中时压暗一档
            gd.setColor(0xFF3D6DD8);
            gd.setStroke(dp(c, 2), 0xFF9FC1FF);
            btn.setTextColor(0xFFFFFFFF);
        } else if (style == BTN_GHOST) {
            // 次要按钮：透明底细描边
            gd.setColor(0x00000000);
            gd.setStroke(dp(c, 1), 0xFF46587A);
            btn.setTextColor(COLOR_SUB);
        } else {
            // 选项按钮：深蓝灰底
            gd.setColor(0xFF2C3B55);
            gd.setStroke(dp(c, 1), 0xFF46587A);
            btn.setTextColor(0xFFF2F5FA);
        }
        btn.setBackground(gd);
    }

    /**
     * 悬浮窗刚 addView 时视图树还没完成首次渲染，此时立即 requestFocus 会失效
     * （初始焦点丢失、遥控器没反应）。延迟到首帧之后聚焦，期间窗口若已关闭则自动跳过。
     */
    /**
     * 方向键在选项间移动焦点（触摸模式兜底）。
     * 焦点在面板上（默认态）→ 移入第一个/最后一个可用选项；
     * 焦点在某个选项上 → 移到相邻选项；移出边界返回 false 交给系统（如移向"下一题"按钮）；
     * 焦点在其他按钮（下一题/家长通道）上时不接管。
     */
    private static boolean moveOptionFocus(int dir) {
        if (optionsBox == null || optionsBox.getChildCount() == 0 || root == null) return false;
        View f = root.findFocus();
        if (f != null && f.getParent() != optionsBox && f != panelRef) return false;
        int count = optionsBox.getChildCount();
        int cur = -1;
        for (int i = 0; i < count; i++) {
            if (optionsBox.getChildAt(i) == f) { cur = i; break; }
        }
        int next = (cur < 0) ? (dir > 0 ? 0 : count - 1) : cur + dir;
        while (next >= 0 && next < count) {
            View v = optionsBox.getChildAt(next);
            if (v.isEnabled() && v.isFocusable()) {
                // 触摸模式下 focusableInTouchMode=false 的按钮 requestFocus 会被忽略，
                // 按方向键说明在用遥控器，放开该标志再聚焦（不影响触摸单击）
                v.setFocusableInTouchMode(true);
                v.requestFocus();
                return true;
            }
            next += dir;
        }
        // 从最后一个选项继续按"下"：移到底部"家长通道"
        if (dir > 0 && parentBtnRef != null) {
            parentBtnRef.setFocusableInTouchMode(true);
            parentBtnRef.requestFocus();
            return true;
        }
        return false;
    }

    private static void focusWhenReady(final View v) {        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (v.isAttachedToWindow() && !v.hasFocus() && v.isEnabled()) {
                    v.requestFocus();
                }
            }
        }, 250);
    }

    private static void renderOptions(final Context c, List<String> texts, List<Boolean> corrects) {
        optionsBox.removeAllViews();
        lastCorrects = new ArrayList<Boolean>(corrects);
        selectedIndex = -1;
        for (int i = 0; i < texts.size(); i++) {
            final int idx = i;
            final String text = texts.get(i);
            final boolean ok = corrects.get(i);
            AppCompatButton btn = makeButton(c, text, BTN_OPTION, 24);
            // 关键：选项不要在触摸模式里获取焦点。
            // 否则第一次点击会被"获取焦点"吞掉，表现为"要点两下才选得中"。
            // 只保留 DPAD 焦点能力：遥控器方向键照常导航，手指/飞鼠单击一下立刻作答。
            btn.setFocusableInTouchMode(false);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answer(c, ok, text, idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 66));
            lp.topMargin = dp(c, 12);
            optionsBox.addView(btn, lp);
        }
        // 不预选任何选项：焦点留在面板本身，孩子按方向键才移入选项、或直接点选
        if (panelRef != null) {
            focusWhenReady(panelRef);
        }
    }

    private static void answer(final Context c, boolean correct, String chosen, int index) {
        Question q = current;
        if (q == null) return;
        selectedIndex = index;
        highlightAnswers(c, correct);

        if (correct) {
            correctCount++;
            Store.addStat(c, true);
            setTitleWithBadge("答题时间到！答对 " + required + " 题继续看（已完成 " + correctCount + " 题）");
            String explain = (q.explain == null || q.explain.isEmpty())
                    ? "" : " 解析：" + q.explain;
            if (correctCount >= required) {
                setFeedback("✅ 太棒了，全部答对！" + explain
                        + " 按\"继续看电视\"解锁", COLOR_GOOD);
                addNextButton(c, "继续看电视 ▶", true);
            } else {
                setFeedback("✅ 答对了！（" + correctCount + "/" + required + "）" + explain
                        + " 看完按\"下一题\"继续", COLOR_GOOD);
                addNextButton(c, "下一题 ▶", false);
            }
        } else {
            Store.addStat(c, false);
            Store.recordWrong(c, q);
            // AI 题"用掉即弃"，答错的题必须回收进池+回写缓存，否则错题本会失联
            QuestionBank.retainQuestion(c, q);
            String right = q.options.get(q.answer);
            String explain = (q.explain == null || q.explain.isEmpty()) ? "" : "（" + q.explain + "）";
            setFeedback("❌ 答错了。正确答案：" + right + explain
                    + " —— 错一题，多答一题！看完解析，按\"下一题\"继续", COLOR_BAD);
            // 不自动跳转：等孩子看完解析，手动点"下一题"
            addNextButton(c, "下一题 ▶", false);
        }
    }

    /** 答题后出现的继续按钮：不自动跳转，看完解析手动继续；unlock=true 时点击即解锁 */
    private static void addNextButton(final Context c, String label, final boolean unlock) {
        AppCompatButton btn = makeButton(c, label, BTN_PRIMARY, 22);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (unlock) {
                    finish(c, true);
                } else {
                    showQuestion(c);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 68));
        lp.topMargin = dp(c, 16);
        optionsBox.addView(btn, lp);
        // 自动聚焦，遥控器按 OK 即可继续（在 ScrollView 内会自动滚动到可见位置）
        focusWhenReady(btn);
    }

    /** 答题后的选项状态标识：正确项绿底✓、选错的项红底、其余变暗 */
    private static void highlightAnswers(Context c, boolean correct) {
        if (optionsBox == null) return;
        for (int i = 0; i < optionsBox.getChildCount(); i++) {
            View v = optionsBox.getChildAt(i);
            if (!(v instanceof AppCompatButton)) continue;
            AppCompatButton b = (AppCompatButton) v;
            b.setEnabled(false);
            b.setOnFocusChangeListener(null);
            boolean isCorrect = i < lastCorrects.size() && lastCorrects.get(i);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(c, 14));
            if (isCorrect) {
                // 正确答案：绿底 + 对勾
                gd.setColor(0xFF1E5E38);
                gd.setStroke(dp(c, 3), 0xFF81C784);
                b.setTextColor(0xFFD7F5DF);
                b.setAlpha(1f);
                String t = b.getText().toString();
                if (!t.endsWith("✓")) b.setText(t + "   ✓");
            } else if (i == selectedIndex) {
                // 孩子选错的那个：红底标出
                gd.setColor(0xFF7A2E2E);
                gd.setStroke(dp(c, 3), 0xFFEF9A9A);
                b.setTextColor(0xFFFFDAD6);
                b.setAlpha(1f);
            } else {
                // 无关选项：变暗
                gd.setColor(0xFF232F45);
                b.setTextColor(0xFF6F7D94);
                b.setAlpha(0.45f);
            }
            b.setBackground(gd);
        }
    }

    private static void setFeedback(String text, int color) {
        feedbackView.setText(text);
        feedbackView.setTextColor(color);
    }

    private static void finish(Context c, boolean unlock) {
        HANDLER.removeCallbacksAndMessages(null);
        parentBtnRef = null;
        FrameLayout r = root;
        root = null;
        if (r != null && wm != null) {
            try {
                wm.removeView(r);
            } catch (Exception ignored) {
            }
        }
        if (unlock) {
            // 解锁（答完题 / 家长通道验证通过）：本段观看计时清零 —— 从解锁这一刻起
            // 重新计满一轮（interval 分钟）才会再弹题；同时让 AI 预生成下一批题
            Store.setWatchedSeconds(c, 0L);
            AiQuestionClient.prefetchAsync(c);
        }
    }

    private static int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        // noinspection deprecation
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
