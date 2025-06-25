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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPresenter = SmbPlayerPresenter.instance(getContext());
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mPresenter.onViewInitialized();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mPresenter != null) {
            mPresenter.onViewResumed();
            update(mPresenter.getVideoGroup());
        }
    }

    protected void updateUI(VideoGroup group) {
        if (group != null) {
            update(group);
        }
    }

    @Override
    public void showError(String message) {
        if (getActivity() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        }
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