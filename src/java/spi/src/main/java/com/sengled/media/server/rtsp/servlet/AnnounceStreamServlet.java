package com.sengled.media.server.rtsp.servlet;

import com.sengled.media.server.rtsp.InterleavedFrame;
import com.sengled.media.server.rtsp.RtspServerContext;
import com.sengled.media.server.rtsp.RtspSession;
import com.sengled.media.server.rtsp.Transport;
import com.sengled.media.server.rtsp.rtp.MediaDescriptionParserFactory;
import com.sengled.media.server.rtsp.rtp.packetizer.RtpDePacketizer;
import com.sengled.media.server.rtsp.sdp.SdpParser;
import com.sengled.media.url.URLObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.rtsp.RtspHeaders;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sdp.SessionDescription;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Vector;

/**
 * 处理 RTSP 上行请求
 *
 * @author chenxh
 */
public class AnnounceStreamServlet extends RtspServletAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnnounceStreamServlet.class);

    private RtspServerContext serverContext;
    private RtspOverTcpSource source;
    private SessionDescription sd;
    private boolean recorded;

    public AnnounceStreamServlet(RtspServerContext serverContext, ChannelHandlerContext channelHandlerContext) {
        super(channelHandlerContext);

        this.serverContext = serverContext;
    }

    @Override
    public void announce(FullHttpRequest request, FullHttpResponse response) {
        URLObject url = URLObject.parse(request.getUri());

        ChannelHandlerContext channelHandlerContext = getChannelHandlerContext();
        String sdp = request.content().toString(Charset.forName("UTF-8"));
        try {
            this.sd = SdpParser.parse(sdp);
            RtpDePacketizer<?>[] rtpStreams = MediaDescriptionParserFactory.parse(sd);
            this.source = new RtspOverTcpSource(channelHandlerContext, serverContext, url, rtpStreams);
        } catch (ParseException | SdpException e) {
            // SDP 异常了
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
            LOGGER.error("[{}] bad sdp {}", url.getUrl(), sdp);
        }
    }

    @Override
    public void setup(FullHttpRequest request, FullHttpResponse response) {
        // 必须先调用 announce
        if (null == source) {
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
            LOGGER.error("call setup before announce, uri = {}, agent = {}", request.getUri(), request.headers().get(HttpHeaders.Names.USER_AGENT));
            return;
        }

        // 只支持 RTP over TCP 
        String exceptTransport = request.headers().get(RtspHeaders.Names.TRANSPORT);
        Transport t = Transport.parse(exceptTransport);
        if (!isTransportSupported(t)) {
            LOGGER.error("unsupported {}", exceptTransport);
            response.setStatus(HttpResponseStatus.NOT_IMPLEMENTED);
            return;
        }


        // 回复支持
        try {
            String url = request.getUri();
            String streamName = FilenameUtils.getName(url);
            Vector<MediaDescription> mdList = sd.getMediaDescriptions(true);
            for (int i = 0; i < mdList.size(); i++) {
                MediaDescription md = mdList.get(i);
                String control = md.getAttribute("control");

                if (StringUtils.equals(streamName, FilenameUtils.getName(control))) {
                    source.setTransport(i, t);
                    response.headers().add(RtspHeaders.Names.TRANSPORT, t.toString());
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Fail read sdp {}", e.getMessage(), e);
        }

        response.setStatus(HttpResponseStatus.BAD_REQUEST);
    }

    @Override
    public void play(FullHttpRequest request, FullHttpResponse response) {
        record(request, response);
    }

    @Override
    public void record(FullHttpRequest request, FullHttpResponse response) {
        if (recorded) {
            LOGGER.warn("[{}] record again", source.getToken());
            return;
        }

        if (null != source) {
            source.start();
            recorded = true;
        } else {
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
        }
    }

    @Override
    public void getParameter(FullHttpRequest request, FullHttpResponse response) {

        // 对讲里面，通过 CameraConnectStatus 
        String key = request.content().toString(Charset.defaultCharset());
        if (null != source && StringUtils.startsWith(key, "CameraConnectStatus")) {
            LOGGER.info("{}:{}", request.getUri(), request.content().toString(Charset.defaultCharset()));
            HttpResponseStatus status = HttpResponseStatus.OK;
            final String result = null != source.getMediaSink(RtspOverTcpSink.class) ? "ok" : "noSinks";
            String content = "CameraConnectStatus:" + result; // 必须得有空格

            response.setStatus(status);
            response.content().writeBytes(content.getBytes());
            response.headers().add(RtspHeaders.Names.CONTENT_TYPE, "text/parameters");
            response.headers().add(RtspHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());

            LOGGER.info("response {}, src = [{}]", request.getUri(), content);
        } else {
            super.getParameter(request, response);
        }
    }

    @Override
    public void destroy() {
        try {
            source.close();
        } finally {
            close();
        }
    }

    @Override
    public void channelRead(InterleavedFrame frame) {
        try {
            if (null != source && !source.isClosed()) {
                source.onInterleavedFrameReceived(frame.retain());
                return;
            }
        } finally {
            frame.release();
        }

        throw new UnsupportedOperationException();
    }

    @Override
    public RtspSession getSession() {
        return null != source ? source.getSession() : null;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{").append(getClass().getSimpleName());
        buf.append(", token=").append(null != source ? source.getToken() : null);
        buf.append(", ").append(this.getChannelHandlerContext().channel().remoteAddress());
        buf.append("}");
        return buf.toString();
    }

}