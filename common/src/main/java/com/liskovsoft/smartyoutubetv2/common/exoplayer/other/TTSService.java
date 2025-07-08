package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TTS (Text-to-Speech) 服务
 * 负责调用Kokoro TTS API进行文字转语音
 */
public class TTSService {
    private static final String TAG = TTSService.class.getSimpleName();
    private static final String KOKORO_TTS_API_URL = "http://192.168.1.113:5066/v1/audio/speech";
    private static final String TTS_LANGUAGE = "英语";
    private static final String TTS_VOICE = "af_jessica";
    private static final String TTS_SPEED = "1";
    
    private final Context mContext;
    private final AudioManager mAudioManager;
    private MediaPlayer mMediaPlayer;
    private String mCurrentAudioFilePath;
    private String mLastSpokenWord;
    private int mOriginalVolume = -1;
    
    public TTSService(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    
    /**
     * 朗读单词
     */
    public void speakWord(String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        
        // 检查是否与上次朗读的单词相同，且音频文件还存在
        if (word.equals(mLastSpokenWord) && mCurrentAudioFilePath != null) {
            File audioFile = new File(mCurrentAudioFilePath);
            if (audioFile.exists()) {
                Log.d(TAG, "复用已有的音频文件: " + mCurrentAudioFilePath);
                playAudio(mCurrentAudioFilePath);
                return;
            }
        }
        
        // 记录当前要朗读的单词
        mLastSpokenWord = word;
        
        // 在后台线程中执行网络请求
        new Thread(() -> fetchAndPlayTTS(word)).start();
    }
    
    /**
     * 获取TTS音频并播放
     */
    private void fetchAndPlayTTS(String word) {
        HttpURLConnection connection = null;
        try {
            // 准备请求
            URL url = new URL(KOKORO_TTS_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "audio/wav");
            connection.setDoOutput(true);
            
            // 准备JSON请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("input", word);
            requestBody.put("language", TTS_LANGUAGE);
            requestBody.put("voice", TTS_VOICE);
            requestBody.put("speed", TTS_SPEED);
            
            // 发送请求
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.toString().getBytes("UTF-8"));
            }
            
            // 处理响应
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取音频数据到临时文件
                File tempFile = File.createTempFile("tts_", ".wav", mContext.getCacheDir());
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream fileOutputStream = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead);
                    }
                    // 播放音频
                    playAudio(tempFile.getAbsolutePath());
                }
            } else {
                Log.e(TAG, "TTS请求失败，响应码：" + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS请求异常：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 播放音频文件
     */
    private void playAudio(String filePath) {
        try {
            // 释放之前的MediaPlayer资源
            if (mMediaPlayer != null) {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
            }
            
            // 保存当前音频文件路径
            mCurrentAudioFilePath = filePath;
            
            // 保存当前系统音量
            if (mAudioManager != null && mOriginalVolume == -1) {
                mOriginalVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                Log.d(TAG, "保存原始音量: " + mOriginalVolume);
                
                // 获取最大音量
                int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                // 将音量设为最大音量的80%
                int newVolume = (int)(maxVolume * 0.8f);
                Log.d(TAG, "设置新音量: " + newVolume + " (最大: " + maxVolume + ")");
                
                // 临时提高系统音量
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
            }
            
            // 创建并设置新的MediaPlayer
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setDataSource(filePath);
            // 保持MediaPlayer音量为最大
            mMediaPlayer.setVolume(1.0f, 1.0f);
            
            // 设置准备完成和播放完成监听器
            mMediaPlayer.setOnPreparedListener(mp -> mp.start());
            mMediaPlayer.setOnCompletionListener(mp -> {
                // 播放完成后恢复原始音量
                if (mAudioManager != null && mOriginalVolume != -1) {
                    Log.d(TAG, "恢复原始音量: " + mOriginalVolume);
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
                    mOriginalVolume = -1;
                }
            });
            
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "播放音频失败：" + e.getMessage());
            // 发生错误时也要恢复音量
            if (mAudioManager != null && mOriginalVolume != -1) {
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
                mOriginalVolume = -1;
            }
        }
    }
    
    /**
     * 停止播放
     */
    public void stopPlaying() {
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
            mMediaPlayer.reset();
            
            // 恢复原始音量
            if (mAudioManager != null && mOriginalVolume != -1) {
                Log.d(TAG, "停止播放时恢复原始音量: " + mOriginalVolume);
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
                mOriginalVolume = -1;
            }
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
        // 删除当前的音频文件
        deleteCurrentAudioFile();
        
        // 恢复原始音量
        if (mAudioManager != null && mOriginalVolume != -1) {
            Log.d(TAG, "释放资源时恢复原始音量: " + mOriginalVolume);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
            mOriginalVolume = -1;
        }
        
        // 释放MediaPlayer资源
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "释放MediaPlayer时出错: " + e.getMessage());
            }
        }
    }
}