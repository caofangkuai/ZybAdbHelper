package com.cfks.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * 应用隐藏与伪装工具类（支持持久化）
 * 功能：动态修改应用名称、图标为系统默认、隐藏桌面图标、恢复原始状态
 * 数据持久化：状态保存至SharedPreferences，应用重启后自动同步
 */
public class HideAppUtil {

    private static final String PREF_NAME = "hide_app_prefs";
    private static final String KEY_IS_DISGUISED = "is_disguised";
    private static final String KEY_ORIGINAL_COMPONENT = "original_component";
    private static final String KEY_ORIGINAL_APP_NAME = "original_app_name";
    private static final String KEY_ALIAS_COMPONENT = "alias_component";

    private static ComponentName sAliasComponent;
    private static String sOriginalAppName;
    private static ComponentName sOriginalComponent;

    /**
     * 伪装应用：修改名称为"SystemLogReporter"，图标改为系统默认图标，隐藏桌面图标
     * @param context 上下文
     */
    public static void disguise(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName defaultComponent = getDefaultLauncherActivity(context);
        if (defaultComponent == null) return;

        // 缓存并持久化原始信息（仅在首次伪装时保存，防止覆盖）
        SharedPreferences prefs = getPrefs(context);
        if (!prefs.getBoolean(KEY_IS_DISGUISED, false)) {
            sOriginalComponent = new ComponentName(context.getPackageName(), defaultComponent.getClassName());
            sOriginalAppName = getAppLabel(context, sOriginalComponent);
            
            // 持久化原始数据
            prefs.edit()
                .putString(KEY_ORIGINAL_COMPONENT, sOriginalComponent.flattenToString())
                .putString(KEY_ORIGINAL_APP_NAME, sOriginalAppName)
                .putString(KEY_ALIAS_COMPONENT, context.getPackageName() + ".HideAppAlias")
                .apply();
        } else {
            // 从持久化恢复缓存
            loadCachedData(context);
        }

        // 1. 禁用原始入口组件（隐藏图标）
        pm.setComponentEnabledSetting(sOriginalComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        // 2. 启用别名组件（伪装后的名称和图标）
        sAliasComponent = new ComponentName(context.getPackageName(), 
                prefs.getString(KEY_ALIAS_COMPONENT, context.getPackageName() + ".HideAppAlias"));
        pm.setComponentEnabledSetting(sAliasComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        // 标记已伪装状态
        prefs.edit().putBoolean(KEY_IS_DISGUISED, true).apply();
    }

    /**
     * 恢复应用原本的样子（名称、图标、桌面图标重新显示）
     * @param context 上下文
     */
    public static void restore(Context context) {
        PackageManager pm = context.getPackageManager();
        SharedPreferences prefs = getPrefs(context);

        if (!prefs.getBoolean(KEY_IS_DISGUISED, false)) {
            return; // 未伪装，无需恢复
        }

        // 从持久化加载原始组件
        loadCachedData(context);
        if (sOriginalComponent == null) return;

        // 1. 恢复原始入口组件
        pm.setComponentEnabledSetting(sOriginalComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        // 2. 禁用别名组件
        if (sAliasComponent != null) {
            pm.setComponentEnabledSetting(sAliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }

        // 清除伪装标记，但保留原始数据以便下次伪装（可选：彻底清除）
        prefs.edit().putBoolean(KEY_IS_DISGUISED, false).apply();
    }

    /**
     * 检查当前是否处于伪装状态
     * @param context 上下文
     * @return true=已伪装，false=未伪装
     */
    public static boolean isDisguised(Context context) {
        return getPrefs(context).getBoolean(KEY_IS_DISGUISED, false);
    }

    /**
     * 获取原始应用名称（持久化缓存）
     * @param context 上下文
     * @return 原始应用名称
     */
    public static String getOriginalAppName(Context context) {
        loadCachedData(context);
        return sOriginalAppName != null ? sOriginalAppName : "Unknown";
    }

    // ==================== 私有辅助方法 ====================

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static void loadCachedData(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (sOriginalComponent == null && prefs.contains(KEY_ORIGINAL_COMPONENT)) {
            sOriginalComponent = ComponentName.unflattenFromString(
                    prefs.getString(KEY_ORIGINAL_COMPONENT, ""));
            sOriginalAppName = prefs.getString(KEY_ORIGINAL_APP_NAME, "");
            String aliasStr = prefs.getString(KEY_ALIAS_COMPONENT, "");
            if (!aliasStr.isEmpty()) {
                sAliasComponent = ComponentName.unflattenFromString(aliasStr);
            }
        }
    }

    private static ComponentName getDefaultLauncherActivity(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(context.getPackageName());
        ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
        }
        return null;
    }

    private static String getAppLabel(Context context, ComponentName component) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getActivityInfo(component, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return context.getApplicationInfo().loadLabel(pm).toString();
        }
    }
}