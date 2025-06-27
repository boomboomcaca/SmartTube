package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.google.android.exoplayer2.ui.PlayerView;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

public interface StandaloneSmbPlayerView {
    void openVideo(Video video);
    void showError(String message);
    void showLoading(boolean show);
    void updatePosition(long positionMs);
    void updateDuration(long durationMs);
    boolean isPlaying();
    void play(boolean play);
    
    /**
     * 获取ExoPlayer的PlayerView
     */
    PlayerView getPlayerView();
} 