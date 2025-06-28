package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * UI覆盖层管理器
 * 负责管理选词覆盖层和解释窗口的显示
 */
public class UIOverlayManager {
    private static final String TAG = UIOverlayManager.class.getSimpleName();
    
    private final Context mContext;
    private final FrameLayout mRootView;
    private FrameLayout mSelectionOverlay;
    private TextView mTextView;
    
    public UIOverlayManager(Context context, FrameLayout rootView) {
        mContext = context;
        mRootView = rootView;
        createSelectionOverlay();
    }
    
    /**
     * 创建选词覆盖层
     */
    private void createSelectionOverlay() {
        // 创建容器
        mSelectionOverlay = new FrameLayout(mContext);
        mSelectionOverlay.setBackgroundColor(Color.argb(178, 20, 20, 20));
        
        // 创建文本视图
        mTextView = new TextView(mContext);
        mTextView.setTextColor(Color.WHITE);
        mTextView.setBackgroundColor(Color.TRANSPARENT);
        mTextView.setTextSize(22);
        mTextView.setPadding(40, 15, 40, 15);
        
        // 设置多行显示和自动换行
        mTextView.setSingleLine(false);
        mTextView.setMaxLines(Integer.MAX_VALUE);
        mTextView.setEllipsize(null);
        mTextView.setHorizontallyScrolling(false);
        
        // 设置文本对齐方式为居中
        mTextView.setGravity(Gravity.CENTER);
        
        // 设置所有行的行距
        mTextView.setLineSpacing(10, 1.1f);
        
        // 将文本视图添加到容器中
        mSelectionOverlay.addView(mTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        
        // 设置容器的布局参数
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        containerParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        containerParams.topMargin = 50;
        
        // 计算屏幕宽度
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        containerParams.width = (int)(screenWidth * 0.7f);
        containerParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        
        mSelectionOverlay.setLayoutParams(containerParams);
        
        // 添加边框和圆角
        try {
            GradientDrawable border = new GradientDrawable();
            border.setColors(new int[] {
                    Color.argb(178, 30, 30, 30),
                    Color.argb(178, 15, 15, 15)
            });
            border.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            border.setCornerRadius(25);
            border.setStroke(2, Color.argb(200, 120, 120, 120));
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                mSelectionOverlay.setOutlineAmbientShadowColor(Color.BLACK);
                mSelectionOverlay.setOutlineSpotShadowColor(Color.BLACK);
                mSelectionOverlay.setElevation(10);
            }
            
            mSelectionOverlay.setBackground(border);
        } catch (Exception e) {
            Log.w(TAG, "设置边框样式失败: " + e.getMessage());
        }
        
        // 默认隐藏
        mSelectionOverlay.setVisibility(View.GONE);
    }
    
    /**
     * 显示解释覆盖层
     */
    public void showDefinitionOverlay(String text) {
        if (mSelectionOverlay != null && mRootView != null) {
            // 如果覆盖层还没有添加到根视图，先添加
            if (mSelectionOverlay.getParent() == null) {
                mRootView.addView(mSelectionOverlay);
            }
            
            // 设置文本内容
            if (mTextView != null) {
                applyTextStyles(text);
            }
            
            mSelectionOverlay.setVisibility(View.VISIBLE);
            
            // 添加淡入动画效果
            try {
                AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
                fadeIn.setDuration(300);
                mSelectionOverlay.startAnimation(fadeIn);
            } catch (Exception e) {
                Log.w(TAG, "应用淡入动画失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 应用文本样式
     */
    private void applyTextStyles(String text) {
        if (text == null || text.isEmpty()) {
            mTextView.setText("");
            return;
        }
        
        // 检查是否包含换行符
        if (text.contains("\n")) {
            // 分割文本并应用不同样式
            String[] parts = text.split("\n\n", 2);
            
            if (parts.length > 1) {
                // 创建SpannableStringBuilder
                SpannableStringBuilder builder = new SpannableStringBuilder();
                
                // 为标题部分添加样式
                SpannableString titleSpan = new SpannableString(parts[0]);
                titleSpan.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 
                        0, titleSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                titleSpan.setSpan(new RelativeSizeSpan(1.2f), 
                        0, titleSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                titleSpan.setSpan(new ForegroundColorSpan(Color.rgb(255, 255, 150)), 
                        0, titleSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                
                // 为内容部分添加样式
                SpannableString contentSpan = new SpannableString("\n\n" + parts[1]);
                
                // 将两部分合并
                builder.append(titleSpan);
                builder.append(contentSpan);
                
                mTextView.setText(builder);
            } else {
                mTextView.setText(text);
            }
        } else {
            mTextView.setText(text);
        }
        
        // 调整覆盖层大小
        adjustOverlaySize();
    }
    
    /**
     * 调整覆盖层大小
     */
    private void adjustOverlaySize() {
        if (mTextView.getLayout() == null) {
            mTextView.requestLayout();
            return;
        }
        
        // 计算屏幕尺寸
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
        
        // 计算文本实际宽度和高度
        int contentWidth = mTextView.getLayout().getWidth() + mTextView.getPaddingLeft() + mTextView.getPaddingRight();
        int contentHeight = mTextView.getLayout().getHeight() + mTextView.getPaddingTop() + mTextView.getPaddingBottom();
        
        // 计算行数和估计高度
        int lineCount = mTextView.getLineCount();
        float lineHeight = mTextView.getLineHeight();
        
        // 限制最大宽度和最小宽度
        int maxWidth = (int)(screenWidth * 0.85f);
        int minWidth = (int)(screenWidth * 0.55f);
        
        // 限制最大高度和最小高度
        int maxHeight = (int)(screenHeight * 0.75f);
        int minHeight = (int)(lineHeight * 3) + mTextView.getPaddingTop() + mTextView.getPaddingBottom();
        
        // 根据内容计算理想宽度
        int idealWidth = Math.max(minWidth, Math.min(contentWidth, maxWidth));
        
        // 根据内容计算理想高度
        int idealHeight;
        if (lineCount <= 5) {
            idealHeight = contentHeight;
        } else if (lineCount <= 15) {
            idealHeight = Math.min(contentHeight, maxHeight);
        } else {
            idealHeight = Math.min((int)(lineHeight * 15) + mTextView.getPaddingTop() + mTextView.getPaddingBottom(), maxHeight);
        }
        
        // 确保高度不小于最小高度
        idealHeight = Math.max(minHeight, idealHeight);
        
        // 获取当前参数
        ViewGroup.LayoutParams params = mSelectionOverlay.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            params.width = idealWidth;
            params.height = idealHeight;
            mSelectionOverlay.setLayoutParams(params);
            
            Log.d(TAG, "动态调整窗口大小: 宽度=" + idealWidth + ", 高度=" + idealHeight);
        }
    }
    
    /**
     * 隐藏解释覆盖层
     */
    public void hideDefinitionOverlay() {
        if (mSelectionOverlay != null && mSelectionOverlay.getVisibility() == View.VISIBLE) {
            // 设置为渐隐动画
            AlphaAnimation anim = new AlphaAnimation(1.0f, 0.0f);
            anim.setDuration(300);
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}
                
                @Override
                public void onAnimationEnd(Animation animation) {
                    mSelectionOverlay.setVisibility(View.GONE);
                    // 尝试从父视图中移除覆盖层
                    if (mSelectionOverlay.getParent() != null) {
                        ((ViewGroup) mSelectionOverlay.getParent()).removeView(mSelectionOverlay);
                    }
                }
                
                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            
            mSelectionOverlay.startAnimation(anim);
        }
    }
    
    /**
     * 检查覆盖层是否可见
     */
    public boolean isOverlayVisible() {
        return mSelectionOverlay != null && mSelectionOverlay.getVisibility() == View.VISIBLE;
    }
}