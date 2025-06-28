package com.liskovsoft.smartyoutubetv2.common.exoplayer;

/**
 * 定义常用的MIME类型常量
 */
public final class MimeTypes {
    private MimeTypes() {
        // 防止实例化
    }

    // 视频MIME类型
    public static final String VIDEO_MP4 = "video/mp4";
    public static final String VIDEO_WEBM = "video/webm";
    public static final String VIDEO_H263 = "video/3gpp";
    public static final String VIDEO_H264 = "video/avc";
    public static final String VIDEO_H265 = "video/hevc";
    public static final String VIDEO_VP8 = "video/x-vnd.on2.vp8";
    public static final String VIDEO_VP9 = "video/x-vnd.on2.vp9";
    public static final String VIDEO_AV1 = "video/av01";
    public static final String VIDEO_MP4V = "video/mp4v-es";
    public static final String VIDEO_MPEG2 = "video/mpeg2";
    public static final String VIDEO_TS = "video/mp2t";

    // 音频MIME类型
    public static final String AUDIO_MP4 = "audio/mp4";
    public static final String AUDIO_AAC = "audio/mp4a-latm";
    public static final String AUDIO_WEBM = "audio/webm";
    public static final String AUDIO_MPEG = "audio/mpeg";
    public static final String AUDIO_OPUS = "audio/opus";
    public static final String AUDIO_VORBIS = "audio/vorbis";
    public static final String AUDIO_AC3 = "audio/ac3";
    public static final String AUDIO_E_AC3 = "audio/eac3";
    public static final String AUDIO_FLAC = "audio/flac";
    public static final String AUDIO_ALAC = "audio/alac";

    // 字幕MIME类型
    public static final String APPLICATION_SUBRIP = "application/x-subrip";
    public static final String TEXT_VTT = "text/vtt";
    public static final String TEXT_SSA = "text/x-ssa";
    public static final String APPLICATION_TTML = "application/ttml+xml";
    public static final String APPLICATION_TX3G = "application/x-quicktime-tx3g";
    public static final String APPLICATION_CEA608 = "application/cea-608";
    public static final String APPLICATION_CEA708 = "application/cea-708";
    public static final String APPLICATION_MP4VTT = "application/x-mp4-vtt";
    public static final String APPLICATION_VOBSUB = "application/vobsub";
    public static final String APPLICATION_PGS = "application/pgs";
    public static final String APPLICATION_DVBSUBS = "application/dvbsubs";

    // 容器MIME类型
    public static final String APPLICATION_MP4 = "application/mp4";
    public static final String APPLICATION_WEBM = "application/webm";
    public static final String APPLICATION_MPD = "application/dash+xml";
    public static final String APPLICATION_M3U8 = "application/x-mpegURL";
    public static final String APPLICATION_SS = "application/vnd.ms-sstr+xml";
} 