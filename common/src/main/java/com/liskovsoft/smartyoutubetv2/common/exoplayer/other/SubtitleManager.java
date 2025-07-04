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
import android.view.ViewGroup;
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
    private int mCurrentSubtitleId = 0;
    private CharSequence mCurrentSubtitleText = null;
    private String mCurrentSubtitleType = null;
    
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
        FrameLayout rootView = (FrameLayout) activity.findViewById(android.R.id.content);
        if (rootView != null && mSubtitleView != null) {
            mWordSelectionController = new SubtitleWordSelectionController(activity, mSubtitleView, rootView);
            Log.d(TAG, "字幕选词控制器初始化成功");
        } else {
            Log.e(TAG, "字幕选词控制器初始化失败: rootView=" + (rootView != null) + ", mSubtitleView=" + (mSubtitleView != null));
        }
        
        // 初始化自动选词定时器
        initAutoSelectWordRunnable();
    }
    
    /**
     * 为SMB播放器提供的简化构造函数
     * @param subtitleView 字幕视图
     */
    public SubtitleManager(SubtitleView subtitleView) {
        mSubtitleView = subtitleView;
        mContext = subtitleView.getContext();
        mPrefs = AppPrefs.instance(mContext);
        mPlayerData = PlayerData.instance(mContext);
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
        
        // 为SMB播放器创建字幕选词控制器
        // 查找父视图作为根视图
        ViewGroup rootView = null;
        View parent = subtitleView.getParent() instanceof View ? (View) subtitleView.getParent() : null;
        while (parent != null) {
            if (parent instanceof FrameLayout) {
                rootView = (ViewGroup) parent;
                break;
            }
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        
        // 如果找不到合适的根视图，尝试使用Activity的内容视图
        if (rootView == null && mContext instanceof Activity) {
            rootView = ((Activity) mContext).findViewById(android.R.id.content);
        }
        
        if (rootView != null) {
            mWordSelectionController = new SubtitleWordSelectionController(mContext, mSubtitleView, (FrameLayout) rootView);
            Log.d(TAG, "SMB播放器: 字幕选词控制器初始化成功");
        } else {
            Log.e(TAG, "SMB播放器: 无法找到合适的根视图，字幕选词控制器初始化失败");
        }
        
        // 初始化自动选词定时器
        initAutoSelectWordRunnable();
    }
    
    /**
     * 初始化自动选词定时器
     */
    private void initAutoSelectWordRunnable() {
        mAutoSelectWordRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "自动选词任务执行，检查条件: " +
                       "hasActiveCues=" + mHasActiveCues + ", " +
                       "wordController=" + (mWordSelectionController != null) + ", " +
                       "autoSelectEnabled=" + (mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) + ", " + 
                       "inWordSelectionMode=" + (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) + ", " +
                       "wordSelectionActive=" + mWordSelectionActive);
                
                // 只有在字幕结束时才执行自动选词
                if (mHasActiveCues && mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled() && !mWordSelectionActive) {
                    // 如果控制器未初始化，尝试初始化它
                    if (mWordSelectionController == null && mContext instanceof Activity && mSubtitleView != null) {
                        try {
                            FrameLayout rootView = (FrameLayout) ((Activity) mContext).findViewById(android.R.id.content);
                            if (rootView != null) {
                                mWordSelectionController = new SubtitleWordSelectionController(mContext, mSubtitleView, rootView);
                                Log.d(TAG, "自动选词时动态初始化字幕选词控制器成功");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "自动选词时动态初始化字幕选词控制器失败", e);
                        }
                    }
                    
                    // 确认控制器已初始化且不在选词模式中
                    if (mWordSelectionController != null && !mWordSelectionController.isInWordSelectionMode()) {
                        Log.d(TAG, "触发自动选词 - 字幕结束时");
                        mWordSelectionActive = true; // 标记选词过程开始，防止重复触发
                        mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
                    } else if (mWordSelectionController == null) {
                        Log.e(TAG, "无法执行自动选词：字幕选词控制器未初始化");
                    } else if (mWordSelectionController.isInWordSelectionMode()) {
                        Log.d(TAG, "已经在选词模式中，无需再次触发自动选词");
                    }
                } else {
                    Log.d(TAG, "不满足自动选词条件，取消操作");
                }
            }
        };
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
        Log.d(TAG, "重置选词状态，为下一个字幕准备");
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }
    
    /**
     * 生成字幕的唯一ID
     */
    private int generateSubtitleId(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) {
            return 0;
        }
        
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
        
        // 处理字幕视图更新
        if (mSubtitleView != null) {
            List<Cue> alignedCues = forceCenterAlignment(cues);
            mSubtitleView.setCues(alignedCues);
            
            // 确保选词控制器使用最新的字幕内容
            if (mWordSelectionController != null) {
                mWordSelectionController.setCurrentSubtitleText(alignedCues != null ? alignedCues : cues);
            }
            
            // 生成当前字幕的ID
            int newSubtitleId = 0;
            CharSequence newSubtitleText = null;
            if (cues != null && !cues.isEmpty() && cues.get(0).text != null) {
                newSubtitleId = generateSubtitleId(cues);
                newSubtitleText = cues.get(0).text;
            }
            
            // 检查当前是否在选词模式，如果是且字幕变化，则退出选词模式
            if (mWordSelectionController != null && 
                (mWordSelectionController.isInWordSelectionMode() || mWordSelectionActive)) {
                Log.d(TAG, "检测到字幕变化时已在选词模式或选词过程中，退出当前选词模式");
                exitWordSelectionModeAndReset();
                
                // 取消所有可能的后续选词操作
                mHandler.removeCallbacks(mAutoSelectWordRunnable);
            }
            
            // 字幕变化检测和自动选词功能 - 独立于选词模式
            if (mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) {
                if (cues != null && !cues.isEmpty() && !mHasActiveCues) {
                    // 新字幕出现 - 重置选词状态
                    mHasActiveCues = true;
                    resetWordSelectionState();
                    
                    // 更新字幕ID和文本
                    mCurrentSubtitleId = newSubtitleId;
                    mCurrentSubtitleText = newSubtitleText;
                    mCurrentSubtitleType = null;
                    
                    // 获取字幕的结束时间
                    mCurrentSubEndTimeUs = -1;
                    if (cues != null && !cues.isEmpty()) {
                        for (Cue cue : cues) {
                            if (cue != null) {
                                long endTime = getSubtitleEndTime(cue);
                                if (endTime > 0) {
                                    mCurrentSubEndTimeUs = endTime;
                                    Log.d(TAG, "获取到字幕结束时间: " + mCurrentSubEndTimeUs + "微秒");
                                    break;
                                }
                            }
                        }
                    }
                    
                    // 取消所有现有定时器
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    
                    // 使用定时器触发自动选词
                    if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                        try {
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
                        } catch (Exception e) {
                            Log.e(TAG, "计算剩余时间时发生错误", e);
                            // 使用固定延迟作为备用
                            mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                        }
                    } else {
                        // 无法获取结束时间，使用固定延迟
                        Log.d(TAG, "无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒");
                        mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                    }
                    
                } else if (cues != null && !cues.isEmpty() && mHasActiveCues && newSubtitleId != mCurrentSubtitleId && newSubtitleId != 0) {
                    // 字幕内容变化但未消失（新的字幕替换旧的）
                    Log.d(TAG, "字幕内容变化: 旧ID=" + mCurrentSubtitleId + ", 新ID=" + newSubtitleId);
                    
                    // 取消所有现有的定时器
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
                    
                    // 退出旧字幕的选词模式
                    exitWordSelectionModeAndReset();
                    
                    // 更新当前字幕ID和文本
                    mCurrentSubtitleId = newSubtitleId;
                    mCurrentSubtitleText = newSubtitleText;
                    mCurrentSubtitleType = null;
                    
                    // 获取新字幕的结束时间
                    mCurrentSubEndTimeUs = -1;
                    if (cues != null && !cues.isEmpty()) {
                        for (Cue cue : cues) {
                            if (cue != null) {
                                long endTime = getSubtitleEndTime(cue);
                                if (endTime > 0) {
                                    mCurrentSubEndTimeUs = endTime;
                                    Log.d(TAG, "字幕变化：获取到新字幕结束时间: " + mCurrentSubEndTimeUs + "微秒");
                                    break;
                                }
                            }
                        }
                    }
                    
                    // 设置定时器
                    if (mCurrentSubEndTimeUs > 0 && mPlayer != null) {
                        try {
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
                        } catch (Exception e) {
                            Log.e(TAG, "计算剩余时间时发生错误", e);
                            // 使用固定延迟作为备用
                            mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                        }
                    } else {
                        Log.d(TAG, "字幕变化：无法获取字幕结束时间，使用固定延迟: " + AUTO_SELECT_DELAY_MS + "毫秒");
                        mHandler.postDelayed(mAutoSelectWordRunnable, AUTO_SELECT_DELAY_MS);
                    }
                    
                } else if ((cues == null || cues.isEmpty()) && mHasActiveCues) {
                    // 字幕消失 - 重置状态，为下一个字幕做准备
                    Log.d(TAG, "字幕已消失，ID: " + mCurrentSubtitleId);
                    mHasActiveCues = false;
                    mCurrentSubEndTimeUs = -1;
                    mCurrentSubtitleId = 0;
                    mCurrentSubtitleText = null;
                    mCurrentSubtitleType = null;
                    
                    resetWordSelectionState();
                    
                    // 取消所有后续操作
                    mHandler.removeCallbacks(mAutoSelectWordRunnable);
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

        // 常规字幕处理
        for (Cue cue : cues) {
            // 修复可能的重复行
            if (cue.text != null) {
                // 检查字幕是否以换行或空格结尾，这是重复显示的原因
                String text = cue.text.toString();
                if (text.endsWith("\n") || text.endsWith(" ")) {
                    text = text.trim();
                    if (subsBuffer != null && text.endsWith(subsBuffer.toString())) {
                        continue;
                    }
                    subsBuffer = text;
                } else {
                    subsBuffer = null;
                }
            }
            
            // 强制居中字幕
            if (cue.position != Cue.DIMEN_UNSET) { // 有位置信息的字幕
                // 使用合法的构造函数，提供所有必需的参数
                result.add(new Cue(
                    cue.text,                   // text
                    cue.textAlignment,          // textAlignment
                    0.5f,                       // line - 垂直位置居中
                    Cue.LINE_TYPE_FRACTION,     // lineType - 使用相对位置
                    Cue.ANCHOR_TYPE_MIDDLE,     // lineAnchor - 垂直锚点居中
                    0.5f,                       // position - 水平位置居中
                    Cue.ANCHOR_TYPE_MIDDLE,     // positionAnchor - 水平锚点居中
                    Cue.DIMEN_UNSET             // size - 使用默认大小
                ));
            } else { // 没有位置信息的字幕
                result.add(cue);
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

    /**
     * 停止自动模式
     */
    private void stopAutoMode() {
        Log.d(TAG, "停止自动模式");
        mHandler.removeCallbacks(mAutoSelectWordRunnable);
    }

    /**
     * 长按操作处理
     */
    private void handleLongPress() {
        Log.d(TAG, "检测到长按操作");
        
        // 停止自动模式
        stopAutoMode();
    }

    /**
     * 处理自动选词
     */
    private void handleAutoWordSelection() {
        Log.d(TAG, "处理字幕结束自动选词");
        
        // 只有在启用了字幕结束自动选词功能时才执行
        if (mWordSelectionController != null && mPlayerData != null && mPlayerData.isAutoSelectLastWordEnabled()) {
            mWordSelectionController.enterWordSelectionMode(false); // 从最后一个单词开始
        }
    }
}
