package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.app.AlertDialog;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
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
import java.util.Collections;
import java.util.Date;
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

    public void loadRootFolder() {
        try {
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

        // 显示加载中提示
        if (getView() != null) {
            getView().showLoading(true);
        }

        // 加载根文件夹
        loadFolder(mCurrentPath);
        } catch (Exception e) {
            Log.e(TAG, "Error loading root folder", e);
            if (getView() != null) {
                getView().showLoading(false);
                getView().showError("加载SMB服务器失败: " + e.getMessage());
            }
        }
    }

    public void loadFolder(String path) {
        mCurrentPath = path;
        Log.d(TAG, "Loading folder: " + path);
        new LoadFolderTask().execute(path);
    }

    public void navigateBack() {
        if (mCurrentPath.equals(mRootPath)) {
            // 已经在根目录，无法返回
            Log.d(TAG, "Already at root directory, cannot navigate back");
            return;
        }

        // 移除最后一个目录
        String path = mCurrentPath.substring(0, mCurrentPath.lastIndexOf('/', mCurrentPath.length() - 2) + 1);
        Log.d(TAG, "Navigating back to: " + path);
        loadFolder(path);
    }

    /**
     * 删除SMB文件及其匹配的字幕文件
     * @param video 要删除的视频
     */
    public void deleteFile(Video video) {
        if (video == null || TextUtils.isEmpty(video.videoUrl)) {
            MessageHelpers.showMessage(getContext(), R.string.file_delete_error, "Invalid file");
            return;
        }
        
        try {
            // 验证文件路径格式
            if (!video.videoUrl.startsWith("smb://")) {
                MessageHelpers.showMessage(getContext(), R.string.file_delete_error, "Invalid SMB path");
                return;
            }
            
            new DeleteFileTask().execute(video);
        } catch (Exception e) {
            Log.e(TAG, "Error starting delete task", e);
            MessageHelpers.showMessage(getContext(), R.string.file_delete_error, e.getMessage());
        }
    }
    
    /**
     * 异步任务用于删除文件，以避免阻塞UI线程
     */
    private class DeleteFileTask extends AsyncTask<Video, Void, Boolean> {
        private String mErrorMessage;
        private String mFilePath;
        
        @Override
        protected Boolean doInBackground(Video... videos) {
            if (videos == null || videos.length == 0 || videos[0] == null) {
                mErrorMessage = "No valid video to delete";
                return false;
            }
            
            Video video = videos[0];
            mFilePath = video.videoUrl;
            
            if (TextUtils.isEmpty(mFilePath)) {
                mErrorMessage = "Empty file path";
                return false;
            }
            
            try {
                Log.d(TAG, "开始删除文件: " + mFilePath);
                
                // 获取认证上下文
                CIFSContext baseContext = SingletonContext.getInstance();
                CIFSContext authenticatedContext;
                
                String username = mGeneralData.getSmbUsername();
                String password = mGeneralData.getSmbPassword();
                
                if (!TextUtils.isEmpty(username)) {
                    Log.d(TAG, "Using authentication with username: " + username);
                    NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(username, password);
                    authenticatedContext = baseContext.withCredentials(auth);
                } else {
                    Log.d(TAG, "Using anonymous authentication");
                    authenticatedContext = baseContext;
                }
                
                // 删除主文件
                SmbFile smbFile = new SmbFile(mFilePath, authenticatedContext);
                if (smbFile.exists()) {
                    smbFile.delete();
                    Log.d(TAG, "文件已删除: " + mFilePath);
                    
                    // 查找并删除匹配的字幕文件
                    deleteMatchingSubtitleFiles(smbFile, authenticatedContext);
                    
                    return true;
                } else {
                    mErrorMessage = "文件不存在";
                    return false;
                }
                
            } catch (MalformedURLException e) {
                Log.e(TAG, "文件路径格式错误: " + mFilePath, e);
                mErrorMessage = "文件路径格式错误: " + e.getMessage();
                return false;
            } catch (SmbException e) {
                Log.e(TAG, "SMB操作失败: " + e.getMessage(), e);
                mErrorMessage = "SMB操作失败: " + e.getMessage();
                return false;
            } catch (Exception e) {
                Log.e(TAG, "删除文件失败: " + e.getMessage(), e);
                mErrorMessage = e.getMessage();
                return false;
            }
        }
        
        @Override
        protected void onPostExecute(Boolean success) {
            if (getContext() == null) {
                Log.e(TAG, "Context is null in onPostExecute, cannot show message");
                return;
            }
            
            if (success) {
                MessageHelpers.showMessage(getContext(), R.string.file_deleted);
                // 重新加载当前文件夹以刷新列表
                if (mCurrentPath != null) {
                    loadFolder(mCurrentPath);
                }
            } else {
                MessageHelpers.showMessage(getContext(), R.string.file_delete_error, mErrorMessage);
            }
        }
        
        /**
         * 查找并删除与视频文件匹配的字幕文件
         */
        private void deleteMatchingSubtitleFiles(SmbFile videoFile, CIFSContext authenticatedContext) {
            if (videoFile == null || authenticatedContext == null) {
                Log.e(TAG, "Invalid parameters for deleting subtitle files");
                return;
            }
            
            try {
                String videoPath = videoFile.getPath();
                // 检查路径是否有效
                if (TextUtils.isEmpty(videoPath) || !videoPath.contains(".")) {
                    Log.e(TAG, "Invalid video path for finding subtitles: " + videoPath);
                    return;
                }
                
                // 获取不带扩展名的文件路径
                String basePath = videoPath.substring(0, videoPath.lastIndexOf('.'));
                Log.d(TAG, "查找字幕文件，基础路径: " + basePath);
                
                // 常见字幕文件扩展名
                String[] subtitleExtensions = {".srt", ".vtt", ".sub", ".sbv", ".ass", ".ssa", ".smi"};
                // 语言后缀
                String[] languageSuffixes = {"", ".zh", ".cn", ".en", ".eng", ".zh-CN", ".zh-TW", ".en-US", ".en-GB"};
                
                // 搜索字幕文件
                for (String ext : subtitleExtensions) {
                    for (String lang : languageSuffixes) {
                        String subtitlePath = basePath + lang + ext;
                        try {
                            SmbFile subtitleFile = new SmbFile(subtitlePath, authenticatedContext);
                            if (subtitleFile.exists()) {
                                Log.d(TAG, "删除字幕文件: " + subtitlePath);
                                subtitleFile.delete();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "删除字幕文件失败: " + subtitlePath, e);
                            // 继续删除其他字幕文件
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "查找字幕文件失败", e);
                // 字幕文件删除失败不应该影响主流程
            }
        }
    }

    private class LoadFolderTask extends AsyncTask<String, Void, VideoGroup> {
        private String mErrorMessage;

        @Override
        protected VideoGroup doInBackground(String... paths) {
            String path = paths[0];
            Log.d(TAG, "Starting to load folder: " + path);
            List<Video> videos = new ArrayList<>();
            VideoGroup videoGroup = new VideoGroup();
            videoGroup.setTitle(path);

            try {
                CIFSContext baseContext = SingletonContext.getInstance();
                CIFSContext authenticatedContext;

                String username = mGeneralData.getSmbUsername();
                String password = mGeneralData.getSmbPassword();

                if (!TextUtils.isEmpty(username)) {
                    Log.d(TAG, "Using authentication with username: " + username);
                    NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(username, password);
                    authenticatedContext = baseContext.withCredentials(auth);
                } else {
                    Log.d(TAG, "Using anonymous authentication");
                    authenticatedContext = baseContext;
                }

                SmbFile smbFile = new SmbFile(path, authenticatedContext);
                
                // 验证SMB文件是否存在且是目录
                if (!smbFile.exists()) {
                    Log.e(TAG, "SMB path does not exist: " + path);
                    mErrorMessage = "SMB路径不存在: " + path;
                    return null;
                }
                
                if (!smbFile.isDirectory()) {
                    Log.e(TAG, "SMB path is not a directory: " + path);
                    mErrorMessage = "SMB路径不是目录: " + path;
                    return null;
                }
                
                SmbFile[] files = smbFile.listFiles();
                
                if (files == null) {
                    Log.e(TAG, "Failed to list files in directory: " + path);
                    mErrorMessage = "无法列出目录中的文件: " + path;
                    return null;
                }
                
                Log.d(TAG, "Found " + files.length + " files in directory");

                // 添加返回上一级目录选项（如果不是根目录）
                if (!path.equals(mRootPath)) {
                    Video backVideo = new Video();
                    backVideo.title = "..";
                    backVideo.cardImageUrl = "drawable://" + R.drawable.ic_arrow_back;
                    backVideo.videoUrl = "back";
                    backVideo.isFolder = true;
                    videos.add(backVideo);
                    Log.d(TAG, "Added back navigation item");
                }

                try {
                    int folderCount = 0;
                    int videoCount = 0;
                    
                    // 先添加所有文件夹
                    for (SmbFile file : files) {
                        if (file.isDirectory()) {
                            // 跳过隐藏文件夹
                            if (file.isHidden() || file.getName().startsWith(".")) {
                                Log.d(TAG, "跳过隐藏文件夹: " + file.getName());
                                continue;
                            }
                            
                            Video video = new Video();
                            video.title = file.getName().replace("/", "");
                            video.cardImageUrl = "drawable://" + R.drawable.ic_folder;
                            video.videoUrl = file.getURL().toString();
                            video.isFolder = true;
                            // 设置文件夹的修改日期
                            video.setFileDate(new Date(file.getLastModified()));
                            videos.add(video);
                            folderCount++;
                        }
                    }
                    Log.d(TAG, "Added " + folderCount + " folders");

                    // 只有在非根目录时才显示视频文件
                    if (!path.equals(mRootPath)) {
                        // 再添加所有视频文件
                        for (SmbFile file : files) {
                            if (!file.isDirectory()) {
                                // 跳过隐藏文件
                                if (file.isHidden() || file.getName().startsWith(".")) {
                                    Log.d(TAG, "跳过隐藏文件: " + file.getName());
                                    continue;
                                }
                                
                                String name = file.getName().toLowerCase();
                                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || 
                                    name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") ||
                                    name.endsWith(".webm") || name.endsWith(".m4v") || name.endsWith(".ts")) {
                                    Video video = new Video();
                                    video.title = file.getName();
                                    video.cardImageUrl = "drawable://" + R.drawable.ic_file_video;
                                    video.videoUrl = file.getURL().toString();
                                    video.isFolder = false;
                                    // 设置文件的修改日期
                                    video.setFileDate(new Date(file.getLastModified()));
                                    // 设置文件大小
                                    video.setFileSize(file.length());
                                    videos.add(video);
                                    videoCount++;
                                }
                            }
                        }
                    }
                    Log.d(TAG, "Added " + videoCount + " video files");
                    
                    // 根据排序设置对文件和文件夹进行排序
                    sortVideos(videos);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing files: " + e.getMessage());
                }

                // 确保视频组有内容
                if (!videos.isEmpty()) {
                    // 使用from工厂方法正确初始化VideoGroup
                    videoGroup = VideoGroup.from(videos);
                    videoGroup.setTitle(path);
                    videoGroup.setAction(VideoGroup.ACTION_REPLACE); // 确保替换当前内容
                    Log.d(TAG, "Created VideoGroup with " + videos.size() + " items");
                } else {
                    // 即使没有视频，仍然显示空组
                    videoGroup.setAction(VideoGroup.ACTION_REPLACE); 
                    Log.d(TAG, "Created empty VideoGroup");
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
        
        /**
         * 根据用户设置的排序类型和顺序对视频列表进行排序
         * @param videos 要排序的视频列表
         */
        private void sortVideos(List<Video> videos) {
            if (videos == null || videos.isEmpty()) {
                return;
            }
            
            // 第一个项目可能是返回上一级的项目，需要单独处理
            Video backItem = null;
            if (!videos.isEmpty() && "..".equals(videos.get(0).title)) {
                backItem = videos.remove(0);
            }
            
            // 分离文件夹和文件
            List<Video> folders = new ArrayList<>();
            List<Video> files = new ArrayList<>();
            
            for (Video video : videos) {
                if (video.isFolder) {
                    folders.add(video);
                } else {
                    files.add(video);
                }
            }
            
            // 根据排序类型和顺序对文件夹排序
            int sortType = mGeneralData.getSmbSortType();
            int sortOrder = mGeneralData.getSmbSortOrder();
            
            // 对文件夹排序（文件夹只能按名称或修改日期排序）
            if (sortType == GeneralData.SMB_SORT_BY_NAME) {
                Collections.sort(folders, (v1, v2) -> {
                    if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                        return v1.title.compareToIgnoreCase(v2.title);
                    } else {
                        return v2.title.compareToIgnoreCase(v1.title);
                    }
                });
            } else if (sortType == GeneralData.SMB_SORT_BY_DATE) {
                Collections.sort(folders, (v1, v2) -> {
                    if (v1.getFileDate() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? -1 : 1;
                    if (v2.getFileDate() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? 1 : -1;
                    
                    if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                        return v1.getFileDate().compareTo(v2.getFileDate());
                    } else {
                        return v2.getFileDate().compareTo(v1.getFileDate());
                    }
                });
            }
            
            // 对文件排序
            switch (sortType) {
                case GeneralData.SMB_SORT_BY_NAME:
                    Collections.sort(files, (v1, v2) -> {
                        if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                            return v1.title.compareToIgnoreCase(v2.title);
                        } else {
                            return v2.title.compareToIgnoreCase(v1.title);
                        }
                    });
                    break;
                    
                case GeneralData.SMB_SORT_BY_DATE:
                    Collections.sort(files, (v1, v2) -> {
                        if (v1.getFileDate() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? -1 : 1;
                        if (v2.getFileDate() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? 1 : -1;
                        
                        if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                            return v1.getFileDate().compareTo(v2.getFileDate());
                        } else {
                            return v2.getFileDate().compareTo(v1.getFileDate());
                        }
                    });
                    break;
                    
                case GeneralData.SMB_SORT_BY_PLAY_TIME:
                    Collections.sort(files, (v1, v2) -> {
                        if (v1.getPlayTime() == null && v2.getPlayTime() == null) {
                            // 如果都没有播放时间，则按名称排序
                            return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? 
                                   v1.title.compareToIgnoreCase(v2.title) : 
                                   v2.title.compareToIgnoreCase(v1.title);
                        }
                        
                        if (v1.getPlayTime() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? -1 : 1;
                        if (v2.getPlayTime() == null) return sortOrder == GeneralData.SMB_SORT_ORDER_ASC ? 1 : -1;
                        
                        if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                            return v1.getPlayTime().compareTo(v2.getPlayTime());
                        } else {
                            return v2.getPlayTime().compareTo(v1.getPlayTime());
                        }
                    });
                    break;
                    
                case GeneralData.SMB_SORT_BY_SIZE:
                    Collections.sort(files, (v1, v2) -> {
                        if (sortOrder == GeneralData.SMB_SORT_ORDER_ASC) {
                            return Long.compare(v1.getFileSize(), v2.getFileSize());
                        } else {
                            return Long.compare(v2.getFileSize(), v1.getFileSize());
                        }
                    });
                    break;
            }
            
            // 清空并重新组装列表：返回上一级 -> 文件夹 -> 文件
            videos.clear();
            if (backItem != null) {
                videos.add(backItem);
            }
            videos.addAll(folders);
            videos.addAll(files);
            
            Log.d(TAG, "Sorted videos list with type: " + sortType + ", order: " + sortOrder);
        }

        @Override
        protected void onPostExecute(VideoGroup videoGroup) {
            if (getView() != null) {
                getView().showLoading(false);
                
                if (videoGroup != null) {
                    mCurrentGroup = videoGroup;
                    Log.d(TAG, "Updating view with VideoGroup containing " + 
                          (videoGroup.getVideos() != null ? videoGroup.getVideos().size() : 0) + " items");
                    getView().updateFolder(videoGroup);
                } else {
                    Log.e(TAG, "VideoGroup is null. Error: " + (mErrorMessage != null ? mErrorMessage : "Unknown error"));
                    getView().showError(mErrorMessage != null ? mErrorMessage : "Unknown error");
                }
            } else {
                Log.e(TAG, "View is null, cannot update UI");
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
            // 更新播放时间
            item.updatePlayTime();
            
            // 播放视频 - 使用独立播放器
            try {
                Intent intent = new Intent();
                intent.setClassName(getContext(), "com.liskovsoft.smartyoutubetv2.tv.ui.playback.StandaloneSmbPlayerActivity");
                intent.putExtra("video_url", item.videoUrl);
                intent.putExtra("video_title", item.title);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "无法启动独立播放器: " + e.getMessage());
                // 回退到普通播放器
                PlaybackPresenter.instance(getContext()).openVideo(item);
            }
        }
    }
    
    /**
     * 显示排序选项对话框
     */
    public void showSortDialog() {
        if (getContext() == null) {
            return;
        }
        
        // 创建排序选项
        String[] sortOptions = new String[] {
            getContext().getString(R.string.sort_by_name),
            getContext().getString(R.string.sort_by_modified_date),
            getContext().getString(R.string.sort_by_play_time),
            getContext().getString(R.string.sort_by_size)
        };
        
        // 创建排序顺序选项
        String[] orderOptions = new String[] {
            getContext().getString(R.string.sort_order_asc),
            getContext().getString(R.string.sort_order_desc)
        };
        
        // 获取当前排序设置
        int currentSortType = mGeneralData.getSmbSortType();
        int currentSortOrder = mGeneralData.getSmbSortOrder();
        
        // 创建对话框构建器
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.sort_options);
        
        // 设置排序类型选项
        builder.setSingleChoiceItems(sortOptions, currentSortType, (dialog, which) -> {
            if (currentSortType != which) {
                mGeneralData.setSmbSortType(which);
                // 重新加载当前文件夹以应用新的排序
                if (mCurrentPath != null) {
                    loadFolder(mCurrentPath);
                }
            }
            dialog.dismiss();
        });
        
        // 添加排序顺序按钮
        builder.setPositiveButton(orderOptions[0], (dialog, which) -> {
            if (currentSortOrder != GeneralData.SMB_SORT_ORDER_ASC) {
                mGeneralData.setSmbSortOrder(GeneralData.SMB_SORT_ORDER_ASC);
                // 重新加载当前文件夹以应用新的排序
                if (mCurrentPath != null) {
                    loadFolder(mCurrentPath);
                }
            }
        });
        
        builder.setNegativeButton(orderOptions[1], (dialog, which) -> {
            if (currentSortOrder != GeneralData.SMB_SORT_ORDER_DESC) {
                mGeneralData.setSmbSortOrder(GeneralData.SMB_SORT_ORDER_DESC);
                // 重新加载当前文件夹以应用新的排序
                if (mCurrentPath != null) {
                    loadFolder(mCurrentPath);
                }
            }
        });
        
        // 显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }
} 