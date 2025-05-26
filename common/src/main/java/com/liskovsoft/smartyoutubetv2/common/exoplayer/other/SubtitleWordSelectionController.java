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
        
        // 分割当前字幕文本为单词
        splitSubtitleIntoWords();
        
        // 显示选词覆盖层
        showSelectionOverlay();
        
        // 高亮显示第一个单词
        highlightCurrentWord();
        
        MessageHelpers.showMessage(mContext, R.string.subtitle_word_selection_mode);
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
        
        // 退出选词模式
        mIsWordSelectionMode = false;
        
        // 恢复播放
        PlaybackView view = mPlaybackPresenter.getView();
        if (view != null) {
            view.setPlayWhenReady(true);
        }
        
        MessageHelpers.showMessage(mContext, R.string.subtitle_word_selection_exit);
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
                selectPreviousWord();
                return true;
                
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                selectNextWord();
                return true;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                translateCurrentWord();
                return true;
                
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                exitWordSelectionMode();
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
        
        // 获取最后一个字幕文本（通常是当前显示的）
        Cue lastCue = cues.get(cues.size() - 1);
        if (lastCue.text != null) {
            mCurrentSubtitleText = lastCue.text.toString();
            
            // 如果在选词模式，更新单词列表
            if (mIsWordSelectionMode) {
                splitSubtitleIntoWords();
                highlightCurrentWord();
            }
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
        
        // 分割文本为单词（按空格分割）
        String[] words = mCurrentSubtitleText.split("\\s+");
        
        // 过滤空单词
        List<String> wordList = new ArrayList<>();
        for (String word : words) {
            // 清理单词（去除标点符号等）
            word = word.replaceAll("[,.!?;:'\"]", "").trim();
            if (!word.isEmpty()) {
                wordList.add(word);
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
        
        // 更新选词覆盖层
        if (mSelectionOverlay != null) {
            mSelectionOverlay.setText(currentWord);
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
        
        // 显示翻译结果（这里只是一个示例，实际应该调用翻译API）
        MessageHelpers.showMessage(mContext, "翻译: " + currentWord);
        
        // TODO: 实现实际的翻译功能
    }
    
    /**
     * 创建选词覆盖层
     */
    private void createSelectionOverlay() {
        mSelectionOverlay = new TextView(mContext);
        mSelectionOverlay.setTextColor(Color.WHITE);
        mSelectionOverlay.setBackgroundColor(Color.argb(180, 0, 0, 0));
        mSelectionOverlay.setTextSize(24);
        mSelectionOverlay.setPadding(40, 20, 40, 20);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER;
        mSelectionOverlay.setLayoutParams(params);
        
        // 默认隐藏
        mSelectionOverlay.setVisibility(View.GONE);
    }
    
    /**
     * 显示选词覆盖层
     */
    private void showSelectionOverlay() {
        if (mSelectionOverlay.getParent() == null) {
            mRootView.addView(mSelectionOverlay);
        }
        mSelectionOverlay.setVisibility(View.VISIBLE);
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
} 