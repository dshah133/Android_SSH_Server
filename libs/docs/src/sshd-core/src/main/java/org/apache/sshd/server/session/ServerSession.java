/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.server.session;

import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.mina.core.session.IoSession;
import org.apache.sshd.agent.AgentForwardSupport;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Channel;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.KeyExchange;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.server.ServerFactoryManager;
import org.apache.sshd.server.UserAuth;
import org.apache.sshd.server.channel.OpenChannelException;
import org.apache.sshd.server.x11.X11ForwardSupport;

/**
 *
 * TODO: handle key re-exchange
 *          key re-exchange should be performed after each gigabyte of transferred data
 *          or one hour time connection (see RFC4253, section 9)
 *
 * TODO: better use of SSH_MSG_DISCONNECT and disconnect error codes
 *
 * TODO: use a single Timer for on the server for all sessions
 *
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ServerSession extends AbstractSession {

    private Timer timer;
    private TimerTask authTimerTask;
    private State state = State.ReceiveKexInit;
    private String username;
    private int maxAuthRequests = 20;
    private int nbAuthRequests;
    private int authTimeout = 10 * 60 * 1000; // 10 minutes in milliseconds
    private boolean allowMoreSessions = true;
    private final TcpipForwardSupport tcpipForward;
    private final AgentForwardSupport agentForward;
    private final X11ForwardSupport x11Forward;

    private List<NamedFactory<UserAuth>> userAuthFactories;

    private enum State {
        ReceiveKexInit, Kex, ReceiveNewKeys, WaitingUserAuth, UserAuth, Running, Unknown
    }

    public ServerSession(FactoryManager server, IoSession ioSession) throws Exception {
        super(server, ioSession);
        maxAuthRequests = getIntProperty(FactoryManager.MAX_AUTH_REQUESTS, maxAuthRequests);
        authTimeout = getIntProperty(FactoryManager.AUTH_TIMEOUT, authTimeout);
        tcpipForward = new TcpipForwardSupport(this);
        agentForward = new AgentForwardSupport(this);
        x11Forward = new X11ForwardSupport(this);
        log.info("Session created...");
        sendServerIdentification();
        sendKexInit();
    }

    @Override
    public CloseFuture close(boolean immediately) {
        unscheduleAuthTimer();
        tcpipForward.close();
        agentForward.close();
        x11Forward.close();
        return super.close(immediately);
    }

    public String getNegociated(int index) {
        return negociated[index];
    }

    public KeyExchange getKex() {
        return kex;
    }

    public ServerFactoryManager getServerFactoryManager() {
        return (ServerFactoryManager) factoryManager;
    }

    public String getUsername() {
        return username;
    }

    protected void handleMessage(Buffer buffer) throws Exception {
        SshConstants.Message cmd = buffer.getCommand();
        log.debug("Received packet {}", cmd);
        switch (cmd) {
            case SSH_MSG_DISCONNECT: {
                int code = buffer.getInt();
                String msg = buffer.getString();
                log.info("Received SSH_MSG_DISCONNECT (reason={}, msg={})", code, msg);
                close(true);
                break;
            }
            case SSH_MSG_UNIMPLEMENTED: {
                int code = buffer.getInt();
                log.info("Received SSH_MSG_UNIMPLEMENTED #{}", code);
                break;
            }
            case SSH_MSG_DEBUG: {
                boolean display = buffer.getBoolean();
                String msg = buffer.getString();
                log.info("Received SSH_MSG_DEBUG (display={}) '{}'", display, msg);
                break;
            }
            case SSH_MSG_IGNORE:
                log.info("Received SSH_MSG_IGNORE");
                break;
            default:
                switch (state) {
                    case ReceiveKexInit:
                        if (cmd != SshConstants.Message.SSH_MSG_KEXINIT) {
                            log.error("Ignoring command " + cmd + " while waiting for " + SshConstants.Message.SSH_MSG_KEXINIT);
                            break;
                        }
                        log.info("Received SSH_MSG_KEXINIT");
                        receiveKexInit(buffer);
                        negociate();
                        kex = NamedFactory.Utils.create(factoryManager.getKeyExchangeFactories(), negociated[SshConstants.PROPOSAL_KEX_ALGS]);
                        kex.init(this, serverVersion.getBytes(), clientVersion.getBytes(), I_S, I_C);
                        state = State.Kex;
                        break;
                    case Kex:
                        buffer.rpos(buffer.rpos() - 1);
                        if (kex.next(buffer)) {
                            sendNewKeys();
                            state = State.ReceiveNewKeys;
                        }
                        break;
                    case ReceiveNewKeys:
                        if (cmd != SshConstants.Message.SSH_MSG_NEWKEYS) {
                            disconnect(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "Protocol error: expected packet " + SshConstants.Message.SSH_MSG_NEWKEYS + ", got " + cmd);
                            return;
                        }
                        log.info("Received SSH_MSG_NEWKEYS");
                        receiveNewKeys(true);
                        state = State.WaitingUserAuth;
                        scheduleAuthTimer();
                        break;
                    case WaitingUserAuth:
                        if (cmd != SshConstants.Message.SSH_MSG_SERVICE_REQUEST) {
                            log.info("Expecting a {}, but received {}", SshConstants.Message.SSH_MSG_SERVICE_REQUEST, cmd);
                            notImplemented();
                        } else {
                            String request = buffer.getString();
                            log.info("Received SSH_MSG_SERVICE_REQUEST '{}'", request);
                            if ("ssh-userauth".equals(request)) {
                                userAuth(buffer);
                            } else {
                                disconnect(SshConstants.SSH2_DISCONNECT_SERVICE_NOT_AVAILABLE, "Bad service request: " + request);
                            }
                        }
                        break;
                    case UserAuth:
                        if (cmd != SshConstants.Message.SSH_MSG_USERAUTH_REQUEST) {
                            disconnect(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "Protocol error: expected packet " + SshConstants.Message.SSH_MSG_USERAUTH_REQUEST + ", got " + cmd);
                            return;
                        }
                        log.info("Received SSH_MSG_USERAUTH_REQUEST");
                        userAuth(buffer);
                        break;
                    case Running:
                        switch (cmd) {
                            case SSH_MSG_SERVICE_REQUEST:
                                serviceRequest(buffer);
                                break;
                            case SSH_MSG_CHANNEL_OPEN:
                                channelOpen(buffer);
                                break;
                            case SSH_MSG_CHANNEL_OPEN_CONFIRMATION:
                                channelOpenConfirmation(buffer);
                                break;
                            case SSH_MSG_CHANNEL_OPEN_FAILURE:
                                channelOpenFailure(buffer);
                                break;
                            case SSH_MSG_CHANNEL_REQUEST:
                                channelRequest(buffer);
                                break;
                            case SSH_MSG_CHANNEL_DATA:
                                channelData(buffer);
                                break;
                            case SSH_MSG_CHANNEL_EXTENDED_DATA:
                                channelExtendedData(buffer);
                                break;
                            case SSH_MSG_CHANNEL_WINDOW_ADJUST:
                                channelWindowAdjust(buffer);
                                break;
                            case SSH_MSG_CHANNEL_EOF:
                                channelEof(buffer);
                                break;
                            case SSH_MSG_CHANNEL_CLOSE:
                                channelClose(buffer);
                                break;
                            case SSH_MSG_GLOBAL_REQUEST:
                                globalRequest(buffer);
                                break;
                            case SSH_MSG_KEXINIT:
                                receiveKexInit(buffer);
                                sendKexInit();
                                negociate();
                                kex = NamedFactory.Utils.create(factoryManager.getKeyExchangeFactories(), negociated[SshConstants.PROPOSAL_KEX_ALGS]);
                                kex.init(this, serverVersion.getBytes(), clientVersion.getBytes(), I_S, I_C);
                                break;
                            case SSH_MSG_KEXDH_INIT:
                                buffer.rpos(buffer.rpos() - 1);
                                if (kex.next(buffer)) {
                                    sendNewKeys();
                                }
                                break;
                            case SSH_MSG_NEWKEYS:
                                receiveNewKeys(true);
                                break;
                            default:
                                throw new IllegalStateException("Unsupported command: " + cmd);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unsupported state: " + state);
                }
        }
    }

    private void scheduleAuthTimer() {
        authTimerTask = new TimerTask() {
            public void run() {
                try {
                    processAuthTimer();
                } catch (IOException e) {
                    // Ignore
                }
            }
        };
        timer = new Timer(true);
        timer.schedule(authTimerTask, authTimeout);
    }

    private void unscheduleAuthTimer() {
        if (authTimerTask != null) {
            authTimerTask.cancel();
            authTimerTask = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void processAuthTimer() throws IOException {
        if (!authed) {
            disconnect(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR,
                       "User authentication has timed out");
        }
    }

    private void sendServerIdentification() {
        if (getFactoryManager().getProperties() != null && getFactoryManager().getProperties().get(ServerFactoryManager.SERVER_IDENTIFICATION) != null) {
            serverVersion = "SSH-2.0-" + getFactoryManager().getProperties().get(ServerFactoryManager.SERVER_IDENTIFICATION);
        } else {
            serverVersion = "SSH-2.0-" + getFactoryManager().getVersion();
        }
        sendIdentification(serverVersion);
    }

    private void sendKexInit() throws IOException {
        serverProposal = createProposal(factoryManager.getKeyPairProvider().getKeyTypes());
        I_S = sendKexInit(serverProposal);
    }

    protected boolean readIdentification(Buffer buffer) throws IOException {
        clientVersion = doReadIdentification(buffer);
        if (clientVersion == null) {
            return false;
        }
        log.info("Client version string: {}", clientVersion);
        if (!clientVersion.startsWith("SSH-2.0-")) {
            throw new SshException(SshConstants.SSH2_DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED,
                                   "Unsupported protocol version: " + clientVersion);
        }
        return true;
    }

    private void receiveKexInit(Buffer buffer) throws IOException {
        clientProposal = new String[SshConstants.PROPOSAL_MAX];
        I_C = receiveKexInit(buffer, clientProposal);
    }

    private void serviceRequest(Buffer buffer) throws Exception {
        String request = buffer.getString();
        log.info("Received SSH_MSG_SERVICE_REQUEST '{}'", request);
        // TODO: handle service requests
        disconnect(SshConstants.SSH2_DISCONNECT_SERVICE_NOT_AVAILABLE, "Unsupported service request: " + request);
    }

    private void userAuth(Buffer buffer) throws Exception {
        if (state == State.WaitingUserAuth) {
            log.info("Accepting user authentication request");
            buffer = createBuffer(SshConstants.Message.SSH_MSG_SERVICE_ACCEPT, 0);
            buffer.putString("ssh-userauth");
            writePacket(buffer);
            userAuthFactories = new ArrayList<NamedFactory<UserAuth>>(getServerFactoryManager().getUserAuthFactories());
            log.info("Authorized authentication methods: {}", NamedFactory.Utils.getNames(userAuthFactories));
            state = State.UserAuth;
        } else {
            if (nbAuthRequests++ > maxAuthRequests) {
                throw new SshException(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "Too may authentication failures");
            }
            String username = buffer.getString();
            String svcName = buffer.getString();
            String method = buffer.getString();

            log.info("Authenticating user '{}' with method '{}'", username, method);
            Boolean authed = null;
            NamedFactory<UserAuth> factory = NamedFactory.Utils.get(userAuthFactories, method);
            if (factory != null) {
                UserAuth auth = factory.create();
                try {
                    authed = auth.auth(this, username, buffer);
                    if (authed == null) {
                        // authentication is still ongoing
                        log.info("Authentication not finished");
                        return;
                    } else {
                        log.info(authed ? "Authentication succeeded" : "Authentication failed");
                    }
                } catch (Exception e) {
                    // Continue
                    authed = false;
                    log.info("Authentication failed: {}", e.getMessage());
                }
            } else {
                log.info("Unsupported authentication method '{}'", method);
            }
            if (authed != null && authed) {

                if (getFactoryManager().getProperties() != null) {
                    String maxSessionCountAsString = getFactoryManager().getProperties().get(ServerFactoryManager.MAX_CONCURRENT_SESSIONS);
                    if (maxSessionCountAsString != null) {
                        int maxSessionCount = Integer.parseInt(maxSessionCountAsString);
                        int currentSessionCount = getActiveSessionCountForUser(username);
                        if (currentSessionCount >= maxSessionCount) {
                            disconnect(SshConstants.SSH2_DISCONNECT_SERVICE_NOT_AVAILABLE, "Too many concurrent connections");
                            return;
                        }
                    }
                }

                buffer = createBuffer(SshConstants.Message.SSH_MSG_USERAUTH_SUCCESS, 0);
                writePacket(buffer);
                state = State.Running;
                this.authed = true;
                this.username = username;
                unscheduleAuthTimer();
            } else {
                buffer = createBuffer(SshConstants.Message.SSH_MSG_USERAUTH_FAILURE, 0);
                NamedFactory.Utils.remove(userAuthFactories, "none"); // 'none' MUST NOT be listed
                buffer.putString(NamedFactory.Utils.getNames(userAuthFactories));
                buffer.putByte((byte) 0);
                writePacket(buffer);
            }
        }
    }

    public KeyPair getHostKey() {
        return factoryManager.getKeyPairProvider().loadKey(negociated[SshConstants.PROPOSAL_SERVER_HOST_KEY_ALGS]);
    }

    /**
     * Retrieve the current number of sessions active for a given username.
     * @param userName The name of the user
     * @return The current number of live <code>SshSession</code> objects associated with the user
     */
    protected int getActiveSessionCountForUser(String userName) {
        int totalCount = 0;
        for (IoSession is : ioSession.getService().getManagedSessions().values()) {
            ServerSession session = (ServerSession) getSession(is, true);
            if (session != null) {
                if (session.getUsername() != null && session.getUsername().equals(userName)) {
                    totalCount++;
                }
            }
        }
        return totalCount;
    }

    private void channelOpen(Buffer buffer) throws Exception {
        String type = buffer.getString();
        final int id = buffer.getInt();
        final int rwsize = buffer.getInt();
        final int rmpsize = buffer.getInt();

        log.info("Received SSH_MSG_CHANNEL_OPEN {}", type);

        if (closing) {
            Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_CONNECT_FAILED);
            buf.putString("SSH server is shutting down: " + type);
            buf.putString("");
            writePacket(buf);
            return;
        }
        if (!allowMoreSessions) {
            Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_CONNECT_FAILED);
            buf.putString("additional sessions disabled");
            buf.putString("");
            writePacket(buf);
            return;
        }

        final Channel channel = NamedFactory.Utils.create(getServerFactoryManager().getChannelFactories(), type);
        if (channel == null) {
            Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_UNKNOWN_CHANNEL_TYPE);
            buf.putString("Unsupported channel type: " + type);
            buf.putString("");
            writePacket(buf);
            return;
        }

        final int channelId = getNextChannelId();
        channels.put(channelId, channel);
        channel.init(this, channelId);
        channel.open(id, rwsize, rmpsize, buffer).addListener(new SshFutureListener<OpenFuture>() {
            public void operationComplete(OpenFuture future) {
                try {
                    if (future.isOpened()) {
                        Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_CONFIRMATION, 0);
                        buf.putInt(id);
                        buf.putInt(channelId);
                        buf.putInt(channel.getLocalWindow().getSize());
                        buf.putInt(channel.getLocalWindow().getPacketSize());
                        writePacket(buf);
                    } else if (future.getException() != null) {
                        Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
                        buf.putInt(id);
                        if (future.getException() instanceof OpenChannelException) {
                            buf.putInt(((OpenChannelException) future.getException()).getReasonCode());
                            buf.putString(future.getException().getMessage());
                        } else {
                            buf.putInt(0);
                            buf.putString("Error opening channel: " + future.getException().getMessage());
                        }
                        buf.putString("");
                        writePacket(buf);
                    }
                } catch (IOException e) {
                    exceptionCaught(e);
                }
            }
        });
    }

    private void globalRequest(Buffer buffer) throws Exception {
        String req = buffer.getString();
        boolean wantReply = buffer.getBoolean();
        if (req.equals("keepalive@openssh.com")) {
            // Relatively standard KeepAlive directive, just wants failure
        } else if (req.equals("no-more-sessions@openssh.com")) {
            allowMoreSessions = false;
        } else if (req.equals("tcpip-forward")) {
            tcpipForward.request(buffer, wantReply);
            return;
        } else if (req.equals("cancel-tcpip-forward")) {
            tcpipForward.cancel(buffer, wantReply);
            return;
        } else {
            log.info("Received SSH_MSG_GLOBAL_REQUEST {}", req);
            log.error("Unknown global request: {}", req);
        }
        if (wantReply) {
            buffer = createBuffer(SshConstants.Message.SSH_MSG_REQUEST_FAILURE, 0);
            writePacket(buffer);
        }
    }

    public String initAgentForward() throws IOException {
        return agentForward.initialize();
    }

    public String createX11Display(boolean singleConnection, String authenticationProtocol, String authenticationCookie, int screen) throws IOException {
        return x11Forward.createDisplay(singleConnection, authenticationProtocol, authenticationCookie, screen);
    }

}
