package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.FrameLayout;

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
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.C;
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
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.SmbFile;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.MimeTypes;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;

public class StandaloneSmbPlayerPresenter extends BasePresenter<StandaloneSmbPlayerView> implements Player.EventListener {
    private static final String TAG = StandaloneSmbPlayerPresenter.class.getSimpleName();
    private static StandaloneSmbPlayerPresenter sInstance;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SimpleExoPlayer mExoPlayer;
    private Video mCurrentVideo;
    private final GeneralData mGeneralData;
    private SubtitleManager mSubtitleManager;

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
            if (mSubtitleManager != null) {
                if (mExoPlayer.getTextComponent() != null) {
                    mExoPlayer.getTextComponent().removeTextOutput(mSubtitleManager);
                }
                mSubtitleManager.release();
                mSubtitleManager = null;
            }
            
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
        
        MediaSource mediaSource;
        
        // 根据文件扩展名选择合适的MediaSource
        String fileExtension = getFileExtension(mCurrentVideo.videoUrl);
        if (fileExtension != null && fileExtension.equals("m3u8")) {
            // 对于HLS文件使用HlsMediaSource
            mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(uri);
            Log.d(TAG, "使用HLS媒体源播放: " + mCurrentVideo.videoUrl);
        } else {
            // 对于TS文件和其他格式使用ProgressiveMediaSource
            mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(uri);
            Log.d(TAG, "使用渐进式媒体源播放: " + mCurrentVideo.videoUrl);
        }

        // 查找并加载外部字幕文件
        MediaSource subtitleMediaSource = loadExternalSubtitles(dataSourceFactory, uri);
        if (subtitleMediaSource != null) {
            // 合并视频和字幕源
            mediaSource = new MergingMediaSource(mediaSource, subtitleMediaSource);
            Log.d(TAG, "已合并字幕到媒体源");
        }

        // 通过view接口设置视频表面
        if (getView() != null) {
            try {
                PlayerView playerView = getView().getPlayerView();
                if (playerView != null) {
                    playerView.setPlayer(mExoPlayer);
                    
                    // 初始化字幕管理器
                    SubtitleView subtitleView = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_subtitles);
                    if (subtitleView != null) {
                        mSubtitleManager = new SubtitleManager(subtitleView);
                        mSubtitleManager.setPlayer(mExoPlayer);
                        if (mExoPlayer.getTextComponent() != null) {
                            mExoPlayer.getTextComponent().addTextOutput(mSubtitleManager);
                        }
                        
                        // 初始化字幕选词控制器
                        try {
                            Log.d(TAG, "尝试初始化字幕选词控制器");
                            
                            // 查找字幕视图
                            if (playerView.getParent() instanceof FrameLayout) {
                                FrameLayout rootView = (FrameLayout) playerView.getParent();
                                Log.d(TAG, "找到根视图，调用initWordSelectionController");
                                
                                // 使用接口方法而不是直接类型转换
                                StandaloneSmbPlayerView view = getView();
                                // 检查view是否实现了initWordSelectionController方法
                                if (view != null) {
                                    Log.d(TAG, "尝试初始化字幕选词控制器，view类型: " + view.getClass().getName());
                                    
                                    // 直接调用接口方法，不需要反射
                                    view.initWordSelectionController(subtitleView, rootView);
                                    
                                    // 检查是否有字幕文本
                                    if (subtitleView.getCues() != null && !subtitleView.getCues().isEmpty()) {
                                        Log.d(TAG, "字幕视图已有字幕内容: " + subtitleView.getCues().size() + " 个Cue");
                                    } else {
                                        Log.d(TAG, "字幕视图暂无字幕内容");
                                    }
                                } else {
                                    Log.e(TAG, "无法初始化字幕选词控制器: view为null");
                                }
                            } else {
                                Log.e(TAG, "父视图不是FrameLayout，无法初始化字幕选词控制器");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "初始化字幕选词控制器失败: " + e.getMessage(), e);
                        }
                        
                        Log.d(TAG, "字幕管理器初始化成功");
                    } else {
                        Log.e(TAG, "找不到字幕视图");
                    }
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

    /**
     * 获取文件扩展名
     * @param url 文件URL
     * @return 小写的文件扩展名，如果没有则返回null
     */
    private String getFileExtension(String url) {
        if (url == null) {
            return null;
        }
        
        // 移除URL参数
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            url = url.substring(0, queryIndex);
        }
        
        // 获取最后一个斜杠后的部分
        int slashIndex = url.lastIndexOf('/');
        if (slashIndex >= 0) {
            url = url.substring(slashIndex + 1);
        }
        
        // 获取最后一个点后的部分
        int dotIndex = url.lastIndexOf('.');
        if (dotIndex > 0) {
            return url.substring(dotIndex + 1).toLowerCase();
        }
        
        return null;
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(playWhenReady);
        }
    }

    public boolean isPlaying() {
        return mExoPlayer != null && mExoPlayer.getPlayWhenReady() && mExoPlayer.getPlaybackState() == Player.STATE_READY;
    }
    
    /**
     * 检查播放器是否已初始化并准备好接收命令
     */
    public boolean isPlayerReady() {
        boolean result = mExoPlayer != null && 
               (mExoPlayer.getPlaybackState() == Player.STATE_READY || 
                mExoPlayer.getPlaybackState() == Player.STATE_BUFFERING || 
                mExoPlayer.getPlaybackState() == Player.STATE_IDLE);
        
        Log.d(TAG, "isPlayerReady: " + result + 
              ", mExoPlayer=" + (mExoPlayer != null ? "非空" : "null") + 
              (mExoPlayer != null ? ", state=" + mExoPlayer.getPlaybackState() : ""));
        
        return result;
    }
    
    /**
     * 检查ExoPlayer是否存在
     */
    public boolean hasExoPlayer() {
        return mExoPlayer != null;
    }
    
    /**
     * 强制恢复播放，即使播放器状态不是READY
     */
    public void forcePlay() {
        Log.d(TAG, "强制恢复播放");
        if (mExoPlayer != null) {
            try {
                mExoPlayer.setPlayWhenReady(true);
                Log.d(TAG, "已设置播放器为播放状态");
            } catch (Exception e) {
                Log.e(TAG, "强制恢复播放失败", e);
            }
        } else {
            Log.e(TAG, "无法强制恢复播放：mExoPlayer为null");
        }
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
        // seek操作完成后立即更新UI
        if (mExoPlayer != null && getView() != null) {
            getView().updatePosition(mExoPlayer.getCurrentPosition());
        }
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

    /**
     * 查找并加载外部字幕文件
     * @param dataSourceFactory 数据源工厂
     * @param videoUri 视频URI
     * @return 字幕媒体源，如果没有找到则返回null
     */
    private MediaSource loadExternalSubtitles(SmbDataSourceFactory dataSourceFactory, Uri videoUri) {
        String videoPath = videoUri.toString();
        String basePath = videoPath.substring(0, videoPath.lastIndexOf('.'));
        
        Log.d(TAG, "开始查找外部字幕文件，视频路径: " + videoPath);
        Log.d(TAG, "字幕基础路径: " + basePath);
        
        // 支持的字幕格式和语言后缀
        String[] subtitleFormats = {".srt", ".vtt"};
        String[] languageSuffixes = {"", ".zh", ".en", ".zh-CN", ".zh-TW", ".en-US", ".en-GB"};
        
        for (String format : subtitleFormats) {
            for (String lang : languageSuffixes) {
                String subtitlePath = basePath + lang + format;
                Uri subtitleUri = Uri.parse(subtitlePath);
                
                Log.d(TAG, "尝试查找字幕文件: " + subtitlePath);
                
                try {
                    // 尝试打开字幕文件
                    SmbFile smbFile = new SmbFile(subtitlePath);
                    if (smbFile.exists()) {
                        Log.d(TAG, "找到字幕文件: " + subtitlePath);
                        
                        // 根据文件扩展名确定MIME类型
                        String mimeType = format.equals(".srt") ? 
                                MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT;
                        
                        // 确定语言代码
                        String languageCode = "und"; // 未定义语言
                        if (lang.startsWith(".zh")) {
                            languageCode = "zh";
                        } else if (lang.startsWith(".en")) {
                            languageCode = "en";
                        }
                        
                        // 创建字幕格式
                        Format subtitleFormat = Format.createTextSampleFormat(
                                /* id= */ null,
                                mimeType,
                                C.SELECTION_FLAG_DEFAULT,
                                languageCode);
                        
                        // 创建字幕媒体源
                        return new SingleSampleMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(subtitleUri, subtitleFormat, C.TIME_UNSET);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "检查字幕文件失败: " + subtitlePath + ", " + e.getMessage());
                }
            }
        }
        
        Log.d(TAG, "未找到匹配的字幕文件");
        return null;
    }

    /**
     * 获取ExoPlayer实例
     * 注意: 此方法仅供字幕管理器等内部组件使用，不应在公共API中暴露
     */
    public SimpleExoPlayer getExoPlayer() {
        return mExoPlayer;
    }
    
    /**
     * 获取当前播放器音量（0-1范围）
     */
    public float getVolume() {
        if (mExoPlayer != null) {
            return mExoPlayer.getVolume();
        }
        return 1.0f; // 默认音量
    }
    
    /**
     * 设置播放器音量（0-1范围）
     */
    public void setVolume(float volume) {
        if (mExoPlayer != null) {
            mExoPlayer.setVolume(volume);
        }
    }
} 