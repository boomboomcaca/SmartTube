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

/**
 * 控制器类，用于管理字幕选词功能
 */
public class SubtitleWordSelectionController {
    private static final String TAG = SubtitleWordSelectionController.class.getSimpleName();
    
    private final Context mContext;
    private final SubtitleView mSubtitleView;
    private final FrameLayout mRootView;
    private final PlaybackPresenter mPlaybackPresenter;
    
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
    
    public SubtitleWordSelectionController(Context context, SubtitleView subtitleView, FrameLayout rootView) {
        mContext = context;
        mSubtitleView = subtitleView;
        mRootView = rootView;
        mPlaybackPresenter = PlaybackPresenter.instance(context);
        
        // 创建选词覆盖层
        createSelectionOverlay();
    }
    
    /**
     * 进入选词模式
     */
    public void enterWordSelectionMode() {
        if (mSubtitleView == null || !hasSubtitleText()) {
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
        
        // 高亮显示第一个单词（但不显示覆盖层）
        highlightCurrentWord();
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
                // 反射获取字幕视图的方法来刷新显示
                java.lang.reflect.Method invalidateMethod = View.class.getDeclaredMethod("invalidate");
                invalidateMethod.setAccessible(true);
                invalidateMethod.invoke(mSubtitleView);
                
                Log.d(TAG, "已强制刷新字幕视图");
            } catch (Exception e) {
                Log.e(TAG, "刷新字幕视图失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 处理按键事件
     * @return 是否已处理按键事件
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (!mIsWordSelectionMode) {
            return false;
        }
        
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true; // 消费所有按键抬起事件
        }
        
        // 检查工具栏是否显示
        boolean isControlsVisible = isControlsOverlayVisible();
        
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
                if (!mIsShowingDefinition) {
                    translateCurrentWord();
                } else {
                    // 如果已经在显示解释，点击确认键隐藏解释
                    hideDefinitionOverlay();
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
                String cleanWord = word.replaceAll("[,.!?;:\"]", "").trim();
                
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
                                    Pattern pattern = Pattern.compile("\\b" + cleanWord + "[\\w']*\\b", Pattern.CASE_INSENSITIVE);
                                    Matcher matcher = pattern.matcher(textStr);
                                    if (matcher.find()) {
                                        String matchedWord = matcher.group();
                                        Log.d(TAG, "找到相似单词匹配: " + matchedWord);
                                        // 更新高亮单词为实际匹配到的单词
                                        highlightWordField.set(painter, matchedWord);
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
        if (mWords.length == 0 || mCurrentWordIndex >= mWords.length) {
            return;
        }
        
        String currentWord = mWords[mCurrentWordIndex];
        
        // 显示覆盖层和加载提示
        showDefinitionOverlay("正在查询中...\n请稍候");
        
        // 创建一个新线程来执行网络请求，避免阻塞主线程
        new Thread(() -> {
            // 尝试使用主API
            String definition = fetchWordDefinition(currentWord);
            
            // 检查主API结果是否有效
            boolean isValidResult = definition != null && 
                                  !definition.contains("获取解释失败") && 
                                  !definition.contains("无法获取解释") &&
                                  !definition.equals("未找到 \"" + currentWord + "\" 的释义");
            
            // 如果主API失败，尝试备用API
            if (!isValidResult) {
                Log.d(TAG, "主API失败，尝试使用备用API");
                
                // 更新UI提示正在使用备用API
                if (mContext instanceof Activity) {
                    ((Activity) mContext).runOnUiThread(() -> {
                        if (mTextView != null && mIsWordSelectionMode) {
                            showDefinitionOverlay("正在使用备用API查询...");
                        }
                    });
                }
                
                String backupDefinition = fetchWordDefinitionBackup(currentWord);
                
                // 如果备用API有结果，使用备用API的结果
                if (backupDefinition != null && 
                    !backupDefinition.contains("备用API获取解释失败") && 
                    !backupDefinition.contains("备用API请求失败")) {
                    definition = backupDefinition;
                }
            }
            
            // 记录日志
            Log.d(TAG, "翻译结果长度: " + (definition != null ? definition.length() : "null"));
            
            // 最终结果
            final String finalDefinition = definition;
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    // 检查是否仍然处于单词选择模式
                    if (mTextView != null && mIsWordSelectionMode) {
                        // 只显示解释内容，不再重复添加单词名称
                        showDefinitionOverlay(finalDefinition);
                    }
                });
            }
        }).start();
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
                    String[] lines = text.split("\n");
                    
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        
                        // 统一处理所有标题【标题】样式
                        if (line.startsWith("【") && line.endsWith("】")) {
                            // 判断是单词标题还是子标题
                            if (i == 0 && line.contains("]") && (line.contains("音标") || line.contains("美") || line.contains("英"))) {
                                // 处理第一行包含单词和音标的情况
                                // 将单词名称和音标分开处理
                                int bracketEnd = line.indexOf("】") + 1;
                                
                                // 处理单词名称部分（使用橙色和粗体，与标题一致）
                                SpannableString wordPart = new SpannableString(line.substring(0, bracketEnd));
                                wordPart.setSpan(new ForegroundColorSpan(Color.rgb(255, 165, 0)), 0, bracketEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                wordPart.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, bracketEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                builder.append(wordPart);
                                
                                // 处理音标部分（保持青色）
                                if (bracketEnd < line.length()) {
                                    SpannableString phoneticPart = new SpannableString(line.substring(bracketEnd));
                                    phoneticPart.setSpan(new ForegroundColorSpan(Color.CYAN), 0, phoneticPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    builder.append(phoneticPart);
                                }
                            } else {
                                // 处理所有子标题（基本释义、网络释义、例句等）统一样式，只保留颜色和粗体，不改变大小
                                SpannableString ss = new SpannableString(line);
                                ss.setSpan(new ForegroundColorSpan(Color.rgb(255, 165, 0)), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                builder.append(ss);
                            }
                        }
                        // 处理单独的音标行 [音标]（不应该再有这种情况，但保留兼容）
                        else if (line.contains("[") && line.contains("]") && 
                                (line.contains("音标") || line.contains("美") || line.contains("英"))) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.CYAN), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            // 移除字体大小修改
                            builder.append(ss);
                        }
                        // 处理列表项 •
                        else if (line.startsWith("•")) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.WHITE), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理例句序号
                        else if (line.matches("^\\d+\\..*")) {
                            SpannableString ss = new SpannableString(line);
                            ss.setSpan(new ForegroundColorSpan(Color.LTGRAY), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            ss.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.append(ss);
                        }
                        // 处理普通文本
                        else {
                            builder.append(line);
                        }
                        
                        // 除了最后一行，每行后面添加换行符
                        if (i < lines.length - 1) {
                            builder.append("\n");
                        }
                    }
                    
                    mTextView.setText(builder);
                    
                    // 手动触发布局更新，使容器适应内容宽度
                    mTextView.post(() -> {
                        // 确保宽度在最小和最大范围内
                        int contentWidth = mTextView.getLayout() != null ? 
                                Math.max(mTextView.getLayout().getWidth() + mTextView.getPaddingLeft() + mTextView.getPaddingRight(), 
                                mTextView.getMinWidth()) : mTextView.getMinWidth();
                        
                        // 根据内容调整宽度，并保持高度不变
                        ViewGroup.LayoutParams params = mSelectionOverlay.getLayoutParams();
                        if (params instanceof FrameLayout.LayoutParams) {
                            // 记录旧高度
                            int oldHeight = params.height;
                            // 更新宽度
                            params.width = contentWidth;
                            // 恢复高度
                            params.height = oldHeight;
                            // 应用更新的布局参数
                            mSelectionOverlay.setLayoutParams(params);
                            
                            Log.d(TAG, "动态调整窗口宽度: " + contentWidth);
                        }
                    });
                    
                } catch (Exception e) {
                    // 如果样式应用失败，退回到普通文本
                    Log.w(TAG, "应用文本样式失败: " + e.getMessage());
                    mTextView.setText(text);
                }
                
                // 重置滚动位置
                mScrollPosition = 0;
                mTextView.scrollTo(0, 0);
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
     * 从有道词典API获取单词解释
     * @param word 要查询的单词
     * @return 单词解释
     */
    private String fetchWordDefinition(String word) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        
        try {
            // 准备查询参数
            String query = word.toLowerCase().trim();
            
            // 编码查询词，处理特殊字符
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            
            // 使用有道词典API，更可靠的URL
            URL url = new URL("https://dict.youdao.com/jsonapi?q=" + encodedQuery);
            
            Log.d(TAG, "查询单词: " + query + ", URL: " + url.toString());
            
            // 设置连接
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000); // 增加超时时间
            connection.setReadTimeout(8000);
            
            // 设置请求头，模拟浏览器请求以避免被屏蔽
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            connection.setRequestProperty("Referer", "https://dict.youdao.com/");
            
            // 获取响应状态码
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                Log.d(TAG, "有道词典API响应长度: " + result.length() + " 字节");
                
                // 解析JSON响应
                return parseYoudaoResponse(result.toString(), word);
            } else {
                Log.e(TAG, "有道词典API请求失败: " + responseCode);
                return "无法获取解释 (错误码: " + responseCode + ")";
            }
        } catch (Exception e) {
            Log.e(TAG, "获取单词解释失败: " + e.getMessage(), e);
            return "获取解释失败: " + e.getMessage();
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
            }
        }
    }
    
    /**
     * 使用备用API获取单词解释
     * 如果主API失败，可以调用此方法
     * @param word 要查询的单词
     * @return 单词解释
     */
    private String fetchWordDefinitionBackup(String word) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        
        try {
            // 使用备用API - 有道智云
            String appKey = "0afe1878d5b9a5c1"; // 这是示例appKey，实际使用需要注册获取
            String salt = String.valueOf(System.currentTimeMillis());
            String from = "auto";
            String to = "zh-CHS";
            
            // 构建签名 - 实际使用需要正确的appKey和appSecret
            // String sign = MD5Util.md5(appKey + word + salt + appSecret);
            
            // 构建URL
            URL url = new URL("https://fanyi.youdao.com/openapi.do?keyfrom=YouDaoCV&key=659600698&type=data&doctype=json&version=1.1&q=" + java.net.URLEncoder.encode(word, "UTF-8"));
            
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            // 设置请求头
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            // 获取响应状态码
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                // 解析备用API的JSON响应
                return parseBackupResponse(result.toString(), word);
            } else {
                return "备用API请求失败 (错误码: " + responseCode + ")";
            }
        } catch (Exception e) {
            Log.e(TAG, "备用API获取单词解释失败: " + e.getMessage(), e);
            return "备用API获取解释失败: " + e.getMessage();
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
            }
        }
    }
    
    /**
     * 解析备用API的JSON响应
     */
    private String parseBackupResponse(String jsonResponse, String originalWord) {
        try {
            StringBuilder definition = new StringBuilder();
            
            // 添加查询单词和音标在同一行
            definition.append("【").append(originalWord).append("】 ");
            
            // 提取音标
            boolean hasPhonetic = false;
            
            if (jsonResponse.contains("\"phonetic\"")) {
                int phoneticIndex = jsonResponse.indexOf("\"phonetic\"");
                if (phoneticIndex > 0) {
                    int phoneticStart = jsonResponse.indexOf("\"", phoneticIndex + 11) + 1;
                    int phoneticEnd = jsonResponse.indexOf("\"", phoneticStart);
                    if (phoneticStart > 0 && phoneticEnd > phoneticStart) {
                        String phonetic = jsonResponse.substring(phoneticStart, phoneticEnd);
                        if (!phonetic.isEmpty()) {
                            definition.append("音标 [").append(phonetic).append("]");
                            hasPhonetic = true;
                        }
                    }
                }
            }
            
            // 提取美式发音音标
            if (!hasPhonetic && jsonResponse.contains("\"us-phonetic\"")) {
                int usPhoneticIndex = jsonResponse.indexOf("\"us-phonetic\"");
                if (usPhoneticIndex > 0) {
                    int usPhoneticStart = jsonResponse.indexOf("\"", usPhoneticIndex + 14) + 1;
                    int usPhoneticEnd = jsonResponse.indexOf("\"", usPhoneticStart);
                    if (usPhoneticStart > 0 && usPhoneticEnd > usPhoneticStart) {
                        String usPhonetic = jsonResponse.substring(usPhoneticStart, usPhoneticEnd);
                        if (!usPhonetic.isEmpty()) {
                            definition.append("美 [").append(usPhonetic).append("]");
                            hasPhonetic = true;
                        }
                    }
                }
            }
            
            // 提取英式发音音标
            if (!hasPhonetic && jsonResponse.contains("\"uk-phonetic\"")) {
                int ukPhoneticIndex = jsonResponse.indexOf("\"uk-phonetic\"");
                if (ukPhoneticIndex > 0) {
                    int ukPhoneticStart = jsonResponse.indexOf("\"", ukPhoneticIndex + 14) + 1;
                    int ukPhoneticEnd = jsonResponse.indexOf("\"", ukPhoneticStart);
                    if (ukPhoneticStart > 0 && ukPhoneticEnd > ukPhoneticStart) {
                        String ukPhonetic = jsonResponse.substring(ukPhoneticStart, ukPhoneticEnd);
                        if (!ukPhonetic.isEmpty()) {
                            definition.append("英 [").append(ukPhonetic).append("]");
                            hasPhonetic = true;
                        }
                    }
                }
            }
            
            // 添加换行
            definition.append("\n\n");
            
            // 添加查询单词翻译
            if (jsonResponse.contains("\"translation\"")) {
                int translationIndex = jsonResponse.indexOf("\"translation\"");
                int translationStart = jsonResponse.indexOf("[", translationIndex);
                int translationEnd = jsonResponse.indexOf("]", translationStart);
                
                if (translationStart > 0 && translationEnd > translationStart) {
                    String translation = jsonResponse.substring(translationStart + 1, translationEnd);
                    translation = translation.replace("\"", "");
                    definition.append("翻译: ").append(translation).append("\n\n");
                }
            }
            
            // 解析基本释义
            if (jsonResponse.contains("\"basic\"")) {
                int basicIndex = jsonResponse.indexOf("\"basic\"");
                int explainsIndex = jsonResponse.indexOf("\"explains\"", basicIndex);
                
                if (explainsIndex > 0) {
                    int explainsStart = jsonResponse.indexOf("[", explainsIndex);
                    int explainsEnd = jsonResponse.indexOf("]", explainsStart);
                    
                    if (explainsStart > 0 && explainsEnd > explainsStart) {
                        String explains = jsonResponse.substring(explainsStart + 1, explainsEnd);
                        String[] explanations = explains.split("\",\"");
                        
                        definition.append("【基本释义】\n");
                        for (String explanation : explanations) {
                            explanation = explanation.replace("\"", "");
                            definition.append("• ").append(explanation).append("\n");
                        }
                        definition.append("\n");
                    }
                }
            }
            
            return definition.toString();
        } catch (Exception e) {
            Log.e(TAG, "解析备用API响应失败: " + e.getMessage(), e);
            return "解析备用API响应失败: " + e.getMessage();
        }
    }
    
    /**
     * 解析有道词典API的JSON响应
     * @param jsonResponse JSON响应字符串
     * @param originalWord 原始查询的单词
     * @return 格式化的单词解释
     */
    private String parseYoudaoResponse(String jsonResponse, String originalWord) {
        try {
            StringBuilder definition = new StringBuilder();
            
            // 添加查询单词和音标在同一行
            definition.append("【").append(originalWord).append("】 ");
            
            // 提取音标
            boolean hasPhonetic = false;
            
            if (jsonResponse.contains("\"uk-phonetic\"")) {
                int ukPhoneticIndex = jsonResponse.indexOf("\"uk-phonetic\"");
                if (ukPhoneticIndex > 0) {
                    int ukPhoneticStart = jsonResponse.indexOf("\"", ukPhoneticIndex + 14) + 1;
                    int ukPhoneticEnd = jsonResponse.indexOf("\"", ukPhoneticStart);
                    if (ukPhoneticStart > 0 && ukPhoneticEnd > ukPhoneticStart) {
                        String ukPhonetic = jsonResponse.substring(ukPhoneticStart, ukPhoneticEnd);
                        definition.append("英 [").append(ukPhonetic).append("]  ");
                        hasPhonetic = true;
                    }
                }
            }
            
            if (jsonResponse.contains("\"us-phonetic\"")) {
                int usPhoneticIndex = jsonResponse.indexOf("\"us-phonetic\"");
                if (usPhoneticIndex > 0) {
                    int usPhoneticStart = jsonResponse.indexOf("\"", usPhoneticIndex + 14) + 1;
                    int usPhoneticEnd = jsonResponse.indexOf("\"", usPhoneticStart);
                    if (usPhoneticStart > 0 && usPhoneticEnd > usPhoneticStart) {
                        String usPhonetic = jsonResponse.substring(usPhoneticStart, usPhoneticEnd);
                        definition.append("美 [").append(usPhonetic).append("]");
                        hasPhonetic = true;
                    }
                }
            }
            
            // 如果没有找到音标，尝试查找备用音标字段
            if (!hasPhonetic && jsonResponse.contains("\"phonetic\"")) {
                int phoneticIndex = jsonResponse.indexOf("\"phonetic\"");
                if (phoneticIndex > 0) {
                    int phoneticStart = jsonResponse.indexOf("\"", phoneticIndex + 11) + 1;
                    int phoneticEnd = jsonResponse.indexOf("\"", phoneticStart);
                    if (phoneticStart > 0 && phoneticEnd > phoneticStart) {
                        String phonetic = jsonResponse.substring(phoneticStart, phoneticEnd);
                        definition.append("音标 [").append(phonetic).append("]");
                        hasPhonetic = true;
                    }
                }
            }
            
            // 添加换行
            definition.append("\n\n");
            
            // 提取基本释义
            if (jsonResponse.contains("\"ec\"")) {
                definition.append("【基本释义】\n");
                int ecIndex = jsonResponse.indexOf("\"ec\"");
                int wordIndex = jsonResponse.indexOf("\"word\"", ecIndex);
                
                if (wordIndex > 0) {
                    int trsIndex = jsonResponse.indexOf("\"trs\"", wordIndex);
                    if (trsIndex > 0) {
                        int trsStart = jsonResponse.indexOf("[", trsIndex);
                        int trsEnd = findMatchingBracket(jsonResponse, trsStart);
                        
                        if (trsStart > 0 && trsEnd > trsStart) {
                            String trsJson = jsonResponse.substring(trsStart, trsEnd + 1);
                            
                            // 解析翻译数组
                            int pos = 0;
                            int count = 0;
                            while (pos < trsJson.length()) { // 不限制释义数量
                                int trIndex = trsJson.indexOf("\"tr\"", pos);
                                if (trIndex < 0) break;
                                
                                int lIndex = trsJson.indexOf("\"l\"", trIndex);
                                if (lIndex < 0) break;
                                
                                int iIndex = trsJson.indexOf("\"i\"", lIndex);
                                if (iIndex < 0) break;
                                
                                int iValueStart = trsJson.indexOf("\"", iIndex + 4) + 1;
                                int iValueEnd = trsJson.indexOf("\"", iValueStart);
                                
                                if (iValueStart > 0 && iValueEnd > iValueStart) {
                                    String meaning = trsJson.substring(iValueStart, iValueEnd);
                                    definition.append("• ").append(meaning).append("\n");
                                    count++;
                                }
                                
                                pos = iValueEnd + 1;
                            }
                            definition.append("\n");
                        }
                    }
                }
            }
            
            // 如果没有找到任何释义
            if (definition.length() <= originalWord.length() + 5) { // 只有单词标题
                return "未找到 \"" + originalWord + "\" 的释义";
            }
            
            return definition.toString();
        } catch (Exception e) {
            Log.e(TAG, "解析有道词典响应失败: " + e.getMessage(), e);
            return "解析释义失败: " + e.getMessage();
        }
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
        mSelectionOverlay.setBackgroundColor(Color.argb(128, 0, 0, 0));  // 背景透明度改为50% (128/255)
        
        // 创建文本视图
        mTextView = new TextView(mContext);
        mTextView.setTextColor(Color.WHITE);
        mTextView.setBackgroundColor(Color.TRANSPARENT); // 背景由容器设置
        mTextView.setTextSize(22);  // 字体大小放大20%，从18增加到22
        mTextView.setPadding(30, 15, 30, 15);  // 减小内边距
        
        // 设置多行显示和自动换行
        mTextView.setSingleLine(false);
        mTextView.setMaxLines(15); // 最大行数仍然保留，但现在可以滚动查看
        mTextView.setEllipsize(null);
        mTextView.setHorizontallyScrolling(false);
        
        // 设置文本对齐方式
        mTextView.setGravity(android.view.Gravity.CENTER);  // 将文本对齐方式改为居中
        
        // 设置所有行的行距为0.9
        mTextView.setLineSpacing(0, 0.9f);
        
        // 将文本视图添加到滚动容器中
        mSelectionOverlay.addView(mTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        
        // 设置滚动容器的布局参数
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度改为WRAP_CONTENT，根据内容自动调整
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        containerParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; // 放置在顶部中央
        containerParams.topMargin = 0;  // 顶部距离为0，置顶显示
        
        // 计算屏幕宽度
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        
        // 设置最小宽度和最大宽度限制
        int minWidth = (int)(screenWidth * 0.5f);  // 最小为屏幕宽度的50%
        int maxWidth = (int)(screenWidth * 0.9f);  // 最大为屏幕宽度的90%
        
        // 设置宽度约束
        mTextView.setMinWidth(minWidth);
        mTextView.setMaxWidth(maxWidth);
        
        // 计算9行文本的高度（考虑到字体大小增加到22）
        int lineHeight = (int) (mTextView.getTextSize() * 0.9f); // 估计行高（考虑行距0.9）
        int paddingVertical = mTextView.getPaddingTop() + mTextView.getPaddingBottom();
        containerParams.height = lineHeight * 9 + paddingVertical; // 设置高度为9行文本高度
        
        mSelectionOverlay.setLayoutParams(containerParams);
        
        // 添加边框和圆角
        try {
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.argb(128, 0, 0, 0));  // 背景透明度改为50%
            border.setCornerRadius(20); // 圆角半径
            border.setStroke(2, Color.argb(200, 100, 100, 100)); // 边框宽度和颜色
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
                // 禁用所有画笔的单词高亮
                for (Object painter : painters) {
                    try {
                    // 清除高亮单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    highlightWordField.set(painter, null);
                
                // 禁用单词高亮
                        Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                enableWordHighlightField.setAccessible(true);
                enableWordHighlightField.set(null, false);
                        
                        // 尝试重置任何可能保存高亮状态的字段
                        resetHighlightRelatedFields(painter);
                    } catch (Exception e) {
                        Log.e(TAG, "清除单个画笔高亮失败: " + e.getMessage());
                    }
                }
                
                // 强制重绘字幕
                mSubtitleView.invalidate();
                
                Log.d(TAG, "已清除所有字幕高亮");
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
                } else if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\"') {
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
            Pattern wordPattern = Pattern.compile("\\b[\\w']+\\b|[,.!?;:\"]");
            Matcher matcher = wordPattern.matcher(processedText);
            
            while (matcher.find()) {
                String word = matcher.group();
                if (!word.isEmpty()) {
                    // 过滤掉单独的标点符号
                    if (word.length() == 1 && ",.!?;:\"".contains(word)) {
                        continue;
                    }
                    wordList.add(word);
                }
            }
            
            // 如果正则匹配没有找到任何单词，退回到简单的空格分割
            if (wordList.isEmpty()) {
                String[] words = processedText.split("\\s+");
                for (String word : words) {
                    String cleanWord = word.trim();
                    if (!cleanWord.isEmpty()) {
                        wordList.add(cleanWord);
                    }
                }
            }
        } else {
            // 普通文本分词：按空格分割，保留撇号，去除其他标点
            String[] words = processedText.split("\\s+");
            for (String word : words) {
                // 清理单词（去除标点符号等，但保留撇号）
                String cleanWord = word.replaceAll("[,.!?;:\"]", "").trim();
                if (!cleanWord.isEmpty()) {
                    wordList.add(cleanWord);
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
            // 计算一页的高度（视图高度减去内边距）
            int viewHeight = mTextView.getHeight() - mTextView.getPaddingTop() - mTextView.getPaddingBottom();
            
            // 计算实际行高
            float actualLineHeight = mTextView.getLineHeight();
            
            // 计算滚动量（以完整行为单位，确保不会截断行）
            int visibleLines = (int)(viewHeight / actualLineHeight);
            // 减少重叠行数，确保新内容可见（从50%减少到30%）
            int scrollLines = Math.max(1, (int)(visibleLines * 0.7));
            // 计算精确的滚动像素
            int scrollAmount = (int)(scrollLines * actualLineHeight) * direction;
            
            // 更新滚动位置
            mScrollPosition += scrollAmount;
            
            // 确保不滚动超出范围
            if (mScrollPosition < 0) {
                mScrollPosition = 0;
            }
            
            // 计算文本总高度
            int textHeight = mTextView.getLineCount() * (int)actualLineHeight;
            
            // 确保不滚动超出底部
            int maxScroll = Math.max(0, textHeight - viewHeight);
            if (mScrollPosition > maxScroll) {
                mScrollPosition = maxScroll;
            }
            
            // 应用滚动
            mTextView.scrollTo(0, mScrollPosition);
            
            // 确保行距设置不变
            mTextView.setLineSpacing(0, 0.9f);
            
            // 日志输出滚动状态，方便调试
            Log.d(TAG, "滚动信息 - 当前位置:" + mScrollPosition + 
                  ", 可见行数:" + visibleLines + 
                  ", 滚动行数:" + scrollLines + 
                  ", 最大滚动:" + maxScroll);
        }
    }
} 