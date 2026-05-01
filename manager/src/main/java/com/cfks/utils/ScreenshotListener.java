package com.cfks.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;

public class ScreenshotListener {

    private static final String TAG = "ScreenshotListener";
    private static final long DEBOUNCE_DELAY_MS = 1500L;
    private static final long FILE_WRITE_DELAY_MS = 500L;

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler;
    private final Handler debounceHandler;

    private ContentObserver contentObserver;
    private String lastProcessedUri = "";
    private long lastProcessedTime = 0;
    private Runnable debounceRunnable;

    public interface Callback {
        void onScreenshotTaken(String filePath);
        void onError(String error);
    }

    public ScreenshotListener(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.debounceHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (contentObserver != null) {
            Log.w(TAG, "监听器已启动");
            return;
        }

        // 检查权限（Android 12 及以下需要）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // 权限检查由调用方处理，这里只记录
            Log.d(TAG, "Android 12 及以下版本，需要 READ_EXTERNAL_STORAGE 权限");
        }

        contentObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (uri != null) {
                    handleUriChange(uri);
                }
            }
        };

        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
        );
        Log.i(TAG, "截图监听器已启动");
    }

    public void stop() {
        if (debounceRunnable != null) {
            debounceHandler.removeCallbacks(debounceRunnable);
            debounceRunnable = null;
        }

        if (contentObserver != null) {
            context.getContentResolver().unregisterContentObserver(contentObserver);
            contentObserver = null;
        }
        Log.i(TAG, "截图监听器已停止");
    }

    private void handleUriChange(Uri uri) {
        // 防抖：同一截图可能触发多次 onChange
        long currentTime = System.currentTimeMillis();
        String uriString = uri.toString();

        if (uriString.equals(lastProcessedUri) && 
            currentTime - lastProcessedTime < DEBOUNCE_DELAY_MS) {
            Log.d(TAG, "防抖过滤重复事件");
            return;
        }

        lastProcessedUri = uriString;
        lastProcessedTime = currentTime;

        // 延迟处理，等待文件写入完成
        if (debounceRunnable != null) {
            debounceHandler.removeCallbacks(debounceRunnable);
        }
        debounceRunnable = () -> queryLatestScreenshot();
        debounceHandler.postDelayed(debounceRunnable, FILE_WRITE_DELAY_MS);
    }

    private void queryLatestScreenshot() {
        ContentResolver contentResolver = context.getContentResolver();
        
        String[] projection = {
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH
        };

        // 查询条件：最近5秒内的图片
        String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
        long timeThreshold = System.currentTimeMillis() / 1000 - 5;
        String[] selectionArgs = { String.valueOf(timeThreshold) };

        // 按时间倒序，取最新一条
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1";

        Cursor cursor = null;
        try {
            cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
            );

            if (cursor != null && cursor.moveToFirst()) {
                int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                int nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                int pathColumn = -1;
                
                // RELATIVE_PATH 在 Android 10+ 可用
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
                }
                
                String filePath = dataColumn >= 0 ? cursor.getString(dataColumn) : "";
                String displayName = nameColumn >= 0 ? cursor.getString(nameColumn) : "";
                String relativePath = pathColumn >= 0 ? cursor.getString(pathColumn) : "";

                Log.d(TAG, "找到新图片: " + displayName + ", 路径: " + relativePath);

                if (isValidScreenshot(filePath, displayName, relativePath)) {
                    Log.i(TAG, "确认为截图: " + filePath);
                    notifyCallback(filePath);
                } else {
                    Log.d(TAG, "非截图文件，忽略");
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "权限不足，无法查询 MediaStore", e);
            notifyError("缺少存储权限");
        } catch (Exception e) {
            Log.e(TAG, "查询截图失败", e);
            notifyError("查询失败: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean isValidScreenshot(String filePath, String fileName, String relativePath) {
        // 检查文件路径是否包含截图目录
        boolean isInScreenshotDir = false;
        if (relativePath != null && !relativePath.isEmpty()) {
            isInScreenshotDir = relativePath.toLowerCase().contains("screenshots");
        } else if (filePath != null && !filePath.isEmpty()) {
            isInScreenshotDir = filePath.toLowerCase().contains("screenshots");
        }

        // 检查文件名是否匹配截图格式
        boolean isValidName = false;
        if (fileName != null && !fileName.isEmpty()) {
            String lowerName = fileName.toLowerCase();
            isValidName = (lowerName.endsWith(".png") || 
                           lowerName.endsWith(".jpg") ||
                           lowerName.endsWith(".jpeg")) &&
                           !fileName.startsWith(".") &&
                           !fileName.startsWith("tmp_");
        }

        return isInScreenshotDir && isValidName;
    }

    private void notifyCallback(final String filePath) {
        if (callback != null) {
            mainHandler.post(() -> callback.onScreenshotTaken(filePath));
        }
    }

    private void notifyError(final String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}