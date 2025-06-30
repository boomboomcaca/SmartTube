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
    private Handler mHandler;
    private boolean mIsUserSeeking;
    private boolean mControlsVisible = true; // 初始状态控制界面可见
    
    // 添加字幕管理器
    private com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager mSubtitleManager;

    private final Runnable mHideUIRunnable = new Runnable() {
        @Override
        public void run() {
            android.util.Log.d("StandaloneSmbPlayerActivity", "执行自动隐藏控制栏");
            hideControls();
        }
    };

    // 字幕选词相关
    private SubtitleWordSelectionController mWordSelectionController;

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
        
        // 初始化字幕选词控制器
        if (mPlayerView != null) {
            SubtitleView subtitleView = mPlayerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
            if (subtitleView != null && mPlayerView.getParent() instanceof FrameLayout) {
                FrameLayout rootView = (FrameLayout) mPlayerView.getParent();
                mWordSelectionController = new SubtitleWordSelectionController(this, subtitleView, rootView);
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
            toggleControlsVisibility();
        });
        
        // 播放/暂停按钮点击事件
        mPlayPauseButton.setOnClickListener(v -> {
            boolean isPlaying = isPlaying();
            play(!isPlaying);
            updatePlayPauseButton(!isPlaying);
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
            
            // 如果正在进行快进/快退操作，显示当前步进级别信息
            if (mIsLongPress && mIsForwardDirection) {
                MessageHelpers.showMessage(this, "快进 " + (SEEK_STEPS[mLongPressStepIndex] / 1000) + " 秒");
            } else if (mIsLongPress && !mIsForwardDirection) {
                MessageHelpers.showMessage(this, "快退 " + (SEEK_STEPS[mLongPressStepIndex] / 1000) + " 秒");
            }
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
        
        // 释放字幕选词控制器资源
        if (mWordSelectionController != null) {
            mWordSelectionController.release();
            mWordSelectionController = null;
        }
        
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
        android.util.Log.d(TAG, "onKeyDown: keyCode=" + keyCode);
        
        // 如果是在字幕选词模式下，将事件传递给字幕选词控制器
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            return mWordSelectionController.handleKeyEvent(event);
        }
        
        // 左右方向键处理 - 前进/后退功能 - 这里需要最高优先级处理
        // 无论控制栏是否显示，左右键都用于视频跳转
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "左键：执行后退操作");
            seekBackward();
            // 如果控制栏不可见，则显示控制栏
            if (!mControlsVisible) {
                showControls();
            } else {
                // 控制栏已显示，则重置自动隐藏计时器
                scheduleHideControls();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "右键：执行前进操作");
            seekForward();
            // 如果控制栏不可见，则显示控制栏
            if (!mControlsVisible) {
                showControls();
            } else {
                // 控制栏已显示，则重置自动隐藏计时器
                scheduleHideControls();
            }
            return true;
        }
        
        // 返回键特殊处理
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 多级返回处理逻辑
            // 1. 如果有解释窗口打开，先关闭解释窗口
            // 2. 如果在选词模式，退出选词模式
            // 3. 如果控制栏可见，隐藏控制栏
            // 4. 以上都不符合时，直接退出视频播放
            
            // 检查是否有解释窗口
            if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode() && 
                    mWordSelectionController.isShowingDefinition()) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：关闭解释窗口");
                mWordSelectionController.hideDefinitionOverlay();
                return true;
            }
            
            // 检查是否在选词模式
            if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：退出选词模式");
                
                android.util.Log.d("StandaloneSmbPlayerActivity", "退出选词模式并恢复播放流程开始");
                
                // 退出选词模式前先暂存当前播放状态
                final boolean wasPlaying = true; // 退出选词模式总是恢复播放
                android.util.Log.d("StandaloneSmbPlayerActivity", "退出选词模式后将恢复播放");
                
                try {
                    // 退出选词模式
                    mWordSelectionController.exitWordSelectionMode();
                    android.util.Log.d("StandaloneSmbPlayerActivity", "已成功退出选词模式");
                } catch (Exception e) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "退出选词模式时出错", e);
                }
                
                // 确保恢复播放 - 使用多级保障机制确保播放恢复
                try {
                    // 第一级：直接调用play方法
                    android.util.Log.d("StandaloneSmbPlayerActivity", "第一级恢复：调用play(true)");
                    play(true);
                    
                    // 第二级：延迟250ms后检查播放状态，如果未播放则再次尝试
                    mHandler.postDelayed(() -> {
                        if (!isPlaying() && mPresenter != null) {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "第二级恢复：播放未恢复，调用forcePlay()");
                            mPresenter.forcePlay();
                            
                            // 第三级：再次延迟检查
                            mHandler.postDelayed(() -> {
                                if (!isPlaying() && mPresenter != null) {
                                    android.util.Log.d("StandaloneSmbPlayerActivity", "第三级恢复：播放仍未恢复，再次尝试");
                                    try {
                                        // 尝试重新初始化播放器
                                        if (mPlayerView != null && mPlayerView.getPlayer() != null) {
                                            android.util.Log.d("StandaloneSmbPlayerActivity", "尝试直接操作PlayerView");
                                            mPlayerView.getPlayer().setPlayWhenReady(true);
                                        }
                                    } catch (Exception e) {
                                        android.util.Log.e("StandaloneSmbPlayerActivity", "第三级恢复失败", e);
                                    }
                                }
                            }, 250);
                        }
                    }, 250);
                } catch (Exception e) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "恢复播放时出错", e);
                }
                return true;
            }
            
            // 检查控制栏是否可见
            if (mControlsVisible) {
                android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：控制栏可见(mControlsVisible=" + mControlsVisible + ")，隐藏控制栏");
                hideControls();
                android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：控制栏已隐藏，当前状态mControlsVisible=" + mControlsVisible);
                mHandler.removeCallbacks(mHideUIRunnable);
                return true;
            }
            
            // 直接退出视频播放
            android.util.Log.d("StandaloneSmbPlayerActivity", "返回键：退出视频播放");
            finish();
            return true;
        }
        
        // 记录长按事件
        if (event.isLongPress()) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "检测到长按事件: keyCode=" + keyCode);
        }
        
        // 控制栏已显示时的其他按键处理
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play(true);
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                play(false);
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                play(!isPlaying());
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekForward();
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBackward();
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                play(!isPlaying());
                scheduleHideControls();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // 按下菜单键显示设置菜单
                showSettingsMenu();
                return true;
        }
        
        // 对于其他所有按键，重置自动隐藏计时器
        scheduleHideControls();
        
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
        // 首先检查是否在选词模式，优先处理
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            boolean handled = mWordSelectionController.handleKeyEvent(event);
            if (handled) {
                return true;
            }
            // 如果选词模式没有处理该事件，继续正常的事件分发
            return super.dispatchKeyEvent(event);
        }
        
        // 左右方向键的处理需要最高优先级，无论控制栏是否显示
        int keyCode = event.getKeyCode();
        
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
                showSettingsMenu();
                return true;
            }
            
            // 对于任何按键释放事件，都重置计时器
            if (mControlsVisible) {
                scheduleHideControls();
            }
        }
        
        // 处理按键按下事件
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 左右键处理 - 根据不同状态有不同行为
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // 如果控制栏可见，执行前进/后退功能
                if (mControlsVisible) {
                    // 检查是否是重复事件（按键长按）
                    if (event.getRepeatCount() == 0) {
                        // 首次按下，先取消可能存在的之前的长按任务
                        cancelLongPress();
                        
                        // 记录时间
                        mLongPressStartTime = System.currentTimeMillis();
                        mIsLongPress = false;
                        mLongPressStepIndex = 0; // 重置步长索引
                        
                        // 执行正常的前进后退
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "dispatchKeyEvent: 左键按下，执行后退操作");
                            seekBackward();
                        } else { // KEYCODE_DPAD_RIGHT
                            android.util.Log.d("StandaloneSmbPlayerActivity", "dispatchKeyEvent: 右键按下，执行前进操作");
                            seekForward();
                        }
                        
                        // 创建长按任务，检测长按状态
                        final boolean isForward = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
                        mLongPressRunnable = new Runnable() {
                            @Override
                            public void run() {
                                // 只在达到长按阈值时触发一次
                                if (System.currentTimeMillis() - mLongPressStartTime > LONG_PRESS_THRESHOLD_MS) {
                                    // 检查是否有字幕文本且设置了自动选词功能，如果有则进入选词模式
                                    if (mWordSelectionController != null && mWordSelectionController.hasSubtitleText() &&
                                        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(
                                            StandaloneSmbPlayerActivity.this).isAutoSelectLastWordEnabled()) {
                                        android.util.Log.d("StandaloneSmbPlayerActivity", "检测到长按且有字幕，进入选词模式");
                                        mIsLongPress = false; // 防止后续操作
                                        // 需要使用post延迟执行，否则可能干扰当前按键处理
                                        mHandler.post(() -> {
                                            boolean fromStart = isForward; // 左键从最后一个词开始，右键从第一个词开始
                                            mWordSelectionController.enterWordSelectionMode(fromStart);
                                        });
                                        return;
                                    }
                                    
                                    // 否则进入常规长按快进/快退
                                    mIsLongPress = true;
                                    mIsForwardDirection = isForward; // 设置当前方向
                                    
                                    android.util.Log.d("StandaloneSmbPlayerActivity", "长按触发: " + 
                                                    (isForward ? "前进" : "后退"));
                                    
                                    // 使用10秒步进开始
                                    mLongPressStepIndex = 1; // 从10秒步进开始（索引1对应10000ms）
                                    
                                    // 获取当前步长
                                    long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                                    
                                    // 执行首次前进后退操作
                                    // 后续操作会在操作完成后的回调中自动连续执行
                                    if (isForward) {
                                        seekForwardWithStep(currentStepMs);
                                    } else {
                                        seekBackwardWithStep(currentStepMs);
                                    }
                                }
                            }
                        };
                        
                        // 启动长按检测
                        mLongPressHandler.postDelayed(mLongPressRunnable, LONG_PRESS_THRESHOLD_MS);
                    }
                    
                    // 重置自动隐藏计时器
                    scheduleHideControls();
                    return true;
                }
                // 如果控制栏不可见且有字幕，则进入选词模式
                else if (hasSubtitleText()) {
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
                return super.dispatchKeyEvent(event);
            }
            
            // 上下按键和OK按键特殊处理 - 显示控制栏
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || 
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                
                android.util.Log.d("StandaloneSmbPlayerActivity", "上下/OK按键按下，显示控制栏");
                
                // 如果控制栏不可见，则显示控制栏
                if (!mControlsVisible) {
                    showControls();
                    return true;
                }
            }
            
            // 对于其他按键，如果控制栏可见，则重置自动隐藏计时器
            if (mControlsVisible) {
                scheduleHideControls();
            }
            
            // 不再自动显示控制栏
            // 让事件继续传递
        }

        // 继续正常的事件分发
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
            
            // 移除长按处理器中的任何挂起任务
            mLongPressHandler.removeCallbacksAndMessages(null);
            
            // 重置跳转进度标志，以便可以立即执行新的操作
            mSeekInProgress = false;
            
            // 注意：由于现在使用回调机制，
            // 设置mIsLongPress为false后
            // 在下一次seek操作完成后，将不会继续执行
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
                        
                        // 如果仍处于长按状态，延迟5秒后再次执行后退操作
                        if (mIsLongPress) {
                            // 获取当前经过的时间
                            long elapsedTime = System.currentTimeMillis() - mLongPressStartTime;
                            
                            // 计算应该使用的步进索引（每个步进使用5秒）
                            int stepIndex = Math.min((int)(elapsedTime / STEP_CHANGE_INTERVAL_MS), SEEK_STEPS.length - 1);
                            
                            // 如果步进索引变化了，记录日志
                            if (stepIndex != mLongPressStepIndex) {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "步进级别变化: " + 
                                                 mLongPressStepIndex + " -> " + stepIndex + 
                                                 ", 经过时间: " + (elapsedTime / 1000) + "秒");
                                mLongPressStepIndex = stepIndex;
                            }
                            
                            // 等待5秒后再执行下一次操作
                            mHandler.postDelayed(() -> {
                                // 检查是否仍处于长按状态
                                if (mIsLongPress) {
                                    // 获取当前步长
                                    long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                                    // 继续后退操作
                                    seekBackwardWithStep(currentStepMs);
                                }
                            }, STEP_CHANGE_INTERVAL_MS);
                        }
                    }, 100);
                } else {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "后退操作验证成功: 位置已正确设置");
                    
                    // 操作成功，更新UI显示
                    updatePosition(currentPos);
                    updateSeekBarForPosition(currentPos);
                    
                    // 重置跳转进度标记
                    mSeekInProgress = false;
                    
                    // 如果仍处于长按状态，延迟5秒后再次执行后退操作
                    if (mIsLongPress) {
                        // 获取当前经过的时间
                        long elapsedTime = System.currentTimeMillis() - mLongPressStartTime;
                        
                        // 计算应该使用的步进索引（每个步进使用5秒）
                        int stepIndex = Math.min((int)(elapsedTime / STEP_CHANGE_INTERVAL_MS), SEEK_STEPS.length - 1);
                        
                        // 如果步进索引变化了，记录日志
                        if (stepIndex != mLongPressStepIndex) {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "步进级别变化: " + 
                                             mLongPressStepIndex + " -> " + stepIndex + 
                                             ", 经过时间: " + (elapsedTime / 1000) + "秒");
                            mLongPressStepIndex = stepIndex;
                        }
                        
                        // 等待5秒后再执行下一次操作
                        mHandler.postDelayed(() -> {
                            // 检查是否仍处于长按状态
                            if (mIsLongPress) {
                                // 获取当前步长
                                long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                                // 继续后退操作
                                seekBackwardWithStep(currentStepMs);
                            }
                        }, STEP_CHANGE_INTERVAL_MS);
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
                        
                        // 如果仍处于长按状态，延迟5秒后再次执行前进操作
                        if (mIsLongPress) {
                            // 获取当前经过的时间
                            long elapsedTime = System.currentTimeMillis() - mLongPressStartTime;
                            
                            // 计算应该使用的步进索引（每个步进使用5秒）
                            int stepIndex = Math.min((int)(elapsedTime / STEP_CHANGE_INTERVAL_MS), SEEK_STEPS.length - 1);
                            
                            // 如果步进索引变化了，记录日志
                            if (stepIndex != mLongPressStepIndex) {
                                android.util.Log.d("StandaloneSmbPlayerActivity", "步进级别变化: " + 
                                                 mLongPressStepIndex + " -> " + stepIndex + 
                                                 ", 经过时间: " + (elapsedTime / 1000) + "秒");
                                mLongPressStepIndex = stepIndex;
                            }
                            
                            // 等待5秒后再执行下一次操作
                            mHandler.postDelayed(() -> {
                                // 检查是否仍处于长按状态
                                if (mIsLongPress) {
                                    // 获取当前步长
                                    long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                                    // 继续前进操作
                                    seekForwardWithStep(currentStepMs);
                                }
                            }, STEP_CHANGE_INTERVAL_MS);
                        }
                    }, 100);
                } else {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "前进操作验证成功: 位置已正确设置");
                    
                    // 操作成功，更新UI显示
                    updatePosition(currentPos);
                    updateSeekBarForPosition(currentPos);
                    
                    // 重置跳转进度标记
                    mSeekInProgress = false;
                    
                    // 如果仍处于长按状态，延迟5秒后再次执行前进操作
                    if (mIsLongPress) {
                        // 获取当前经过的时间
                        long elapsedTime = System.currentTimeMillis() - mLongPressStartTime;
                        
                        // 计算应该使用的步进索引（每个步进使用5秒）
                        int stepIndex = Math.min((int)(elapsedTime / STEP_CHANGE_INTERVAL_MS), SEEK_STEPS.length - 1);
                        
                        // 如果步进索引变化了，记录日志
                        if (stepIndex != mLongPressStepIndex) {
                            android.util.Log.d("StandaloneSmbPlayerActivity", "步进级别变化: " + 
                                             mLongPressStepIndex + " -> " + stepIndex + 
                                             ", 经过时间: " + (elapsedTime / 1000) + "秒");
                            mLongPressStepIndex = stepIndex;
                        }
                        
                        // 等待5秒后再执行下一次操作
                        mHandler.postDelayed(() -> {
                            // 检查是否仍处于长按状态
                            if (mIsLongPress) {
                                // 获取当前步长
                                long currentStepMs = SEEK_STEPS[mLongPressStepIndex];
                                // 继续前进操作
                                seekForwardWithStep(currentStepMs);
                            }
                        }, STEP_CHANGE_INTERVAL_MS);
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
                    mWordSelectionController = new SubtitleWordSelectionController(this, subtitleView, (FrameLayout) rootView);
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
     * 显示SMB播放器设置菜单
     */
    private void showSettingsMenu() {
        android.util.Log.d("StandaloneSmbPlayerActivity", "显示设置菜单");
        
        // 创建对话框构建器
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("SMB播放器设置");
        
        // 创建设置项
        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        
        // 添加"字幕结束时自动选择最后一个单词"开关
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData = 
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
        boolean isAutoSelectLastWordEnabled = playerData.isAutoSelectLastWordEnabled();
        items.add("字幕结束时自动选择最后一个单词: " + (isAutoSelectLastWordEnabled ? "开启" : "关闭"));
        actions.add(() -> {
            // 切换开关状态
            playerData.enableAutoSelectLastWord(!isAutoSelectLastWordEnabled);
            // 显示提示
            android.widget.Toast.makeText(this, 
                    "字幕结束时自动选择最后一个单词: " + (!isAutoSelectLastWordEnabled ? "开启" : "关闭"), 
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        
        // 设置点击监听器
        builder.setItems(items.toArray(new String[0]), (dialog, which) -> {
            // 执行选择的操作
            if (which >= 0 && which < actions.size()) {
                actions.get(which).run();
            }
            dialog.dismiss();
        });
        
        // 添加取消按钮
        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        
        // 显示对话框
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
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
                mWordSelectionController = new SubtitleWordSelectionController(this, subtitleView, rootView);
                android.util.Log.d(TAG, "字幕选词控制器通过接口方法初始化成功");
            } else {
                android.util.Log.d(TAG, "字幕选词控制器已存在，无需重新初始化");
            }
        } else {
            android.util.Log.e(TAG, "无法初始化字幕选词控制器：参数无效");
        }
    }

    /**
     * 手动触发进入选词模式
     * 可以通过菜单或其他UI元素调用此方法
     */
    private void enterWordSelectionMode(boolean fromStart) {
        android.util.Log.d("StandaloneSmbPlayerActivity", "手动触发进入选词模式: fromStart=" + fromStart);
        
        if (mWordSelectionController != null) {
            mWordSelectionController.enterWordSelectionMode(fromStart);
        } else {
            android.util.Log.e("StandaloneSmbPlayerActivity", "无法进入选词模式: 字幕选词控制器为null");
            
            // 尝试初始化字幕选词控制器
            if (mPlayerView != null) {
                SubtitleView subtitleView = mPlayerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
                if (subtitleView != null && mPlayerView.getParent() instanceof FrameLayout) {
                    FrameLayout rootView = (FrameLayout) mPlayerView.getParent();
                    mWordSelectionController = new SubtitleWordSelectionController(this, subtitleView, rootView);
                    
                    // 再次尝试进入选词模式
                    if (mWordSelectionController != null) {
                        mWordSelectionController.enterWordSelectionMode(fromStart);
                    }
                }
            }
        }
    }

    /**
     * 获取播放器视图，实现StandaloneSmbPlayerView接口
     */
    @Override
    public PlayerView getPlayerView() {
        return mPlayerView;
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
     * 执行后退操作
     */
    private void seekBackward() {
        if (mSeekInProgress) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "跳转操作正在进行中，忽略此次操作");
            return;
        }
        
        long position = mPresenter.getPositionMs();
        long stepMs = SEEK_STEPS[mCurrentSeekStepIndex];
        long newPosition = Math.max(0, position - stepMs);
        
        android.util.Log.d("StandaloneSmbPlayerActivity", "后退: 当前位置=" + position + 
                          "ms, 步长=" + stepMs + "ms, 新位置=" + newPosition + "ms");
        
        mPresenter.setPositionMs(newPosition);
        updatePosition(newPosition);
        updateSeekBarForPosition(newPosition);
        
        // 显示后退消息
        MessageHelpers.showMessage(this, "后退 " + (stepMs / 1000) + " 秒");
    }
    
    /**
     * 执行前进操作
     */
    private void seekForward() {
        if (mSeekInProgress) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "跳转操作正在进行中，忽略此次操作");
            return;
        }
        
        long position = mPresenter.getPositionMs();
        long duration = mPresenter.getDurationMs();
        long stepMs = SEEK_STEPS[mCurrentSeekStepIndex];
        long newPosition = Math.min(duration, position + stepMs);
        
        android.util.Log.d("StandaloneSmbPlayerActivity", "前进: 当前位置=" + position + 
                          "ms, 步长=" + stepMs + "ms, 新位置=" + newPosition + 
                          "ms, 总时长=" + duration + "ms");
        
        mPresenter.setPositionMs(newPosition);
        updatePosition(newPosition);
        updateSeekBarForPosition(newPosition);
        
        // 显示前进消息
        MessageHelpers.showMessage(this, "前进 " + (stepMs / 1000) + " 秒");
    }
} 