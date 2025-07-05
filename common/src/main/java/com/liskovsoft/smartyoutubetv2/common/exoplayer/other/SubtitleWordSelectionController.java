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
 * 重写版本：彻底简化核心逻辑，确保功能正确稳定
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

    // 额外跟踪变量 - 强制保存最后一次自动选择的单词
    private String mLastAutoSelectedWord = null;
    private String mCurrentForcedHighlightWord = null; // 强制使用的高亮单词

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

    // 新增变量
    private String mLastHighlightedWord = null;  // 最后高亮的单词

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
     * 进入选词模式 - 完全重写版本
     *
     * @param fromStart 是否从第一个单词开始，false表示从最后一个单词开始
     */
    public void enterWordSelectionMode(boolean fromStart) {
        Log.d(TAG, "enterWordSelectionMode被调用，fromStart=" + fromStart);

        if (!mIsInitialized) {
            Log.d(TAG, "控制器尚未初始化，延迟激活选词模式");
            mPendingActivation = true;
            mPendingFromStart = fromStart;
            // 强制初始化
            initializeController();
            // 延迟处理选词模式
            mHandler.postDelayed(() -> enterWordSelectionMode(fromStart), 200);
            return;
        }

        if (mSubtitleView == null) {
            Log.d(TAG, "无法进入选词模式：字幕视图为空");
            return;
        }

        // 获取并设置字幕文本（优先使用当前字幕，如果为空则使用上一个字幕）
        refreshCurrentSubtitle();
        Log.d(TAG, "刷新字幕后，当前字幕文本: " + (mCurrentSubtitleText.isEmpty() ? "空" : mCurrentSubtitleText));

        // 如果当前字幕为空但有上一个字幕，使用上一个字幕
        if (mCurrentSubtitleText.isEmpty() && !mLastSubtitleText.isEmpty()) {
            mCurrentSubtitleText = mLastSubtitleText;
            Log.d(TAG, "当前字幕为空，使用上一个字幕文本: " + mCurrentSubtitleText);
        }

        if (mCurrentSubtitleText.isEmpty()) {
            Log.d(TAG, "无法进入选词模式：没有字幕文本");
            
            // 检查是否真的有字幕
            boolean reallyHasSubtitle = false;
            try {
                // 这里不使用hasSubtitleText()方法，因为它可能返回true仅仅因为字幕管理器存在
                // 而是直接检查是否有实际的字幕文本
                if (mSubtitleView != null) {
                    List<Cue> currentCues = mSubtitleView.getCues();
                    if (currentCues != null && !currentCues.isEmpty()) {
                        reallyHasSubtitle = true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "检查字幕文本时出错", e);
            }
            
            // 只有在确认有字幕的情况下才尝试使用测试文本
            if (reallyHasSubtitle) {
                Log.d(TAG, "确认有字幕，但获取不到文本，尝试强制创建");
                mCurrentSubtitleText = "This is a test subtitle text.";
            } else {
                Log.d(TAG, "确认没有字幕，取消进入选词模式");
                return; // 没有字幕时直接返回，不进入选词模式，也不暂停视频
            }
        }

        // 暂停视频 - 确保在任何情况下都暂停视频
        pauseVideo();

        // 设置选词模式状态
        mIsWordSelectionMode = true;
        mIsShowingDefinition = false;

        // 分割字幕为单词
        splitSubtitleIntoWords();
        Log.d(TAG, "分割字幕后，单词数量: " + mWords.length);

        if (mWords.length == 0) {
            Log.d(TAG, "没有找到可选择的单词，尝试再次分析字幕");
            refreshCurrentSubtitle();
            splitSubtitleIntoWords();
            Log.d(TAG, "再次分析后，单词数量: " + mWords.length);

            if (mWords.length == 0) {
                Log.d(TAG, "仍然没有找到可选择的单词，使用简单分词法");
                // 使用简单分词法
                String[] simpleWords = mCurrentSubtitleText.split("\\s+");
                if (simpleWords.length > 0) {
                    mWords = simpleWords;
                    // 简单计算单词位置
                    mWordPositions = new int[simpleWords.length];
                    int pos = 0;
                    for (int i = 0; i < simpleWords.length; i++) {
                        mWordPositions[i] = pos;
                        pos += simpleWords[i].length() + 1; // +1 是空格
                    }
                    Log.d(TAG, "简单分词法得到单词数量: " + mWords.length);
                } else {
                    Log.d(TAG, "仍然没有找到可选择的单词，退出选词模式");
                    mIsWordSelectionMode = false;
                    return;
                }
            }
        }

        // 设置单词索引 - 简化逻辑，只保留最基本的功能
        if (fromStart) {
            mCurrentWordIndex = 0;
            Log.d(TAG, "从第一个单词开始");
        } else {
            mCurrentWordIndex = mWords.length - 1;
            Log.d(TAG, "从最后一个单词开始");
            // 保存最后一个单词，以便后续使用
            mLastAutoSelectedWord = mWords[mCurrentWordIndex];
            Log.d(TAG, "【重写】自动选词：保存最后一个单词 = " + mLastAutoSelectedWord);
        }

        // 高亮当前单词
        highlightCurrentWord();

        Log.d(TAG, "已进入选词模式，单词数量: " + mWords.length + ", 当前索引: " + mCurrentWordIndex + ", 当前单词: " + (mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length ? mWords[mCurrentWordIndex] : "无效"));
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
            @SuppressWarnings("unchecked") List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);

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

        // 处理按键前，刷新一次状态，确保mIsWordSelectionMode的值是准确的
        refreshStatus();

        if (!mIsWordSelectionMode) {
            // 移除自动进入选词模式的代码
            return false;
        }

        // 音量键不拦截，让系统正常处理
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE) {
            Log.d(TAG, "选词模式下不拦截音量键: " + event.getKeyCode());
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }

        // 检查控制栏是否可见
        boolean isControlsVisible = isControlsOverlayVisible();

        // 如果控制栏可见，左右键不进行选词处理，而是进行前进/后退功能
        if (isControlsVisible && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            Log.d(TAG, "控制栏可见时，不处理左右键，返回false让系统处理前进/后退: " + event.getKeyCode());
            return false;
        }

        // 【修改】当用户按下左右方向键时，清除mLastAutoSelectedWord，表示用户已手动选择单词
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (mLastAutoSelectedWord != null) {
                Log.d(TAG, "【重要】用户按下方向键，清除自动选择的单词记录: " + mLastAutoSelectedWord);
                mLastAutoSelectedWord = null;
            }
            // 同时清除强制高亮单词
            if (mCurrentForcedHighlightWord != null) {
                Log.d(TAG, "【重要】用户按下方向键，清除强制高亮单词: " + mCurrentForcedHighlightWord);
                mCurrentForcedHighlightWord = null;
            }
        }

        // 删除强制使用自动选择单词的代码
        // 删除相关的 if 块

        // 确保单词列表有效
        if (mWords.length == 0 || mCurrentWordIndex >= mWords.length) {
            Log.d(TAG, "单词列表为空或索引无效，尝试刷新");

            // 保存当前选中的单词优先级顺序：当前选中单词
            String selectedWord = null;
            if (mWords.length > 0 && mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
                selectedWord = mWords[mCurrentWordIndex];
                Log.d(TAG, "保存当前选中的单词: " + selectedWord + ", 索引: " + mCurrentWordIndex);
            }

            refreshCurrentSubtitle();
            splitSubtitleIntoWords();

            // 尝试恢复之前选中的单词位置
            if (selectedWord != null && mWords.length > 0) {
                boolean found = false;
                // 精确匹配
                for (int i = 0; i < mWords.length; i++) {
                    if (selectedWord.equals(mWords[i])) {
                        mCurrentWordIndex = i;
                        Log.d(TAG, "恢复选中单词位置: " + selectedWord + ", 新索引: " + mCurrentWordIndex);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    // 如果找不到相同单词，则尝试查找相似单词（去除标点符号后比较）
                    String cleanSelectedWord = selectedWord.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").toLowerCase().trim();
                    for (int i = 0; i < mWords.length; i++) {
                        String cleanCurrentWord = mWords[i].replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").toLowerCase().trim();
                        if (cleanSelectedWord.equals(cleanCurrentWord)) {
                            mCurrentWordIndex = i;
                            Log.d(TAG, "通过相似匹配恢复单词位置: " + selectedWord + " -> " + mWords[i] + ", 索引: " + mCurrentWordIndex);
                            found = true;
                            break;
                        }
                    }

                    // 最后尝试包含关系匹配
                    if (!found) {
                        for (int i = 0; i < mWords.length; i++) {
                            String cleanCurrentWord = mWords[i].replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").toLowerCase().trim();
                            if (cleanSelectedWord.contains(cleanCurrentWord) || cleanCurrentWord.contains(cleanSelectedWord)) {
                                mCurrentWordIndex = i;
                                Log.d(TAG, "通过包含关系匹配恢复单词位置: " + selectedWord + " <-> " + mWords[i] + ", 索引: " + mCurrentWordIndex);
                                found = true;
                                break;
                            }
                        }
                    }

                    if (!found) {
                        Log.d(TAG, "未找到之前选中的单词: " + selectedWord + ", 使用默认索引");
                    }
                }
            }
        }

        if (mWords.length == 0) {
            Log.w(TAG, "无法处理按键：单词列表为空");
            return false;
        }

        if (mCurrentWordIndex >= mWords.length) {
            Log.d(TAG, "重置单词索引到0");
            mCurrentWordIndex = 0;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isControlsVisible) return false; // 控制栏可见时，左键不做选词处理
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
                if (isControlsVisible) return false; // 控制栏可见时，右键不做选词处理
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
     * 处理中心按键（OK键）- 完全重写版本
     */
    private boolean handleCenterKey() {
        // 确保单词列表有效
        if (mWords == null || mWords.length == 0) {
            Log.d(TAG, "【重写】handleCenterKey: 单词列表为空，无法处理");
            return true;
        }

        // 保存当前索引，以便后续使用
        final int savedWordIndex = mCurrentWordIndex;
        Log.d(TAG, "【重写】handleCenterKey: 保存当前单词索引 = " + savedWordIndex);

        // 处理自动选词情况：如果字幕已消失但有上一个字幕，使用上一个字幕
        boolean contextRestored = restoreSubtitleContextIfNeeded();

        // 如果上下文被恢复，尝试恢复原始索引
        if (contextRestored && savedWordIndex >= 0 && savedWordIndex < mWords.length) {
            mCurrentWordIndex = savedWordIndex;
            Log.d(TAG, "【重写】handleCenterKey: 上下文恢复后重置索引为 = " + mCurrentWordIndex);
        }

        // 确保索引有效，但不要自动跳转到第一个或最后一个单词
        if (mCurrentWordIndex < 0 || mCurrentWordIndex >= mWords.length) {
            if (mWords.length > 0) {
                // 使用第一个单词作为默认值，但只在必要时使用
                mCurrentWordIndex = 0;
                Log.d(TAG, "【重写】handleCenterKey: 修正无效索引，使用第一个单词 = " + mWords[mCurrentWordIndex]);
            } else {
                Log.d(TAG, "【重写】handleCenterKey: 单词列表为空或索引无效，无法继续");
                return true;
            }
        }

        // 记录点击时间（用于双击检测）
        long clickTime = System.currentTimeMillis();
        long timeDiff = clickTime - mLastClickTime;
        mLastClickTime = clickTime;

        Log.d(TAG, "OK按键详情 - 时间差: " + timeDiff + "ms, 阈值: " + DOUBLE_CLICK_TIME_DELTA + "ms");
        Log.d(TAG, "OK按键详情 - 当前状态: 选词模式=" + mIsWordSelectionMode + ", 显示定义=" + mIsShowingDefinition + ", 当前索引=" + mCurrentWordIndex + ", 单词数量=" + mWords.length);

        // 处理双击
        if (timeDiff < DOUBLE_CLICK_TIME_DELTA) {
            Log.d(TAG, "【重写】handleCenterKey: 检测到双击OK按键");

            if (mIsShowingDefinition) {
                hideDefinitionOverlay();
            }

            showDoubleClickToast();
            seekToCurrentSubtitleStartTime();
            exitWordSelectionMode();

            mLastClickTime = 0;
            return true;
        }

        // 单击处理 - 延迟执行，以便能检测双击
        final int finalWordIndex = mCurrentWordIndex; // 捕获当前索引
        mHandler.postDelayed(() -> {
            if (System.currentTimeMillis() - mLastClickTime >= DOUBLE_CLICK_TIME_DELTA) {
                Log.d(TAG, "【重写】handleCenterKey: 执行单击操作");

                // 如果当前显示定义，则处理学习状态切换
                if (mIsShowingDefinition) {
                    toggleWordLearningStatus();
                    return;
                }

                // 确保使用保存的索引
                if (finalWordIndex >= 0 && finalWordIndex < mWords.length) {
                    mCurrentWordIndex = finalWordIndex;
                }

                // 否则翻译当前单词
                Log.d(TAG, "【重写】handleCenterKey: 翻译当前单词，索引 = " + mCurrentWordIndex + ", 单词 = " + (mCurrentWordIndex < mWords.length ? mWords[mCurrentWordIndex] : "无效"));

                // 重新高亮当前单词，确保UI状态一致
                highlightCurrentWord();

                // 不要在这里调用restoreSubtitleContextIfNeeded，避免重复处理
                // 直接翻译当前单词
                translateCurrentWordDirectly();
            }
        }, DOUBLE_CLICK_TIME_DELTA);

        return true;
    }

    /**
     * 直接翻译当前单词（不尝试恢复上下文）
     */
    private void translateCurrentWordDirectly() {
        Log.d(TAG, "【重写】translateCurrentWordDirectly: 开始直接翻译单词");

        // 确保单词列表和索引有效
        if (mWords == null || mWords.length == 0) {
            Log.e(TAG, "【重写】translateCurrentWordDirectly: 单词列表为空，无法翻译");
            showDefinitionOverlay("无法获取单词信息，请重试", false);
            return;
        }

        // 确保索引有效
        if (mCurrentWordIndex < 0 || mCurrentWordIndex >= mWords.length) {
            if (mWords.length > 0) {
                // 使用第一个单词而不是最后一个单词
                mCurrentWordIndex = 0;
                Log.d(TAG, "【重写】translateCurrentWordDirectly: 修正无效索引为第一个单词，索引 = " + mCurrentWordIndex);
                highlightCurrentWord();
            } else {
                Log.e(TAG, "【重写】translateCurrentWordDirectly: 无法获取有效单词");
                showDefinitionOverlay("无法获取有效单词，请重试", false);
                return;
            }
        }

        // 获取要翻译的单词
        String wordToTranslate = mWords[mCurrentWordIndex];

        if (wordToTranslate == null || wordToTranslate.trim().isEmpty()) {
            Log.e(TAG, "【重写】translateCurrentWordDirectly: 选中的单词为空");
            showDefinitionOverlay("无法获取单词内容，请重试", false);
            return;
        }

        Log.d(TAG, "【重写】translateCurrentWordDirectly: 准备翻译单词 = " + wordToTranslate + ", 字幕上下文 = " + mCurrentSubtitleText);

        // 重置重试计数
        mRetryCount = 0;

        // 开始翻译
        translateCurrentWordWithRetry();
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

        // 检测字幕是否变化
        boolean isSubtitleChanged = !mCurrentSubtitleText.equals(oldSubtitleText);

        if (isSubtitleChanged) {
            // 记录字幕变化时间
            mLastSubtitleText = oldSubtitleText;
            mLastSubtitleChangeTime = System.currentTimeMillis();

            // 检查是否启用了"字幕结束时自动选择最后一个单词"功能
            if (!mIsWordSelectionMode && com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(mContext).isAutoSelectLastWordEnabled()) {

                Log.d(TAG, "检查是否需要自动选择最后一个单词 - 旧字幕: " + (oldSubtitleText.length() > 50 ? oldSubtitleText.substring(0, 50) + "..." : oldSubtitleText) + ", 新字幕: " + (mCurrentSubtitleText.length() > 50 ? mCurrentSubtitleText.substring(0, 50) + "..." : mCurrentSubtitleText));

                // 修改触发条件：字幕结束或者字幕变化且包含完整句子
                boolean isSubtitleEnding = !oldSubtitleText.isEmpty() && mCurrentSubtitleText.isEmpty();
                boolean isCompleteSubtitle = !oldSubtitleText.isEmpty() && (oldSubtitleText.endsWith(".") || oldSubtitleText.endsWith("?") || oldSubtitleText.endsWith("!") || oldSubtitleText.endsWith("。") || oldSubtitleText.endsWith("？") || oldSubtitleText.endsWith("！"));

                if (isSubtitleEnding || (isSubtitleChanged && isCompleteSubtitle)) {
                    Log.d(TAG, "检测到字幕" + (isSubtitleEnding ? "结束" : "完整句子") + "且启用了自动选词功能，自动进入选词模式并从最后一个单词开始");

                    // 保存要使用的字幕文本
                    String subtitleToUse = isSubtitleEnding ? oldSubtitleText : mCurrentSubtitleText;
                    String tempSubtitle = mCurrentSubtitleText;
                    mCurrentSubtitleText = subtitleToUse;

                    // 预处理字幕文本，确保单词分割正确
                    splitSubtitleIntoWords();

                    // 从最后一个单词开始进入选词模式
                    if (mWords.length > 0) {
                        Log.d(TAG, "找到单词列表，长度: " + mWords.length + "，准备选择最后一个单词");
                        enterWordSelectionMode(false);

                        // 确保选中最后一个单词并高亮显示
                        if (mIsWordSelectionMode && mWords.length > 0) {
                            mCurrentWordIndex = mWords.length - 1;
                            highlightCurrentWord();
                            Log.d(TAG, "已选中最后一个单词: " + mWords[mCurrentWordIndex]);
                        }
                    } else {
                        Log.d(TAG, "字幕文本中未找到单词，无法进入选词模式");
                        // 如果enterWordSelectionMode失败，恢复当前字幕
                        if (!mIsWordSelectionMode) {
                            mCurrentSubtitleText = tempSubtitle;
                        }
                    }
                }
            }
        }

        Log.d(TAG, "设置字幕文本: " + mCurrentSubtitleText);

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
            // 分割文本并更新单词索引
            splitSubtitleIntoWords();

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
        try {
            boolean hasText = mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
            Log.d(TAG, "hasSubtitleText初步检查: " + hasText + ", mCurrentSubtitleText=" + (mCurrentSubtitleText != null ? "\"" + mCurrentSubtitleText + "\"" : "null"));
    
            if (!hasText) {
                // 如果没有字幕文本，尝试刷新一次
                refreshCurrentSubtitle();
                hasText = mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
                Log.d(TAG, "刷新后 hasSubtitleText: " + hasText + ", mCurrentSubtitleText=" + (mCurrentSubtitleText != null ? "\"" + mCurrentSubtitleText + "\"" : "null"));
    
                // 如果还是没有，尝试检查字幕视图
                if (!hasText && mSubtitleView != null) {
                    try {
                        // 检查是否有可见的字幕
                        if (mSubtitleView.getVisibility() == View.VISIBLE) {
                            // 尝试通过反射获取字幕内容
                            tryGetSubtitleByReflection();
                            hasText = mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
                            Log.d(TAG, "通过反射后 hasSubtitleText: " + hasText);
                        }
    
                        // 如果字幕视图可见，则认为有字幕
                        if (!hasText && mSubtitleView.getVisibility() == View.VISIBLE) {
                            Log.d(TAG, "字幕视图可见，但无法获取文本，假定有字幕");
                            return true;
                        }
    
                        // 检查是否有上一个字幕
                        if (!hasText && mLastSubtitleText != null && !mLastSubtitleText.isEmpty()) {
                            Log.d(TAG, "使用上一个字幕文本: " + mLastSubtitleText);
                            mCurrentSubtitleText = mLastSubtitleText;
                            return true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "检查字幕视图时出错: " + e.getMessage(), e);
                    }
                }
    
                // 最后的方法：检查字幕管理器
                if (!hasText && mPlaybackPresenter != null && mPlaybackPresenter.getView() != null) {
                    try {
                        SubtitleManager subtitleManager = mPlaybackPresenter.getView().getSubtitleManager();
                        if (subtitleManager != null) {
                            // 这里我们需要更严格地判断是否真的有字幕
                            // 不再仅仅因为字幕管理器存在就假定有字幕
                            // 只有当确实存在字幕数据时才返回true
                            if (subtitleManager.getWordSelectionController() != null) {
                                Log.d(TAG, "找到字幕控制器，进一步检查字幕");
                                // 什么都不做，让方法返回hasText的值
                            } else {
                                Log.d(TAG, "找到字幕管理器，但没有字幕控制器，可能没有字幕");
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "检查字幕管理器时出错: " + e.getMessage(), e);
                    }
                }
            }
            
            return hasText;
        } catch (Exception e) {
            Log.e(TAG, "hasSubtitleText方法出现异常: " + e.getMessage(), e);
            return false; // 出现异常时返回false，表示没有字幕
        }
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

        // 使用统一的分词方法
        List<String> wordList = SubtitleTextProcessor.tokenizeText(mCurrentSubtitleText, containsCJK, false);

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

        // 清除自动选择的单词记录，因为用户正在手动选择单词
        if (mLastAutoSelectedWord != null) {
            Log.d(TAG, "【重要】用户手动选择下一个单词，清除自动选择的单词记录: " + mLastAutoSelectedWord);
            mLastAutoSelectedWord = null;
        }

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

        // 清除自动选择的单词记录，因为用户正在手动选择单词
        if (mLastAutoSelectedWord != null) {
            Log.d(TAG, "【重要】用户手动选择上一个单词，清除自动选择的单词记录: " + mLastAutoSelectedWord);
            mLastAutoSelectedWord = null;
        }

        if (mWords.length > 0) {
            mCurrentWordIndex = (mCurrentWordIndex - 1 + mWords.length) % mWords.length;
            Log.d(TAG, "选择上一个单词: " + mWords[mCurrentWordIndex] + " 索引: " + mCurrentWordIndex);
            highlightCurrentWord();
        }
    }

    /**
     * 高亮当前单词
     */
    private void highlightCurrentWord() {
        if (mWords == null || mWords.length == 0 || mWordPositions == null || mWordPositions.length != mWords.length) {
            Log.e(TAG, "【重写】高亮单词失败：无效的单词数组或位置数组");
            return;
        }

        // 确保索引有效
        if (mCurrentWordIndex < 0) {
            mCurrentWordIndex = 0;
        } else if (mCurrentWordIndex >= mWords.length) {
            mCurrentWordIndex = mWords.length - 1;
        }

        // 保存当前单词以备后用
        String currentWord = mWords[mCurrentWordIndex];
        mLastHighlightedWord = currentWord;

        // 【修改】只有在自动选词模式下才更新mLastAutoSelectedWord
        // 检查是否是由自动选词功能触发的高亮操作
        boolean isAutoSelection = false;

        // 通过调用栈分析是否是自动选词
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            // 检查是否是从setCurrentSubtitleText方法调用的
            if (element.getMethodName().equals("setCurrentSubtitleText")) {
                isAutoSelection = true;
                break;
            }
        }

        if (mCurrentWordIndex == mWords.length - 1 && isAutoSelection) {
            // 只有在自动选词模式下且是最后一个单词时，才更新mLastAutoSelectedWord
            mLastAutoSelectedWord = currentWord;
            Log.d(TAG, "【重写】自动选词模式下高亮最后一个单词: " + currentWord);
        }

        // 使用原始的高亮逻辑
        int wordPosition = mWordPositions[mCurrentWordIndex];
        highlightWordInSubtitle(currentWord, wordPosition);

        // 检查单词是否在学习列表中
        if (mVocabularyDatabase != null && mVocabularyDatabase.isWordInLearningList(currentWord)) {
            if (!mIsShowingDefinition) {
                translateCurrentWord();
            }
        }

        Log.d(TAG, "【重写】高亮单词 \"" + currentWord + "\" (索引: " + mCurrentWordIndex + ")");
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
            @SuppressWarnings("unchecked") List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);

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
     * 恢复字幕上下文并尝试保持当前单词索引
     * 当用户按下OK键时，字幕可能已经消失，此方法确保我们能正确恢复上下文
     *
     * @return 是否进行了恢复操作
     */
    private boolean restoreSubtitleContextIfNeeded() {
        // 如果当前字幕为空但上一个字幕不为空，恢复上下文
        if (mCurrentSubtitleText.isEmpty() && !mLastSubtitleText.isEmpty()) {
            Log.d(TAG, "【重写】恢复字幕上下文");

            // 保存当前索引
            int savedIndex = mCurrentWordIndex;
            Log.d(TAG, "【重写】恢复前保存索引: " + savedIndex);

            // 恢复字幕文本
            mCurrentSubtitleText = mLastSubtitleText;
            Log.d(TAG, "【重写】已恢复字幕文本: " + mCurrentSubtitleText);

            // 重新分词
            splitSubtitleIntoWords();
            Log.d(TAG, "【重写】重新分词后单词数量: " + mWords.length);

            // 恢复原来选中的单词索引，如果索引无效则使用有效范围内的索引
            if (mWords.length > 0) {
                if (savedIndex >= 0 && savedIndex < mWords.length) {
                    // 保持原有索引
                    mCurrentWordIndex = savedIndex;
                    Log.d(TAG, "【重写】恢复后保持原有单词索引: " + mCurrentWordIndex + ", 单词: " + mWords[mCurrentWordIndex]);
                } else {
                    // 索引无效时，使用合理的默认值（第一个单词）
                    mCurrentWordIndex = 0;
                    Log.d(TAG, "【重写】恢复后使用第一个单词: " + mWords[mCurrentWordIndex]);
                }

                // 注意：这里不自动高亮单词，由调用方决定是否需要高亮
                // 这样可以避免在多处调用该方法时出现重复高亮的问题
                return true;
            }
        }

        return false;
    }

    /**
     * 翻译当前单词 - 完全重写版本
     */
    private void translateCurrentWord() {
        Log.d(TAG, "【重写】translateCurrentWord: 开始翻译单词");

        // 确保单词列表和索引有效
        if (mWords == null || mWords.length == 0) {
            Log.e(TAG, "【重写】translateCurrentWord: 单词列表为空，无法翻译");
            showDefinitionOverlay("无法获取单词信息，请重试", false);
            return;
        }

        // 保存当前索引，以便后续恢复
        final int savedWordIndex = mCurrentWordIndex;
        Log.d(TAG, "【重写】translateCurrentWord: 保存当前单词索引 = " + savedWordIndex);

        // 处理字幕为空的情况
        boolean contextRestored = restoreSubtitleContextIfNeeded();

        // 如果上下文被恢复，尝试恢复原始索引
        if (contextRestored && savedWordIndex >= 0 && savedWordIndex < mWords.length) {
            mCurrentWordIndex = savedWordIndex;
            Log.d(TAG, "【重写】translateCurrentWord: 上下文恢复后重置索引为 = " + mCurrentWordIndex);
            highlightCurrentWord();
        }

        // 确保索引有效
        if (mCurrentWordIndex < 0 || mCurrentWordIndex >= mWords.length) {
            if (mWords.length > 0) {
                // 使用第一个单词而不是最后一个单词
                mCurrentWordIndex = 0;
                Log.d(TAG, "【重写】translateCurrentWord: 修正无效索引为第一个单词，索引 = " + mCurrentWordIndex);
                highlightCurrentWord();
            } else {
                Log.e(TAG, "【重写】translateCurrentWord: 无法获取有效单词");
                showDefinitionOverlay("无法获取有效单词，请重试", false);
                return;
            }
        }

        // 获取要翻译的单词
        String wordToTranslate = mWords[mCurrentWordIndex];

        if (wordToTranslate == null || wordToTranslate.trim().isEmpty()) {
            Log.e(TAG, "【重写】translateCurrentWord: 选中的单词为空");
            showDefinitionOverlay("无法获取单词内容，请重试", false);
            return;
        }

        Log.d(TAG, "【重写】translateCurrentWord: 准备翻译单词 = " + wordToTranslate + ", 字幕上下文 = " + mCurrentSubtitleText);

        // 重置重试计数
        mRetryCount = 0;

        // 开始翻译
        translateCurrentWordWithRetry();
    }

    /**
     * 带重试机制的翻译当前单词
     */
    private void translateCurrentWordWithRetry() {
        if (mWords == null || mWords.length == 0 || mCurrentWordIndex < 0 || mCurrentWordIndex >= mWords.length) {
            Log.e(TAG, "【重写】translateCurrentWordWithRetry: 无效的单词索引或数组");
            showDefinitionOverlay("无法获取有效单词，请重试", false);
            return;
        }

        // 获取要翻译的单词
        String currentWordFromArray = mWords[mCurrentWordIndex];

        // 如果是首次尝试，显示加载信息
        if (mRetryCount == 0) {
            showDefinitionOverlay("正在查询中...\n请稍候", false);
        }

        Log.d(TAG, "【重写】翻译单词: " + currentWordFromArray + ", 重试次数: " + mRetryCount);

        // 在后台线程中执行翻译
        final String finalWordToTranslate = currentWordFromArray;
        new Thread(() -> {
            String definition = TranslationService.fetchDefinition(finalWordToTranslate, mCurrentSubtitleText, mRetryCount);

            Log.d(TAG, "翻译结果长度: " + (definition != null ? definition.length() : "null"));

            // 检查结果是否需要重试
            boolean needRetry = (definition != null && (definition.contains("注意：AI没有使用中文回答") || (definition.contains("美式英语") && definition.length() < 200))) && mRetryCount < MAX_RETRY_COUNT;

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
                            showDefinitionOverlay(definition, true);
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
     * 直接从UI中获取当前高亮的单词
     * 这个方法会尝试从字幕画笔中直接读取高亮单词，比内存变量更可靠
     */
    private String getCurrentHighlightedWordFromUI() {
        if (mSubtitleView == null) {
            Log.d(TAG, "无法从UI获取高亮单词：字幕视图为空");
            return null;
        }

        try {
            // 通过反射获取字幕画笔
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked") List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);

            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    try {
                        // 尝试获取高亮单词
                        Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                        highlightWordField.setAccessible(true);
                        Object highlightWord = highlightWordField.get(painter);

                        if (highlightWord != null) {
                            Log.d(TAG, "从UI直接读取到高亮单词: " + highlightWord.toString());
                            return highlightWord.toString();
                        }
                    } catch (Exception e) {
                        // 忽略特定画笔的反射失败
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "从UI获取高亮单词失败: " + e.getMessage(), e);
        }

        Log.d(TAG, "未能从UI直接读取到高亮单词");
        return null;
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
        // 获取状态前先刷新一次，确保状态准确
        refreshStatus();
        return mIsWordSelectionMode;
    }

    /**
     * 刷新选词控制器状态 - 强制检查当前UI状态判断是否在选词模式
     */
    public void refreshStatus() {
        Log.d(TAG, "刷新选词控制器状态, 当前记录的状态: " + mIsWordSelectionMode);

        // 检查UI状态确认是否真的在选词模式
        boolean actualModeFromUI = false;
        boolean hasHighlightedWord = false;
        String currentHighlightedWord = null;

        try {
            // 如果字幕视图中有高亮单词，说明确实在选词模式
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked") List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);

            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    try {
                        Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                        highlightWordField.setAccessible(true);
                        Object highlightWord = highlightWordField.get(painter);

                        if (highlightWord != null) {
                            actualModeFromUI = true;
                            hasHighlightedWord = true;
                            currentHighlightedWord = highlightWord.toString();
                            Log.d(TAG, "在UI中找到高亮单词: " + currentHighlightedWord);
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

            // 如果UI显示我们在选词模式，但状态变量说我们不在，则同步其他状态变量
            if (actualModeFromUI && hasHighlightedWord) {
                // 如果有字幕文本但单词列表为空，重新分词
                if (hasSubtitleText() && (mWords == null || mWords.length == 0)) {
                    Log.d(TAG, "同步状态：发现单词列表为空，重新分词");
                    splitSubtitleIntoWords();
                }

                // 如果找到了高亮单词但索引不匹配，尝试找到正确的索引
                if (mWords != null && mWords.length > 0 && currentHighlightedWord != null) {
                    boolean foundMatchingWord = false;
                    for (int i = 0; i < mWords.length; i++) {
                        if (currentHighlightedWord.equals(mWords[i])) {
                            if (mCurrentWordIndex != i) {
                                Log.d(TAG, "同步状态：更新单词索引 " + mCurrentWordIndex + " -> " + i + " (" + mWords[i] + ")");
                                mCurrentWordIndex = i;
                            }
                            foundMatchingWord = true;
                            break;
                        }
                    }

                    if (!foundMatchingWord) {
                        // 尝试相似匹配
                        String cleanHighlightedWord = currentHighlightedWord.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").trim();
                        for (int i = 0; i < mWords.length; i++) {
                            String cleanCurrentWord = mWords[i].replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").trim();
                            if (cleanHighlightedWord.equals(cleanCurrentWord)) {
                                if (mCurrentWordIndex != i) {
                                    Log.d(TAG, "同步状态：通过相似匹配更新单词索引 " + mCurrentWordIndex + " -> " + i + " (" + currentHighlightedWord + " -> " + mWords[i] + ")");
                                    mCurrentWordIndex = i;
                                }
                                foundMatchingWord = true;
                                break;
                            }
                        }
                    }
                }
            }
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
            @SuppressWarnings("unchecked") List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);

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

                if (mLastSubtitleChangeTime > currentPositionMs + 5000 || mLastSubtitleChangeTime < currentPositionMs - 30000) {
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