package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * TTS (Text-to-Speech) 服务
 * 负责文字转语音功能
 */
public class TTSService {
    private static final String TAG = TTSService.class.getSimpleName();
    
    private final Context mContext;
    private final AudioManager mAudioManager;
    private MediaPlayer mMediaPlayer;
    private TextToSpeech mTextToSpeech;
    private String mCurrentAudioFilePath;
    private String mLastSpokenWord;
    private int mOriginalVolume = -1;
    private boolean mIsInitialized = false;
    
    public TTSService(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initTTS();
    }
    
    /**
     * 初始化TTS引擎
     */
    private void initTTS() {
        try {
            mTextToSpeech = new TextToSpeech(mContext, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int result = mTextToSpeech.setLanguage(Locale.US);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "语言不支持");
                    } else {
                        mIsInitialized = true;
                        Log.d(TAG, "TTS初始化成功");
                    }
                } else {
                    Log.e(TAG, "TTS初始化失败");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "创建TTS引擎失败: " + e.getMessage());
        }
    }
    
    /**
     * 朗读单词
     */
    public void speakWord(String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        
        // 记录当前要朗读的单词
        mLastSpokenWord = word;
        
        // 使用TTS朗读
        if (mIsInitialized && mTextToSpeech != null) {
            // 保存当前系统音量
            if (mAudioManager != null && mOriginalVolume == -1) {
                mOriginalVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                
                // 获取最大音量
                int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                // 将音量设为最大音量的80%
                int newVolume = (int)(maxVolume * 0.8f);
                
                // 临时提高系统音量
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
            }
            
            mTextToSpeech.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word_id");
        } else {
            Log.e(TAG, "TTS引擎未初始化");
        }
    }
    
    /**
     * 停止播放
     */
    public void stopPlaying() {
        if (mTextToSpeech != null && mTextToSpeech.isSpeaking()) {
            mTextToSpeech.stop();
        }
        
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "停止MediaPlayer失败: " + e.getMessage());
            }
        }
            
            // 恢复原始音量
        restoreOriginalVolume();
    }
    
    /**
     * 恢复原始音量
     */
    private void restoreOriginalVolume() {
            if (mAudioManager != null && mOriginalVolume != -1) {
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
                mOriginalVolume = -1;
        }
    }
    
    /**
     * 删除当前音频文件
     */
    public void deleteCurrentAudioFile() {
        if (mCurrentAudioFilePath != null) {
            try {
                File audioFile = new File(mCurrentAudioFilePath);
                if (audioFile.exists()) {
                    boolean deleted = audioFile.delete();
                    if (deleted) {
                        Log.d(TAG, "成功删除音频文件: " + mCurrentAudioFilePath);
                    } else {
                        Log.e(TAG, "无法删除音频文件: " + mCurrentAudioFilePath);
                    }
                }
                mCurrentAudioFilePath = null;
                // 清除上次朗读的单词记录
                mLastSpokenWord = null;
            } catch (Exception e) {
                Log.e(TAG, "删除音频文件时出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        stopPlaying();
        deleteCurrentAudioFile();
        
        // 释放媒体播放器
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.release();
                mMediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "释放媒体播放器失败", e);
            }
        }
        
        // 释放其他资源，但不清空Context引用，因为它是final
        // 移除mHandler相关代码，因为该类中不存在此变量
        
        Log.d(TAG, "TTS服务资源已释放");
    }
}