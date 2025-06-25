package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SmbPlayerView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

public class SmbPlayerPresenter extends BasePresenter<SmbPlayerView> {
    @SuppressLint("StaticFieldLeak")
    private static SmbPlayerPresenter sInstance;
    private final Context mContext;
    private VideoGroup mVideoGroup;

    private SmbPlayerPresenter(Context context) {
        super(context);
        mContext = context;
        initVideoGroup();
    }

    public static SmbPlayerPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new SmbPlayerPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public void openView() {
        if (getView() == null) {
            ViewManager.instance(mContext).startView(SmbPlayerView.class);
        }
    }

    private void initVideoGroup() {
        mVideoGroup = new VideoGroup();
        mVideoGroup.setTitle("SMB Player");
        mVideoGroup.setId(MediaGroup.TYPE_SMB_PLAYER);

        // 示例：这里可以根据实际需求加载SMB共享的文件或目录
        Video sampleVideo = new Video();
        sampleVideo.title = "Sample SMB Video";
        sampleVideo.description = "This is a sample SMB video";
        sampleVideo.videoId = "smb_sample_id";
        sampleVideo.cardImageUrl = "https://www.example.com/sample.jpg"; // 添加一个默认图片URL
        
        mVideoGroup.getVideos().add(sampleVideo);
    }

    public VideoGroup getVideoGroup() {
        return mVideoGroup;
    }

    public void onVideoItemClicked(Video item) {
        // 处理视频点击事件，打开SMB视频
        PlaybackPresenter.instance(mContext).openVideo(item);
    }

    public void onVideoItemLongClicked(Video item) {
        // 长按处理（可选）
    }
} 