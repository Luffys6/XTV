package com.family.quizlock;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成工具（基于 ZXing 纯 Java 核心库，不需要摄像头/扫码组件）。
 *
 * 只做一件事：把一段文本（这里是管理面板地址 http://192.168.x.x:8099）画成黑白位图，
 * 电视上显示出来，手机用微信/相机扫一下就能打开管理面板，省去在电视遥控器上
 * 一个字符一个字符地敲 IP 地址。
 */
public final class QrCode {

    private static final String TAG = "QrCode";

    private QrCode() {
    }

    /**
     * 生成二维码位图。
     *
     * @param text   要编码的文本（http 地址）
     * @param sizePx 期望边长（像素），实际输出为不小于该值的正方形位图
     * @return 位图；文本为空或编码失败时返回 null（调用方需判空）
     */
    public static Bitmap encode(String text, int sizePx) {
        if (text == null || text.trim().isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // M 级容错：约 15% 破损仍可识别，电视上放大显示也不会太密
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter().encode(
                    text.trim(), BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "二维码生成失败：" + e.getMessage());
            return null;
        }
    }
}
