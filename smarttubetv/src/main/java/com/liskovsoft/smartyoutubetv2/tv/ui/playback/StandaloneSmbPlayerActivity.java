package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.view.Gravity;
import android.widget.PopupMenu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.StandaloneSmbPlayerPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.StandaloneSmbPlayerView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleWordSelectionController;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * 独立的SMB视频播放器活动
 */
public class StandaloneSmbPlayerActivity extends FragmentActivity implements StandaloneSmbPlayerView {
    private static final String TAG = StandaloneSmbPlayerActivity.class.getSimpleName();
    private static final int AUTO_HIDE_DELAY_MS = 5000; // 5秒后自动隐藏UI
    
    // 定义快进快退常量
    private static final int[] SEEK_STEPS = {5000, 10000, 30000, 60000, 300000}; // 5秒，10秒，30秒，1分钟，5分钟
    private static final float SEEK_STEP_PERCENTAGE = 0.02f; // 视频长度的2%
    private static final int ACCELERATION_INTERVAL_MS = 0; // 设置为0，使用操作成功回调而不是固定时间间隔
    private int mCurrentSeekStepIndex = 0; // 默认使用5秒的跳转步长
    private boolean mSeekStepChanged = false; // 标记用户是否修改了跳转步长
    private boolean mSeekInProgress = false; // 表示当前是否有跳转操作正在进行中
    
    // 连续点击相关变量
    private static final int CONSECUTIVE_CLICK_TIMEOUT_MS = 800; // 连续点击的超时时间
    private long mLastSeekTime = 0; // 上次跳转的时间
    private long mAccumulatedSeekMs = 0; // 累积的跳转时间
    private boolean mIsForwardDirection = true; // 当前跳转方向
    private int mConsecutiveSeekCount = 0; // 连续点击次数
    
    // 长按加速相关变量
    private static final int LONG_PRESS_THRESHOLD_MS = 1000; // 长按阈值，1秒
    private static final int STEP_CHANGE_INTERVAL_MS = 5000; // 每5秒切换一次步进级别
    private boolean mIsLongPress = false; // 是否处于长按状态
    private long mLongPressStartTime = 0; // 长按开始时间
    private int mLongPressStepIndex = 0; // 长按时的步长索引（注意：长按时从索引1开始，即10秒步进）
    private int mLongPressRepeatCount = 0; // 长按时以相同速度重复的计数
    private Handler mLongPressHandler = new Handler(); // 长按处理器
    private Runnable mLongPressRunnable; // 长按任务
    private long mLastStepChangeTime = 0; // 上次步进级别变化的时间
    
    private StandaloneSmbPlayerPresenter mPresenter;
    private PlayerView mPlayerView;
    private ProgressBar mProgressBar;
    private TextView mErrorTextView;
    private TextView mTitleTextView;
    private LinearLayout mControlsContainer;
    private LinearLayout mTitleContainer;
    private TextView mPositionView;
    private TextView mDurationView;
    private SeekBar mSeekBar;
    private ImageButton mPlayPauseButton;
    private ImageButton mMuteButton; // 添加静音按钮引用
    private ImageButton mAutoSelectWordButton; // 添加自动选词按钮引用
    private FrameLayout mPlaybackSpeedContainer; // 更改为FrameLayout
    private ImageView mPlaybackSpeedIcon; // 添加播放速度图标
    private TextView mPlaybackSpeedText; // 添加播放速度文本
    private Handler mHandler;
    private boolean mIsUserSeeking;
    private boolean mControlsVisible = true; // 初始状态控制界面可见
    
    // 倍速播放相关
    private static final float[] PLAYBACK_SPEEDS = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 10.0f};
    private int mCurrentSpeedIndex = 3; // 默认索引为3，对应1.0x速度
    private long mLastClickTime = 0;
    private static final long CLICK_TIMEOUT = 500; // 长按判断阈值
    
    // 添加字幕管理器
    private com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager mSubtitleManager;
    
    // 添加静音状态记忆
    private boolean mIsMuted = false;
    private float mLastVolume = 1.0f; // 保存静音前的音量
    
    private final Runnable mHideUIRunnable = new Runnable() {
        @Override
        public void run() {
            android.util.Log.d("StandaloneSmbPlayerActivity", "执行自动隐藏控制栏");
            hideControls();
        }
    };

    // 字幕选词相关
    private SubtitleWordSelectionController mWordSelectionController;

    private float mLastSelectedSpeed = 2.0f; // 存储上次选择的非1x倍速

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_standalone_smb_player);

        mPresenter = StandaloneSmbPlayerPresenter.instance(this);
        mHandler = new Handler();
        
        initViews();
        initListeners();
        
        // 确保初始状态下控制栏可见
        mControlsVisible = true;
        android.util.Log.d("StandaloneSmbPlayerActivity", "onCreate: 初始化控制栏状态为可见, mControlsVisible=" + mControlsVisible);
        
        // 初始化控制栏自动隐藏
        mHandler.postDelayed(mHideUIRunnable, AUTO_HIDE_DELAY_MS);
        
        // 应用初始的静音设置
        if (mIsMuted && mPresenter != null) {
            mPresenter.setVolume(0);
        }
        
        // 初始化字幕选词控制器
        if (mPlayerView != null) {
            SubtitleView subtitleView = mPlayerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
            if (subtitleView != null && mPlayerView.getParent() instanceof FrameLayout) {
                FrameLayout rootView = (FrameLayout) mPlayerView.getParent();
                mWordSelectionController = SubtitleWordSelectionController.getInstance(this, subtitleView, rootView);
            }
        }
        
        // 初始化字幕管理器
        initSubtitleManager();
                    
        // 长按任务将在dispatchKeyEvent中动态创建
        mLongPressRunnable = null;
        
        mPresenter.setView(this);

        // 从Intent中获取视频数据
        Video video = getVideoFromIntent();
        if (video != null) {
            mPresenter.openVideo(video);
            mPresenter.startProgressUpdates();
            
            // 初始化播放按钮状态
            updatePlayPauseButton(true);
            
            // 初始化自动选词按钮状态
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            updateAutoSelectWordButton(playerData.isAutoSelectLastWordEnabled());
            
            // 初始化静音状态
            mIsMuted = playerData.isSmbPlayerMuted();
            updateMuteButton(mIsMuted);
            if (mIsMuted) {
                mLastVolume = mPresenter.getVolume();
                mPresenter.setVolume(0);
            }
            
            // 启动自动隐藏UI的定时器
            scheduleHideControls();
        } else {
            showError("未提供有效的视频数据");
        }
    }
    
    /**
     * 从启动Intent中提取视频数据
     */
    private Video getVideoFromIntent() {
        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            return null;
        }
        
        Video video = new Video();
        video.videoUrl = extras.getString("video_url");
        video.title = extras.getString("video_title", "未知标题");
        
        if (video.videoUrl == null || video.videoUrl.isEmpty()) {
            return null;
        }
        
        return video;
    }

    private void initViews() {
        mPlayerView = findViewById(R.id.player_view);
        mProgressBar = findViewById(R.id.progress_bar);
        mErrorTextView = findViewById(R.id.error_text);
        mTitleTextView = findViewById(R.id.video_title);
        mTitleContainer = findViewById(R.id.title_container);
        mControlsContainer = findViewById(R.id.controls_container);
        mPositionView = findViewById(R.id.position_view);
        mDurationView = findViewById(R.id.duration_view);
        mSeekBar = findViewById(R.id.seek_bar);
        mPlayPauseButton = findViewById(R.id.play_pause_button);
        mMuteButton = findViewById(R.id.mute_button);
        mAutoSelectWordButton = findViewById(R.id.auto_select_word_button); // 初始化自动选词按钮
        mPlaybackSpeedContainer = findViewById(R.id.playback_speed_container); // 更新引用
        mPlaybackSpeedIcon = findViewById(R.id.playback_speed_icon); // 添加图标引用
        mPlaybackSpeedText = findViewById(R.id.playback_speed_text); // 添加文本引用

        // 初始化静音按钮
        mMuteButton = findViewById(R.id.mute_button);
        if (mMuteButton != null) {
            // 加载保存的静音状态
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            mIsMuted = playerData.isSmbPlayerMuted();
            
            // 设置点击事件
            mMuteButton.setOnClickListener(v -> toggleMute());
            
            // 初始化按钮状态
            updateMuteButton(mIsMuted);
        }
        
        // 初始化倍速播放按钮
        if (mPlaybackSpeedContainer != null) {
            // 加载保存的倍速设置
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            float savedSpeed = playerData.getSmbPlayerSpeed();
            
            // 加载上次选择的非1x倍速设置
            mLastSelectedSpeed = playerData.getSmbPlayerLastSpeed();
            android.util.Log.d("SpeedToggle", "初始化视图 - 加载上次选择的非1x倍速: " + mLastSelectedSpeed);
            
            if (mLastSelectedSpeed <= 0.25f || Math.abs(mLastSelectedSpeed - 1.0f) < 0.001f) {
                // 确保mLastSelectedSpeed不为1.0x或过小值
                mLastSelectedSpeed = 2.0f; // 默认使用2.0x作为备选倍速
                android.util.Log.d("SpeedToggle", "上次选择的倍速无效，使用默认值: " + mLastSelectedSpeed);
                
                // 保存默认值
                playerData.setSmbPlayerLastSpeed(mLastSelectedSpeed);
            }
            
            // 查找对应的倍速索引
            mCurrentSpeedIndex = findSpeedIndex(savedSpeed);
            android.util.Log.d("SpeedToggle", "当前倍速索引: " + mCurrentSpeedIndex + ", 对应速度: " + PLAYBACK_SPEEDS[mCurrentSpeedIndex]);
            
            // 设置点击和长按事件
            setupPlaybackSpeedButton();
            
            // 更新按钮文本
            updatePlaybackSpeedButton();
            
            // 应用倍速设置
            if (mPresenter != null) {
                mPresenter.setSpeed(PLAYBACK_SPEEDS[mCurrentSpeedIndex]);
                android.util.Log.d("SpeedToggle", "已应用初始倍速: " + PLAYBACK_SPEEDS[mCurrentSpeedIndex]);
            }
        }
    }
    
    private void initListeners() {
        // 进度条拖动监听器
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mIsUserSeeking) {
                    // 更新当前位置显示
                    long positionMs = calculatePositionFromProgress(progress);
                    mPositionView.setText(formatTime(positionMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsUserSeeking = true;
                // 取消自动隐藏
                mHandler.removeCallbacks(mHideUIRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mIsUserSeeking) {
                    // 设置新的播放位置
                    long newPositionMs = calculatePositionFromProgress(seekBar.getProgress());
                    mPresenter.setPositionMs(newPositionMs);
                    
                    // 立即更新UI，确保进度条位置与实际播放位置同步
                    updatePosition(newPositionMs);
                    
                    mIsUserSeeking = false;
                    
                    // 重新启动自动隐藏
                    scheduleHideControls();
                }
            }
        });
        
        // 点击播放器区域显示/隐藏控制界面
        mPlayerView.setOnClickListener(v -> {
            if (mControlsVisible) {
                hideControls();
            } else {
                showControls();
                // 点击显示控制栏时，焦点设置在进度条上
                if (mSeekBar != null) {
                    mSeekBar.requestFocus();
                }
            }
        });
        
        // 播放/暂停按钮点击事件
        mPlayPauseButton.setOnClickListener(v -> {
            boolean isPlaying = isPlaying();
            play(!isPlaying);
            updatePlayPauseButton(!isPlaying);
            scheduleHideControls();
        });
        
        // 自动选词按钮点击事件
        mAutoSelectWordButton.setOnClickListener(v -> {
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            boolean isEnabled = playerData.isAutoSelectLastWordEnabled();
            
            // 切换状态
            playerData.enableAutoSelectLastWord(!isEnabled);
            
            // 更新按钮状态
            updateAutoSelectWordButton(!isEnabled);
            
            // 显示提示
            android.widget.Toast.makeText(this, 
                    "字幕结束时自动选择最后一个单词: " + (!isEnabled ? "开启" : "关闭"), 
                    android.widget.Toast.LENGTH_SHORT).show();
            
            // 重置控制栏自动隐藏计时器
            scheduleHideControls();
        });
    }
    
    /**
     * 根据进度条值计算播放位置（毫秒）
     */
    private long calculatePositionFromProgress(int progress) {
        long durationMs = mPresenter.getDurationMs();
        if (durationMs <= 0) {
            return 0;
        }
        
        // 使用整数计算避免浮点数精度问题
        // 进度条范围是0-1000
        return durationMs * progress / 1000;
    }
    
    /**
     * 根据播放位置计算进度条值（0-1000）
     */
    private int calculateProgressFromPosition(long positionMs) {
        long durationMs = mPresenter.getDurationMs();
        if (durationMs <= 0) {
            return 0;
        }
        
        // 使用整数计算，先乘后除避免精度损失
        // 进度条范围是0-1000
        return (int) (positionMs * 1000 / durationMs);
    }

    @Override
    public void openVideo(Video video) {
        mTitleTextView.setText(video.title);
        showError(null); // 清除任何之前的错误
    }

    @Override
    public void showError(String message) {
        if (message != null) {
            mErrorTextView.setText(message);
            mErrorTextView.setVisibility(View.VISIBLE);
            mPlayerView.setVisibility(View.GONE);
            
            // 确保错误信息显示时，控制界面也可见
            showControls();
        } else {
            mErrorTextView.setVisibility(View.GONE);
            mPlayerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showLoading(boolean show) {
        mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void updatePosition(long positionMs) {
        if (mPositionView != null && !mIsUserSeeking) {
            mPositionView.setText(formatTime(positionMs));
        
            // 如果没有手动调整进度条，则更新进度条
            if (mSeekBar != null) {
                int progress = calculateProgressFromPosition(positionMs);
                mSeekBar.setProgress(progress);
            }
        }
    }

    /**
     * 更新进度条位置
     */
    private void updateSeekBarForPosition(long positionMs) {
        if (mSeekBar != null) {
            int progress = calculateProgressFromPosition(positionMs);
            mSeekBar.setProgress(progress);
        }
    }

    @Override
    public void updateDuration(long durationMs) {
        // 格式化时间为 HH:MM:SS
        String formattedTime = formatTime(durationMs);
        mDurationView.setText(formattedTime);
        
        // 设置进度条最大值为1000，提高精度
        mSeekBar.setMax(1000);
    }

    @Override
    public boolean isPlaying() {
        return mPresenter.isPlaying();
    }

    @Override
    public void play(boolean play) {
        android.util.Log.d("StandaloneSmbPlayerActivity", "play方法被调用: " + (play ? "播放" : "暂停"));
        if (mPresenter != null) {
            try {
                // 直接操作ExoPlayer确保播放状态正确设置
                if (play) {
                    // 先检查播放器状态
                    if (mPresenter.isPlayerReady()) {
                        // 正常设置播放状态
                        mPresenter.setPlayWhenReady(true);
                        android.util.Log.d("StandaloneSmbPlayerActivity", "已设置播放器状态为: 播放");
                        
                        // 更新播放/暂停按钮状态
                        updatePlayPauseButton(true);
                        
                        // 添加验证检查确认播放状态已设置
                        mHandler.postDelayed(() -> {
                            if (!isPlaying() && mPresenter != null) {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "验证检查：播放状态未生效，尝试forcePlay()");
                                mPresenter.forcePlay();
                                updatePlayPauseButton(true);
                            } else {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "验证检查：播放状态已正确设置");
                            }
                        }, 100);
                    } else {
                        // 播放器未准备好，使用强制播放
                        android.util.Log.d("StandaloneSmbPlayerActivity", "播放器未准备好，尝试强制播放");
                        mPresenter.forcePlay();
                        updatePlayPauseButton(true);
                        
                        // 添加延迟检查，确保播放状态正确设置
                        mHandler.postDelayed(() -> {
                            if (!isPlaying() && mPresenter != null && mPresenter.hasExoPlayer()) {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "延迟检查：播放未恢复，再次尝试");
                                mPresenter.forcePlay();
                                updatePlayPauseButton(true);
                                
                                // 最后尝试直接访问播放器
                                if (mPlayerView != null && mPlayerView.getPlayer() != null) {
                                    android.util.Log.d("StandaloneSmbPlayerActivity", "最终尝试：直接设置PlayerView的播放状态");
                                    mPlayerView.getPlayer().setPlayWhenReady(true);
                                    updatePlayPauseButton(true);
                                }
                            }
                        }, 250);
                    }
                } else {
                    // 暂停播放
                    mPresenter.setPlayWhenReady(false);
                    updatePlayPauseButton(false);
                    android.util.Log.d("StandaloneSmbPlayerActivity", "已设置播放器状态为: 暂停");
                }
            } catch (Exception e) {
                android.util.Log.e("StandaloneSmbPlayerActivity", "设置播放状态失败", e);
                // 捕获异常后仍然尝试设置播放状态
                try {
                    if (play && mPlayerView != null && mPlayerView.getPlayer() != null) {
                        android.util.Log.d("StandaloneSmbPlayerActivity", "尝试恢复方案：直接设置PlayerView的播放状态");
                        mPlayerView.getPlayer().setPlayWhenReady(true);
                        updatePlayPauseButton(true);
                    }
                } catch (Exception ex) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "恢复方案也失败", ex);
                }
            }
        } else {
            android.util.Log.e("StandaloneSmbPlayerActivity", "无法设置播放状态：mPresenter为null");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPresenter = StandaloneSmbPlayerPresenter.instance(this);
        mPresenter.startProgressUpdates();
        
        // 在视图初始化完成后，连接字幕管理器和ExoPlayer
        if (mPresenter.hasExoPlayer() && mSubtitleManager != null) {
            android.util.Log.d(TAG, "onStart: 连接字幕管理器和ExoPlayer");
            mSubtitleManager.setPlayer(mPresenter.getExoPlayer());
            
            if (mPresenter.getExoPlayer().getTextComponent() != null) {
                // 确保ExoPlayer的TextComponent可以将字幕输出到字幕管理器
                mPresenter.getExoPlayer().getTextComponent().addTextOutput(mSubtitleManager);
                android.util.Log.d(TAG, "字幕管理器已连接到ExoPlayer的TextComponent");
            } else {
                android.util.Log.e(TAG, "ExoPlayer的TextComponent为null，无法设置字幕输出");
            }
        } else {
            android.util.Log.d(TAG, "onStart: ExoPlayer或字幕管理器尚未初始化");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPresenter.stopProgressUpdates();
        mHandler.removeCallbacks(mHideUIRunnable);
        cancelLongPress(); // 确保在Activity停止时取消长按任务
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        android.util.Log.d(TAG, "onDestroy: 清理资源...");
        
        // 释放字幕选词控制器资源
        SubtitleWordSelectionController.release();
        
        android.util.Log.d(TAG, "onDestroy: 资源清理完成");
        
        // 释放字幕管理器资源
        if (mSubtitleManager != null) {
            mSubtitleManager.release();
            mSubtitleManager = null;
        }
        
        // 取消所有延迟任务
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        
        // 释放播放器资源
        if (mPresenter != null) {
        mPresenter.releasePlayer();
        }
    }

    /**
     * 将毫秒格式化为 HH:MM:SS 字符串
     */
    private String formatTime(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBackward();
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekForward();
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play(true);
                updatePlayPauseButton(true);
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                play(false);
                updatePlayPauseButton(false);
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                boolean isPlaying = isPlaying();
                play(!isPlaying);
                updatePlayPauseButton(!isPlaying);
                return true;
        }
        
        return super.onKeyDown(keyCode, event);
    }
    
    /**
     * 检查当前是否有字幕文本
     */
    private boolean hasSubtitleText() {
        boolean result = mWordSelectionController != null && mWordSelectionController.hasSubtitleText();
        android.util.Log.d("StandaloneSmbPlayerActivity", "hasSubtitleText: " + result + 
                          ", mWordSelectionController=" + (mWordSelectionController != null ? "非空" : "为空"));
        return result;
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        
        // 如果控制栏可见，则优先处理控制栏相关按键操作
        if (mControlsVisible) {
            // 重置自动隐藏计时器
            scheduleHideControls();
            
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // 检查当前焦点是否在进度条下方的控件上
                        boolean isFocusOnBottomControls = false;
                        
                        if (mMuteButton != null && mMuteButton.isFocused()) {
                            isFocusOnBottomControls = true;
                        } else if (mAutoSelectWordButton != null && mAutoSelectWordButton.isFocused()) {
                            isFocusOnBottomControls = true;
                        } else if (mPlaybackSpeedContainer != null && mPlaybackSpeedContainer.isFocused()) {
                            isFocusOnBottomControls = true;
                        } else if (mPlayPauseButton != null && mPlayPauseButton.isFocused()) {
                            isFocusOnBottomControls = true;
                        }
                        
                        // 如果焦点在底部控件上，使用默认的焦点导航
                        if (isFocusOnBottomControls) {
                            return super.dispatchKeyEvent(event);
                        }
                        
                        // 否则，左右键用于快进/快退
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            seekBackward();
                        } else {
                            seekForward();
                        }
                        return true;
                        
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        // 上下键用于控制栏导航，不触发字幕相关功能
                        return super.dispatchKeyEvent(event);
                        
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        // 检查焦点是否在播放/暂停按钮上
                        if (mPlayPauseButton != null && mPlayPauseButton.isFocused()) {
                            boolean isPlaying = isPlaying();
                            play(!isPlaying);
                            updatePlayPauseButton(!isPlaying);
                            scheduleHideControls();
                            return true;
                        }
                        // 检查焦点是否在静音按钮上
                        else if (mMuteButton != null && mMuteButton.isFocused()) {
                            toggleMute();
                            scheduleHideControls();
                            return true;
                        }
                        // 检查焦点是否在自动选词按钮上
                        else if (mAutoSelectWordButton != null && mAutoSelectWordButton.isFocused()) {
                            // 切换自动选词状态
                            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
                            boolean isEnabled = playerData.isAutoSelectLastWordEnabled();
                            playerData.enableAutoSelectLastWord(!isEnabled);
                            updateAutoSelectWordButton(!isEnabled);
                            
                            // 显示提示
                            android.widget.Toast.makeText(this, 
                                    "字幕结束时自动选择最后一个单词: " + (!isEnabled ? "开启" : "关闭"), 
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return true;
                        }
                        // 检查焦点是否在播放速度容器上
                        else if (mPlaybackSpeedContainer != null && mPlaybackSpeedContainer.isFocused()) {
                            // 检测是否是长按
                            if (event.isLongPress()) {
                                showPlaybackSpeedMenu();
                                return true;
                            } else if (event.getRepeatCount() == 0) {
                                // 短按事件 - 在1.0x和上次设定的倍速之间切换
                                togglePlaybackSpeed();
                                
                                // 显示提示消息
                                float currentSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
                                showSpeedMessage(currentSpeed);
                                return true;
                            }
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                        
                    case KeyEvent.KEYCODE_BACK:
                        // 返回键隐藏控制栏
                        hideControls();
                        return true;
                        
                    case KeyEvent.KEYCODE_MENU:
                        // 菜单键切换自动选词状态，但不进入选词模式
                        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
                        boolean isEnabled = playerData.isAutoSelectLastWordEnabled();
                        playerData.enableAutoSelectLastWord(!isEnabled);
                        updateAutoSelectWordButton(!isEnabled);
                        
                        // 显示提示
                        android.widget.Toast.makeText(this, 
                                "字幕结束时自动选择最后一个单词: " + (!isEnabled ? "开启" : "关闭"), 
                                android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                }
            }
            
            // 控制栏可见时，不处理任何与字幕选词相关的操作
            return super.dispatchKeyEvent(event);
        }
        
        // 如果已经在选词模式，优先处理选词相关操作
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            // 特殊处理返回键，确保在自动选词模式下也能正确退出选词模式
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "选词模式下检测到返回键，尝试退出选词模式");
                
                // 检查是否有解释窗口
                if (mWordSelectionController.isShowingDefinition()) {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：关闭解释窗口");
                    mWordSelectionController.hideDefinitionOverlay();
                    return true;
                } else {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：退出选词模式");
                    
                    android.util.Log.d("StandaloneSmbPlayerActivity", "退出选词模式并恢复播放流程开始");
                    
                    try {
                        // 退出选词模式
                        mWordSelectionController.exitWordSelectionMode();
                        android.util.Log.d("StandaloneSmbPlayerActivity", "已成功退出选词模式");
                    } catch (Exception e) {
                        android.util.Log.e("StandaloneSmbPlayerActivity", "退出选词模式时出错", e);
                    }
                    
                    // 确保恢复播放
                    try {
                        // 第一级：直接调用play方法
                        android.util.Log.d("StandaloneSmbPlayerActivity", "第一级恢复：调用play(true)");
                        play(true);
                        
                        // 第二级：延迟250ms后检查播放状态，如果未播放则再次尝试
                        mHandler.postDelayed(() -> {
                            if (!isPlaying() && mPresenter != null) {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "第二级恢复：播放未恢复，调用forcePlay()");
                                mPresenter.forcePlay();
                            }
                        }, 250);
                    } catch (Exception e) {
                        android.util.Log.e("StandaloneSmbPlayerActivity", "恢复播放时出错", e);
                    }
                    return true;
                }
            }
            
            boolean handled = mWordSelectionController.handleKeyEvent(event);
            if (handled) {
                return true;
            }
            // 如果选词模式没有处理该事件，继续正常的事件分发
            return super.dispatchKeyEvent(event);
        }
        
        // 控制栏不可见且不在选词模式的情况下，处理常规按键事件
        
        // 处理按键释放事件
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // 处理左右键释放，取消长按状态
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cancelLongPress();
                
                // 处理字幕选词的长按事件
                if (event.isLongPress() && mWordSelectionController != null && 
                    mWordSelectionController.hasSubtitleText()) {
                    boolean fromStart = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT); // 左键从最后一个词开始，右键从第一个词开始
                    mWordSelectionController.enterWordSelectionMode(fromStart);
                    return true;
                }
            }
            
            // 菜单键处理
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                // 显示控制栏
                showControls();
                return true;
            }
        }
        
        // 处理按键按下事件
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 处理方向键和导航键
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // 如果控制栏不可见，按下方向键显示控制栏并将焦点设置到倍速按钮
                    if (!mControlsVisible) {
                        showControls();
                        // 焦点直接落在倍速按钮上
                        focusOnSpeedButton();
                        return true;
                    }
                    break;
                    
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // 如果控制栏不可见，按下确认键显示控制栏
                    if (!mControlsVisible) {
                        showControls();
                        return true;
                    }
                    break;
                    
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 如果控制栏不可见且没有字幕，快退
                    if (!mControlsVisible && 
                            (mWordSelectionController == null || !mWordSelectionController.hasSubtitleText())) {
                        seekBackward();
                        return true;
                    }
                    break;
                    
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 如果控制栏不可见且没有字幕，快进
                    if (!mControlsVisible && 
                            (mWordSelectionController == null || !mWordSelectionController.hasSubtitleText())) {
                        seekForward();
                        return true;
                    }
                    break;
            }
            
            // 左右键处理 - 根据不同状态有不同行为
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // 如果控制栏不可见且有字幕，则进入选词模式
                if (hasSubtitleText()) {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "控制栏不可见且有字幕，进入选词模式");
                    // 根据按键方向决定从哪个词开始选择
                    boolean fromStart = (keyCode != KeyEvent.KEYCODE_DPAD_LEFT); // 左键从最后一个词开始，右键从第一个词开始
                    enterWordSelectionMode(fromStart);
                    return true;
                }
                // 其他情况，不做特殊处理，也不显示控制栏
                return super.dispatchKeyEvent(event);
            }
            
            // 返回键特殊处理 - 直接传递给onKeyDown处理
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return onKeyDown(keyCode, event);
            }
            
            // 确认键处理 - 显示控制栏
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                showControls();
                return true;
            }
            
            // 上下键处理 - 显示控制栏
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                showControls();
                return true;
            }
            
            // 菜单键处理 - 显示控制栏
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showControls();
                return true;
            }
        }
        
        // 其他情况，使用默认处理
        return super.dispatchKeyEvent(event);
    }
    
    /**
     * 取消长按状态
     */
    private void cancelLongPress() {
        if (mIsLongPress) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "取消长按状态");
            mIsLongPress = false;
            mLongPressStepIndex = 0; // 重置步长索引
            mLastStepChangeTime = 0; // 重置步进变化时间
            
            // 移除长按处理器中的任何挂起任务
            mLongPressHandler.removeCallbacksAndMessages(null);
            
            // 重置跳转进度标志，以便可以立即执行新的操作
            mSeekInProgress = false;
        }
        
        // 即使不在长按状态，也移除所有挂起的任务
        if (mLongPressRunnable != null) {
            mLongPressHandler.removeCallbacks(mLongPressRunnable);
        }
    }
    
    /**
     * 使用指定步长的后退功能
     */
    private void seekBackwardWithStep(long stepMs) {
        try {
            // 如果有跳转操作正在进行中，忽略此次调用
            if (mSeekInProgress) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "上一个后退操作尚未完成，忽略此次操作");
                return;
            }
            
            // 标记跳转操作开始
            mSeekInProgress = true;
            
            long position = mPresenter.getPositionMs();
            long currentTime = System.currentTimeMillis();
            
            // 更新方向和时间戳
            mIsForwardDirection = false;
            mLastSeekTime = currentTime;
            
            // 计算新位置
            long newPosition = Math.max(0, position - stepMs);
            
            android.util.Log.d("StandaloneSmbPlayerActivity", "长按后退: 当前位置=" + position + 
                              "ms, 步长=" + stepMs + "ms, 新位置=" + newPosition + "ms");
            
            // 计算实际跳转时间（考虑边界情况）
            long actualJumpMs = position - newPosition;
            
            // 设置新位置
            mPresenter.setPositionMs(newPosition);
        
            // 验证跳转是否成功
            mHandler.postDelayed(() -> {
                long currentPos = mPresenter.getPositionMs();
                boolean isSuccessful = Math.abs(currentPos - newPosition) <= 1000; // 如果差异在1秒内视为成功
                
                if (!isSuccessful) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "后退操作验证失败: 期望位置=" + 
                                      newPosition + "ms, 实际位置=" + currentPos + "ms, 差异=" + 
                                      Math.abs(currentPos - newPosition) + "ms");
                    
                    // 再次尝试设置位置
                    mPresenter.setPositionMs(newPosition);
                    
                    // 延迟再次验证
                    mHandler.postDelayed(() -> {
                        long verifiedPos = mPresenter.getPositionMs();
                        // 无论成功与否，都更新UI，但记录日志
                        if (Math.abs(verifiedPos - newPosition) > 1000) {
                            android.util.Log.e("StandaloneSmbPlayerActivity", "后退操作二次验证仍然失败");
                        } else {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "后退操作二次验证成功");
                        }
                        
                        // 更新UI显示
                        updatePosition(verifiedPos);
                        updateSeekBarForPosition(verifiedPos);
                        
                        // 重置跳转进度标记
                        mSeekInProgress = false;
                        
                        // 如果仍处于长按状态，立即执行下一次操作
                        if (mIsLongPress) {
                            // 检查是否需要更新步进级别
                            checkAndUpdateSeekStepIndex();
                            
                            // 获取当前步长
                            long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                            
                            // 立即继续后退操作
                            seekBackwardWithStep(currentStepMs);
                        }
                    }, 100);
                } else {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "后退操作验证成功: 位置已正确设置");
                    
                    // 操作成功，更新UI显示
                    updatePosition(currentPos);
                    updateSeekBarForPosition(currentPos);
                    
                    // 重置跳转进度标记
                    mSeekInProgress = false;
                    
                    // 如果仍处于长按状态，立即执行下一次操作
                    if (mIsLongPress) {
                        // 检查是否需要更新步进级别
                        checkAndUpdateSeekStepIndex();
                        
                        // 获取当前步长
                        long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                        
                        // 立即继续后退操作
                        seekBackwardWithStep(currentStepMs);
                    }
                }
            }, 200);
        } catch (Exception e) {
            android.util.Log.e("StandaloneSmbPlayerActivity", "后退操作失败", e);
            MessageHelpers.showMessage(this, "后退操作失败: " + e.getMessage());
            mSeekInProgress = false; // 确保在发生异常时重置标志
        }
    }
    
    /**
     * 检查并更新步进索引
     */
    private void checkAndUpdateSeekStepIndex() {
        long currentTime = System.currentTimeMillis();
        long elapsedSinceLastChange = currentTime - mLastStepChangeTime;
        
        // 如果是首次长按或上次步进级别变化已经超过5秒
        if (mLastStepChangeTime == 0 || elapsedSinceLastChange >= STEP_CHANGE_INTERVAL_MS) {
            // 计算新的步进索引，确保不超过数组长度
            int newStepIndex = Math.min(mLongPressStepIndex + 1, SEEK_STEPS.length - 1);
            
            // 如果步进索引变化了，记录日志并更新时间戳
            if (newStepIndex != mLongPressStepIndex) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "步进级别变化: " + 
                                 mLongPressStepIndex + " -> " + newStepIndex + 
                                 ", 经过时间: " + (elapsedSinceLastChange / 1000) + "秒");
                mLongPressStepIndex = newStepIndex;
                mLastStepChangeTime = currentTime;
            }
        }
    }
    
    /**
     * 使用指定步长的前进功能
     */
    private void seekForwardWithStep(long stepMs) {
        try {
            // 如果有跳转操作正在进行中，忽略此次调用
            if (mSeekInProgress) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "上一个前进操作尚未完成，忽略此次操作");
                return;
            }
            
            // 标记跳转操作开始
            mSeekInProgress = true;
            
            long position = mPresenter.getPositionMs();
            long duration = mPresenter.getDurationMs();
            long currentTime = System.currentTimeMillis();
            
            // 更新方向和时间戳
            mIsForwardDirection = true;
            mLastSeekTime = currentTime;
            
            // 计算新位置
            long newPosition = Math.min(duration, position + stepMs);
            
            android.util.Log.d("StandaloneSmbPlayerActivity", "长按前进: 当前位置=" + position + 
                              "ms, 步长=" + stepMs + "ms, 新位置=" + newPosition + 
                              "ms, 总时长=" + duration + "ms");
            
            // 计算实际跳转时间（考虑边界情况）
            long actualJumpMs = newPosition - position;
            
            // 设置新位置
            mPresenter.setPositionMs(newPosition);
        
            // 验证跳转是否成功
            mHandler.postDelayed(() -> {
                long currentPos = mPresenter.getPositionMs();
                boolean isSuccessful = Math.abs(currentPos - newPosition) <= 1000; // 如果差异在1秒内视为成功
                
                if (!isSuccessful) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "前进操作验证失败: 期望位置=" + 
                                      newPosition + "ms, 实际位置=" + currentPos + "ms, 差异=" + 
                                      Math.abs(currentPos - newPosition) + "ms");
                    
                    // 再次尝试设置位置
                    mPresenter.setPositionMs(newPosition);
                    
                    // 延迟再次验证
                    mHandler.postDelayed(() -> {
                        long verifiedPos = mPresenter.getPositionMs();
                        // 无论成功与否，都更新UI，但记录日志
                        if (Math.abs(verifiedPos - newPosition) > 1000) {
                            android.util.Log.e("StandaloneSmbPlayerActivity", "前进操作二次验证仍然失败");
                        } else {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "前进操作二次验证成功");
                        }
                        
                        // 更新UI显示
                        updatePosition(verifiedPos);
                        updateSeekBarForPosition(verifiedPos);
                        
                        // 重置跳转进度标记
                        mSeekInProgress = false;
                        
                        // 如果仍处于长按状态，立即执行下一次操作
                        if (mIsLongPress) {
                            // 检查是否需要更新步进级别
                            checkAndUpdateSeekStepIndex();
                            
                            // 获取当前步长
                            long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                            
                            // 立即继续前进操作
                            seekForwardWithStep(currentStepMs);
                        }
                    }, 100);
                } else {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "前进操作验证成功: 位置已正确设置");
                    
                    // 操作成功，更新UI显示
                    updatePosition(currentPos);
                    updateSeekBarForPosition(currentPos);
                    
                    // 重置跳转进度标记
                    mSeekInProgress = false;
                    
                    // 如果仍处于长按状态，立即执行下一次操作
                    if (mIsLongPress) {
                        // 检查是否需要更新步进级别
                        checkAndUpdateSeekStepIndex();
                        
                        // 获取当前步长
                        long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                        
                        // 立即继续前进操作
                        seekForwardWithStep(currentStepMs);
                    }
                }
            }, 200);
        } catch (Exception e) {
            android.util.Log.e("StandaloneSmbPlayerActivity", "前进操作失败", e);
            MessageHelpers.showMessage(this, "前进操作失败: " + e.getMessage());
            mSeekInProgress = false; // 确保在发生异常时重置标志
        }
    }
    
    /**
     * 初始化字幕管理器
     */
    private void initSubtitleManager() {
        if (mSubtitleManager == null && mPlayerView != null) {
            SubtitleView subtitleView = mPlayerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
            if (subtitleView != null) {
                // 获取父视图作为根视图
                ViewGroup rootView = null;
                View parent = subtitleView.getParent() instanceof View ? (View) subtitleView.getParent() : null;
                while (parent != null) {
                    if (parent instanceof FrameLayout) {
                        rootView = (ViewGroup) parent;
                        break;
                    }
                    parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
                }
                
                // 如果找不到合适的根视图，尝试使用Activity的内容视图
                if (rootView == null) {
                    rootView = findViewById(android.R.id.content);
                }
                
                if (rootView instanceof FrameLayout) {
                    // 创建字幕管理器
                    mSubtitleManager = new com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager(subtitleView);
                    android.util.Log.d(TAG, "字幕管理器已创建，等待ExoPlayer初始化");
                    
                    // 初始化字幕选词控制器
                    mWordSelectionController = SubtitleWordSelectionController.getInstance(this, subtitleView, (FrameLayout) rootView);
                    android.util.Log.d(TAG, "字幕选词控制器初始化成功");
                } else {
                    android.util.Log.e(TAG, "无法找到合适的根视图，字幕选词控制器初始化失败");
                }
            }
        }
    }
    
    /**
     * 获取字幕管理器，实现StandaloneSmbPlayerView接口
     */
    @Override
    public com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager getSubtitleManager() {
        if (mSubtitleManager == null) {
            initSubtitleManager();
        }
        return mSubtitleManager;
    }
    
    /**
     * 设置播放状态
     */
    public void setPlayWhenReady(boolean play) {
        play(play);
    }

    /**
     * 更新播放/暂停按钮状态
     * @param isPlaying 是否正在播放
     */
    private void updatePlayPauseButton(boolean isPlaying) {
        if (mPlayPauseButton != null) {
            mPlayPauseButton.setImageResource(isPlaying ? 
                com.google.android.exoplayer2.ui.R.drawable.exo_controls_pause : 
                com.google.android.exoplayer2.ui.R.drawable.exo_controls_play);
        }
    }

    /**
     * 更新自动选词按钮状态
     */
    private void updateAutoSelectWordButton(boolean isEnabled) {
        if (mAutoSelectWordButton != null) {
            // 根据状态设置不同图标
            mAutoSelectWordButton.setImageResource(isEnabled ?
                android.R.drawable.ic_menu_edit :  // 使用编辑图标表示开启状态(更直观地表达"选词"功能)
                android.R.drawable.ic_menu_close_clear_cancel); // 使用取消图标表示关闭状态
                
            // 设置不同的背景色
            mAutoSelectWordButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                isEnabled ? 0xFF4CAF50 : 0xFFE57373)); // 启用时为绿色，禁用时为红色
        }
    }

    /**
     * 更新静音按钮状态
     */
    private void updateMuteButton(boolean isMuted) {
        if (mMuteButton != null) {
            // 根据静音状态设置不同的图标
            mMuteButton.setImageResource(isMuted ? 
                R.drawable.ic_volume_off : 
                R.drawable.ic_volume_up);
            
            // 设置不同的背景色
            mMuteButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                isMuted ? 0xFFE57373 : 0xFF4CAF50)); // 静音时为红色，正常时为绿色
        }
    }

    /**
     * 初始化字幕选词控制器
     * @param subtitleView 字幕视图
     * @param rootView 根视图
     */
    @Override
    public void initWordSelectionController(SubtitleView subtitleView, FrameLayout rootView) {
        if (subtitleView != null && rootView != null) {
            if (mWordSelectionController == null) {
                mWordSelectionController = SubtitleWordSelectionController.getInstance(this, subtitleView, rootView);
                android.util.Log.d(TAG, "字幕选词控制器通过接口方法初始化成功");
            } else {
                android.util.Log.d(TAG, "字幕选词控制器已存在，无需重新初始化");
            }
        } else {
            android.util.Log.e(TAG, "无法初始化字幕选词控制器：参数无效");
        }
    }
    
    /**
     * 进入字幕选词模式
     */
    private void enterWordSelectionMode(boolean fromStart) {
        // 如果控制栏可见，不进入选词模式
        if (mControlsVisible) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "控制栏可见，不进入选词模式");
            return;
        }
        
        // 如果没有字幕，不进入选词模式
        if (!hasSubtitleText()) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "没有字幕文本，不进入选词模式");
            return;
        }
        
        // 确保字幕选词控制器已初始化
        if (mWordSelectionController != null) {
            // 暂停播放
            play(false);
            
            // 进入选词模式
            mWordSelectionController.enterWordSelectionMode(fromStart);
            android.util.Log.d("StandaloneSmbPlayerActivity", "已进入选词模式，fromStart=" + fromStart);
        } else {
            android.util.Log.d("StandaloneSmbPlayerActivity", "字幕选词控制器未初始化，无法进入选词模式");
        }
    }
    
    /**
     * 获取播放器视图，实现StandaloneSmbPlayerView接口
     */
    @Override
    public PlayerView getPlayerView() {
        return mPlayerView;
    }
    
    @Override
    public void setPositionMs(long positionMs) {
        android.util.Log.d(TAG, "setPositionMs: 设置播放位置为 " + positionMs + "ms");
        if (mPresenter != null) {
            mPresenter.setPositionMs(positionMs);
            updatePosition(positionMs);
            updateSeekBarForPosition(positionMs);
        }
    }
    
    /**
     * 隐藏控制界面
     */
    private void hideControls() {
        if (mControlsContainer != null && mTitleContainer != null) {
        mControlsContainer.setVisibility(View.GONE);
            mTitleContainer.setVisibility(View.GONE);
        mControlsVisible = false;
        
            // 移除自动隐藏任务
            mHandler.removeCallbacks(mHideUIRunnable);
        }
    }
    
    /**
     * 显示控制界面
     */
    private void showControls() {
        if (mControlsContainer != null && mTitleContainer != null) {
            mControlsContainer.setVisibility(View.VISIBLE);
            mTitleContainer.setVisibility(View.VISIBLE);
            mControlsVisible = true;
            
            // 注意：不在此设置默认焦点，由调用方决定焦点
            
            // 安排自动隐藏
            scheduleHideControls();
        }
    }
    
    /**
     * 切换控制界面的可见性
     */
    private void toggleControlsVisibility() {
        if (mControlsVisible) {
            hideControls();
        } else {
            showControls();
            // 当通过点击播放器区域显示控制栏时，默认焦点在进度条上
            if (mSeekBar != null) {
                mSeekBar.requestFocus();
            }
        }
    }
    
    /**
     * 安排自动隐藏控制界面
     */
    private void scheduleHideControls() {
        // 先移除可能存在的隐藏任务
        mHandler.removeCallbacks(mHideUIRunnable);
        
        // 安排新的隐藏任务
        mHandler.postDelayed(mHideUIRunnable, AUTO_HIDE_DELAY_MS);
    }
    
    /**
     * 向后跳转
     */
    private void seekBackward() {
        if (mPresenter == null) {
            return;
        }
        
        // 如果控制栏可见，使用简单的跳转逻辑
        if (mControlsVisible) {
            long currentPositionMs = mPresenter.getPositionMs();
            long seekStepMs = SEEK_STEPS[0]; // 使用默认的5秒步长
            long newPositionMs = Math.max(0, currentPositionMs - seekStepMs);
            
            // 设置新位置
            mPresenter.setPositionMs(newPositionMs);
            
            // 更新UI
            updatePosition(newPositionMs);
            
            // 显示提示
            android.widget.Toast.makeText(this, 
                    "后退 " + (seekStepMs / 1000) + " 秒", 
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 控制栏不可见时，使用原有的复杂跳转逻辑
        long currentTimeMs = System.currentTimeMillis();
        
        // 检查是否是连续点击
        if (currentTimeMs - mLastSeekTime < CONSECUTIVE_CLICK_TIMEOUT_MS) {
            // 连续点击，累积跳转时间
            mConsecutiveSeekCount++;
            
            // 如果方向改变，重置累积时间
            if (mIsForwardDirection) {
                mAccumulatedSeekMs = 0;
                mIsForwardDirection = false;
            }
            
            // 根据连续点击次数增加步长
            checkAndUpdateSeekStepIndex();
        } else {
            // 非连续点击，重置状态
            mConsecutiveSeekCount = 1;
            mCurrentSeekStepIndex = 0; // 重置为默认步长
            mAccumulatedSeekMs = 0;
            mIsForwardDirection = false;
        }
        
        // 记录本次操作时间
        mLastSeekTime = currentTimeMs;
        
        // 获取当前步长
        long seekStepMs = SEEK_STEPS[mCurrentSeekStepIndex];
        
        // 累积跳转时间
        mAccumulatedSeekMs += seekStepMs;
        
        // 执行跳转
        seekBackwardWithStep(seekStepMs);
    }
    
    /**
     * 向前跳转
     */
    private void seekForward() {
        if (mPresenter == null) {
            return;
        }
        
        // 如果控制栏可见，使用简单的跳转逻辑
        if (mControlsVisible) {
            long currentPositionMs = mPresenter.getPositionMs();
            long seekStepMs = SEEK_STEPS[0]; // 使用默认的5秒步长
            long durationMs = mPresenter.getDurationMs();
            long newPositionMs = Math.min(durationMs, currentPositionMs + seekStepMs);
            
            // 设置新位置
            mPresenter.setPositionMs(newPositionMs);
            
            // 更新UI
            updatePosition(newPositionMs);
            
            // 显示提示
            android.widget.Toast.makeText(this, 
                    "前进 " + (seekStepMs / 1000) + " 秒", 
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 控制栏不可见时，使用原有的复杂跳转逻辑
        long currentTimeMs = System.currentTimeMillis();
        
        // 检查是否是连续点击
        if (currentTimeMs - mLastSeekTime < CONSECUTIVE_CLICK_TIMEOUT_MS) {
            // 连续点击，累积跳转时间
            mConsecutiveSeekCount++;
            
            // 如果方向改变，重置累积时间
            if (!mIsForwardDirection) {
                mAccumulatedSeekMs = 0;
                mIsForwardDirection = true;
            }
            
            // 根据连续点击次数增加步长
            checkAndUpdateSeekStepIndex();
        } else {
            // 非连续点击，重置状态
            mConsecutiveSeekCount = 1;
            mCurrentSeekStepIndex = 0; // 重置为默认步长
            mAccumulatedSeekMs = 0;
            mIsForwardDirection = true;
        }
        
        // 记录本次操作时间
        mLastSeekTime = currentTimeMs;
        
        // 获取当前步长
        long seekStepMs = SEEK_STEPS[mCurrentSeekStepIndex];
        
        // 累积跳转时间
        mAccumulatedSeekMs += seekStepMs;
        
        // 执行跳转
        seekForwardWithStep(seekStepMs);
    }

    /**
     * 切换静音状态
     */
    private void toggleMute() {
        mIsMuted = !mIsMuted;
        
        if (mIsMuted) {
            // 保存当前音量，然后设置为0
            mLastVolume = mPresenter.getVolume();
            mPresenter.setVolume(0);
        } else {
            // 恢复之前的音量
            mPresenter.setVolume(mLastVolume);
        }
        
        // 更新静音按钮状态
        updateMuteButton(mIsMuted);
        
        // 保存静音状态
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
        playerData.setSmbPlayerMuted(mIsMuted);
        
        // 显示提示
        android.widget.Toast.makeText(this, 
                "SMB播放器静音: " + (mIsMuted ? "开启" : "关闭"), 
                android.widget.Toast.LENGTH_SHORT).show();
                
        // 重置控制栏自动隐藏计时器
        scheduleHideControls();
    }

    /**
     * 显示音量消息
     */
    private void showVolumeMessage(int volumePercent) {
        com.liskovsoft.sharedutils.helpers.MessageHelpers.showMessage(this, 
                this.getString(com.liskovsoft.smartyoutubetv2.common.R.string.volume, volumePercent));
    }

    /**
     * 设置播放速度按钮的交互行为
     * 注意：不需要在此添加点击监听器，OK键点击事件通过dispatchKeyEvent处理
     */
    private void setupPlaybackSpeedButton() {
        if (mPlaybackSpeedContainer != null) {
            // 移除所有可能的点击监听器，只通过OK键控制
            mPlaybackSpeedContainer.setOnClickListener(null);
            mPlaybackSpeedContainer.setOnLongClickListener(null);
            
            // 确保按钮可以获得焦点
            mPlaybackSpeedContainer.setFocusable(true);
            mPlaybackSpeedContainer.setFocusableInTouchMode(true);
        }
    }
    
    /**
     * 切换播放速度
     */
    private void togglePlaybackSpeed() {
        android.util.Log.d("SpeedToggle", "开始切换播放速度");
        android.util.Log.d("SpeedToggle", "当前速度索引: " + mCurrentSpeedIndex + ", 对应速度: " + PLAYBACK_SPEEDS[mCurrentSpeedIndex]);
        android.util.Log.d("SpeedToggle", "上次选定速度: " + mLastSelectedSpeed);
        android.util.Log.d("SpeedToggle", "1x速度索引: " + findSpeedIndex(1.0f));
        
        // 获取1x速度的索引
        int normalSpeedIndex = findSpeedIndex(1.0f);
        
        // 在1.0x和上次设定的速度之间切换
        if (Math.abs(PLAYBACK_SPEEDS[mCurrentSpeedIndex] - 1.0f) < 0.001f) {
            // 当前是1x，切换到上次设定的倍速
            android.util.Log.d("SpeedToggle", "当前是1x，切换到上次设定的倍速: " + mLastSelectedSpeed);
            setPlaybackSpeed(mLastSelectedSpeed);
        } else {
            // 当前不是1x，保存这个速度，然后切换到1x
            android.util.Log.d("SpeedToggle", "当前不是1x，保存当前速度: " + PLAYBACK_SPEEDS[mCurrentSpeedIndex] + ", 然后切换到1x");
            mLastSelectedSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
            setPlaybackSpeed(1.0f);
            
            // 持久化保存上次选定的倍速
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            playerData.setSmbPlayerLastSpeed(mLastSelectedSpeed);
        }
        
        // 重置控制栏自动隐藏计时器
        scheduleHideControls();
    }
    
    /**
     * 设置特定的播放速度
     */
    private void setPlaybackSpeed(float speed) {
        android.util.Log.d("SpeedToggle", "设置播放速度: " + speed);
        
        // 找到最接近的预设速度索引
        mCurrentSpeedIndex = findSpeedIndex(speed);
        float actualSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
        
        android.util.Log.d("SpeedToggle", "实际设置的速度索引: " + mCurrentSpeedIndex + ", 对应速度: " + actualSpeed);
        
        // 应用新的播放速度
        if (mPresenter != null) {
            mPresenter.setSpeed(actualSpeed);
            android.util.Log.d("SpeedToggle", "已应用新的播放速度到播放器");
        } else {
            android.util.Log.e("SpeedToggle", "错误: mPresenter为null，无法设置播放速度");
        }
        
        // 保存播放速度设置
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
        playerData.setSmbPlayerSpeed(actualSpeed);
        android.util.Log.d("SpeedToggle", "已保存播放速度设置到PlayerData");
        
        // 显示当前播放速度提示
        android.widget.Toast.makeText(this, "播放速度: " + actualSpeed + "x", android.widget.Toast.LENGTH_SHORT).show();
        
        // 更新按钮显示
        updatePlaybackSpeedButton();
        android.util.Log.d("SpeedToggle", "已更新播放速度按钮显示");
    }
    
    /**
     * 获取最接近指定速度的预设速度索引
     */
    private int findSpeedIndex(float speed) {
        if (speed <= 0) {
            // 无效值，返回1.0x倍速的索引
            for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
                if (Math.abs(PLAYBACK_SPEEDS[i] - 1.0f) < 0.001f) {
                    return i;
                }
            }
            return 3; // 默认值，对应1.0x的索引
        }
        
        // 首先查找完全匹配的速度
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            if (Math.abs(PLAYBACK_SPEEDS[i] - speed) < 0.001f) {
                return i;
            }
        }
        
        // 如果没有完全匹配的，找最接近的
        int closestIndex = 0;
        float minDiff = Math.abs(PLAYBACK_SPEEDS[0] - speed);
        
        for (int i = 1; i < PLAYBACK_SPEEDS.length; i++) {
            float diff = Math.abs(PLAYBACK_SPEEDS[i] - speed);
            if (diff < minDiff) {
                minDiff = diff;
                closestIndex = i;
            }
        }
        
        return closestIndex;
    }
    
    /**
     * 循环切换预设播放速度（在长按菜单选择速度时使用）
     */
    private void cyclePlaybackSpeed() {
        mCurrentSpeedIndex = (mCurrentSpeedIndex + 1) % PLAYBACK_SPEEDS.length;
        float newSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
        
        // 如果不是1x速度，保存为上次选择的速度
        if (Math.abs(newSpeed - 1.0f) > 0.001f) {
            mLastSelectedSpeed = newSpeed;
        }
        
        // 应用新的播放速度
        if (mPresenter != null) {
            mPresenter.setSpeed(newSpeed);
        }
        
        // 保存播放速度设置
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
        playerData.setSmbPlayerSpeed(newSpeed);
        
        // 显示当前播放速度提示
        android.widget.Toast.makeText(this, "播放速度: " + newSpeed + "x", android.widget.Toast.LENGTH_SHORT).show();
        
        // 更新按钮显示
        updatePlaybackSpeedButton();
    }
    
    /**
     * 显示播放速度选择菜单
     */
    private void showPlaybackSpeedMenu() {
        // 创建弹出菜单
        PopupMenu popupMenu = new PopupMenu(this, mPlaybackSpeedContainer);
        android.view.Menu menu = popupMenu.getMenu();
        
        // 添加速度选项
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            float speed = PLAYBACK_SPEEDS[i];
            String label = speed + "x";
            menu.add(0, i, i, label).setChecked(i == mCurrentSpeedIndex);
        }
        
        // 设置菜单项点击监听器
        popupMenu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index >= 0 && index < PLAYBACK_SPEEDS.length) {
                mCurrentSpeedIndex = index;
                float newSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
                
                // 如果选择的不是1x速度，则保存为上次选定的速度
                if (Math.abs(newSpeed - 1.0f) > 0.001f) {
                    mLastSelectedSpeed = newSpeed;
                    // 持久化保存上次选定的倍速
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
                    playerData.setSmbPlayerLastSpeed(mLastSelectedSpeed);
                }
                
                // 应用新的播放速度
                applySpeed(newSpeed);
                
                // 更新按钮显示
                updatePlaybackSpeedButton();
                
                // 显示提示
                showSpeedMessage(newSpeed);
                
                // 保存设置
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
                playerData.setSmbPlayerSpeed(newSpeed);
                
                return true;
            }
            return false;
        });
        
        // 显示菜单
        popupMenu.show();
        
        // 重置控制栏自动隐藏计时器
        scheduleHideControls();
    }
    
    /**
     * 应用播放速度
     */
    private void applySpeed(float speed) {
        if (mPresenter != null) {
            mPresenter.setSpeed(speed);
        }
    }
    
    /**
     * 更新播放速度按钮显示
     */
    private void updatePlaybackSpeedButton() {
        if (mPlaybackSpeedContainer != null && mPlaybackSpeedIcon != null && mPlaybackSpeedText != null) {
            float currentSpeed = PLAYBACK_SPEEDS[mCurrentSpeedIndex];
            
            // 设置按钮描述
            mPlaybackSpeedContainer.setContentDescription("播放速度: " + currentSpeed + "x");
            
            // 根据速度设置不同的背景色
            int backgroundColor = getSpeedButtonColor(currentSpeed);
            mPlaybackSpeedContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(backgroundColor));
            
            // 更新显示内容
            if (Math.abs(currentSpeed - 1.0f) > 0.001f) {
                // 非标准速度时，显示速度值文本
                mPlaybackSpeedIcon.setVisibility(View.GONE);
                mPlaybackSpeedText.setVisibility(View.VISIBLE);
                
                // 格式化速度文本，避免小数点后太多位
                String speedText;
                if (currentSpeed == (int)currentSpeed) {
                    // 整数速度值，如2.0显示为2x
                    speedText = (int)currentSpeed + "x";
                } else {
                    // 非整数速度值，如1.5显示为1.5x
                    speedText = currentSpeed + "x";
                }
                mPlaybackSpeedText.setText(speedText);
            } else {
                // 标准速度时，显示默认图标
                mPlaybackSpeedIcon.setVisibility(View.VISIBLE);
                mPlaybackSpeedText.setVisibility(View.GONE);
            }
        }
    }
    
    /**
     * 根据速度获取按钮颜色
     */
    private int getSpeedButtonColor(float speed) {
        if (speed < 1.0f) {
            return 0xFFE57373; // 减速为红色
        } else if (speed > 1.0f) {
            return 0xFF4CAF50; // 加速为绿色
        } else {
            return 0xFF2196F3; // 正常速度为蓝色
        }
    }
    
    /**
     * 显示播放速度消息
     */
    private void showSpeedMessage(float speed) {
        // 格式化速度文本，避免小数点后太多位
        String speedText;
        if (speed == (int)speed) {
            // 整数速度值，如2.0显示为2x
            speedText = (int)speed + "x";
        } else {
            // 非整数速度值，如1.5显示为1.5x
            speedText = speed + "x";
        }
        
        com.liskovsoft.sharedutils.helpers.MessageHelpers.showMessage(this, 
                "播放速度: " + speedText);
    }

    /**
     * 初始化控制栏按钮
     */
    private void initControlsButtons() {
        // 初始化播放/暂停按钮
        if (mPlayPauseButton != null) {
            mPlayPauseButton.setOnClickListener(v -> {
                boolean isPlaying = isPlaying();
                play(!isPlaying);
                updatePlayPauseButton(!isPlaying);
                scheduleHideControls();
            });
        }
        
        // 初始化静音按钮
        if (mMuteButton != null) {
            mMuteButton.setOnClickListener(v -> {
                toggleMute();
                scheduleHideControls();
            });
        }
        
        // 初始化自动选词按钮
        if (mAutoSelectWordButton != null) {
            mAutoSelectWordButton.setOnClickListener(v -> {
                // 切换自动选词状态
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
                boolean isEnabled = playerData.isAutoSelectLastWordEnabled();
                playerData.enableAutoSelectLastWord(!isEnabled);
                updateAutoSelectWordButton(!isEnabled);
                
                // 显示提示
                android.widget.Toast.makeText(this, 
                        "字幕结束时自动选择最后一个单词: " + (!isEnabled ? "开启" : "关闭"), 
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        
        // 播放速度按钮只通过OK键操作，不设置点击监听器
        if (mPlaybackSpeedContainer != null) {
            // 确保按钮可以获得焦点
            mPlaybackSpeedContainer.setFocusable(true);
            mPlaybackSpeedContainer.setFocusableInTouchMode(true);
        }
    }

    /**
     * 设置焦点到倍速按钮
     */
    private void focusOnSpeedButton() {
        if (mPlaybackSpeedContainer != null) {
            mPlaybackSpeedContainer.requestFocus();
        }
    }

} 