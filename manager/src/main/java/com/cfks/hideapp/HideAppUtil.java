package com.cfks.hideapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/**
 * 应用隐藏/恢复工具类
 * 支持修改图标和名称
 */
public class HideAppUtil {

    private static final String PREF_NAME = "app_hide_prefs";
    private static final String KEY_IS_HIDDEN = "is_hidden";
    
    private static final String ALIAS_VISIBLE = "MainActivityVisible";      // 正常显示别名
    private static final String ALIAS_HIDDEN = "MainActivityHidden";        // 隐藏状态别名

    /**
     * 隐藏应用图标（修改为 SystemLogReporter + 安卓默认图标）
     */
    public static void hideApp(Context context) {
        String pkgName = context.getPackageName();
        
        // 禁用正常图标，启用伪装图标
        setComponentState(context, pkgName + "." + ALIAS_VISIBLE, false);
        setComponentState(context, pkgName + "." + ALIAS_HIDDEN, true);
        
        getPrefs(context).edit().putBoolean(KEY_IS_HIDDEN, true).apply();
        scheduleIconRefresh(context);
    }

    /**
     * 恢复应用图标（恢复正常图标和名称）
     */
    public static void restoreApp(Context context) {
        String pkgName = context.getPackageName();
        
        // 启用正常图标，禁用伪装图标
        setComponentState(context, pkgName + "." + ALIAS_VISIBLE, true);
        setComponentState(context, pkgName + "." + ALIAS_HIDDEN, false);
        
        getPrefs(context).edit().putBoolean(KEY_IS_HIDDEN, false).apply();
        scheduleIconRefresh(context);
    }

    /**
     * 判断当前是否处于隐藏状态
     */
    public static boolean isHidden(Context context) {
        return getPrefs(context).getBoolean(KEY_IS_HIDDEN, false);
    }

    /**
     * 启用/禁用组件
     */
    private static void setComponentState(Context context, String componentNameStr, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, componentNameStr);
        int newState = enabled ? 
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED : 
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        
        try {
            pm.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);
        } catch (SecurityException e) {
            e.printStackTrace();
            pm.setComponentEnabledSetting(componentName, newState, 0);
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static void scheduleIconRefresh(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getPackageManager().flushPackageCache();
        }
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_PACKAGE_CHANGED);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        context.sendBroadcast(intent);
    }
}