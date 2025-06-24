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
    
    // 修改：增加额外的状态跟踪变量
    private long mCurrentSubEndTimeUs = -1;
    private boolean mWordSelectionActive = false;
    private boolean mWordSelectionPending = false; // 新增：选词操作等待执行标志
    private boolean mIsAutomaticSubtitles = false; // 新增：标记是否为自动生成字幕
    private static final long CHECK_INTERVAL_MS = 100; // 修改为100毫秒检查一次
    private int mCurrentSubtitleId = 0;
    private CharSequence mCurrentSubtitleText = null;
    private String mCurrentSubtitleType = null;
    private long mLastSubtitleChangeTimeMs = 0;
    private static final long BACKUP_TRIGGER_DELAY_MS = 3000; // 备用触发时间
    
    // 修改：增加锁定时间，防止短时间内重复触发
    private long mLastSelectionTimeMs = 0;
    private static final long SELECTION_COOLDOWN_MS = 2000; // 2秒内不重复触发
    
    private final Runnable mPeriodicCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkSubtitleEndTimeAndSelect();
            // 继续安排下一次检查，只要有活动的字幕
            if (mHasActiveCues && mPlayer != null && !mWordSelectionActive) {
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
        
        // 启用学习中单词高亮功能
        try {
            Class<?> subtitlePainterClass = Class.forName("com.google.android.exoplayer2.ui.SubtitlePainter");
            java.lang.reflect.Field enableLearningWordHighlightField = subtitlePainterClass.getField("ENABLE_LEARNING_WORD_HIGHLIGHT");
            enableLearningWordHighlightField.setAccessible(true);
            enableLearningWordHighlightField.set(null, true);
            Log.d(TAG, "学习中单词高亮功能已启用");
        } catch (Exception e) {
            Log.e(TAG, "启用学习中单词高亮功能失败", e);
        }
        
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
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - mLastSelectionTimeMs < SELECTION_COOLDOWN_MS) {
            Log.d(TAG, "冷却时间内，忽略选词请求 - 上次触发: " + (now - mLastSelectionTimeMs) + "毫秒前");
            return;
        }
        
        if (mWordSelectionController != null && !mWordSelectionActive && !mWordSelectionPending) {
            mWordSelectionPending = true; // 标记选词操作为等待状态
            
            // 取消所有可能的后续触发
            mHandler.removeCallbacks(mAutoSelectWordRunnable);
            
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!mWordSelectionController.isInWordSelectionMode()) {
                        mWordSelectionActive = true; // 设置标志位，避免重复触发
                        mLastSelectionTimeMs = System.currentTimeMillis();
                        Log.d(TAG, "进入选词模式并标记状态 - 字幕ID: " + mCurrentSubtitleId);
                        mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
                    }
                    mWordSelectionPending = false; // 无论是否成功，都重置等待状态
                }
            });
        }
    }
    
    /**
     * 退出选词模式并重置状态
     */
    private void exitWordSelectionModeAndReset() {
        if (mWordSelectionController != null) {
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
        if (!mHasActiveCues || mPlayer == null || mWordSelectionActive || mWordSelectionPending || 
            mWordSelectionController == null || !mPlayerData.isAutoSelectLastWordEnabled() || 
            mWordSelectionController.isInWordSelectionMode()) {
            return;
        }
        
        // 当前播放位置
        long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
        
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - mLastSelectionTimeMs < SELECTION_COOLDOWN_MS) {
            return;
        }
        
        // 如果有明确的结束时间
        if (mCurrentSubEndTimeUs > 0) {
            long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
            
            // 改进：在距离结束前0.2秒内触发，增加触发窗口
            if (remainingUs > 0 && remainingUs < 200000) { // 0.2秒内
                Log.d(TAG, "周期性检查: 字幕即将结束（剩余" + (remainingUs/1000) + "毫秒），触发自动选词");
                enterWordSelectionModeAndTrack();
            }
        } else if (mIsAutomaticSubtitles) {
            // 自动生成字幕的备用机制
            long elapsedMs = System.currentTimeMillis() - mLastSubtitleChangeTimeMs;
            if (elapsedMs >= BACKUP_TRIGGER_DELAY_MS) {
                Log.d(TAG, "自动生成字幕备用触发: 字幕显示时间已达" + BACKUP_TRIGGER_DELAY_MS + "毫秒");
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
        mWordSelectionPending = false;
        Log.d(TAG, "重置选词状态，为下一个字幕准备");
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }
    
    /**
     * 生成字幕的唯一ID，改进识别自动生成字幕的能力
     */
    private int generateSubtitleId(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) {
            return 0;
        }
        
        // 检查是否是自动生成字幕
        mIsAutomaticSubtitles = isAutoGeneratedSubtitle(cues);
        
        StringBuilder sb = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.text != null) {
                sb.append(cue.text.toString());
            }
        }
        return sb.toString().hashCode();
    }
    
    /**
     * 判断是否为自动生成字幕
     */
    private boolean isAutoGeneratedSubtitle(List<Cue> cues) {
        if (cues == null || cues.size() <= 1) {
            return false;
        }
        
        // 自动生成字幕通常是一个单词一个Cue
        int singleWordCount = 0;
        for (Cue cue : cues) {
            if (cue.text != null) {
                String text = cue.text.toString().trim();
                if (!text.isEmpty() && !text.contains(" ") && !text.contains("\n")) {
                    singleWordCount++;
                }
            }
        }
        
        // 如果超过一半的Cue是单词，判定为自动生成字幕
        boolean isAuto = singleWordCount > cues.size() / 2;
        if (isAuto) {
            Log.d(TAG, "检测到自动生成字幕，单词数: " + singleWordCount + ", 总Cue数: " + cues.size());
        }
        return isAuto;
    }

    @Override
    public void onCues(List<Cue> cues) {
        // 调试日志：记录每次字幕事件
        Log.d(TAG, "onCues: 收到字幕事件, 字幕数量: " + (cues != null ? cues.size() : 0));
        
        // 检查当前是否在选词模式，如果是且字幕变化，则退出选词模式
        if (mWordSelectionController != null && 
            (mWordSelectionController.isInWordSelectionMode() || mWordSelectionActive || mWordSelectionPending)) {
            Log.d(TAG, "检测到字幕变化时已在选词模式或选词过程中，退出当前选词模式");
            exitWordSelectionModeAndReset();
            
            // 取消所有可能的后续选词操作
            mHandler.removeCallbacks(mAutoSelectWordRunnable);
            mHandler.removeCallbacks(mPeriodicCheckRunnable);
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
            }
            
            // 字幕变化检测和自动选词功能
            if (mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) {
                if (!cues.isEmpty() && !mHasActiveCues) {
                    // 新字幕出现 - 重置选词状态
                    mHasActiveCues = true;
                    resetWordSelectionState();
                    mLastSubtitleChangeTimeMs = System.currentTimeMillis(); // 记录字幕变化时间
                    
                    // 更新字幕ID和文本
                    mCurrentSubtitleId = newSubtitleId;
                    mCurrentSubtitleText = newSubtitleText;
                    Log.d(TAG, "新字幕出现，ID: " + mCurrentSubtitleId + 
                          ", 自动生成: " + mIsAutomaticSubtitles + 
                          ", 文本: " + (newSubtitleText != null ? newSubtitleText.toString() : "null"));
                    
                    // 获取字幕的结束时间
                    mCurrentSubEndTimeUs = -1;
                    if (!cues.isEmpty()) {
                        for (Cue cue : cues) {
                            long endTime = getSubtitleEndTime(cue);
                            if (endTime > 0) {
                                mCurrentSubEndTimeUs = endTime;
                                Log.d(TAG, "获取到字幕结束时间: " + mCurrentSubEndTimeUs + "微秒");
                                break;
                            }
                        }
                    }
                    
                    // 取消所有现有定时器
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    mHandler.removeCallbacks(mPeriodicCheckRunnable);
                    
                    // 针对自动生成字幕和普通字幕分别处理
                    if (mIsAutomaticSubtitles) {
                        // 自动生成字幕：使用备用触发机制，减少重复触发风险
                        Log.d(TAG, "自动生成字幕：使用备用触发机制");
                        // 启动周期性检查
                        mHandler.post(mPeriodicCheckRunnable);
                    } else {
                        // 普通字幕：可以使用更准确的定时器
                        if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                            long currentPositionUs = mPlayer.getCurrentPosition() * 1000; // 转换为微秒
                            long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
                            
                            if (remainingUs > 200000) { // 如果剩余时间大于0.2秒
                                long delayMs = (remainingUs - 200000) / 1000; // 转换为毫秒
                                delayMs = Math.max(delayMs, 100); // 确保至少100毫秒
                                Log.d(TAG, "计划在字幕结束前0.2秒选词，延迟: " + delayMs + "毫秒");
                                mHandler.postDelayed(mAutoSelectWordRunnable, delayMs);
                            } else if (remainingUs > 0) {
                                // 如果剩余时间小于0.2秒但大于0，立即触发
                                Log.d(TAG, "字幕剩余时间小于0.2秒，立即触发选词");
                                mHandler.post(mAutoSelectWordRunnable);
                            }
                        } else {
                            // 无法获取结束时间，使用固定延迟
                            Log.d(TAG, "无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒");
                            mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                        }
                        
                        // 同时启用周期性检查作为备用
                        mHandler.post(mPeriodicCheckRunnable);
                    }
                } else if (!cues.isEmpty() && mHasActiveCues && newSubtitleId != mCurrentSubtitleId && newSubtitleId != 0) {
                    // 字幕内容变化但未消失（新的字幕替换旧的）
                    Log.d(TAG, "字幕内容变化: 旧ID=" + mCurrentSubtitleId + 
                          ", 新ID=" + newSubtitleId + 
                          ", 自动生成: " + mIsAutomaticSubtitles);
                    
                    // 取消所有现有的定时器
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    mHandler.removeCallbacks(mPeriodicCheckRunnable);
                    
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
                                Log.d(TAG, "字幕变化：获取到新字幕结束时间: " + mCurrentSubEndTimeUs + "微秒");
                                break;
                            }
                        }
                    }
                    
                    // 针对不同类型字幕采用不同策略
                    if (mIsAutomaticSubtitles) {
                        // 自动生成字幕：仅使用周期性检查，避免重复触发
                        Log.d(TAG, "字幕变化-自动生成字幕：仅使用周期性检查机制");
                        mHandler.post(mPeriodicCheckRunnable);
                    } else {
                        // 普通字幕：设置定时器
                        if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                            long currentPositionUs = mPlayer.getCurrentPosition() * 1000;
                            long remainingUs = mCurrentSubEndTimeUs - currentPositionUs;
                            
                            if (remainingUs > 200000) {
                                long delayMs = (remainingUs - 200000) / 1000;
                                delayMs = Math.max(delayMs, 100);
                                Log.d(TAG, "字幕变化：计划在字幕结束前0.2秒选词，延迟: " + delayMs + "毫秒");
                                mHandler.postDelayed(mAutoSelectWordRunnable, delayMs);
                            } else if (remainingUs > 0) {
                                Log.d(TAG, "字幕变化：字幕剩余时间小于0.2秒，立即触发选词");
                                mHandler.post(mAutoSelectWordRunnable);
                            }
                        } else {
                            Log.d(TAG, "字幕变化：无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒");
                            mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                        }
                        
                        // 同时启用周期性检查作为备用
                        mHandler.post(mPeriodicCheckRunnable);
                    }
                    
                } else if (cues.isEmpty() && mHasActiveCues) {
                    // 字幕消失 - 重置状态，为下一个字幕做准备
                    Log.d(TAG, "字幕已消失，ID: " + mCurrentSubtitleId);
                    mHasActiveCues = false;
                    mCurrentSubEndTimeUs = -1;
                    mCurrentSubtitleId = 0;
                    mCurrentSubtitleText = null;
                    mCurrentSubtitleType = null;
                    mIsAutomaticSubtitles = false;
                    mLastSubtitleChangeTimeMs = 0; // 重置字幕变化时间
                    resetWordSelectionState();
                    
                    // 确保选词模式已退出
                    exitWordSelectionModeAndReset();
                    
                    // 取消所有定时器
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

        // Autogenerated subs fix - 避免字幕重复显示
        boolean isAutoGenerated = isAutoGeneratedSubtitle(cues);
        
        if (isAutoGenerated) {
            // 对于自动生成的字幕，只保留最后一个字幕内容
            CharSequence lastText = null;
            for (int i = cues.size() - 1; i >= 0; i--) {
                Cue cue = cues.get(i);
                if (cue != null && cue.text != null && !cue.text.toString().trim().isEmpty()) {
                    lastText = cue.text;
                    break;
                }
            }
            
            if (lastText != null) {
                // 确保清空之前的 subsBuffer
                subsBuffer = null;
                // 只添加最后一个字幕
                result.add(new Cue(lastText));
            }
        } else {
            // 常规字幕处理
            for (Cue cue : cues) {
                // 将自动生成字幕的重复行修复
                if (cue.text != null) {
                    // 检查字幕是否以换行或空格结尾，这是重复显示的原因
                    if (cue.text.toString().endsWith("\n") || cue.text.toString().endsWith(" ")) {
                        subsBuffer = cue.text;
                    } else {
                        // 如果有之前缓存的文本，从当前文本中移除
                        CharSequence text = subsBuffer != null ? cue.text.toString().replace(subsBuffer.toString(), "") : cue.text;
                        result.add(new Cue(text)); // sub centered by default
                        subsBuffer = null;
                    }
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
        
        // 处理WebVTT格式
        if (cue instanceof com.google.android.exoplayer2.text.webvtt.WebvttCue) {
            com.google.android.exoplayer2.text.webvtt.WebvttCue webvttCue = 
                    (com.google.android.exoplayer2.text.webvtt.WebvttCue) cue;
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
                                    return timeValue;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "无法通过反射获取字幕结束时间: " + e.getMessage());
        }
        
        return -1;
    }

    public void setSubtitleView(SubtitleView subtitleView) {
        // 我们不能重新赋值给final变量mSubtitleView，仅启用学习中单词高亮功能
        
        // 使用addOnLayoutChangeListener监听布局变化
        if (mSubtitleView != null) {
            mSubtitleView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                // 如果尺寸发生变化，调整轨道之间的间距
                if (oldBottom - oldTop != bottom - top || oldRight - oldLeft != right - left) {
                    updateTrackSpacing();
                }
            });
            
            // 启用学习中单词高亮功能
            try {
                Class<?> subtitlePainterClass = Class.forName("com.google.android.exoplayer2.ui.SubtitlePainter");
                java.lang.reflect.Field enableLearningWordHighlightField = subtitlePainterClass.getField("ENABLE_LEARNING_WORD_HIGHLIGHT");
                enableLearningWordHighlightField.setAccessible(true);
                enableLearningWordHighlightField.set(null, true);
                Log.d(TAG, "学习中单词高亮功能已启用");
            } catch (Exception e) {
                Log.e(TAG, "启用学习中单词高亮功能失败", e);
            }
        }
    }
    
    /**
     * 更新轨道间距
     */
    private void updateTrackSpacing() {
        // 如果需要调整轨道间距的实现，可以在这里添加
        Log.d(TAG, "更新字幕轨道间距");
    }

    /**
     * 释放资源
     * 在活动销毁时调用
     */
    public void release() {
        // 释放字幕选词控制器资源
        if (mWordSelectionController != null) {
            mWordSelectionController.release();
            mWordSelectionController = null;
            Log.d(TAG, "字幕选词控制器资源已释放");
        }
        
        // 移除所有回调和定时器
        if (mHandler != null) {
            mHandler.removeCallbacks(mAutoSelectWordRunnable);
            mHandler.removeCallbacks(mPeriodicCheckRunnable);
            Log.d(TAG, "字幕管理器定时器已移除");
        }
        
        // 重置状态
        resetWordSelectionState();
        
        // 解除播放器引用
        if (mPlayer != null) {
            mPlayer.removeTextOutput(this);
            mPlayer = null;
        }
    }
}
