package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

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
    private static final int AUTO_HIDE_DELAY_MS = 3000; // 3秒后自动隐藏UI
    
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
    private Handler mHandler;
    private boolean mIsUserSeeking;
    private boolean mControlsVisible = true;
    
    private final Runnable mHideUIRunnable = new Runnable() {
        @Override
        public void run() {
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
        
        // 尝试初始化字幕选词控制器
        if (mPlayerView != null) {
            mPlayerView.post(() -> {
                try {
                    SubtitleView subtitleView = mPlayerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
                    if (subtitleView != null && mPlayerView.getParent() instanceof FrameLayout) {
                        FrameLayout rootView = (FrameLayout) mPlayerView.getParent();
                        initWordSelectionController(subtitleView, rootView);
                        android.util.Log.d("StandaloneSmbPlayerActivity", "onCreate中初始化字幕选词控制器成功");
                    } else {
                        android.util.Log.e("StandaloneSmbPlayerActivity", "onCreate中无法初始化字幕选词控制器: " +
                                "subtitleView=" + (subtitleView != null ? "有效" : "null") + 
                                ", rootView=" + (mPlayerView.getParent() instanceof FrameLayout ? "有效" : "无效类型"));
                    }
                } catch (Exception e) {
                    android.util.Log.e("StandaloneSmbPlayerActivity", "onCreate中初始化字幕选词控制器失败", e);
                }
            });
        }
        
        mPresenter.setView(this);

        // 从Intent中获取视频数据
        Video video = getVideoFromIntent();
        if (video != null) {
            mPresenter.openVideo(video);
            mPresenter.startProgressUpdates();
            
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
        // 格式化时间为 HH:MM:SS
        String formattedTime = formatTime(positionMs);
        mPositionView.setText(formattedTime);
        
        // 更新进度条（只有在用户不拖动时）
        if (!mIsUserSeeking) {
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
        mPresenter.setPlayWhenReady(play);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPresenter.startProgressUpdates();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPresenter.stopProgressUpdates();
        mHandler.removeCallbacks(mHideUIRunnable);
    }

    @Override
    protected void onDestroy() {
        if (mWordSelectionController != null) {
            mWordSelectionController.release();
            mWordSelectionController = null;
        }
        mPresenter.releasePlayer();
        super.onDestroy();
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
        // 首先检查是否在字幕选词模式下
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            return mWordSelectionController.handleKeyEvent(event);
        }
        
        // 任何按键操作都会显示控制界面
        showControls();
        
        // 添加日志输出，追踪长按事件
        if (event.isLongPress()) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "检测到长按事件: keyCode=" + keyCode);
        }
        
        // 处理媒体控制键
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                play(false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                play(!isPlaying());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                play(!isPlaying());
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 检查是否有字幕文本，有则进入选词模式，否则执行快退
                android.util.Log.d("StandaloneSmbPlayerActivity", "按下左键，检查是否有字幕文本");
                if (mWordSelectionController != null && hasSubtitleText()) {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "点击左键，尝试进入选词模式(从末尾开始)");
                    mWordSelectionController.enterWordSelectionMode(false);
                    return true;
                }
                android.util.Log.d("StandaloneSmbPlayerActivity", "没有字幕文本或控制器为空，执行快退操作");
                seekBackward();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 检查是否有字幕文本，有则进入选词模式，否则执行快进
                android.util.Log.d("StandaloneSmbPlayerActivity", "按下右键，检查是否有字幕文本");
                if (mWordSelectionController != null && hasSubtitleText()) {
                    android.util.Log.d("StandaloneSmbPlayerActivity", "点击右键，尝试进入选词模式(从开头开始)");
                    mWordSelectionController.enterWordSelectionMode(true);
                    return true;
                }
                android.util.Log.d("StandaloneSmbPlayerActivity", "没有字幕文本或控制器为空，执行快进操作");
                seekForward();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // 按下菜单键进入选词模式
                enterWordSelectionMode(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
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
        // 不再需要特殊处理长按事件，直接调用父类方法
        return super.dispatchKeyEvent(event);
    }
    
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 不再需要在长按事件中处理选词模式
        return super.onKeyLongPress(keyCode, event);
    }
    
    private void seekBackward() {
        long position = mPresenter.getPositionMs();
        long newPosition = Math.max(0, position - 10000); // 后退10秒
        mPresenter.setPositionMs(newPosition);
        
        // 立即更新UI显示，而不等待下一次进度更新周期
        updatePosition(newPosition);
        
        MessageHelpers.showMessage(this, "后退10秒");
    }
    
    private void seekForward() {
        long position = mPresenter.getPositionMs();
        long duration = mPresenter.getDurationMs();
        long newPosition = Math.min(duration, position + 30000); // 前进30秒
        mPresenter.setPositionMs(newPosition);
        
        // 立即更新UI显示，而不等待下一次进度更新周期
        updatePosition(newPosition);
        
        MessageHelpers.showMessage(this, "前进30秒");
    }
    
    /**
     * 显示控制界面
     */
    private void showControls() {
        if (!mControlsVisible) {
            mTitleContainer.setVisibility(View.VISIBLE);
            mControlsContainer.setVisibility(View.VISIBLE);
            mControlsVisible = true;
        }
        
        // 重新安排自动隐藏
        scheduleHideControls();
    }
    
    /**
     * 隐藏控制界面
     */
    private void hideControls() {
        if (mControlsVisible) {
            mTitleContainer.setVisibility(View.GONE);
            mControlsContainer.setVisibility(View.GONE);
            mControlsVisible = false;
        }
    }
    
    /**
     * 切换控制界面显示/隐藏状态
     */
    private void toggleControlsVisibility() {
        if (mControlsVisible) {
            hideControls();
            mHandler.removeCallbacks(mHideUIRunnable);
        } else {
            showControls();
        }
    }
    
    /**
     * 安排自动隐藏控制界面的任务
     */
    private void scheduleHideControls() {
        mHandler.removeCallbacks(mHideUIRunnable);
        mHandler.postDelayed(mHideUIRunnable, AUTO_HIDE_DELAY_MS);
    }
    
    /**
     * 获取ExoPlayer的PlayerView
     */
    public PlayerView getPlayerView() {
        return mPlayerView;
    }
    
    /**
     * 初始化字幕选词控制器
     */
    public void initWordSelectionController(SubtitleView subtitleView, FrameLayout rootView) {
        android.util.Log.d("StandaloneSmbPlayerActivity", "开始初始化字幕选词控制器");
        
        if (subtitleView != null && rootView != null) {
            android.util.Log.d("StandaloneSmbPlayerActivity", "字幕视图和根视图均有效，创建控制器");
            mWordSelectionController = new SubtitleWordSelectionController(this, subtitleView, rootView);
            android.util.Log.d("StandaloneSmbPlayerActivity", "字幕选词控制器初始化完成");
        } else {
            android.util.Log.e("StandaloneSmbPlayerActivity", "无法初始化字幕选词控制器: subtitleView=" + 
                    (subtitleView != null ? "有效" : "null") + ", rootView=" + 
                    (rootView != null ? "有效" : "null"));
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        android.util.Log.d("StandaloneSmbPlayerActivity", "onKeyUp: keyCode=" + keyCode);
        
        // 如果是在字幕选词模式下，将事件传递给字幕选词控制器
        if (mWordSelectionController != null && mWordSelectionController.isInWordSelectionMode()) {
            return mWordSelectionController.handleKeyEvent(event);
        }
        
        // 左右键的处理已经在onKeyDown中完成
        // 不再需要在这里处理快进快退
        
        return super.onKeyUp(keyCode, event);
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
                    initWordSelectionController(subtitleView, rootView);
                    
                    // 再次尝试进入选词模式
                    if (mWordSelectionController != null) {
                        mWordSelectionController.enterWordSelectionMode(fromStart);
                    }
                }
            }
        }
    }
} 