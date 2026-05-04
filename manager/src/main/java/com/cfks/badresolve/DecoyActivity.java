package com.cfks.badresolve;

import android.app.Activity;
import android.os.Bundle;

/**
 * 诱饵 Activity - 必须继承 Activity
 * 需在 AndroidManifest.xml 中注册，并声明大量 categories (5000+)
 * 注意：这个类不能是静态内部类，必须是独立的顶层类
 */
public class DecoyActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 首选设置模式 - 静默关闭
        if (getIntent().hasExtra("setup_preferred")) {
            finish();
        }
    }
}