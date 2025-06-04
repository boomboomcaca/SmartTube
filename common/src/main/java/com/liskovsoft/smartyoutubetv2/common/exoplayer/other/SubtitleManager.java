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
import java.lang.reflect.Field;

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
    // 添加字幕结束时间相关变量
    private long mCurrentSubEndTimeUs = -1;
    // 添加标志位，跟踪选词状态
    private boolean mWordSelectionActive = false;
    // 添加周期性检查机制
    private static final long CHECK_INTERVAL_MS = 50; // 每50毫秒检查一次，提高检查频率
    // 添加字幕ID跟踪
    private int mCurrentSubtitleId = 0;
    private CharSequence mCurrentSubtitleText = null;
    // 添加字幕类型跟踪
    private String mCurrentSubtitleType = null;
    
    // 添加额外的备用触发计时器
    private long mLastSubtitleChangeTimeMs = 0;
    private static final long BACKUP_TRIGGER_DELAY_MS = 3000; // 备用触发时间，字幕出现3秒后尝试触发
    
    private final Runnable mPeriodicCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkSubtitleEndTimeAndSelect();
            // 继续安排下一次检查，只要有活动的字幕
            if (mHasActiveCues && mPlayer != null) {
                mHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

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
            Log.d(TAG, "字幕选词控制器初始化成功");
        } else {
            Log.e(TAG, "字幕选词控制器初始化失败: rootView=" + (rootView != null) + ", mSubtitleView=" + (mSubtitleView != null));
        }
        
        // 初始化自动选词定时器
        mAutoSelectWordRunnable = new Runnable() {
            @Override
            public void run() {
                if (mHasActiveCues && mWordSelectionController != null && 
                        mPlayerData.isAutoSelectLastWordEnabled() && 
                        !mWordSelectionController.isInWordSelectionMode() && 
                        !mWordSelectionActive) { // 添加标志位检查
                    Log.d(TAG, "定时器触发自动选词");
                    enterWordSelectionModeAndTrack();
                }
            }
        };
    }

    /**
     * 进入选词模式并跟踪状态
     */
    private void enterWordSelectionModeAndTrack() {
        if (mWordSelectionController != null && !mWordSelectionActive) {
            mWordSelectionActive = true; // 设置标志位，避免重复触发
            Log.d(TAG, "进入选词模式并标记状态 - 字幕ID: " + mCurrentSubtitleId);
            mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
        }
    }
    
    /**
     * 退出选词模式并重置状态
     */
    private void exitWordSelectionModeAndReset() {
        if (mWordSelectionController != null && mWordSelectionActive) {
            if (mWordSelectionController.isInWordSelectionMode()) {
                mWordSelectionController.exitWordSelectionMode();
                Log.d(TAG, "退出选词模式 - 字幕ID: " + mCurrentSubtitleId);
            }
            resetWordSelectionState();
        }
    }
    
    /**
     * 检查字幕结束时间并选择单词
     */
    private void checkSubtitleEndTimeAndSelect() {
        if (!mHasActiveCues || mPlayer == null || mWordSelectionActive || 
            mWordSelectionController == null || !mPlayerData.isAutoSelectLastWordEnabled() || 
            mWordSelectionController.isInWordSelectionMode()) {
            return;
        }
        
        // 当前播放位置
        long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
        
        // 如果有明确的结束时间
        if (mCurrentSubEndTimeUs > 0) {
            long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
            
            // 改进：在距离结束前0.1秒到0.01秒之间触发，增加触发成功率窗口
            if (remainingUs > 0 && remainingUs < 100000) { // 0.1秒内
                Log.d(TAG, "周期性检查: 字幕即将结束（剩余" + (remainingUs/1000) + "毫秒），触发自动选词 - 字幕ID: " + 
                      mCurrentSubtitleId + ", 类型: " + mCurrentSubtitleType);
                enterWordSelectionModeAndTrack();
            }
        } else {
            // 备用机制：如果无法获取结束时间，使用经过时间作为触发依据
            long elapsedMs = System.currentTimeMillis() - mLastSubtitleChangeTimeMs;
            if (elapsedMs >= BACKUP_TRIGGER_DELAY_MS) {
                Log.d(TAG, "备用触发机制: 字幕显示时间已达" + BACKUP_TRIGGER_DELAY_MS + "毫秒，触发自动选词 - 字幕ID: " + 
                      mCurrentSubtitleId);
                enterWordSelectionModeAndTrack();
                
                // 避免重复触发备用机制
                mLastSubtitleChangeTimeMs = Long.MAX_VALUE;
            }
        }
    }

    /**
     * 设置播放器引用，用于获取当前播放位置
     */
    public void setPlayer(SimpleExoPlayer player) {
        mPlayer = player;
        Log.d(TAG, "设置播放器: " + (player != null ? "成功" : "null"));
    }
    
    /**
     * 重置选词状态，为下一个字幕准备
     */
    private void resetWordSelectionState() {
        mWordSelectionActive = false;
        Log.d(TAG, "重置选词状态，为下一个字幕准备 - 当前字幕ID: " + mCurrentSubtitleId);
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }
    
    /**
     * 生成字幕的唯一ID
     */
    private int generateSubtitleId(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.text != null) {
                sb.append(cue.text.toString());
            }
        }
        return sb.toString().hashCode();
    }

    @Override
    public void onCues(List<Cue> cues) {
        // 调试日志：记录每次字幕事件
        Log.d(TAG, "onCues: 收到字幕事件, 字幕数量: " + (cues != null ? cues.size() : 0));
        
        // 检查当前是否在选词模式，如果是且字幕变化，则退出选词模式
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            Log.d(TAG, "检测到字幕变化时已在选词模式，退出当前选词模式");
            exitWordSelectionModeAndReset();
        }
        
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
            
            // 生成当前字幕的ID
            int newSubtitleId = 0;
            CharSequence newSubtitleText = null;
            if (!cues.isEmpty() && cues.get(0).text != null) {
                newSubtitleId = generateSubtitleId(cues);
                newSubtitleText = cues.get(0).text;
                
                // 调试日志：显示字幕内容和类型
                if (!cues.isEmpty()) {
                    String cueClassName = cues.get(0).getClass().getName();
                    Log.d(TAG, "字幕类型: " + cueClassName + ", 文本: " + newSubtitleText);
                }
            }
            
            // 字幕变化检测和自动选词功能
            if (mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) {
                if (!cues.isEmpty() && !mHasActiveCues) {
                    // 新字幕出现 - 重置选词状态
                    mHasActiveCues = true;
                    mWordSelectionActive = false;
                    mLastSubtitleChangeTimeMs = System.currentTimeMillis(); // 记录字幕变化时间
                    
                    // 更新字幕ID和文本
                    mCurrentSubtitleId = newSubtitleId;
                    mCurrentSubtitleText = newSubtitleText;
                    Log.d(TAG, "新字幕出现，ID: " + mCurrentSubtitleId + ", 文本: " + (newSubtitleText != null ? newSubtitleText.toString() : "null"));
                    
                    // 获取字幕的结束时间（使用新的辅助方法）
                    mCurrentSubEndTimeUs = -1;
                    if (!cues.isEmpty()) {
                        for (Cue cue : cues) {
                            long endTime = getSubtitleEndTime(cue);
                            if (endTime > 0) {
                                mCurrentSubEndTimeUs = endTime;
                                Log.d(TAG, "获取到字幕结束时间: " + mCurrentSubEndTimeUs + "微秒 - 字幕ID: " + mCurrentSubtitleId + 
                                      ", 类型: " + mCurrentSubtitleType);
                                break;
                            }
                        }
                    }
                    
                    // 如果获取到结束时间，计划在结束前0.01秒触发选词
                    if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                        long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
                        long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
                        
                        Log.d(TAG, "字幕总剩余时间: " + remainingUs/1000 + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                        
                        if (remainingUs > 10000) { // 如果剩余时间大于0.01秒
                            long delayMs = (remainingUs - 10000) / 1000; // 转换为毫秒
                            // 修正：确保延迟至少为100毫秒，避免太快触发
                            delayMs = Math.max(delayMs, 100);
                            Log.d(TAG, "计划在字幕结束前0.01秒选词，延迟: " + delayMs + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                            
                            // 取消之前的定时任务
                            mHandler.removeCallbacks(mAutoSelectWordRunnable);
                            
                            // 安排新的定时任务
                            mHandler.postDelayed(mAutoSelectWordRunnable, delayMs);
                        } else if (remainingUs > 0) {
                            // 如果剩余时间小于0.01秒但大于0，立即触发
                            Log.d(TAG, "字幕剩余时间小于0.01秒，立即触发选词 - 字幕ID: " + mCurrentSubtitleId);
                            mHandler.removeCallbacks(mAutoSelectWordRunnable);
                            mHandler.post(mAutoSelectWordRunnable);
                        }
                    } else {
                        // 如果无法获取结束时间，使用固定延迟（保留原有逻辑作为备选）
                        Log.d(TAG, "无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                        mHandler.removeCallbacks(mAutoSelectWordRunnable);
                        mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                        
                        // 备用机制：启用基于时长的备用触发（通过检查机制实现）
                        Log.d(TAG, "启用备用触发机制，将在字幕显示" + BACKUP_TRIGGER_DELAY_MS + "毫秒后尝试触发");
                    }
                    
                    // 启动周期性检查
                    mHandler.removeCallbacks(mPeriodicCheckRunnable);
                    mHandler.post(mPeriodicCheckRunnable);
                    
                } else if (!cues.isEmpty() && mHasActiveCues && newSubtitleId != mCurrentSubtitleId && newSubtitleId != 0) {
                    // 字幕内容变化但未消失（新的字幕替换旧的）
                    Log.d(TAG, "字幕内容变化: 旧ID=" + mCurrentSubtitleId + ", 新ID=" + newSubtitleId);
                    // 退出旧字幕的选词模式
                    exitWordSelectionModeAndReset();
                    // 更新当前字幕ID和文本
                    mCurrentSubtitleId = newSubtitleId;
                    mCurrentSubtitleText = newSubtitleText;
                    mLastSubtitleChangeTimeMs = System.currentTimeMillis(); // 更新字幕变化时间
                    
                    // 获取新字幕的结束时间
                    mCurrentSubEndTimeUs = -1;
                    if (!cues.isEmpty()) {
                        for (Cue cue : cues) {
                            long endTime = getSubtitleEndTime(cue);
                            if (endTime > 0) {
                                mCurrentSubEndTimeUs = endTime;
                                Log.d(TAG, "字幕变化：获取到新字幕结束时间: " + mCurrentSubEndTimeUs + "微秒 - 字幕ID: " + 
                                      mCurrentSubtitleId + ", 类型: " + mCurrentSubtitleType);
                                break;
                            }
                        }
                    }
                    
                    // 设置新的定时器
                    if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                        long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
                        long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
                        
                        Log.d(TAG, "字幕变化：字幕总剩余时间: " + remainingUs/1000 + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                        
                        if (remainingUs > 10000) { // 如果剩余时间大于0.01秒
                            long delayMs = (remainingUs - 10000) / 1000; // 转换为毫秒
                            // 修正：确保延迟至少为100毫秒，避免太快触发
                            delayMs = Math.max(delayMs, 100);
                            Log.d(TAG, "字幕变化：计划在字幕结束前0.01秒选词，延迟: " + delayMs + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                            
                            // 取消之前的定时任务
                            mHandler.removeCallbacks(mAutoSelectWordRunnable);
                            
                            // 安排新的定时任务
                            mHandler.postDelayed(mAutoSelectWordRunnable, delayMs);
                        } else if (remainingUs > 0) {
                            // 如果剩余时间小于0.01秒但大于0，立即触发
                            Log.d(TAG, "字幕变化：字幕剩余时间小于0.01秒，立即触发选词 - 字幕ID: " + mCurrentSubtitleId);
                            mHandler.removeCallbacks(mAutoSelectWordRunnable);
                            mHandler.post(mAutoSelectWordRunnable);
                        }
                    } else {
                        // 如果无法获取结束时间，使用固定延迟
                        Log.d(TAG, "字幕变化：无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒 - 字幕ID: " + mCurrentSubtitleId);
                        mHandler.removeCallbacks(mAutoSelectWordRunnable);
                        mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                    }
                    
                } else if (cues.isEmpty() && mHasActiveCues) {
                    // 字幕消失 - 重置状态，为下一个字幕做准备
                    Log.d(TAG, "字幕已消失，ID: " + mCurrentSubtitleId);
                    mHasActiveCues = false;
                    mCurrentSubEndTimeUs = -1;
                    mCurrentSubtitleId = 0;
                    mCurrentSubtitleText = null;
                    mCurrentSubtitleType = null;
                    mLastSubtitleChangeTimeMs = 0; // 重置字幕变化时间
                    resetWordSelectionState();
                    
                    // 确保选词模式已退出
                    exitWordSelectionModeAndReset();
                    
                    Log.d(TAG, "取消自动选词计划和周期检查");
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    mHandler.removeCallbacks(mPeriodicCheckRunnable);
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

    /**
     * 根据字幕对象获取结束时间
     * @param cue 字幕对象
     * @return 结束时间（微秒），如果无法获取则返回-1
     */
    private long getSubtitleEndTime(Cue cue) {
        if (cue == null) {
            return -1;
        }
        
        // 记录字幕类型用于调试
        mCurrentSubtitleType = cue.getClass().getSimpleName();
        String fullClassName = cue.getClass().getName();
        Log.d(TAG, "尝试从字幕类型获取结束时间: " + fullClassName);
        
        // 处理WebVTT格式
        if (cue instanceof com.google.android.exoplayer2.text.webvtt.WebvttCue) {
            com.google.android.exoplayer2.text.webvtt.WebvttCue webvttCue = 
                    (com.google.android.exoplayer2.text.webvtt.WebvttCue) cue;
            Log.d(TAG, "WebVTT字幕: endTime=" + webvttCue.endTime);
            return webvttCue.endTime;
        }
        
        // 尝试通过反射获取其他格式字幕的结束时间
        try {
            // 尝试访问更多可能的结束时间字段名
            String[] possibleFieldNames = {
                "endTimeUs", "endTime", "timeOut", "durationUs", "end", "endMs", 
                "endTimestamp", "timeEnd", "duration", "exitTime", "timeExit"
            };
            
            for (String fieldName : possibleFieldNames) {
                try {
                    Field field = cue.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(cue);
                    if (value instanceof Number) {
                        long timeValue = ((Number) value).longValue();
                        Log.d(TAG, "找到可能的结束时间字段: " + fieldName + " = " + timeValue);
                        return timeValue;
                    }
                } catch (Exception e) {
                    // 继续尝试下一个字段名
                }
            }
            
            // 特殊处理：尝试获取字段类型为long的成员变量，可能是结束时间
            for (Field field : cue.getClass().getDeclaredFields()) {
                if (field.getType() == long.class || field.getType() == Long.class) {
                    field.setAccessible(true);
                    String fieldName = field.getName().toLowerCase();
                    // 扩展字段名匹配规则
                    if (fieldName.contains("end") || fieldName.contains("time") || 
                        fieldName.contains("duration") || fieldName.contains("exit") ||
                        fieldName.contains("out")) {
                        Object value = field.get(cue);
                        if (value instanceof Number) {
                            long timeValue = ((Number) value).longValue();
                            // 检查值的合理性（作为微秒时间）
                            if (timeValue > 0 && timeValue < 7200000000L) { // 小于2小时
                                Log.d(TAG, "根据字段名推断可能的结束时间字段: " + fieldName + " = " + timeValue);
                                return timeValue;
                            }
                        }
                    }
                }
            }
            
            // 进一步探索：检查父类字段
            Class<?> parentClass = cue.getClass().getSuperclass();
            if (parentClass != null && !parentClass.equals(Object.class)) {
                for (Field field : parentClass.getDeclaredFields()) {
                    if (field.getType() == long.class || field.getType() == Long.class) {
                        field.setAccessible(true);
                        String fieldName = field.getName().toLowerCase();
                        if (fieldName.contains("end") || fieldName.contains("time") || 
                            fieldName.contains("duration") || fieldName.contains("exit") ||
                            fieldName.contains("out")) {
                            Object value = field.get(cue);
                            if (value instanceof Number) {
                                long timeValue = ((Number) value).longValue();
                                if (timeValue > 0 && timeValue < 7200000000L) {
                                    Log.d(TAG, "从父类获取到可能的结束时间字段: " + fieldName + " = " + timeValue);
                                    return timeValue;
                                }
                            }
                        }
                    }
                }
            }
            
            // 尝试从字幕文本估算时长（如果包含时间格式如 "00:01:23,456"）
            if (cue.text != null) {
                String text = cue.text.toString();
                // 这里可以添加更多复杂的时间提取逻辑，但这需要更多的上下文
            }
            
        } catch (Exception e) {
            Log.d(TAG, "无法通过反射获取字幕结束时间: " + e.getMessage());
        }
        
        Log.d(TAG, "无法获取字幕结束时间，将使用备用触发机制");
        return -1;
    }
}
