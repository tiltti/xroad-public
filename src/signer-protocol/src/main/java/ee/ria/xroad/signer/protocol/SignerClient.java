/**
 * The MIT License
 * Copyright (c) 2015 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ee.ria.xroad.signer.protocol;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.joda.time.DateTime;

import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.UntypedActor;

import ee.ria.xroad.common.CodedException;
import ee.ria.xroad.common.SystemProperties;
import ee.ria.xroad.signer.protocol.message.ConnectionPing;
import ee.ria.xroad.signer.protocol.message.ConnectionPong;

import static ee.ria.xroad.common.ErrorCodes.X_HTTP_ERROR;
import static ee.ria.xroad.signer.protocol.ComponentNames.REQUEST_PROCESSOR;
import static ee.ria.xroad.signer.protocol.ComponentNames.SIGNER;

/**
 * Signer client is used to send messages to signer from other components
 * (running as separate JVM processes).
 */
@Slf4j
public final class SignerClient {

    private static final int TIMEOUT_MILLIS =
            SystemProperties.getSignerClientTimeout();

    private static ActorSystem actorSystem;
    private static ActorSelection requestProcessor;

    private static Boolean connected;

    private SignerClient() {
    }

    /**
     * Initializes the client with the provided actor system.
     * @param system the actor system
     * @throws Exception if an error occurs
     */
    public static void init(ActorSystem system) throws Exception {
        log.trace("init()");

        if (SignerClient.actorSystem == null) {
            SignerClient.actorSystem = system;

            requestProcessor = system.actorSelection(
                    getSignerPath() + "/user/" + REQUEST_PROCESSOR);

            system.actorOf(Props.create(ConnectionPinger.class),
                    "ConnectionPinger");
        }
    }

    /**
     * Sends a message and waits for any response. Does not return the response.
     * If the response is an exception, throws it.
     * @param message the message
     * @param receiver the receiver actor
     * @throws Exception if the response is an exception
     */
    public static void execute(Object message, ActorRef receiver)
            throws Exception {
        verifyInitialized();
        verifyConnected();

        CountDownLatch latch = new CountDownLatch(1);

        ActorRef executionCtx = actorSystem.actorOf(
                Props.create(ReceiverExecutionCtx.class, latch, receiver));

        requestProcessor.tell(message, executionCtx);
        try {
            waitForResponse(latch);
        } finally {
            executionCtx.tell(PoisonPill.getInstance(), ActorRef.noSender());
        }
    }

    /**
     * Sends a message and waits for a response, returning it. If the response
     * is an exception, throws it.
     * @param <T> the type of result
     * @param message the message
     * @return the response
     * @throws Exception if the response is an exception
     */
    public static <T> T execute(Object message) throws Exception {
        verifyInitialized();
        verifyConnected();

        CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response();

        ActorRef executionCtx = actorSystem.actorOf(
                Props.create(ResponseExecutionCtx.class, latch, response));

        requestProcessor.tell(message, executionCtx);
        try {
            waitForResponse(latch);
            return result(response.getValue());
        } finally {
            executionCtx.tell(PoisonPill.getInstance(), ActorRef.noSender());
        }
    }

    /**
     * Returns the object as the instance or throws exception, if the object
     * is throwable.
     * @param <T> the type of result
     * @param result the result object
     * @return result
     * @throws Exception if the object is throwable
     */
    @SuppressWarnings("unchecked")
    public static <T> T result(Object result) throws Exception {
        if (result instanceof Throwable) {
            throw (Exception) result;
        } else {
            return (T) result;
        }
    }

    private static void waitForResponse(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException();
            }
        } catch (Exception e) {
            throw connectionTimeoutException();
        }
    }

    private static String getSignerPath() {
        return "akka.tcp://" + SIGNER + "@127.0.0.1:"
                + SystemProperties.getSignerPort();
    }

    private static void verifyInitialized() {
        if (actorSystem == null) {
            throw new IllegalStateException("SignerClient is not initialized");
        }
    }

    private static void verifyConnected() {
        if (connected != null && !connected) {
            throw connectionTimeoutException();
        }
    }

    private static CodedException connectionTimeoutException() {
        return new CodedException(X_HTTP_ERROR,
                "Connection to Signer (port %s) timed out",
                SystemProperties.getSignerPort());
    }

    @Data
    private static class Response {
        private Object value;
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static class ReceiverExecutionCtx extends UntypedActor {

        private final CountDownLatch latch;
        private final ActorRef receiver;

        @Override
        public void onReceive(Object message) throws Exception {
            log.trace("onReceive({})", message);

            if (receiver != ActorRef.noSender()) {
                receiver.tell(message, getSender());
            }

            latch.countDown();
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static class ResponseExecutionCtx extends UntypedActor {

        private final CountDownLatch latch;
        private final Response response;

        @Override
        public void onReceive(Object message) throws Exception {
            log.trace("onReceive({})", message);

            response.setValue(message);
            latch.countDown();
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static class ConnectionPinger extends UntypedActor {

        private static final FiniteDuration INITIAL_DELAY =
                FiniteDuration.create(100, TimeUnit.MILLISECONDS);

        private final FiniteDuration interval =
                FiniteDuration.create(5, TimeUnit.SECONDS);

        private Cancellable tick;
        private DateTime lastPong;
        private boolean firstPing = true;

        @Override
        public void preStart() throws Exception {
            tick = start();
        }

        @Override
        public void postStop() {
            tick.cancel();
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof ConnectionPing) {
                requestProcessor.tell(message, getSelf());

                if (!firstPing) {
                    checkConnected();
                } else {
                    firstPing = false;
                }
            } else if (message instanceof ConnectionPong) {
                connected = true;
                lastPong = new DateTime();
            }
        }

        private void checkConnected() {
            if (lastPong == null || hasTimedOut()) {
                connected = false;
                log.trace("Connection timed out");
            }
        }

        private boolean hasTimedOut() {
            long now = new DateTime().getMillis();
            long diff = now - lastPong.getMillis();
            return diff > TIMEOUT_MILLIS;
        }

        private Cancellable start() {
            return getContext().system().scheduler().schedule(
                    INITIAL_DELAY, interval, getSelf(), new ConnectionPing(),
                    getContext().dispatcher(), ActorRef.noSender());
        }
    }
}
