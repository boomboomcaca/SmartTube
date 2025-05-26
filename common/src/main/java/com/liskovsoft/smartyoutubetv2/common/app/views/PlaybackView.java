package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;

public interface PlaybackView extends PlayerManager {
    void showProgressBar(boolean show);
    
    /**
     * 获取字幕管理器
     */
    SubtitleManager getSubtitleManager();
    
    /**
     * 检查控制栏是否可见
     */
    boolean isControlsVisible();
}
