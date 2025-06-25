package com.liskovsoft.smartyoutubetv2.common.smb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.GeneralSettingsPresenter;

public class SmbDialog {
    public static final String TAG = SmbDialog.class.getSimpleName();
    private final Context mContext;
    private AlertDialog mSmbConfigDialog;
    private final GeneralData mGeneralData;
    private final Handler mHandler;

    public SmbDialog(Context context) {
        mContext = context;
        mGeneralData = GeneralData.instance(context);
        mHandler = new Handler(Looper.myLooper());
    }

    public boolean isEnabled() {
        return mGeneralData.isSmbPlayerEnabled();
    }
    
    public boolean isConfigured() {
        return mGeneralData.isSmbServerConfigured();
    }

    public void enable(boolean checked) {
        mGeneralData.enableSmbPlayer(checked);
        if (checked) {
            showSmbConfigDialog();
        }
    }
    
    public void enableConfig(boolean checked) {
        if (checked) {
            showSmbConfigDialog();
        } else {
            mGeneralData.enableSmbServerConfig(false);
        }
    }

    protected void appendStatusMessage(String msgFormat, Object ...args) {
        TextView statusView = mSmbConfigDialog.findViewById(R.id.smb_config_message);
        String message = String.format(msgFormat, args);
        if (statusView.getText().toString().isEmpty())
            statusView.append(message);
        else
            statusView.append("\n"+message);
    }

    protected void appendStatusMessage(int resId, Object ...args) {
        appendStatusMessage(mContext.getString(resId), args);
    }

    protected boolean validateSmbConfigFields() {
        boolean isConfigValid = true;
        String serverUrl = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_server_url)).getText().toString();
        if (serverUrl.isEmpty()) {
            isConfigValid = false;
            appendStatusMessage(R.string.smb_server_url_invalid);
        }
        
        // 用户名和密码可以为空，但如果有一个不为空，另一个也不应该为空
        String username = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_username)).getText().toString();
        String password = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_password)).getText().toString();
        if (username.isEmpty() != password.isEmpty()) {
            isConfigValid = false;
            appendStatusMessage(R.string.smb_credentials_invalid);
        }
        
        return isConfigValid;
    }

    public void showSmbConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext, R.style.AppDialog);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View contentView = inflater.inflate(R.layout.smb_dialog, null);

        KeyHelpers.fixShowKeyboard(
                contentView.findViewById(R.id.smb_server_url),
                contentView.findViewById(R.id.smb_username),
                contentView.findViewById(R.id.smb_password)
        );

        String serverUrl = mGeneralData.getSmbServerUrl();
        String username = mGeneralData.getSmbUsername();
        String password = mGeneralData.getSmbPassword();

        ((EditText) contentView.findViewById(R.id.smb_server_url)).setText(serverUrl);
        ((EditText) contentView.findViewById(R.id.smb_username)).setText(username);
        ((EditText) contentView.findViewById(R.id.smb_password)).setText(password);

        // keep empty, will override below.
        // https://stackoverflow.com/a/15619098/5379584
        mSmbConfigDialog = builder
                .setTitle(R.string.smb_settings_title)
                .setView(contentView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> { })
                .create();

        mSmbConfigDialog.show();

        mSmbConfigDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((view) -> {
            ((TextView) mSmbConfigDialog.findViewById(R.id.smb_config_message)).setText("");
            boolean isValid = validateSmbConfigFields();
            if (!isValid) {
                appendStatusMessage(R.string.smb_application_aborted);
            } else {
                String serverUrl1 = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_server_url)).getText().toString();
                String username1 = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_username)).getText().toString();
                String password1 = ((EditText) mSmbConfigDialog.findViewById(R.id.smb_password)).getText().toString();
                
                Log.d(TAG, "Saving SMB info: " + serverUrl1);
                mGeneralData.setSmbServerUrl(serverUrl1);
                mGeneralData.setSmbUsername(username1);
                mGeneralData.setSmbPassword(password1);
                mGeneralData.persistStateNow();
                
                mSmbConfigDialog.dismiss();
                
                // 配置完成后重新显示SMB播放器设置界面
                mHandler.post(() -> {
                    GeneralSettingsPresenter.instance(mContext).showSmbPlayerSettings();
                });
            }
        });

        mSmbConfigDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener((view) -> {
            mSmbConfigDialog.dismiss();
            
            // 取消配置后重新显示SMB播放器设置界面
            mHandler.post(() -> {
                GeneralSettingsPresenter.instance(mContext).showSmbPlayerSettings();
            });
        });
    }
} 