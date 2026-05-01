package com.cfks.utils;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

/**
 * 截图区域自动适配工具
 */
public class ScreenCaptureHelper {
    private static final String TAG = "ScreenCaptureHelper";
    
    // 基准分辨率
    private static final int BASE_WIDTH = 2176;
    private static final int BASE_HEIGHT = 1600;
    
    // 基准截图区域
    private static final int BASE_LEFT = 636;
    private static final int BASE_TOP = 706;
    private static final int BASE_RIGHT = 920;
    private static final int BASE_BOTTOM = 775;
    
    // 缓存当前屏幕分辨率
    private static int screenWidth = BASE_WIDTH;
    private static int screenHeight = BASE_HEIGHT;
    
    /**
     * 设置当前设备分辨率（在 Application 中调用一次即可）
     * @param width 屏幕宽度
     * @param height 屏幕高度
     */
    public static void setScreenSize(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        Log.d(TAG, "Screen size set to: " + width + "x" + height);
    }
    
    /**
     * 获取适配后的截图区域（使用缓存的分辨率）
     */
    public static Rect getAdaptedCaptureRect() {
        return calculateAdaptedRect(screenWidth, screenHeight);
    }
    
    /**
     * 计算适配后的截图区域（按宽度比例）
     */
    public static Rect calculateAdaptedRect(int currentWidth, int currentHeight) {
        float scale = (float) currentWidth / BASE_WIDTH;
        
        int left = Math.round(BASE_LEFT * scale);
        int top = Math.round(BASE_TOP * scale);
        int right = Math.round(BASE_RIGHT * scale);
        int bottom = Math.round(BASE_BOTTOM * scale);
        
        // 边界检查
        left = Math.max(0, Math.min(left, currentWidth - 1));
        right = Math.max(left + 1, Math.min(right, currentWidth));
        top = Math.max(0, Math.min(top, currentHeight - 1));
        bottom = Math.max(top + 1, Math.min(bottom, currentHeight));
        
        Log.d(TAG, String.format("Adapted rect: (%d,%d,%d,%d) scale=%.3f screen=%dx%d", 
                left, top, right, bottom, scale, currentWidth, currentHeight));
        
        return new Rect(left, top, right, bottom);
    }
    
    /**
     * 裁剪 Bitmap
     */
    public static Bitmap cropBitmap(Bitmap source, Rect rect) {
        if (source == null || rect == null) {
            return null;
        }
        
        int left = Math.max(0, rect.left);
        int top = Math.max(0, rect.top);
        int right = Math.min(source.getWidth(), rect.right);
        int bottom = Math.min(source.getHeight(), rect.bottom);
        
        if (left >= right || top >= bottom) {
            Log.e(TAG, "Invalid crop area");
            return null;
        }
        
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }
}