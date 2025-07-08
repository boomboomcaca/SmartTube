package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.StandaloneSmbPlayerView;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 控制器类，用于管理字幕选词功能
 * 重构版本：将功能拆分到独立的模块中
 */
public class SubtitleWordSelectionController {
    private static final String TAG = SubtitleWordSelectionController.class.getSimpleName();
    
    // 单例实例
    private static SubtitleWordSelectionController sInstance;
    
    // 添加锁对象，用于线程安全地创建单例
    private static final Object INSTANCE_LOCK = new Object();
    
    // 内部类用于保存翻译状态，确保不会丢失信息
    private static class SubtitleTranslationState {
        public final String word;           // 要翻译的单词
        public final String context;        // 单词所在的上下文(字幕)
        public final int wordIndex;         // 单词在数组中的索引
        public final boolean isAutoSelected; // 是否是自动选择的单词
        public final long timestamp;        // 创建时间戳
        
        public SubtitleTranslationState(String word, String context, int index, boolean isAuto) {
            this.word = word;
            this.context = context;
            this.wordIndex = index;
            this.isAutoSelected = isAuto;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return "TranslationState{word='" + word + "', contextLength=" + 
                   (context != null ? context.length() : 0) + 
                   ", index=" + wordIndex + 
                   ", auto=" + isAutoSelected + 
                   ", age=" + (System.currentTimeMillis() - timestamp) + "ms}";
        }
    }
    
    private Context mContext;
    private SubtitleView mSubtitleView;
    private FrameLayout mRootView;
    private PlaybackPresenter mPlaybackPresenter;
    
    // 各个功能模块
    private VocabularyDatabase mVocabularyDatabase;
    private TTSService mTTSService;
    private UIOverlayManager mUIManager;
    
    // 选词模式状态
    private boolean mIsWordSelectionMode = false;
    private String mCurrentSubtitleText = "";
    private String[] mWords = new String[0];
    private int mCurrentWordIndex = 0;
    private int[] mWordPositions = new int[0];
    private boolean mIsShowingDefinition = false;
    private boolean isExitTriggeredByBackKey = false; // 标记是否是由返回键触发的退出
    
    // 翻译状态管理 - 关键改进
    private SubtitleTranslationState mCurrentTranslationState = null;
    private SubtitleTranslationState mLastTranslationState = null;
    
    private final Handler mHandler = new Handler();
    private boolean mIsInitialized = false;
    private boolean mPendingActivation = false;
    private boolean mPendingFromStart = true;
    
    // 双击检测相关变量
    private long mLastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 300;
    
    // 字幕时间记录
    private String mLastSubtitleText = "";
    private long mLastSubtitleChangeTime = 0;
    
    // 跳转保护
    private boolean mIsSeekProtectionActive = false;
    private long mSeekProtectionStartTime = 0;
    private static final long SEEK_PROTECTION_DURATION = 5000;
    
    // AI命令和重试
    private String mCurrentAIPrompt = "";
    private int mRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 10;
    
    // 构造函数改为私有，但不标记为final
    private SubtitleWordSelectionController(Context context, SubtitleView subtitleView, FrameLayout rootView) {
        this.mContext = context;
        this.mSubtitleView = subtitleView;
        this.mRootView = rootView;
        this.mPlaybackPresenter = PlaybackPresenter.instance(context);
        
        // 初始化各个模块
        this.mVocabularyDatabase = VocabularyDatabase.getInstance(context);
        this.mTTSService = new TTSService(context);
        
        if (rootView != null) {
            this.mUIManager = new UIOverlayManager(context, rootView);
            Log.d(TAG, "UI管理器初始化成功");
        } else {
            Log.e(TAG, "无法初始化UI管理器：根视图为null");
        }
        
        // 延迟初始化
        mHandler.postDelayed(this::initializeController, 1000);
    }
    
    /**
     * 获取单例实例
     */
    public static SubtitleWordSelectionController getInstance(Context context, SubtitleView subtitleView, FrameLayout rootView) {
        if (sInstance == null) {
            synchronized (INSTANCE_LOCK) {
                if (sInstance == null) {
                    sInstance = new SubtitleWordSelectionController(context, subtitleView, rootView);
                } else {
                    // 如果实例已存在，更新视图引用
                    sInstance.updateViews(subtitleView, rootView);
                }
            }
        } else {
            // 如果实例已存在，更新视图引用
            sInstance.updateViews(subtitleView, rootView);
        }
        
        return sInstance;
    }
    
    /**
     * 更新视图引用
     */
    private void updateViews(SubtitleView subtitleView, FrameLayout rootView) {
        if (subtitleView != null && this.mSubtitleView != subtitleView) {
            this.mSubtitleView = subtitleView;
            Log.d(TAG, "字幕视图引用已更新");
        }
        
        if (rootView != null && this.mRootView != rootView) {
            this.mRootView = rootView;
            
            // 更新UI管理器的根视图引用
            if (mUIManager != null) {
                mUIManager.updateRootView(rootView);
                Log.d(TAG, "UI管理器根视图已更新");
            }
            
            Log.d(TAG, "根视图引用已更新");
        }
    }
    
    /**
     * 释放单例资源
     */
    public static void release() {
        if (sInstance != null) {
            if (sInstance.mTTSService != null) {
                sInstance.mTTSService.release();
            }
            
            sInstance = null;
            Log.d(TAG, "字幕选词控制器单例已释放");
        }
    }
    
    /**
     * 初始化控制器
     */
    private void initializeController() {
        if (mSubtitleView != null) {
            List<Cue> currentCues = mSubtitleView.getCues();
            if (currentCues != null && !currentCues.isEmpty()) {
                setCurrentSubtitleText(currentCues);
            }
        }
        
        mIsInitialized = true;
        Log.d(TAG, "字幕选词控制器初始化完成");
        
        if (mPendingActivation) {
            mHandler.post(() -> enterWordSelectionMode(mPendingFromStart));
            mPendingActivation = false;
        }
    }
    
    /**
     * 进入选词模式
     */
    public void enterWordSelectionMode() {
        enterWordSelectionMode(true);
    }
    
    /**
     * 进入选词模式
     */
    public void enterWordSelectionMode(boolean fromStart) {
        enterWordSelectionMode(fromStart, false);
    }
    
    /**
     * 进入单词选择模式
     * @param fromStart 是否从第一个单词开始选择
     * @param isAutoSelect 是否是自动选择模式
     */
    public void enterWordSelectionMode(boolean fromStart, boolean isAutoSelect) {
        Log.d(TAG, "enterWordSelectionMode被调用，fromStart=" + fromStart + ", isAutoSelect=" + isAutoSelect);
        
        if (!mIsInitialized) {
            Log.d(TAG, "控制器尚未初始化，延迟激活选词模式");
            mPendingActivation = true;
            mPendingFromStart = fromStart;
            return;
        }
        
        if (mSubtitleView == null) {
            Log.d(TAG, "无法进入选词模式：字幕视图为空");
            return;
        }
        
        refreshCurrentSubtitle();
        
        if (!hasSubtitleText()) {
            Log.d(TAG, "无法进入选词模式：没有字幕文本");
            return;
        }
        
        // 暂停视频 - 确保在任何情况下都暂停视频
        pauseVideo();
        
        mIsWordSelectionMode = true;
        mIsShowingDefinition = false;
        
        splitSubtitleIntoWords();
        
        if (mWords.length == 0) {
            Log.d(TAG, "没有找到可选择的单词，尝试再次分析字幕");
            refreshCurrentSubtitle();
            splitSubtitleIntoWords();
            
            if (mWords.length == 0) {
                Log.d(TAG, "仍然没有找到可选择的单词，退出选词模式");
                mIsWordSelectionMode = false;
                return;
            }
        }
        
        // 设置起始单词索引
        if (fromStart) {
            mCurrentWordIndex = 0;
        } else {
            mCurrentWordIndex = mWords.length - 1;
        }
        
        // 如果是自动选择模式，直接创建带有自动选词标记的翻译状态
        if (isAutoSelect && mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String wordToTranslate = mWords[mCurrentWordIndex];
            String contextToUse = mLastSubtitleText.isEmpty() ? mCurrentSubtitleText : mLastSubtitleText;
            
            mCurrentTranslationState = new SubtitleTranslationState(
                wordToTranslate, contextToUse, mCurrentWordIndex, true); // 设置isAutoSelected为true
                
            Log.d(TAG, "自动选词模式 - 创建翻译状态: " + mCurrentTranslationState);
        }
        
        highlightCurrentWord();
        
        // 只有在非自动选词模式下才调用captureTranslationState，避免覆盖自动选词标记
        if (!isAutoSelect) {
        captureTranslationState();
        }
        
        Log.d(TAG, "已进入选词模式，单词数量: " + mWords.length + 
              ", 当前索引: " + mCurrentWordIndex + 
              ", 翻译状态: " + mCurrentTranslationState +
              ", 是否自动选词: " + isAutoSelect);
    }
    
    /**
     * 确保视频暂停
     */
    private void pauseVideo() {
        // 首先尝试通过PlaybackPresenter暂停
        if (mPlaybackPresenter != null && mPlaybackPresenter.getView() != null) {
            Log.d(TAG, "通过PlaybackPresenter暂停视频");
            mPlaybackPresenter.getView().setPlayWhenReady(false);
            return;
        }
        
        // 如果是在SMB播放器中，尝试通过Context接口暂停
        if (mContext instanceof StandaloneSmbPlayerView) {
            Log.d(TAG, "通过StandaloneSmbPlayerView暂停视频");
            ((StandaloneSmbPlayerView) mContext).play(false);
        } else {
            Log.d(TAG, "无法识别的播放器类型，无法暂停视频");
        }
    }
    
    /**
     * 刷新当前字幕文本
     */
    private void refreshCurrentSubtitle() {
        if (mSubtitleView != null) {
            List<Cue> currentCues = mSubtitleView.getCues();
            if (currentCues != null && !currentCues.isEmpty()) {
                Log.d(TAG, "刷新当前字幕文本，Cue数量: " + currentCues.size());
                setCurrentSubtitleText(currentCues);
            } else {
                // 尝试通过反射获取字幕内容
                tryGetSubtitleByReflection();
            }
        }
    }
    
    /**
     * 尝试通过反射获取字幕内容
     */
    private void tryGetSubtitleByReflection() {
        try {
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                StringBuilder subtitleText = new StringBuilder();
                for (Object painter : painters) {
                    Field textField = painter.getClass().getDeclaredField("text");
                    textField.setAccessible(true);
                    CharSequence text = (CharSequence) textField.get(painter);
                    
                    if (text != null && text.length() > 0) {
                        if (subtitleText.length() > 0) {
                            subtitleText.append(" ");
                        }
                        subtitleText.append(text);
                    }
                }
                
                if (subtitleText.length() > 0) {
                    Log.d(TAG, "通过反射获取到字幕文本: " + subtitleText);
                    mCurrentSubtitleText = subtitleText.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "通过反射获取字幕文本失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理按键事件
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "收到按键: " + event.getKeyCode());
        }
        
        if (!mIsInitialized) {
            Log.d(TAG, "控制器尚未初始化，忽略按键事件");
            return false;
        }
        
        if (!mIsWordSelectionMode) {
            return false;
        }
        
        // 音量键不拦截，让系统正常处理
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || 
            event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE) {
            Log.d(TAG, "选词模式下不拦截音量键: " + event.getKeyCode());
            return false;
        }
        
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        
        // 确保单词列表有效
        if (mWords.length == 0 || mCurrentWordIndex >= mWords.length) {
            Log.d(TAG, "单词列表为空或索引无效，尝试刷新");
            refreshCurrentSubtitle();
            splitSubtitleIntoWords();
        }
        
        if (mWords.length == 0) {
            Log.w(TAG, "无法处理按键：单词列表为空");
            return false;
        }
        
        if (mCurrentWordIndex >= mWords.length) {
            Log.d(TAG, "重置单词索引到0");
            mCurrentWordIndex = 0;
        }
        
        boolean isControlsVisible = isControlsOverlayVisible();
        
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isControlsVisible) return false;
                if (mIsShowingDefinition) hideDefinitionOverlay();
                
                // 如果当前是第一个词，按左键时直接跳到最后一个词
                if (mCurrentWordIndex == 0 && mWords.length > 0) {
                    mCurrentWordIndex = mWords.length - 1;
                    highlightCurrentWord();
                } else {
                selectPreviousWord();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isControlsVisible) return false;
                if (mIsShowingDefinition) hideDefinitionOverlay();
                selectNextWord();
                return true;
                
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mIsShowingDefinition) {
                    // 上方向键不做特殊处理
                    return true;
                }
                return false;
                
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mIsShowingDefinition) {
                    // 下方向键不做特殊处理
                    return true;
                }
                return false;
                
            case KeyEvent.KEYCODE_MENU:
                String currentWord = getActualHighlightedWord();
                if (currentWord != null && !currentWord.isEmpty()) {
                    Log.d(TAG, "菜单键按下，朗读单词: " + currentWord);
                    mTTSService.speakWord(currentWord);
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleCenterKey();
                
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (mIsShowingDefinition) {
                    hideDefinitionOverlay();
                } else {
                    exitWordSelectionMode();
                }
                return true;
                
            default:
                return true;
        }
    }
    
    /**
     * 处理中心(确认)按键
     */
    private boolean handleCenterKey() {
        // 如果没有在选词模式且字幕存在，切换到选词模式
        if (!mIsWordSelectionMode && hasSubtitleText()) {
            // 保存当前可能的字幕上下文，避免在模式转换过程中丢失
            final String subtitleContext = getSubtitleTextWithHighlightedWord();
            if (subtitleContext != null) {
                Log.d(TAG, "保存当前字幕上下文以备后用: " + 
                      (subtitleContext.length() > 30 ? subtitleContext.substring(0, 30) + "..." : subtitleContext));
                // 临时替换为我们找到的正确字幕上下文
                String tempCurrentText = mCurrentSubtitleText;
                mCurrentSubtitleText = subtitleContext;
                
                // 进入选词模式
                enterWordSelectionMode();
                
                // 如果确实有更换，恢复当前字幕文本以避免干扰后续操作
                if (!subtitleContext.equals(tempCurrentText)) {
                    mLastSubtitleText = subtitleContext; // 保存为上一个字幕，以备自动选词使用
                    mCurrentSubtitleText = tempCurrentText;
                }
                
                return true;
            }
            
            // 否则正常进入选词模式
            enterWordSelectionMode();
            return true;
        }
        
        // 如果已经在选词模式，检测为双击
        if (mIsWordSelectionMode) {
            long now = System.currentTimeMillis();
            long timeSinceLastClick = now - mLastClickTime;
            mLastClickTime = now;
            
            Log.d(TAG, "OK按键详情 - 时间差: " + timeSinceLastClick + "ms, 阈值: " + DOUBLE_CLICK_TIME_DELTA + "ms");
            Log.d(TAG, "OK按键详情 - 当前状态: mIsWordSelectionMode=" + mIsWordSelectionMode + 
                      ", mIsShowingDefinition=" + mIsShowingDefinition + 
                      ", mCurrentWordIndex=" + mCurrentWordIndex + 
                      ", mWords.length=" + mWords.length + 
                      ", mCurrentSubtitleText为空=" + (mCurrentSubtitleText == null || mCurrentSubtitleText.isEmpty()) + 
                      ", mLastSubtitleText为空=" + (mLastSubtitleText == null || mLastSubtitleText.isEmpty()) + 
                      ", mCurrentTranslationState=" + mCurrentTranslationState);
            
            // 检测是否是双击
            if (timeSinceLastClick < DOUBLE_CLICK_TIME_DELTA) {
                // 双击逻辑：跳转到字幕开始时间
                Log.d(TAG, "检测到双击OK按键 - 时间差: " + timeSinceLastClick + "ms");
                
                // 跳转到当前字幕的开始时间
                if (!isSeekProtectionActive()) {
                    seekToCurrentSubtitleStartTime();
                    MessageHelpers.showMessage(mContext, "跳转到字幕开始位置");
                } else {
                    Log.d(TAG, "跳转保护已激活，忽略跳转请求");
                    MessageHelpers.showMessage(mContext, "跳转保护中，请稍后再试");
                }
                
                return true;
            }
            
            // 单击逻辑
            mHandler.postDelayed(() -> {
                Log.d(TAG, "处理单击OK按键 - 当前状态: mIsShowingDefinition=" + mIsShowingDefinition);
                
                if (mIsShowingDefinition) {
                    // 已显示定义时，单击切换单词学习状态
                    toggleWordLearningStatus();
                } else {
                    // 保存字幕上下文（如果可用）
                    String currentContext = getSubtitleTextWithHighlightedWord();
                    if (currentContext != null) {
                        Log.d(TAG, "在翻译前保存当前字幕上下文: " + 
                             (currentContext.length() > 30 ? currentContext.substring(0, 30) + "..." : currentContext));
                        // 如果可能，使用找到的字幕上下文
                        if (mCurrentTranslationState != null) {
                            // 保存原始状态
                            SubtitleTranslationState savedTranslationState = mCurrentTranslationState;
                            
                            // 创建新的状态，但使用找到的字幕上下文
                            mCurrentTranslationState = new SubtitleTranslationState(
                                savedTranslationState.word,
                                currentContext,
                                savedTranslationState.wordIndex,
                                savedTranslationState.isAutoSelected
                            );
                            
                            Log.d(TAG, "使用找到的字幕上下文更新翻译状态: " + mCurrentTranslationState);
                        }
                    }
                    
                    // 未显示定义，显示翻译结果
                    if (mLastClickTime - mLastSubtitleChangeTime < 300 && mLastTranslationState != null) {
                        // 如果刚刚切换过字幕，尝试使用上一次的翻译状态
                        Log.d(TAG, "字幕刚刚切换，尝试使用上一次的翻译状态");
                        mCurrentTranslationState = mLastTranslationState;
                    } else {
                        // 在非自动选词模式下，重新捕获翻译状态
                        captureTranslationState();
                    }
                    
                    // 执行翻译
                    translateCurrentWord();
                }
            }, DOUBLE_CLICK_TIME_DELTA);
            
            return true;
        }
        
        return false;
    }
    
    /**
     * 捕获翻译状态 - 收集当前需要翻译的单词和上下文
     */
    private void captureTranslationState() {
        String wordToTranslate = "";
        String contextToUse = "";
        boolean isAutoSelected = false;
        
        // 检查是否已经在翻译状态中标记为自动选择的单词
        if (mCurrentTranslationState != null && 
            mCurrentTranslationState.isAutoSelected && 
            mCurrentWordIndex == mCurrentTranslationState.wordIndex) {
            
            Log.d(TAG, "已从现有翻译状态中发现自动选择标记 - 保持此状态");
            isAutoSelected = true;
            
            // 如果已经是自动选择的单词，直接使用之前的翻译状态
            if (mWords != null && mWords.length > 0 && 
                mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
                
                // 直接获取单词
                wordToTranslate = mWords[mCurrentWordIndex];
                
                // 使用上一个字幕作为上下文
                contextToUse = mLastSubtitleText.isEmpty() ? 
                               mCurrentTranslationState.context : mLastSubtitleText;
                
                Log.d(TAG, "维持自动选择状态 - 单词: " + wordToTranslate + 
                      ", 上下文长度: " + (contextToUse.length() > 50 ? contextToUse.substring(0, 50) + "..." : contextToUse));
                
                // 更新翻译状态但保持isAutoSelected为true
                mLastTranslationState = mCurrentTranslationState;
                mCurrentTranslationState = new SubtitleTranslationState(
                    wordToTranslate, contextToUse, mCurrentWordIndex, true);
                    
                Log.d(TAG, "更新自动选择的翻译状态: " + mCurrentTranslationState);
                return;
            }
        }
        
        // 检查是否是自动选词模式下的最后一个单词
        boolean isAutoSelectedLastWord = mCurrentWordIndex == mWords.length - 1 && 
                !mLastSubtitleText.isEmpty() &&
                (mCurrentSubtitleText.isEmpty() || 
                 System.currentTimeMillis() - mLastSubtitleChangeTime < 1000);
                 
        // 根据条件判断是否是自动选择
        isAutoSelected = isAutoSelectedLastWord;
                 
        Log.d(TAG, "捕获翻译状态 - 是否自动选词: " + isAutoSelected + 
              ", 当前索引: " + mCurrentWordIndex + 
              ", 单词数量: " + mWords.length +
              ", mLastSubtitleText为空: " + mLastSubtitleText.isEmpty() +
              ", mCurrentSubtitleText为空: " + mCurrentSubtitleText.isEmpty());
                 
        // 获取当前要翻译的单词和上下文
        if (mWords != null && mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            wordToTranslate = mWords[mCurrentWordIndex];
            
            // 判断使用哪个字幕作为上下文
            if (isAutoSelected && !mLastSubtitleText.isEmpty()) {
                // 自动选词模式下使用上一个字幕作为上下文
                contextToUse = mLastSubtitleText;
                Log.d(TAG, "使用上一个字幕作为上下文: " + 
                      (contextToUse.length() > 50 ? contextToUse.substring(0, 50) + "..." : contextToUse));
            } else {
                // 正常模式使用当前字幕作为上下文
                contextToUse = mCurrentSubtitleText;
                Log.d(TAG, "使用当前字幕作为上下文: " + 
                      (contextToUse.length() > 50 ? contextToUse.substring(0, 50) + "..." : contextToUse));
            }
            
            // 保存翻译状态
            mLastTranslationState = mCurrentTranslationState;
            mCurrentTranslationState = new SubtitleTranslationState(
                wordToTranslate, contextToUse, mCurrentWordIndex, isAutoSelected);
                
            Log.d(TAG, "已捕获翻译状态: " + mCurrentTranslationState);
        } else {
            Log.e(TAG, "无法捕获翻译状态: 单词数组为空或索引无效");
        }
    }
    
    /**
     * 翻译当前单词
     */
    private void translateCurrentWord() {
        Log.d(TAG, "translateCurrentWord被调用 - 直接从字幕视图中获取高亮单词");
        
        // 记录当前翻译状态
        boolean isAutoSelected = mCurrentTranslationState != null ? mCurrentTranslationState.isAutoSelected : false;
        
        // 根据翻译状态获取单词和上下文
        String wordToTranslate = null;
        String subtitleContext = null;
        
        // 优先从当前字幕视图获取高亮单词
        String highlightedWordFromView = getActualHighlightedWordFromSubtitleView();
        if (highlightedWordFromView != null && !highlightedWordFromView.isEmpty()) {
            wordToTranslate = highlightedWordFromView;
            
            // 尝试获取包含高亮单词的字幕文本作为上下文
            subtitleContext = getSubtitleTextWithHighlightedWord();
            if (subtitleContext != null) {
                Log.d(TAG, "获取到包含高亮单词'" + wordToTranslate + "'的字幕文本作为上下文: " + 
                     (subtitleContext.length() > 30 ? subtitleContext.substring(0, 30) + "..." : subtitleContext));
            } else {
                // 如果无法获取包含高亮单词的字幕文本，使用常规方法获取上下文
                subtitleContext = getActualSubtitleContextForHighlightedWord();
                Log.d(TAG, "无法获取包含高亮单词的字幕文本，使用常规方法获取上下文，长度: " + 
                     (subtitleContext != null ? subtitleContext.length() : 0));
            }
        }
        
        // 如果无法从视图获取，回退到使用翻译状态或当前选中的单词
        if (wordToTranslate == null) {
            if (mCurrentTranslationState != null) {
                // 使用翻译状态中的信息
                wordToTranslate = mCurrentTranslationState.word;
                subtitleContext = mCurrentTranslationState.context;
                
                Log.d(TAG, "使用翻译状态获取 - 单词: " + wordToTranslate + 
                      ", 上下文长度: " + (subtitleContext != null ? subtitleContext.length() : 0) + 
                      ", isAutoSelected=" + isAutoSelected);
            } else if (mWords != null && mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
                // 使用当前选中的单词
                wordToTranslate = mWords[mCurrentWordIndex];
                subtitleContext = getActualSubtitleContextForHighlightedWord();
                
                Log.d(TAG, "使用当前选中单词: " + wordToTranslate + ", 上下文长度: " + 
                     (subtitleContext != null ? subtitleContext.length() : 0));
            }
        }
        
        if (wordToTranslate == null || wordToTranslate.trim().isEmpty()) {
            Log.e(TAG, "无法翻译：无法获取需要翻译的单词");
            showDefinitionOverlay("无法获取单词内容，请重试", false);
            return;
        }
        
        // 显示加载提示
        showDefinitionOverlay("正在查询中...\n请稍候", false);
        
        // 在后台线程中执行翻译
        final String finalWord = wordToTranslate;
        final String finalContext = subtitleContext;
        
        new Thread(() -> {
            Log.d(TAG, "翻译线程开始 - 单词: " + finalWord + 
                  ", 上下文长度: " + (finalContext != null ? finalContext.length() : 0) +
                  ", isAutoSelected=" + isAutoSelected);
                   
            String definition = TranslationService.fetchDefinition(finalWord, finalContext, 0);
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    if (definition != null) {
                        Log.d(TAG, "显示翻译结果 - 单词: " + finalWord);
                        showDefinitionOverlay(definition, false);
                    } else {
                        showDefinitionOverlay("无法获取单词解释，请重试", false);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 直接从字幕视图中获取当前高亮的单词
     * 这是最可靠的方法，因为它直接读取UI中实际显示的内容
     */
    private String getActualHighlightedWordFromSubtitleView() {
        if (mSubtitleView == null) {
            Log.e(TAG, "字幕视图为空，无法获取高亮单词");
            return null;
        }
        
        try {
            // 通过反射获取SubtitlePainter对象
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    // 获取高亮单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    String highlightWord = (String) highlightWordField.get(painter);
                    
                    if (highlightWord != null && !highlightWord.isEmpty()) {
                        Log.d(TAG, "从SubtitleView直接获取到高亮单词: " + highlightWord);
                        return highlightWord;
                    }
                }
            }
            
            Log.d(TAG, "SubtitleView中没有找到高亮单词");
        } catch (Exception e) {
            Log.e(TAG, "通过反射获取高亮单词失败: " + e.getMessage(), e);
        }
        
        // 回退方法：如果无法从视图获取，则尝试使用当前选中的单词
        if (mWords != null && mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String word = mWords[mCurrentWordIndex];
            Log.d(TAG, "使用回退方法获取单词: " + word);
            return word;
        }
        
        return null;
    }
    
    /**
     * 获取当前高亮单词的上下文（字幕文本）
     */
    private String getActualSubtitleContextForHighlightedWord() {
        // 优先使用当前翻译状态中的上下文 - 这是最准确的
        if (mCurrentTranslationState != null) {
            Log.d(TAG, "使用翻译状态中的上下文 (isAutoSelected=" + 
                  mCurrentTranslationState.isAutoSelected + ")");
            return mCurrentTranslationState.context;
        }
        
        // 尝试获取包含高亮单词的实际字幕文本
        String subtitleWithHighlightedWord = getSubtitleTextWithHighlightedWord();
        if (subtitleWithHighlightedWord != null) {
            Log.d(TAG, "找到包含高亮单词的字幕文本: " + 
                  (subtitleWithHighlightedWord.length() > 30 ? subtitleWithHighlightedWord.substring(0, 30) + "..." : subtitleWithHighlightedWord));
            return subtitleWithHighlightedWord;
        }
        
        // 检查是否是自动选词模式下的最后一个单词
        boolean isAutoSelectedLastWord = mCurrentWordIndex == mWords.length - 1 && 
                !mLastSubtitleText.isEmpty() &&
                (mCurrentSubtitleText.isEmpty() || 
                 System.currentTimeMillis() - mLastSubtitleChangeTime < 1000);
        
        // 根据条件选择上下文
        if (isAutoSelectedLastWord && !mLastSubtitleText.isEmpty()) {
            // 自动选词模式 - 使用上一个字幕作为上下文
            Log.d(TAG, "根据条件判断为自动选词 - 使用上一个字幕作为上下文");
            return mLastSubtitleText;
        } else {
            // 获取实际字幕对象上下文失败，回退到使用当前保存的字幕内容
            Log.d(TAG, "使用当前保存的字幕作为上下文");
            return mCurrentSubtitleText;
        }
    }
    
    /**
     * 从字幕视图中获取当前显示的文本
     */
    private String getTextFromSubtitleView() {
        if (mSubtitleView == null) {
            return null;
        }
        
        try {
            // 通过反射获取SubtitlePainter对象
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                StringBuilder subtitleText = new StringBuilder();
                
                for (Object painter : painters) {
                    // 尝试多个可能的字段名称（不同版本的ExoPlayer可能使用不同的字段）
                    String[] possibleTextFields = {"text", "cueText", "cue", "textContent"};
                    CharSequence text = null;
                    
                    for (String fieldName : possibleTextFields) {
                        try {
                            Field textField = painter.getClass().getDeclaredField(fieldName);
                            textField.setAccessible(true);
                            Object value = textField.get(painter);
                            if (value instanceof CharSequence) {
                                text = (CharSequence) value;
                                break;
                            }
                        } catch (NoSuchFieldException e) {
                            // 继续尝试下一个字段名
                            continue;
                        }
                    }
                    
                    // 如果还没找到文本，尝试在cue对象中寻找text字段
                    if (text == null) {
                        try {
                            Field cueField = painter.getClass().getDeclaredField("cue");
                            cueField.setAccessible(true);
                            Object cue = cueField.get(painter);
                            
                            if (cue != null) {
                                Field textField = cue.getClass().getDeclaredField("text");
                                textField.setAccessible(true);
                                Object value = textField.get(cue);
                                if (value instanceof CharSequence) {
                                    text = (CharSequence) value;
                                }
                            }
                        } catch (Exception e) {
                            // 忽略此异常，继续处理
                        }
                    }
                    
                    if (text != null && text.length() > 0) {
                        if (subtitleText.length() > 0) {
                            subtitleText.append(" ");
                        }
                        subtitleText.append(text);
                    }
                }
                
                if (subtitleText.length() > 0) {
                    return subtitleText.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "通过反射获取字幕文本失败: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * 获取包含高亮单词的实际字幕文本
     * 这个方法尝试使用多种方法来获取包含高亮单词的字幕文本
     */
    private String getSubtitleTextWithHighlightedWord() {
        if (mSubtitleView == null) {
            return null;
        }
        
        String highlightedWord = getActualHighlightedWordFromSubtitleView();
        if (highlightedWord == null || highlightedWord.isEmpty()) {
            return null;
        }
        
        // 首先尝试通过反射获取字幕文本
        String subtitleText = getTextFromSubtitleView();
        if (subtitleText != null && subtitleText.contains(highlightedWord)) {
            Log.d(TAG, "通过反射获取到包含高亮单词的字幕文本");
            return subtitleText;
        }
        
        // 如果反射失败或文本不包含高亮单词，尝试使用当前字幕文本
        if (mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty() && 
            mCurrentSubtitleText.contains(highlightedWord)) {
            Log.d(TAG, "当前字幕文本包含高亮单词");
            return mCurrentSubtitleText;
        }
        
        // 如果当前字幕文本不包含高亮单词，检查上一个字幕文本
        if (mLastSubtitleText != null && !mLastSubtitleText.isEmpty() && 
            mLastSubtitleText.contains(highlightedWord)) {
            Log.d(TAG, "上一个字幕文本包含高亮单词");
            return mLastSubtitleText;
        }
        
        // 无法确定哪个字幕文本包含高亮单词
        Log.w(TAG, "无法找到包含高亮单词'" + highlightedWord + "'的字幕文本");
        return null;
    }
    
    /**
     * 切换单词学习状态
     */
    private void toggleWordLearningStatus() {
        if (mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String selectedWord = mWords[mCurrentWordIndex];
            Log.d(TAG, "切换单词学习状态: " + selectedWord);
            
            boolean isLearningWord = false;
            try {
                isLearningWord = mVocabularyDatabase.isWordInLearningList(selectedWord);
                Log.d(TAG, selectedWord + " 是否已在学习列表中: " + isLearningWord);
            } catch (Exception e) {
                Log.e(TAG, "检查单词是否在学习列表中时出错: " + e.getMessage(), e);
            }
            
            boolean success = false;
            
            if (isLearningWord) {
                try {
                    success = mVocabularyDatabase.removeWord(selectedWord);
                    if (success) {
                        Log.d(TAG, "单词已从学习列表中删除: " + selectedWord);
                        MessageHelpers.showMessage(mContext, "单词已删除: " + selectedWord);
                    } else {
                        Log.e(TAG, "删除单词失败: " + selectedWord);
                        MessageHelpers.showMessage(mContext, "删除单词失败: " + selectedWord);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "删除单词时出错: " + e.getMessage(), e);
                    MessageHelpers.showMessage(mContext, "删除单词时出错");
                }
            } else {
                try {
                    success = mVocabularyDatabase.addWord(selectedWord);
                    if (success) {
                        Log.d(TAG, "单词已添加到学习列表: " + selectedWord);
                        MessageHelpers.showMessage(mContext, "单词已添加: " + selectedWord);
                    } else {
                        Log.e(TAG, "添加单词失败: " + selectedWord);
                        MessageHelpers.showMessage(mContext, "添加单词失败: " + selectedWord);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "添加单词时出错: " + e.getMessage(), e);
                    MessageHelpers.showMessage(mContext, "添加单词时出错");
                }
            }
            
            // 刷新字幕视图以更新高亮状态
            refreshSubtitleView();
        } else {
            Log.e(TAG, "无法切换单词状态: 无效的单词索引 " + mCurrentWordIndex);
            MessageHelpers.showMessage(mContext, "无法切换单词状态: 无效的单词索引");
        }
    }
    
    /**
     * 检查工具栏是否可见
     */
    private boolean isControlsOverlayVisible() {
        if (mPlaybackPresenter != null && mPlaybackPresenter.getView() != null) {
            return mPlaybackPresenter.getView().isControlsVisible();
        }
        return false;
    }
    
    /**
     * 设置当前字幕文本
     */
    public void setCurrentSubtitleText(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) {
            // 保存之前的字幕，但记录当前字幕为空
            if (!mCurrentSubtitleText.isEmpty()) {
                mLastSubtitleText = mCurrentSubtitleText;
                mLastSubtitleChangeTime = System.currentTimeMillis();
                Log.d(TAG, "字幕消失，保存上一字幕: " + 
                      (mLastSubtitleText.length() > 50 ? mLastSubtitleText.substring(0, 50) + "..." : mLastSubtitleText));
            }
            
            // 如果已经在选词模式且有自动选词标记，不清空当前字幕文本
            if (!mIsWordSelectionMode || (mCurrentTranslationState != null && !mCurrentTranslationState.isAutoSelected)) {
            mCurrentSubtitleText = "";
            } else {
                Log.d(TAG, "保持当前字幕文本不变 - 处于自动选词模式");
            }
            return;
        }
        
        // 在选词模式下，特别是自动选词模式下，不覆盖当前文本和分词
        if (mIsWordSelectionMode && mCurrentTranslationState != null && mCurrentTranslationState.isAutoSelected) {
            String newSubtitleText = SubtitleTextProcessor.extractTextFromCues(cues);
            Log.d(TAG, "在自动选词模式下收到新字幕，但保持原状态 - 原字幕: " + 
                  (mCurrentSubtitleText.length() > 30 ? mCurrentSubtitleText.substring(0, 30) + "..." : mCurrentSubtitleText) + 
                  ", 新字幕: " + 
                  (newSubtitleText.length() > 30 ? newSubtitleText.substring(0, 30) + "..." : newSubtitleText));
            
            // 仅记录字幕变化时间，但不更新文本或分词
            if (!newSubtitleText.equals(mCurrentSubtitleText)) {
                mLastSubtitleText = mCurrentSubtitleText;
                mLastSubtitleChangeTime = System.currentTimeMillis();
            }
            return;
        }
        
        String oldSubtitleText = mCurrentSubtitleText;
        
        // 提取字幕文本
        mCurrentSubtitleText = SubtitleTextProcessor.extractTextFromCues(cues);
        
        // 检测字幕是否变化
        boolean isSubtitleChanged = !mCurrentSubtitleText.equals(oldSubtitleText);
        
        if (isSubtitleChanged) {
            // 记录字幕变化时间
            mLastSubtitleText = oldSubtitleText;
            mLastSubtitleChangeTime = System.currentTimeMillis();
            
            Log.d(TAG, "字幕变化 - 新: " + 
                  (mCurrentSubtitleText.length() > 50 ? mCurrentSubtitleText.substring(0, 50) + "..." : mCurrentSubtitleText) +
                  ", 旧: " + (mLastSubtitleText.length() > 50 ? mLastSubtitleText.substring(0, 50) + "..." : mLastSubtitleText));
            
            // 检查是否启用了"字幕结束时自动选择最后一个单词"功能
            if (!mIsWordSelectionMode && 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(mContext).isAutoSelectLastWordEnabled()) {
                
                Log.d(TAG, "检查是否需要自动选择最后一个单词");
                
                // 修改触发条件：字幕结束或者字幕变化且包含完整句子
                boolean isSubtitleEnding = !oldSubtitleText.isEmpty() && mCurrentSubtitleText.isEmpty();
                boolean isCompleteSubtitle = !oldSubtitleText.isEmpty() && 
                                           (oldSubtitleText.endsWith(".") || 
                                            oldSubtitleText.endsWith("?") || 
                                            oldSubtitleText.endsWith("!") ||
                                            oldSubtitleText.endsWith("。") ||
                                            oldSubtitleText.endsWith("？") ||
                                            oldSubtitleText.endsWith("！"));
                                            
                if (isSubtitleEnding || isCompleteSubtitle) {
                    // 这里使用上一个字幕作为上下文，并选择最后一个单词
                    String subtitleContext = oldSubtitleText;
                    
                    // 临时设置字幕上下文
                    String tempCurrentSubtitle = mCurrentSubtitleText;
                    mCurrentSubtitleText = subtitleContext;
                    
                    // 进入选词模式，选择最后一个单词
                    Log.d(TAG, "自动进入选词模式，选择最后一个单词");
                    enterWordSelectionMode(false, true); // false表示从末尾开始选择，true表示是自动选词模式
                    
                    // 确保记录正确的当前字幕内容
                    mLastSubtitleText = subtitleContext;
                    mCurrentSubtitleText = tempCurrentSubtitle;
                    
                    // 如果成功选择了单词，创建一个翻译状态
                    if (mWords.length > 0) {
                        int lastWordIndex = mWords.length - 1;
                        String lastWord = mWords[lastWordIndex];
                        
                        // 创建翻译状态
                        mCurrentTranslationState = new SubtitleTranslationState(
                            lastWord, subtitleContext, lastWordIndex, true);
                            
                        Log.d(TAG, "自动选择了字幕中的最后一个单词，创建翻译状态: " + mCurrentTranslationState);
                    }
                }
            }
        }
    }
    
    /**
     * 检查是否有字幕文本
     */
    public boolean hasSubtitleText() {
        boolean hasText = mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
        Log.d(TAG, "hasSubtitleText: " + hasText + ", mCurrentSubtitleText=" + 
              (mCurrentSubtitleText != null ? "\"" + mCurrentSubtitleText + "\"" : "null"));
        
        if (!hasText) {
            // 如果没有字幕文本，尝试刷新一次
            refreshCurrentSubtitle();
            hasText = mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
            Log.d(TAG, "刷新后 hasSubtitleText: " + hasText + ", mCurrentSubtitleText=" + 
                  (mCurrentSubtitleText != null ? "\"" + mCurrentSubtitleText + "\"" : "null"));
        }
        
        return hasText;
    }
    
    /**
     * 分割字幕文本为单词
     */
    private void splitSubtitleIntoWords() {
        if (mCurrentSubtitleText == null || mCurrentSubtitleText.isEmpty()) {
            mWords = new String[0];
            mWordPositions = new int[0];
            Log.d(TAG, "分词失败：字幕文本为空");
            return;
        }
        
        // 检测文本是否包含CJK字符
        boolean containsCJK = false;
        for (int i = 0; i < mCurrentSubtitleText.length(); i++) {
            char c = mCurrentSubtitleText.charAt(i);
            if (SubtitleTextProcessor.isCJKChar(c)) {
                containsCJK = true;
                break;
            }
        }
        
        // 检测是否是自动生成字幕
        boolean isAutoGenerated = SubtitleTextProcessor.isAutoGeneratedSubtitle(mCurrentSubtitleText);
        
        // 使用统一的分词方法
        List<String> wordList = SubtitleTextProcessor.tokenizeText(mCurrentSubtitleText, containsCJK, isAutoGenerated);
        
        // 计算单词位置
        mWordPositions = SubtitleTextProcessor.calculateWordPositions(mCurrentSubtitleText, wordList);
        
        mWords = wordList.toArray(new String[0]);
        
        // 记录分词结果
        StringBuilder logBuilder = new StringBuilder("分词结果: 总单词数=" + mWords.length + "\n");
        for (int i = 0; i < mWords.length && i < 20; i++) {
            logBuilder.append(i).append(": '").append(mWords[i]).append("' (位置=")
                    .append(i < mWordPositions.length ? mWordPositions[i] : "未知").append(")\n");
        }
        if (mWords.length > 20) {
            logBuilder.append("...还有").append(mWords.length - 20).append("个单词\n");
        }
        Log.d(TAG, logBuilder.toString());
        
        mCurrentWordIndex = 0;
    }
    
    /**
     * 选择下一个单词
     */
    private void selectNextWord() {
        mTTSService.deleteCurrentAudioFile();
        
        if (mWords.length > 0) {
            mCurrentWordIndex = (mCurrentWordIndex + 1) % mWords.length;
            Log.d(TAG, "选择下一个单词: " + mWords[mCurrentWordIndex] + " 索引: " + mCurrentWordIndex);
            highlightCurrentWord();
            
            // 更新翻译状态
            captureTranslationState();
        }
    }
    
    /**
     * 选择上一个单词
     */
    private void selectPreviousWord() {
        mTTSService.deleteCurrentAudioFile();
        
        if (mWords.length > 0) {
            mCurrentWordIndex = (mCurrentWordIndex - 1 + mWords.length) % mWords.length;
            Log.d(TAG, "选择上一个单词: " + mWords[mCurrentWordIndex] + " 索引: " + mCurrentWordIndex);
            highlightCurrentWord();
            
            // 更新翻译状态
            captureTranslationState();
        }
    }
    
    /**
     * 高亮显示当前单词
     */
    private void highlightCurrentWord() {
        if (mWords.length == 0 || mWordPositions.length != mWords.length) {
            Log.d(TAG, "无法高亮单词：单词列表为空或位置数组不匹配");
            return;
        }
        
        if (mCurrentWordIndex < 0) {
            mCurrentWordIndex = 0;
        } else if (mCurrentWordIndex >= mWords.length) {
            mCurrentWordIndex = mWords.length - 1;
        }
        
        String word = mWords[mCurrentWordIndex];
        int wordPosition = mWordPositions[mCurrentWordIndex];
        
        highlightWordInSubtitle(word, wordPosition);
        
        // 检查单词是否在学习列表中
        if (mVocabularyDatabase != null && mVocabularyDatabase.isWordInLearningList(word)) {
            if (!mIsShowingDefinition) {
                translateCurrentWord();
            }
        }
    }
    
    /**
     * 在字幕中高亮显示指定单词
     */
    private void highlightWordInSubtitle(String word, int wordPosition) {
        if (mSubtitleView == null || word == null || word.isEmpty()) {
            return;
        }
        
        try {
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                String cleanWord = word.replaceAll("[,.!?;:\"\\(\\)\\[\\]\\{\\}]", "").trim();
                
                Log.d(TAG, "设置高亮单词: " + cleanWord + ", 位置: " + wordPosition);
                
                for (Object painter : painters) {
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    highlightWordField.set(painter, cleanWord);
                    
                    try {
                        Field highlightWordPositionField = painter.getClass().getDeclaredField("highlightWordPosition");
                        highlightWordPositionField.setAccessible(true);
                        highlightWordPositionField.set(painter, wordPosition);
                    } catch (NoSuchFieldException e) {
                        Log.w(TAG, "SubtitlePainter缺少highlightWordPosition字段");
                    }
                    
                    Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                    enableWordHighlightField.setAccessible(true);
                    enableWordHighlightField.set(null, true);
                }
                
                mSubtitleView.invalidate();
            }
        } catch (Exception e) {
            Log.e(TAG, "设置高亮单词失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取字幕中实际高亮的单词
     */
    private String getActualHighlightedWord() {
        if (mIsWordSelectionMode && mWords != null && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String word = mWords[mCurrentWordIndex];
            String cleanWord = word.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").trim();
            Log.d(TAG, "获取高亮单词: 原始=" + word + ", 清理后=" + cleanWord + ", 索引=" + mCurrentWordIndex);
            return cleanWord;
        }
        Log.d(TAG, "无法获取高亮单词: 选词模式=" + mIsWordSelectionMode + 
              ", 单词数组=" + (mWords != null ? mWords.length : 0) + 
              ", 当前索引=" + mCurrentWordIndex);
        return "";
    }
    
    /**
     * 显示解释覆盖层
     */
    private void showDefinitionOverlay(String text, boolean shouldSpeak) {
        if (shouldSpeak) {
            String currentWord = getActualHighlightedWord();
            mTTSService.speakWord(currentWord);
        }
        
        mUIManager.showDefinitionOverlay(text);
        mIsShowingDefinition = true;
    }
    
    /**
     * 检查是否正在显示解释
     */
    public boolean isShowingDefinition() {
        return mIsShowingDefinition;
    }
    
    /**
     * 隐藏解释覆盖层
     */
    public void hideDefinitionOverlay() {
        mTTSService.stopPlaying();
        mUIManager.hideDefinitionOverlay();
        mIsShowingDefinition = false;
    }
    
    /**
     * 检查是否处于选词模式
     */
    public boolean isInWordSelectionMode() {
        return mIsWordSelectionMode;
    }
    
    /**
     * 刷新选词控制器状态 - 强制检查当前UI状态判断是否在选词模式
     */
    public void refreshStatus() {
        Log.d(TAG, "刷新选词控制器状态, 当前记录的状态: " + mIsWordSelectionMode);
        
        // 检查UI状态确认是否真的在选词模式
        boolean actualModeFromUI = false;
        
        try {
            // 如果字幕视图中有高亮单词，说明确实在选词模式
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    try {
                        Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                        highlightWordField.setAccessible(true);
                        Object highlightWord = highlightWordField.get(painter);
                        
                        if (highlightWord != null) {
                            actualModeFromUI = true;
                            break;
                        }
                    } catch (Exception e) {
                        // 忽略异常
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查UI状态失败: " + e.getMessage(), e);
        }
        
        // 如果UI状态与记录状态不一致，则更新记录状态
        if (actualModeFromUI != mIsWordSelectionMode) {
            Log.d(TAG, "状态不一致，更新记录状态: " + mIsWordSelectionMode + " -> " + actualModeFromUI);
            mIsWordSelectionMode = actualModeFromUI;
        }
    }
    
    /**
     * 清除字幕中的高亮显示
     */
    private void clearSubtitleHighlight() {
        if (mSubtitleView == null) {
            return;
        }
        
        try {
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    try {
                        Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                        highlightWordField.setAccessible(true);
                        highlightWordField.set(painter, null);
                        
                        Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                        enableWordHighlightField.setAccessible(true);
                        enableWordHighlightField.set(null, false);
                        
                        Field enableLearningWordHighlightField = painter.getClass().getDeclaredField("ENABLE_LEARNING_WORD_HIGHLIGHT");
                        enableLearningWordHighlightField.setAccessible(true);
                        enableLearningWordHighlightField.set(null, true);
                    } catch (Exception e) {
                        Log.e(TAG, "清除单个画笔高亮失败: " + e.getMessage());
                    }
                }
                
                mSubtitleView.invalidate();
                Log.d(TAG, "已清除选词高亮，保持学习中单词高亮");
            }
        } catch (Exception e) {
            Log.e(TAG, "清除字幕高亮失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 退出选词模式
     */
    public void exitWordSelectionMode() {
        // 先刷新状态，确保状态与UI一致
        refreshStatus();
        
        if (!mIsWordSelectionMode) {
            Log.d(TAG, "exitWordSelectionMode: 当前不在选词模式，无需退出");
            return;
        }
        
        Log.d(TAG, "exitWordSelectionMode: 开始退出选词模式...");
        
        try {
            clearSubtitleHighlight();
            Log.d(TAG, "exitWordSelectionMode: 已清除字幕高亮");
        } catch (Exception e) {
            Log.e(TAG, "exitWordSelectionMode: 清除字幕高亮失败", e);
        }
        
        try {
            hideDefinitionOverlay();
            Log.d(TAG, "exitWordSelectionMode: 已隐藏解释覆盖层");
        } catch (Exception e) {
            Log.e(TAG, "exitWordSelectionMode: 隐藏解释覆盖层失败", e);
        }
        
        try {
            mTTSService.deleteCurrentAudioFile();
            mTTSService.stopPlaying();
            Log.d(TAG, "exitWordSelectionMode: 已停止TTS播放");
        } catch (Exception e) {
            Log.e(TAG, "exitWordSelectionMode: 停止TTS播放失败", e);
        }
        
        mIsWordSelectionMode = false;
        mIsShowingDefinition = false;
        
        // 修改播放恢复逻辑：对所有类型都尝试恢复播放
        try {
            if (mPlaybackPresenter != null && mPlaybackPresenter.getView() != null) {
                // 通过PlaybackPresenter恢复播放
                Log.d(TAG, "exitWordSelectionMode: 通过PlaybackPresenter恢复播放");
                mPlaybackPresenter.getView().setPlayWhenReady(true);
            } 
            
            // 如果是在SMB播放器中，也直接尝试恢复播放
            if (mContext instanceof StandaloneSmbPlayerView) {
                Log.d(TAG, "exitWordSelectionMode: 在SMB播放器中主动恢复播放");
                ((StandaloneSmbPlayerView) mContext).play(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "exitWordSelectionMode: 恢复播放失败", e);
        }
        
        Log.d(TAG, "exitWordSelectionMode: 已完全退出选词模式");
    }
    
    /**
     * 强制刷新字幕视图
     */
    private void refreshSubtitleView() {
        if (mSubtitleView != null) {
            try {
                List<Cue> currentCues = mSubtitleView.getCues();
                if (currentCues != null && !currentCues.isEmpty()) {
                    mSubtitleView.setCues(currentCues);
                }
            } catch (Exception e) {
                Log.e(TAG, "刷新字幕视图失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 从当前字幕的开始时间重新播放视频
     */
    private void seekToCurrentSubtitleStartTime() {
        PlaybackView view = mPlaybackPresenter.getView();
        if (view == null) {
            Log.e(TAG, "无法获取播放视图，取消定位操作");
            return;
        }
        
        long currentPositionMs = view.getPositionMs();
        Log.d(TAG, "当前播放位置: " + currentPositionMs + "ms");
        
        if (mLastSubtitleChangeTime > 0) {
            Log.d(TAG, "使用记录的字幕变化时间: " + mLastSubtitleChangeTime + "ms");
            
            long timeDiff = Math.abs(currentPositionMs - mLastSubtitleChangeTime);
            if (timeDiff > 10000) {
                Log.w(TAG, "记录的字幕时间与当前时间相差太大: " + timeDiff + "ms");
                
                if (mLastSubtitleChangeTime > currentPositionMs + 5000 || 
                    mLastSubtitleChangeTime < currentPositionMs - 30000) {
                    Log.w(TAG, "使用当前播放位置代替不合理的字幕时间");
                    view.setPositionMs(currentPositionMs);
                    MessageHelpers.showMessage(mContext, "使用当前位置重新播放");
                    view.setPlayWhenReady(true);
                    activateSeekProtection();
                    return;
                }
            }
            
            view.setPositionMs(mLastSubtitleChangeTime);
        } else {
            Log.w(TAG, "没有记录的字幕变化时间，使用当前位置");
            view.setPositionMs(currentPositionMs);
            MessageHelpers.showMessage(mContext, "使用当前位置重新播放");
        }
        
        view.setPlayWhenReady(true);
        activateSeekProtection();
    }
    
    /**
     * 显示双击状态的提示信息
     */
    private void showDoubleClickToast() {
        try {
            Log.d(TAG, "显示双击提示信息");
        } catch (Exception e) {
            Log.e(TAG, "显示双击提示信息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否在跳转保护期内
     */
    private boolean isSeekProtectionActive() {
        if (mIsSeekProtectionActive) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - mSeekProtectionStartTime < SEEK_PROTECTION_DURATION) {
                return true;
            } else {
                mIsSeekProtectionActive = false;
                Log.d(TAG, "跳转保护已过期");
            }
        }
        return false;
    }
    
    /**
     * 激活跳转保护
     */
    private void activateSeekProtection() {
        mIsSeekProtectionActive = true;
        mSeekProtectionStartTime = System.currentTimeMillis();
        Log.d(TAG, "激活跳转保护，持续 " + (SEEK_PROTECTION_DURATION / 1000) + " 秒");
        
        mHandler.postDelayed(() -> {
            mIsSeekProtectionActive = false;
            Log.d(TAG, "跳转保护已自动清除");
        }, SEEK_PROTECTION_DURATION);
    }
    
    /**
     * 在活动销毁时调用，清理资源
     */
    public void onDestroy() {
        exitWordSelectionMode();
        if (mTTSService != null) {
            mTTSService.stopPlaying();
        }
    }
}