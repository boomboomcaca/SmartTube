package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.os.Bundle;
import android.view.View;
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

public class SmbPlayerFragment extends VideoGridFragment implements SmbPlayerView {
    private static final String TAG = SmbPlayerFragment.class.getSimpleName();
    private SmbPlayerPresenter mPresenter;
    private VideoGroup mCurrentGroup;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPresenter = SmbPlayerPresenter.instance(getContext());
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setOnItemViewClickedListener(new ItemViewClickedListener());
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mPresenter.setView(this);
    }

    @Override
    public void update(VideoGroup videoGroup) {
        if (videoGroup == null) {
            return;
        }

        mCurrentGroup = videoGroup;

        super.update(videoGroup);
    }
    
    @Override
    public void updateFolder(VideoGroup videoGroup) {
        update(videoGroup);
    }

    @Override
    public void showError(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Video) {
                mPresenter.onVideoItemClicked((Video) item);
            }
        }
    }
} 