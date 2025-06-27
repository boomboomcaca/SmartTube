package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.exoplayer2.ui.PlayerView;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.StandaloneSmbPlayerPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.StandaloneSmbPlayerView;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * 独立的SMB视频播放器活动
 */
public class StandaloneSmbPlayerActivity extends FragmentActivity implements StandaloneSmbPlayerView {
    private static final String TAG = StandaloneSmbPlayerActivity.class.getSimpleName();
    
    private StandaloneSmbPlayerPresenter mPresenter;
    private PlayerView mPlayerView;
    private ProgressBar mProgressBar;
    private TextView mErrorTextView;
    private TextView mTitleTextView;
    private FrameLayout mControlsContainer;
    private TextView mPositionView;
    private TextView mDurationView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_standalone_smb_player);

        mPresenter = StandaloneSmbPlayerPresenter.instance(this);
        initViews();
        mPresenter.setView(this);

        // 从Intent中获取视频数据
        Video video = getVideoFromIntent();
        if (video != null) {
            mPresenter.openVideo(video);
            mPresenter.startProgressUpdates();
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
        mControlsContainer = findViewById(R.id.controls_container);
        mPositionView = findViewById(R.id.position_view);
        mDurationView = findViewById(R.id.duration_view);
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
    }

    @Override
    public void updateDuration(long durationMs) {
        // 格式化时间为 HH:MM:SS
        String formattedTime = formatTime(durationMs);
        mDurationView.setText(formattedTime);
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
    }

    @Override
    protected void onDestroy() {
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
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBackward();
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekForward();
                showControls();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        
        return super.onKeyDown(keyCode, event);
    }
    
    private void seekBackward() {
        long position = mPresenter.getPositionMs();
        long newPosition = Math.max(0, position - 10000); // 后退10秒
        mPresenter.setPositionMs(newPosition);
        MessageHelpers.showMessage(this, "后退10秒");
    }
    
    private void seekForward() {
        long position = mPresenter.getPositionMs();
        long duration = mPresenter.getDurationMs();
        long newPosition = Math.min(duration, position + 30000); // 前进30秒
        mPresenter.setPositionMs(newPosition);
        MessageHelpers.showMessage(this, "前进30秒");
    }
    
    private void showControls() {
        mControlsContainer.setVisibility(View.VISIBLE);
        
        // 3秒后隐藏控制条
        mControlsContainer.postDelayed(() -> {
            mControlsContainer.setVisibility(View.GONE);
        }, 3000);
    }
    
    /**
     * 获取ExoPlayer的PlayerView
     */
    public PlayerView getPlayerView() {
        return mPlayerView;
    }
} 