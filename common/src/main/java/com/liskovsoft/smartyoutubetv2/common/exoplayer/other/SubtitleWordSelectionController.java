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
        
        Log.d(TAG, "已进入选词模式，单词数量: " + mWords.length + ", 当前索引: " + mCurrentWordIndex);
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
            mCurrentSubtitleText = "";
            return;
        }
        
        String oldSubtitleText = mCurrentSubtitleText;
        
        // 提取字幕文本
        mCurrentSubtitleText = SubtitleTextProcessor.extractTextFromCues(cues);
        
        // 检测是否是自动生成字幕
        boolean isAutoGenerated = false;
        if (cues.size() > 1) {
            boolean allSingleWords = true;
            for (Cue cue : cues) {
                if (cue.text != null) {
                    String text = cue.text.toString();
                    if (text.split("\\s+").length > 1 && !SubtitleTextProcessor.containsOnlyCJK(text)) {
                        allSingleWords = false;
                        break;
                    }
                }
            }
            isAutoGenerated = allSingleWords;
        } else if (cues.size() == 1) {
            Cue cue = cues.get(0);
            if (cue.text != null) {
                isAutoGenerated = SubtitleTextProcessor.isAutoGeneratedSubtitle(cue.text.toString());
            }
        }
        
        Log.d(TAG, "设置字幕文本: " + mCurrentSubtitleText + (isAutoGenerated ? " (自动生成字幕)" : ""));
        
        // 检测字幕文本是否有变化
        if (!oldSubtitleText.equals(mCurrentSubtitleText)) {
            boolean isProtected = isSeekProtectionActive();
            
            if (!isProtected) {
                PlaybackView view = mPlaybackPresenter.getView();
                if (view != null) {
                    mLastSubtitleText = mCurrentSubtitleText;
                    mLastSubtitleChangeTime = view.getPositionMs();
                    Log.d(TAG, "字幕变化，记录时间点: " + mLastSubtitleChangeTime + "ms");
                }
            }
        }
        
        // 如果在选词模式，更新单词列表
        if (mIsWordSelectionMode) {
            boolean hasSignificantChange = !oldSubtitleText.equals(mCurrentSubtitleText) && 
                    (oldSubtitleText.length() < 3 || mCurrentSubtitleText.length() < 3 || 
                     !oldSubtitleText.substring(0, Math.min(3, oldSubtitleText.length())).equals(
                          mCurrentSubtitleText.substring(0, Math.min(3, mCurrentSubtitleText.length()))));
                     
            if (isAutoGenerated && hasSignificantChange) {
                Log.d(TAG, "检测到自动生成字幕更新，重置单词索引");
                splitSubtitleIntoWords();
                mCurrentWordIndex = 0;
            } else {
                splitSubtitleIntoWords();
            }
            
            if (mWords.length > 0 && mCurrentWordIndex >= mWords.length) {
                mCurrentWordIndex = 0;
            }
            
            highlightCurrentWord();
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
        mCurrentWordIndex = 0;
        
        Log.d(TAG, "分词结果 - 总单词数: " + mWords.length);
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
     * 翻译当前单词
     */
    private void translateCurrentWord() {
        mRetryCount = 0;
        translateCurrentWordWithRetry();
    }
    
    /**
     * 带重试功能的翻译当前单词
     */
    private void translateCurrentWordWithRetry() {
        if (mWords.length == 0 || mCurrentWordIndex >= mWords.length) {
            return;
        }
        
        String currentWordFromArray = mWords[mCurrentWordIndex];
        String actualHighlightedWord = getActualHighlightedWord();
        String wordToTranslate = (actualHighlightedWord != null && !actualHighlightedWord.isEmpty()) ? 
                                actualHighlightedWord : currentWordFromArray;
        
        if (mRetryCount == 0) {
            showDefinitionOverlay("正在查询中...\n请稍候", false);
        }
        
        // 在后台线程中执行翻译
        final String finalWordToTranslate = wordToTranslate;
        new Thread(() -> {
            String definition = TranslationService.fetchDefinition(finalWordToTranslate, mCurrentSubtitleText, mRetryCount);
            
            Log.d(TAG, "翻译结果长度: " + (definition != null ? definition.length() : "null"));
            
            // 检查结果是否需要重试
            boolean needRetry = (definition != null && 
                    (definition.contains("注意：AI没有使用中文回答") || 
                     (definition.contains("美式英语") && definition.length() < 200))
                    ) && mRetryCount < MAX_RETRY_COUNT;
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    if (mIsWordSelectionMode) {
                        if (needRetry) {
                            mRetryCount++;
                            Log.d(TAG, "需要重试，当前重试次数: " + mRetryCount);
                            int delayMs = 500 + (mRetryCount * 100);
                            mHandler.postDelayed(this::translateCurrentWordWithRetry, delayMs);
                        } else {
                            showDefinitionOverlay(definition, false);
                        }
                    }
                });
            }
        }).start();
    }
    
    /**
     * 获取字幕中实际高亮的单词
     */
    private String getActualHighlightedWord() {
        if (mIsWordSelectionMode && mWords != null && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
            String word = mWords[mCurrentWordIndex];
            return word.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").trim();
        }
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