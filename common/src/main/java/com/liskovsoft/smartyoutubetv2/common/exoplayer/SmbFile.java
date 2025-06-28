package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.text.TextUtils;
import android.util.Log;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;

/**
 * 封装JCIFS SmbFile的工具类，用于检查SMB文件是否存在
 */
public class SmbFile {
    private static final String TAG = "SmbFile";
    private final String mPath;
    private final jcifs.smb.SmbFile mSmbFile;
    
    /**
     * 创建SmbFile实例
     * @param path SMB文件路径
     * @throws Exception 如果创建失败
     */
    public SmbFile(String path) throws Exception {
        mPath = path;
        
        CIFSContext baseContext = SingletonContext.getInstance();
        CIFSContext authenticatedContext;
        
        // 尝试获取用户名和密码
        String username = null;
        String password = null;
        
        try {
            // 尝试从应用程序数据中获取SMB凭据
            com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData generalData = 
                    com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData.instance(null);
            if (generalData != null) {
                username = generalData.getSmbUsername();
                password = generalData.getSmbPassword();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取SMB凭据失败: " + e.getMessage());
        }
        
        // 设置认证上下文
        if (!TextUtils.isEmpty(username)) {
            Log.d(TAG, "使用认证用户名: " + username);
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(username, password);
            authenticatedContext = baseContext.withCredentials(auth);
        } else {
            Log.d(TAG, "使用匿名认证");
            authenticatedContext = baseContext;
        }
        
        // 创建JCIFS SmbFile
        mSmbFile = new jcifs.smb.SmbFile(path, authenticatedContext);
    }
    
    /**
     * 检查SMB文件是否存在
     * @return 如果文件存在则返回true，否则返回false
     */
    public boolean exists() {
        try {
            return mSmbFile.exists();
        } catch (Exception e) {
            Log.e(TAG, "检查文件存在失败: " + mPath + ", " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取文件长度
     * @return 文件长度，如果获取失败则返回-1
     */
    public long length() {
        try {
            return mSmbFile.length();
        } catch (Exception e) {
            Log.e(TAG, "获取文件长度失败: " + mPath + ", " + e.getMessage());
            return -1;
        }
    }
    
    /**
     * 获取原始的JCIFS SmbFile对象
     * @return JCIFS SmbFile对象
     */
    public jcifs.smb.SmbFile getJcifsSmbFile() {
        return mSmbFile;
    }
} 