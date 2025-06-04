package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import java.util.ArrayList;
import java.util.List;

public class SubtitleManager implements TextOutput, OnDataChange {
    private static final String TAG = SubtitleManager.class.getSimpleName();
    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final List<SubtitleStyle> mSubtitleStyles = new ArrayList<>();
    private final AppPrefs mPrefs;
    private final PlayerData mPlayerData;
    private CharSequence subsBuffer;
    private SubtitleWordSelectionController mWordSelectionController;
    private SimpleExoPlayer mPlayer;
    
    // 添加用于定时检测字幕变化的Handler
    private final Handler mHandler = new Handler();
    private final long AUTO_SELECT_DELAY_MS = 4000; // 4秒后自动选词
    private boolean mHasActiveCues = false;
    private Runnable mAutoSelectWordRunnable;

    public static class SubtitleStyle {
        public final int nameResId;
        public final int subsColorResId;
        public final int backgroundColorResId;
        public final int captionStyle;

        public SubtitleStyle(int nameResId) {
            this(nameResId, -1, -1, -1);
        }

        public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
            this.nameResId = nameResId;
            this.subsColorResId = subsColorResId;
            this.backgroundColorResId = backgroundColorResId;
            this.captionStyle = captionStyle;
        }

        public boolean isSystem() {
            return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
        }
    }

    public SubtitleManager(Activity activity, int subViewId) {
        mContext = activity;
        mSubtitleView = activity.findViewById(subViewId);
        mPrefs = AppPrefs.instance(activity);
        mPlayerData = PlayerData.instance(activity);
        mPlayerData.setOnChange(this);
        configureSubtitleView();
        
        // 初始化字幕选词控制器
        FrameLayout rootView = activity.findViewById(R.id.playback_fragment_root);
        if (rootView != null && mSubtitleView != null) {
            mWordSelectionController = new SubtitleWordSelectionController(activity, mSubtitleView, rootView);
        }
        
        // 初始化自动选词定时器
        mAutoSelectWordRunnable = new Runnable() {
            @Override
            public void run() {
                if (mHasActiveCues && mWordSelectionController != null && 
                        mPlayerData.isAutoSelectLastWordEnabled() && 
                        !mWordSelectionController.isInWordSelectionMode()) {
                    Log.d(TAG, "定时器触发自动选词");
                    mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
                }
            }
        };
    }

    /**
     * 设置播放器引用，用于获取当前播放位置
     */
    public void setPlayer(SimpleExoPlayer player) {
        mPlayer = player;
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }

    @Override
    public void onCues(List<Cue> cues) {
        // 更新字幕文本到选词控制器
        if (mWordSelectionController != null) {
            mWordSelectionController.setCurrentSubtitleText(cues);
        }
        
        if (mSubtitleView != null) {
            List<Cue> alignedCues = forceCenterAlignment(cues);
            mSubtitleView.setCues(alignedCues);
            
            // 确保选词控制器使用最新的字幕内容
            if (mWordSelectionController != null && alignedCues != cues) {
                mWordSelectionController.setCurrentSubtitleText(alignedCues);
            }
            
            // 字幕变化检测和自动选词功能
            if (mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) {
                if (!cues.isEmpty() && !mHasActiveCues) {
                    // 新字幕出现
                    mHasActiveCues = true;
                    Log.d(TAG, "检测到新字幕，计划" + AUTO_SELECT_DELAY_MS + "毫秒后自动选词");
                    
                    // 取消之前的定时任务
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    
                    // 安排新的定时任务，在字幕显示后延迟一段时间触发选词
                    mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                } else if (cues.isEmpty() && mHasActiveCues) {
                    // 字幕消失
                    mHasActiveCues = false;
                    Log.d(TAG, "字幕已消失，取消自动选词计划");
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                }
            }
            
            // 原有的WebVTT字幕处理代码（保留调试信息)
            if (mWordSelectionController != null && mPlayerData.isAutoSelectLastWordEnabled() && 
                    !alignedCues.isEmpty() && mPlayer != null) {
                
                boolean foundWebvttCue = false;
                // 查找WebVTT类型的Cue，它们包含结束时间信息
                for (Cue cue : cues) {
                    Log.d(TAG, "处理字幕Cue: " + cue.getClass().getName());
                    
                    if (cue instanceof com.google.android.exoplayer2.text.webvtt.WebvttCue) {
                        foundWebvttCue = true;
                        com.google.android.exoplayer2.text.webvtt.WebvttCue webvttCue = 
                                (com.google.android.exoplayer2.text.webvtt.WebvttCue) cue;
                        
                        // 获取当前播放位置和字幕结束时间
                        long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
                        long endTimeUs = webvttCue.endTime;
                        
                        // 计算剩余时间（微秒）
                        long remainingTimeUs = endTimeUs - currentPositionUs;
                        
                        Log.d(TAG, "WebVTT字幕: 当前位置=" + currentPositionUs + 
                                     "微秒, 结束时间=" + endTimeUs + 
                                     "微秒, 剩余时间=" + remainingTimeUs + "微秒" +
                                     ", 内容: " + cue.text);
                        
                        // 如果字幕即将结束（改为5秒内）且不在选词模式中，则自动进入选词模式并选择最后一个单词
                        if (remainingTimeUs > 0 && remainingTimeUs < 5000000 && !mWordSelectionController.isInWordSelectionMode()) {
                            Log.d(TAG, "字幕即将结束，触发自动选词模式");
                            mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
                        }
                        
                        break; // 找到一个有时间信息的Cue就可以了
                    }
                }
                
                if (!foundWebvttCue) {
                    Log.d(TAG, "未找到WebvttCue类型的字幕，将使用定时器方式");
                }
            }
        }
    }

    public void show(boolean show) {
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 获取字幕选词控制器
     */
    public SubtitleWordSelectionController getWordSelectionController() {
        if (mWordSelectionController == null && mSubtitleView != null) {
            // 创建选词控制器
            FrameLayout rootView = (FrameLayout) mSubtitleView.getParent();
            if (rootView != null) {
                mWordSelectionController = new SubtitleWordSelectionController(mContext, mSubtitleView, rootView);
            }
        }
        return mWordSelectionController;
    }

    private List<SubtitleStyle> getSubtitleStyles() {
        return mSubtitleStyles;
    }

    private SubtitleStyle getSubtitleStyle() {
        return mPlayerData.getSubtitleStyle();
    }

    private void setSubtitleStyle(SubtitleStyle subtitleStyle) {
        mPlayerData.setSubtitleStyle(subtitleStyle);
        configureSubtitleView();
    }

    private List<Cue> forceCenterAlignment(List<Cue> cues) {
        List<Cue> result = new ArrayList<>();

        if (cues == null || cues.isEmpty()) {
            return result;
        }

        // 对于自动生成的字幕，我们需要保留所有的单词
        StringBuilder fullText = new StringBuilder();
        boolean isAutoGenerated = false;

        // 检查是否是自动生成的字幕（通常是一个单词一个 Cue）
        if (cues.size() > 1) {
            boolean allSingleWords = true;
            for (Cue cue : cues) {
                if (cue.text != null) {
                    String text = cue.text.toString();
                    // 考虑 CJK 字符，一个 CJK 字符也可能是一个完整的单词
                    if (text.split("\\s+").length > 1 && !containsOnlyCJK(text)) {
                        allSingleWords = false;
                        break;
                    }
                }
            }
            isAutoGenerated = allSingleWords;
        }

        if (isAutoGenerated) {
            // 合并所有单词到一个完整的字幕
            for (Cue cue : cues) {
                if (cue.text != null) {
                    if (fullText.length() > 0) {
                        fullText.append(" ");
                    }
                    fullText.append(cue.text.toString().trim());
                }
            }
            // 创建一个新的 Cue，包含所有单词
            result.add(new Cue(fullText.toString()));
        } else {
            // 常规字幕处理
            for (Cue cue : cues) {
                // Autogenerated subs repeated lines fix
                // if (cue.text.toString().endsWith("\n")) {
                if (Helpers.endsWithAny(cue.text.toString(), "\n", " ")) {
                    subsBuffer = cue.text;
                } else {
                    CharSequence text = subsBuffer != null ? cue.text.toString().replace(subsBuffer, "") : cue.text;
                    result.add(new Cue(text)); // sub centered by default
                    subsBuffer = null;
                }
            }
        }

        return result;
    }

    /**
     * 检查文本是否只包含 CJK 字符
     * @param text 要检查的文本
     * @return 如果文本只包含 CJK 字符，返回 true
     */
    private boolean containsOnlyCJK(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 如果不是 CJK 字符且不是空白字符，则返回 false
            if (!isCJKChar(c) && !Character.isWhitespace(c)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 检查字符是否为 CJK 字符
     * @param c 要检查的字符
     * @return 如果是 CJK 字符，返回 true
     */
    private boolean isCJKChar(char c) {
        // 中文范围: \u4E00-\u9FFF, 日文片假名: \u3040-\u309F, 韩文: \uAC00-\uD7A3 等
        return (c >= '\u4E00' && c <= '\u9FFF') || // 中文
               (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
               (c >= '\uAC00' && c <= '\uD7A3');   // 韩文
    }

    private void configureSubtitleView() {
        if (mSubtitleView != null) {
            // disable default style
            mSubtitleView.setApplyEmbeddedStyles(false);

            SubtitleStyle subtitleStyle = getSubtitleStyle();

            if (subtitleStyle.isSystem()) {
                if (VERSION.SDK_INT >= 19) {
                    applySystemStyle();
                }
            } else {
                applyStyle(subtitleStyle);
            }

            mSubtitleView.setBottomPaddingFraction(mPlayerData.getSubtitlePosition());
        }
    }

    private void applyStyle(SubtitleStyle subtitleStyle) {
        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int outlineColor = ContextCompat.getColor(mContext, R.color.black);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);

        CaptionStyleCompat style =
                new CaptionStyleCompat(textColor,
                        backgroundColor, Color.TRANSPARENT,
                        subtitleStyle.captionStyle,
                        outlineColor, Typeface.DEFAULT_BOLD);
        mSubtitleView.setStyle(style);

        float textSize = getTextSizePx();
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    }

    @RequiresApi(19)
    private void applySystemStyle() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager != null) {
            CaptionStyle userStyle = captioningManager.getUserStyle();

            CaptionStyleCompat style =
                    new CaptionStyleCompat(userStyle.foregroundColor,
                            userStyle.backgroundColor, VERSION.SDK_INT >= 21 ? userStyle.windowColor : Color.TRANSPARENT,
                            userStyle.edgeType,
                            userStyle.edgeColor, userStyle.getTypeface());
            mSubtitleView.setStyle(style);

            float textSizePx = getTextSizePx();
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * captioningManager.getFontScale());
        }
    }

    private float getTextSizePx() {
        float textSizePx = mSubtitleView.getContext().getResources().getDimension(R.dimen.subtitle_text_size);
        return textSizePx * mPlayerData.getSubtitleScale();
    }
}
