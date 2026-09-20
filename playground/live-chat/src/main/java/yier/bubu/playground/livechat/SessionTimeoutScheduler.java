package yier.bubu.playground.livechat;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class SessionTimeoutScheduler {

    private static final AttributeKey<Session> SESSION_KEY = AttributeKey.valueOf("yier.bubu.playground.livechat.session");

    private final HashedWheelTimer timer;
    private final long timeoutMillis;

    public SessionTimeoutScheduler(HashedWheelTimer timer, Duration timeout) {
        this.timer = timer;
        this.timeoutMillis = timeout.toMillis();
    }

    public void start(Channel channel, Runnable onTimeout) {
        Session session = new Session(channel, onTimeout);
        channel.attr(SESSION_KEY).set(session);
        schedule(session, timeoutMillis);
    }

    public void touch(Channel channel) {
        Session session = channel.attr(SESSION_KEY).get();
        if (session == null) {
            return;
        }
        session.lastActiveAt = System.currentTimeMillis();
        Timeout pending = session.pending;
        if (pending != null) {
            pending.cancel();
        }
        schedule(session, timeoutMillis);
    }

    public void cancel(Channel channel) {
        Session session = channel.attr(SESSION_KEY).get();
        if (session == null) {
            return;
        }
        Timeout pending = session.pending;
        if (pending != null) {
            pending.cancel();
        }
    }

    private void schedule(Session session, long delayMillis) {
        session.pending = timer.newTimeout(ignored -> onFire(session), Math.max(delayMillis, 1L), TimeUnit.MILLISECONDS);
    }

    private void onFire(Session session) {
        long remaining = timeoutMillis - (System.currentTimeMillis() - session.lastActiveAt);
        if (remaining > 0) {
            schedule(session, remaining);
            return;
        }
        if (session.channel.isActive()) {
            session.onTimeout.run();
        }
    }

    private static final class Session {
        private final Channel channel;
        private final Runnable onTimeout;
        private volatile long lastActiveAt = System.currentTimeMillis();
        private volatile Timeout pending;

        private Session(Channel channel, Runnable onTimeout) {
            this.channel = channel;
            this.onTimeout = onTimeout;
        }
    }
}
