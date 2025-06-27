package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

import java.io.IOException;
import java.util.Map;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

/**
 * 用于创建可以处理SMB文件的数据源工厂
 */
public class SmbDataSourceFactory implements DataSource.Factory {
    private static final String TAG = "SmbDataSourceFactory";
    private final Context mContext;
    private final DefaultDataSourceFactory mDefaultDataSourceFactory;
    private final GeneralData mGeneralData;

    public SmbDataSourceFactory(Context context) {
        mContext = context;
        mGeneralData = GeneralData.instance(context);
        
        // 创建默认数据源工厂用于处理非SMB URL
        DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory("Mozilla/5.0");
        mDefaultDataSourceFactory = new DefaultDataSourceFactory(context, httpDataSourceFactory);
    }

    @Override
    public DataSource createDataSource() {
        return new SmbDataSource(mContext, mDefaultDataSourceFactory.createDataSource());
    }

    /**
     * 可以处理SMB协议的数据源
     */
    private class SmbDataSource implements DataSource {
        private final Context mContext;
        private final DataSource mFallbackDataSource;
        private SmbFileInputStream mSmbFileInputStream;
        private String mCurrentUri;
        private Uri mUri;

        public SmbDataSource(Context context, DataSource fallbackDataSource) {
            mContext = context;
            mFallbackDataSource = fallbackDataSource;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            mUri = dataSpec.uri;
            mCurrentUri = dataSpec.uri.toString();

            // 如果是SMB协议，使用JCIFS处理
            if (mCurrentUri.startsWith("smb://")) {
                Log.d(TAG, "Opening SMB URI: " + mCurrentUri);
                
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

                    SmbFile smbFile = new SmbFile(mCurrentUri, authenticatedContext);
                    long fileSize = smbFile.length();
                    mSmbFileInputStream = new SmbFileInputStream(smbFile);
                    
                    // 处理偏移量
                    long skipped = mSmbFileInputStream.skip(dataSpec.position);
                    if (skipped != dataSpec.position) {
                        throw new IOException("Failed to skip to position " + dataSpec.position);
                    }

                    // 返回可读取的长度
                    if (dataSpec.length != C.LENGTH_UNSET) {
                        return dataSpec.length;
                    } else {
                        return fileSize - dataSpec.position;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error opening SMB file: " + e.getMessage(), e);
                    throw new IOException("Error opening SMB file: " + e.getMessage(), e);
                }
            } else {
                // 使用默认数据源处理非SMB协议
                return mFallbackDataSource.open(dataSpec);
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (mSmbFileInputStream != null) {
                return mSmbFileInputStream.read(buffer, offset, length);
            } else {
                return mFallbackDataSource.read(buffer, offset, length);
            }
        }

        @Override
        public void close() throws IOException {
            if (mSmbFileInputStream != null) {
                try {
                    mSmbFileInputStream.close();
                } finally {
                    mSmbFileInputStream = null;
                }
            } else {
                mFallbackDataSource.close();
            }
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            mFallbackDataSource.addTransferListener(transferListener);
        }

        @Override
        public Uri getUri() {
            return mUri;
        }
    }
} 