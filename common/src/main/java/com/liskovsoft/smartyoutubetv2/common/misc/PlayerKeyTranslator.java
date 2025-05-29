package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.view.KeyEvent;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleWordSelectionController;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.Arrays;
import java.util.Map;

public class PlayerKeyTranslator extends GlobalKeyTranslator {
    private final GeneralData mGeneralData;
    private final Context mContext;
    private final Runnable likeAction = () -> {
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            playbackPresenter.onLikeClicked(true);
            playbackPresenter.getView().setLikeButtonState(true);
            playbackPresenter.getView().setDislikeButtonState(false);
            MessageHelpers.showMessage(getContext(), R.string.action_like);
        }
    };
    private final Runnable dislikeAction = () -> {
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            playbackPresenter.onDislikeClicked(true);
            playbackPresenter.getView().setLikeButtonState(false);
            playbackPresenter.getView().setDislikeButtonState(true);
            MessageHelpers.showMessage(getContext(), R.string.action_dislike);
        }
    };
    private final Runnable speedUpAction = () -> speedUp(true);
    private final Runnable speedDownAction = () -> speedUp(false);
    private final Runnable volumeUpAction = () -> volumeUp(true);
    private final Runnable volumeDownAction = () -> volumeUp(false);
    private final Runnable leftKeyAction = () -> {
        // 检查是否已经在选词模式，如果是，不需要再次进入选词模式
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            SubtitleManager subtitleManager = playbackPresenter.getView().getSubtitleManager();
            if (subtitleManager != null) {
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null) {
                    // 如果没有在选词模式且有字幕，则进入选词模式
                    if (!controller.isInWordSelectionMode() && controller.hasSubtitleText()) {
                        controller.enterWordSelectionMode(false); // 从最后一个单词开始
                    }
                }
            }
        }
    };
    private final Runnable rightKeyAction = () -> {
        // 检查是否已经在选词模式，如果是，不需要再次进入选词模式
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            SubtitleManager subtitleManager = playbackPresenter.getView().getSubtitleManager();
            if (subtitleManager != null) {
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null) {
                    // 如果没有在选词模式且有字幕，则进入选词模式
                    if (!controller.isInWordSelectionMode() && controller.hasSubtitleText()) {
                        controller.enterWordSelectionMode(true); // 从第一个单词开始
                    }
                }
            }
        }
    };
    
    private boolean mIsWordSelectionModeEnabled = false;

    public PlayerKeyTranslator(Context context) {
        super(context);
        mContext = context;
        mGeneralData = GeneralData.instance(context);
    }

    @Override
    protected void initKeyMapping() {
        super.initKeyMapping();

        Map<Integer, Integer> globalKeyMapping = getKeyMapping();

        // Reset global mapping to default
        globalKeyMapping.remove(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        globalKeyMapping.remove(KeyEvent.KEYCODE_MEDIA_REWIND);
        globalKeyMapping.remove(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);

        if (mGeneralData.isRemapFastForwardToNextEnabled()) {
            globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT);
            globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }

        if (mGeneralData.isRemapNextToFastForwardEnabled()) {
            globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND);
        }

        if (mGeneralData.isRemapPageUpToNextEnabled()) {
            globalKeyMapping.put(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT);
            globalKeyMapping.put(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }

        if (mGeneralData.isRemapChannelUpToNextEnabled()) {
            globalKeyMapping.put(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT);
            globalKeyMapping.put(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
        
        if (!PlaybackPresenter.instance(mContext).isInPipMode() && mGeneralData.isRemapPlayToOKEnabled()) {
            globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER);
        } else {
            globalKeyMapping.remove(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }

        globalKeyMapping.put(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_0); // reset position of the video (if enabled number key handling in the settings)

        // Toggle playback on PLAY/PAUSE. NOTE: cause troubles with IoT handlers!!!
        //globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        //globalKeyMapping.put(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    @Override
    protected void initActionMapping() {
        super.initActionMapping();

        Map<Integer, Runnable> actionMapping = getActionMapping();

        if (mGeneralData.isRemapPageUpToLikeEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_PAGE_UP, likeAction);
            actionMapping.put(KeyEvent.KEYCODE_PAGE_DOWN, dislikeAction);
        }

        if (mGeneralData.isRemapChannelUpToLikeEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_UP, likeAction);
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_DOWN, dislikeAction);
        }

        if (mGeneralData.isRemapPageUpToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_PAGE_UP, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_PAGE_DOWN, speedDownAction);
        }

        if (mGeneralData.isRemapPageDownToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_PAGE_UP, speedDownAction);
            actionMapping.put(KeyEvent.KEYCODE_PAGE_DOWN, speedUpAction);
        }

        if (mGeneralData.isRemapChannelUpToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_UP, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_DOWN, speedDownAction);
        }

        if (mGeneralData.isRemapFastForwardToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_MEDIA_REWIND, speedDownAction);
        }

        if (mGeneralData.isRemapNextToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_MEDIA_NEXT, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, speedDownAction);
        }

        if (mGeneralData.isRemapDpadUpToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_DPAD_UP, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_DPAD_DOWN, speedDownAction);
        }

        if (mGeneralData.isRemapNumbersToSpeedEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_3, speedUpAction);
            actionMapping.put(KeyEvent.KEYCODE_1, speedDownAction);
        }

        if (mGeneralData.isRemapChannelUpToVolumeEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_UP, volumeUpAction);
            actionMapping.put(KeyEvent.KEYCODE_CHANNEL_DOWN, volumeDownAction);
        }

        if (mGeneralData.isRemapDpadUpToVolumeEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_DPAD_UP, volumeUpAction);
            actionMapping.put(KeyEvent.KEYCODE_DPAD_DOWN, volumeDownAction);
        }

        if (mGeneralData.isRemapDpadLeftToVolumeEnabled()) {
            actionMapping.put(KeyEvent.KEYCODE_DPAD_LEFT, volumeDownAction);
            actionMapping.put(KeyEvent.KEYCODE_DPAD_RIGHT, volumeUpAction);
        }

        // 添加左右键进入选词模式的映射
        if (isSubtitleWordSelectionEnabled()) {
            // 只有在没有其他映射的情况下才添加选词功能的映射
            if (!actionMapping.containsKey(KeyEvent.KEYCODE_DPAD_LEFT)) {
                actionMapping.put(KeyEvent.KEYCODE_DPAD_LEFT, leftKeyAction);
            }
            
            if (!actionMapping.containsKey(KeyEvent.KEYCODE_DPAD_RIGHT)) {
                actionMapping.put(KeyEvent.KEYCODE_DPAD_RIGHT, rightKeyAction);
            }
        }
    }
    
    /**
     * 检查是否在选词模式下，如果是则将按键事件传递给选词控制器处理
     * 这个方法应该在按键事件处理前调用
     * @param event 按键事件
     * @return 如果事件被处理返回true，否则返回false
     */
    public boolean handleSubtitleWordSelectionKeyEvent(KeyEvent event) {
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            SubtitleManager subtitleManager = playbackPresenter.getView().getSubtitleManager();
            if (subtitleManager != null) {
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null && controller.isInWordSelectionMode()) {
                    return controller.handleKeyEvent(event);
                }
            }
        }
        return false;
    }

    /**
     * 检查是否有字幕并进入选词模式
     */
    private void checkAndEnterWordSelectionMode() {
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();
        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            SubtitleManager subtitleManager = playbackPresenter.getView().getSubtitleManager();
            if (subtitleManager != null) {
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null && controller.hasSubtitleText()) {
                    controller.enterWordSelectionMode(); // 使用默认参数（从第一个单词开始）
                }
            }
        }
    }
    
    /**
     * 检查是否启用了字幕选词功能
     */
    private boolean isSubtitleWordSelectionEnabled() {
        // 这里可以添加一个设置选项来控制是否启用字幕选词功能
        // 现在默认启用
        return true;
    }

    private void speedUp(boolean up) {
        PlayerTweaksData data = PlayerTweaksData.instance(mContext);
        float[] speedSteps = data.isLongSpeedListEnabled() ? Utils.SPEED_LIST_LONG :
                data.isExtraLongSpeedListEnabled() ? Utils.SPEED_LIST_EXTRA_LONG : Utils.SPEED_LIST_SHORT;

        PlaybackPresenter playbackPresenter = getPlaybackPresenter();

        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            float currentSpeed = playbackPresenter.getView().getSpeed();
            int currentIndex = Arrays.binarySearch(speedSteps, currentSpeed);

            if (currentIndex < 0) {
                currentIndex = Arrays.binarySearch(speedSteps, 1.0f);
            }

            int newIndex = up ? currentIndex + 1 : currentIndex - 1;

            float speed = newIndex >= 0 && newIndex < speedSteps.length ? speedSteps[newIndex] : speedSteps[currentIndex];

            PlayerData.instance(mContext).setSpeed(speed);
            playbackPresenter.getView().setSpeed(speed);
            MessageHelpers.showMessage(mContext, String.format("%sx", speed));
        }
    }

    private void volumeUp(boolean up) {
        PlaybackPresenter playbackPresenter = getPlaybackPresenter();

        if (playbackPresenter != null && playbackPresenter.getView() != null) {
            Utils.volumeUp(mContext, playbackPresenter.getView(), up);
        }
    }

    private PlaybackPresenter getPlaybackPresenter() {
        return PlaybackPresenter.instance(mContext);
    }

    private Context getContext() {
        return mContext;
    }
}
