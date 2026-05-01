package com.cfks.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import roro.stellar.manager.R;

public class PairingCodeRectHelper {
	public static Rect rect = new Rect();

    public static void getPairingCodeRect(Context context) {
        AlertDialog dialog = createMockDialog(context);
        dialog.show();
        
        Rect rect = new Rect();
        try {
            View sixDigitLayout = dialog.findViewById(R.id.l_pairing_six_digit);
            if (sixDigitLayout != null && sixDigitLayout.getVisibility() == View.VISIBLE) {
                TextView codeView = sixDigitLayout.findViewById(R.id.pairing_code);
                if (codeView != null) {
                    int[] location = new int[2];
                    codeView.getLocationOnScreen(location);
                    rect.left = location[0];
                    rect.top = location[1];
                    rect.right = location[0] + codeView.getWidth();
                    rect.bottom = location[1] + codeView.getHeight();
                }
            }
        } catch (Exception e) {
            rect.setEmpty();
        } finally {
            dialog.dismiss();
        }
        
        PairingCodeRectHelper.rect = rect;
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
    
    /**
     * 裁剪 Bitmap
     */
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