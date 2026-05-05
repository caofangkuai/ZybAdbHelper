package com.cfks.hideapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class TriggerActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 验证是否通过adb.cfknb文件打开
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            String path = intent.getData().getPath();
            if (path != null && path.endsWith(".cfknb")) {
                // 通过adb.cfknb文件打开，启动主应用
                launchMainApp();
            }
        }
        
        // 关闭触发器Activity
        finish();
    }
    
    private void launchMainApp() {
        Intent launchIntent = new Intent(this, roro.stellar.manager.MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launchIntent);
    }
}