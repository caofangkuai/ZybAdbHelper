package com.cfks.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

public class WatermarkView extends View {
   private Paint mPaint;
   private String watermarkText = "cfknb";
   private LinearGradient mGradient;
   private Handler mHandler;
   private Runnable mColorRunnable;
   private float mOffset = 0;
   private int[] mColors;

   public WatermarkView(Context context) {
      super(context);
      init();
   }

   public WatermarkView(Context context, AttributeSet attrs) {
      super(context, attrs);
      init();
   }

   private void init() {
      mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      mPaint.setTextSize(80);
      mPaint.setStyle(Paint.Style.FILL_AND_STROKE);
      mPaint.setStrokeWidth(2);
      mPaint.setAlpha(100);

      mColors = new int[]{
         Color.parseColor("#FF6B6B"),
         Color.parseColor("#4ECDC4"),
         Color.parseColor("#FFE66D"),
         Color.parseColor("#A8E6CF"),
         Color.parseColor("#FF8C94"),
         Color.parseColor("#C39BD3"),
         Color.parseColor("#85C1E9")
      };

      setClickable(false);
      setFocusable(false);
      setWillNotDraw(false);

      startColorTransition();
   }

   private void startColorTransition() {
      mHandler = new Handler(Looper.getMainLooper());
      mColorRunnable = new Runnable() {
         @Override
         public void run() {
            mOffset += 0.02f;
            if (mOffset > 1) {
               mOffset = 0;
            }
            updateGradient();
            invalidate();
            mHandler.postDelayed(this, 50);
         }
      };
      mHandler.post(mColorRunnable);
   }

   private void updateGradient() {
      int width = getWidth();
      int height = getHeight();
      if (width <= 0 || height <= 0) return;

      int[] shiftedColors = shiftColors(mColors, mOffset);

      mGradient = new LinearGradient(0, 0, width, height,
      shiftedColors, null, Shader.TileMode.MIRROR);
      mPaint.setShader(mGradient);
   }

   private int[] shiftColors(int[] colors, float offset) {
      int len = colors.length;
      int[] result = new int[len];
      int shift = (int)(offset * len);
      for (int i = 0; i < len; i++) {
         result[i] = colors[(i + shift) % len];
      }
      return result;
   }

   @Override
   protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      updateGradient();
   }

   @Override
   protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);

      int width = getWidth();
      int height = getHeight();
      int spacingX = 380;
      int spacingY = 380;
      float angle = - 25;

      canvas.save();
      canvas.rotate(angle, width / 2f, height / 2f);

      for (int x = - width; x < width * 2; x += spacingX) {
         for (int y = - height; y < height * 2; y += spacingY) {
            canvas.drawText(watermarkText, x, y, mPaint);
         }
      }

      canvas.restore();
   }

   @Override
   protected void onDetachedFromWindow() {
      super.onDetachedFromWindow();
      if (mHandler != null && mColorRunnable != null) {
         mHandler.removeCallbacks(mColorRunnable);
      }
   }
}