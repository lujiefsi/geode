/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.tcp;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.lang.ThreadLocal.withInitial;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_PEER_AUTH_INIT;
import static org.apache.geode.distributed.internal.DistributionConfigImpl.SECURITY_SYSTEM_PREFIX;
import static org.apache.geode.internal.monitoring.ThreadsMonitoring.Mode.P2PReaderExecutor;
import static org.apache.geode.internal.net.BufferPool.BufferType.TRACKED_RECEIVER;
import static org.apache.geode.util.internal.GeodeGlossary.GEMFIRE_PREFIX;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import org.apache.geode.CancelException;
import org.apache.geode.SerializationException;
import org.apache.geode.SystemFailure;
import org.apache.geode.alerting.internal.spi.AlertingAction;
import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.annotations.internal.MutableForTesting;
import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.distributed.DistributedSystemDisconnectedException;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.ConflationKey;
import org.apache.geode.distributed.internal.DMStats;
import org.apache.geode.distributed.internal.DirectReplyProcessor;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.DistributionMessage;
import org.apache.geode.distributed.internal.DistributionStats;
import org.apache.geode.distributed.internal.OperationExecutors;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyMessage;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.distributed.internal.ReplySender;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.membership.api.MemberShunnedException;
import org.apache.geode.distributed.internal.membership.api.Membership;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.DSFIDFactory;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.SystemTimer;
import org.apache.geode.internal.SystemTimer.SystemTimerTask;
import org.apache.geode.internal.monitoring.ThreadsMonitoring;
import org.apache.geode.internal.monitoring.executor.AbstractExecutor;
import org.apache.geode.internal.net.BufferPool;
import org.apache.geode.internal.net.ByteBufferSharing;
import org.apache.geode.internal.net.ByteBufferVendor;
import org.apache.geode.internal.net.NioFilter;
import org.apache.geode.internal.net.NioPlainEngine;
import org.apache.geode.internal.net.SocketCreator;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.Versioning;
import org.apache.geode.internal.serialization.VersioningIO;
import org.apache.geode.internal.tcp.MsgReader.Header;
import org.apache.geode.logging.internal.executors.LoggingThread;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * Connection is a socket holder that sends and receives serialized message objects. A Connection
 * may be closed to preserve system resources and will automatically be reopened when it's needed.
 *
 * @since GemFire 2.0
 */
public class Connection implements Runnable {
  private static final Logger logger = LogService.getLogger();

  public static final String THREAD_KIND_IDENTIFIER = "P2P message reader";

  @MakeNotStatic
  private static int P2P_CONNECT_TIMEOUT;
  @MakeNotStatic
  private static boolean IS_P2P_CONNECT_TIMEOUT_INITIALIZED;

  static final int NORMAL_MSG_TYPE = 0x4c;
  static final int CHUNKED_MSG_TYPE = 0x4d; // a chunk of one logical msg
  static final int END_CHUNKED_MSG_TYPE = 0x4e; // last in a series of chunks
  static final int DIRECT_ACK_BIT = 0x20;

  static final int MSG_HEADER_SIZE_OFFSET = 0;
  static final int MSG_HEADER_TYPE_OFFSET = 4;
  static final int MSG_HEADER_ID_OFFSET = 5;
  static final int MSG_HEADER_BYTES = 7;

  /**
   * Small buffer used for send socket buffer on receiver connections and receive buffer on sender
   * connections.
   */
  public static final int SMALL_BUFFER_SIZE =
      Integer.getInteger(GEMFIRE_PREFIX + "SMALL_BUFFER_SIZE", 4096);

  /**
   * counter to give connections a unique id
   */
  @MakeNotStatic
  private static final AtomicLong ID_COUNTER = new AtomicLong(1);

  /**
   * string used as the reason for initiating suspect processing
   */
  @VisibleForTesting
  public static final String INITIATING_SUSPECT_PROCESSING =
      "member unexpectedly shut down shared, unordered connection";

  /**
   * the table holding this connection
   */
  private final ConnectionTable owner;

  private final TCPConduit conduit;
  private NioFilter ioFilter;

  /**
   * Set to false once run() is terminating. Using this instead of Thread.isAlive as the reader
   * thread may be a pooled thread.
   */
  private volatile boolean isRunning;

  /**
   * true if connection is a shared resource that can be used by more than one thread
   */
  private boolean sharedResource;

  /**
   * The idle timeout timer task for this connection
   */
  private volatile SystemTimerTask idleTask;

  /**
   * If true then readers for thread owned sockets will send all messages on thread owned senders.
   * Even normally unordered msgs get send on TO socks.
   */
  private static final boolean DOMINO_THREAD_OWNED_SOCKETS =
      Boolean.getBoolean("p2p.ENABLE_DOMINO_THREAD_OWNED_SOCKETS");

  private static final ThreadLocal<Boolean> isDominoThread = withInitial(() -> FALSE);

  /**
   * the socket entrusted to this connection
   */
  private final Socket socket;

  /**
   * output stream/channel lock
   */
  private final Object outLock = new Object();

  /**
   * the ID string of the conduit (for logging)
   */
  private final String conduitIdStr;

  private InternalDistributedMember remoteAddr;

  /**
   * Identifies the version of the member on the other side of the connection.
   */
  private KnownVersion remoteVersion;

  /**
   * True if this connection was accepted by a listening socket. This makes it a receiver. False if
   * this connection was explicitly created by a connect call. This makes it a sender.
   */
  private final boolean isReceiver;

  /**
   * count of how many unshared p2p-readers removed from the original action this thread is. For
   * instance, server-connection -> owned p2p reader (count 0) -> owned p2p reader (count 1) ->
   * owned p2p reader (count 2). This shows up in thread names as "DOM #x" (domino #x)
   */
  private static final ThreadLocal<Integer> dominoCount = withInitial(() -> 0);

  /**
   * How long to wait if receiver will not accept a message before we go into queue mode.
   *
   * @since GemFire 4.2.2
   */
  private int asyncDistributionTimeout;

  /**
   * How long to wait, with the receiver not accepting any messages, before kicking the receiver out
   * of the distributed system. Ignored if asyncDistributionTimeout is zero.
   *
   * @since GemFire 4.2.2
   */
  private int asyncQueueTimeout;

  /**
   * How much queued data we can have, with the receiver not accepting any messages, before kicking
   * the receiver out of the distributed system. Ignored if asyncDistributionTimeout is zero.
   * Canonicalized to bytes (property file has it as megabytes
   *
   * @since GemFire 4.2.2
   */
  private long asyncMaxQueueSize;

  /**
   * True if an async queue is already being filled.
   */
  private volatile boolean asyncQueuingInProgress;

  /**
   * Maps ConflatedKey instances to ConflatedKey instance. Note that even though the key and value
   * for an entry is the map will always be "equal" they will not always be "==".
   */
  private final Map<ConflationKey, ConflationKey> conflatedKeys = new HashMap<>();

  /**
   * NOTE: LinkedBlockingQueue has a bug in which removes from the queue
   * cause future offer to increase the size without adding anything to the queue.
   * So I've changed from this backport class to a java.util.LinkedList
   */
  private final LinkedList<Object> outgoingQueue = new LinkedList<>();

  /**
   * Number of bytes in the outgoingQueue. Used to control capacity.
   */
  private long queuedBytes;

  /** used for async writes */
  private Thread pusherThread;

  /** Set to true once the handshake has been read */
  private volatile boolean handshakeRead;
  private volatile boolean handshakeCancelled;

  private static final byte REPLY_CODE_OK = (byte) 69;
  private static final byte REPLY_CODE_OK_WITH_ASYNC_INFO = (byte) 70;

  private final Object handshakeSync = new Object();

  /** message reader thread */
  private volatile Thread readerThread;

  /** whether the reader thread is, or should be, running */
  volatile boolean stopped = true;

  /** set to true once a close begins */
  private final AtomicBoolean closing = new AtomicBoolean(false);

  private volatile boolean readerShuttingDown;

  /** whether the socket is connected */
  volatile boolean connected;

  /**
   * Set to true once a connection finishes its constructor
   */
  private volatile boolean finishedConnecting;

  private volatile boolean accessed = true;
  private volatile boolean socketInUse;
  volatile boolean timedOut;

  /**
   * task for detecting ack timeouts and issuing alerts
   */
  private volatile SystemTimer.SystemTimerTask ackTimeoutTask;

  /**
   * millisecond clock at the time message transmission started, if doing forced-disconnect
   * processing
   */
  private long transmissionStartTime;

  /** ack wait timeout - if socketInUse, use this to trigger SUSPECT processing */
  private long ackWaitTimeout;

  /** ack severe alert timeout - if socketInUse, use this to send alert */
  private long ackSATimeout;

  /**
   * other connections participating in the current transmission. we notify them if ackSATimeout
   * expires to keep all members from generating alerts when only one is slow
   */
  private List<Connection> ackConnectionGroup;

  /** name of thread that we're currently performing an operation in (may be null) */
  private String ackThreadName;

  /*
   * This object mediates access to the input ByteBuffer and ensures its return to
   * pool after last use. This reference couldn't be final since it is initialized
   * in createIoFilter() not in the constructors. It had to be initialized there
   * because in general we have to construct an SSLEngine before we know the buffer
   * size and createIoFilter() is where we create that object.
   */
  private ByteBufferVendor inputBufferVendor;

  /** the length of the next message to be dispatched */
  private int messageLength;

  /** the type of message being received */
  private byte messageType;

  /**
   * when messages are chunked by a MsgStreamer we track the destreamers on
   * the receiving side using a message identifier
   */
  private short messageId;

  /** whether the length of the next message has been established */
  private boolean lengthSet;

  /** used to lock access to destreamer data */
  private final Object destreamerLock = new Object();

  /** caches a msg destreamer that is currently not being used */
  private MsgDestreamer idleMsgDestreamer;

  /**
   * used to map a msgId to a MsgDestreamer which are used for destreaming chunked messages
   */
  private HashMap<Short, MsgDestreamer> destreamerMap;

  private boolean directAck;

  private boolean asyncMode;

  /**
   * Is this connection used for serial message delivery?
   * May be mutated during {@link #readHandshakeForReceiver(DataInput)}
   */
  private boolean preserveOrder;

  /** number of messages sent on this connection */
  private long messagesSent;

  /** number of messages received on this connection */
  private long messagesReceived;

  /** unique ID of this connection (remote if isReceiver==true) */
  private volatile long uniqueId;

  private int sendBufferSize = -1;
  private int recvBufferSize = -1;

  @MakeNotStatic
  private static final ByteBuffer okHandshakeBuf;
  static {
    int msglen = 1; // one byte for reply code
    byte[] bytes = new byte[MSG_HEADER_BYTES + msglen];
    msglen = calcHdrSize(msglen);
    bytes[MSG_HEADER_SIZE_OFFSET] = (byte) (msglen / 0x1000000 & 0xff);
    bytes[MSG_HEADER_SIZE_OFFSET + 1] = (byte) (msglen / 0x10000 & 0xff);
    bytes[MSG_HEADER_SIZE_OFFSET + 2] = (byte) (msglen / 0x100 & 0xff);
    bytes[MSG_HEADER_SIZE_OFFSET + 3] = (byte) (msglen & 0xff);
    bytes[MSG_HEADER_TYPE_OFFSET] = (byte) NORMAL_MSG_TYPE; // message type
    bytes[MSG_HEADER_ID_OFFSET] = (byte) (MsgIdGenerator.NO_MSG_ID >> 8 & 0xff);
    bytes[MSG_HEADER_ID_OFFSET + 1] = (byte) (MsgIdGenerator.NO_MSG_ID & 0xff);
    bytes[MSG_HEADER_BYTES] = REPLY_CODE_OK;
    int allocSize = bytes.length;
    ByteBuffer bb;
    if (BufferPool.useDirectBuffers) {
      bb = ByteBuffer.allocateDirect(allocSize);
    } else {
      bb = ByteBuffer.allocate(allocSize);
    }
    bb.put(bytes);
    okHandshakeBuf = bb;
  }

  /**
   * maximum message buffer size
   */
  public static final int MAX_MSG_SIZE = 0x00ffffff;

  private static final int HANDSHAKE_TIMEOUT_MS =
      Integer.getInteger("p2p.handshakeTimeoutMs", 59000);

  /**
   * product version exchange during handshake.
   *
   * NOTICE: handshake_version should not be changed anymore. Use the gemfire version transmitted
   * with the handshake bits and handle old handshakes based on that
   */
  static final byte HANDSHAKE_VERSION = 7;

  private final AtomicBoolean asyncCloseCalled = new AtomicBoolean();

  private static final int CONNECT_HANDSHAKE_SIZE = 4096;

  /** time between connection attempts */
  private static final int RECONNECT_WAIT_TIME =
      Integer.getInteger(GEMFIRE_PREFIX + "RECONNECT_WAIT_TIME", 2000);

  /**
   * Batch sends currently should not be turned on because: 1. They will be used for all sends
   * (instead of just no-ack) and thus will break messages that wait for a response (or kill perf).
   * 2. The buffer is not properly flushed and closed on shutdown. The code attempts to do this but
   * must not be doing it correctly.
   */
  private static final boolean BATCH_SENDS = Boolean.getBoolean("p2p.batchSends");
  private static final int BATCH_BUFFER_SIZE =
      Integer.getInteger("p2p.batchBufferSize", 1024 * 1024);
  private static final int BATCH_FLUSH_MS = Integer.getInteger("p2p.batchFlushTime", 50);
  private final Object batchLock = new Object();
  private ByteBuffer fillBatchBuffer;
  private ByteBuffer sendBatchBuffer;
  private BatchBufferFlusher batchFlusher;

  /**
   * use to test message prep overhead (no socket write). WARNING: turning this on completely
   * disables distribution of batched sends
   */
  private static final boolean SOCKET_WRITE_DISABLED = Boolean.getBoolean("p2p.disableSocketWrite");

  private final Object pusherSync = new Object();

  private boolean disconnectRequested;

  /**
   * If true then act as if the socket buffer is full and start async queuing
   */
  @MutableForTesting
  public static volatile boolean FORCE_ASYNC_QUEUE;

  private static final int MAX_WAIT_TIME = 32; // ms (must be a power of 2)

  /**
   * stateLock is used to synchronize state changes.
   */
  private final Object stateLock = new Object();

  /**
   * for timeout processing, this is the current state of the connection
   */
  private byte connectionState = STATE_IDLE;

  /* ~~~~~~~~~~~~~ connection states ~~~~~~~~~~~~~~~ */
  /** the connection is idle, but may be in use */
  private static final byte STATE_IDLE = 0;
  /** the connection is in use and is transmitting data */
  private static final byte STATE_SENDING = 1;
  /** the connection is in use and is done transmitting */
  private static final byte STATE_POST_SENDING = 2;
  /** the connection is in use and is reading a direct-ack */
  private static final byte STATE_READING_ACK = 3;
  /** the connection is in use and has finished reading a direct-ack */
  private static final byte STATE_RECEIVED_ACK = 4;
  /** the connection is in use and is reading a message */
  private static final byte STATE_READING = 5;
  /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */

  /** set to true if we exceeded the ack-wait-threshold waiting for a response */
  private volatile boolean ackTimedOut;

  /**
   * a Reader thread for an shared Connection will remain around in order to
   * ensure that the socket is properly closed.
   */
  private volatile boolean hasResidualReaderThread;

  /**
   * creates a "reader" connection that we accepted (it was initiated by an explicit connect being
   * done on the other side).
   */
  protected Connection(ConnectionTable connectionTable, Socket socket) throws ConnectionException {
    if (connectionTable == null) {
      throw new IllegalArgumentException("Null ConnectionTable");
    }
    conduit = connectionTable.getConduit();
    isReceiver = true;
    owner = connectionTable;
    this.socket = socket;
    InetSocketAddress conduitSocketId = conduit.getSocketId();
    conduitIdStr = conduitSocketId.toString();
    handshakeRead = false;
    handshakeCancelled = false;
    connected = true;
    asyncMode = false;

    try {
      socket.setTcpNoDelay(true);
      socket.setKeepAlive(true);
      setSendBufferSize(socket, SMALL_BUFFER_SIZE);
      setReceiveBufferSize(socket);
    } catch (SocketException e) {
      // unable to get the settings we want. Don't log an error because it will likely happen a lot
    }
  }

  private ThreadsMonitoring getThreadMonitoring() {
    return conduit.getDM().getThreadMonitoring();
  }

  public boolean isSharedResource() {
    return sharedResource;
  }

  @VisibleForTesting
  int getP2PConnectTimeout(DistributionConfig config) {
    if (AlertingAction.isThreadAlerting()) {
      return config.getMemberTimeout();
    }
    if (IS_P2P_CONNECT_TIMEOUT_INITIALIZED) {
      return P2P_CONNECT_TIMEOUT;
    }
    String connectTimeoutStr = System.getProperty("p2p.connectTimeout");
    if (connectTimeoutStr != null) {
      P2P_CONNECT_TIMEOUT = Integer.parseInt(connectTimeoutStr);
    } else {
      P2P_CONNECT_TIMEOUT = 6 * config.getMemberTimeout();
    }
    IS_P2P_CONNECT_TIMEOUT_INITIALIZED = true;
    return P2P_CONNECT_TIMEOUT;
  }

  /**
   * @return true if this thread is a reader thread, otherwise false
   */
  private static boolean tipDomino() {
    if (DOMINO_THREAD_OWNED_SOCKETS) {
      // mark this thread as one who wants to send ALL on TO sockets
      ConnectionTable.threadWantsOwnResources();
      isDominoThread.set(TRUE);
      return true;
    }
    return false;
  }

  public static boolean isDominoThread() {
    return isDominoThread.get();
  }

  private void setSendBufferSize(Socket sock) {
    setSendBufferSize(sock, owner.getConduit().tcpBufferSize);
  }

  private void setReceiveBufferSize(Socket sock) {
    setReceiveBufferSize(sock, owner.getConduit().tcpBufferSize);
  }

  private void setSendBufferSize(Socket sock, int requestedSize) {
    setSocketBufferSize(sock, true, requestedSize);
  }

  private void setReceiveBufferSize(Socket sock, int requestedSize) {
    setSocketBufferSize(sock, false, requestedSize);
  }

  private void setSocketBufferSize(Socket sock, boolean send, int requestedSize) {
    if (requestedSize > 0) {
      try {
        int currentSize = send ? sock.getSendBufferSize() : sock.getReceiveBufferSize();
        if (currentSize == requestedSize) {
          if (send) {
            sendBufferSize = currentSize;
          }
          return;
        }
        if (send) {
          sock.setSendBufferSize(requestedSize);
        } else {
          sock.setReceiveBufferSize(requestedSize);
        }
      } catch (SocketException ignore) {
      }
      try {
        int actualSize = send ? sock.getSendBufferSize() : sock.getReceiveBufferSize();
        if (send) {
          sendBufferSize = actualSize;
        } else {
          recvBufferSize = actualSize;
        }
        if (actualSize < requestedSize) {
          logger.info("Socket {} is {} instead of the requested {}.",
              send ? "send buffer size" : "receive buffer size",
              actualSize, requestedSize);
        } else if (actualSize > requestedSize) {
          if (logger.isTraceEnabled()) {
            logger.trace("Socket {} buffer size is {} instead of the requested {}",
                send ? "send" : "receive", actualSize, requestedSize);
          }
          // Remember the request size which is smaller.
          // This remembered value is used for allocating direct mem buffers.
          if (send) {
            sendBufferSize = requestedSize;
          } else {
            recvBufferSize = requestedSize;
          }
        }
      } catch (SocketException ignore) {
        if (send) {
          sendBufferSize = requestedSize;
        } else {
          recvBufferSize = requestedSize;
        }
      }
    }
  }

  /**
   * Returns the size of the send buffer on this connection's socket.
   */
  int getSendBufferSize() {
    int result = sendBufferSize;
    if (result != -1) {
      return result;
    }
    try {
      result = getSocket().getSendBufferSize();
    } catch (SocketException ignore) {
      // just return a default
      result = owner.getConduit().tcpBufferSize;
    }
    sendBufferSize = result;
    return result;
  }

  void initReceiver() {
    startReader(owner);
  }

  void setIdleTimeoutTask(SystemTimerTask task) {
    idleTask = task;
  }

  /**
   * Returns true if an idle connection was detected.
   */
  boolean checkForIdleTimeout() {
    if (isSocketClosed()) {
      return true;
    }
    if (isSocketInUse() || sharedResource && !preserveOrder) {
      // shared/unordered connections are used for failure-detection
      // and are not subject to idle-timeout
      return false;
    }
    boolean isIdle = !accessed;
    accessed = false;
    if (isIdle) {
      timedOut = true;
      owner.getConduit().getStats().incLostLease();
      if (logger.isDebugEnabled()) {
        logger.debug("Closing idle connection {} shared={} ordered={}", this, sharedResource,
            preserveOrder);
      }
      try {
        // Instead of calling requestClose we call closeForReconnect.
        // We don't want this timeout close to close any other connections.
        // The problem with requestClose has removeEndpoint set to true
        // which will close an receivers we have if this connection is a shared one.
        closeForReconnect("idle connection timed out");
      } catch (Exception ignore) {
      }
    }
    return isIdle;
  }

  static int calcHdrSize(int byteSize) {
    if (byteSize > MAX_MSG_SIZE) {
      throw new IllegalStateException(format("tcp message exceeded max size of %s",
          MAX_MSG_SIZE));
    }
    int hdrSize = byteSize;
    hdrSize |= HANDSHAKE_VERSION << 24;
    return hdrSize;
  }

  static int calcMsgByteSize(int hdrSize) {
    return hdrSize & MAX_MSG_SIZE;
  }

  static void calcHdrVersion(int hdrSize) throws IOException {
    byte ver = (byte) (hdrSize >> 24);
    if (ver != HANDSHAKE_VERSION) {
      throw new IOException(
          format(
              "Detected wrong version of GemFire product during handshake. Expected %s but found %s",
              HANDSHAKE_VERSION, ver));
    }
  }

  private void sendOKHandshakeReply() throws IOException, ConnectionException {
    ByteBuffer my_okHandshakeBuf;
    if (isReceiver) {
      DistributionConfig cfg = owner.getConduit().getConfig();
      ByteBuffer bb;
      if (BufferPool.useDirectBuffers) {
        bb = ByteBuffer.allocateDirect(128);
      } else {
        bb = ByteBuffer.allocate(128);
      }
      bb.putInt(0); // reserve first 4 bytes for packet length
      bb.put((byte) NORMAL_MSG_TYPE);
      bb.putShort(MsgIdGenerator.NO_MSG_ID);
      bb.put(REPLY_CODE_OK_WITH_ASYNC_INFO);
      bb.putInt(cfg.getAsyncDistributionTimeout());
      bb.putInt(cfg.getAsyncQueueTimeout());
      bb.putInt(cfg.getAsyncMaxQueueSize());
      // write own product version
      VersioningIO.writeOrdinal(bb, KnownVersion.CURRENT.ordinal(), true);
      // now set the msg length into position 0
      bb.putInt(0, calcHdrSize(bb.position() - MSG_HEADER_BYTES));
      my_okHandshakeBuf = bb;
      bb.flip();
    } else {
      my_okHandshakeBuf = okHandshakeBuf;
    }
    my_okHandshakeBuf.position(0);
    writeFully(getSocket().getChannel(), my_okHandshakeBuf, false, null);
  }

  /**
   * @throws ConnectionException if the conduit has stopped
   */
  private void waitForHandshake() throws ConnectionException {
    boolean needToClose = false;
    String reason = null;
    try {
      synchronized (handshakeSync) {
        if (!handshakeRead && !handshakeCancelled) {
          reason = "unknown";
          boolean interrupted = Thread.interrupted();
          boolean success = false;
          try {
            final long endTime = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
            long msToWait = HANDSHAKE_TIMEOUT_MS;
            while (!handshakeRead && !handshakeCancelled && msToWait > 0) {
              handshakeSync.wait(msToWait); // spurious wakeup ok
              if (!handshakeRead && !handshakeCancelled) {
                msToWait = endTime - System.currentTimeMillis();
              }
            }
            if (!handshakeRead && !handshakeCancelled) {
              reason = "handshake timed out";
              String peerName;
              if (remoteAddr != null) {
                peerName = remoteAddr.toString();
                // late in the life of jdk 1.7 we started seeing connections accepted
                // when accept() was not even being called. This started causing timeouts
                // to occur in the handshake threads instead of causing failures in
                // connection-formation. So, we need to initiate suspect processing here
                owner.getDM().getDistribution().suspectMember(remoteAddr,
                    format(
                        "Connection handshake with %s timed out after waiting %s milliseconds.",
                        peerName, HANDSHAKE_TIMEOUT_MS));
              } else {
                peerName = "socket " + socket.getRemoteSocketAddress() + ":" + socket.getPort();
              }
              throw new ConnectionException(
                  format(
                      "Connection handshake with %s timed out after waiting %s milliseconds.",
                      peerName, HANDSHAKE_TIMEOUT_MS));
            }
            success = handshakeRead;
          } catch (InterruptedException ex) {
            interrupted = true;
            owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
            reason = "interrupted";
          } finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
            if (success) {
              if (isReceiver) {
                needToClose =
                    !owner.getConduit().getMembership().addSurpriseMember(remoteAddr);
                if (needToClose) {
                  reason = "this member is shunned";
                }
              }
            } else {
              needToClose = true;
            }
          }
        }
      }

    } finally {
      if (needToClose) {
        try {
          requestClose(reason);
        } catch (Exception ignore) {
        }
      }
    }
  }

  @VisibleForTesting
  void clearSSLInputBuffer() {
    if (getConduit().useSSL() && ioFilter != null) {
      try (final ByteBufferSharing sharedBuffer = ioFilter.getUnwrappedBuffer()) {
        // clear out any remaining handshake bytes
        sharedBuffer.getBuffer().position(0).limit(0);
      } catch (IOException e) {
        // means the NioFilter was already closed
      }
    }
  }

  @VisibleForTesting
  void notifyHandshakeWaiter(boolean success) {
    synchronized (handshakeSync) {
      /*
       * Return early to avoid modifying ioFilter's buffer more than once.
       */
      if (handshakeRead || handshakeCancelled) {
        return;
      }
      clearSSLInputBuffer();
      if (success) {
        handshakeRead = true;
      } else {
        handshakeCancelled = true;
      }
      handshakeSync.notifyAll();
    }
  }

  /**
   * asynchronously close this connection
   *
   * @param beingSickForTests test hook to simulate sickness in communications & membership
   */
  private void asyncClose(boolean beingSickForTests) {
    // note: remoteAddr may be null if this is a receiver that hasn't finished its handshake

    // we do the close in a background thread because the operation may hang if
    // there is a problem with the network

    // if simulating sickness, sockets must be closed in-line so that tests know
    // that the vm is sick when the beSick operation completes
    if (beingSickForTests) {
      prepareForAsyncClose();
    } else {
      if (asyncCloseCalled.compareAndSet(false, true)) {
        Socket s = socket;
        if (s != null && !s.isClosed()) {
          prepareForAsyncClose();
          owner.getSocketCloser().asyncClose(s, String.valueOf(remoteAddr),
              () -> ioFilter.close(s.getChannel()));
        }
      }
    }
  }

  private void prepareForAsyncClose() {
    synchronized (stateLock) {
      if (readerThread != null && isRunning && !readerShuttingDown
          && (connectionState == STATE_READING || connectionState == STATE_READING_ACK)) {
        readerThread.interrupt();
      }
    }
  }

  /**
   * waits until we've joined the distributed system before returning
   */
  private void waitForAddressCompletion() {
    InternalDistributedMember myAddr = owner.getConduit().getMemberId();
    synchronized (myAddr) {
      while (!owner.getConduit().getCancelCriterion().isCancelInProgress()
          && myAddr.getInetAddress() == null && myAddr.getVmViewId() < 0) {
        try {
          myAddr.wait(100); // spurious wakeup ok
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          owner.getConduit().getCancelCriterion().checkCancelInProgress(ie);
        }
      }
      Assert.assertTrue(myAddr.getDirectChannelPort() == owner.getConduit().getPort());
    }
  }

  private void handshakeFromNewSender() throws IOException {
    waitForAddressCompletion();

    InternalDistributedMember myAddr = owner.getConduit().getMemberId();
    try (final MsgOutputStream connectHandshake = new MsgOutputStream(CONNECT_HANDSHAKE_SIZE)) {
      /*
       * Note a byte of zero is always written because old products serialized a member id with
       * always sends an ip address. My reading of the ip-address specs indicated that the first
       * byte of a valid address would never be 0.
       */
      connectHandshake.writeByte(0);
      connectHandshake.writeByte(HANDSHAKE_VERSION);
      // NOTE: if you add or remove code in this section bump HANDSHAKE_VERSION
      InternalDataSerializer.invokeToData(myAddr, connectHandshake);
      connectHandshake.writeBoolean(sharedResource);
      connectHandshake.writeBoolean(preserveOrder);
      connectHandshake.writeLong(uniqueId);
      // write the product version ordinal
      VersioningIO.writeOrdinal(connectHandshake, KnownVersion.CURRENT.ordinal(), true);
      connectHandshake.writeInt(dominoCount.get() + 1);
      // this writes the sending member + thread name that is stored in senderName
      // on the receiver to show the cause of reader thread creation
      connectHandshake.setMessageHeader(NORMAL_MSG_TYPE, OperationExecutors.STANDARD_EXECUTOR,
          MsgIdGenerator.NO_MSG_ID);
      writeFully(getSocket().getChannel(), connectHandshake.getContentBuffer(), false, null);
    }
  }

  /**
   * @throws IOException if handshake fails
   */
  private void attemptHandshake(ConnectionTable connTable) throws IOException {
    // send HANDSHAKE
    // send this member's information. It's expected on the other side
    if (logger.isDebugEnabled()) {
      logger.debug("starting peer-to-peer handshake on socket {}", socket);
    }
    handshakeFromNewSender();
    startReader(connTable); // this reader only reads the handshake and then exits
    waitForHandshake(); // waiting for reply
  }

  /**
   * creates a new connection to a remote server. We are initiating this connection; the other side
   * must accept us We will almost always send messages; small acks are received.
   */
  static Connection createSender(final Membership<InternalDistributedMember> mgr,
      final ConnectionTable t,
      final boolean preserveOrder, final InternalDistributedMember remoteAddr,
      final boolean sharedResource,
      final long startTime, final long ackTimeout, final long ackSATimeout)
      throws IOException, DistributedSystemDisconnectedException {
    boolean success = false;
    Connection conn = null;
    // keep trying. Note that this may be executing during the shutdown window
    // where a cancel criterion has not been established, but threads are being
    // interrupted. In this case we must allow the connection to succeed even
    // though subsequent messaging using the socket may fail
    boolean interrupted = Thread.interrupted();
    try {
      boolean connectionErrorLogged = false;
      long reconnectWaitTime = RECONNECT_WAIT_TIME;
      boolean suspected = false;
      boolean severeAlertIssued = false;
      boolean firstTime = true;
      boolean warningPrinted = false;
      while (!success) { // keep trying
        // Quit if DM has stopped distribution
        t.getConduit().getCancelCriterion().checkCancelInProgress(null);
        long now = System.currentTimeMillis();
        if (!severeAlertIssued && ackSATimeout > 0 && startTime + ackTimeout < now) {
          if (startTime + ackTimeout + ackSATimeout < now) {
            if (remoteAddr != null) {
              logger.fatal("Unable to form a TCP/IP connection to {} in over {} seconds",
                  remoteAddr, (ackSATimeout + ackTimeout) / 1000);
            }
            severeAlertIssued = true;
          } else if (!suspected) {
            if (remoteAddr != null) {
              logger.warn("Unable to form a TCP/IP connection to {} in over {} seconds",
                  remoteAddr, ackTimeout / 1000);
            }
            mgr.suspectMember(remoteAddr,
                "Unable to form a TCP/IP connection in a reasonable amount of time");
            suspected = true;
          }
          reconnectWaitTime =
              Math.min(RECONNECT_WAIT_TIME, ackSATimeout - (now - startTime - ackTimeout));
          if (reconnectWaitTime <= 0) {
            reconnectWaitTime = RECONNECT_WAIT_TIME;
          }
        } else if (!suspected && startTime > 0 && ackTimeout > 0
            && startTime + ackTimeout < now) {
          mgr.suspectMember(remoteAddr,
              "Unable to form a TCP/IP connection in a reasonable amount of time");
          suspected = true;
        }
        if (firstTime) {
          firstTime = false;
          if (!mgr.memberExists(remoteAddr) || mgr.isShunned(remoteAddr)
              || mgr.shutdownInProgress()) {
            throw new IOException("Member " + remoteAddr + " left the system");
          }
        } else {
          // if we're sending an alert and can't connect, bail out. A sick
          // alert listener should not prevent cache operations from continuing
          if (AlertingAction.isThreadAlerting()) {
            // do not change the text of this exception - it is looked for in exception handlers
            throw new IOException("Cannot form connection to alert listener " + remoteAddr);
          }

          // Wait briefly...
          interrupted = Thread.interrupted() || interrupted;
          try {
            Thread.sleep(reconnectWaitTime);
          } catch (InterruptedException ie) {
            interrupted = true;
            t.getConduit().getCancelCriterion().checkCancelInProgress(ie);
          }
          t.getConduit().getCancelCriterion().checkCancelInProgress(null);
          if (giveUpOnMember(mgr, remoteAddr)) {
            throw new IOException(
                format("Member %s left the group", remoteAddr));
          }
          if (!warningPrinted) {
            warningPrinted = true;
            logger.warn("Connection: Attempting reconnect to peer {}",
                remoteAddr);
          }
          t.getConduit().getStats().incReconnectAttempts();
        }
        // create connection
        try {
          conn = new Connection(t, preserveOrder, remoteAddr, sharedResource);
        } catch (SSLHandshakeException se) {
          // no need to retry if certificates were rejected
          throw se;
        } catch (IOException ioe) {
          // Only give up if the member leaves the view.
          if (giveUpOnMember(mgr, remoteAddr)) {
            throw ioe;
          }
          t.getConduit().getCancelCriterion().checkCancelInProgress(null);
          if ("Too many open files".equals(ioe.getMessage())) {
            t.fileDescriptorsExhausted();
          } else if (!connectionErrorLogged) {
            connectionErrorLogged = true; // otherwise change to use 100ms intervals causes a lot of
                                          // these
            logger.info("Connection: shared={} ordered={} failed to connect to peer {} because: {}",
                sharedResource, preserveOrder, remoteAddr,
                ioe.getCause() != null ? ioe.getCause() : ioe);
          }
        } // IOException
        finally {
          if (conn == null) {
            t.getConduit().getStats().incFailedConnect();
          }
        }
        if (conn != null) {
          // handshake
          try {
            conn.attemptHandshake(t);
            if (conn.isSocketClosed()) {
              // something went wrong while reading the handshake
              // and the socket was closed or we were sent
              // ShutdownMessage
              if (giveUpOnMember(mgr, remoteAddr)) {
                throw new IOException(format("Member %s left the group", remoteAddr));
              }
              t.getConduit().getCancelCriterion().checkCancelInProgress(null);
              // no success but no need to log; just retry
            } else {
              success = true;
            }
          } catch (ConnectionException e) {
            if (giveUpOnMember(mgr, remoteAddr)) {
              throw new IOException("Handshake failed", e);
            }
            t.getConduit().getCancelCriterion().checkCancelInProgress(null);
            logger.info(
                "Connection: shared={} ordered={} handshake failed to connect to peer {} because: {}",
                sharedResource, preserveOrder, remoteAddr, e);
          } catch (IOException e) {
            if (giveUpOnMember(mgr, remoteAddr)) {
              throw e;
            }
            t.getConduit().getCancelCriterion().checkCancelInProgress(null);
            logger.info(
                "Connection: shared={} ordered={} handshake failed to connect to peer {} because: {}",
                sharedResource, preserveOrder, remoteAddr, e);
            if (!sharedResource && "Too many open files".equals(e.getMessage())) {
              t.fileDescriptorsExhausted();
            }
          } finally {
            if (!success) {
              try {
                conn.requestClose("failed handshake");
              } catch (Exception ignore) {
              }
              conn = null;
            }
          }
        }
      } // while
      if (warningPrinted) {
        logger.info("{}: Successfully reestablished connection to peer {}",
            mgr.getLocalMember(), remoteAddr);
      }
    } finally {
      try {
        if (!success) {
          if (conn != null) {
            conn.requestClose("failed construction");
            conn = null;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    if (conn == null) {
      throw new ConnectionException(
          format("Connection: failed construction for peer %s", remoteAddr));
    }
    if (preserveOrder && BATCH_SENDS) {
      conn.createBatchSendBuffer();
    }
    conn.finishedConnecting = true;
    return conn;
  }

  private static boolean giveUpOnMember(Membership<InternalDistributedMember> mgr,
      InternalDistributedMember remoteAddr) {
    return !mgr.memberExists(remoteAddr) || mgr.isShunned(remoteAddr) || mgr.shutdownInProgress();
  }

  private void setRemoteAddr(InternalDistributedMember m) {
    remoteAddr = owner.getDM().getCanonicalId(m);
    Membership<InternalDistributedMember> mgr = conduit.getMembership();
    mgr.addSurpriseMember(m);
  }

  /**
   * creates a new connection to a remote server. We are initiating this connection; the other side
   * must accept us We will almost always send messages; small acks are received.
   */
  private Connection(ConnectionTable t, boolean preserveOrder, InternalDistributedMember remoteID,
      boolean sharedResource) throws IOException, DistributedSystemDisconnectedException {
    // initialize a socket upfront. So that the
    if (t == null) {
      throw new IllegalArgumentException("ConnectionTable is null.");
    }
    conduit = t.getConduit();
    isReceiver = false;
    owner = t;
    this.sharedResource = sharedResource;
    this.preserveOrder = preserveOrder;
    setRemoteAddr(remoteID);
    conduitIdStr = owner.getConduit().getSocketId().toString();
    handshakeRead = false;
    handshakeCancelled = false;
    connected = true;
    asyncMode = false;

    uniqueId = ID_COUNTER.getAndIncrement();

    // connect to listening socket

    InetSocketAddress addr =
        new InetSocketAddress(remoteID.getInetAddress(), remoteID.getDirectChannelPort());
    SocketChannel channel = SocketChannel.open();
    owner.addConnectingSocket(channel.socket(), addr.getAddress());

    try {
      channel.socket().setTcpNoDelay(true);
      channel.socket().setKeepAlive(SocketCreator.ENABLE_TCP_KEEP_ALIVE);

      // If conserve-sockets is false, the socket can be used for receiving responses, so set the
      // receive buffer accordingly.
      if (!sharedResource) {
        setReceiveBufferSize(channel.socket(), owner.getConduit().tcpBufferSize);
      } else {
        setReceiveBufferSize(channel.socket(), SMALL_BUFFER_SIZE); // make small since only
        // receive ack messages
      }
      setSendBufferSize(channel.socket());
      channel.configureBlocking(true);

      int connectTime = getP2PConnectTimeout(conduit.getDM().getConfig());

      try {

        channel.socket().connect(addr, connectTime);

        createIoFilter(channel, true);

      } catch (NullPointerException e) {
        // jdk 1.7 sometimes throws an NPE here
        ConnectException c = new ConnectException("Encountered bug #45044 - retrying");
        c.initCause(e);
        // prevent a hot loop by sleeping a little bit
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        throw c;
      } catch (SSLException e) {
        ConnectException c = new ConnectException("Problem connecting to peer " + addr);
        c.initCause(e);
        throw c;
      } catch (CancelledKeyException | ClosedSelectorException e) {
        // for some reason NIO throws this runtime exception instead of an IOException on timeouts
        ConnectException c = new ConnectException(
            format("Attempt timed out after %s milliseconds", connectTime));
        c.initCause(e);
        throw c;
      }
    } finally {
      owner.removeConnectingSocket(channel.socket());
    }
    socket = channel.socket();

    if (logger.isDebugEnabled()) {
      logger.debug("Connection: connected to {} with IP address {}", remoteID, addr);
    }
    try {
      getSocket().setTcpNoDelay(true);
    } catch (SocketException ignored) {
    }
  }

  private void createBatchSendBuffer() {
    if (BufferPool.useDirectBuffers) {
      fillBatchBuffer = ByteBuffer.allocateDirect(BATCH_BUFFER_SIZE);
      sendBatchBuffer = ByteBuffer.allocateDirect(BATCH_BUFFER_SIZE);
    } else {
      fillBatchBuffer = ByteBuffer.allocate(BATCH_BUFFER_SIZE);
      sendBatchBuffer = ByteBuffer.allocate(BATCH_BUFFER_SIZE);
    }
    batchFlusher = new BatchBufferFlusher();
    batchFlusher.start();
  }

  void cleanUpOnIdleTaskCancel() {
    // Make sure receivers are removed from the connection table, this should always be a noop, but
    // is done here as a fail safe.
    if (isReceiver) {
      owner.removeReceiver(this);
    }
  }

  private void closeBatchBuffer() {
    if (batchFlusher != null) {
      batchFlusher.close();
    }
  }

  private void batchSend(ByteBuffer src) {
    if (SOCKET_WRITE_DISABLED) {
      return;
    }
    final long start = DistributionStats.getStatTime();
    try {
      Assert.assertTrue(src.remaining() <= BATCH_BUFFER_SIZE, "Message size(" + src.remaining()
          + ") exceeded BATCH_BUFFER_SIZE(" + BATCH_BUFFER_SIZE + ")");
      do {
        ByteBuffer dst;
        synchronized (batchLock) {
          dst = fillBatchBuffer;
          if (src.remaining() <= dst.remaining()) {
            final long copyStart = DistributionStats.getStatTime();
            dst.put(src);
            owner.getConduit().getStats().incBatchCopyTime(copyStart);
            return;
          }
        }
        // If we got this far then we do not have room in the current
        // buffer and need the flusher thread to flush before we can fill it
        batchFlusher.flushBuffer(dst);
      } while (true);
    } finally {
      owner.getConduit().getStats().incBatchSendTime(start);
    }
  }

  /**
   * Request that the manager close this connection, or close it forcibly if there is no manager.
   * Invoking this method ensures that the proper synchronization is done.
   */
  void requestClose(String reason) {
    close(reason, true, false, false, false);
  }

  boolean isClosing() {
    return closing.get();
  }

  void closePartialConnect(String reason, boolean beingSick) {
    close(reason, false, false, beingSick, false);
  }

  void closeForReconnect(String reason) {
    close(reason, true, false, false, false);
  }

  void closeOldConnection(String reason) {
    close(reason, true, true, false, true);
  }

  /**
   * Closes the connection.
   *
   * @see #requestClose
   */
  @SuppressWarnings("TLW_TWO_LOCK_WAIT")
  private void close(String reason, boolean cleanupEndpoint, boolean p_removeEndpoint,
      boolean beingSick, boolean forceRemoval) {
    // use getAndSet outside sync on this
    boolean onlyCleanup = closing.getAndSet(true);
    if (onlyCleanup && !forceRemoval) {
      return;
    }
    boolean removeEndpoint = p_removeEndpoint;
    if (!onlyCleanup) {
      synchronized (this) {
        stopped = true;
        if (connected) {
          if (asyncQueuingInProgress && pusherThread != Thread.currentThread()) {
            // We don't need to do this if we are the pusher thread
            // and we have determined that we need to close the connection.
            synchronized (outgoingQueue) {
              // wait for the flusher to complete (it may timeout)
              while (asyncQueuingInProgress) {
                boolean interrupted = Thread.interrupted();
                try {
                  outgoingQueue.wait(); // spurious wakeup ok
                } catch (InterruptedException ie) {
                  interrupted = true;
                } finally {
                  if (interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }
              }
            }
          }
          connected = false;

          final DMStats stats = owner.getConduit().getStats();
          if (finishedConnecting) {
            if (isReceiver) {
              stats.decReceivers();
            } else {
              stats.decSenders(sharedResource, preserveOrder);
            }
          }

        } else if (!forceRemoval) {
          removeEndpoint = false;
        }
        // make sure our socket is closed
        asyncClose(false);
        if (!isReceiver && !hasResidualReaderThread()) {
          // receivers release the input buffer when exiting run(). Senders use the
          // inputBuffer for reading direct-reply responses
          inputBufferVendor.destruct();
        }
        lengthSet = false;
      }

      // Make sure anyone waiting for a handshake stops waiting
      notifyHandshakeWaiter(false);
      // wait a bit for the our reader thread to exit don't wait if we are the reader thread
      boolean isIBM = false;
      // if network partition detection is enabled or this is an admin vm
      // we can't wait for the reader thread when running in an IBM JRE
      if (conduit.getConfig().getEnableNetworkPartitionDetection()
          || conduit.getMemberId().getVmKind() == ClusterDistributionManager.ADMIN_ONLY_DM_TYPE
          || conduit.getMemberId().getVmKind() == ClusterDistributionManager.LOCATOR_DM_TYPE) {
        isIBM = "IBM Corporation".equals(System.getProperty("java.vm.vendor"));
      }

      // Now that readerThread is returned to a pool after we close
      // we need to be more careful not to join on a thread that belongs
      // to someone else.
      Thread readerThreadSnapshot = readerThread;
      if (!beingSick && readerThreadSnapshot != null && !isIBM && isRunning
          && !readerShuttingDown && readerThreadSnapshot != Thread.currentThread()) {
        try {
          readerThreadSnapshot.join(500);
          readerThreadSnapshot = readerThread;
          if (isRunning && !readerShuttingDown && readerThreadSnapshot != null
              && owner.getDM().getRootCause() == null) {
            // don't wait twice if there's a system failure
            readerThreadSnapshot.join(1500);
            if (isRunning) {
              logger.info("Timed out waiting for readerThread on {} to finish.",
                  this);
            }
          }
        } catch (IllegalThreadStateException ignore) {
          // ignored - thread already stopped
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
          // but keep going, we're trying to close.
        }
      }

      closeBatchBuffer();
      closeAllMsgDestreamers();
    }
    if (cleanupEndpoint) {
      if (isReceiver) {
        owner.removeReceiver(this);
      }
      if (removeEndpoint) {
        if (sharedResource) {
          if (!preserveOrder) {
            // only remove endpoint when shared unordered connection is closed
            if (!isReceiver) {
              // Only remove endpoint if sender.
              if (finishedConnecting) {
                // only remove endpoint if our constructor finished
                owner.removeEndpoint(remoteAddr, reason);
              }
            }
          } else {
            // noinspection ConstantConditions
            owner.removeSharedConnection(reason, remoteAddr, preserveOrder, this);
          }
        } else if (!isReceiver) {
          owner.removeThreadConnection(remoteAddr, this);
        }
      } else {
        // This code is ok to do even if the ConnectionTable has never added this Connection to its
        // maps since the calls in this block use our identity to do the removes.
        if (sharedResource) {
          owner.removeSharedConnection(reason, remoteAddr, preserveOrder, this);
        } else if (!isReceiver) {
          owner.removeThreadConnection(remoteAddr, this);
        }
      }
    }

    // This cancels the idle timer task, but it also removes the tasks reference to this connection,
    // freeing up the connection (and it's buffers for GC sooner.
    if (idleTask != null) {
      synchronized (idleTask) {
        idleTask.cancel();
      }
    }

    if (ackTimeoutTask != null) {
      synchronized (ackTimeoutTask) {
        ackTimeoutTask.cancel();
      }
    }
  }

  /**
   * starts a reader thread
   */
  private void startReader(ConnectionTable connTable) {
    if (logger.isDebugEnabled()) {
      logger.debug("Starting thread for " + p2pReaderName());
    }
    Assert.assertTrue(!isRunning);
    stopped = false;
    isRunning = true;
    connTable.executeCommand(this);
  }

  /**
   * in order to read non-NIO socket-based messages we need to have a thread actively trying to grab
   * bytes out of the sockets input queue. This is that thread.
   */
  @Override
  public void run() {
    readerThread = Thread.currentThread();
    readerThread.setName(p2pReaderName());
    ConnectionTable.threadWantsSharedResources();

    try {
      readMessages();
    } finally {
      // do the socket close within a finally block
      if (logger.isDebugEnabled()) {
        logger.debug("Stopping {} for {}", p2pReaderName(), remoteAddr);
      }
      if (isReceiver) {
        try {
          initiateSuspicionIfSharedUnordered();
        } catch (CancelException e) {
          // shutting down
        }
        if (!sharedResource) {
          conduit.getStats().incThreadOwnedReceivers(-1L, dominoCount.get());
        }
        asyncClose(false);
        owner.removeAndCloseThreadOwnedSockets();
      } else {
        if (sharedResource && !asyncMode) {
          asyncClose(false);
        }
      }

      inputBufferVendor.destruct();

      // make sure that if the reader thread exits we notify a thread waiting for the handshake.
      notifyHandshakeWaiter(false);
      readerThread.setName("unused p2p reader");
      synchronized (stateLock) {
        isRunning = false;
        readerThread = null;
      }
    }
  }

  BufferPool getBufferPool() {
    return owner.getBufferPool();
  }

  private String p2pReaderName() {
    StringBuilder sb = new StringBuilder(64);
    if (isReceiver) {
      sb.append(THREAD_KIND_IDENTIFIER + "@");
    } else if (handshakeRead) {
      sb.append("P2P message sender@");
    } else {
      sb.append("P2P handshake reader@");
    }
    sb.append(Integer.toHexString(System.identityHashCode(this)));
    if (!isReceiver) {
      sb.append('-').append(getUniqueId());
    }
    return sb.toString();
  }

  private void readMessages() {
    // take a snapshot of uniqueId to detect reconnect attempts
    SocketChannel channel;
    try {
      channel = getSocket().getChannel();
      socket.setSoTimeout(0);
      socket.setTcpNoDelay(true);
      if (ioFilter == null) {
        createIoFilter(channel, false);
      }
      channel.configureBlocking(true);
    } catch (ClosedChannelException e) {
      // the channel was asynchronously closed. Our work is done.
      try {
        requestClose("readMessages caught closed channel");
      } catch (Exception ignore) {
      }
      // exit loop and thread
      return;
    } catch (IOException ex) {
      if (stopped || owner.getConduit().getCancelCriterion().isCancelInProgress()) {
        try {
          requestClose("readMessages caught shutdown");
        } catch (Exception ignore) {
        }
        // exit loop (and thread)
        return;
      }
      logger.info("Failed initializing socket for message {}: {}",
          isReceiver ? "receiver" : "sender", ex.getMessage());
      readerShuttingDown = true;
      try {
        requestClose(format("Failed initializing socket %s", ex));
      } catch (Exception ignore) {
      }
      return;
    }

    if (!stopped) {
      if (logger.isDebugEnabled()) {
        logger.debug("Starting {} on {}", p2pReaderName(), socket);
      }
    }

    // we should not change the state of the connection if we are a handshake reader thread
    // as there is a race between this thread and the application thread doing direct ack
    boolean handshakeHasBeenRead = false;
    final ThreadsMonitoring threadMonitoring = getThreadMonitoring();
    final AbstractExecutor threadMonitorExecutor =
        threadMonitoring.createAbstractExecutor(P2PReaderExecutor);
    threadMonitorExecutor.suspendMonitoring();
    threadMonitoring.register(threadMonitorExecutor);
    try {
      for (boolean isInitialRead = true;;) {
        if (stopped) {
          break;
        }
        if (SystemFailure.getFailure() != null) {
          // Allocate no objects here!
          try {
            ioFilter.close(socket.getChannel());
            socket.close();
          } catch (IOException e) {
            // don't care
          }
          getCheckFailure();
        }
        if (owner.getConduit().getCancelCriterion().isCancelInProgress()) {
          break;
        }

        try (final ByteBufferSharing inputSharing = inputBufferVendor.open()) {
          ByteBuffer buff = inputSharing.getBuffer();

          synchronized (stateLock) {
            connectionState = STATE_READING;
          }
          int amountRead;
          if (!isInitialRead) {
            amountRead = channel.read(buff);
          } else {
            isInitialRead = false;
            // if we're using SSL/TLS the input buffer may already have data to process
            final boolean skipInitialRead = buff.position() > 0;
            if (!skipInitialRead) {
              amountRead = channel.read(buff);
            } else {
              amountRead = buff.position();
            }
          }
          synchronized (stateLock) {
            connectionState = STATE_IDLE;
          }
          if (amountRead == 0) {
            continue;
          }
          if (amountRead < 0) {
            readerShuttingDown = true;
            try {
              requestClose("SocketChannel.read returned EOF");
            } catch (Exception e) {
              // ignore - shutting down
            }
            return;
          }
          processInputBuffer(threadMonitorExecutor);

          if (!handshakeHasBeenRead && !isReceiver && (handshakeRead || handshakeCancelled)) {
            if (logger.isDebugEnabled()) {
              if (handshakeRead) {
                logger.debug("handshake has been read {}", this);
              } else {
                logger.debug("handshake has been cancelled {}", this);
              }
            }
            handshakeHasBeenRead = true;

            // Once we have read the handshake for unshared connections, the reader can skip
            // processing messages
            if (!sharedResource || asyncMode) {
              break;
            } else {
              // not exiting and not a Reader spawned from a ServerSocket.accept(), so
              // let's set some state noting that this is happening
              hasResidualReaderThread = true;
            }

          }
        } catch (CancelException e) {
          if (logger.isDebugEnabled()) {
            logger.debug("{} Terminated <{}> due to cancellation", p2pReaderName(), this, e);
          }
          readerShuttingDown = true;
          try {
            requestClose(format("CacheClosed in channel read: %s", e));
          } catch (Exception ignored) {
          }
          return;
        } catch (ClosedChannelException e) {
          readerShuttingDown = true;
          try {
            requestClose(format("ClosedChannelException in channel read: %s", e));
          } catch (Exception ignored) {
          }
          return;
        } catch (IOException e) {
          // "Socket closed" check needed for Solaris jdk 1.4.2_08
          if (!isSocketClosed() && !"Socket closed".equalsIgnoreCase(e.getMessage())) {
            if (logger.isInfoEnabled() && !isIgnorableIOException(e)) {
              logger.info("{} io exception for {}", p2pReaderName(), this, e);
            }
            if (logger.isDebugEnabled()) {
              if (e.getMessage().contains("interrupted by a call to WSACancelBlockingCall")) {
                logger.debug(
                    "{} received unexpected WSACancelBlockingCall exception, which may result in a hang",
                    p2pReaderName());
              }
            }
          }
          readerShuttingDown = true;
          try {
            requestClose(format("IOException in channel read: %s", e));
          } catch (Exception ignored) {
          }
          return;

        } catch (Exception e) {
          owner.getConduit().getCancelCriterion().checkCancelInProgress(e);
          if (!stopped && !isSocketClosed()) {
            logger.fatal(format("%s exception in channel read", p2pReaderName()), e);
          }
          readerShuttingDown = true;
          try {
            requestClose(format("%s exception in channel read", e));
          } catch (Exception ignored) {
          }
          return;
        }
      }
    } finally {
      threadMonitoring.unregister(threadMonitorExecutor);
      hasResidualReaderThread = false;
      if (!handshakeHasBeenRead || (sharedResource && !asyncMode)) {
        synchronized (stateLock) {
          connectionState = STATE_IDLE;
        }
      }
      if (logger.isDebugEnabled()) {
        logger.debug("readMessages terminated id={} from {} isHandshakeReader={}", conduitIdStr,
            remoteAddr, handshakeHasBeenRead);
      }
    }
  }

  private void createIoFilter(SocketChannel channel, boolean clientSocket) throws IOException {
    if (getConduit().useSSL() && channel != null) {
      InetSocketAddress address = (InetSocketAddress) channel.getRemoteAddress();
      String hostName;
      if (remoteAddr != null) {
        hostName = remoteAddr.getHostName();
      } else {
        hostName = SocketCreator.getHostName(address.getAddress());
      }
      SSLEngine engine =
          getConduit().getSocketCreator().createSSLEngine(hostName,
              address.getPort(), clientSocket);

      final int packetBufferSize = engine.getSession().getPacketBufferSize();

      inputBufferVendor =
          new ByteBufferVendor(
              getBufferPool().acquireDirectReceiveBuffer(packetBufferSize),
              TRACKED_RECEIVER,
              getBufferPool());

      if (channel.socket().getReceiveBufferSize() < packetBufferSize) {
        channel.socket().setReceiveBufferSize(packetBufferSize);
      }
      if (channel.socket().getSendBufferSize() < packetBufferSize) {
        channel.socket().setSendBufferSize(packetBufferSize);
      }
      try (final ByteBufferSharing inputSharing = inputBufferVendor.open()) {
        final ByteBuffer inputBuffer = inputSharing.getBuffer();
        /*
         * It's ok to share the inputBuffer with handshakeSSLSocketChannel() since that method
         * accesses the referenced buffer for the handshake which completes before returning
         * control here. The NioSslEngine retains no reference to the buffer.
         */
        ioFilter = getConduit().getSocketCreator().handshakeSSLSocketChannel(channel, engine,
            getConduit().idleConnectionTimeout, inputBuffer,
            getBufferPool());
      }
    } else {
      final int allocSize;
      if (recvBufferSize == -1) {
        allocSize = owner.getConduit().tcpBufferSize;
      } else {
        allocSize = recvBufferSize;
      }

      inputBufferVendor =
          new ByteBufferVendor(
              getBufferPool().acquireDirectReceiveBuffer(allocSize),
              TRACKED_RECEIVER,
              getBufferPool());

      ioFilter = new NioPlainEngine(getBufferPool());
    }
  }

  /**
   * initiate suspect processing if a shared/ordered connection is lost and we're not shutting down
   */
  private void initiateSuspicionIfSharedUnordered() {
    if (isReceiver && handshakeRead && !preserveOrder && sharedResource) {
      if (!owner.getConduit().getCancelCriterion().isCancelInProgress()) {
        owner.getDM().getDistribution().suspectMember(getRemoteAddress(),
            INITIATING_SUSPECT_PROCESSING);
      }
    }
  }

  /**
   * checks to see if an exception should not be logged: i.e., "forcibly closed", "reset by peer",
   * or "connection reset"
   */
  private static boolean isIgnorableIOException(Exception e) {
    if (e instanceof ClosedChannelException) {
      return true;
    }

    String msg = e.getMessage();
    if (msg == null) {
      msg = e.toString();
    }

    msg = msg.toLowerCase();

    if (e instanceof SSLException && msg.contains("status = closed")) {
      return true; // engine has been closed - this is normal
    }

    return (msg.contains("forcibly closed") || msg.contains("reset by peer")
        || msg.contains("connection reset") || msg.contains("socket is closed"));
  }

  private static boolean validMsgType(int msgType) {
    return msgType == NORMAL_MSG_TYPE
        || msgType == CHUNKED_MSG_TYPE
        || msgType == END_CHUNKED_MSG_TYPE;
  }

  private void closeAllMsgDestreamers() {
    synchronized (destreamerLock) {
      if (idleMsgDestreamer != null) {
        idleMsgDestreamer.close();
        idleMsgDestreamer = null;
      }
      if (destreamerMap != null) {
        for (MsgDestreamer msgDestreamer : destreamerMap.values()) {
          msgDestreamer.close();
        }
        destreamerMap = null;
      }
    }
  }

  private MsgDestreamer obtainMsgDestreamer(short msgId, final KnownVersion v) {
    synchronized (destreamerLock) {
      if (destreamerMap == null) {
        destreamerMap = new HashMap<>();
      }
      Short key = msgId;
      MsgDestreamer result = destreamerMap.get(key);
      if (result == null) {
        result = idleMsgDestreamer;
        if (result != null) {
          idleMsgDestreamer = null;
        } else {
          result =
              new MsgDestreamer(owner.getConduit().getStats(), conduit.getCancelCriterion(), v);
        }
        result.setName(p2pReaderName() + " msgId=" + msgId);
        destreamerMap.put(key, result);
      }
      return result;
    }
  }

  private void releaseMsgDestreamer(short msgId, MsgDestreamer md) {
    synchronized (destreamerLock) {
      destreamerMap.remove(msgId);
      if (idleMsgDestreamer == null) {
        md.reset();
        idleMsgDestreamer = md;
      } else {
        md.close();
      }
    }
  }

  private void sendFailureReply(int rpId, String exMsg, Throwable ex, boolean directAck) {
    ReplyException exception = new ReplyException(exMsg, ex);
    if (directAck) {
      ReplySender dm = new DirectReplySender(this);
      ReplyMessage.send(getRemoteAddress(), rpId, exception, dm);
    } else if (rpId != 0) {
      DistributionManager dm = owner.getDM();
      dm.getExecutors().getWaitingThreadPool()
          .execute(() -> ReplyMessage.send(getRemoteAddress(), rpId, exception, dm));
    }
  }

  /**
   * sends a serialized message to the other end of this connection. This is used by the
   * DirectChannel in GemFire when the message is going to be sent to multiple recipients.
   *
   * @throws ConnectionException if the conduit has stopped
   */
  void sendPreserialized(ByteBuffer buffer, boolean cacheContentChanges,
      DistributionMessage msg) throws IOException, ConnectionException {
    if (!connected) {
      throw new ConnectionException(format("Not connected to %s", remoteAddr));
    }
    if (batchFlusher != null) {
      batchSend(buffer);
      return;
    }
    final boolean origSocketInUse = socketInUse;
    byte originalState;
    synchronized (stateLock) {
      originalState = connectionState;
      connectionState = STATE_SENDING;
    }
    socketInUse = true;
    try {
      SocketChannel channel = getSocket().getChannel();
      writeFully(channel, buffer, false, msg);
      if (cacheContentChanges) {
        messagesSent++;
      }
    } finally {
      accessed();
      socketInUse = origSocketInUse;
      synchronized (stateLock) {
        connectionState = originalState;
      }
    }
  }

  /**
   * If {@code use} is true then "claim" the connection for our use. If {@code use} is
   * false then "release" the connection.
   */
  public void setInUse(boolean use, long startTime, long ackWaitThreshold, long ackSAThreshold,
      List<Connection> connectionGroup) {
    // just do the following; EVEN if the connection has been closed
    synchronized (this) {
      if (use && (ackWaitThreshold > 0 || ackSAThreshold > 0)) {
        // set times that events should be triggered
        transmissionStartTime = startTime;
        ackWaitTimeout = ackWaitThreshold;
        ackSATimeout = ackSAThreshold;
        ackConnectionGroup = connectionGroup;
        ackThreadName = Thread.currentThread().getName();
      } else {
        ackWaitTimeout = 0;
        ackSATimeout = 0;
        ackConnectionGroup = null;
        ackThreadName = null;
      }
      synchronized (stateLock) {
        connectionState = STATE_IDLE;
      }
      socketInUse = use;
    }
    if (!use) {
      accessed();
    }
  }

  /**
   * For testing we want to configure the connection without having to read a handshake
   */
  @VisibleForTesting
  void setSharedUnorderedForTest() {
    preserveOrder = false;
    sharedResource = true;
    handshakeRead = true;
  }

  /**
   * ensure that a task is running to monitor transmission and reading of acks
   */
  synchronized void scheduleAckTimeouts() {
    if (ackTimeoutTask == null) {
      final long msAW = SECONDS.toMillis(owner.getDM().getConfig().getAckWaitThreshold());
      final long msSA = SECONDS.toMillis(owner.getDM().getConfig().getAckSevereAlertThreshold());
      ackTimeoutTask = new SystemTimer.SystemTimerTask() {
        @Override
        public void run2() {
          if (isSocketClosed()) {
            // Connection is closing - nothing to do anymore
            cancel();
            return;
          }
          if (owner.isClosed()) {
            cancel();
            return;
          }
          byte connState;
          synchronized (stateLock) {
            connState = connectionState;
          }
          boolean sentAlert = false;
          synchronized (Connection.this) {
            if (socketInUse) {
              switch (connState) {
                case Connection.STATE_IDLE:
                case Connection.STATE_POST_SENDING:
                case Connection.STATE_RECEIVED_ACK:
                  break;
                case Connection.STATE_SENDING:
                case Connection.STATE_READING_ACK:
                  sentAlert = doSevereAlertProcessing();
                  break;
                default:
              }
            }
          }
          List<Connection> group = ackConnectionGroup;
          if (sentAlert && group != null) {
            // since transmission and ack-receipt are performed serially, we don't want to complain
            // about all receivers out just because one was slow. We therefore reset the time stamps
            // and give others more time
            for (Connection connection : group) {
              if (connection != Connection.this) {
                connection.transmissionStartTime += connection.ackSATimeout;
              }
            }
          }
        }
      };

      synchronized (owner) {
        final SystemTimer timer = owner.getIdleConnTimer();
        if (timer != null) {
          synchronized (ackTimeoutTask) {
            if (!ackTimeoutTask.isCancelled()) {
              if (msSA > 0) {
                timer.scheduleAtFixedRate(ackTimeoutTask, msAW, Math.min(msAW, msSA));
              } else {
                timer.schedule(ackTimeoutTask, msAW);
              }
            }
          }
        }
      }
    }
  }

  /**
   * ack-wait-threshold and ack-severe-alert-threshold processing
   */
  private boolean doSevereAlertProcessing() {
    long now = System.currentTimeMillis();
    if (ackSATimeout > 0 && transmissionStartTime + ackWaitTimeout + ackSATimeout <= now) {
      logger.fatal("{} seconds have elapsed waiting for a response from {} for thread {}",
          (ackWaitTimeout + ackSATimeout) / 1000L,
          getRemoteAddress(),
          ackThreadName);
      // turn off subsequent checks by setting the timeout to zero, then boot the member
      ackSATimeout = 0;
      return true;
    }
    if (!ackTimedOut && 0 < ackWaitTimeout
        && transmissionStartTime + ackWaitTimeout <= now) {
      logger.warn("{} seconds have elapsed waiting for a response from {} for thread {}",
          ackWaitTimeout / 1000L, getRemoteAddress(), ackThreadName);
      ackTimedOut = true;

      final String state = connectionState == Connection.STATE_SENDING
          ? "Sender has been unable to transmit a message within ack-wait-threshold seconds"
          : "Sender has been unable to receive a response to a message within ack-wait-threshold seconds";
      if (ackSATimeout > 0) {
        owner.getDM().getDistribution()
            .suspectMembers(Collections.singleton(getRemoteAddress()), state);
      }
    }
    return false;
  }

  private boolean addToQueue(ByteBuffer buffer, DistributionMessage msg, boolean force)
      throws ConnectionException {
    final DMStats stats = owner.getConduit().getStats();
    long start = DistributionStats.getStatTime();
    try {
      ConflationKey ck = null;
      if (msg != null) {
        ck = msg.getConflationKey();
      }
      Object objToQueue = null;
      // if we can conflate delay the copy to see if we can reuse an already allocated buffer.
      final int newBytes = buffer.remaining();
      final int origBufferPos = buffer.position();
      if (ck == null || !ck.allowsConflation()) {
        // do this outside of sync for multi thread perf
        ByteBuffer newbb = ByteBuffer.allocate(newBytes);
        newbb.put(buffer);
        newbb.flip();
        objToQueue = newbb;
      }
      synchronized (outgoingQueue) {
        if (disconnectRequested) {
          buffer.position(origBufferPos);
          // we have given up so just drop this message.
          throw new ConnectionException(format("Forced disconnect sent to %s", remoteAddr));
        }
        if (!force && !asyncQueuingInProgress) {
          // reset buffer since we will be sending it
          buffer.position(origBufferPos);
          // the pusher emptied the queue so don't add since we are not forced to.
          return false;
        }
        boolean didConflation = false;
        if (ck != null) {
          if (ck.allowsConflation()) {
            objToQueue = ck;
            ConflationKey oldValue = conflatedKeys.put(ck, ck);
            if (oldValue != null) {
              ByteBuffer oldBuffer = oldValue.getBuffer();
              // need to always do this to allow old buffer to be gc'd
              oldValue.setBuffer(null);

              // remove the conflated key from current spot in queue

              // Note we no longer remove from the queue because the search
              // can be expensive on large queues. Instead we just wait for
              // the queue removal code to find the oldValue and ignore it since
              // its buffer is null

              // We do a quick check of the last thing in the queue
              // and if it has the same identity of our last thing then
              // remove it

              if (outgoingQueue.getLast() == oldValue) {
                outgoingQueue.removeLast();
              }
              int oldBytes = oldBuffer.remaining();
              queuedBytes -= oldBytes;
              stats.incAsyncQueueSize(-oldBytes);
              stats.incAsyncConflatedMsgs();
              didConflation = true;
              if (oldBuffer.capacity() >= newBytes) {
                // copy new buffer into oldBuffer
                oldBuffer.clear();
                oldBuffer.put(buffer);
                oldBuffer.flip();
                ck.setBuffer(oldBuffer);
              } else {
                // old buffer was not large enough
                ByteBuffer newbb = ByteBuffer.allocate(newBytes);
                newbb.put(buffer);
                newbb.flip();
                ck.setBuffer(newbb);
              }
            } else {
              // no old buffer so need to allocate one
              ByteBuffer newbb = ByteBuffer.allocate(newBytes);
              newbb.put(buffer);
              newbb.flip();
              ck.setBuffer(newbb);
            }
          } else {
            // just forget about having a conflatable operation
            conflatedKeys.remove(ck);
          }
        }

        long newQueueSize = newBytes + queuedBytes;
        if (newQueueSize > asyncMaxQueueSize) {
          logger.warn("Queued bytes {} exceeds max of {}, asking slow receiver {} to disconnect.",
              newQueueSize, asyncMaxQueueSize, remoteAddr);
          stats.incAsyncQueueSizeExceeded(1);
          disconnectSlowReceiver();
          // reset buffer since we will be sending it
          buffer.position(origBufferPos);
          return false;
        }

        outgoingQueue.addLast(objToQueue);
        queuedBytes += newBytes;
        stats.incAsyncQueueSize(newBytes);
        if (!didConflation) {
          stats.incAsyncQueuedMsgs();
        }
        return true;
      }
    } finally {
      if (DistributionStats.enableClockStats) {
        stats.incAsyncQueueAddTime(DistributionStats.getStatTime() - start);
      }
    }
  }

  /**
   * Return true if it was able to handle a block write of the given buffer. Return false if it is
   * still the caller is still responsible for writing it.
   *
   * @throws ConnectionException if the conduit has stopped
   */
  private boolean handleBlockedWrite(ByteBuffer buffer, DistributionMessage msg)
      throws ConnectionException {
    if (!addToQueue(buffer, msg, true)) {
      return false;
    }
    startMessagePusher();
    return true;
  }

  private void startMessagePusher() {
    synchronized (pusherSync) {
      while (pusherThread != null) {
        // wait for previous pusher thread to exit
        boolean interrupted = Thread.interrupted();
        try {
          pusherSync.wait(); // spurious wakeup ok
        } catch (InterruptedException ex) {
          interrupted = true;
          owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
      asyncQueuingInProgress = true;
      pusherThread = new LoggingThread("P2P async pusher to " + remoteAddr, this::runMessagePusher);
    }
    pusherThread.start();
  }

  private ByteBuffer takeFromOutgoingQueue() {
    final DMStats stats = owner.getConduit().getStats();
    long start = DistributionStats.getStatTime();
    try {
      ByteBuffer result = null;
      synchronized (outgoingQueue) {
        if (disconnectRequested) {
          // don't bother with anymore work since we are done
          asyncQueuingInProgress = false;
          outgoingQueue.notifyAll();
          return null;
        }
        do {
          if (outgoingQueue.isEmpty()) {
            break;
          }
          Object o = outgoingQueue.removeFirst();
          if (o == null) {
            break;
          }
          if (o instanceof ConflationKey) {
            result = ((ConflationKey) o).getBuffer();
            if (result != null) {
              conflatedKeys.remove(o);
            } else {
              // if result is null then this same key will be found later in the
              // queue so we just need to skip this entry
              continue;
            }
          } else {
            result = (ByteBuffer) o;
          }
          int newBytes = result.remaining();
          queuedBytes -= newBytes;
          stats.incAsyncQueueSize(-newBytes);
          stats.incAsyncDequeuedMsgs();
        } while (result == null);
        if (result == null) {
          asyncQueuingInProgress = false;
          outgoingQueue.notifyAll();
        }
      }
      return result;
    } finally {
      if (DistributionStats.enableClockStats) {
        stats.incAsyncQueueRemoveTime(DistributionStats.getStatTime() - start);
      }
    }
  }

  /**
   * @since GemFire 4.2.2
   */
  private void disconnectSlowReceiver() {
    synchronized (outgoingQueue) {
      if (disconnectRequested) {
        // only ask once
        return;
      }
      disconnectRequested = true;
    }
    DistributionManager dm = owner.getDM();
    if (dm == null) {
      owner.removeEndpoint(remoteAddr, "no distribution manager");
      return;
    }
    dm.getDistribution().requestMemberRemoval(remoteAddr,
        "Disconnected as a slow-receiver");
    // Ok, we sent the message, the coordinator should kick the member out
    // immediately and inform this process with a new view.
    // Let's wait for that to happen and if it doesn't in X seconds then remove the endpoint.
    while (dm.getOtherDistributionManagerIds().contains(remoteAddr)) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        owner.getConduit().getCancelCriterion().checkCancelInProgress(ie);
        return;
      }
    }
    owner.removeEndpoint(remoteAddr,
        "Force disconnect timed out");
    if (dm.getOtherDistributionManagerIds().contains(remoteAddr)) {
      if (logger.isDebugEnabled()) {
        final int FORCE_TIMEOUT = 3000;
        logger.debug("Force disconnect timed out after waiting {} seconds", FORCE_TIMEOUT / 1000);
      }
    }
  }

  /**
   * have the pusher thread check for queue overflow and for idle time exceeded
   */
  private void runMessagePusher() {
    try {
      final DMStats stats = owner.getConduit().getStats();
      final long threadStart = stats.startAsyncThread();
      try {
        stats.incAsyncQueues(1);
        stats.incAsyncThreads(1);

        try {
          int flushId = 0;
          while (asyncQueuingInProgress && connected) {
            if (SystemFailure.getFailure() != null) {
              // Allocate no objects here!
              Socket s = socket;
              if (s != null) {
                try {
                  logger.debug("closing socket", new Exception("closing socket"));
                  ioFilter.close(s.getChannel());
                  s.close();
                } catch (IOException e) {
                  // don't care
                }
              }
              getCheckFailure();
            }
            if (owner.getConduit().getCancelCriterion().isCancelInProgress()) {
              break;
            }
            flushId++;
            long flushStart = stats.startAsyncQueueFlush();
            try {
              long curQueuedBytes = queuedBytes;
              if (curQueuedBytes > asyncMaxQueueSize) {
                logger.warn(
                    "Queued bytes {} exceeds max of {}, asking slow receiver {} to disconnect.",
                    curQueuedBytes, asyncMaxQueueSize, remoteAddr);
                stats.incAsyncQueueSizeExceeded(1);
                disconnectSlowReceiver();
                return;
              }
              SocketChannel channel = getSocket().getChannel();
              ByteBuffer bb = takeFromOutgoingQueue();
              if (bb == null) {
                if (logger.isDebugEnabled() && flushId == 1) {
                  logger.debug("P2P pusher found empty queue");
                }
                return;
              }
              writeFully(channel, bb, true, null);
              // We should not add messagesSent. The counts are increased elsewhere.
              accessed();
            } finally {
              stats.endAsyncQueueFlush(flushStart);
            }
          }
        } finally {
          // need to force this to false before doing the requestClose calls
          synchronized (outgoingQueue) {
            asyncQueuingInProgress = false;
            outgoingQueue.notifyAll();
          }
        }
      } catch (IOException ex) {
        String err = format("P2P pusher io exception for %s", this);
        if (!isSocketClosed()) {
          if (logger.isDebugEnabled() && !isIgnorableIOException(ex)) {
            logger.debug(err, ex);
          }
        }
        try {
          requestClose(err + ": " + ex);
        } catch (Exception ignore) {
        }
      } catch (CancelException ex) {
        String err = format("P2P pusher %s caught CacheClosedException: %s", this, ex);
        logger.debug(err);
        try {
          requestClose(err);
        } catch (Exception ignore) {
        }
      } catch (Exception ex) {
        owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
        if (!isSocketClosed()) {
          logger.fatal(format("P2P pusher exception: %s", ex), ex);
        }
        try {
          requestClose(format("P2P pusher exception: %s", ex));
        } catch (Exception ignore) {
        }
      } finally {
        stats.incAsyncQueueSize(-queuedBytes);
        queuedBytes = 0;
        stats.endAsyncThread(threadStart);
        stats.incAsyncThreads(-1);
        stats.incAsyncQueues(-1);
        if (logger.isDebugEnabled()) {
          logger.debug("runMessagePusher terminated id={} from {}/{}", conduitIdStr, remoteAddr,
              remoteAddr);
        }
      }
    } finally {
      synchronized (pusherSync) {
        pusherThread = null;
        pusherSync.notifyAll();
      }
    }
  }

  /**
   * Return false if socket writes to be done async/nonblocking Return true if socket writes to be
   * done sync/blocking
   */
  private boolean useSyncWrites(boolean forceAsync) {
    if (forceAsync) {
      return false;
    }
    // only use sync writes if:
    // we are already queuing
    if (asyncQueuingInProgress) {
      // it will just tack this msg onto the outgoing queue
      return true;
    }
    // or we are a receiver
    if (isReceiver) {
      return true;
    }
    // or we are an unordered connection
    if (!preserveOrder) {
      return true;
    }

    // or the receiver does not allow queuing
    // OTHERWISE return false and let caller send async
    return asyncDistributionTimeout == 0;
  }

  private void writeAsync(SocketChannel channel, ByteBuffer buffer, boolean forceAsync,
      DistributionMessage p_msg, final DMStats stats) throws IOException {
    DistributionMessage msg = p_msg;
    // async/non-blocking
    boolean socketWriteStarted = false;
    long startSocketWrite = 0;
    int retries = 0;
    int totalAmtWritten = 0;
    try {
      synchronized (outLock) {
        if (!forceAsync) {
          // check one more time while holding outLock in case a pusher was created
          if (asyncQueuingInProgress) {
            if (addToQueue(buffer, msg, false)) {
              return;
            }
            // fall through
          }
        }
        socketWriteStarted = true;
        startSocketWrite = stats.startSocketWrite(false);
        long now = System.currentTimeMillis();
        long distributionTimeoutTarget = 0;
        // if asyncDistributionTimeout == 1 then we want to start queuing
        // as soon as we do a non blocking socket write that returns 0
        if (asyncDistributionTimeout != 1) {
          distributionTimeoutTarget = now + asyncDistributionTimeout;
        }
        long queueTimeoutTarget = now + asyncQueueTimeout;
        channel.configureBlocking(false);
        try {
          try (final ByteBufferSharing outputSharing = ioFilter.wrap(buffer)) {
            final ByteBuffer wrappedBuffer = outputSharing.getBuffer();

            int waitTime = 1;
            do {
              owner.getConduit().getCancelCriterion().checkCancelInProgress(null);
              retries++;
              int amtWritten;
              if (FORCE_ASYNC_QUEUE) {
                amtWritten = 0;
              } else {
                amtWritten = channel.write(wrappedBuffer);
              }
              if (amtWritten == 0) {
                now = System.currentTimeMillis();
                long timeoutTarget;
                if (!forceAsync) {
                  if (now > distributionTimeoutTarget) {
                    if (logger.isDebugEnabled()) {
                      if (distributionTimeoutTarget == 0) {
                        logger.debug(
                            "Starting async pusher to handle async queue because distribution-timeout is 1 and the last socket write would have blocked.");
                      } else {
                        long blockedMs = now - distributionTimeoutTarget;
                        blockedMs += asyncDistributionTimeout;
                        logger.debug(
                            "Blocked for {}ms which is longer than the max of {}ms so starting async pusher to handle async queue.",
                            blockedMs, asyncDistributionTimeout);
                      }
                    }
                    stats.incAsyncDistributionTimeoutExceeded();
                    if (totalAmtWritten > 0) {
                      // we have written part of the msg to the socket buffer
                      // and we are going to queue the remainder.
                      // We set msg to null so that will not make
                      // the partial msg a candidate for conflation.
                      msg = null;
                    }
                    if (handleBlockedWrite(wrappedBuffer, msg)) {
                      return;
                    }
                  }
                  timeoutTarget = distributionTimeoutTarget;
                } else {
                  boolean disconnectNeeded = false;
                  long curQueuedBytes = queuedBytes;
                  if (curQueuedBytes > asyncMaxQueueSize) {
                    logger.warn(
                        "Queued bytes {} exceeds max of {}, asking slow receiver {} to disconnect.",
                        curQueuedBytes, asyncMaxQueueSize, remoteAddr);
                    stats.incAsyncQueueSizeExceeded(1);
                    disconnectNeeded = true;
                  }
                  if (now > queueTimeoutTarget) {
                    // we have waited long enough the pusher has been idle too long!
                    long blockedMs = now - queueTimeoutTarget;
                    blockedMs += asyncQueueTimeout;
                    logger.warn(
                        "Blocked for {}ms which is longer than the max of {}ms, asking slow receiver {} to disconnect.",
                        blockedMs,
                        asyncQueueTimeout, remoteAddr);
                    stats.incAsyncQueueTimeouts(1);
                    disconnectNeeded = true;
                  }
                  if (disconnectNeeded) {
                    disconnectSlowReceiver();
                    synchronized (outgoingQueue) {
                      asyncQueuingInProgress = false;
                      outgoingQueue.notifyAll();
                    }
                    return;
                  }
                  timeoutTarget = queueTimeoutTarget;
                }
                {
                  long msToWait = waitTime;
                  long msRemaining = timeoutTarget - now;
                  if (msRemaining > 0) {
                    msRemaining /= 2;
                  }
                  if (msRemaining < msToWait) {
                    msToWait = msRemaining;
                  }
                  if (msToWait <= 0) {
                    Thread.yield();
                  } else {
                    boolean interrupted = Thread.interrupted();
                    try {
                      Thread.sleep(msToWait);
                    } catch (InterruptedException ex) {
                      interrupted = true;
                      owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
                    } finally {
                      if (interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    }
                  }
                }
                if (waitTime < MAX_WAIT_TIME) {
                  // double it since it is not yet the max
                  waitTime <<= 1;
                }
              } // amtWritten == 0
              else {
                totalAmtWritten += amtWritten;
                // reset queueTimeoutTarget since we made some progress
                queueTimeoutTarget = System.currentTimeMillis() + asyncQueueTimeout;
                waitTime = 1;
              }
            } while (wrappedBuffer.remaining() > 0);
          }
        } finally {
          channel.configureBlocking(true);
        }
      }
    } finally {
      if (socketWriteStarted) {
        if (retries > 0) {
          retries--;
        }
        stats.endSocketWrite(false, startSocketWrite, totalAmtWritten, retries);
      }
    }
  }

  /**
   * writeFully implements a blocking write on a channel that is in non-blocking mode.
   *
   * @param forceAsync true if we need to force a blocking async write.
   * @throws ConnectionException if the conduit has stopped
   */
  @VisibleForTesting
  void writeFully(SocketChannel channel, ByteBuffer buffer, boolean forceAsync,
      DistributionMessage msg) throws IOException, ConnectionException {
    final DMStats stats = owner.getConduit().getStats();
    if (!sharedResource) {
      stats.incTOSentMsg();
    }
    if (useSyncWrites(forceAsync)) {
      if (asyncQueuingInProgress) {
        if (addToQueue(buffer, msg, false)) {
          return;
        }
        // fall through
      }
      long startLock = stats.startSocketLock();
      synchronized (outLock) {
        stats.endSocketLock(startLock);
        if (asyncQueuingInProgress) {
          if (addToQueue(buffer, msg, false)) {
            return;
          }
          // fall through
        }
        try (final ByteBufferSharing outputSharing = ioFilter.wrap(buffer)) {
          final ByteBuffer wrappedBuffer = outputSharing.getBuffer();

          while (wrappedBuffer.remaining() > 0) {
            int amtWritten = 0;
            long start = stats.startSocketWrite(true);
            try {
              amtWritten = channel.write(wrappedBuffer);
            } finally {
              stats.endSocketWrite(true, start, amtWritten, 0);
            }
          }

        }
      }
    } else {
      writeAsync(channel, buffer, forceAsync, msg, stats);
    }
  }

  /**
   * @throws SocketTimeoutException if wait expires.
   * @throws ConnectionException if ack is not received
   */
  public void readAck(final DirectReplyProcessor processor)
      throws SocketTimeoutException, ConnectionException {
    if (isSocketClosed()) {
      throw new ConnectionException("connection is closed");
    }
    synchronized (stateLock) {
      connectionState = STATE_READING_ACK;
    }

    boolean origSocketInUse = socketInUse;
    socketInUse = true;
    MsgReader msgReader = null;
    DMStats stats = owner.getConduit().getStats();
    final KnownVersion version = getRemoteVersion();
    try {
      msgReader = new MsgReader(this, ioFilter, version);

      ReplyMessage msg;
      int len;

      // (we have to lock here to protect between reading header and message body)
      try (final ByteBufferSharing ignored = ioFilter.getUnwrappedBuffer()) {
        Header header = msgReader.readHeader();

        if (header.getMessageType() == NORMAL_MSG_TYPE) {
          msg = (ReplyMessage) msgReader.readMessage(header);
          len = header.getMessageLength();
        } else {
          MsgDestreamer destreamer = obtainMsgDestreamer(header.getMessageId(), version);
          while (header.getMessageType() == CHUNKED_MSG_TYPE) {
            msgReader.readChunk(header, destreamer);
            header = msgReader.readHeader();
          }
          msgReader.readChunk(header, destreamer);
          msg = (ReplyMessage) destreamer.getMessage();
          releaseMsgDestreamer(header.getMessageId(), destreamer);
          len = destreamer.size();
        }
      }
      // I'd really just like to call dispatchMessage here. However,
      // that call goes through a bunch of checks that knock about
      // 10% of the performance. Since this direct-ack stuff is all
      // about performance, we'll skip those checks. Skipping them
      // should be legit, because we just sent a message so we know
      // the member is already in our view, etc.
      DistributionManager dm = owner.getDM();
      msg.setBytesRead(len);
      msg.setSender(remoteAddr);
      stats.incReceivedMessages(1L);
      stats.incReceivedBytes(msg.getBytesRead());
      stats.incMessageChannelTime(msg.resetTimestamp());
      msg.process(dm, processor);
      // dispatchMessage(msg, len, false);
    } catch (SocketTimeoutException timeout) {
      throw timeout;
    } catch (IOException e) {
      final String err =
          format("ack read io exception for %s", this);
      if (!isSocketClosed()) {
        if (logger.isDebugEnabled() && !isIgnorableIOException(e)) {
          logger.debug(err, e);
        }
      }
      try {
        requestClose(err + ": " + e);
      } catch (Exception ignored) {
      }
      throw new ConnectionException(format("Unable to read direct ack because: %s", e));
    } catch (ConnectionException e) {
      owner.getConduit().getCancelCriterion().checkCancelInProgress(e);
      throw e;
    } catch (Exception e) {
      owner.getConduit().getCancelCriterion().checkCancelInProgress(e);
      if (!isSocketClosed()) {
        logger.fatal("ack read exception", e);
      }
      try {
        requestClose(format("ack read exception: %s", e));
      } catch (Exception ignored) {
      }
      throw new ConnectionException(format("Unable to read direct ack because: %s", e));
    } finally {
      stats.incProcessedMessages(1L);
      accessed();
      socketInUse = origSocketInUse;
      if (ackTimedOut) {
        logger.info("Finished waiting for reply from {}", getRemoteAddress());
        ackTimedOut = false;
      }
      if (msgReader != null) {
        msgReader.close();
      }
    }
    synchronized (stateLock) {
      connectionState = STATE_RECEIVED_ACK;
    }
  }

  /**
   * processes the current NIO buffer. If there are complete messages in the buffer, they are
   * deserialized and passed to TCPConduit for further processing
   */
  private void processInputBuffer(AbstractExecutor threadMonitorExecutor)
      throws ConnectionException, IOException {
    try (final ByteBufferSharing inputSharing = inputBufferVendor.open()) {
      // can't be final because in some cases we expand the buffer (resulting in a new object)
      ByteBuffer inputBuffer = inputSharing.getBuffer();
      inputBuffer.flip();

      try (final ByteBufferSharing sharedBuffer = ioFilter.unwrap(inputBuffer)) {
        final ByteBuffer peerDataBuffer = sharedBuffer.getBuffer();

        peerDataBuffer.flip();

        boolean done = false;

        while (!done && connected) {
          owner.getConduit().getCancelCriterion().checkCancelInProgress(null);
          int remaining = peerDataBuffer.remaining();
          if (lengthSet || remaining >= MSG_HEADER_BYTES) {
            if (!lengthSet) {
              if (readMessageHeader(peerDataBuffer)) {
                break;
              }
            }
            if (remaining >= messageLength + MSG_HEADER_BYTES) {
              lengthSet = false;
              peerDataBuffer.position(peerDataBuffer.position() + MSG_HEADER_BYTES);
              // don't trust the message deserialization to leave the position in
              // the correct spot. Some of the serialization uses buffered
              // streams that can leave the position at the wrong spot
              int startPos = peerDataBuffer.position();
              int oldLimit = peerDataBuffer.limit();
              peerDataBuffer.limit(startPos + messageLength);

              if (handshakeRead) {
                try {
                  readMessage(peerDataBuffer, threadMonitorExecutor);
                } catch (SerializationException e) {
                  logger.info("input buffer startPos {} oldLimit {}", startPos, oldLimit);
                  throw e;
                }
              } else {
                try (ByteBufferInputStream bbis = new ByteBufferInputStream(peerDataBuffer);
                    DataInputStream dis = new DataInputStream(bbis)) {
                  if (!isReceiver) {
                    // we read the handshake and then stop processing since we don't want
                    // to process the input buffer anymore in a handshake thread
                    readHandshakeForSender(dis, peerDataBuffer);
                    return;
                  }
                  if (readHandshakeForReceiver(dis)) {
                    ioFilter.doneReading(peerDataBuffer);
                    return;
                  }
                }
              }
              if (!connected) {
                continue;
              }
              accessed();
              peerDataBuffer.limit(oldLimit);
              peerDataBuffer.position(startPos + messageLength);
            } else {
              done = true;
              if (getConduit().useSSL()) {
                ioFilter.doneReading(peerDataBuffer);
              } else {
                // compact or resize the buffer
                final int oldBufferSize = inputBuffer.capacity();
                final int allocSize = messageLength + MSG_HEADER_BYTES;
                if (oldBufferSize < allocSize) {
                  // need a bigger buffer
                  logger.info(
                      "Allocating larger network read buffer, new size is {} old size was {}.",
                      allocSize, oldBufferSize);
                  inputBuffer = inputSharing.expandReadBufferIfNeeded(allocSize);
                } else {
                  if (inputBuffer.position() != 0) {
                    inputBuffer.compact();
                  } else {
                    inputBuffer.position(inputBuffer.limit());
                    inputBuffer.limit(inputBuffer.capacity());
                  }
                }
              }
            }
          } else {
            ioFilter.doneReading(peerDataBuffer);
            done = true;
          }
        }
      }
    }
  }

  private boolean readHandshakeForReceiver(final DataInput dis) {
    try {
      checkHandshakeInitialByte(dis);
      checkHandshakeVersion(dis);
      remoteAddr = DSFIDFactory.readInternalDistributedMember(dis);
      sharedResource = dis.readBoolean();
      preserveOrder = dis.readBoolean();
      uniqueId = dis.readLong();
      // read the product version ordinal for on-the-fly serialization
      // transformations (for rolling upgrades)
      remoteVersion = Versioning.getKnownVersionOrDefault(
          Versioning.getVersion(VersioningIO.readOrdinal(dis)),
          null);
      final int dominoNumber = readDominoNumber(dis, sharedResource);
      dominoCount.set(dominoNumber);
      if (!sharedResource) {
        if (tipDomino()) {
          logger.info("thread owned receiver forcing itself to send on thread owned sockets");
          // if domino count is >= 2 use shared resources.
          // Also see DistributedCacheOperation#supportsDirectAck
        } else {
          ConnectionTable.threadWantsOwnResources();
          if (logger.isDebugEnabled()) {
            logger.debug(
                "thread-owned receiver with domino count of {} will prefer sending on thread-owned sockets",
                dominoNumber);
          }
        }
        conduit.getStats().incThreadOwnedReceivers(1L, dominoNumber);
        // Because this thread is not shared resource, it will be used for direct ack.
        // Direct ack messages can be large. This call will resize the send buffer.
        setSendBufferSize(socket);
      }
      setThreadName(dominoNumber);
    } catch (Exception e) {
      owner.getConduit().getCancelCriterion().checkCancelInProgress(e); // bug 37101
      logger.fatal("Error deserializing P2P handshake message", e);
      readerShuttingDown = true;
      requestClose("Error deserializing P2P handshake message");
      return true;
    }
    if (logger.isDebugEnabled()) {
      logger.debug("P2P handshake remoteAddr is {}{}", remoteAddr,
          remoteVersion != null ? " (" + remoteVersion + ')' : "");
    }
    try {
      final String authInit = System.getProperty(SECURITY_SYSTEM_PREFIX + SECURITY_PEER_AUTH_INIT);
      final boolean isSecure = authInit != null && !authInit.isEmpty();

      if (isSecure) {
        if (owner.getConduit().waitForMembershipCheck(remoteAddr)) {
          sendOKHandshakeReply();
          notifyHandshakeWaiter(true);
        } else {
          // check if we need notifyHandshakeWaiter() call.
          notifyHandshakeWaiter(false);
          logger.warn("{} timed out during a membership check.",
              p2pReaderName());
          return true;
        }
      } else {
        sendOKHandshakeReply();
        try {
          notifyHandshakeWaiter(true);
        } catch (Exception e) {
          logger.fatal("Uncaught exception from listener", e);
        }
      }
      finishedConnecting = true;
    } catch (IOException ex) {
      final String err = "Failed sending handshake reply";
      if (logger.isDebugEnabled()) {
        logger.debug(err, ex);
      }
      readerShuttingDown = true;
      requestClose(err + ": " + ex);
      return true;
    }
    return false;
  }

  static int readDominoNumber(final DataInput dis, final boolean sharedResource)
      throws IOException {
    final int dominoNumber = dis.readInt();
    if (sharedResource) {
      return 0;
    }
    return dominoNumber;
  }

  static void checkHandshakeInitialByte(@NotNull final DataInput dis) throws IOException {
    final byte initialByte = dis.readByte();
    if (initialByte != 0) {
      throw new IllegalStateException(
          format("Detected non Geode peer during handshake due to initial byte being %s",
              initialByte));
    }
  }

  static void checkHandshakeVersion(@NotNull final DataInput dis) throws IOException {
    final byte version = dis.readByte();
    if (version != HANDSHAKE_VERSION) {
      throw new IllegalStateException(
          format(
              "Detected wrong version of Geode product during handshake. Expected %s but found %s",
              HANDSHAKE_VERSION, version));
    }
  }

  private boolean readMessageHeader(ByteBuffer peerDataBuffer) throws IOException {
    int headerStartPos = peerDataBuffer.position();
    messageLength = peerDataBuffer.getInt();
    /* nioMessageVersion = */
    calcHdrVersion(messageLength);
    messageLength = calcMsgByteSize(messageLength);
    messageType = peerDataBuffer.get();
    messageId = peerDataBuffer.getShort();
    directAck = (messageType & DIRECT_ACK_BIT) != 0;
    if (directAck) {
      // clear the ack bit
      messageType &= ~DIRECT_ACK_BIT;
    }
    if (!validMsgType(messageType)) {
      Integer nioMessageTypeInteger = (int) messageType;
      logger.fatal("Unknown P2P message type: {}", nioMessageTypeInteger);
      readerShuttingDown = true;
      requestClose(format("Unknown P2P message type: %s",
          nioMessageTypeInteger));
      return true;
    }
    lengthSet = true;
    // keep the header "in" the buffer until we have read the entire msg.
    // Trust me: this will reduce copying on large messages.
    peerDataBuffer.position(headerStartPos);
    return false;
  }

  private void readMessage(ByteBuffer peerDataBuffer, AbstractExecutor threadMonitorExecutor) {
    if (messageType == NORMAL_MSG_TYPE) {
      owner.getConduit().getStats().incMessagesBeingReceived(true, messageLength);
      try (ByteBufferInputStream bbis =
          remoteVersion == null ? new ByteBufferInputStream(peerDataBuffer)
              : new VersionedByteBufferInputStream(peerDataBuffer, remoteVersion)) {
        ReplyProcessor21.initMessageRPId();
        // add serialization stats
        long startSer = owner.getConduit().getStats().startMsgDeserialization();
        int startingPosition = peerDataBuffer.position();
        DistributionMessage msg;
        try {
          msg = (DistributionMessage) InternalDataSerializer.readDSFID(bbis);
        } catch (SerializationException e) {
          logger.info("input buffer starting position {} "
              + " current position {} limit {} capacity {} message length {}",
              startingPosition, peerDataBuffer.position(), peerDataBuffer.limit(),
              peerDataBuffer.capacity(), messageLength);
          throw e;
        }
        owner.getConduit().getStats().endMsgDeserialization(startSer);
        if (bbis.available() != 0) {
          logger.warn("Message deserialization of {} did not read {} bytes.", msg,
              bbis.available());
        }
        try {
          if (!dispatchMessage(msg, messageLength, directAck, threadMonitorExecutor)) {
            directAck = false;
          }
        } catch (MemberShunnedException e) {
          directAck = false; // don't respond
        } catch (Exception de) {
          owner.getConduit().getCancelCriterion().checkCancelInProgress(de);
          logger.fatal("Error dispatching message", de);
        } catch (ThreadDeath td) {
          throw td;
        } catch (VirtualMachineError err) {
          initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        } catch (Throwable t) {
          // Whenever you catch Error or Throwable, you must also
          // catch VirtualMachineError (see above). However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          getCheckFailure();
          logger.fatal("Throwable dispatching message", t);
        }
      } catch (VirtualMachineError err) {
        initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable t) {
        logger.fatal("Error deserializing message", t);
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        getCheckFailure();
        sendFailureReply(ReplyProcessor21.getMessageRPId(), "Error deserializing message", t,
            directAck);
        if (t instanceof ThreadDeath) {
          throw (ThreadDeath) t;
        }
        if (t instanceof CancelException) {
          if (!(t instanceof CacheClosedException)) {
            // Just log a message if we had trouble deserializing due to
            // CacheClosedException; see bug 43543
            throw (CancelException) t;
          }
        }
      } finally {
        ReplyProcessor21.clearMessageRPId();
      }
    } else if (messageType == CHUNKED_MSG_TYPE) {
      MsgDestreamer md = obtainMsgDestreamer(messageId, remoteVersion);
      owner.getConduit().getStats().incMessagesBeingReceived(md.size() == 0,
          messageLength);
      try {
        md.addChunk(peerDataBuffer, messageLength);
      } catch (IOException ex) {
        // ignored
      }
    } else /* (nioMessageType == END_CHUNKED_MSG_TYPE) */ {
      MsgDestreamer md = obtainMsgDestreamer(messageId, remoteVersion);
      owner.getConduit().getStats().incMessagesBeingReceived(md.size() == 0,
          messageLength);
      try {
        md.addChunk(peerDataBuffer, messageLength);
      } catch (IOException ex) {
        logger.fatal("Failed handling end chunk message", ex);
      }
      DistributionMessage msg = null;
      int msgLength;
      String failureMsg = null;
      Throwable failureEx = null;
      int rpId = 0;
      boolean interrupted = false;
      try {
        msg = md.getMessage();
      } catch (ClassNotFoundException ex) {
        owner.getConduit().getStats().decMessagesBeingReceived(md.size());
        failureMsg = "ClassNotFound deserializing message";
        failureEx = ex;
        rpId = md.getRPid();
        logAtInfoAndFatal(failureMsg, failureEx);
      } catch (IOException ex) {
        owner.getConduit().getStats().decMessagesBeingReceived(md.size());
        failureMsg = "IOException deserializing message";
        failureEx = ex;
        rpId = md.getRPid();
        logAtInfoAndFatal(failureMsg, failureEx);
      } catch (InterruptedException ex) {
        interrupted = true;
        owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
      } catch (VirtualMachineError err) {
        initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable ex) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        getCheckFailure();
        owner.getConduit().getCancelCriterion().checkCancelInProgress(ex);
        owner.getConduit().getStats().decMessagesBeingReceived(md.size());
        failureMsg = "Unexpected failure deserializing message";
        failureEx = ex;
        rpId = md.getRPid();
        logAtInfoAndFatal(failureMsg, failureEx);
      } finally {
        msgLength = md.size();
        releaseMsgDestreamer(messageId, md);
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (msg != null) {
        try {
          if (!dispatchMessage(msg, msgLength, directAck, threadMonitorExecutor)) {
            directAck = false;
          }
        } catch (MemberShunnedException e) {
          // not a member anymore - don't reply
          directAck = false;
        } catch (Exception de) {
          owner.getConduit().getCancelCriterion().checkCancelInProgress(de);
          logger.fatal("Error dispatching message", de);
        } catch (ThreadDeath td) {
          throw td;
        } catch (VirtualMachineError err) {
          initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        } catch (Throwable t) {
          // Whenever you catch Error or Throwable, you must also
          // catch VirtualMachineError (see above). However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          getCheckFailure();
          logger.fatal("Throwable dispatching message", t);
        }
      } else if (failureEx != null) {
        sendFailureReply(rpId, failureMsg, failureEx, directAck);
      }
    }
  }

  /**
   * For exceptions that we absolutely must see in the log files, use this method
   * to log the problem first at "info" level and then at "fatal" level. We do this
   * in case the "fatal" level log entry generates an alert that gets blocked in
   * transmitting the data to an alert listener like the JMX Manager
   */
  private void logAtInfoAndFatal(String failureMsg, Throwable failureEx) {
    // log at info level first in case fatal-level alert notification becomes blocked
    logger.info(failureMsg, failureEx);
    // log at fatal-level with toString() on the exception since this will generate an
    // alert
    logger.fatal(failureMsg, failureEx.toString());
  }

  void readHandshakeForSender(DataInputStream dis, ByteBuffer peerDataBuffer) {
    try {
      int replyCode = dis.readUnsignedByte();
      switch (replyCode) {
        case REPLY_CODE_OK:
          ioFilter.doneReading(peerDataBuffer);
          notifyHandshakeWaiter(true);
          return;
        case REPLY_CODE_OK_WITH_ASYNC_INFO:
          asyncDistributionTimeout = dis.readInt();
          asyncQueueTimeout = dis.readInt();
          asyncMaxQueueSize = (long) dis.readInt() * (1024 * 1024);
          if (asyncDistributionTimeout != 0) {
            logger.info("{} async configuration received {}.", p2pReaderName(),
                " asyncDistributionTimeout=" + asyncDistributionTimeout
                    + " asyncQueueTimeout=" + asyncQueueTimeout
                    + " asyncMaxQueueSize=" + asyncMaxQueueSize / (1024 * 1024));
          }
          // read the product version ordinal for on-the-fly serialization
          // transformations (for rolling upgrades)
          remoteVersion = Versioning.getKnownVersionOrDefault(
              Versioning.getVersion(VersioningIO.readOrdinal(dis)),
              null);
          ioFilter.doneReading(peerDataBuffer);
          notifyHandshakeWaiter(true);
          if (preserveOrder && asyncDistributionTimeout != 0) {
            asyncMode = true;
          }

          return;
        default:
          String err =
              "Unknown handshake reply code: " + replyCode + " messageLength: " + messageLength;
          if (replyCode == 0 && logger.isDebugEnabled()) {
            logger.debug(err + " (peer probably departed ungracefully)");
          } else {
            logger.fatal(err);
          }
          readerShuttingDown = true;
          requestClose(err);
      }
    } catch (Exception e) {
      owner.getConduit().getCancelCriterion().checkCancelInProgress(e);
      logger.fatal("Error deserializing P2P handshake reply", e);
      readerShuttingDown = true;
      requestClose("Error deserializing P2P handshake reply");
    } catch (ThreadDeath td) {
      throw td;
    } catch (VirtualMachineError err) {
      initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    } catch (Throwable t) {
      // Whenever you catch Error or Throwable, you must also
      // catch VirtualMachineError (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      getCheckFailure();
      logger.fatal("Throwable deserializing P2P handshake reply", t);
      readerShuttingDown = true;
      requestClose("Throwable deserializing P2P handshake reply");
    }
  }

  @SuppressWarnings("deprecation")
  private void initiateFailure(final VirtualMachineError err) {
    SystemFailure.initiateFailure(err);
  }

  @SuppressWarnings("deprecation")
  private void getCheckFailure() {
    SystemFailure.checkFailure();
  }

  private void setThreadName(int dominoNumber) {
    Thread.currentThread().setName(THREAD_KIND_IDENTIFIER + " for " + remoteAddr + " "
        + (sharedResource ? "" : "un") + "shared" + " " + (preserveOrder ? "" : "un")
        + "ordered sender uid=" + uniqueId + (dominoNumber > 0 ? " dom #" + dominoNumber : "")
        + " local port=" + socket.getLocalPort() + " remote port=" + socket.getPort());
  }

  private boolean dispatchMessage(DistributionMessage msg, int bytesRead, boolean directAck,
      AbstractExecutor threadMonitorExecutor)
      throws MemberShunnedException {
    threadMonitorExecutor.resumeMonitoring();
    try {
      msg.setDoDecMessagesBeingReceived(true);
      if (directAck) {
        Assert.assertTrue(!isSharedResource(),
            "We were asked to send a direct reply on a shared socket");
        msg.setReplySender(new DirectReplySender(this));
      }
      owner.getConduit().messageReceived(this, msg, bytesRead);
      return true;
    } finally {
      threadMonitorExecutor.suspendMonitoring();
      if (msg.containsRegionContentChange()) {
        messagesReceived++;
      }
    }
  }

  TCPConduit getConduit() {
    return conduit;
  }

  protected Socket getSocket() throws SocketException {
    Socket result = socket;
    if (result == null) {
      throw new SocketException("socket has been closed");
    }
    return result;
  }

  boolean isSocketClosed() {
    return socket.isClosed() || !socket.isConnected();
  }

  boolean isReceiverStopped() {
    return stopped;
  }

  private boolean isSocketInUse() {
    return socketInUse;
  }

  protected void accessed() {
    accessed = true;
  }

  /**
   * return the DM id of the member on the other side of this connection.
   */
  public InternalDistributedMember getRemoteAddress() {
    return remoteAddr;
  }

  /**
   * Return the version of the member on the other side of this connection.
   */
  KnownVersion getRemoteVersion() {
    return remoteVersion;
  }

  @Override
  public String toString() {
    return remoteAddr + "(uid=" + uniqueId + ")"
        + (remoteVersion != null && remoteVersion != KnownVersion.CURRENT
            ? "(v" + remoteVersion + ')' : "");
  }

  /**
   * answers whether this connection was initiated in this vm
   *
   * @return true if the connection was initiated here
   * @since GemFire 5.1
   */
  boolean getOriginatedHere() {
    return !isReceiver;
  }

  /**
   * A shared sender connection will leave a reader thread around to ensure that the
   * socket is properly closed at this end. When that is the case isResidualReaderThread
   * will return true.
   */
  @VisibleForTesting
  public boolean hasResidualReaderThread() {
    return hasResidualReaderThread;
  }

  /**
   * answers whether this connection is used for ordered message delivery
   */
  boolean getPreserveOrder() {
    return preserveOrder;
  }

  /**
   * answers the unique ID of this connection in the originating VM
   */
  protected long getUniqueId() {
    return uniqueId;
  }

  /**
   * answers the number of messages received by this connection
   */
  long getMessagesReceived() {
    return messagesReceived;
  }

  /**
   * answers the number of messages sent on this connection
   */
  long getMessagesSent() {
    return messagesSent;
  }

  private class BatchBufferFlusher extends Thread {

    private volatile boolean flushNeeded;
    private volatile boolean timeToStop;
    private final DMStats stats;

    BatchBufferFlusher() {
      setDaemon(true);
      stats = owner.getConduit().getStats();
    }

    /**
     * Called when a message writer needs the current fillBatchBuffer flushed
     */
    void flushBuffer(ByteBuffer bb) {
      final long start = DistributionStats.getStatTime();
      try {
        synchronized (this) {
          synchronized (batchLock) {
            if (bb != fillBatchBuffer) {
              // it must have already been flushed. So just return and use the new fillBatchBuffer
              return;
            }
          }
          flushNeeded = true;
          notifyAll();
        }
        synchronized (batchLock) {
          // Wait for the flusher thread
          while (bb == fillBatchBuffer) {
            owner.getConduit().getCancelCriterion().checkCancelInProgress(null);
            boolean interrupted = Thread.interrupted();
            try {
              batchLock.wait(); // spurious wakeup ok
            } catch (InterruptedException ex) {
              interrupted = true;
            } finally {
              if (interrupted) {
                Thread.currentThread().interrupt();
              }
            }
          }
        }
      } finally {
        owner.getConduit().getStats().incBatchWaitTime(start);
      }
    }

    public void close() {
      synchronized (this) {
        timeToStop = true;
        flushNeeded = true;
        notifyAll();
      }
    }

    @Override
    public void run() {
      try {
        synchronized (this) {
          while (!timeToStop) {
            if (!flushNeeded && fillBatchBuffer.position() <= BATCH_BUFFER_SIZE / 2) {
              wait(BATCH_FLUSH_MS); // spurious wakeup ok
            }
            if (flushNeeded || fillBatchBuffer.position() > BATCH_BUFFER_SIZE / 2) {
              final long start = DistributionStats.getStatTime();
              synchronized (batchLock) {
                // This is the only block of code that will swap the buffer references
                flushNeeded = false;
                ByteBuffer tmp = fillBatchBuffer;
                fillBatchBuffer = sendBatchBuffer;
                sendBatchBuffer = tmp;
                batchLock.notifyAll();
              }
              // We now own the sendBatchBuffer
              if (sendBatchBuffer.position() > 0) {
                final boolean origSocketInUse = socketInUse;
                socketInUse = true;
                try {
                  sendBatchBuffer.flip();
                  SocketChannel channel = getSocket().getChannel();
                  writeFully(channel, sendBatchBuffer, false, null);
                  sendBatchBuffer.clear();
                } catch (IOException | ConnectionException ex) {
                  logger.fatal("Exception flushing batch send buffer: %s", ex);
                  readerShuttingDown = true;
                  requestClose(format("Exception flushing batch send buffer: %s", ex));
                } finally {
                  accessed();
                  socketInUse = origSocketInUse;
                }
              }
              stats.incBatchFlushTime(start);
            }
          }
        }
      } catch (InterruptedException ex) {
        // time for this thread to shutdown
      }
    }
  }
}
