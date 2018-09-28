package com.sengled.media.server.rtsp.rtp;

import java.util.Arrays;
import java.util.List;

/**
 * @author las
 * @date 18-9-28
 */
public class FixedRtpHeader implements RtpHeader {

    private int version;
    private boolean padding;
    private boolean extension;
    private int cc;
    private boolean marker;

    private int payloadType;
    private int seqNumber;

    private long time;
    private long syncSource;
    private List<Long> cSources;

    private int profile;
    private int headerExtensionLength;
    private byte[] headerExtension;

    public FixedRtpHeader(int version, boolean padding, boolean extension, int cc, boolean marker, int payloadType, int seqNumber, long time, long syncSource, List<Long> cSources, int profile, int headerExtensionLength, byte[] headerExtension) {
        this.version = version;
        this.padding = padding;
        this.extension = extension;
        this.cc = cc;
        this.marker = marker;
        this.payloadType = payloadType;
        this.seqNumber = seqNumber;
        this.time = time;
        this.syncSource = syncSource;
        this.cSources = cSources;
        this.profile = profile;
        this.headerExtensionLength = headerExtensionLength;
        this.headerExtension = headerExtension;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public boolean padding() {
        return padding;
    }

    @Override
    public boolean extension() {
        return extension;
    }

    @Override
    public int cc() {
        return cc;
    }

    @Override
    public boolean marker() {
        return marker;
    }

    @Override
    public int payloadType() {
        return payloadType;
    }

    @Override
    public int seqNumber() {
        return seqNumber;
    }

    @Override
    public long time() {
        return time;
    }

    @Override
    public long SSRC() {
        return syncSource;
    }

    @Override
    public List<Long> CSRC() {
        return cSources;
    }

    @Override
    public int profile() {
        return profile;
    }

    @Override
    public int extensionLength() {
        return headerExtensionLength;
    }

    @Override
    public byte[] extensionHeader() {
        return headerExtension;
    }


    @Override
    public String toString() {
        return "FixedRtpHeader{" +
                "version=" + version +
                ", padding=" + padding +
                ", extension=" + extension +
                ", cc=" + cc +
                ", marker=" + marker +
                ", payloadType=" + payloadType +
                ", seqNumber=" + seqNumber +
                ", time=" + time +
                ", syncSource=" + syncSource +
                ", cSources=" + cSources +
                ", profile=" + profile +
                ", headerExtensionLength=" + headerExtensionLength +
                ", headerExtension=" + Arrays.toString(headerExtension) +
                '}';
    }
}