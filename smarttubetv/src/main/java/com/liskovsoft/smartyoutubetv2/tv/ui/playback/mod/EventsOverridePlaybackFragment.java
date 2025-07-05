package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod;

import android.os.Bundle;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;

import androidx.leanback.app.PlaybackSupportFragment;
import androidx.leanback.widget.VerticalGridView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleWordSelectionController;
import com.liskovsoft.smartyoutubetv2.common.misc.PlayerKeyTranslator;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface.SurfacePlaybackFragment;

/**
 * Every successfully handled event invokes {@link PlaybackSupportFragment#tickle} that makes ui to appear.
 * Fixing that for keys.
 */
public class EventsOverridePlaybackFragment extends SurfacePlaybackFragment {
    private static final String TAG = "EventsOverridePlaybackFragment";
    private PlayerKeyTranslator mKeyTranslator;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mKeyTranslator = new PlayerKeyTranslator(getContext());
        mKeyTranslator.apply();

        Object onTouchInterceptListener = Helpers.getField(this, "mOnTouchInterceptListener");

        if (onTouchInterceptListener != null) {
            Helpers.setField(this, "mOnTouchInterceptListener", (VerticalGridView.OnTouchInterceptListener) this::onInterceptInputEvent);
        }

        Object onKeyInterceptListener = Helpers.getField(this, "mOnKeyInterceptListener");

        if (onKeyInterceptListener != null) {
            Helpers.setField(this, "mOnKeyInterceptListener", (VerticalGridView.OnKeyInterceptListener) this::onInterceptInputEvent);
        }
    }

    boolean onInterceptInputEvent(InputEvent event) {
        final boolean controlsHidden = !isControlsOverlayVisible();
        //if (DEBUG) Log.v(TAG, "onInterceptInputEvent hidden " + controlsHidden + " " + event);
        boolean consumeEvent = false;
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
        int keyAction = 0;

        if (event instanceof KeyEvent) {
            keyCode = ((KeyEvent) event).getKeyCode();
            keyAction = ((KeyEvent) event).getAction();
            android.util.Log.d(TAG, "onInterceptInputEvent: 按键=" + keyCode + ", 动作=" + keyAction + ", 控制栏隐藏=" + controlsHidden);

            // 当工具栏显示时，对于上下左右键，不拦截事件，恢复默认功能
            if (!controlsHidden && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                android.util.Log.d(TAG, "控制栏可见，不拦截上下左右键");
                return false;
            }

            // 如果是左右方向键且动作是按下
            if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && keyAction == KeyEvent.ACTION_DOWN) {
                // 调用专门处理左右键的方法
                boolean handled = handleLeftRightKeyDown((KeyEvent) event);
                if (handled) {
                    android.util.Log.d(TAG, "左右键已被处理");
                    return true;
                }
            }

            // 只有当工具栏隐藏时，才检查是否在选词模式下
            if (controlsHidden && event instanceof KeyEvent && mKeyTranslator != null && mKeyTranslator.handleSubtitleWordSelectionKeyEvent((KeyEvent) event)) {
                android.util.Log.d(TAG, "选词模式处理按键");
                return true;
            }

            if (getInputEventHandler() != null) {
                // VideoPlayerGlue handler
                consumeEvent = getInputEventHandler().onKey(getView(), keyCode, (KeyEvent) event);
            }
        }

        if (consumeEvent) {
            return true;
        }

        switch (keyCode) {
            // Confirm key
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Navigation key
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Event may be consumed; regardless, if controls are hidden then these keys will
                // bring up the controls.

                // MOD: show ui and apply key immediately
                //if (controlsHidden) {
                //    consumeEvent = true;
                //}

                if (keyAction == KeyEvent.ACTION_DOWN) {
                    tickle();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (isInSeek()) {
                    // when in seek, the SeekUi will handle the BACK.
                    return false;
                }
                // If controls are not hidden, back will be consumed to fade
                // them out (even if the key was consumed by the handler).
                if (!controlsHidden) {
                    consumeEvent = true;

                    if (((KeyEvent) event).getAction() == KeyEvent.ACTION_UP) {
                        hideControlsOverlay(true);
                    }
                }
                break;
            default:
                if (consumeEvent) {
                    if (keyAction == KeyEvent.ACTION_DOWN) {
                        tickle();
                    }
                }
        }
        return consumeEvent;
    }

    private View.OnKeyListener getInputEventHandler() {
        return (View.OnKeyListener) Helpers.getField(this, "mInputEventHandler");
    }

    private boolean isInSeek() {
        Object mInSeek = Helpers.getField(this, "mInSeek");
        return mInSeek != null && (boolean) mInSeek;
    }

    /**
     * 检查是否有字幕文本
     */
    private boolean hasSubtitleText() {
        try {
            SubtitleManager subtitleManager = getSubtitleManager();
            if (subtitleManager != null) {
                // 首先检查字幕控制器
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null) {
                    boolean hasText = controller.hasSubtitleText();
                    android.util.Log.d(TAG, "通过WordSelectionController检查字幕: " + hasText);
                    return hasText;
                }

                // 如果没有控制器或控制器返回false，检查字幕管理器是否存在
                if (subtitleManager != null) {
                    // 如果字幕管理器存在，假定有字幕
                    android.util.Log.d(TAG, "找到字幕管理器，假定有字幕");
                    return true;
                }
            }

            android.util.Log.d(TAG, "未找到字幕");
            return false;
        } catch (Exception e) {
            android.util.Log.e(TAG, "检查字幕时出错", e);
            return false;
        }
    }

    /**
     * 处理左右方向键按下事件
     */
    private boolean handleLeftRightKeyDown(KeyEvent event) {
        try {
            int keyCode = event.getKeyCode();
            android.util.Log.d(TAG, "处理左右方向键: " + keyCode + ", 控制栏隐藏: " + !isControlsOverlayVisible());

            // 如果控制栏可见，不处理左右键
            if (isControlsOverlayVisible()) {
                android.util.Log.d(TAG, "控制栏可见，不处理左右键");
                return false;
            }

            // 检查是否有字幕
            boolean hasSubtitles = hasSubtitleText();
            android.util.Log.d(TAG, "有字幕: " + hasSubtitles);

            // 如果没有字幕，直接消费事件，不做任何操作，并且不暂停视频
            if (!hasSubtitles) {
                android.util.Log.d(TAG, "没有字幕，左右方向键不做任何操作，直接消费事件");
                return true; // 直接消费事件，阻止事件继续传递，从而避免视频暂停
            }

            // 检查是否已经在选词模式
            SubtitleManager subtitleManager = getSubtitleManager();
            if (subtitleManager != null) {
                SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
                if (controller != null) {
                    // 如果已经在选词模式，直接处理按键
                    if (controller.isInWordSelectionMode()) {
                        android.util.Log.d(TAG, "已在选词模式，交给选词控制器处理");
                        return controller.handleKeyEvent(event);
                    }

                    // 如果不在选词模式，进入选词模式
                    android.util.Log.d(TAG, "进入选词模式");
                    boolean fromStart = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT); // 左键从最后一个词开始，右键从第一个词开始
                    controller.enterWordSelectionMode(fromStart);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            android.util.Log.e(TAG, "处理左右键时出错", e);
            return false;
        }
    }

    /**
     * 检查是否处于选词模式
     */
    private boolean isInWordSelectionMode() {
        SubtitleManager subtitleManager = getSubtitleManager();
        if (subtitleManager != null) {
            SubtitleWordSelectionController controller = subtitleManager.getWordSelectionController();
            return controller != null && controller.isInWordSelectionMode();
        }
        return false;
    }

    /**
     * 获取字幕管理器
     */
    private SubtitleManager getSubtitleManager() {
        if (getActivity() instanceof PlaybackActivity) {
            PlaybackActivity activity = (PlaybackActivity) getActivity();
            PlaybackView view = activity.getPlaybackView();
            if (view != null) {
                return view.getSubtitleManager();
            }
        }
        return null;
    }
}
