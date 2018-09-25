package com.sengled.media.server.rtsp.rtp;

import org.mobicents.media.server.impl.rtcp.RtcpPacket;
import com.sengled.media.server.NettySupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

/**
 * RTP 
 * @author chenxh
 */
public final class RTP {
    private static NettySupport Netty = new NettySupport();
    
    public static final int FIXED_HEADER_SIZE = 12;
    /**
     * The size of the extension header as defined by RFC 3550.
     */
    public static final int EXT_HEADER_SIZE = 4;  
    
    private RTP() {}
    
    
    public static ByteBuf encode(RtcpPacket rtcp) {
        byte[] bytes = new byte[1500];
        int length = rtcp.encode(bytes, 0);

        ByteBuf rtcpBytes = Netty.alloc().buffer(length);
        rtcpBytes.writeBytes(bytes, 0, length);
        return rtcpBytes;
    }
    /**
     * 释放 data, 并返回解码后的 RTP packet
     * 
     * 遇到非法的 RTP 包，则销毁 RTP 并抛出异常
     * @param data
     * @return
     * @author chenxh
     */
    public static RtpPacketI decode(ByteBuf data) {
        try {
            int byte0,byte1,byte2,byte3;
            byte0 = data.readUnsignedByte();
            byte1 = data.readUnsignedByte();
            byte2 = data.readUnsignedByte();
            byte3 = data.readUnsignedByte();
            
            final int version = (byte0 & 0xC0) >> 6;
            //final boolean padding = (byte0 & 0x20) > 0;
            final boolean extension =  (byte0 & 0x10) > 0;
            final int cc = (byte0 & 0x0F);
            
            final boolean marker = (byte1 & 0x80) > 0;
            final int payloadType = (byte1 & 0x7F);
            final int seqNo = (byte2 << 8) | (byte3 << 0);
            
            final long time = data.readUnsignedInt();
            final long ssrc = data.readUnsignedInt();

            data.readSlice(cc * 4); // skip csrc
            
            int profile = 0;
            if (extension) {
                profile = data.readUnsignedShort();
                int extensionLength = data.readUnsignedShort();
                data.skipBytes(extensionLength);
            }
            
            final ByteBuf payload = data.readSlice(data.readableBytes());
            
            return wrap(version, marker, payloadType, seqNo, time, ssrc, profile, payload.retain());
        } finally {
            ReferenceCountUtil.release(data);
        }
    }

    public static MutableRtpPacket wrap(final int version, final boolean marker, final int payloadType,
            final int seqNo, final long time, final long ssrc, int profile, ByteBuf payload) {
        MutableRtpPacket rtp = new MutableRtpPacket(payload);
        rtp.setFlags(profile);
        rtp.setMarker(marker);
        rtp.setPayloadType(payloadType);
        rtp.setSeqNumber(seqNo);
        rtp.setSyncSource(ssrc);
        rtp.setTime(time);
        rtp.setVersion(version);
        return rtp;
    }

    public static ByteBuf encode(RtpPacketI rtp) {
        try {
            final ByteBufAllocator alloc = rtp.content().alloc();
            
            ByteBuf header = alloc.buffer(FIXED_HEADER_SIZE);

            // version, padding, extension, numCC
            int version = 0xC0 & (rtp.getVersion() << 6);
            int numCC = 0;
            header.writeByte(version | numCC);
            
            // marker, payload type
            int marker = rtp.getMarker() ? 0x80 : 0x60;
            int payloadType = 0x7F & rtp.getPayloadType();
            header.writeByte(marker | payloadType);

            // seq num
            header.writeShort(0xFFFF & rtp.getSeqNumber());
            
            // rtp time
            header.writeInt((int)(0xFFFFFFFF & rtp.getTime()));
            
            // ssrc
            header.writeInt((int)(0xFFFFFFFF & rtp.getSyncSource()));
            
            return alloc.compositeBuffer()
                .addComponent(true, header)
                .addComponent(true, rtp.content().duplicate().retain());
        } finally {
            rtp.release();
        }
    }

}