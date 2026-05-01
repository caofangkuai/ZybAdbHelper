package com.cfks.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tesseract OCR 工具类
 * 自动从 assets 解压训练数据到私有目录，并提供文字识别功能
 */
public class TesseractHelper {
    private static final String TAG = "TesseractHelper";
    private static final String TESSDATA_DIR_NAME = "tesseract";  // 私有目录下的主目录
    private static final String TESSDATA_SUB_DIR = "tessdata";    // 必须包含 tessdata 子目录
    private static final String LANGUAGE = "equ";                 // 训练数据语言
    private static final String TRAINEDDATA_FILENAME = "equ.traineddata";

    private static volatile boolean isInitialized = false;
    private static String dataPath;  // 指向 tessdata 的父目录

    /**
     * 初始化：将 equ.traineddata 从 assets 复制到私有目录
     * 建议在 Application 或首次调用识别前调用
     *
     * @param context 上下文
     * @return 初始化是否成功
     */
    public static synchronized boolean init(Context context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized");
            return true;
        }

        try {
            // 创建目录: /data/data/包名/files/tesseract/tessdata/
            File tessdataDir = new File(context.getFilesDir(), TESSDATA_DIR_NAME + File.separator + TESSDATA_SUB_DIR);
            if (!tessdataDir.exists()) {
                boolean created = tessdataDir.mkdirs();
                if (!created) {
                    Log.e(TAG, "Failed to create tessdata directory");
                    return false;
                }
            }

            // 目标文件路径
            File destFile = new File(tessdataDir, TRAINEDDATA_FILENAME);

            // 如果文件已存在且大小正确，则跳过复制
            if (destFile.exists()) {
                Log.d(TAG, "Traineddata already exists: " + destFile.getAbsolutePath());
            } else {
                // 从 assets 复制文件
                copyAssetToFile(context, TRAINEDDATA_FILENAME, destFile);
                Log.d(TAG, "Traineddata copied to: " + destFile.getAbsolutePath());
            }

            // 存储路径（传递给 TessBaseAPI.init() 的路径，需要指向包含 tessdata 的目录）
            dataPath = new File(context.getFilesDir(), TESSDATA_DIR_NAME).getAbsolutePath();
            isInitialized = true;
            Log.d(TAG, "Init success, dataPath: " + dataPath);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Init failed", e);
            return false;
        }
    }

    /**
     * 从 assets 复制文件到目标文件
     */
    private static void copyAssetToFile(Context context, String assetPath, File destFile) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) != -1) {
                os.write(buffer, 0, length);
            }
            os.flush();
        }
    }

    /**
     * 识别图片中的文字（同步方法，应在子线程调用）
     *
     * @param context 上下文
     * @param bitmap  要识别的 Bitmap 图片
     * @return 识别出的文字，失败返回 null
     */
    public static String recognizeText(Context context, Bitmap bitmap) {
        if (!isInitialized) {
            if (!init(context)) {
                Log.e(TAG, "Init failed, cannot recognize");
                return null;
            }
        }

        TessBaseAPI tess = new TessBaseAPI();
        try {
            // 初始化 Tesseract
            // 参数: 数据目录路径（包含 tessdata 子目录），语言代码
            boolean initSuccess = tess.init(dataPath, LANGUAGE);
            if (!initSuccess) {
                Log.e(TAG, "Tesseract init failed. Check path: " + dataPath);
                return null;
            }

            // 设置图片
            tess.setImage(bitmap);

            // 获取识别结果
            String text = tess.getUTF8Text();
            Log.d(TAG, "Recognized text: " + (text != null ? text.trim() : "null"));
            return text != null ? text.trim() : "";

        } catch (Exception e) {
            Log.e(TAG, "Recognition failed", e);
            return null;
        } finally {
            // 必须回收释放资源
            tess.recycle();
        }
    }

    /**
     * 识别图片中的文字（从文件路径加载图片）
     *
     * @param context     上下文
     * @param imagePath   图片文件路径
     * @return 识别出的文字，失败返回 null
     */
    public static String recognizeTextFromPath(Context context, String imagePath) {
        Bitmap bitmap = null;
        try {
            // 使用 BitmapFactory 加载图片，注意可能会 OOM，建议根据情况压缩
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = android.graphics.BitmapFactory.decodeFile(imagePath, options);
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from path: " + imagePath);
                return null;
            }
            return recognizeText(context, bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Load image failed", e);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    /**
     * 检查初始化状态
     */
    public static boolean isInitialized() {
        return isInitialized;
    }
}