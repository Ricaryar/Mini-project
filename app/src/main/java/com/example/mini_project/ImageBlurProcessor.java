package com.example.mini_project;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.content.Context;

/**
 * 图像模糊处理器
 * 对敏感区域进行高斯模糊处理
 */
public class ImageBlurProcessor {
    private static final float DEFAULT_BLUR_RADIUS = 25.0f;

    private Context context;
    private RenderScript renderScript;

    public ImageBlurProcessor(Context context) {
        this.context = context;
        try {
            this.renderScript = RenderScript.create(context);
        } catch (Exception e) {
            // 如果RenderScript不可用，使用备用方案
            this.renderScript = null;
        }
    }

    /**
     * 对图片的指定区域进行模糊处理
     * @param source 原始图片
     * @param areas 需要模糊的区域
     * @return 处理后的图片
     */
    public Bitmap blurSensitiveAreas(Bitmap source, java.util.List<Rect> areas) {
        if (source == null || areas == null || areas.isEmpty()) {
            return source;
        }

        // 创建可变副本
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        if (result == null) return source;

        Canvas canvas = new Canvas(result);

        for (Rect area : areas) {
            // 确保区域在图片范围内
            Rect validArea = new Rect(
                    Math.max(0, area.left),
                    Math.max(0, area.top),
                    Math.min(result.getWidth(), area.right),
                    Math.min(result.getHeight(), area.bottom)
            );

            if (validArea.width() <= 0 || validArea.height() <= 0) continue;

            // 提取区域图片
            Bitmap areaBitmap = Bitmap.createBitmap(result,
                    validArea.left, validArea.top,
                    validArea.width(), validArea.height());

            // 对区域进行模糊
            Bitmap blurredArea = blurBitmap(areaBitmap);

            // 将模糊后的区域绘制回去
            canvas.drawBitmap(blurredArea, validArea.left, validArea.top, new Paint());

            // 回收临时bitmap
            areaBitmap.recycle();
            if (blurredArea != areaBitmap) {
                blurredArea.recycle();
            }
        }

        canvas.setBitmap(null);
        return result;
    }

    /**
     * 对单个Bitmap进行高斯模糊
     */
    private Bitmap blurBitmap(Bitmap bitmap) {
        if (renderScript != null) {
            return blurWithRenderScript(bitmap);
        } else {
            return blurWithStackBlur(bitmap);
        }
    }

    /**
     * 使用RenderScript进行模糊（更快）
     */
    private Bitmap blurWithRenderScript(Bitmap bitmap) {
        try {
            // 创建缩小版本提高性能
            int scaleFactor = 2;
            Bitmap smallBitmap = Bitmap.createScaledBitmap(bitmap,
                    bitmap.getWidth() / scaleFactor,
                    bitmap.getHeight() / scaleFactor,
                    true);

            Allocation input = Allocation.createFromBitmap(renderScript, smallBitmap);
            Allocation output = Allocation.createTyped(renderScript, input.getType());

            ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
            blur.setInput(input);
            blur.setRadius(DEFAULT_BLUR_RADIUS / scaleFactor);
            blur.forEach(output);

            output.copyTo(smallBitmap);

            // 放大回原尺寸
            Bitmap result = Bitmap.createScaledBitmap(smallBitmap,
                    bitmap.getWidth(), bitmap.getHeight(), true);

            smallBitmap.recycle();
            return result;

        } catch (Exception e) {
            return blurWithStackBlur(bitmap);
        }
    }

    /**
     * 简单的堆栈模糊算法（备用方案）
     */
    private Bitmap blurWithStackBlur(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // 简单的均值模糊
        int radius = 15;
        int[] output = new int[pixels.length];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0;
                int count = 0;

                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            int pixel = pixels[ny * width + nx];
                            r += (pixel >> 16) & 0xFF;
                            g += (pixel >> 8) & 0xFF;
                            b += pixel & 0xFF;
                            count++;
                        }
                    }
                }

                r /= count;
                g /= count;
                b /= count;

                output[y * width + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(output, 0, width, 0, 0, width, height);

        return result;
    }

    /**
     * 释放资源
     */
    public void release() {
        if (renderScript != null) {
            renderScript.destroy();
            renderScript = null;
        }
    }
}