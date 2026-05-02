package com.cfks.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import roro.stellar.manager.R;

public class PairingCodeRectHelper {
    public static Rect rect = new Rect();

    public static String getPairingCodeRect(Context context) {
        final int MAX_RETRIES = 3;
        final int RETRY_DELAY_MS = 200;
        
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            String result = getPairingCodeRectInternal(context);
            if (result == null) {
                return "";
            }
            if (!result.equals("rect为0")) {
                return result;
            }
            if (retryCount < MAX_RETRIES - 1) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    return Log.getStackTraceString(e);
                }
            }
        }
        return "重试" + MAX_RETRIES + "次后rect仍为0";
    }
    
    private static String getPairingCodeRectInternal(Context context) {
        rect = new Rect();
        AlertDialog dialog = createMockDialog(context);
        dialog.show();
        
        final String[] errorMsg = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        
        View sixDigitLayout = dialog.findViewById(R.id.l_pairing_six_digit);
        if (sixDigitLayout != null) {
            sixDigitLayout.post(() -> {
                try {
                    TextView codeView = sixDigitLayout.findViewById(R.id.pairing_code);
                    if (codeView != null) {
                        int[] location = new int[2];
                        codeView.getLocationOnScreen(location);
                        rect.left = location[0];
                        rect.top = location[1];
                        rect.right = location[0] + codeView.getWidth();
                        rect.bottom = location[1] + codeView.getHeight();
                        
                        if (rect.left == 0 && rect.top == 0 && rect.right == 0 && rect.bottom == 0) {
                            errorMsg[0] = "rect为0";
                        } else {
                            errorMsg[0] = null;
                        }
                    } else {
                        errorMsg[0] = "未找到配对码 TextView";
                    }
                } catch (Exception e) {
                    errorMsg[0] = Log.getStackTraceString(e);
                } finally {
                    latch.countDown();
                    dialog.dismiss();
                }
            });
        } else {
            errorMsg[0] = "未找到 six digit layout";
            latch.countDown();
            dialog.dismiss();
        }
        
        try {
            latch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return Log.getStackTraceString(e);
        }
        
        return errorMsg[0];
    }
    
    private static AlertDialog createMockDialog(Context context) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.adb_wireless_dialog, null);
        
        TextView codeView = dialogView.findViewById(R.id.pairing_code);
        if (codeView != null) {
            codeView.setText("114514");
        }
        
        TextView ipAddrView = dialogView.findViewById(R.id.ip_addr);
        if (ipAddrView != null) {
            ipAddrView.setText("192.168.0.100:45465");
        }
        
        View sixDigitLayout = dialogView.findViewById(R.id.l_pairing_six_digit);
        if (sixDigitLayout != null) {
            sixDigitLayout.setVisibility(View.VISIBLE);
        }
        
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        
        dialog.setTitle("与设备配对");
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (d, which) -> {});
        
        return dialog;
    }
    
    public static Bitmap cropBitmap(Bitmap source, Rect rect) {
        if (source == null || rect == null || source.isRecycled()) {
            return null;
        }
        
        int left = Math.max(0, rect.left);
        int top = Math.max(0, rect.top);
        int right = Math.min(source.getWidth(), rect.right);
        int bottom = Math.min(source.getHeight(), rect.bottom);
        
        if (left >= right || top >= bottom) {
            return null;
        }
        
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }
}