package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.StandaloneSmbPlayerView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.SmbDataSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

public class StandaloneSmbPlayerPresenter extends BasePresenter<StandaloneSmbPlayerView> implements Player.EventListener {
    private static final String TAG = StandaloneSmbPlayerPresenter.class.getSimpleName();
    private static StandaloneSmbPlayerPresenter sInstance;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SimpleExoPlayer mExoPlayer;
    private Video mCurrentVideo;
    private final GeneralData mGeneralData;

    private StandaloneSmbPlayerPresenter(Context context) {
        super(context);
        mGeneralData = GeneralData.instance(context);
    }

    public static StandaloneSmbPlayerPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new StandaloneSmbPlayerPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public void openView() {
        if (getView() == null) {
            ViewManager.instance(getContext()).startView(StandaloneSmbPlayerView.class);
        }
    }

    public void openVideo(Video video) {
        if (video == null || TextUtils.isEmpty(video.videoUrl)) {
            if (getView() != null) {
                getView().showError("无效的视频");
            }
            return;
        }

        // 仅处理SMB视频
        if (!video.videoUrl.startsWith("smb://")) {
            if (getView() != null) {
                getView().showError("仅支持SMB视频");
            }
            return;
        }

        mCurrentVideo = video;

        if (getView() != null) {
            getView().showLoading(true);
            getView().openVideo(video);
            initializePlayer();
        }
    }

    public void releasePlayer() {
        if (mExoPlayer != null) {
            mExoPlayer.removeListener(this);
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    private void initializePlayer() {
        releasePlayer();

        // 创建默认的带宽测量器
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

        // 创建自适应轨道选择工厂
        AdaptiveTrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory();

        // 创建默认的轨道选择器
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(trackSelectionFactory);

        // 创建渲染工厂
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        // 创建ExoPlayer实例
        mExoPlayer = ExoPlayerFactory.newSimpleInstance(getContext(), renderersFactory, trackSelector);

        // 设置播放器事件监听器
        mExoPlayer.addListener(this);

        // 准备媒体源
        SmbDataSourceFactory dataSourceFactory = new SmbDataSourceFactory(getContext());
        Uri uri = Uri.parse(mCurrentVideo.videoUrl);
        
        // 创建渐进式媒体源（适用于MP4、MKV等常见格式）
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(uri);

        // 通过view接口设置视频表面
        if (getView() != null) {
            try {
                PlayerView playerView = getView().getPlayerView();
                if (playerView != null) {
                    playerView.setPlayer(mExoPlayer);
                }
            } catch (Exception e) {
                Log.e(TAG, "无法设置播放器视图: " + e.getMessage());
                // 失败时忽略
            }
        }

        // 准备播放
        mExoPlayer.prepare(mediaSource);
        mExoPlayer.setPlayWhenReady(true);
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(playWhenReady);
        }
    }

    public boolean isPlaying() {
        return mExoPlayer != null && mExoPlayer.getPlayWhenReady() && mExoPlayer.getPlaybackState() == Player.STATE_READY;
    }

    public long getPositionMs() {
        return mExoPlayer != null ? mExoPlayer.getCurrentPosition() : 0;
    }

    public long getDurationMs() {
        return mExoPlayer != null ? mExoPlayer.getDuration() : 0;
    }

    public void setPositionMs(long positionMs) {
        if (mExoPlayer != null) {
            mExoPlayer.seekTo(positionMs);
        }
    }

    // Player.EventListener 实现

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (getView() == null) {
            return;
        }

        if (playbackState == Player.STATE_READY) {
            getView().showLoading(false);
            if (mExoPlayer != null) {
                getView().updateDuration(mExoPlayer.getDuration());
            }
        } else if (playbackState == Player.STATE_BUFFERING) {
            getView().showLoading(true);
        } else if (playbackState == Player.STATE_ENDED) {
            getView().play(false);
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        if (getView() != null) {
            getView().showError("播放错误: " + error.getMessage());
            getView().showLoading(false);
        }
    }

    // 其他必要的Player.EventListener方法实现
    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
        // 不需要实现
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        // 不需要实现
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        // 不需要实现
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        // 不需要实现
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        // 不需要实现
    }

    @Override
    public void onSeekProcessed() {
        // 不需要实现
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        // 不需要实现
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
        // 不需要实现
    }

    // 更新进度的Runnable
    private final Runnable mProgressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer != null && getView() != null) {
                getView().updatePosition(mExoPlayer.getCurrentPosition());
            }
            mHandler.postDelayed(this, 1000); // 每秒更新一次
        }
    };

    public void startProgressUpdates() {
        mHandler.post(mProgressUpdateRunnable);
    }

    public void stopProgressUpdates() {
        mHandler.removeCallbacks(mProgressUpdateRunnable);
    }
} 