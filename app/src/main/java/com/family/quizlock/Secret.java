package com.family.quizlock;

import android.util.Base64;

import java.nio.charset.Charset;

/**
 * 打包进 APK 的敏感串：{@code LICENSE_SECRET}（发卡密钥）和 {@code AI_API_KEY}。
 *
 * <p>并不以明文字符串存在 dex 里。构建时（见 app/build.gradle）做了「异或 + Base64」：
 * <pre>
 *   每个字节 = 原文 ^ 混淆钥[i % 8] ^ (byte)(i * 31)
 * </pre>
 * 混淆钥每次构建随机生成，和密文一起放进 BuildConfig，运行时在这里还原。
 *
 * <p><b>这不是加密。</b>离线校验要求密钥最终必须在设备上可用，所以反编译 + 还原算法
 * 照样能拿到（hook 运行时内存更容易）。它只解决两件事：
 * <ol>
 *   <li>{@code strings app.apk | grep 密钥} 这种一秒钟的提取失效；</li>
 *   <li>jadx 打开不会在 {@code BuildConfig} 里直接看到 {@code LICENSE_SECRET = "..."}。</li>
 * </ol>
 * 要真正防伪造激活码，只能把校验搬到服务端（密钥不出服务器），见 README「已知缺陷」。
 */
final class Secret {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static String licenseSecret;
    private static String aiKey;
    private static String aiKey2;

    private Secret() {
    }

    /** 发卡密钥（空串表示构建时没配 secrets.properties） */
    static String license() {
        if (licenseSecret == null) {
            licenseSecret = decode(BuildConfig.LICENSE_SECRET_OBF, BuildConfig.LICENSE_SECRET_XOR);
        }
        return licenseSecret;
    }

    /** AI 接口 Key */
    static String aiKey() {
        if (aiKey == null) {
            aiKey = decode(BuildConfig.AI_API_KEY_OBF, BuildConfig.AI_API_KEY_XOR);
        }
        return aiKey;
    }

    /** 备用平台 AI Key（空串表示构建时没配，可运行期 adb 写入） */
    static String aiKey2() {
        if (aiKey2 == null) {
            aiKey2 = decode(BuildConfig.AI_API_KEY_2_OBF, BuildConfig.AI_API_KEY_XOR);
        }
        return aiKey2;
    }

    private static String decode(String blob, String keyB64) {
        if (blob == null || blob.isEmpty() || keyB64 == null || keyB64.isEmpty()) return "";
        try {
            byte[] key = Base64.decode(keyB64, Base64.NO_WRAP);
            byte[] b = Base64.decode(blob, Base64.NO_WRAP);
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (b[i] ^ key[i % key.length] ^ (byte) (i * 31));
            }
            return new String(b, UTF8);
        } catch (Exception e) {
            return "";
        }
    }
}
