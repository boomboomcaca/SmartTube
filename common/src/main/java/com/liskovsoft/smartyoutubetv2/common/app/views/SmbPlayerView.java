package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
 
public interface SmbPlayerView {
    void update(VideoGroup videoGroup);
    void updateFolder(VideoGroup videoGroup);
    void showError(String message);
    void showLoading(boolean show);
} 