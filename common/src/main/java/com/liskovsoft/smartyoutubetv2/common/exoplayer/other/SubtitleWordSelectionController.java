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
        
        // 重置选择的单词
        mCurrentWordIndex = 0;
        mWords = new String[0];
        
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
        
        List<String> wordList = new ArrayList<>();
        
        // 检测文本是否包含CJK字符（中文、日文、韩文）
        boolean containsCJK = false;
        for (int i = 0; i < mCurrentSubtitleText.length(); i++) {
            char c = mCurrentSubtitleText.charAt(i);
            // 中文范围: \u4E00-\u9FFF, 日文片假名: \u3040-\u309F, 韩文: \uAC00-\uD7A3 等
            if ((c >= '\u4E00' && c <= '\u9FFF') || // 中文
                (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
                (c >= '\uAC00' && c <= '\uD7A3')) { // 韩文
                containsCJK = true;
                break;
            }
        }
        
        if (containsCJK) {
            // CJK文字处理：逐字分词
            StringBuilder currentWord = new StringBuilder();
            
            for (int i = 0; i < mCurrentSubtitleText.length(); i++) {
                char c = mCurrentSubtitleText.charAt(i);
                
                // 如果是CJK字符，则作为单独的词
                if ((c >= '\u4E00' && c <= '\u9FFF') || // 中文
                    (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
                    (c >= '\uAC00' && c <= '\uD7A3')) { // 韩文
                    
                    // 如果之前已有非CJK单词在构建中，先添加它
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                            Log.d(TAG, "添加非CJK单词: '" + word + "'");
                        }
                        currentWord = new StringBuilder();
                    }
                    
                    // 添加CJK字符作为单独的词
                    wordList.add(String.valueOf(c));
                    Log.d(TAG, "添加CJK字符: '" + c + "'");
                    
                } else if (Character.isWhitespace(c)) {
                    // 处理空白字符
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                            Log.d(TAG, "添加单词: '" + word + "'");
                        }
                        currentWord = new StringBuilder();
                    }
                } else if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\'' || c == '\"') {
                    // 处理标点符号
                    if (currentWord.length() > 0) {
                        String word = currentWord.toString().trim();
                        if (!word.isEmpty()) {
                            wordList.add(word);
                            Log.d(TAG, "添加单词: '" + word + "'");
                        }
                        currentWord = new StringBuilder();
                    }
                    
                    // 可选：将标点符号也作为单独的词处理
                    // wordList.add(String.valueOf(c));
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
                    Log.d(TAG, "添加最后一个单词: '" + word + "'");
                }
            }
        } else {
            // 非CJK文字处理：使用标准的空格分词加标点符号处理
            String[] words = mCurrentSubtitleText.split("\\s+");
            
            // 过滤空单词
            for (String word : words) {
                // 清理单词（去除标点符号等）
                String cleanWord = word.replaceAll("[,.!?;:'\"]", "").trim();
                if (!cleanWord.isEmpty()) {
                    wordList.add(cleanWord); // 保存清理后的单词
                    Log.d(TAG, "添加单词: '" + cleanWord + "' 原始单词: '" + word + "'");
                }
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
     * 从有道词典API获取单词解释
     * @param word 要查询的单词
     * @return 单词解释
     */
    private String fetchWordDefinition(String word) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        
        try {
            // 使用有道词典API，不需要API key的简化版本
            URL url = new URL("https://dict.youdao.com/jsonapi?q=" + word.toLowerCase());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            // 设置请求头，模拟浏览器请求
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Accept", "application/json");
            
            // 获取响应状态码
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                // 解析JSON响应
                return parseYoudaoResponse(result.toString(), word);
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
     * 解析有道词典API的JSON响应
     * @param jsonResponse JSON响应字符串
     * @param originalWord 原始查询的单词
     * @return 格式化的单词解释
     */
    private String parseYoudaoResponse(String jsonResponse, String originalWord) {
        try {
            StringBuilder definition = new StringBuilder();
            
            // 添加查询单词
            definition.append("【").append(originalWord).append("】\n\n");
            
            // 提取音标
            if (jsonResponse.contains("\"uk-phonetic\"")) {
                int ukPhoneticIndex = jsonResponse.indexOf("\"uk-phonetic\"");
                if (ukPhoneticIndex > 0) {
                    int ukPhoneticStart = jsonResponse.indexOf("\"", ukPhoneticIndex + 14) + 1;
                    int ukPhoneticEnd = jsonResponse.indexOf("\"", ukPhoneticStart);
                    if (ukPhoneticStart > 0 && ukPhoneticEnd > ukPhoneticStart) {
                        String ukPhonetic = jsonResponse.substring(ukPhoneticStart, ukPhoneticEnd);
                        definition.append("英 [").append(ukPhonetic).append("]  ");
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
                        definition.append("美 [").append(usPhonetic).append("]\n\n");
                    }
                }
            }
            
            // 提取基本释义
            if (jsonResponse.contains("\"ec\"")) {
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
                            while (pos < trsJson.length() && count < 5) { // 最多显示5个释义
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
                        }
                    }
                }
            }
            
            // 提取网络释义
            if (jsonResponse.contains("\"web_trans\"")) {
                definition.append("\n网络释义:\n");
                
                int webTransIndex = jsonResponse.indexOf("\"web_trans\"");
                int webTranslationIndex = jsonResponse.indexOf("\"web-translation\"", webTransIndex);
                
                if (webTranslationIndex > 0) {
                    int entriesIndex = jsonResponse.indexOf("\"@entries\"", webTranslationIndex);
                    if (entriesIndex > 0) {
                        int entriesStart = jsonResponse.indexOf("[", entriesIndex);
                        int entriesEnd = findMatchingBracket(jsonResponse, entriesStart);
                        
                        if (entriesStart > 0 && entriesEnd > entriesStart) {
                            String entriesJson = jsonResponse.substring(entriesStart, entriesEnd + 1);
                            
                            // 解析网络释义
                            int pos = 0;
                            int count = 0;
                            while (pos < entriesJson.length() && count < 3) { // 最多显示3个网络释义
                                int valueIndex = entriesJson.indexOf("\"value\"", pos);
                                if (valueIndex < 0) break;
                                
                                int valueStart = entriesJson.indexOf("\"", valueIndex + 8) + 1;
                                int valueEnd = entriesJson.indexOf("\"", valueStart);
                                
                                if (valueStart > 0 && valueEnd > valueStart) {
                                    String webMeaning = entriesJson.substring(valueStart, valueEnd);
                                    definition.append("• ").append(webMeaning).append("\n");
                                    count++;
                                }
                                
                                pos = valueEnd + 1;
                            }
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
} 