package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SmbPlayerPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SmbPlayerView;
import com.liskovsoft.smartyoutubetv2.tv.R;

public class SmbPlayerFragment extends VideoGridFragment implements SmbPlayerView {
    private static final String TAG = SmbPlayerFragment.class.getSimpleName();
    private SmbPlayerPresenter mPresenter;
    private VideoGroup mCurrentGroup;
    private ProgressBar mProgressBar;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mPresenter = SmbPlayerPresenter.instance(getContext());
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");
        
        setOnItemViewClickedListener(new ItemViewClickedListener());
        
        // 创建进度条并添加到视图中
        mProgressBar = new ProgressBar(getContext());
        mProgressBar.setId(R.id.progress_bar);
        
        // 设置进度条居中显示
        android.view.ViewGroup.LayoutParams params = new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mProgressBar.setLayoutParams(params);
        
        // 添加到布局中
        android.widget.FrameLayout rootView = (android.widget.FrameLayout) view;
        rootView.addView(mProgressBar);
        
        // 设置进度条居中
        android.widget.FrameLayout.LayoutParams layoutParams = 
                (android.widget.FrameLayout.LayoutParams) mProgressBar.getLayoutParams();
        layoutParams.gravity = android.view.Gravity.CENTER;
        mProgressBar.setLayoutParams(layoutParams);
        
        // 初始状态隐藏
        mProgressBar.setVisibility(View.GONE);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.d(TAG, "onActivityCreated");
        
        mPresenter.setView(this);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        
        // 每次Fragment恢复时重新加载SMB内容
        // 这确保每次切换到SMB播放器标签时都会刷新显示
        if (mPresenter != null) {
            mPresenter.loadRootFolder();
        }
    }

    @Override
    public void update(VideoGroup videoGroup) {
        if (videoGroup == null) {
            Log.e(TAG, "update: videoGroup is null");
            return;
        }

        Log.d(TAG, "update: videoGroup has " + 
              (videoGroup.getVideos() != null ? videoGroup.getVideos().size() : 0) + " items");
        
        mCurrentGroup = videoGroup;

        super.update(videoGroup);
    }
    
    @Override
    public void updateFolder(VideoGroup videoGroup) {
        if (videoGroup == null) {
            Log.e(TAG, "updateFolder: videoGroup is null");
            return;
        }
        
        Log.d(TAG, "updateFolder: videoGroup has " + 
              (videoGroup.getVideos() != null ? videoGroup.getVideos().size() : 0) + " items");
        
        // 明确设置替换操作
        videoGroup.setAction(VideoGroup.ACTION_REPLACE);
        Log.d(TAG, "updateFolder: setting action to REPLACE");
        
        // 确保清空当前内容
        clear();
        Log.d(TAG, "updateFolder: cleared current content");
        
        mCurrentGroup = videoGroup;
        
        if (videoGroup.isEmpty()) {
            Log.d(TAG, "updateFolder: videoGroup is empty");
            Toast.makeText(getContext(), "文件夹为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        update(videoGroup);
    }

    @Override
    public void showError(String message) {
        Log.e(TAG, "showError: " + message);
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }
    
    @Override
    public void showLoading(boolean show) {
        Log.d(TAG, "showLoading: " + show);
        if (mProgressBar != null) {
            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Video) {
                Log.d(TAG, "onItemClicked: " + ((Video) item).title);
                mPresenter.onVideoItemClicked((Video) item);
            }
        }
    }
} 