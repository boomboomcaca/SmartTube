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
    private int mScrollPosition = 0;
    
    public UIOverlayManager(Context context, FrameLayout rootView) {
        mContext = context;
        mRootView = rootView;
        createSelectionOverlay();
    }
    
    /**
     * 创建选词覆盖层
     */
    private void createSelectionOverlay() {
        // 创建滚动视图容器
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
        
        // 将文本视图添加到滚动容器中
        mSelectionOverlay.addView(mTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        
        // 设置滚动容器的布局参数
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
        mScrollPosition = 0;
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
        try {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            String[] lines = text.split("\n");
            
            int lineSpacing = 5;
            boolean lastLineWasEmpty = false;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                // 处理空行
                if (line.isEmpty()) {
                    if (!lastLineWasEmpty && i > 0) {
                        builder.append("\n");
                        lastLineWasEmpty = true;
                    }
                    continue;
                }
                
                lastLineWasEmpty = false;
                
                // 处理不同类型的行
                if (line.startsWith("【") && line.contains("】")) {
                    applyTitleStyle(builder, line);
                } else if (isPhoneticLine(line)) {
                    applyPhoneticStyle(builder, line);
                } else if (line.contains("：") && line.length() < 20) {
                    applySectionTitleStyle(builder, line);
                } else if (line.startsWith("注意：")) {
                    applyWarningStyle(builder, line);
                } else if (line.startsWith("•")) {
                    applyListItemStyle(builder, line);
                } else if (line.matches("^\\d+\\..*")) {
                    applyNumberedListStyle(builder, line);
                } else {
                    applyNormalStyle(builder, line);
                }
                
                // 除了最后一行，每行后面添加换行符
                if (i < lines.length - 1) {
                    builder.append("\n");
                    if (!(line.startsWith("【") && line.contains("】"))) {
                        if (line.contains("：") && line.length() < 20) {
                            builder.append("\n");
                        }
                    }
                }
            }
            
            mTextView.setText(builder);
            mTextView.setLineSpacing(lineSpacing, 1.1f);
            
            // 动态调整窗口大小
            mTextView.post(this::adjustOverlaySize);
            
        } catch (Exception e) {
            Log.w(TAG, "应用文本样式失败: " + e.getMessage());
            mTextView.setText(text);
            mTextView.post(this::setDefaultSize);
        }
    }
    
    /**
     * 应用标题样式
     */
    private void applyTitleStyle(SpannableStringBuilder builder, String line) {
        int bracketEnd = line.indexOf("】") + 1;
        String titlePart = line.substring(0, bracketEnd);
        String afterTitle = "";
        
        if (bracketEnd < line.length()) {
            afterTitle = line.substring(bracketEnd);
        }
        
        // 处理标题部分
        SpannableString titleSpan = new SpannableString(titlePart);
        titleSpan.setSpan(new ForegroundColorSpan(Color.rgb(255, 200, 0)), 0, titlePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleSpan.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, titlePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleSpan.setSpan(new RelativeSizeSpan(1.2f), 0, titlePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(titleSpan);
        
        // 处理标题后的内容（如果有），主要是音标
        if (!afterTitle.isEmpty()) {
            if (afterTitle.contains("[") && afterTitle.contains("]")) {
                SpannableString phoneticsSpan = new SpannableString(afterTitle);
                phoneticsSpan.setSpan(new ForegroundColorSpan(Color.CYAN), 0, afterTitle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                phoneticsSpan.setSpan(new RelativeSizeSpan(0.9f), 0, afterTitle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.append(phoneticsSpan);
            } else {
                builder.append(afterTitle);
            }
        }
    }
    
    /**
     * 检查是否是音标行
     */
    private boolean isPhoneticLine(String line) {
        return (line.contains("[") && line.contains("]") && 
                (line.contains("音标") || line.contains("美") || line.contains("英"))) ||
               line.matches(".*(?:美式发音|美音|音标)[:：].*\\[.*\\].*");
    }
    
    /**
     * 应用音标样式
     */
    private void applyPhoneticStyle(SpannableStringBuilder builder, String line) {
        SpannableString ss = new SpannableString(line);
        ss.setSpan(new ForegroundColorSpan(Color.CYAN), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(ss);
    }
    
    /**
     * 应用段落标题样式
     */
    private void applySectionTitleStyle(SpannableStringBuilder builder, String line) {
        SpannableString ss = new SpannableString(line);
        ss.setSpan(new ForegroundColorSpan(Color.rgb(255, 165, 0)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new RelativeSizeSpan(1.1f), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(ss);
    }
    
    /**
     * 应用警告样式
     */
    private void applyWarningStyle(SpannableStringBuilder builder, String line) {
        SpannableString ss = new SpannableString(line);
        ss.setSpan(new ForegroundColorSpan(Color.rgb(255, 100, 100)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(ss);
    }
    
    /**
     * 应用列表项样式
     */
    private void applyListItemStyle(SpannableStringBuilder builder, String line) {
        SpannableString ss = new SpannableString("  " + line);
        ss.setSpan(new ForegroundColorSpan(Color.WHITE), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(Color.rgb(120, 230, 120)), 2, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(ss);
    }
    
    /**
     * 应用编号列表样式
     */
    private void applyNumberedListStyle(SpannableStringBuilder builder, String line) {
        int dotIndex = line.indexOf('.');
        if (dotIndex > 0) {
            SpannableString numberPart = new SpannableString(line.substring(0, dotIndex+1) + " ");
            numberPart.setSpan(new ForegroundColorSpan(Color.rgb(180, 180, 255)), 0, numberPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            numberPart.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, numberPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            SpannableString examplePart = new SpannableString(line.substring(dotIndex+1).trim());
            examplePart.setSpan(new ForegroundColorSpan(Color.LTGRAY), 0, examplePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            examplePart.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, examplePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            builder.append(numberPart);
            builder.append(examplePart);
        } else {
            applyNormalStyle(builder, line);
        }
    }
    
    /**
     * 应用普通样式
     */
    private void applyNormalStyle(SpannableStringBuilder builder, String line) {
        SpannableString ss = new SpannableString(line);
        ss.setSpan(new ForegroundColorSpan(Color.rgb(220, 220, 220)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(ss);
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
        
        // 重置滚动位置
        mScrollPosition = 0;
        mTextView.scrollTo(0, 0);
    }
    
    /**
     * 设置默认大小
     */
    private void setDefaultSize() {
        try {
            ViewGroup.LayoutParams params = mSelectionOverlay.getLayoutParams();
            if (params != null) {
                int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
                params.width = (int)(screenWidth * 0.7f);
                mSelectionOverlay.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "调整默认大小失败: " + e.getMessage());
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
    
    /**
     * 按页滚动解释窗口
     */
    public void scrollDefinitionByPage(int direction) {
        if (mTextView != null) {
            float actualLineHeight = mTextView.getLineHeight();
            int viewHeight = mTextView.getHeight() - mTextView.getPaddingTop() - mTextView.getPaddingBottom();
            int totalLines = mTextView.getLineCount();
            
            int firstVisibleLine = 0;
            int lastVisibleLine = 0;
            
            try {
                android.text.Layout layout = mTextView.getLayout();
                if (layout != null) {
                    firstVisibleLine = layout.getLineForVertical(mScrollPosition);
                    lastVisibleLine = layout.getLineForVertical(mScrollPosition + viewHeight);
                    
                    if (mScrollPosition + viewHeight < layout.getLineBottom(lastVisibleLine)) {
                        lastVisibleLine--;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "计算可见行出错: " + e.getMessage());
            }
            
            int targetLine;
            if (direction > 0) {
                // 向下滚动
                targetLine = Math.min(lastVisibleLine + 1, totalLines - 1);
                int linesToShow = Math.max(1, (int)(viewHeight / actualLineHeight * 0.8));
                targetLine = Math.min(targetLine + linesToShow, totalLines - 1);
                
                try {
                    if (mTextView.getLayout() != null) {
                        mScrollPosition = mTextView.getLayout().getLineTop(targetLine);
                        
                        if (targetLine == totalLines - 1) {
                            int lineBottom = mTextView.getLayout().getLineBottom(targetLine);
                            if (mScrollPosition + viewHeight < lineBottom) {
                                mScrollPosition = lineBottom - viewHeight;
                                mScrollPosition += mTextView.getPaddingBottom();
                            }
                        }
                    }
                } catch (Exception e) {
                    mScrollPosition += viewHeight;
                }
            } else {
                // 向上滚动
                targetLine = Math.max(firstVisibleLine - 1, 0);
                int linesToShow = Math.max(1, (int)(viewHeight / actualLineHeight * 0.8));
                targetLine = Math.max(targetLine - linesToShow, 0);
                
                try {
                    if (mTextView.getLayout() != null) {
                        mScrollPosition = mTextView.getLayout().getLineTop(targetLine);
                    }
                } catch (Exception e) {
                    mScrollPosition -= viewHeight;
                }
            }
            
            // 确保不滚动超出范围
            if (mScrollPosition < 0) {
                mScrollPosition = 0;
            }
            
            int textHeight = totalLines * (int)actualLineHeight;
            int extraBottomPadding = (int)(actualLineHeight * 0.3);
            int maxScroll = Math.max(0, textHeight - viewHeight + extraBottomPadding);
            
            if (mScrollPosition > maxScroll) {
                mScrollPosition = maxScroll;
            }
            
            // 应用滚动
            mTextView.scrollTo(0, mScrollPosition);
            mTextView.setLineSpacing(0, 0.9f);
        }
    }
}