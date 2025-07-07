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
    
    private final Context mContext;
    private final SubtitleView mSubtitleView;
    private final FrameLayout mRootView;
    private final PlaybackPresenter mPlaybackPresenter;
    
    // 各个功能模块
    private final VocabularyDatabase mVocabularyDatabase;
    private final TTSService mTTSService;
    private final UIOverlayManager mUIManager;
    
    // 选词模式状态
    private boolean mIsWordSelectionMode = false;
    private String mCurrentSubtitleText = "";
    private String[] mWords = new String[0];
    private int mCurrentWordIndex = 0;
    private int[] mWordPositions = new int[0];
    private boolean mIsShowingDefinition = false;
    
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
    
    public SubtitleWordSelectionController(Context context, SubtitleView subtitleView, FrameLayout rootView) {
        mContext = context;
        mSubtitleView = subtitleView;
        mRootView = rootView;
        mPlaybackPresenter = PlaybackPresenter.instance(context);
        
        // 初始化各个模块
        mVocabularyDatabase = VocabularyDatabase.getInstance(context);
        mTTSService = new TTSService(context);
        mUIManager = new UIOverlayManager(context, rootView);
        
        // 延迟初始化
        mHandler.postDelayed(this::initializeController, 1000);
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
        Log.d(TAG, "enterWordSelectionMode被调用，fromStart=" + fromStart);
        
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
        
        highlightCurrentWord();
        
        // 更新翻译状态
        captureTranslationState();
        
        Log.d(TAG, "已进入选词模式，单词数量: " + mWords.length + 
              ", 当前索引: " + mCurrentWordIndex + 
              ", 翻译状态: " + mCurrentTranslationState);
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
     * 处理中心按键（OK键）
     */
    private boolean handleCenterKey() {
        long clickTime = System.currentTimeMillis();
        long timeDiff = clickTime - mLastClickTime;
        
        Log.d(TAG, "OK按键详情 - 时间差: " + timeDiff + "ms, 阈值: " + DOUBLE_CLICK_TIME_DELTA + "ms");
        Log.d(TAG, "OK按键详情 - 当前状态: mIsWordSelectionMode=" + mIsWordSelectionMode + 
              ", mIsShowingDefinition=" + mIsShowingDefinition + 
              ", mCurrentWordIndex=" + mCurrentWordIndex + 
              ", mWords.length=" + mWords.length + 
              ", mCurrentSubtitleText为空=" + mCurrentSubtitleText.isEmpty() + 
              ", mLastSubtitleText为空=" + mLastSubtitleText.isEmpty() + 
              ", mCurrentTranslationState=" + (mCurrentTranslationState != null ? mCurrentTranslationState : "null"));
        
        mLastClickTime = clickTime;
        
        if (timeDiff < DOUBLE_CLICK_TIME_DELTA) {
            // 双击操作
            Log.d(TAG, "检测到双击OK按键");
            
            if (mIsShowingDefinition) {
                hideDefinitionOverlay();
            }
            
            showDoubleClickToast();
            seekToCurrentSubtitleStartTime();
            exitWordSelectionMode();
            
            mLastClickTime = 0;
            return true;
        }
        
        // 延迟处理单击事件
        mHandler.postDelayed(() -> {
            if (System.currentTimeMillis() - mLastClickTime >= DOUBLE_CLICK_TIME_DELTA) {
                // 单击逻辑
                if (!mIsShowingDefinition) {
                    Log.d(TAG, "处理单击OK按键 - 当前未显示定义窗口");
                    
                    // 在翻译之前捕获当前状态
                    captureTranslationState();
                    
                    // 开始翻译
                    translateCurrentWord();
                } else {
                    // 切换单词学习状态
                    toggleWordLearningStatus();
                }
            }
        }, DOUBLE_CLICK_TIME_DELTA);
        
        return true;
    }
    
    /**
     * 捕获翻译状态 - 收集当前需要翻译的单词和上下文
     */
    private void captureTranslationState() {
        String wordToTranslate = "";
        String contextToUse = "";
        boolean isAutoSelected = false;
        
        // 检查是否是自动选词模式下的最后一个单词
        boolean isAutoSelectedLastWord = mCurrentWordIndex == mWords.length - 1 && 
                !mLastSubtitleText.isEmpty() &&
                (mCurrentSubtitleText.isEmpty() || 
                 System.currentTimeMillis() - mLastSubtitleChangeTime < 1000);
                 
        Log.d(TAG, "捕获翻译状态 - 是否自动选词: " + isAutoSelectedLastWord + 
              ", 当前索引: " + mCurrentWordIndex + 
              ", 单词数量: " + mWords.length);
                 
        // 获取当前要翻译的单词和上下文
        if (mWords != null && mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            wordToTranslate = mWords[mCurrentWordIndex];
            
            // 判断使用哪个字幕作为上下文
            if (isAutoSelectedLastWord && !mLastSubtitleText.isEmpty()) {
                // 自动选词模式下使用上一个字幕作为上下文
                contextToUse = mLastSubtitleText;
                isAutoSelected = true;
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
        
        // 直接从字幕视图中获取当前高亮的单词 - 这是最可靠的方法
        String highlightedWord = getActualHighlightedWordFromSubtitleView();
        String subtitleContext = getActualSubtitleContextForHighlightedWord();
        
        Log.d(TAG, "从字幕视图获取的高亮单词: " + highlightedWord + 
              ", 上下文长度: " + (subtitleContext != null ? subtitleContext.length() : 0));
        
        if (highlightedWord == null || highlightedWord.trim().isEmpty()) {
            Log.e(TAG, "无法翻译：无法从字幕视图获取高亮单词");
            showDefinitionOverlay("无法获取单词内容，请重试", false);
            return;
        }
        
        // 显示加载提示
        showDefinitionOverlay("正在查询中...\n请稍候", false);
        
        // 保存要翻译的单词和上下文，避免在线程中引用可能变化的变量
        final String wordToTranslate = highlightedWord;
        final String contextToUse = subtitleContext;
        
        // 在后台线程中执行翻译
        new Thread(() -> {
            Log.d(TAG, "翻译线程开始 - 单词: " + wordToTranslate + 
                  ", 上下文长度: " + (contextToUse != null ? contextToUse.length() : 0));
                  
            String definition = TranslationService.fetchDefinition(wordToTranslate, contextToUse, 0);
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    if (definition != null) {
                        Log.d(TAG, "显示翻译结果 - 单词: " + wordToTranslate);
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
        // 检查是否是自动选词模式下的最后一个单词
        boolean isAutoSelectedLastWord = mCurrentWordIndex == mWords.length - 1 && 
                !mLastSubtitleText.isEmpty() &&
                (mCurrentSubtitleText.isEmpty() || 
                 System.currentTimeMillis() - mLastSubtitleChangeTime < 1000);
        
        // 优先使用合适的上下文
        if (isAutoSelectedLastWord && !mLastSubtitleText.isEmpty()) {
            // 如果是自动选词模式下的最后一个单词，使用上一个字幕作为上下文
            Log.d(TAG, "使用上一个字幕作为上下文 (自动选词模式)");
            return mLastSubtitleText;
        } 
        
        // 尝试获取字幕视图中的完整文本
        String subtitleText = getTextFromSubtitleView();
        if (subtitleText != null && !subtitleText.isEmpty()) {
            Log.d(TAG, "使用从字幕视图获取的文本作为上下文");
            return subtitleText;
        }
        
        // 回退到当前字幕文本
        if (!mCurrentSubtitleText.isEmpty()) {
            Log.d(TAG, "使用当前字幕文本作为上下文");
            return mCurrentSubtitleText;
        }
        
        // 最后回退到上一个字幕文本
        if (!mLastSubtitleText.isEmpty()) {
            Log.d(TAG, "使用上一个字幕文本作为上下文 (回退)");
            return mLastSubtitleText;
        }
        
        Log.e(TAG, "无法获取字幕上下文");
        return "";
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
                    // 获取文本
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
                    return subtitleText.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "通过反射获取字幕文本失败: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * 切换单词学习状态
     */
    private void toggleWordLearningStatus() {
        if (mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String selectedWord = mWords[mCurrentWordIndex];
            boolean isLearningWord = mVocabularyDatabase.isWordInLearningList(selectedWord);
            boolean success;
            
            if (isLearningWord) {
                success = mVocabularyDatabase.removeWord(selectedWord);
                if (success) {
                    Log.d(TAG, "单词已从学习列表中删除: " + selectedWord);
                }
            } else {
                success = mVocabularyDatabase.addWord(selectedWord);
                if (success) {
                    Log.d(TAG, "单词已添加到学习列表: " + selectedWord);
                }
            }
            
            refreshSubtitleView();
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
            mCurrentSubtitleText = "";
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
                    enterWordSelectionMode(false); // false表示从末尾开始选择
                    
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
     * 释放资源
     */
    public void release() {
        exitWordSelectionMode();
        mTTSService.release();
    }
}