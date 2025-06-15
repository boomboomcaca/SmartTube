package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Field;
import android.util.Log;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URL;
import android.app.Activity;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.AlphaAnimation;
import android.os.Handler;
import java.lang.reflect.Method;

/**
 * 控制器类，用于管理字幕选词功能
 */
public class SubtitleWordSelectionController {
    private static final String TAG = SubtitleWordSelectionController.class.getSimpleName();
    
    private final Context mContext;
    private final SubtitleView mSubtitleView;
    private final FrameLayout mRootView;
    private final PlaybackPresenter mPlaybackPresenter;
    
    // 单词数据库
    private VocabularyDatabase mVocabularyDatabase;
    
    private boolean mIsWordSelectionMode = false;
    private String mCurrentSubtitleText = "";
    private String[] mWords = new String[0];
    private int mCurrentWordIndex = 0;
    private int[] mWordPositions = new int[0];
    private TextView mTextView;
    private FrameLayout mSelectionOverlay;
    private boolean mIsShowingDefinition = false; // 是否正在显示解释窗口
    
    private int mScrollPosition = 0;
    private static final int SCROLL_STEP = 50;
    
    private final Handler mHandler = new Handler();
    private boolean mIsInitialized = false; // 标记是否已完成初始化
    private boolean mPendingActivation = false; // 标记是否有待激活的选词请求
    private boolean mPendingFromStart = true; // 待激活的选词模式是否从第一个单词开始
    
    // 双击检测相关变量
    private long mLastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 800; // 双击时间阈值，单位毫秒
    
    // 字幕时间记录
    private String mLastSubtitleText = "";
    private long mLastSubtitleChangeTime = 0;
    
    // 跳转保护 - 防止重新播放时更新字幕时间
    private boolean mIsSeekProtectionActive = false;
    private long mSeekProtectionStartTime = 0;
    private static final long SEEK_PROTECTION_DURATION = 5000; // 跳转保护持续5秒
    
    // 添加一个成员变量，用于存储当前的AI命令
    private String mCurrentAIPrompt = "";
    
    // 在类的成员变量区域添加重试计数器和最大重试次数
    private int mRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 10;  // 最大重试次数，防止无限循环
    
    public SubtitleWordSelectionController(Context context, SubtitleView subtitleView, FrameLayout rootView) {
        mContext = context;
        mSubtitleView = subtitleView;
        mRootView = rootView;
        mPlaybackPresenter = PlaybackPresenter.instance(context);
        
        // 初始化单词数据库
        mVocabularyDatabase = VocabularyDatabase.getInstance(context);
        
        // 创建选词覆盖层
        createSelectionOverlay();
        
        // 延迟初始化，确保所有组件都已准备好
        mHandler.postDelayed(this::initializeController, 1000);
    }
    
    /**
     * 初始化控制器
     */
    private void initializeController() {
        // 尝试获取当前字幕
        if (mSubtitleView != null) {
            List<Cue> currentCues = mSubtitleView.getCues();
            if (currentCues != null && !currentCues.isEmpty()) {
                setCurrentSubtitleText(currentCues);
            }
        }
        
        mIsInitialized = true;
        Log.d(TAG, "字幕选词控制器初始化完成");
        
        // 如果有待激活的请求，现在激活它
        if (mPendingActivation) {
            mHandler.post(() -> enterWordSelectionMode(mPendingFromStart));
            mPendingActivation = false;
        }
    }
    
    /**
     * 进入选词模式
     */
    public void enterWordSelectionMode() {
        enterWordSelectionMode(true); // 默认从第一个单词开始
    }
    
    /**
     * 进入选词模式
     * @param fromStart 是否从第一个单词开始，false表示从最后一个单词开始
     */
    public void enterWordSelectionMode(boolean fromStart) {
        // 如果控制器尚未初始化，则延迟激活
        if (!mIsInitialized) {
            Log.d(TAG, "控制器尚未初始化，延迟激活选词模式");
            mPendingActivation = true;
            mPendingFromStart = fromStart; // 保存起始位置参数
            return;
        }
        
        // 确保字幕文本存在
        if (mSubtitleView == null) {
            Log.d(TAG, "无法进入选词模式：字幕视图为空");
            MessageHelpers.showMessage(mContext, R.string.no_subtitle_available);
            return;
        }
        
        // 尝试获取最新字幕
        refreshCurrentSubtitle();
        
        // 验证字幕文本
        if (!hasSubtitleText()) {
            Log.d(TAG, "无法进入选词模式：没有字幕文本");
            MessageHelpers.showMessage(mContext, R.string.no_subtitle_available);
            return;
        }
        
        // 暂停视频
        PlaybackView view = mPlaybackPresenter.getView();
        if (view != null) {
            view.setPlayWhenReady(false);
        }
        
        // 进入选词模式
        mIsWordSelectionMode = true;
        mIsShowingDefinition = false;
        
        // 分割当前字幕文本为单词
        splitSubtitleIntoWords();
        
        // 确保有单词可选
        if (mWords.length == 0) {
            Log.d(TAG, "没有找到可选择的单词，尝试再次分析字幕");
            // 再次尝试提取字幕文本
            refreshCurrentSubtitle();
            // 再次尝试分割单词
            splitSubtitleIntoWords();
            
            // 如果仍然没有单词，则显示消息并退出选词模式
            if (mWords.length == 0) {
                Log.d(TAG, "仍然没有找到可选择的单词，退出选词模式");
                MessageHelpers.showMessage(mContext, R.string.no_subtitle_available);
                mIsWordSelectionMode = false;
            return;
            }
        }
        
        // 根据fromStart参数设置起始单词索引
        if (fromStart) {
            mCurrentWordIndex = 0; // 从第一个单词开始
        } else {
            mCurrentWordIndex = mWords.length - 1; // 从最后一个单词开始
        }
        
        // 高亮显示当前单词
        highlightCurrentWord();
        
        // 记录日志
        Log.d(TAG, "已进入选词模式，单词数量: " + mWords.length + ", 当前索引: " + mCurrentWordIndex);
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
                Log.d(TAG, "刷新字幕失败：当前没有字幕Cue");
                
                // 尝试通过反射获取字幕内容
                try {
                    // 通过反射获取 SubtitleView 的内部实现
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
        }
    }
    
    /**
     * 处理按键事件
     * @return 是否已处理按键事件
     */
    public boolean handleKeyEvent(KeyEvent event) {
        // 记录键码，便于调试
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "收到按键: " + event.getKeyCode());
        }
        
        // 如果控制器尚未初始化，延迟处理按键事件
        if (!mIsInitialized) {
            Log.d(TAG, "控制器尚未初始化，忽略按键事件");
            return false;
        }
        
        if (!mIsWordSelectionMode) {
            // 如果不在选词模式，不处理按键
            return false;
        }
        
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true; // 消费所有按键抬起事件
        }
        
        // 尝试刷新字幕和单词列表，确保数据是最新的
        if (mWords.length == 0 || (mCurrentWordIndex >= mWords.length)) {
            Log.d(TAG, "单词列表为空或索引无效，尝试刷新");
            refreshCurrentSubtitle();
            splitSubtitleIntoWords();
        }
        
        // 再次检查单词列表
        if (mWords.length == 0) {
            Log.w(TAG, "无法处理按键：单词列表为空");
            return false;
        }
        
        // 确保当前索引有效
        if (mCurrentWordIndex >= mWords.length) {
            Log.d(TAG, "重置单词索引到0，当前值 " + mCurrentWordIndex + " 超出范围 " + mWords.length);
            mCurrentWordIndex = 0;
        }
        
        // 检查工具栏是否显示
        boolean isControlsVisible = isControlsOverlayVisible();
        
        // 记录当前选中的单词和状态，便于调试
        Log.d(TAG, "处理按键前状态：当前单词 = " + (mCurrentWordIndex < mWords.length ? mWords[mCurrentWordIndex] : "无效") + 
              ", 显示解释 = " + mIsShowingDefinition + 
              ", 工具栏可见 = " + isControlsVisible);
        
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isControlsVisible) {
                    // 如果工具栏显示，不处理左右键，让它们恢复默认导航功能
                    return false;
                }
                
                if (mIsShowingDefinition) {
                    // 如果正在显示解释窗口，先隐藏窗口
                    hideDefinitionOverlay();
                    // 然后选择上一个单词
                    selectPreviousWord();
                } else {
                    selectPreviousWord();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isControlsVisible) {
                    // 如果工具栏显示，不处理左右键，让它们恢复默认导航功能
                    return false;
                }
                
                if (mIsShowingDefinition) {
                    // 如果正在显示解释窗口，先隐藏窗口
                    hideDefinitionOverlay();
                    // 然后选择下一个单词
                    selectNextWord();
                } else {
                    selectNextWord();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mIsShowingDefinition) {
                    // 向上翻页滚动
                    scrollDefinitionByPage(-1);
                    return true;
                }
                return false;
                
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mIsShowingDefinition) {
                    // 向下翻页滚动
                    scrollDefinitionByPage(1);
                    return true;
                }
                return false;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 检测是否是双击 - 当前时间与上次点击时间的差小于阈值
                long clickTime = System.currentTimeMillis();
                long timeDiff = clickTime - mLastClickTime;
                
                // 记录详细日志用于调试
                Log.d(TAG, "OK按键详情 - 当前时间: " + clickTime + 
                      "ms, 上次时间: " + mLastClickTime + 
                      "ms, 时间差: " + timeDiff + 
                      "ms, 阈值: " + DOUBLE_CLICK_TIME_DELTA + "ms");
                
                if (timeDiff < DOUBLE_CLICK_TIME_DELTA) {
                    // 双击操作 - 从当前字幕时间点重新播放并退出选词模式
                    Log.d(TAG, "检测到双击OK按键，从当前字幕开始时间重新播放");
                    
                    // 显示提示信息
                    showDoubleClickToast();
                    
                    // 执行重新播放操作
                    seekToCurrentSubtitleStartTime();
                    exitWordSelectionMode();
                    
                    // 重置最后点击时间，避免三击被误认为另一次双击
                    mLastClickTime = 0;
                    return true;
                }
                
                // 更新最后点击时间，用于下次双击检测
                mLastClickTime = clickTime;
                
                // 单击逻辑
                if (!mIsShowingDefinition) {
                    // 如果没有显示解释，显示单词翻译
                    translateCurrentWord();
                } else {
                    // 如果已经显示解释，切换单词学习状态
                    String currentWord = "";
                    if (mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
                        currentWord = mWords[mCurrentWordIndex];
                    } else {
                        Log.e(TAG, "无法获取当前单词，索引无效: " + mCurrentWordIndex);
                        return true;
                    }
                    
                    // 切换单词学习状态
                    boolean isLearningWord = mVocabularyDatabase.isWordInLearningList(currentWord);
                    boolean success = false;
                    
                    if (isLearningWord) {
                        // 如果单词已在学习列表中，从列表中删除
                        success = mVocabularyDatabase.removeWord(currentWord);
                        if (success) {
                            // 更新解释窗口第一行
                            updateDefinitionFirstLine(currentWord, false);
                            Log.d(TAG, "单词已从学习列表中删除: " + currentWord);
                        } else {
                            Log.e(TAG, "从学习列表中删除单词失败: " + currentWord);
                        }
                    } else {
                        // 如果单词不在学习列表中，添加到列表
                        success = mVocabularyDatabase.addWord(currentWord);
                        if (success) {
                            // 更新解释窗口第一行
                            updateDefinitionFirstLine(currentWord, true);
                            Log.d(TAG, "单词已添加到学习列表: " + currentWord);
                        } else {
                            Log.e(TAG, "添加单词到学习列表失败: " + currentWord);
                        }
                    }
                    
                    // 强制刷新字幕，使学习中单词的高亮状态立即生效
                    refreshSubtitleView();
                }
                return true;
                
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (mIsShowingDefinition) {
                    // 如果正在显示解释窗口，先隐藏窗口
                    hideDefinitionOverlay();
                } else {
                    // 否则退出选词模式
                    exitWordSelectionMode();
                }
                return true;
                
            default:
                return true; // 在选词模式下消费所有按键事件
        }
    }
    
    /**
     * 检查工具栏是否可见
     */
    private boolean isControlsOverlayVisible() {
        PlaybackPresenter playbackPresenter = mPlaybackPresenter;
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            return playbackPresenter.getView().isControlsVisible();
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
        
        // 保存旧的字幕文本，用于检测是否有变化
        String oldSubtitleText = mCurrentSubtitleText;
        
        // 检测是否是自动生成字幕（通常是一个单词一个 Cue）
        boolean isAutoGenerated = false;
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
        } else if (cues.size() == 1) {
            // 单个 Cue 也可能是自动生成字幕的一部分
            Cue cue = cues.get(0);
            if (cue.text != null) {
                String text = cue.text.toString();
                isAutoGenerated = isAutoGeneratedSubtitle(text);
            }
        }
        
        // 合并所有字幕文本，确保包含完整的字幕内容
        StringBuilder fullText = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.text != null) {
                String cueText = cue.text.toString();
                // 替换换行符为空格，以便正确处理多行字幕
                cueText = cueText.replace("\n", " ").replace("\r", " ");
                
                if (fullText.length() > 0) {
                    fullText.append(" ");
                }
                fullText.append(cueText);
            }
        }
        
        mCurrentSubtitleText = fullText.toString();
        Log.d(TAG, "设置字幕文本: " + mCurrentSubtitleText + (isAutoGenerated ? " (自动生成字幕)" : ""));
        
        // 检测字幕文本是否有变化
        if (!oldSubtitleText.equals(mCurrentSubtitleText)) {
            // 检查是否在跳转保护期内
            boolean isProtected = isSeekProtectionActive();
            
            // 记录字幕变化时间，但仅在非保护期内
            if (!isProtected) {
                PlaybackView view = mPlaybackPresenter.getView();
                if (view != null) {
                    mLastSubtitleText = mCurrentSubtitleText;
                    mLastSubtitleChangeTime = view.getPositionMs();
                    Log.d(TAG, "字幕变化，记录时间点: " + mLastSubtitleChangeTime + "ms, 文本: " + mLastSubtitleText);
                }
            } else {
                Log.d(TAG, "字幕变化，但处于跳转保护期内，不更新时间点。当前保护到: " + 
                      (mSeekProtectionStartTime + SEEK_PROTECTION_DURATION) + "ms");
            }
        }
        
        // 如果在选词模式，更新单词列表
        if (mIsWordSelectionMode) {
            // 检测字幕是否有明显变化
            boolean hasSignificantChange = !oldSubtitleText.equals(mCurrentSubtitleText) && 
                    (oldSubtitleText.length() < 3 || mCurrentSubtitleText.length() < 3 || 
                     !oldSubtitleText.substring(0, Math.min(3, oldSubtitleText.length())).equals(
                          mCurrentSubtitleText.substring(0, Math.min(3, mCurrentSubtitleText.length()))));
                     
            // 检查是否是自动生成字幕且发生了变化
            if (isAutoGenerated && hasSignificantChange) {
                // 自动生成字幕有变化，重置单词索引
                Log.d(TAG, "检测到自动生成字幕更新，重置单词索引");
                splitSubtitleIntoWords();
                mCurrentWordIndex = 0; // 强制重置索引到第一个单词
            } else {
                // 正常分词
                splitSubtitleIntoWords();
            }
            
            // 确保有效的单词索引
            if (mWords.length > 0 && mCurrentWordIndex >= mWords.length) {
                mCurrentWordIndex = 0;
            }
            
            // 高亮当前单词
            highlightCurrentWord();
        }
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
        return (c >= '\u4E00' && c <= '\u9FFF') || // 中文
               (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
               (c >= '\uAC00' && c <= '\uD7A3');   // 韩文
    }
    
    /**
     * 检查是否有字幕文本
     */
    public boolean hasSubtitleText() {
        return mCurrentSubtitleText != null && !mCurrentSubtitleText.isEmpty();
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
        
        // 检测文本是否包含CJK字符（中文、日文、韩文）
        boolean containsCJK = false;
        for (int i = 0; i < mCurrentSubtitleText.length(); i++) {
            char c = mCurrentSubtitleText.charAt(i);
            if (isCJKChar(c)) {
                containsCJK = true;
                break;
            }
        }
        
        // 检测是否是自动生成字幕
        boolean isAutoGenerated = isAutoGeneratedSubtitle(mCurrentSubtitleText);
        
        // 使用统一的分词方法
        List<String> wordList = tokenizeText(mCurrentSubtitleText, containsCJK, isAutoGenerated);
        
        // 创建一个数组存储每个单词在原始字幕中的位置索引
        List<Integer> positionsList = new ArrayList<>();
        String tempText = mCurrentSubtitleText.replace("\n", " ").replace("\r", " ");
        int lastPosition = 0;
        
        // 为每个单词找到其在原始文本中的位置
        for (String word : wordList) {
            // 从上一个位置开始查找，避免找到重复单词中的第一个
            int position = tempText.indexOf(word, lastPosition);
            if (position >= 0) {
                positionsList.add(position);
                // 更新最后查找位置，确保下一次查找从当前单词之后开始
                lastPosition = position + word.length();
            } else {
                // 如果找不到精确匹配，可能是因为清理了标点符号等
                // 添加一个默认位置（-1表示未找到）
                positionsList.add(-1);
            }
        }
        
        mWords = wordList.toArray(new String[0]);
        // 将位置列表转换为数组
        mWordPositions = new int[positionsList.size()];
        for (int i = 0; i < positionsList.size(); i++) {
            mWordPositions[i] = positionsList.get(i);
        }
        
        mCurrentWordIndex = 0;
        
        // 记录最终的单词数量
        Log.d(TAG, "分词结果 - 总单词数: " + mWords.length);
        for (int i = 0; i < mWords.length; i++) {
            Log.d(TAG, "单词[" + i + "]: " + mWords[i] + ", 位置: " + mWordPositions[i]);
        }
        
        // 获取实际显示的单词数量，并与分词结果比较
        int actualWordCount = getActualSubtitleWordCount();
        if (actualWordCount > 0 && actualWordCount != mWords.length) {
            Log.d(TAG, "检测到单词数量不匹配: 分词结果=" + mWords.length + ", 实际显示=" + actualWordCount);
            
            // 如果实际显示的单词数量与分词结果不一致，尝试调整
            if (isAutoGeneratedSubtitle(mCurrentSubtitleText)) {
                // 对于自动生成字幕，重新使用更精确的分词方法
                Log.d(TAG, "尝试为自动生成字幕重新分词");
                
                List<String> adjustedWordList = new ArrayList<>();
                List<Integer> adjustedPositionsList = new ArrayList<>();
                
                try {
                    // 通过反射获取字幕画布的实际文本内容
                    Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
                    paintersField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
                    
                    if (painters != null && !painters.isEmpty()) {
                        for (Object painter : painters) {
                            Field textField = painter.getClass().getDeclaredField("text");
                            textField.setAccessible(true);
                            CharSequence text = (CharSequence) textField.get(painter);
                            
                            if (text != null && text.length() > 0) {
                                String textStr = text.toString();
                                
                                // 检查是否是CJK文本
                                boolean isTextCJK = containsOnlyCJK(textStr);
                                boolean isTextAuto = isAutoGeneratedSubtitle(textStr);
                                
                                // 使用统一的分词方法
                                List<String> painterWords = tokenizeText(textStr, isTextCJK, isTextAuto);
                                
                                // 计算每个单词在原始文本中的位置
                                String tempPainterText = textStr.replace("\n", " ").replace("\r", " ");
                                int lastPainterPosition = 0;
                                
                                for (String word : painterWords) {
                                    int position = tempPainterText.indexOf(word, lastPainterPosition);
                                    if (position >= 0) {
                                        adjustedPositionsList.add(position);
                                        lastPainterPosition = position + word.length();
                                    } else {
                                        adjustedPositionsList.add(-1);
                                    }
                                    adjustedWordList.add(word);
                                }
                            }
                        }
                    }
                    
                    // 如果调整后的单词列表不为空，使用它
                    if (!adjustedWordList.isEmpty()) {
                        mWords = adjustedWordList.toArray(new String[0]);
                        
                        // 将调整后的位置列表转换为数组
                        mWordPositions = new int[adjustedPositionsList.size()];
                        for (int i = 0; i < adjustedPositionsList.size(); i++) {
                            mWordPositions[i] = adjustedPositionsList.get(i);
                        }
                        
                        Log.d(TAG, "调整后的单词数量: " + mWords.length);
                        for (int i = 0; i < mWords.length; i++) {
                            Log.d(TAG, "调整后单词[" + i + "]: " + mWords[i] + ", 位置: " + mWordPositions[i]);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "调整单词数量失败: " + e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * 判断是否是自动生成字幕
     * @param text 字幕文本
     * @return 是否是自动生成字幕
     */
    private boolean isAutoGeneratedSubtitle(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // 自动生成字幕通常具有以下特征:
        // 1. 句子较短（单词数量少）
        // 2. 单词之间的空格规律性强
        // 3. 可能没有标点符号
        
        // 计算单词数量
        String[] words = text.split("\\s+");
        
        // 检查是否有太多单词（自动生成字幕通常只有几个单词）
        if (words.length > 10) {
            return false;
        }
        
        // 检查文本长度（自动生成字幕通常很短）
        if (text.length() > 100) {
            return false;
        }
        
        // 检查是否有句号或问号（结束标点）
        // 自动生成字幕通常没有完整的句子结构
        boolean hasEndPunctuation = text.contains(".") || text.contains("?") || text.contains("!");
        
        // 检查首字母是否大写且末尾有句号（完整句子特征）
        boolean isCompleteFormattedSentence = !text.isEmpty() && 
                                            Character.isUpperCase(text.charAt(0)) && 
                                            (text.endsWith(".") || text.endsWith("?") || text.endsWith("!"));
        
        // 综合判断
        // 是自动生成字幕的可能性: 短文本 + 少量单词 + 缺少句子结构
        return words.length <= 10 && 
               text.length() <= 100 && 
               (!hasEndPunctuation || !isCompleteFormattedSentence);
    }
    
    /**
     * 选择下一个单词
     */
    private void selectNextWord() {
        if (mWords.length == 0) {
            return;
        }
        
        mCurrentWordIndex = (mCurrentWordIndex + 1) % mWords.length;
        highlightCurrentWord();
    }
    
    /**
     * 选择上一个单词
     */
    private void selectPreviousWord() {
        if (mWords.length == 0) {
            return;
        }
        
        mCurrentWordIndex = (mCurrentWordIndex - 1 + mWords.length) % mWords.length;
        highlightCurrentWord();
    }
    
    /**
     * 高亮显示当前单词
     */
    private void highlightCurrentWord() {
        if (mWords.length == 0 || mCurrentWordIndex >= mWords.length) {
            return;
        }
        
        String currentWord = mWords[mCurrentWordIndex];
        int currentPosition = mWordPositions.length > mCurrentWordIndex ? mWordPositions[mCurrentWordIndex] : -1;
        
        // 在字幕中高亮显示当前单词，并传递位置信息
        highlightWordInSubtitle(currentWord, currentPosition);
    }
    
    /**
     * 在字幕中高亮显示指定单词
     * @param word 要高亮的单词
     * @param wordPosition 单词在字幕中的位置，-1表示未知位置
     */
    private void highlightWordInSubtitle(String word, int wordPosition) {
        if (mSubtitleView == null || word == null || word.isEmpty()) {
            return;
        }
        
        try {
            // 通过反射获取 SubtitleView 的内部实现
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                // 与分词方法一致的清理逻辑
                String cleanWord = word.replaceAll("[,.!?;:\"\\(\\)\\[\\]\\{\\}]", "").trim();
                
                Log.d(TAG, "设置高亮单词: " + cleanWord + ", 位置: " + wordPosition + ", 字幕画笔数量: " + painters.size());
                
                // 获取最新的字幕内容
                List<Cue> currentCues = mSubtitleView.getCues();
                if (currentCues == null || currentCues.isEmpty()) {
                    Log.d(TAG, "无法高亮：字幕内容为空");
                    return;
                }
                
                // 为所有画笔设置高亮单词
                for (Object painter : painters) {
                    // 设置要高亮的单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    highlightWordField.set(painter, cleanWord);
                    
                    // 设置单词位置信息
                    try {
                        Field highlightWordPositionField = painter.getClass().getDeclaredField("highlightWordPosition");
                        highlightWordPositionField.setAccessible(true);
                        highlightWordPositionField.set(painter, wordPosition);
                    } catch (NoSuchFieldException e) {
                        // SubtitlePainter可能还没有highlightWordPosition字段
                        Log.w(TAG, "SubtitlePainter缺少highlightWordPosition字段，无法设置单词位置");
                    }
                    
                    // 启用单词高亮
                    Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                    enableWordHighlightField.setAccessible(true);
                    enableWordHighlightField.set(null, true);
                    
                    // 检查画笔的字幕文本，确保单词存在于文本中
                    try {
                        Field cueTextField = painter.getClass().getDeclaredField("cueText");
                        cueTextField.setAccessible(true);
                        CharSequence text = (CharSequence) cueTextField.get(painter);
                        
                        if (text != null) {
                            String textStr = text.toString();
                            
                            // 处理可能的换行符
                            textStr = textStr.replace("\n", " ").replace("\r", " ");
                            
                            if (!textStr.contains(cleanWord)) {
                                // 如果文本中不包含精确的单词，尝试使用更宽松的匹配
                                Log.d(TAG, "未在画笔文本中找到精确单词，尝试宽松匹配: '" + cleanWord + "' in '" + textStr + "'");
                                
                                // 检查是否是自动生成字幕的一部分
                                boolean isAuto = isAutoGeneratedSubtitle(textStr);
                                boolean isCJK = containsOnlyCJK(textStr);
                                
                                // 使用与分词相同的逻辑处理文本
                                List<String> textWords = tokenizeText(textStr, isCJK, isAuto);
                                
                                boolean foundMatch = false;
                                for (String textWord : textWords) {
                                    if (textWord.equalsIgnoreCase(cleanWord)) {
                                        // 找到不区分大小写的匹配
                                        Log.d(TAG, "找到不区分大小写的匹配: '" + textWord + "'");
                                        highlightWordField.set(painter, textWord);
                                        foundMatch = true;
                                        break;
                                    }
                                }
                                
                                if (!foundMatch) {
                                    // 如果还是找不到，尝试使用正则表达式匹配相似单词
                                    // 改进：更准确的单词边界和忽略相邻标点的正则匹配
                                    Pattern pattern = Pattern.compile("\\b" + cleanWord + "\\b", Pattern.CASE_INSENSITIVE);
                                    Matcher matcher = pattern.matcher(textStr);
                                    if (matcher.find()) {
                                        String matchedWord = matcher.group();
                                        Log.d(TAG, "找到相似单词匹配: " + matchedWord);
                                        // 更新高亮单词为实际匹配到的单词
                                        highlightWordField.set(painter, matchedWord);
                                    } else {
                                        // 尝试去除文本中单词周围的标点后再次匹配
                                        String cleanText = textStr.replaceAll("[,.!?;:\"\\(\\)\\[\\]\\{\\}]", " ");
                                        matcher = pattern.matcher(cleanText);
                                        if (matcher.find()) {
                                            String matchedWord = matcher.group();
                                            Log.d(TAG, "在清理标点后找到单词匹配: " + matchedWord);
                                            highlightWordField.set(painter, matchedWord);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "无法检查画笔文本: " + e.getMessage());
                    }
                }
                
                // 强制重绘字幕
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
        // 重置重试计数器
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
        
        // 获取mWords数组中的当前单词
        String currentWordFromArray = mWords[mCurrentWordIndex];
        
        // 尝试从字幕高亮获取实际单词
        String actualHighlightedWord = getActualHighlightedWord();
        
        // 使用实际高亮单词（如果能获取到），否则使用数组中的单词
        String wordToTranslate = (actualHighlightedWord != null && !actualHighlightedWord.isEmpty()) ? 
                                actualHighlightedWord : currentWordFromArray;
        
        // 记录日志，便于调试
        if (!wordToTranslate.equals(currentWordFromArray)) {
            Log.d(TAG, "使用实际高亮单词: " + wordToTranslate + " 替代数组中单词: " + currentWordFromArray);
        }
        
        // 获取当前单词的位置信息
        int currentWordPosition = (mCurrentWordIndex < mWordPositions.length) ? mWordPositions[mCurrentWordIndex] : -1;
        
        // 显示覆盖层和加载提示
        if (mRetryCount == 0) {
            showDefinitionOverlay("正在查询中...\n请稍候");
        }
        
        // 创建一个新线程来执行网络请求，避免阻塞主线程
        final String finalWordToTranslate = wordToTranslate;
        final int finalWordPosition = currentWordPosition;
        final int currentRetryCount = mRetryCount;
        
        new Thread(() -> {
            // 使用Ollama本地服务查询词义，传入重试次数
            String definition = fetchOllamaDefinition(finalWordToTranslate, mCurrentSubtitleText, finalWordPosition, currentRetryCount);
            
            // 记录日志
            Log.d(TAG, "翻译结果长度: " + (definition != null ? definition.length() : "null"));
            
            // 最终结果
            final String finalDefinition = definition;
            
            // 检查结果是否需要重试
            boolean needRetry = (finalDefinition != null && 
                    (finalDefinition.contains("注意：AI没有使用中文回答") || 
                     (finalDefinition.contains("美式英语") && finalDefinition.length() < 200) ||
                     (finalDefinition.contains("美式英语的发音") && finalDefinition.length() < 200))
                    ) && mRetryCount < MAX_RETRY_COUNT;
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    // 检查是否仍然处于单词选择模式
                    if (mTextView != null && mIsWordSelectionMode) {
                        if (needRetry) {
                            // 增加重试计数
                            mRetryCount++;
                            Log.d(TAG, "需要重试，原因: " + 
                                (finalDefinition.contains("注意：AI没有使用中文回答") ? "AI没有使用中文回答" : 
                                 (finalDefinition.contains("美式英语的发音") ? "返回内容仅包含'美式英语的发音'" : 
                                  "返回内容仅包含'美式英语'")));
                            // 延迟时间随重试次数增加，避免过快请求
                            int delayMs = 500 + (mRetryCount * 100);
                            mHandler.postDelayed(this::translateCurrentWordWithRetry, delayMs);
                        } else {
                            // 显示最终结果或达到最大重试次数的结果
                            showDefinitionOverlay(finalDefinition);
                            // 如果达到最大重试次数但仍未获得中文回答，记录日志
                            if (mRetryCount >= MAX_RETRY_COUNT && 
                                (finalDefinition.contains("注意：AI没有使用中文回答") || 
                                 (finalDefinition.contains("美式英语") && finalDefinition.length() < 200) ||
                                 (finalDefinition.contains("美式英语的发音") && finalDefinition.length() < 200))) {
                                Log.w(TAG, "达到最大重试次数" + MAX_RETRY_COUNT + "，但仍未获得有效回答");
                            }
                        }
                    }
                });
            }
        }).start();
    }
    
    /**
     * 获取字幕中实际高亮的单词
     * @return 实际高亮的单词，如果无法获取则返回null
     */
    private String getActualHighlightedWord() {
        if (mSubtitleView == null) {
            return null;
        }
        
        try {
            // 通过反射获取 SubtitleView 的内部实现
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                for (Object painter : painters) {
                    // 获取当前高亮的单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    String highlightedWord = (String) highlightWordField.get(painter);
                    
                    if (highlightedWord != null && !highlightedWord.isEmpty()) {
                        Log.d(TAG, "从字幕获取实际高亮单词: " + highlightedWord);
                        return highlightedWord;
                    }
                }
            }
                } catch (Exception e) {
            Log.e(TAG, "获取实际高亮单词失败: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * 从本地Ollama服务获取单词解释
     * @param word 要查询的单词
     * @param context 单词所在的上下文
     * @param wordPosition 单词在字幕中的位置
     * @param retryCount 当前重试次数
     * @return 单词解释
     */
    private String fetchOllamaDefinition(String word, String context, int wordPosition, int retryCount) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        
        try {
            // 准备查询参数
            String query = word.trim();
            String originalWord = query; // 保存原始单词，用于结果显示
            
            // 添加随机性以增加每次查询的差异
            if (retryCount > 0) {
                // 生成一个随机后缀，仅用于请求，不影响结果显示
                long timestamp = System.currentTimeMillis();
                int randomNum = (int)(timestamp % 1000);
                query = query + " #" + randomNum;
                Log.d(TAG, "添加随机后缀: '" + query + "'");
            }
            
            // 检查单词在字幕中是否重复出现
            boolean isRepeated = false;
            int occurrenceIndex = -1;
            
            if (wordPosition >= 0) {
                // 计算这个单词是第几次出现的
                List<Integer> occurrences = new ArrayList<>();
                for (int i = 0; i < mWords.length; i++) {
                    if (mWords[i].equalsIgnoreCase(originalWord)) { // 使用原始单词匹配
                        occurrences.add(i);
                    }
                }
                
                if (occurrences.size() > 1) {  // 如果单词出现多次
                    isRepeated = true;
                    // 找出当前单词是第几次出现
                    for (int i = 0; i < occurrences.size(); i++) {
                        if (mWordPositions[occurrences.get(i)] == wordPosition) {
                            occurrenceIndex = i + 1;  // 从1开始计数
                            break;
                        }
                    }
                }
            }
            
            // 根据重试次数增加中文回答的强调
            String chineseEmphasis;
            if (retryCount == 0) {
                chineseEmphasis = "请始终使用中文回答，保持简洁明了的解释。";
            } else if (retryCount == 1) {
                chineseEmphasis = "请注意：必须用中文回答！保持简洁明了的解释。";
            } else if (retryCount == 2) {
                chineseEmphasis = "警告：你必须完全用中文回答！不要使用英文或其他语言解释。";
            } else if (retryCount == 3) {
                chineseEmphasis = "严格警告：只能用中文回答，一个英文单词都不要出现在解释中！";
            } else {
                chineseEmphasis = "最后警告：我只接受纯中文回答！不要有任何英文单词出现在解释中（音标除外）！";
            }
            
            String prompt;
            if (isRepeated && occurrenceIndex > 0) {
                // 如果是重复单词，在命令中明确指定位置
                prompt = "请解释一下\"" + originalWord + "\"这个词在这句话\"" + context + 
                        "\"中的用法。这个单词在句子中出现了多次，我查询的是第" + occurrenceIndex + 
                        "次出现的\"" + originalWord + "\"（共出现" + mWords.length + "个单词中的第" + 
                        (mCurrentWordIndex + 1) + "个）。" + chineseEmphasis + "必须在单词后面提供美式英语的音标。严格禁止显示任何思考过程，直接给出干净的解释。";
            } else {
                // 常规命令
                prompt = "请解释一下\"" + originalWord + "\"这个词在这句话\"" + context + 
                        "\"中的用法。" + chineseEmphasis + "必须在单词后面提供美式英语的音标。严格禁止显示任何思考过程，直接给出干净的解释。";
            }
            
            // 保存当前的AI命令，以便在需要时显示
            mCurrentAIPrompt = prompt;
            
            // 记录完整请求信息，方便调试
            if (retryCount > 0) {
                Log.d(TAG, "Ollama查询词: " + query + " (原始单词: " + originalWord + ")");
            } else {
                Log.d(TAG, "Ollama查询词: " + query);
            }
            Log.d(TAG, "Ollama查询上下文: " + context);
            if (isRepeated) {
                Log.d(TAG, "查询重复单词: 第" + occurrenceIndex + "次出现, 位置: " + wordPosition);
            }
            
            // 构建请求JSON
            String requestJson = "{\"model\":\"qwen3:latest\",\"stream\":false,\"prompt\":\"" + 
                                  prompt.replace("\"", "\\\"").replace("\n", "\\n") + 
                                  "\"}";
            
            Log.d(TAG, "Ollama请求JSON: " + requestJson);
            
            // 使用本地Ollama服务
            URL url = new URL("http://192.168.1.113:11434/api/generate");
            
            // 设置连接
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000); // 增加超时时间到15秒
            connection.setReadTimeout(30000); // 增加读取超时时间到30秒
            
            // 设置请求头
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            
            // 启用输出
            connection.setDoOutput(true);
            
            // 写入请求体
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = requestJson.getBytes("utf-8");
                os.write(input, 0, input.length);
                os.flush();
                Log.d(TAG, "已向Ollama发送请求");
            }
            
            // 获取响应状态码
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Ollama响应码: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                String responseStr = result.toString();
                Log.d(TAG, "Ollama响应长度: " + responseStr.length() + " 字节");
                if (responseStr.length() < 1000) {
                    // 如果响应不太长，记录完整内容便于调试
                    Log.d(TAG, "Ollama响应内容: " + responseStr);
                }
                
                // 解析JSON响应
                return parseOllamaResponse(responseStr, query);
            } else {
                // 尝试获取错误流中的信息
                try {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "UTF-8"));
                    String line;
                    StringBuilder errorResult = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        errorResult.append(line);
                    }
                    Log.e(TAG, "Ollama错误响应: " + errorResult.toString());
                    return "Ollama服务请求失败: " + responseCode + ", " + errorResult.toString();
        } catch (Exception e) {
                    Log.e(TAG, "无法读取错误流: " + e.getMessage());
                    return "Ollama服务请求失败: " + responseCode;
                }
            }
        } catch (Exception e) {
            // 记录详细的异常信息
            Log.e(TAG, "调用Ollama异常: " + e.getClass().getName() + ": " + e.getMessage(), e);
            if (e instanceof java.net.ConnectException) {
                return "连接Ollama服务失败: 请检查服务是否启动或IP地址是否正确";
            } else if (e instanceof java.net.SocketTimeoutException) {
                return "连接Ollama服务超时: 请求可能需要更长时间或服务器负载过高";
            } else {
                return "查询失败: " + e.getClass().getSimpleName() + " - " + (e.getMessage() != null ? e.getMessage() : "未知错误");
            }
        } finally {
            // 关闭连接和读取器
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭读取器失败: " + e.getMessage(), e);
                }
            }
            if (connection != null) {
                connection.disconnect();
                Log.d(TAG, "已断开Ollama连接");
            }
        }
    }
    
    /**
     * 解析Ollama服务响应
     * @param jsonResponse JSON响应字符串
     * @param word 查询的单词
     * @return 格式化的解释
     */
    private String parseOllamaResponse(String jsonResponse, String word) {
        try {
            if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
                Log.e(TAG, "Ollama返回了空响应");
                return "【" + capitalizeFirstLetter(word) + "】\n\n获取解释失败：服务器返回空响应";
            }
            
            StringBuilder definition = new StringBuilder();
            
            // 确保使用实际的查询单词，而不是内部数组的单词
            String displayWord = word;
            // 确保首字母大写
            String capitalizedWord = capitalizeFirstLetter(displayWord);
            
            // 添加查询单词标题（标题将在下面处理音标后加入）
            StringBuilder titleBuilder = new StringBuilder();
            titleBuilder.append("【").append(capitalizedWord);
            
            // 用于保存提取到的音标
            String extractedPhonetics = "";
            
            // 从JSON中提取响应文本
            if (jsonResponse.contains("\"response\":")) {
                int responseIndex = jsonResponse.indexOf("\"response\":") + 12;
                int responseEnd = -1;
                
                // 查找响应文本的结束位置，处理可能的转义字符
                for (int i = responseIndex; i < jsonResponse.length(); i++) {
                    if (jsonResponse.charAt(i) == '"' && (i == 0 || jsonResponse.charAt(i-1) != '\\')) {
                        responseEnd = i;
                        break;
                    }
                }
                
                if (responseIndex > 0 && responseEnd > responseIndex) {
                    String response = jsonResponse.substring(responseIndex, responseEnd);
                    // 清理转义字符
                    response = response.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
                    
                    // 解码Unicode转义序列（如\u003e \u003c等）
                    response = decodeUnicodeEscapes(response);
                    
                    // 在早期阶段直接移除所有think>文本
                    response = response.replace("think>", "");
                    response = response.replace("<think", "");
                    response = response.replace("</think", "");
                    response = response.replace("<think>", "");
                    response = response.replace("</think>", "");
                    response = response.replace("think", "");
                    
                    // 专门移除所有尖括号及其中间内容
                    response = response.replaceAll("<[^>]*>", "");
                    
                    // 检测和清理可能的乱码
                    response = cleanupResponse(response);
                    
                    // 多层次过滤思考过程和think标签
                    response = filterThinkingContent(response);
                    // 再次过滤，确保彻底删除所有think标签
                    response = response.replaceAll("(?i)<think[^>]*>.*?</think>", "");
                    response = response.replaceAll("(?i)</?think[^>]*>", "");
                    // 移除可能遗漏的标签
                    response = response.replaceAll("(?i)<[/]?think.*?>", "");
                    // 移除单词"think"后面的内容，如果它看起来像是标签的开始
                    response = response.replaceAll("(?i)think>.*?<", "");
                    // 直接移除残留的"think>"文本
                    response = response.replace("think>", "");
                    // 移除任何包含"think"的标签片段
                    response = response.replaceAll("(?i)\\bthink[^\\s]*>", "");
                    
                    // 更强大的音标提取逻辑
                    boolean foundPhonetics = false;
                    
                    // 1. 首先尝试查找"美音："或"美式发音："等明确标记后面的音标
                    Pattern usPattern = Pattern.compile("(?:美音|【美音】|美式发音|美式音标|美式英语|音标)[:：]\\s*\\[([^\\]]+)\\]");
                    Matcher usMatcher = usPattern.matcher(response);
                    if (usMatcher.find()) {
                        String phonetics = usMatcher.group(1);
                        if (phonetics != null && !phonetics.isEmpty()) {
                            extractedPhonetics = " [" + phonetics + "]";
                            foundPhonetics = true;
                            Log.d(TAG, "从标记中提取到音标: " + extractedPhonetics);
                        }
                    }
                    
                    // 1.1 查找"美音："或"美式发音："等明确标记后面的反斜杠音标格式 /pi:/
                    if (!foundPhonetics) {
                        Pattern usSlashPattern = Pattern.compile("(?:美音|【美音】|美式发音|美式音标|美式英语|音标)[:：]\\s*\\/([^\\/]+)\\/");
                        Matcher usSlashMatcher = usSlashPattern.matcher(response);
                        if (usSlashMatcher.find()) {
                            String phonetics = usSlashMatcher.group(1);
                            if (phonetics != null && !phonetics.isEmpty()) {
                                extractedPhonetics = " [" + phonetics + "]"; // 转换为方括号格式
                                foundPhonetics = true;
                                Log.d(TAG, "从标记中提取到反斜杠格式音标: " + extractedPhonetics);
                            }
                        }
                    }
                    
                    // 2. 如果没找到明确标记的音标，尝试在整个文本中找出可能的音标
                    if (!foundPhonetics) {
                        Pattern phoneticsPattern = Pattern.compile("\\[([^\\]]+)\\]|（([^）]+)）");
                        Matcher phoneticsMatcher = phoneticsPattern.matcher(response);
                        
                        while (phoneticsMatcher.find()) {
                            String phonetics = phoneticsMatcher.group(1) != null ? phoneticsMatcher.group(1) : phoneticsMatcher.group(2);
                            if (phonetics != null && (
                                    phonetics.contains("ə") || 
                                    phonetics.contains("ɪ") || 
                                    phonetics.contains("ʌ") || 
                                    phonetics.contains("ɑ") || 
                                    phonetics.contains("ɔ") || 
                                    phonetics.contains("æ") ||
                                    phonetics.contains("ː") || 
                                    phonetics.contains("ˈ") || 
                                    phonetics.contains("ˌ") ||
                                    phonetics.matches(".*[a-zA-Z].*"))) {
                                // 看起来是音标，保存到extractedPhonetics中
                                extractedPhonetics = " [" + phonetics + "]";
                                foundPhonetics = true;
                                Log.d(TAG, "从文本中提取到音标: " + extractedPhonetics);
                                break;
                            }
                        }
                    }
                    
                    // 2.1 如果没找到方括号音标，尝试查找反斜杠格式的音标 /pi:/
                    if (!foundPhonetics) {
                        Pattern slashPhoneticPattern = Pattern.compile("\\/([^\\/]+)\\/");
                        Matcher slashPhoneticMatcher = slashPhoneticPattern.matcher(response);
                        
                        while (slashPhoneticMatcher.find()) {
                            String phonetics = slashPhoneticMatcher.group(1);
                            if (phonetics != null && (
                                    phonetics.contains("ə") || 
                                    phonetics.contains("ɪ") || 
                                    phonetics.contains("ʌ") || 
                                    phonetics.contains("ɑ") || 
                                    phonetics.contains("ɔ") || 
                                    phonetics.contains("æ") ||
                                    phonetics.contains("ː") || 
                                    phonetics.contains("ˈ") || 
                                    phonetics.contains("ˌ") ||
                                    phonetics.contains("i:") || 
                                    phonetics.contains("e:") || 
                                    phonetics.contains("u:") ||
                                    phonetics.contains("ɒ") || 
                                    phonetics.contains("ɜ") || 
                                    phonetics.matches(".*[a-zA-Z].*"))) {
                                // 看起来是音标，保存到extractedPhonetics中
                                extractedPhonetics = " [" + phonetics + "]"; // 转换为方括号格式
                                foundPhonetics = true;
                                Log.d(TAG, "从文本中提取到反斜杠格式音标: " + extractedPhonetics);
                                break;
                            }
                        }
                    }
                    
                    // 3. 尝试从文本开头的几行查找音标（Ollama常在回答开头提供音标）
                    if (!foundPhonetics) {
                        String[] lines = response.split("\n");
                        for (int i = 0; i < Math.min(5, lines.length); i++) {
                            String line = lines[i].trim();
                            if (line.contains("[") && line.contains("]") && 
                                (line.contains("音标") || line.contains("发音") || line.contains("读音"))) {
                                int start = line.indexOf("[");
                                int end = line.indexOf("]", start);
                                if (start >= 0 && end > start) {
                                    String phonetics = line.substring(start + 1, end);
                                    if (!phonetics.isEmpty()) {
                                        extractedPhonetics = " [" + phonetics + "]";
                                        foundPhonetics = true;
                                        Log.d(TAG, "从开头几行提取到音标: " + extractedPhonetics);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    
                    // 完成标题，音标放在右括号后面
                    titleBuilder.append("】");
                    if (!extractedPhonetics.isEmpty()) {
                        titleBuilder.append(extractedPhonetics);
                    }
                    titleBuilder.append("\n");
                    definition.append(titleBuilder);
                    
                    // 从解释内容中彻底移除所有音标和发音相关内容
                    String cleanResponse = removePhoneticFromText(response);
                    
                    // 额外清理"美式英语"和周围的符号
                    cleanResponse = cleanResponse.replaceAll("[（(\\[【]?美式英语[)）\\]】]?", "");
                    cleanResponse = cleanResponse.replaceAll("[（(\\[【]?美式英语的发音[)）\\]】]?", "");
                    
                    // 如果美式英语后面跟着冒号和内容，只保留冒号后的内容
                    cleanResponse = cleanResponse.replaceAll("美式英语[:：]\\s*", "");
                    cleanResponse = cleanResponse.replaceAll("美式英语的发音[:：]\\s*", "");
                    
                    // 处理可能的空行
                    cleanResponse = cleanResponse.replaceAll("(?m)^\\s*$\\n", "");
                    cleanResponse = cleanResponse.replaceAll("\n{3,}", "\n\n");
                    
                    // 检查是否包含中文，如果不包含，添加提示和发送给AI的命令
                    if (!containsChinese(cleanResponse)) {
                        cleanResponse = "注意：AI没有使用中文回答。\n\n发送给AI的命令：\n" + mCurrentAIPrompt + "\n\n" + cleanResponse;
                    }
                    
                    definition.append(cleanResponse);
                    
                    // 记录成功解析
                    Log.d(TAG, "成功从Ollama响应中提取解释内容");
                    return definition.toString();
                } else {
                    Log.e(TAG, "无法在JSON中找到响应文本的结束位置");
                    definition.append(titleBuilder).append("】\n\n解析响应失败：无法提取响应文本");
                }
            } else if (jsonResponse.contains("\"error\":")) {
                // 处理错误响应
                int errorIndex = jsonResponse.indexOf("\"error\":") + 9;
                int errorEnd = jsonResponse.indexOf("\"", errorIndex);
                
                if (errorIndex > 0 && errorEnd > errorIndex) {
                    String error = jsonResponse.substring(errorIndex, errorEnd);
                    Log.e(TAG, "Ollama返回错误: " + error);
                    definition.append(titleBuilder).append("】\n\nOllama服务错误: ").append(error);
                } else {
                    Log.e(TAG, "JSON中包含error字段，但无法提取错误信息: " + jsonResponse);
                    definition.append(titleBuilder).append("】\n\nOllama服务返回了错误，但无法提取错误信息");
                }
            } else {
                // 如果无法解析JSON，记录并返回适当的错误消息
                Log.e(TAG, "无法从JSON响应中找到response或error字段: " + jsonResponse);
                definition.append(titleBuilder).append("】\n\n解析失败：响应格式不符合预期\n\n");
                
                // 如果JSON很短，直接附加它以便调试
                if (jsonResponse.length() < 500) {
                    definition.append("原始响应: ").append(jsonResponse);
                } else {
                    definition.append("原始响应过长，已省略");
                }
            }
            
            return definition.toString();
        } catch (Exception e) {
            Log.e(TAG, "解析Ollama响应异常: " + e.getClass().getName() + ": " + e.getMessage(), e);
            return "【" + capitalizeFirstLetter(word) + "】\n\n解析响应失败: " + 
                   e.getClass().getSimpleName() + " - " + 
                   (e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }
    
    /**
     * 清理响应文本中的乱码
     * @param text 原始响应文本
     * @return 清理后的文本
     */
    private String cleanupResponse(String text) {
        if (text == null) {
            return "";
        }
        
        // 移除控制字符
        String cleaned = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        
        // 移除不可打印字符
        cleaned = cleaned.replaceAll("[^\\p{Print}\\p{Space}]", "");
        
        // 移除连续的空白字符
        cleaned = cleaned.replaceAll("\\s+", " ");
        
        // 移除文本开头的特殊符号（如果有）
        cleaned = cleaned.replaceAll("^[^\\w\\u4e00-\\u9fa5\\n]+", "");
        
        // 处理一些常见的乱码序列
        cleaned = cleaned.replace("\uFFFD", ""); // 替换Unicode替换字符（常见乱码符号）
        
        return cleaned.trim();
    }
    
    /**
     * 检查文本是否包含中文字符
     * @param text 要检查的文本
     * @return 是否包含中文
     */
    private boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // 使用正则表达式检查是否包含中文字符
        Pattern p = Pattern.compile("[\\u4e00-\\u9fa5]");
        Matcher m = p.matcher(text);
        return m.find();
    }
    
    /**
     * 解码字符串中的Unicode转义序列
     * @param text 包含Unicode转义序列的文本
     * @return 解码后的文本
     */
    private String decodeUnicodeEscapes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        try {
            // 使用正则表达式查找所有Unicode转义序列
            // 例如: \u003c 会被转换为 <
            Pattern pattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher matcher = pattern.matcher(text);
            
            StringBuilder result = new StringBuilder();
            int lastEnd = 0;
            
            while (matcher.find()) {
                // 添加匹配之前的文本
                result.append(text.substring(lastEnd, matcher.start()));
                
                // 解码Unicode序列
                String hexValue = matcher.group(1);
                int charValue = Integer.parseInt(hexValue, 16);
                result.append((char) charValue);
                
                lastEnd = matcher.end();
            }
            
            // 添加剩余文本
            if (lastEnd < text.length()) {
                result.append(text.substring(lastEnd));
            }
            
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "解码Unicode转义序列失败: " + e.getMessage());
            return text; // 如果解码失败，返回原始文本
        }
    }
    
    /**
     * 显示解释覆盖层
     */
    private void showDefinitionOverlay(String text) {
        if (mSelectionOverlay != null && mRootView != null) {
            // 如果覆盖层还没有添加到根视图，先添加
            if (mSelectionOverlay.getParent() == null) {
                mRootView.addView(mSelectionOverlay);
            }
            
            // 设置文本内容
            if (mTextView != null) {
                // 处理文本，应用不同的样式
                try {
                    SpannableStringBuilder builder = new SpannableStringBuilder();
                    
                    // 获取当前单词，用于检查学习状态
                    String currentWord = "";
                    if (mCurrentWordIndex >= 0 && mCurrentWordIndex < mWords.length) {
                        currentWord = mWords[mCurrentWordIndex];
                    } else {
                        // 尝试从高亮信息中获取
                        currentWord = getActualHighlightedWord();
                    }
                    
                    if (currentWord != null && !currentWord.isEmpty()) {
                        // 检查单词是否在学习中
                        boolean isLearning = mVocabularyDatabase.isWordInLearningList(currentWord);
                        
                        // 添加学习状态行
                        String statusLine = isLearning ? 
                            "【学习中】 按OK切换为[取消]" : 
                            "【取消】 按OK切换为[学习中]";
                        
                        // 为状态行添加样式
                        SpannableString statusSpan = new SpannableString(statusLine);
                        
                        // 设置"学习中"/"取消"的颜色和样式
                        if (isLearning) {
                            statusSpan.setSpan(
                                new ForegroundColorSpan(Color.GREEN), 
                                0, 
                                4, // "【学习中】"的长度
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        } else {
                            statusSpan.setSpan(
                                new ForegroundColorSpan(Color.GRAY), 
                                0, 
                                4, // "【取消】"的长度
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        }
                        
                        // 设置提示文字的样式
                        statusSpan.setSpan(
                            new ForegroundColorSpan(Color.LTGRAY), 
                            5, 
                            statusLine.length(), 
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        );
                        
                        statusSpan.setSpan(
                            new StyleSpan(android.graphics.Typeface.ITALIC), 
                            5, 
                            statusLine.length(), 
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        );
                        
                        statusSpan.setSpan(
                            new StyleSpan(android.graphics.Typeface.BOLD), 
                            0, 
                            4, 
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        );
                        
                        // 添加状态行
                        builder.append(statusSpan);
                        builder.append("\n\n");
                    }
                    
                    // 处理原始文本内容
                    String[] lines = text.split("\n");
                    
                    // 减小段落间距
                    int lineSpacing = 5;
                    boolean lastLineWasEmpty = false;
                    
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        
                        // 处理空行，避免连续空行
                        if (line.isEmpty()) {
                            if (!lastLineWasEmpty && i > 0) {
                                builder.append("\n");
                                lastLineWasEmpty = true;
                            }
                            continue;
                        }
                        
                        lastLineWasEmpty = false;
                        
                        // 统一处理所有标题【标题】样式
                        if (line.startsWith("【") && line.contains("】")) {
                            // 提取标题部分和右括号后可能的音标部分
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
                                // 检查是否包含音标
                                if (afterTitle.contains("[") && afterTitle.contains("]")) {
                                    SpannableString phoneticsSpan = new SpannableString(afterTitle);
                                    phoneticsSpan.setSpan(new ForegroundColorSpan(Color.CYAN), 0, afterTitle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    phoneticsSpan.setSpan(new RelativeSizeSpan(0.9f), 0, afterTitle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    builder.append(phoneticsSpan);
                                } else {
                                    // 其他非音标内容使用普通样式
                                    SpannableString afterTitleSpan = new SpannableString(afterTitle);
                                    builder.append(afterTitleSpan);
                                }
                            }
                        }
                        // 单独处理音标行
                        else if ((line.contains("[") && line.contains("]") && 
                                (line.contains("音标") || line.contains("美") || line.contains("英"))) ||
                                line.matches(".*(?:美式发音|美音|音标)[:：].*\\[.*\\].*")) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.CYAN), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理段落标题
                        else if (line.contains("：") && line.length() < 20) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.rgb(255, 165, 0)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            ss.setSpan(new RelativeSizeSpan(1.1f), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理警告/提示信息
                        else if (line.startsWith("注意：")) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.rgb(255, 100, 100)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            ss.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理列表项
                        else if (line.startsWith("•")) {
                            SpannableString ss = new SpannableString("  " + line); // 添加缩进
                            ss.setSpan(new ForegroundColorSpan(Color.WHITE), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            // 为项目符号添加特殊颜色
                            ss.setSpan(new ForegroundColorSpan(Color.rgb(120, 230, 120)), 2, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理例句序号
                        else if (line.matches("^\\d+\\..*")) {
                            // 查找序号的结束位置
                            int dotIndex = line.indexOf('.');
                            if (dotIndex > 0) {
                                // 为序号部分创建样式
                                SpannableString numberPart = new SpannableString(line.substring(0, dotIndex+1) + " ");
                                numberPart.setSpan(new ForegroundColorSpan(Color.rgb(180, 180, 255)), 0, numberPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                numberPart.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, numberPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                
                                // 为例句内容创建样式
                                SpannableString examplePart = new SpannableString(line.substring(dotIndex+1).trim());
                                examplePart.setSpan(new ForegroundColorSpan(Color.LTGRAY), 0, examplePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                examplePart.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, examplePart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                
                                // 合并序号和例句
                                builder.append(numberPart);
                                builder.append(examplePart);
                            } else {
                                // 如果无法分离序号，使用默认样式
                                SpannableString ss = new SpannableString(line);
                                ss.setSpan(new ForegroundColorSpan(Color.LTGRAY), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                ss.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                builder.append(ss);
                            }
                        }
                        // 处理普通文本段落
                        else {
                            // 为普通文本添加适当的缩进和颜色
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.rgb(220, 220, 220)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        
                        // 除了最后一行，每行后面添加换行符
                        if (i < lines.length - 1) {
                            builder.append("\n");
                            
                            // 如果当前行是标题，不添加额外的空行，直接紧跟内容
                            if (!(line.startsWith("【") && line.contains("】"))) {
                                // 只有非标题行才添加额外空行
                                // 如果当前行是段落标题或特殊行，添加额外的空行
                                if (line.contains("：") && line.length() < 20) {
                                    builder.append("\n");
                                }
                            }
                        }
                    }
                    
                    mTextView.setText(builder);
                    
                    // 设置行间距
                    mTextView.setLineSpacing(lineSpacing, 1.1f);
                    
                    // 手动触发布局更新，使容器适应内容大小
                    mTextView.post(() -> {
                        // 确保布局已经计算完成
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
                        
                        // 限制最大宽度(屏幕宽度的85%)和最小宽度(屏幕宽度的55%)
                        int maxWidth = (int)(screenWidth * 0.85f);
                        int minWidth = (int)(screenWidth * 0.55f);
                        
                        // 限制最大高度(屏幕高度的75%)和最小高度(至少3行)
                        int maxHeight = (int)(screenHeight * 0.75f);
                        int minHeight = (int)(lineHeight * 3) + mTextView.getPaddingTop() + mTextView.getPaddingBottom();
                        
                        // 根据内容计算理想宽度
                        int idealWidth = Math.max(minWidth, Math.min(contentWidth, maxWidth));
                        
                        // 根据内容计算理想高度
                        int idealHeight;
                        if (lineCount <= 5) {
                            // 如果行数很少，直接使用实际内容高度
                            idealHeight = contentHeight;
                        } else if (lineCount <= 15) {
                            // 中等行数，显示所有内容但不超过最大高度
                            idealHeight = Math.min(contentHeight, maxHeight);
                        } else {
                            // 大量内容，显示一个固定高度，允许滚动
                            idealHeight = Math.min((int)(lineHeight * 15) + mTextView.getPaddingTop() + mTextView.getPaddingBottom(), maxHeight);
                        }
                        
                        // 确保高度不小于最小高度
                        idealHeight = Math.max(minHeight, idealHeight);
                        
                        // 获取当前参数
                        ViewGroup.LayoutParams params = mSelectionOverlay.getLayoutParams();
                        if (params instanceof FrameLayout.LayoutParams) {
                            // 更新宽度和高度
                            params.width = idealWidth;
                            params.height = idealHeight;
                            
                            // 应用更新的布局参数
                            mSelectionOverlay.setLayoutParams(params);
                            
                            Log.d(TAG, "动态调整窗口大小: 宽度=" + idealWidth + ", 高度=" + idealHeight + 
                                  ", 行数=" + lineCount + ", 内容实际宽度=" + contentWidth + 
                                  ", 内容实际高度=" + contentHeight);
                        }
                        
                        // 重置滚动位置
                        mScrollPosition = 0;
                        mTextView.scrollTo(0, 0);
                    });
                    
                } catch (Exception e) {
                    // 如果样式应用失败，退回到普通文本
                    Log.w(TAG, "应用文本样式失败: " + e.getMessage());
                    mTextView.setText(text);
                    
                    // 即使失败也尝试调整大小
                    mTextView.post(() -> {
                        try {
                            // 设置默认大小
                            ViewGroup.LayoutParams params = mSelectionOverlay.getLayoutParams();
                            if (params != null) {
                                int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
                                params.width = (int)(screenWidth * 0.7f);
                                mSelectionOverlay.setLayoutParams(params);
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "调整默认大小失败: " + ex.getMessage());
                        }
                    });
                }
            }
            
            mSelectionOverlay.setVisibility(View.VISIBLE);
            mIsShowingDefinition = true;
            
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
     * 隐藏解释覆盖层
     */
    private void hideDefinitionOverlay() {
        if (mSelectionOverlay != null) {
        mSelectionOverlay.setVisibility(View.GONE);
            mIsShowingDefinition = false;
        }
    }
    
    /**
     * 隐藏选词覆盖层
     */
    private void hideSelectionOverlay() {
        if (mSelectionOverlay != null) {
            mSelectionOverlay.setVisibility(View.GONE);
        }
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
            // 通过反射获取 SubtitleView 的内部实现
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                // 只禁用选词高亮，不影响学习中单词的高亮
                for (Object painter : painters) {
                    try {
                        // 清除高亮单词
                        Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                        highlightWordField.setAccessible(true);
                        highlightWordField.set(painter, null);
                        
                        // 禁用选词高亮，但保持学习中单词高亮功能
                        Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                        enableWordHighlightField.setAccessible(true);
                        enableWordHighlightField.set(null, false);
                        
                        // 确保学习中单词高亮功能保持启用
                        Field enableLearningWordHighlightField = painter.getClass().getDeclaredField("ENABLE_LEARNING_WORD_HIGHLIGHT");
                        enableLearningWordHighlightField.setAccessible(true);
                        enableLearningWordHighlightField.set(null, true);
                        
                        // 不要调用resetHighlightRelatedFields，因为它会重置所有高亮相关的字段
                        // resetHighlightRelatedFields(painter);
                    } catch (Exception e) {
                        Log.e(TAG, "清除单个画笔高亮失败: " + e.getMessage());
                    }
                }
                
                // 强制重绘字幕
                mSubtitleView.invalidate();
                
                Log.d(TAG, "已清除选词高亮，保持学习中单词高亮");
            }
        } catch (Exception e) {
            Log.e(TAG, "清除字幕高亮失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 重置与高亮相关的所有可能字段
     */
    private void resetHighlightRelatedFields(Object painter) {
        try {
            // 查找并重置可能与高亮相关的所有字段
            Field[] fields = painter.getClass().getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                if (fieldName.contains("highlight") || fieldName.contains("color") || fieldName.contains("span")) {
                    field.setAccessible(true);
                    // 对基本类型，设置为默认值
                    if (field.getType() == boolean.class) {
                        field.setBoolean(painter, false);
                    } else if (field.getType() == int.class && fieldName.contains("color")) {
                        // 颜色相关字段重置为黑色
                        field.setInt(painter, Color.BLACK);
                    } else if (!field.getType().isPrimitive() && field.get(painter) != null) {
                        // 非基本类型且非null的字段尝试设为null
                        // 某些静态final字段无法修改，会抛出异常，忽略这些异常
                        try {
                            field.set(painter, null);
                        } catch (Exception e) {
                            // 忽略无法修改的字段
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，确保清理过程不中断
            Log.d(TAG, "重置高亮相关字段时出现非关键错误: " + e.getMessage());
        }
    }
    
    /**
     * 滚动解释窗口
     * @param offset 滚动偏移量，正值向下滚动，负值向上滚动
     */
    private void scrollDefinition(int offset) {
        if (mTextView != null) {
            // 更新滚动位置
            mScrollPosition += offset;
            
            // 确保不滚动超出范围
            if (mScrollPosition < 0) {
                mScrollPosition = 0;
            }
            
            // 计算文本高度
            int textHeight = mTextView.getLineCount() * mTextView.getLineHeight();
            int viewHeight = mTextView.getHeight() - mTextView.getPaddingTop() - mTextView.getPaddingBottom();
            
            // 确保不滚动超出底部
            int maxScroll = Math.max(0, textHeight - viewHeight);
            if (mScrollPosition > maxScroll) {
                mScrollPosition = maxScroll;
            }
            
            // 应用滚动
            mTextView.scrollTo(0, mScrollPosition);
            
            // 确保行距设置不变
            mTextView.setLineSpacing(0, 0.9f);
        }
    }
    
    /**
     * 获取字幕画布上实际显示的单词数量
     * 用于验证选词模式下的单词数量是否与实际显示一致
     * @return 实际显示的单词数量
     */
    private int getActualSubtitleWordCount() {
        if (mSubtitleView == null) {
            return 0;
        }
        
        try {
            // 通过反射获取 SubtitleView 的内部实现
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters == null || painters.isEmpty()) {
                return 0;
            }
            
            int totalWords = 0;
            
            // 遍历所有画笔，获取文本并计算单词数量
            for (Object painter : painters) {
                try {
                    // 获取画笔的文本内容
                    Field textField = painter.getClass().getDeclaredField("text");
                    textField.setAccessible(true);
                    CharSequence text = (CharSequence) textField.get(painter);
                    
                    if (text != null && text.length() > 0) {
                        String textStr = text.toString();
                        
                        // 检查是否包含 CJK 字符
                        boolean containsCJK = false;
                        for (int i = 0; i < textStr.length(); i++) {
                            if (isCJKChar(textStr.charAt(i))) {
                                containsCJK = true;
                                break;
                            }
                        }
                        
                        boolean isAuto = isAutoGeneratedSubtitle(textStr);
                        
                        // 使用统一的分词方法
                        List<String> words = tokenizeText(textStr, containsCJK, isAuto);
                        totalWords += words.size();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取画笔文本失败: " + e.getMessage());
                }
            }
            
            Log.d(TAG, "字幕画布实际单词数量: " + totalWords);
            return totalWords;
        } catch (Exception e) {
            Log.e(TAG, "获取字幕画布单词数量失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 统一的分词方法，确保所有地方使用相同的分词算法
     * @param text 要分词的文本
     * @param isCJK 是否是CJK文字
     * @param isAutoGenerated 是否是自动生成字幕
     * @return 分词结果
     */
    private List<String> tokenizeText(String text, boolean isCJK, boolean isAutoGenerated) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 处理换行符
        String processedText = text.replace("\n", " ").replace("\r", " ");
        List<String> wordList = new ArrayList<>();
        
        if (isCJK) {
            // CJK文字处理：逐字分词
            StringBuilder currentWord = new StringBuilder();
            
            for (int i = 0; i < processedText.length(); i++) {
                char c = processedText.charAt(i);
                
                // 如果是CJK字符，则作为单独的词
                if (isCJKChar(c)) {
                    // 如果之前已有非CJK单词在构建中，先添加它
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                        }
                        currentWord = new StringBuilder();
                    }
                    
                    // 添加CJK字符作为单独的词
                    wordList.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    // 处理空白字符
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                        }
                        currentWord = new StringBuilder();
                    }
                } else if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\"' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}') {
                    // 处理标点符号（保留撇号）
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                        }
                        currentWord = new StringBuilder();
                    }
                } else {
                    // 累积非CJK字符
                    currentWord.append(c);
                }
            }
            
            // 添加最后一个单词（如果有）
            if (currentWord.length() > 0) {
                String word = currentWord.toString().trim();
                if (!word.isEmpty()) {
                    wordList.add(word);
                }
            }
        } else if (isAutoGenerated) {
            // 自动生成字幕处理：使用更精确的分词方式
            // 改进：更加精确地处理单词和标点的分割
            Pattern wordPattern = Pattern.compile("\\b[\\w']+\\b|[,.!?;:\"\\(\\)\\[\\]\\{\\}]");
            Matcher matcher = wordPattern.matcher(processedText);
            
            while (matcher.find()) {
                String word = matcher.group();
                if (!word.isEmpty()) {
                    // 过滤掉单独的标点符号
                    if (word.length() == 1 && ",.!?;:\"()[]{}".contains(word)) {
                        continue;
                    }
                    wordList.add(word);
                }
            }
            
            // 如果正则匹配没有找到任何单词，退回到简单的空格分割
            if (wordList.isEmpty()) {
                String[] words = processedText.split("\\s+");
                for (String word : words) {
                    // 删除首尾的标点符号，保留中间的标点（如撇号）
                    String cleanWord = word.replaceAll("^[,.!?;:\"\\(\\)\\[\\]\\{\\}]+|[,.!?;:\"\\(\\)\\[\\]\\{\\}]+$", "").trim();
                    if (!cleanWord.isEmpty()) {
                        wordList.add(cleanWord);
                    }
                }
            }
        } else {
            // 普通文本分词：按空格分割，保留撇号，去除其他标点
            // 改进：先按空格分割，然后处理每个部分的标点符号
            String[] segments = processedText.split("\\s+");
            for (String segment : segments) {
                // 如果片段为空，跳过处理
                if (segment.isEmpty()) {
                    continue;
                }
                
                StringBuilder wordBuilder = new StringBuilder();
                boolean hasNonPunctuation = false;
                
                for (int i = 0; i < segment.length(); i++) {
                    char c = segment.charAt(i);
                    
                    // 检查是否是需要处理的标点符号
                    boolean isPunctuation = ",.!?;:\"()[]{}".indexOf(c) >= 0;
                    
                    if (isPunctuation) {
                        // 如果已经有单词在构建中，先添加它
                        if (wordBuilder.length() > 0) {
                            String word = wordBuilder.toString().trim();
                            if (!word.isEmpty()) {
                                wordList.add(word);
                                hasNonPunctuation = false;
                            }
                            wordBuilder = new StringBuilder();
                        }
                        // 忽略标点符号
                    } else {
                        // 累积字符
                        wordBuilder.append(c);
                        hasNonPunctuation = true;
                    }
                }
                
                // 添加最后一个单词（如果有）
                if (wordBuilder.length() > 0) {
                    String word = wordBuilder.toString().trim();
                    if (!word.isEmpty()) {
                        wordList.add(word);
                    }
                }
            }
        }
        
        return wordList;
    }
    
    /**
     * 按页滚动解释窗口
     * @param direction 滚动方向，1表示向下翻页，-1表示向上翻页
     */
    private void scrollDefinitionByPage(int direction) {
        if (mTextView != null) {
            // 计算实际行高
            float actualLineHeight = mTextView.getLineHeight();
            
            // 计算一页的高度（视图高度减去内边距）
            int viewHeight = mTextView.getHeight() - mTextView.getPaddingTop() - mTextView.getPaddingBottom();
            
            // 计算文本总行数
            int totalLines = mTextView.getLineCount();
            
            // 计算当前可见的第一行和最后一行
            int firstVisibleLine = 0;
            int lastVisibleLine = 0;
            
            try {
                // 获取TextView的Layout对象
                android.text.Layout layout = mTextView.getLayout();
                if (layout != null) {
                    // 计算当前滚动位置对应的行号
                    firstVisibleLine = layout.getLineForVertical(mScrollPosition);
                    lastVisibleLine = layout.getLineForVertical(mScrollPosition + viewHeight);
                    
                    // 确保最后一行完全可见
                    if (mScrollPosition + viewHeight < layout.getLineBottom(lastVisibleLine)) {
                        lastVisibleLine--;
                    }
                    
                    Log.d(TAG, "当前可见行: " + firstVisibleLine + " - " + lastVisibleLine);
                }
            } catch (Exception e) {
                Log.e(TAG, "计算可见行出错: " + e.getMessage());
            }
            
            // 根据滚动方向计算目标行
            int targetLine;
            if (direction > 0) {
                // 向下滚动，目标是当前最后可见行之后的一页
                targetLine = Math.min(lastVisibleLine + 1, totalLines - 1);
                
                // 计算要显示的行数，保留一点重叠以提供连续性
                int linesToShow = Math.max(1, (int)(viewHeight / actualLineHeight * 0.8));
                targetLine = Math.min(targetLine + linesToShow, totalLines - 1);
                
                // 滚动到目标行的顶部
                try {
                    if (mTextView.getLayout() != null) {
                        mScrollPosition = mTextView.getLayout().getLineTop(targetLine);
                        
                        // 确保最后一行完全可见
                        if (targetLine == totalLines - 1) {
                            int lineBottom = mTextView.getLayout().getLineBottom(targetLine);
                            if (mScrollPosition + viewHeight < lineBottom) {
                                // 调整滚动位置确保最后一行完全可见
                                mScrollPosition = lineBottom - viewHeight;
                                
                                // 添加额外边距确保完整显示最后一行
                                mScrollPosition += mTextView.getPaddingBottom();
                            }
                        }
                    }
                } catch (Exception e) {
                    // 回退到传统计算
                    mScrollPosition += viewHeight;
                }
            } else {
                // 向上滚动，目标是当前第一可见行之前的一页
                targetLine = Math.max(firstVisibleLine - 1, 0);
                
                // 计算要显示的行数，保留一点重叠以提供连续性
                int linesToShow = Math.max(1, (int)(viewHeight / actualLineHeight * 0.8));
                targetLine = Math.max(targetLine - linesToShow, 0);
                
                // 滚动到目标行的顶部
                try {
                    if (mTextView.getLayout() != null) {
                        mScrollPosition = mTextView.getLayout().getLineTop(targetLine);
                    }
                } catch (Exception e) {
                    // 回退到传统计算
                    mScrollPosition -= viewHeight;
                }
            }
            
            // 确保不滚动超出范围
            if (mScrollPosition < 0) {
                mScrollPosition = 0;
            }
            
            // 计算文本总高度和最大滚动位置
            int textHeight = totalLines * (int)actualLineHeight;
            // 修改：为底部添加额外的内边距，确保最后一行完整显示
            int extraBottomPadding = (int)(actualLineHeight * 0.3); // 添加30%行高的额外空间
            int maxScroll = Math.max(0, textHeight - viewHeight + extraBottomPadding);
            
            // 确保不滚动超出底部
            if (mScrollPosition > maxScroll) {
                mScrollPosition = maxScroll;
            }
            
            // 应用滚动并确保是整行的倍数
            try {
                if (mTextView.getLayout() != null) {
                    // 找到最接近当前滚动位置的行的顶部位置
                    int line = mTextView.getLayout().getLineForVertical(mScrollPosition);
                    // 调整到行的起始位置
                    mScrollPosition = mTextView.getLayout().getLineTop(line);
                    
                    // 特殊处理最后几行的显示，确保完全可见
                    if (line >= totalLines - 3) {
                        // 如果是最后几行，检查是否需要调整以确保最后一行完全可见
                        int lastLineBottom = mTextView.getLayout().getLineBottom(totalLines - 1);
                        if (mScrollPosition + viewHeight < lastLineBottom) {
                            // 调整位置确保最后一行完全可见
                            mScrollPosition = lastLineBottom - viewHeight;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "对齐到行起始位置失败: " + e.getMessage());
            }
            
            // 应用滚动
            mTextView.scrollTo(0, mScrollPosition);
            
            // 确保行距设置不变
            mTextView.setLineSpacing(0, 0.9f);
            
            // 日志输出滚动状态，方便调试
            Log.d(TAG, "滚动信息 - 当前位置:" + mScrollPosition + 
                  ", 可见行数:" + (lastVisibleLine - firstVisibleLine + 1) + 
                  ", 滚动行数:" + (direction > 0 ? targetLine - lastVisibleLine : firstVisibleLine - targetLine) + 
                  ", 最大滚动:" + maxScroll);
        }
    }
    
    /**
     * 将字符串的首字母转为大写
     * @param word 要处理的字符串
     * @return 首字母大写的字符串
     */
    private String capitalizeFirstLetter(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        
        return word.substring(0, 1).toUpperCase() + word.substring(1);
    }
    
    /**
     * 查找JSON中匹配的括号位置
     */
    private int findMatchingBracket(String json, int startPos) {
        if (json.charAt(startPos) != '[') return -1;
        
        int count = 1;
        for (int i = startPos + 1; i < json.length(); i++) {
            if (json.charAt(i) == '[') count++;
            else if (json.charAt(i) == ']') {
                count--;
                if (count == 0) return i;
            }
        }
        return -1;
    }
    
    /**
     * 创建选词覆盖层
     */
    private void createSelectionOverlay() {
        // 创建滚动视图容器
        mSelectionOverlay = new FrameLayout(mContext);
        // 使用半透明深灰色背景
        mSelectionOverlay.setBackgroundColor(Color.argb(178, 20, 20, 20));  // 背景透明度约70%
        
        // 创建文本视图
        mTextView = new TextView(mContext);
        mTextView.setTextColor(Color.WHITE);
        mTextView.setBackgroundColor(Color.TRANSPARENT); // 背景由容器设置
        mTextView.setTextSize(22);  // 字体大小
        mTextView.setPadding(40, 15, 40, 15);  // 减小顶部和底部内边距
        
        // 设置多行显示和自动换行
        mTextView.setSingleLine(false);
        // 不设置最大行数，允许内容自由扩展
        mTextView.setMaxLines(Integer.MAX_VALUE);
        mTextView.setEllipsize(null);
        mTextView.setHorizontallyScrolling(false);
        
        // 设置文本对齐方式为居中
        mTextView.setGravity(android.view.Gravity.CENTER);  // 设置为居中对齐
        
        // 设置所有行的行距
        mTextView.setLineSpacing(10, 1.1f);
        
        // 将文本视图添加到滚动容器中
        mSelectionOverlay.addView(mTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        
        // 设置滚动容器的布局参数
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度根据内容自动调整
                FrameLayout.LayoutParams.WRAP_CONTENT   // 高度根据内容自动调整
        );
        containerParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; // 放置在顶部中央
        containerParams.topMargin = 50;  // 增加顶部距离，避免遮挡字幕
        
        // 计算屏幕宽度
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        
        // 设置初始宽度为屏幕宽度的70%
        containerParams.width = (int)(screenWidth * 0.7f);
        
        // 设置初始高度为自适应，稍后会根据内容动态调整
        containerParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        
        mSelectionOverlay.setLayoutParams(containerParams);
        
        // 添加边框和圆角
        try {
            GradientDrawable border = new GradientDrawable();
            
            // 创建渐变背景，底部稍微深一些
            border.setColors(new int[] {
                    Color.argb(178, 30, 30, 30),   // 顶部颜色
                    Color.argb(178, 15, 15, 15)    // 底部颜色
            });
            border.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            
            // 设置圆角和边框
            border.setCornerRadius(25); // 增大圆角半径
            border.setStroke(2, Color.argb(200, 120, 120, 120)); // 边框宽度和颜色
            
            // 添加轻微的阴影效果
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
        
        // 重置滚动位置
        mScrollPosition = 0;
    }
    
    /**
     * 过滤响应中的思考过程和think标签
     * @param text 原始响应文本
     * @return 过滤后的文本
     */
    private String filterThinkingContent(String text) {
        if (text == null) {
            return "";
        }
        
        // 移除所有尖括号及其中间内容（通用规则，放在最前面）
        String filtered = text.replaceAll("<[^>]*>", "");
        
        // 移除XML风格的<think>...</think>标签及其内容
        filtered = filtered.replaceAll("(?i)<think>.*?</think>", "");
        
        // 移除<think 开头直到 think>结束的内容，这种格式可能会有属性
        filtered = filtered.replaceAll("(?i)<think\\s+[^>]*>.*?</think>", "");
        
        // 移除单独的<think>或</think>标签
        filtered = filtered.replaceAll("(?i)</?think[^>]*>", "");
        
        // 移除以"let me think"或"thinking"开头的段落
        filtered = filtered.replaceAll("(?i)(?m)^\\s*(?:let me think|thinking).*$", "");
        
        // 移除常见思考模式
        filtered = filtered.replaceAll("(?i)I need to consider|Let's analyze|Let me consider", "");
        
        // 移除含有"思考"或"思索"的句子
        filtered = filtered.replaceAll("[^。]*[思考|思索][^。]*。", "");
        
        // 移除"我来分析一下"和类似表达
        filtered = filtered.replaceAll("我来分析一下|我们来看看|让我思考", "");
        
        // 移除其他常见的思考过程标记
        filtered = filtered.replaceAll("首先[，,]我|首先[，,]让|第一步|第二步|第三步", "");
        
        // 移除英文括号中的思考内容 (例如: (let me analyze this))
        filtered = filtered.replaceAll("\\([^)]*(?:analyze|think|consider)[^)]*\\)", "");
        
        // 替换多个连续换行为最多两个换行符
        filtered = filtered.replaceAll("\n{3,}", "\n\n");
        
        // 移除空行开头
        filtered = filtered.replaceAll("^\\s*\n", "");
        
        return filtered.trim();
    }
    
    /**
     * 从解释内容中移除音标
     * @param text 原始解释文本
     * @return 移除音标后的文本
     */
    private String removePhoneticFromText(String text) {
        if (text == null) {
            return "";
        }
        
        // 保存原始文本
        String originalText = text;
        
        // 处理文本，按行处理以确保更精确的控制
        String[] lines = text.split("\n");
        StringBuilder cleanedTextBuilder = new StringBuilder();
        
        // 标记是否在音标部分
        boolean inPhoneticSection = false;
        
        for (String line : lines) {
            // 检查是否进入或退出音标部分
            if (line.trim().matches("^\\s*【(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)】\\s*$")) {
                inPhoneticSection = true;
                continue; // 跳过此行
            } else if (inPhoneticSection && line.trim().matches("^\\s*【.*】\\s*$")) {
                // 如果在音标部分内，遇到其他【xxx】标题，说明音标部分结束
                inPhoneticSection = false;
            }
            
            // 如果在音标部分内，跳过此行
            if (inPhoneticSection) {
                continue;
            }
            
            // 跳过只包含音标的行
            if (isPhoneticOnlyLine(line)) {
                continue;
            }
            
            // 从行中移除音标部分
            String cleanedLine = removePhoneticFromLine(line);
            
            // 如果清理后的行不为空，添加到结果中
            if (!cleanedLine.trim().isEmpty()) {
                cleanedTextBuilder.append(cleanedLine).append("\n");
            }
        }
        
        // 最终处理
        String cleanedText = cleanedTextBuilder.toString();
        
        // 移除"发音："等相关内容的整行
        cleanedText = cleanedText.replaceAll("(?m)^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)(?:为|是|:|：)?[^\\n]*$", "");
        
        // 移除空行
        cleanedText = cleanedText.replaceAll("(?m)^\\s*$\\n", "");
        
        // 合并多个连续空行
        cleanedText = cleanedText.replaceAll("\n{3,}", "\n\n");
        
        // 检查是否删除了太多内容
        if (cleanedText.trim().isEmpty() && !originalText.trim().isEmpty()) {
            // 如果删除了所有内容，则使用简单的方式只删除明显的音标和音标说明
            return originalText
                .replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)(?:为|是|:|：)?\\s*\\[[^\\]]+\\]", "")
                .replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)(?:为|是|:|：)?\\s*\\/[^\\/]+\\/", "")
                .replaceAll("\\[\\[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\]", "")
                .replaceAll("\\/[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\/", "")
                .replace("*", "")
                .trim();
        }
        
        return cleanedText.trim();
    }
    
    /**
     * 从一行文本中移除音标
     * @param line 要处理的行
     * @return 移除音标后的行
     */
    private String removePhoneticFromLine(String line) {
        // 移除整个"音标为：[xxx]"或"美式音标为：[xxx]"的模式
        String cleanedLine = line.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\[[^\\]]+\\]", "");
        
        // 移除整个"音标为：/xxx/"或"美式音标为：/xxx/"的模式
        cleanedLine = cleanedLine.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\/[^\\/]+\\/", "");
        
        // 移除方括号音标
        cleanedLine = cleanedLine.replaceAll("\\[\\[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\]", "");
        
        // 移除反斜杠音标
        cleanedLine = cleanedLine.replaceAll("\\/[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\/", "");
        
        // 移除可能的残留音标说明（如果只有说明没有跟随音标）
        cleanedLine = cleanedLine.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*$", "");
        
        // 移除独立的"美式英语"短语及其周围的标点符号
        cleanedLine = cleanedLine.replaceAll("[（(\\[【]?美式英语[)）\\]】]?", "");
        
        // 移除所有星号
        cleanedLine = cleanedLine.replace("*", "");
        
        return cleanedLine.trim();
    }
    
    /**
     * 判断一行文本是否只包含音标
     * @param line 要检查的行
     * @return 如果只包含音标，返回true
     */
    private boolean isPhoneticOnlyLine(String line) {
        String trimmedLine = line.trim();
        
        // 检查是否是纯音标行
        if (trimmedLine.matches("^\\s*\\[[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\]\\s*$")) {
            return true;
        }
        
        // 检查是否是纯反斜杠音标行
        if (trimmedLine.matches("^\\s*\\/[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\/\\s*$")) {
            return true;
        }
        
        // 检查是否是"美音："等开头后面跟音标的行
        if (trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\[[^\\]]+\\]\\s*$")) {
            return true;
        }
        
        // 检查是否是"美音："等开头后面跟反斜杠音标的行
        if (trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\/[^\\/]+\\/\\s*$")) {
            return true;
        }
        
        // 检查是否是只包含"发音"或"音标"的行
        if (trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*$")) {
            return true;
        }
        
        // 检查是否是发音部分的标题行（如"【发音】"）
        if (trimmedLine.matches("^\\s*【(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)】\\s*$")) {
            return true;
        }
        
        // 检查是否只包含"美式英语"及其周围符号的行
        if (trimmedLine.matches("^\\s*[（(\\[【]?美式英语[)）\\]】]?\\s*$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 退出选词模式
     */
    public void exitWordSelectionMode() {
        if (!mIsWordSelectionMode) {
            return;
        }
        
        // 隐藏选词覆盖层
        hideSelectionOverlay();
        
        // 清除字幕中的高亮显示
        clearSubtitleHighlight();
        
        // 重置选择的单词
        mCurrentWordIndex = 0;
        mWords = new String[0];
        mWordPositions = new int[0];
        
        // 退出选词模式
        mIsWordSelectionMode = false;
        mIsShowingDefinition = false;
        
        // 强制刷新字幕视图，确保高亮完全消失
        refreshSubtitleView();
        
        // 恢复播放
        PlaybackView view = mPlaybackPresenter.getView();
        if (view != null) {
            view.setPlayWhenReady(true);
        }
    }
    
    /**
     * 强制刷新字幕视图
     */
    private void refreshSubtitleView() {
        if (mSubtitleView != null) {
            try {
                // 获取SubtitleView的当前Cues
                List<Cue> currentCues = mSubtitleView.getCues();
                if (currentCues != null && !currentCues.isEmpty()) {
                    // 手动触发字幕重绘
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
        
        // 先记录当前播放位置，用于对比
        long currentPositionMs = view.getPositionMs();
        Log.d(TAG, "当前播放位置: " + currentPositionMs + "ms");
        
        // 使用记录的字幕变化时间作为字幕起始时间
        if (mLastSubtitleChangeTime > 0) {
            Log.d(TAG, "使用记录的字幕变化时间: " + mLastSubtitleChangeTime + "ms, 字幕: '" + mLastSubtitleText + "'");
            
            // 检查时间是否合理（不应该比当前时间早太多或晚太多）
            long timeDiff = Math.abs(currentPositionMs - mLastSubtitleChangeTime);
            if (timeDiff > 10000) { // 如果相差超过10秒
                Log.w(TAG, "记录的字幕时间与当前时间相差太大: " + timeDiff + "ms，可能不准确");
                
                // 如果字幕变化时间明显不合理，使用当前时间
                if (mLastSubtitleChangeTime > currentPositionMs + 5000 || 
                    mLastSubtitleChangeTime < currentPositionMs - 30000) {
                    Log.w(TAG, "使用当前播放位置代替不合理的字幕时间");
                    view.setPositionMs(currentPositionMs);
                    MessageHelpers.showMessage(mContext, "使用当前位置重新播放");
                    view.setPlayWhenReady(true);
                    // 激活跳转保护
                    activateSeekProtection();
                    return;
                }
            }
            
            // 使用记录的字幕变化时间
            view.setPositionMs(mLastSubtitleChangeTime);
            MessageHelpers.showMessage(mContext, "跳转到字幕起始时间: " + formatTime(mLastSubtitleChangeTime));
        } else {
            // 没有记录的字幕变化时间，退回到使用当前位置
            Log.w(TAG, "没有记录的字幕变化时间，使用当前位置");
            view.setPositionMs(currentPositionMs);
            MessageHelpers.showMessage(mContext, "使用当前位置重新播放");
        }
        
        // 开始播放
        view.setPlayWhenReady(true);
        
        // 激活跳转保护，防止重新播放时更新字幕时间点
        activateSeekProtection();
    }
    
    /**
     * 格式化时间（毫秒 -> HH:MM:SS 格式）
     */
    private String formatTime(long timeMs) {
        int seconds = (int) (timeMs / 1000) % 60;
        int minutes = (int) ((timeMs / (1000 * 60)) % 60);
        int hours = (int) ((timeMs / (1000 * 60 * 60)) % 24);
        
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
    
    /**
     * 显示双击状态的提示信息
     */
    private void showDoubleClickToast() {
        try {
            // 显示提示信息
            MessageHelpers.showMessage(mContext, "检测到双击，从字幕开始时间重新播放");
            
            // 记录日志
            Log.d(TAG, "显示双击提示信息");
        } catch (Exception e) {
            Log.e(TAG, "显示双击提示信息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否在跳转保护期内
     */
    private boolean isSeekProtectionActive() {
        // 检查保护状态
        if (mIsSeekProtectionActive) {
            // 检查保护是否已过期
            long currentTime = System.currentTimeMillis();
            if (currentTime - mSeekProtectionStartTime < SEEK_PROTECTION_DURATION) {
                // 保护仍然有效
                return true;
            } else {
                // 保护已过期，清除保护状态
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
        
        // 设置定时器，在保护期结束后自动清除保护状态
        mHandler.postDelayed(() -> {
            mIsSeekProtectionActive = false;
            Log.d(TAG, "跳转保护已自动清除");
        }, SEEK_PROTECTION_DURATION);
    }
    
    // 在类的最上方fetchOllamaDefinition方法声明处添加无重试次数的重载版本
    /**
     * 从本地Ollama服务获取单词解释
     * @param word 要查询的单词
     * @param context 单词所在的上下文
     * @param wordPosition 单词在字幕中的位置
     * @return 单词解释
     */
    private String fetchOllamaDefinition(String word, String context, int wordPosition) {
        return fetchOllamaDefinition(word, context, wordPosition, 0);
    }
    
    /**
     * 更新解释窗口的第一行，显示学习状态
     * @param word 当前单词
     * @param isLearning 是否在学习列表中
     */
    private void updateDefinitionFirstLine(String word, boolean isLearning) {
        if (mTextView == null) {
            Log.e(TAG, "无法更新解释窗口，mTextView为空");
            return;
        }
        
        try {
            // 获取当前文本
            String originalText = mTextView.getText().toString();
            
            // 查找第一个换行符位置
            int firstLineBreak = originalText.indexOf('\n');
            if (firstLineBreak < 0) {
                // 如果没有换行符，直接添加状态行
                String statusLine = "【" + (isLearning ? "学习中" : "取消") + "】 " + word + "\n\n";
                mTextView.setText(statusLine + originalText);
                return;
            }
            
            // 查找第二个换行符位置
            int secondLineBreak = originalText.indexOf('\n', firstLineBreak + 1);
            if (secondLineBreak < 0) {
                // 如果只有一行，添加状态行和空行
                String statusLine = "【" + (isLearning ? "学习中" : "取消") + "】 " + word + "\n\n";
                String contentText = originalText.substring(firstLineBreak + 1);
                mTextView.setText(statusLine + contentText);
                return;
            }
            
            // 提取内容部分（从第二个换行符之后）
            String contentText = originalText.substring(secondLineBreak + 1);
            
            // 创建新的文本，包含状态行、空行和内容
            String statusLine = "【" + (isLearning ? "学习中" : "取消") + "】 " + word + "\n\n";
            mTextView.setText(statusLine + contentText);
            
            // 重置滚动位置
            mScrollPosition = 0;
            mTextView.scrollTo(0, 0);
            
            // 显示提示消息
            MessageHelpers.showMessage(mContext, isLearning ? 
                    "已添加到学习列表: " + word : 
                    "已从学习列表移除: " + word);
            
            Log.d(TAG, "已更新解释窗口第一行，显示学习状态: " + (isLearning ? "学习中" : "取消"));
        } catch (Exception e) {
            Log.e(TAG, "更新解释窗口第一行失败: " + e.getMessage(), e);
        }
    }
} 