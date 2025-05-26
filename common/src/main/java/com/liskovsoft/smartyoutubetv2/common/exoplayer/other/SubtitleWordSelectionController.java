package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
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
    private TextView mSelectionOverlay;
    private boolean mIsShowingDefinition = false; // 是否正在显示解释窗口
    
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
        
        // 退出选词模式
        mIsWordSelectionMode = false;
        
        // 恢复播放
        PlaybackView view = mPlaybackPresenter.getView();
        if (view != null) {
            view.setPlayWhenReady(true);
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
        
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!mIsShowingDefinition) {
                    selectPreviousWord();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!mIsShowingDefinition) {
                    selectNextWord();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!mIsShowingDefinition) {
                    translateCurrentWord();
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
     * 设置当前字幕文本
     */
    public void setCurrentSubtitleText(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) {
            mCurrentSubtitleText = "";
            return;
        }
        
        // 合并所有字幕文本，确保包含完整的字幕内容
        StringBuilder fullText = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.text != null) {
                if (fullText.length() > 0) {
                    fullText.append(" ");
                }
                fullText.append(cue.text.toString());
            }
        }
        
        mCurrentSubtitleText = fullText.toString();
        Log.d(TAG, "设置字幕文本: " + mCurrentSubtitleText);
        
        // 如果在选词模式，更新单词列表
        if (mIsWordSelectionMode) {
            splitSubtitleIntoWords();
            highlightCurrentWord();
        }
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
            return;
        }
        
        Log.d(TAG, "分割字幕文本: " + mCurrentSubtitleText);
        
        // 分割文本为单词（按空格分割）
        String[] words = mCurrentSubtitleText.split("\\s+");
        
        // 过滤空单词
        List<String> wordList = new ArrayList<>();
        for (String word : words) {
            // 清理单词（去除标点符号等）
            String cleanWord = word.replaceAll("[,.!?;:'\"]", "").trim();
            if (!cleanWord.isEmpty()) {
                wordList.add(cleanWord); // 保存清理后的单词
                Log.d(TAG, "添加单词: '" + cleanWord + "' 原始单词: '" + word + "'");
            }
        }
        
        mWords = wordList.toArray(new String[0]);
        mCurrentWordIndex = 0;
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
        
        // 在字幕中高亮显示当前单词
        highlightWordInSubtitle(currentWord);
    }
    
    /**
     * 在字幕中高亮显示指定单词
     * @param word 要高亮的单词
     */
    private void highlightWordInSubtitle(String word) {
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
                // 清理单词（去除标点符号等），与分词时的处理保持一致
                String cleanWord = word.replaceAll("[,.!?;:'\"]", "").trim();
                
                Log.d(TAG, "设置高亮单词: " + cleanWord + ", 字幕画笔数量: " + painters.size());
                
                // 为所有画笔设置高亮单词
                for (Object painter : painters) {
                    // 设置要高亮的单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    highlightWordField.set(painter, cleanWord);
                    
                    // 启用单词高亮
                    Field enableWordHighlightField = painter.getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                    enableWordHighlightField.setAccessible(true);
                    enableWordHighlightField.set(null, true);
                }
                
                // 强制重绘字幕
                mSubtitleView.invalidate();
            }
        } catch (Exception e) {
            // 反射失败，记录错误但不中断程序
            Log.e(TAG, "无法通过反射高亮字幕中的单词: " + e.getMessage(), e);
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
        showDefinitionOverlay(currentWord + "\n加载中...");
        
        // 创建一个新线程来执行网络请求，避免阻塞主线程
        new Thread(() -> {
            String definition = fetchWordDefinition(currentWord);
            
            // 回到主线程更新 UI
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(() -> {
                    if (mSelectionOverlay != null && mIsWordSelectionMode) {
                        // 显示单词和解释
                        showDefinitionOverlay(currentWord + "\n\n" + definition);
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
            
            mSelectionOverlay.setText(text);
            mSelectionOverlay.setVisibility(View.VISIBLE);
            mIsShowingDefinition = true;
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
     * 从 Free Dictionary API 获取单词解释
     * @param word 要查询的单词
     * @return 单词解释
     */
    private String fetchWordDefinition(String word) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        
        try {
            // 使用 WordsAPI，这个 API 提供更详细的解释，适合中文学习者
            // 注意：此 API 需要订阅密钥，这里使用免费的 Free Dictionary API 作为替代
            URL url = new URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + word.toLowerCase());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            // 获取响应状态码
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                // 解析 JSON 响应
                return parseDefinitionForChineseLearner(result.toString());
            } else {
                return "无法获取解释 (错误码: " + responseCode + ")";
            }
        } catch (Exception e) {
            Log.e(TAG, "获取单词解释失败: " + e.getMessage(), e);
            return "获取解释失败: " + e.getMessage();
        } finally {
            // 关闭连接和读取器
            if (connection != null) {
                connection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭读取器失败: " + e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * 解析 Free Dictionary API 的 JSON 响应，以中文学习者的角度格式化
     * @param jsonResponse JSON 响应字符串
     * @return 格式化的单词解释
     */
    private String parseDefinitionForChineseLearner(String jsonResponse) {
        try {
            // 简单解析 JSON 响应，提取对中文学习者有用的信息
            if (jsonResponse.startsWith("[") && jsonResponse.contains("\"meanings\"")) {
                StringBuilder definition = new StringBuilder();
                
                // 提取音标（如果有）
                int phoneticsIndex = jsonResponse.indexOf("\"phonetic\"");
                if (phoneticsIndex > 0) {
                    int phoneticStart = jsonResponse.indexOf("\"", phoneticsIndex + 11) + 1;
                    int phoneticEnd = jsonResponse.indexOf("\"", phoneticStart);
                    if (phoneticStart > 0 && phoneticEnd > phoneticStart) {
                        String phonetic = jsonResponse.substring(phoneticStart, phoneticEnd);
                        definition.append("音标: ").append(phonetic).append("\n\n");
                    }
                }
                
                // 提取词性和定义
                int meaningsIndex = jsonResponse.indexOf("\"meanings\"");
                if (meaningsIndex > 0) {
                    int partOfSpeechIndex = jsonResponse.indexOf("\"partOfSpeech\"", meaningsIndex);
                    while (partOfSpeechIndex > 0) {
                        // 提取词性
                        int partOfSpeechStart = jsonResponse.indexOf("\"", partOfSpeechIndex + 15) + 1;
                        int partOfSpeechEnd = jsonResponse.indexOf("\"", partOfSpeechStart);
                        String partOfSpeech = jsonResponse.substring(partOfSpeechStart, partOfSpeechEnd);
                        
                        // 翻译词性为中文
                        String chinesePartOfSpeech = translatePartOfSpeech(partOfSpeech);
                        definition.append("【").append(chinesePartOfSpeech).append("】\n");
                        
                        // 提取定义
                        int definitionsIndex = jsonResponse.indexOf("\"definitions\"", partOfSpeechEnd);
                        if (definitionsIndex > 0) {
                            int definitionIndex = jsonResponse.indexOf("\"definition\"", definitionsIndex);
                            int exampleIndex = jsonResponse.indexOf("\"example\"", definitionsIndex);
                            
                            if (definitionIndex > 0) {
                                int definitionStart = jsonResponse.indexOf("\"", definitionIndex + 13) + 1;
                                int definitionEnd = jsonResponse.indexOf("\"", definitionStart);
                                String def = jsonResponse.substring(definitionStart, definitionEnd);
                                
                                definition.append("- ").append(def).append("\n");
                                
                                // 提取例句（如果有）
                                if (exampleIndex > 0 && exampleIndex < jsonResponse.indexOf("\"definitions\"", definitionIndex + 1)) {
                                    int exampleStart = jsonResponse.indexOf("\"", exampleIndex + 10) + 1;
                                    int exampleEnd = jsonResponse.indexOf("\"", exampleStart);
                                    if (exampleStart > 0 && exampleEnd > exampleStart) {
                                        String example = jsonResponse.substring(exampleStart, exampleEnd);
                                        definition.append("  例句: ").append(example).append("\n");
                                    }
                                }
                                
                                definition.append("\n");
                            }
                        }
                        
                        // 查找下一个词性
                        partOfSpeechIndex = jsonResponse.indexOf("\"partOfSpeech\"", partOfSpeechEnd + 100);
                        
                        // 限制只显示前三个定义，避免太长
                        if (definition.length() > 500) {
                            definition.append("...(更多解释已省略)");
                            break;
                        }
                    }
                }
                
                if (definition.length() > 0) {
                    return definition.toString();
                }
            }
            
            return "无法解析单词解释";
        } catch (Exception e) {
            Log.e(TAG, "解析单词解释失败: " + e.getMessage(), e);
            return "解析解释失败: " + e.getMessage();
        }
    }
    
    /**
     * 将英文词性翻译为中文
     * @param partOfSpeech 英文词性
     * @return 中文词性
     */
    private String translatePartOfSpeech(String partOfSpeech) {
        switch (partOfSpeech.toLowerCase()) {
            case "noun":
                return "名词";
            case "verb":
                return "动词";
            case "adjective":
                return "形容词";
            case "adverb":
                return "副词";
            case "pronoun":
                return "代词";
            case "preposition":
                return "介词";
            case "conjunction":
                return "连词";
            case "interjection":
                return "感叹词";
            case "determiner":
                return "限定词";
            case "article":
                return "冠词";
            default:
                return partOfSpeech; // 如果不能翻译，返回原词性
        }
    }
    
    /**
     * 创建选词覆盖层
     */
    private void createSelectionOverlay() {
        mSelectionOverlay = new TextView(mContext);
        mSelectionOverlay.setTextColor(Color.WHITE);
        mSelectionOverlay.setBackgroundColor(Color.argb(220, 0, 0, 0));
        mSelectionOverlay.setTextSize(18);
        mSelectionOverlay.setPadding(50, 30, 50, 30);
        
        // 设置多行显示和自动换行
        mSelectionOverlay.setSingleLine(false);
        mSelectionOverlay.setMaxLines(15);
        mSelectionOverlay.setEllipsize(null);
        mSelectionOverlay.setHorizontallyScrolling(false);
        
        // 设置文本对齐方式
        mSelectionOverlay.setGravity(android.view.Gravity.CENTER);
        
        // 设置布局参数
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER;
        params.width = (int) (mContext.getResources().getDisplayMetrics().widthPixels * 0.7f); // 设置宽度为屏幕宽度的70%
        mSelectionOverlay.setLayoutParams(params);
        
        // 默认隐藏
        mSelectionOverlay.setVisibility(View.GONE);
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
        try {
            // 通过反射获取 SubtitleView 的内部实现
            Field paintersField = mSubtitleView.getClass().getDeclaredField("painters");
            paintersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> painters = (List<Object>) paintersField.get(mSubtitleView);
            
            if (painters != null && !painters.isEmpty()) {
                Log.d(TAG, "清除高亮单词, 字幕画笔数量: " + painters.size());
                
                // 为所有画笔清除高亮单词
                for (Object painter : painters) {
                    // 清除高亮单词
                    Field highlightWordField = painter.getClass().getDeclaredField("highlightWord");
                    highlightWordField.setAccessible(true);
                    highlightWordField.set(painter, null);
                }
                
                // 禁用单词高亮
                Field enableWordHighlightField = painters.get(0).getClass().getDeclaredField("ENABLE_WORD_HIGHLIGHT");
                enableWordHighlightField.setAccessible(true);
                enableWordHighlightField.set(null, false);
                
                // 强制重绘字幕
                mSubtitleView.invalidate();
            }
        } catch (Exception e) {
            // 反射失败，记录错误但不中断程序
            Log.e(TAG, "无法通过反射清除字幕中的高亮: " + e.getMessage(), e);
        }
    }
} 