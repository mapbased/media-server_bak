package com.sengled.media.server.rtsp.handler;

import com.sengled.media.Version;
import com.sengled.media.server.RtspServlet;
import com.sengled.media.server.rtsp.RtspServerContext;
import com.sengled.media.server.rtsp.servlet.AnnounceStreamServlet;
import com.sengled.media.server.rtsp.servlet.DescribeStreamServlet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaders;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static io.netty.handler.codec.rtsp.RtspMethods.*;

/**
 * Rtsp 服务端协议栈
 *
 * @author 陈修恒
 * @date 2016年6月1日
 */
public class RtspServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static Logger LOGGER = LoggerFactory.getLogger(RtspServerHandler.class);

    private final RtspServerContext serverContext;

    private RtspServlet servlet;
    private Set<HttpMethod> options;


    public RtspServerHandler(RtspServerContext rtspServerContext, HttpMethod[] options) {
        this.serverContext = rtspServerContext;
        this.options = new HashSet<>(Arrays.asList(options));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleEvt = (IdleStateEvent) evt;
            switch (idleEvt.state()) {
                case READER_IDLE:
                    if (servlet instanceof AnnounceStreamServlet) {
                        exceptionCaught(ctx, new TimeoutException(idleEvt.state() + " timeout"));
                    }
                    break;
                case WRITER_IDLE:
                    if (servlet instanceof DescribeStreamServlet) {
                        exceptionCaught(ctx, new TimeoutException(idleEvt.state() + " timeout"));
                    }
                    break;
                case ALL_IDLE:
                    // 既没有输入，也没有输出就断开
                    exceptionCaught(ctx, new TimeoutException(idleEvt.state() + " timeout"));
                    break;
                default:
                    LOGGER.debug("{} timeout", idleEvt.state());
                    break;
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {

    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closeServlet();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Object error = null != servlet ? servlet : ctx.channel().remoteAddress();
        if (cause instanceof IOException) {
            LOGGER.info("{} {}", error, cause.getMessage());
        } else if (cause instanceof TimeoutException) {
            LOGGER.error("{} {}.", error, cause.getMessage());
        } else if (cause instanceof IndexOutOfBoundsException) {
            LOGGER.error("{}", error, cause);
        } else {
            LOGGER.error("{}", error, cause);
        }
        closeServlet();
    }

    private void closeServlet() {
        if (null != servlet) {
            try {
                servlet.close();
            } catch (IOException e) {
                LOGGER.error("{} close servlet fail with {}", servlet.getSession(), e.getMessage());
            }
            servlet = null;
        }
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        HttpMethod method = request.getMethod();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} {}, from {}.", request.getMethod(), request.getUri(), ctx.channel().remoteAddress());
            LOGGER.debug("{}", request.content().toString(Charset.forName("UTF-8")));
        }

        final ChannelPromise promise = ctx.newPromise();
        final FullHttpResponse response = newResponse(request);
        try {
            if (RtspMethods.OPTIONS.equals(method)) {
                response.headers().add(RtspHeaders.Names.PUBLIC, getOptionString());
            } else if (TEARDOWN.equals(method)) {
                servlet.teardown(request, response);
            } else if (ANNOUNCE.equals(method) && null == servlet) {
                servlet = new AnnounceStreamServlet(serverContext, ctx);
                servlet.announce(request, response);
            } else if (DESCRIBE.equals(method) && null == servlet) {
                servlet = new DescribeStreamServlet(serverContext, ctx);
                servlet.describe(request, response);
            } else if (null != servlet) {
                if (GET_PARAMETER.equals(method)) {
                    servlet.getParameter(request, response);
                } else if (PAUSE.equals(method)) {
                    servlet.pause(request, response);
                } else if (PLAY.equals(method)) {
                    servlet.play(request, response);
                    LOGGER.info("{} {}, from {}.", request.getMethod(), request.getUri(), servlet);
                } else if (RECORD.equals(method)) {
                    servlet.record(request, response);
                    LOGGER.info("{} {}, from {}.", request.getMethod(), request.getUri(), servlet);
                } else if (REDIRECT.equals(method)) {
                    servlet.redirect(request, response);
                } else if (SETUP.equals(method)) {
                    servlet.setup(request, response);
                } else if (SET_PARAMETER.equals(method)) {
                    servlet.setParameter(request, response);
                } else {
                    response.setStatus(HttpResponseStatus.CONFLICT);
                }
            } else {
                response.setStatus(HttpResponseStatus.BAD_REQUEST);
            }

        } catch (Exception ex) {
            response.setStatus(HttpResponseStatus.BAD_GATEWAY);
            final Object message = null != servlet ? servlet : "handshak failed";
            LOGGER.error("{}: {}", message, ex.getMessage(), ex);
        }

        // set session id
        if (null != servlet && null != servlet.getSession()) {
            response.headers().add(RtspHeaders.Names.SESSION, servlet.getSession().getSessionId());
        }
        // 链接是需要关闭的，但是没有 Connection 属性， 则自动补充上
        if (!isOK(response) && !isConnectionClose(response)) {
            response.headers().add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        }

        final Future<Void> f = ctx.writeAndFlush(response, promise);

        // 如果连接显示为关闭，则自动关闭
        if (isConnectionClose(response) || !isOK(response)) {
            f.addListener(new GenericFutureListener<Future<? super Void>>() {
                public void operationComplete(Future<? super Void> future) throws Exception {
                    ctx.close();
                }
            });
            LOGGER.info("close {} for {} {}, {}.", ctx.channel().remoteAddress(), method, request.getUri(), response.getStatus());
        }
    }

    private boolean isConnectionClose(final FullHttpResponse response) {
        return HttpHeaders.Values.CLOSE.equals(response.headers().get(HttpHeaders.Names.CONNECTION));
    }

    private boolean isOK(final FullHttpResponse response) {
        return response.getStatus() == HttpResponseStatus.OK
                || response.getStatus() == HttpResponseStatus.UNAUTHORIZED;
    }

    private FullHttpResponse newResponse(FullHttpRequest request) {
        final FullHttpResponse response;
        response = new DefaultFullHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
        response.headers().add(RtspHeaders.Names.DATE, new Timestamp(System.currentTimeMillis()));
        response.headers().add(RtspHeaders.Names.SERVER, Version.server());
        String cseq = request.headers().get(RtspHeaders.Names.CSEQ);
        if (null != cseq) {
            response.headers().add(RtspHeaders.Names.CSEQ, cseq);
        }

        return response;
    }

    private String getOptionString() {
        StringBuilder buf = new StringBuilder();
        for (HttpMethod option : options) {
            if (buf.length() > 0) {
                buf.append(", ");
            }
            buf.append(option.name());
        }

        return buf.toString();
    }
}
