package com.family.quizlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用期限（月卡授权），完全离线校验，不需要服务器：
 *
 *  电视显示「设备码」（8 位，ANDROID_ID 哈希而来）
 *        ↓ 家长把设备码发给你
 *  你用 tools/license_gen.py 生成 12 位数字「激活码」
 *        ↓ 家长在电视或手机面板输入
 *  本地用 HMAC-SHA256 校验 → 通过则记录到期日，可用 N 天
 *
 * 激活码结构：4 位到期日序号 + 8 位签名
 *  - 序号 = 从 2020-01-01（固定按东八区）起的天数，9999 表示永久
 *  - 签名 = HMAC(设备码|序号, 密钥) 取 8 位十进制
 *  密钥只在打包时注入 BuildConfig，伪造需要密钥，改日期/换设备都无效。
 */
public final class License {

    private static final String TAG = "License";
    private static final String P = "quizlock_license";

    /** 是否启用使用期限限制：false=永久可用（默认），true=月卡制 */
    public static final boolean ENFORCED = BuildConfig.LICENSE_ENFORCE;

    private static final long DAY_MS = 86400000L;
    private static final long TZ_OFFSET_MS = 8 * 3600 * 1000L;   // 固定东八区，保证电视/工具算法一致
    private static final long BASE_DAY = 18262L;                 // 2020-01-01
    private static final int PERMANENT = 9999;
    /** 首次安装自动赠送的免费试用天数 */
    public static final int TRIAL_DAYS = 30;
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // 去掉易混的 0O1I

    /** 可接受的日期下限（早于 2020 说明电视时钟还没联网校准） */
    private static final long MIN_SANE_DAY = BASE_DAY;
    /** 可接受的日期上限（约 2080 年，超过说明时钟坏了） */
    private static final long MAX_SANE_DAY = BASE_DAY + 60L * 365L;

    private License() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** 当前天序号（东八区，自 1970-01-01 起） */
    public static long today() {
        return (System.currentTimeMillis() + TZ_OFFSET_MS) / DAY_MS;
    }

    /** 设备码：8 位大写字母数字，报给管理员用 */
    public static String deviceCode(Context c) {
        String raw;
        try {
            raw = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            raw = null;
        }
        if (raw == null || raw.trim().length() == 0) {
            raw = "fallback-" + android.os.Build.MODEL + "-" + android.os.Build.SERIAL;
        }
        String h = sha256(raw + "|" + c.getPackageName());
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            int v = Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            sb.append(ALPHABET.charAt(v % 32));
        }
        return sb.toString();
    }

    /** 签名：HMAC(设备码|到期序号) 取 8 位十进制（密钥来自 Secret，dex 里无明文） */
    private static String sign(String dev, int expire) {
        String secret = Secret.license();
        if (secret.isEmpty()) {
            Log.w(TAG, "未配置 LICENSE_SECRET，无法签名（检查 secrets.properties 后重新编译）");
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
            byte[] d = mac.doFinal((dev + "|" + expire).getBytes(Charset.forName("UTF-8")));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[i] & 0xFF);
            }
            v = (v & Long.MAX_VALUE) % 100000000L;
            return String.format(Locale.US, "%08d", v);
        } catch (Exception e) {
            Log.w(TAG, "签名失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 启动时调用：首次安装自动发放 30 天免费试用。
     * 未启用授权限制（LICENSE_ENFORCE=false）时直接跳过，不写任何授权数据。
     */
    public static synchronized void init(Context c) {
        if (!ENFORCED) return;
        ensureTrial(c);
        touch(c);
    }

    /**
     * 可信的"试用起点"（返回 0 表示哪个来源都不可信）：
     *
     *  1. 安装锚点（PackageManager 记录的安装时间）——优先。它跟着系统走，清 App 数据清不掉，
     *     所以"清数据 = 重新领 30 天"这个漏洞被堵住；
     *  2. 当前设备日期——仅在设备时钟可信时（见 devTrustworthy）；
     *  3. 服务器日期（HTTP Date 头，来自真实服务器）。
     *
     * 注意：安装时间本身也是"安装那一刻的设备时钟"记下的，跟设备时间一样会被出厂 RTC 污染
     * （本机 firstInstallTime 就是出厂时间 2022-03-21），所以它必须过 devTrustworthy 这道闸门
     * ——闸门用的是本 APK 的编译时间，与设备时钟无关，设备改不了也污染不了。
     */
    private static long trustedDay(Context c) {
        long anchor = installAnchor(c);
        if (anchor > 0) return anchor;
        long dev = today();
        if (devTrustworthy(dev)) return dev;
        long net = netDay(c);
        if (sane(net) && net >= buildDay() - 1) return net;
        return 0;
    }

    /** APK 编译日期的天序号（唯一的、与设备时钟无关的真值下界） */
    private static long buildDay() {
        return (BuildConfig.BUILD_TIME + TZ_OFFSET_MS) / DAY_MS;
    }

    private static boolean installRead = false;
    private static long installFirstDay;
    private static long installLastDay;

    /** 读一次 PackageManager 里的安装/更新时间（缓存，避免每秒刷新都走一次 IPC） */
    private static void readInstallTime(Context c) {
        if (installRead) return;
        installRead = true;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            installFirstDay = (pi.firstInstallTime + TZ_OFFSET_MS) / DAY_MS;
            installLastDay = (pi.lastUpdateTime + TZ_OFFSET_MS) / DAY_MS;
        } catch (Exception e) {
            installFirstDay = installLastDay = 0;
        }
    }

    /**
     * 安装锚点：本 App 第一次装到这台设备上的日期。清 App 数据不会变，卸载重装才会变。
     *
     *  - 全新安装：firstInstallTime 必然晚于本 APK 的编译时间 → 可信；
     *  - 覆盖升级：firstInstallTime 属于更老的版本（早于本 APK 编译日期），改用 lastUpdateTime
     *    （本次版本的安装时间，必然晚于编译时间）；
     *  - 出厂预装 / 首次安装时电视时钟还停在出厂日期 → 两个时间都早于编译日期，判为不可信（返回 0），
     *    交给"当前日期/服务器日期"重新定锚，不会照着 2022 年把 30 天试用一次性烧光。
     */
    private static long installAnchor(Context c) {
        readInstallTime(c);
        if (devTrustworthy(installFirstDay)) return installFirstDay;
        if (devTrustworthy(installLastDay)) return installLastDay;
        return 0;
    }

    private static void grantTrial(SharedPreferences sp, long day) {
        sp.edit()
                .putBoolean("lic_init", true)
                .putBoolean("lic_trial", true)
                .putInt("lic_expire", (int) (day - BASE_DAY + TRIAL_DAYS))
                .putLong("lic_max_day", day)
                .putBoolean("lic_tamper", false)
                .apply();
    }

    /** 首次安装发放试用；时钟未校准时先不发（界面显示"试用待启动"），校准后自动补发 */
    private static void ensureTrial(Context c) {
        SharedPreferences sp = p(c);
        if (sp.getBoolean("lic_init", false)) return;
        long day = trustedDay(c);
        if (day <= 0) {
            Log.w(TAG, "时钟未校准（day=" + today() + "），暂不发放试用，联网校时后自动开始");
            return;
        }
        grantTrial(sp, day);
        Log.i(TAG, "首次安装：发放 " + TRIAL_DAYS + " 天免费试用（锚点 day=" + day + "）");
    }

    /** 日期是否在合理区间（2020 ~ 2080），用于过滤电视未校准/坏掉的时钟 */
    private static boolean sane(long day) {
        return day >= MIN_SANE_DAY && day <= MAX_SANE_DAY;
    }

    /** 当前是否为免费试用（尚未输入过激活码） */
    public static boolean isTrial(Context c) {
        return p(c).getBoolean("lic_trial", false);
    }

    /** 试用尚未开始：设备时钟还没校准，等联网校时后自动开始 30 天试用。此状态下先放行使用 */
    public static boolean isTrialPending(Context c) {
        if (!ENFORCED) return false;
        return !p(c).getBoolean("lic_init", false);
    }

    /**
     * 激活 / 续期：校验 12 位激活码。
     *
     * @return true 表示校验通过且已写入到期日
     */
    public static boolean activate(Context c, String code) {
        if (!ENFORCED) return false;   // 未启用授权限制时激活无意义
        String digits = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (digits.length() != 12) return false;
        int expire;
        try {
            expire = Integer.parseInt(digits.substring(0, 4));
        } catch (Exception e) {
            return false;
        }
        String sig = digits.substring(4);
        String expect = sign(deviceCode(c), expire);
        if (expect == null || !expect.equals(sig)) return false;
        long today = effectiveDay(c);
        if (expire != PERMANENT && expire < today - BASE_DAY) return false;  // 已过期的码不接受
        // 重新激活时以"激活当天"为日期基准（旧的 max_day 可能来自被改过的时钟）
        p(c).edit()
                .putBoolean("lic_init", true)
                .putBoolean("lic_trial", false)
                .putInt("lic_expire", expire)
                .putString("lic_code", digits)
                .putLong("lic_max_day", today)
                .putBoolean("lic_tamper", false)
                .apply();
        Log.i(TAG, "激活成功，到期序号=" + expire);
        return true;
    }

    public static boolean isActivated(Context c) {
        if (!ENFORCED) return true;
        return p(c).getInt("lic_expire", -1) >= 0;
    }

    /** 到期日序号（9999 = 永久） */
    public static int expireDay(Context c) {
        return p(c).getInt("lic_expire", -1);
    }

    public static boolean isPermanent(Context c) {
        if (!ENFORCED) return true;
        return expireDay(c) == PERMANENT;
    }

    /** 剩余天数：永久返回 9999；未激活返回 -1；试用待启动返回 30 */
    public static int daysLeft(Context c) {
        if (!ENFORCED) return 9999;
        if (isTrialPending(c)) return TRIAL_DAYS;
        if (!isActivated(c)) return -1;
        if (isPermanent(c)) return 9999;
        long left = BASE_DAY + expireDay(c) - effectiveDay(c);
        return (int) left;
    }

    /**
     * 是否被判定为「日期曾回退过」——仅作诊断信息，**不再影响可用性**。
     *
     * 历史教训（1.4 真机事故）：把自动记录的 lic_max_day 当作作弊证据并永久锁定，
     * 一旦基准来自开机未校时的坏时钟，用户就会在完全没动过时间的情况下被要求重新激活。
     * 防回拨已由 effectiveDay() 保证（取历史最大值，回拨延长不了使用期），无需再锁死功能。
     */
    public static boolean isTampered(Context c) {
        return p(c).getBoolean("lic_tamper", false);
    }

    public static boolean isExpired(Context c) {
        if (!ENFORCED) return false;
        if (isTrialPending(c)) return false;
        if (!isActivated(c)) return true;
        if (isPermanent(c)) return false;
        // 只看是否到期：日期回退不会延长使用期（effectiveDay 取最大值），也不会锁死使用
        return daysLeft(c) < 0;
    }

    /** 功能是否可用：已激活、未过期、未检测到时间回拨（试用待启动的宽限状态也算可用） */
    public static boolean isUsable(Context c) {
        if (!ENFORCED) return true;
        if (isTrialPending(c)) return true;
        return isActivated(c) && !isExpired(c);
    }

    /**
     * 联网时记录服务器日期（HTTP 响应的 Date 头），作为纠正电视时钟的锚点。
     * 电视时钟不准（刚开机没同步、用户改时间）时以服务器时间为准。
     */
    public static void noteHttpDate(Context c, String httpDate) {
        if (!ENFORCED || httpDate == null) return;
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            Date d = f.parse(httpDate.trim());
            long day = (d.getTime() + TZ_OFFSET_MS) / DAY_MS;
            if (!sane(day)) return;
            p(c).edit().putLong("lic_net_day", day).apply();
        } catch (Exception ignored) {
        }
    }

    /** 最近一次联网拿到的服务器日期（0 = 从没联网过） */
    public static long netDay(Context c) {
        return p(c).getLong("lic_net_day", 0);
    }

    /** 设备时钟是否可信：合理区间内，且不早于本 APK 的编译日期（开机 RTC 停在出厂日期时不可信） */
    private static boolean devTrustworthy(long day) {
        return sane(day) && day >= buildDay() - 1;
    }

    /**
     * 当前"有效日期"，用于算剩余天数：
     *  - 设备时钟不可信（1970 / 出厂日期，早于本 APK 编译日期）时**完全不参与计算**，
     *    避免刚开机那两分钟按 2022 年算出"试用剩余 1720 天"这种乱跳；
     *  - 再与服务器日期、历史最大日期取较大值：时间往回拨不会延长使用期（防回拨）。
     */
    public static long effectiveDay(Context c) {
        long dev = today();
        long net = netDay(c);
        long max = p(c).getLong("lic_max_day", 0);
        long ref = 0;
        if (devTrustworthy(dev)) ref = dev;
        if (sane(net) && net > ref) ref = net;
        // 基准只可能在"安装之后"写入，早于编译日期的必定是坏时钟留下的脏数据，不参与计算
        if (max >= buildDay() - 1 && max > ref) ref = max;
        // 全都不可信时退回编译日期：至少不会按 1970/出厂日期算出离谱的天数
        return ref > 0 ? ref : buildDay();
    }

    /** 每次启动/回到前台调用：时钟校准后自动补发试用；记录见过的最大日期（回拨不延长使用期），并纠正被坏时钟污染的基准 */
    public static synchronized void touch(Context c) {
        if (!ENFORCED) return;
        if (isTrialPending(c)) {
            ensureTrial(c);   // 面板每秒刷新都会走到这里：时钟一校准，试用立即自动开始
            return;
        }
        if (!isActivated(c)) return;
        SharedPreferences sp = p(c);
        long dev = today();
        long net = netDay(c);
        long max = sp.getLong("lic_max_day", 0);

        if (!devTrustworthy(dev)) {
            // 电视刚开机还没联网校时（1970 / 出厂日期）：本次既不用它更新基准，
            // 也不能拿它去"纠正"基准——否则会把真实的 2026 基准改成 2022，
            // 界面上就会先跳出一千多天、等校时才变回正常。
            Log.w(TAG, "设备时钟未校准（day=" + dev + "），本次不更新日期基准");
        } else {
            if (max < buildDay() - 1 || dev > max) {
                // 没有基准，或基准早于编译日期（坏时钟留下的脏数据）→ 用可信的当前日期重建
                sp.edit().putLong("lic_max_day", dev).apply();
                max = dev;
            } else if (max - dev > 60 && sane(net) && Math.abs(net - dev) <= 7) {
                // 基准比"现在"超前 60 天以上，且服务器日期能证实设备时间才是对的
                // （如 RTC 曾跑飞把基准写到了未来）→ 就地纠正回正常值。
                // 没有服务器日期作证时不动基准：宁可多用几天，也不能把用户误判成到期。
                Log.w(TAG, "纠正超前的日期基准：" + max + " -> " + dev + "（超前 " + (max - dev) + " 天）");
                sp.edit().putLong("lic_max_day", dev).apply();
                max = dev;
            }
            if (dev < max - 2) {
                // 仅记录诊断信息：日期回退不再锁死功能（防回拨靠 effectiveDay 取最大值）
                Log.w(TAG, "日期回退：now=" + dev + " max=" + max + "（不影响使用）");
            }
        }
        // 历史版本写入的误判标记：一律清除，避免主界面/面板再显示"时间异常"
        if (sp.getBoolean("lic_tamper", false)) {
            sp.edit().putBoolean("lic_tamper", false).apply();
            Log.i(TAG, "已清除历史'时间异常'标记");
        }
    }

    /** 到期日文本，如 2026-12-05；永久返回「永久」；未激活返回「未激活」 */
    public static String expireText(Context c) {
        if (!isActivated(c)) return "未激活";
        if (isPermanent(c)) return "永久";
        return formatDay(BASE_DAY + expireDay(c));
    }

    /** 状态一行摘要，用于主界面 */
    public static String summary(Context c) {
        if (!ENFORCED) return "永久使用";
        if (isTrialPending(c)) return "试用待启动（联网校准时间后自动开始）";
        if (!isActivated(c)) return "未激活";
        if (isPermanent(c)) return "永久授权";
        int left = daysLeft(c);
        boolean trial = isTrial(c);
        if (left < 0) return trial ? "试用已到期" : "已到期";
        return (trial ? "试用剩余 " : "剩余 ") + left + " 天";
    }

    /** 是否处于「需要提醒续期」状态（到期或只剩 7 天内） */
    public static boolean needRenew(Context c) {
        if (!ENFORCED) return false;
        return !isUsable(c) || (!isPermanent(c) && daysLeft(c) <= 7);
    }

    private static String formatDay(long day) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));
        return f.format(new Date((day * DAY_MS) - TZ_OFFSET_MS));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }
}
