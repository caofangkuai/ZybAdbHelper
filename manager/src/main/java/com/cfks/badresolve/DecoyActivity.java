package com.cfks.badresolve;

/**
 * 诱饵 Activity - 需在 AndroidManifest.xml 中注册
 * 必须声明大量 categories (5000+) 来拖慢 Intent 解析
 */
public static class DecoyActivity extends Activity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getIntent().hasExtra("setup_preferred")) {
            finish();
        }
    }
}