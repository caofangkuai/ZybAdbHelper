package com.cfks.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.util.regex.Pattern;

public class ScreenshotListener {

    private FileObserver fileObserver;
    private ContentObserver contentObserver;
    private ContentResolver contentResolver;
    private Callback callback;
    private Handler mainHandler;

    public interface Callback {
        void onScreenshotTaken(String filePath);
    }

    public ScreenshotListener(Context context, Callback callback) {
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.contentResolver = context.getApplicationContext().getContentResolver();
        start();
    }

    public void start() {
        File screenshotsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_PICTURES + "/Screenshots");

        if (screenshotsDir.exists()) {
            fileObserver = new FileObserver(screenshotsDir.getAbsolutePath(), FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && isScreenshotFile(path)) {
                        notifyCallback(screenshotsDir.getAbsolutePath() + "/" + path);
                    }
                }
            };
            fileObserver.startWatching();
        }

        contentObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
            }
        };
        contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, contentObserver);
    }

    public void stop() {
        if (fileObserver != null) {
            fileObserver.stopWatching();
            fileObserver = null;
        }
        if (contentObserver != null) {
            contentResolver.unregisterContentObserver(contentObserver);
            contentObserver = null;
        }
    }

    private void notifyCallback(String filePath) {
        if (callback != null) {
            mainHandler.post(() -> callback.onScreenshotTaken(filePath));
        }
    }

    private boolean isScreenshotFile(String fileName) {
        return fileName != null &&
               Pattern.matches("(?i).*\\.(png|jpg|jpeg)$", fileName) &&
               !fileName.startsWith(".") &&
               !fileName.startsWith("tmp_");
    }
}