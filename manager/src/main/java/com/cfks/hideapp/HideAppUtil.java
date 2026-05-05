package com.cfks.hideapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public class HideAppUtil {
    private static final String TAG = "HideAppUtil";
    private static final String PREF_NAME = "hide_app_config";
    private static final String KEY_IS_HIDDEN = "is_app_hidden";
    private static final String KEY_ORIGINAL_LABEL = "original_app_label";
    
    // 用于通过adb.cfknb文件打开的Activity
    private static final String TRIGGER_ACTIVITY_ALIAS = "com.cfks.hideapp.TriggerActivity";
    
    /**
     * 隐藏桌面图标，修改应用名为"SystemLogReporter"
     */
    public static boolean hideApp(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName mainComponent = new ComponentName(context, context.getPackageName() + ".MainActivity");
            String packageName = context.getPackageName();
            
            // 获取当前应用标签并保存原始标签
            try {
                String currentLabel = pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0)).toString();
                saveOriginalLabel(context, currentLabel);
            } catch (Exception e) {
                Log.e(TAG, "获取原始标签失败", e);
            }
            
            // 1. 禁用主Activity（隐藏桌面图标）
            pm.setComponentEnabledSetting(mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            
            // 2. 使用Activity-Alias动态修改图标和名称
            // 需要在AndroidManifest.xml中配置对应的activity-alias
            
            // 3. 启用触发器Activity（通过adb.cfknb文件打开）
            enableTriggerActivity(context);
            
            // 4. 保存隐藏状态
            saveHiddenState(context, true);
            
            Log.d(TAG, "应用已隐藏，名称已修改为SystemLogReporter");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "隐藏应用失败", e);
            return false;
        }
    }
    
    /**
     * 恢复桌面图标和原始应用名
     */
    public static boolean restoreApp(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName mainComponent = new ComponentName(context, context.getPackageName() + ".MainActivity");
            
            // 1. 启用主Activity（恢复桌面图标）
            pm.setComponentEnabledSetting(mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            
            // 2. 禁用触发器Activity
            disableTriggerActivity(context);
            
            // 3. 清除隐藏状态
            saveHiddenState(context, false);
            
            Log.d(TAG, "应用已恢复");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "恢复应用失败", e);
            return false;
        }
    }
    
    /**
     * 判断应用是否处于隐藏状态
     */
    public static boolean isHidden(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_IS_HIDDEN, false);
    }
    
    /**
     * 启用触发器Activity
     */
    private static void enableTriggerActivity(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName triggerComponent = new ComponentName(context, TRIGGER_ACTIVITY_ALIAS);
            pm.setComponentEnabledSetting(triggerComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "启用触发器失败", e);
        }
    }
    
    /**
     * 禁用触发器Activity
     */
    private static void disableTriggerActivity(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName triggerComponent = new ComponentName(context, TRIGGER_ACTIVITY_ALIAS);
            pm.setComponentEnabledSetting(triggerComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "禁用触发器失败", e);
        }
    }
    
    /**
     * 保存隐藏状态
     */
    private static void saveHiddenState(Context context, boolean isHidden) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_IS_HIDDEN, isHidden).apply();
    }
    
    /**
     * 保存原始应用标签
     */
    private static void saveOriginalLabel(Context context, String label) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ORIGINAL_LABEL, label).apply();
    }
    
    /**
     * 获取原始应用标签
     */
    public static String getOriginalLabel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ORIGINAL_LABEL, "");
    }
    
    /**
     * 初始化触发器Activity（应用启动时调用）
     */
    public static void initTriggerActivity(Context context) {
        if (isHidden(context)) {
            enableTriggerActivity(context);
        } else {
            disableTriggerActivity(context);
        }
    }
}