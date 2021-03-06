package platform.connection;

import platform.messages.ILogin;
import common.util.KeyValue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import platform.service.AuthService;
import platform.service.LoginController;
import platform.service.MessageController;
import platform.session.Session;
import platform.session.SessionMap;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@ChannelHandler.Sharable
public class MessageHandler extends ChannelInboundHandlerAdapter {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Resource
    private AuthService authService;

    @Resource
    private LoginController loginController;

    @Resource
    private SessionMap sessionMap;

    private Map<Channel, Session> openConnections = new ConcurrentHashMap<>();

    private Map<Class, MessageController> controllers = Collections.emptyMap();

    @Autowired(required = false)
    private void setControllers(List<MessageController> controllers) {
        this.controllers = controllers.stream().collect(Collectors.toMap(MessageController::messageClass, c -> c));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Channel CONNECTED: {}", ctx.channel());
        }
        openConnections.put(ctx.channel(), Session.EMPTY_SESSION);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, final Object message) throws Exception {
        var channel = ctx.channel();
        if (message instanceof ILogin) {
            var error = new KeyValue<Integer, String>();
            var session = authService.authorize((ILogin) message, channel, error);
            if (session != null) {
                openConnections.put(channel, session);
                var outMessages = loginController.onSuccessLogin(session.profile);
                outMessages.forEach(channel::write);
                channel.flush();
            } else {
                channel.writeAndFlush(loginController.onLoginError((ILogin) message, error));
            }
        } else {
            var messageController = controllers.get(message.getClass());
            if (messageController != null) {
                var outMessage = messageController.onMessage(message, openConnections.get(channel).profile);
                if (outMessage != null) {
                    channel.writeAndFlush(outMessage);
                }
            } else {
                log.error("Controller for message of class [{}] not found!", message.getClass());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Channel channel = ctx.channel();
        log.error(cause.toString(), cause);
        channel.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Channel CLOSED: {}", ctx.channel());
        }
        var session = openConnections.remove(ctx.channel());
        if (session != null && session.profile != null) {
            sessionMap.closeSession(session.profile.id());
        }
    }

}
