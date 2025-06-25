package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SmbPlayerView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

public class SmbPlayerPresenter extends BasePresenter<SmbPlayerView> {
    private static final String TAG = SmbPlayerPresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static SmbPlayerPresenter sInstance;
    private final GeneralData mGeneralData;
    private String mCurrentPath;
    private String mRootPath;
    private VideoGroup mCurrentGroup;

    public SmbPlayerPresenter(Context context) {
        super(context);
        mGeneralData = GeneralData.instance(context);
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
            ViewManager.instance(getContext()).startView(SmbPlayerView.class);
        }

        loadRootFolder();
    }

    private void loadRootFolder() {
        String serverUrl = mGeneralData.getSmbServerUrl();
        if (TextUtils.isEmpty(serverUrl)) {
            if (getView() != null) {
                getView().showError(getContext().getString(R.string.smb_server_url) + " " + getContext().getString(R.string.not_set));
            }
            return;
        }

        // 确保URL以smb://开头
        if (!serverUrl.startsWith("smb://")) {
            serverUrl = "smb://" + serverUrl;
        }

        // 确保URL以/结尾
        if (!serverUrl.endsWith("/")) {
            serverUrl += "/";
        }

        mRootPath = serverUrl;
        mCurrentPath = serverUrl;

        loadFolder(mCurrentPath);
    }

    public void loadFolder(String path) {
        mCurrentPath = path;
        new LoadFolderTask().execute(path);
    }

    public void navigateBack() {
        if (mCurrentPath.equals(mRootPath)) {
            // 已经在根目录，无法返回
            return;
        }

        // 移除最后一个目录
        String path = mCurrentPath.substring(0, mCurrentPath.lastIndexOf('/', mCurrentPath.length() - 2) + 1);
        loadFolder(path);
    }

    private class LoadFolderTask extends AsyncTask<String, Void, VideoGroup> {
        private String mErrorMessage;

        @Override
        protected VideoGroup doInBackground(String... paths) {
            String path = paths[0];
            List<Video> videos = new ArrayList<>();
            VideoGroup videoGroup = new VideoGroup();

            try {
                CIFSContext baseContext = SingletonContext.getInstance();
                CIFSContext authenticatedContext;

                String username = mGeneralData.getSmbUsername();
                String password = mGeneralData.getSmbPassword();

                if (!TextUtils.isEmpty(username)) {
                    NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(username, password);
                    authenticatedContext = baseContext.withCredentials(auth);
                } else {
                    authenticatedContext = baseContext;
                }

                SmbFile smbFile = new SmbFile(path, authenticatedContext);
                SmbFile[] files = smbFile.listFiles();

                // 添加返回上一级目录选项（如果不是根目录）
                if (!path.equals(mRootPath)) {
                    Video backVideo = new Video();
                    backVideo.title = "..";
                    backVideo.cardImageUrl = "drawable://" + R.drawable.ic_arrow_back;
                    backVideo.videoUrl = "back";
                    backVideo.isFolder = true;
                    videos.add(backVideo);
                }

                try {
                    // 先添加所有文件夹
                    for (SmbFile file : files) {
                        if (file.isDirectory()) {
                            Video video = new Video();
                            video.title = file.getName().replace("/", "");
                            video.cardImageUrl = "drawable://" + R.drawable.ic_folder;
                            video.videoUrl = file.getURL().toString();
                            video.isFolder = true;
                            videos.add(video);
                        }
                    }

                    // 只有在非根目录时才显示视频文件
                    if (!path.equals(mRootPath)) {
                        // 再添加所有视频文件
                        for (SmbFile file : files) {
                            if (!file.isDirectory()) {
                                String name = file.getName().toLowerCase();
                                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || 
                                    name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") ||
                                    name.endsWith(".webm") || name.endsWith(".m4v") || name.endsWith(".ts")) {
                                    Video video = new Video();
                                    video.title = file.getName();
                                    video.cardImageUrl = "drawable://" + R.drawable.ic_file_video;
                                    video.videoUrl = file.getURL().toString();
                                    video.isFolder = false;
                                    videos.add(video);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing files: " + e.getMessage());
                }

                videoGroup.setTitle(path);
                for (Video video : videos) {
                    videoGroup.add(video);
                }
                return videoGroup;
            } catch (MalformedURLException e) {
                Log.e(TAG, "Invalid URL: " + e.getMessage());
                mErrorMessage = "Invalid URL: " + e.getMessage();
            } catch (SmbException e) {
                Log.e(TAG, "SMB error: " + e.getMessage());
                mErrorMessage = "SMB error: " + e.getMessage();
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
                mErrorMessage = "Error: " + e.getMessage();
            }

            return null;
        }

        @Override
        protected void onPostExecute(VideoGroup videoGroup) {
            if (videoGroup != null) {
                mCurrentGroup = videoGroup;
                if (getView() != null) {
                    getView().updateFolder(videoGroup);
                }
            } else if (getView() != null) {
                getView().showError(mErrorMessage != null ? mErrorMessage : "Unknown error");
            }
        }
    }

    public void onVideoItemClicked(Video item) {
        if (item.isFolder) {
            if ("back".equals(item.videoUrl)) {
                navigateBack();
            } else {
                loadFolder(item.videoUrl);
            }
        } else {
            // 播放视频
            PlaybackPresenter.instance(getContext()).openVideo(item);
        }
    }
} 