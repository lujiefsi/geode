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

package org.apache.geode.internal.cache.wan;

import static org.apache.geode.internal.cache.wan.GatewaySenderEventImpl.TransactionMetadataDisposition.EXCLUDE;
import static org.apache.geode.internal.cache.wan.GatewaySenderEventImpl.TransactionMetadataDisposition.INCLUDE_LAST_EVENT;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.geode.DataSerializer;
import org.apache.geode.InternalGemFireError;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.TransactionId;
import org.apache.geode.cache.asyncqueue.AsyncEvent;
import org.apache.geode.cache.util.ObjectSizer;
import org.apache.geode.cache.wan.EventSequenceID;
import org.apache.geode.internal.cache.CachedDeserializable;
import org.apache.geode.internal.cache.CachedDeserializableFactory;
import org.apache.geode.internal.cache.Conflatable;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EnumListenerEvent;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.Token;
import org.apache.geode.internal.cache.WrappedCallbackArgument;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.lang.ObjectUtils;
import org.apache.geode.internal.offheap.OffHeapHelper;
import org.apache.geode.internal.offheap.ReferenceCountHelper;
import org.apache.geode.internal.offheap.Releasable;
import org.apache.geode.internal.offheap.StoredObject;
import org.apache.geode.internal.offheap.annotations.OffHeapIdentifier;
import org.apache.geode.internal.offheap.annotations.Released;
import org.apache.geode.internal.offheap.annotations.Retained;
import org.apache.geode.internal.offheap.annotations.Unretained;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.size.Sizeable;

/**
 * Class <code>GatewaySenderEventImpl</code> represents an event sent between
 * <code>GatewaySender</code>
 *
 *
 * @since GemFire 7.0
 *
 */
public class GatewaySenderEventImpl
    implements AsyncEvent, DataSerializableFixedID, Conflatable, Sizeable, Releasable {
  private static final long serialVersionUID = -5690172020872255422L;
  protected static final Object TOKEN_NULL = new Object();

  // It should use current version. But it was hard-coded to be 0x11, i.e. GEODE_120_ORDINAL,
  // by mistake since 120 to pre-190
  protected static final short VERSION = KnownVersion.getCurrentVersion().ordinal();

  protected EnumListenerEvent operation;

  protected Object substituteValue;

  /**
   * The action to be taken (e.g. AFTER_CREATE)
   */
  protected int action;

  /**
   * The operation detail of EntryEvent (e.g. LOAD, PUTALL etc.)
   */
  protected int operationDetail;

  /**
   * The number of parts for the <code>Message</code>
   *
   * @see Message
   */
  protected int numberOfParts;

  /**
   * The identifier of this event
   */
  protected EventID id;

  /**
   * The <code>Region</code> that was updated
   */
  private transient LocalRegion region;

  /**
   * The name of the region being affected by this event
   */
  protected String regionPath;

  /**
   * The key being affected by this event
   */
  protected Object key;

  /**
   * The serialized new value for this event's key. May not be computed at construction time.
   */
  protected volatile byte[] value;

  /**
   * The "object" form of the value. Will be null after this object is deserialized.
   */
  @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  protected transient Object valueObj;
  protected transient boolean valueObjReleased;

  private transient boolean serializedValueNotAvailable;

  /**
   * Whether the value is a serialized object or just a byte[]
   */
  protected byte valueIsObject;

  /**
   * The callback argument for this event
   */
  protected GatewaySenderEventCallbackArgument callbackArgument;

  /**
   * The version timestamp
   */
  protected long versionTimeStamp;

  /**
   * Whether this event is a possible duplicate
   */
  protected boolean possibleDuplicate;

  /**
   * Whether this event is acknowledged after the ack received by AckReaderThread. As of now this is
   * getting used for PDX related GatewaySenderEvent. But can be extended for for other
   * GatewaySenderEvent.
   */
  protected volatile boolean isAcked;

  /**
   * Whether this event is dispatched by dispatcher. As of now this is getting used for PDX related
   * GatewaySenderEvent. But can be extended for for other GatewaySenderEvent.
   */
  protected volatile boolean isDispatched;
  /**
   * The creation timestamp in ms
   */
  protected long creationTime;

  /**
   * For ParalledGatewaySender we need bucketId of the PartitionRegion on which the update operation
   * was applied.
   */
  protected int bucketId;

  protected Long shadowKey = -1L;

  protected boolean isInitialized;

  private transient boolean isConcurrencyConflict = false;

  private short version;

  private boolean isLastEventInTransaction = false;

  private TransactionId transactionId = null;


  /**
   * Is this thread in the process of serializing this event?
   */
  public static final ThreadLocal<Boolean> isSerializingValue =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  private static final int CREATE_ACTION = 0;

  private static final int UPDATE_ACTION = 1;

  private static final int DESTROY_ACTION = 2;

  private static final int VERSION_ACTION = 3;

  private static final int INVALIDATE_ACTION = 5;
  /**
   * Static constants for Operation detail of EntryEvent.
   */
  private static final int OP_DETAIL_NONE = 10;

  private static final int OP_DETAIL_LOCAL_LOAD = 11;

  private static final int OP_DETAIL_NET_LOAD = 12;

  private static final int OP_DETAIL_PUTALL = 13;

  private static final int OP_DETAIL_REMOVEALL = 14;

  private static final int DEFAULT_SERIALIZED_VALUE_SIZE = -1;

  private volatile int serializedValueSize = DEFAULT_SERIALIZED_VALUE_SIZE;



  /**
   * No-arg constructor for data serialization.
   *
   * @see DataSerializer
   */
  public GatewaySenderEventImpl() {}

  /**
   * @param operation The operation for this event (e.g. AFTER_CREATE)
   * @param event The <code>CacheEvent</code> on which this <code>GatewayEventImpl</code> is based
   * @param substituteValue The value to be enqueued instead of the value in the event.
   * @param transactionMetadataDisposition indicating the inclusion of transaction metadata.
   *
   */
  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent<?, ?> event,
      Object substituteValue, final TransactionMetadataDisposition transactionMetadataDisposition)
      throws IOException {
    this(operation, event, substituteValue, true, transactionMetadataDisposition);
  }

  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent<?, ?> event,
      Object substituteValue) throws IOException {
    this(operation, event, substituteValue, true, EXCLUDE);
  }

  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent<?, ?> event,
      Object substituteValue, boolean initialize, int bucketId,
      final TransactionMetadataDisposition transactionMetadataDisposition) throws IOException {
    this(operation, event, substituteValue, initialize, transactionMetadataDisposition);
    this.bucketId = bucketId;
  }

  /**
   * @param operation The operation for this event (e.g. AFTER_CREATE)
   * @param ce The <code>CacheEvent</code> on which this <code>GatewayEventImpl</code> is based
   * @param substituteValue The value to be enqueued instead of the value in the event.
   * @param initialize Whether to initialize this instance
   * @param transactionMetadataDisposition indicating the inclusion of transaction metadata.
   */
  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent<?, ?> ce,
      Object substituteValue, boolean initialize,
      final TransactionMetadataDisposition transactionMetadataDisposition) throws IOException {
    // Set the operation and event
    final EntryEventImpl event = (EntryEventImpl) ce;
    this.operation = operation;
    this.substituteValue = substituteValue;

    // Initialize the region name. This is being done here because the event
    // can get serialized/deserialized (for some reason) between the time
    // it is set above and used (in initialize). If this happens, the
    // region is null because it is a transient field of the event.
    region = (LocalRegion) event.getRegion();
    regionPath = region.getFullPath();

    // Initialize the unique id
    initializeId(event);

    // Initialize possible duplicate
    possibleDuplicate = event.isPossibleDuplicate();

    // Initialize ack and dispatch status of events
    isAcked = false;
    isDispatched = false;


    // Initialize the creation timestamp
    creationTime = System.currentTimeMillis();

    if (event.getVersionTag() != null && event.getVersionTag().hasValidVersion()) {
      versionTimeStamp = event.getVersionTag().getVersionTimeStamp();
    }

    // Set key
    // System.out.println("this._entryEvent: " + event);
    // System.out.println("this._entryEvent.getKey(): " +
    // event.getKey());
    key = event.getKey();

    initializeValue(event);

    // Set the callback arg
    callbackArgument = (GatewaySenderEventCallbackArgument) event.getRawCallbackArgument();

    // Initialize the action and number of parts (called after _callbackArgument
    // is set above)
    initializeAction(this.operation);

    // initialize the operation detail
    initializeOperationDetail(event.getOperation());

    setShadowKey(event.getTailKey());

    if (initialize) {
      initialize();
    }
    isConcurrencyConflict = event.isConcurrencyConflict();

    if (transactionMetadataDisposition != EXCLUDE) {
      transactionId = event.getTransactionId();
      isLastEventInTransaction =
          transactionMetadataDisposition == INCLUDE_LAST_EVENT && null != transactionId;
    }
  }

  /**
   * Used to create a heap copy of an offHeap event. Note that this constructor produces an instance
   * that does not need to be released.
   */
  protected GatewaySenderEventImpl(GatewaySenderEventImpl offHeapEvent) {
    operation = offHeapEvent.operation;
    action = offHeapEvent.action;
    numberOfParts = offHeapEvent.numberOfParts;
    id = offHeapEvent.id;
    region = offHeapEvent.region;
    regionPath = offHeapEvent.regionPath;
    key = offHeapEvent.key;
    callbackArgument = offHeapEvent.callbackArgument;
    versionTimeStamp = offHeapEvent.versionTimeStamp;
    possibleDuplicate = offHeapEvent.possibleDuplicate;
    isAcked = offHeapEvent.isAcked;
    isDispatched = offHeapEvent.isDispatched;
    creationTime = offHeapEvent.creationTime;
    bucketId = offHeapEvent.bucketId;
    shadowKey = offHeapEvent.shadowKey;
    isInitialized = offHeapEvent.isInitialized;

    valueObj = null;
    valueObjReleased = false;
    valueIsObject = offHeapEvent.valueIsObject;
    value = offHeapEvent.getSerializedValue();
    transactionId = offHeapEvent.transactionId;
    isLastEventInTransaction = offHeapEvent.isLastEventInTransaction;
  }

  /**
   * Returns this event's action
   *
   * @return this event's action
   */
  public int getAction() {
    return action;
  }

  /**
   * Returns this event's operation
   *
   * @return this event's operation
   */
  @Override
  public Operation getOperation() {
    Operation op = null;
    switch (action) {
      case CREATE_ACTION:
        switch (operationDetail) {
          case OP_DETAIL_LOCAL_LOAD:
            op = Operation.LOCAL_LOAD_CREATE;
            break;
          case OP_DETAIL_NET_LOAD:
            op = Operation.NET_LOAD_CREATE;
            break;
          case OP_DETAIL_PUTALL:
            op = Operation.PUTALL_CREATE;
            break;
          case OP_DETAIL_NONE:
            op = Operation.CREATE;
            break;
          // if operationDetail is none of the above, then default should be NONE
          default:
            op = Operation.CREATE;
            break;
        }
        break;
      case UPDATE_ACTION:
        switch (operationDetail) {
          case OP_DETAIL_LOCAL_LOAD:
            op = Operation.LOCAL_LOAD_UPDATE;
            break;
          case OP_DETAIL_NET_LOAD:
            op = Operation.NET_LOAD_UPDATE;
            break;
          case OP_DETAIL_PUTALL:
            op = Operation.PUTALL_UPDATE;
            break;
          case OP_DETAIL_NONE:
            op = Operation.UPDATE;
            break;
          // if operationDetail is none of the above, then default should be NONE
          default:
            op = Operation.UPDATE;
            break;
        }
        break;
      case DESTROY_ACTION:
        if (operationDetail == OP_DETAIL_REMOVEALL) {
          op = Operation.REMOVEALL_DESTROY;
        } else {
          op = Operation.DESTROY;
        }
        break;
      case VERSION_ACTION:
        op = Operation.UPDATE_VERSION_STAMP;
        break;
      case INVALIDATE_ACTION:
        op = Operation.INVALIDATE;
        break;
    }
    return op;
  }

  public Object getSubstituteValue() {
    return substituteValue;
  }

  public EnumListenerEvent getEnumListenerEvent() {
    return operation;
  }

  /**
   * Return this event's region name
   *
   * @return this event's region name
   */
  public String getRegionPath() {
    return regionPath;
  }

  public boolean isInitialized() {
    return isInitialized;
  }

  /**
   * Returns this event's key
   *
   * @return this event's key
   */
  @Override
  public Object getKey() {
    // TODO:Asif : Ideally would like to have throw exception if the key
    // is TOKEN_UN_INITIALIZED, but for the time being trying to retain the GFE
    // behaviour
    // of returning null if getKey is invoked on un-initialized gateway event
    return isInitialized() ? key : null;
  }

  /**
   * Returns whether this event's value is a serialized object
   *
   * @return whether this event's value is a serialized object
   */
  public byte getValueIsObject() {
    return valueIsObject;
  }

  /**
   * Return this event's callback argument
   *
   * @return this event's callback argument
   */
  @Override
  public Object getCallbackArgument() {
    Object result = getSenderCallbackArgument();
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument) result;
      result = wca.getOriginalCallbackArg();
    }
    return result;
  }

  public GatewaySenderEventCallbackArgument getSenderCallbackArgument() {
    return callbackArgument;
  }

  /**
   * Return this event's number of parts
   *
   * @return this event's number of parts
   */
  public int getNumberOfParts() {
    return numberOfParts;
  }

  /**
   * Return the currently held form of the object. May return a retained OFF_HEAP_REFERENCE.
   */
  @Retained
  public Object getRawValue() {
    @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object result = value;
    if (result == null) {
      result = substituteValue;
      if (result == null) {
        result = valueObj;
        if (result instanceof StoredObject && ((StoredObject) result).hasRefCount()) {
          if (valueObjReleased) {
            result = null;
          } else {
            StoredObject ohref = (StoredObject) result;
            if (!ohref.retain()) {
              result = null;
            } else if (valueObjReleased) {
              ohref.release();
              result = null;
            }
          }
        }
      }
    }
    return result;
  }

  /**
   * Return this event's deserialized value
   *
   * @return this event's deserialized value
   */
  @Override
  public Object getDeserializedValue() {
    if (valueIsObject == 0x00) {
      Object result = value;
      if (result == null) {
        @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
        Object so = valueObj;
        if (valueObjReleased) {
          throw new IllegalStateException(
              "Value is no longer available. getDeserializedValue must be called before processEvents returns.");
        }
        if (so instanceof StoredObject) {
          return ((StoredObject) so).getValueAsDeserializedHeapObject();
        } else {
          throw new IllegalStateException(
              "expected valueObj field to be an instance of StoredObject but it was " + so);
        }
      }
      return result;
    } else {
      Object vo = valueObj;
      if (vo != null) {
        if (vo instanceof StoredObject) {
          @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
          StoredObject so = (StoredObject) vo;
          return so.getValueAsDeserializedHeapObject();
        } else {
          return vo; // it is already deserialized
        }
      } else {
        if (value != null) {
          Object result = EntryEventImpl.deserialize(value);
          valueObj = result;
          return result;
        } else if (substituteValue != null) {
          // If the substitute value is set, return it.
          return substituteValue;
        } else {
          if (valueObjReleased) {
            throw new IllegalStateException(
                "Value is no longer available. getDeserializedValue must be called before processEvents returns.");
          }
          // both value and valueObj are null but we did not free it.
          return null;
        }
      }
    }
  }

  /**
   * Returns the value in the form of a String. This should be used by code that wants to log the
   * value. This is a debugging exception.
   */
  public String getValueAsString(boolean deserialize) {
    Object v = value;
    if (v == null) {
      v = substituteValue;
    }
    if (deserialize) {
      try {
        v = getDeserializedValue();
      } catch (Exception | InternalGemFireError e) {
        return "Could not convert value to string because " + e;
      }
    }
    if (v == null) {
      @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      Object ov = valueObj;
      if (ov instanceof CachedDeserializable) {
        return ((CachedDeserializable) ov).getStringForm();
      }
    }
    if (v != null) {
      if (v instanceof byte[]) {
        byte[] bav = (byte[]) v;
        // Using Arrays.toString(bav) can cause us to run out of memory
        return "byte[" + bav.length + "]";
      } else {
        String valueStr;
        try {
          valueStr = v.toString();
        } catch (Exception e) {
          valueStr = "Could not convert value to string because " + e;
        }
        return valueStr;
      }
    } else {
      return "";
    }
  }

  public boolean isSerializedValueNotAvailable() {
    return serializedValueNotAvailable;
  }

  /**
   * If the value owned of this event is just bytes return that byte array; otherwise serialize the
   * value object and return the serialized bytes. Use {@link #getValueIsObject()} to determine if
   * the result is raw or serialized bytes.
   */
  @Override
  public byte[] getSerializedValue() {
    byte[] result = value;
    if (result == null) {
      if (substituteValue != null) {
        // The substitute value is set. Serialize it
        isSerializingValue.set(Boolean.TRUE);
        result = EntryEventImpl.serialize(substituteValue);
        isSerializingValue.set(Boolean.FALSE);
        return result;
      }
      @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      Object vo = valueObj;
      if (vo instanceof StoredObject) {
        synchronized (this) {
          result = value;
          if (result == null) {
            StoredObject so = (StoredObject) vo;
            result = so.getValueAsHeapByteArray();
            value = result;
          }
        }
      } else {
        synchronized (this) {
          result = value;
          if (result == null && vo != null && !(vo instanceof Token)) {
            isSerializingValue.set(Boolean.TRUE);
            result = EntryEventImpl.serialize(vo);
            isSerializingValue.set(Boolean.FALSE);
            value = result;
          } else if (result == null) {
            if (valueObjReleased) {
              serializedValueNotAvailable = true;
              throw new IllegalStateException(
                  "Value is no longer available. getSerializedValue must be called before processEvents returns.");
            }
          }
        }
      }
    }
    return result;
  }

  public void setPossibleDuplicate(boolean possibleDuplicate) {
    this.possibleDuplicate = possibleDuplicate;
  }

  @Override
  public boolean getPossibleDuplicate() {
    return possibleDuplicate;
  }

  public long getCreationTime() {
    return creationTime;
  }

  @Override
  public int getDSFID() {
    return GATEWAY_SENDER_EVENT_IMPL;
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    toDataPre_GEODE_1_15_0_0(out, context);
    out.writeInt(operationDetail);
  }

  public void toDataPre_GEODE_1_15_0_0(DataOutput out,
      SerializationContext context) throws IOException {
    toDataPre_GEODE_1_14_0_0(out, context);
    boolean hasTransaction = transactionId != null;
    DataSerializer.writeBoolean(hasTransaction, out);
    if (hasTransaction) {
      DataSerializer.writeBoolean(isLastEventInTransaction, out);
      context.getSerializer().writeObject(transactionId, out);
    }
  }

  public void toDataPre_GEODE_1_14_0_0(DataOutput out,
      SerializationContext context) throws IOException {
    toDataPre_GEODE_1_9_0_0(out, context);
    DataSerializer.writeBoolean(isConcurrencyConflict, out);
  }

  public void toDataPre_GEODE_1_9_0_0(DataOutput out, SerializationContext context)
      throws IOException {
    // Make sure we are initialized before we serialize.
    initialize();
    out.writeShort(VERSION);
    out.writeInt(action);
    out.writeInt(numberOfParts);
    // out.writeUTF(this._id);
    context.getSerializer().writeObject(id, out);
    DataSerializer.writeString(regionPath, out);
    out.writeByte(valueIsObject);
    serializeKey(out, context);
    DataSerializer.writeByteArray(getSerializedValue(), out);
    context.getSerializer().writeObject(callbackArgument, out);
    out.writeBoolean(possibleDuplicate);
    out.writeLong(creationTime);
    out.writeInt(bucketId);
    out.writeLong(shadowKey);
    out.writeLong(getVersionTimeStamp());
  }

  protected void serializeKey(DataOutput out,
      SerializationContext context) throws IOException {
    context.getSerializer().writeObject(key, out);
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    fromDataPre_GEODE_1_15_0_0(in, context);
    if (version >= KnownVersion.GEODE_1_15_0.ordinal()) {
      operationDetail = in.readInt();
    }
  }

  public void fromDataPre_GEODE_1_15_0_0(DataInput in, DeserializationContext context)
      throws IOException, ClassNotFoundException {
    fromDataPre_GEODE_1_14_0_0(in, context);
    if (version >= KnownVersion.GEODE_1_14_0.ordinal()) {
      boolean hasTransaction = DataSerializer.readBoolean(in);
      if (hasTransaction) {
        isLastEventInTransaction = DataSerializer.readBoolean(in);
        transactionId = context.getDeserializer().readObject(in);
      }
    }
  }

  public void fromDataPre_GEODE_1_14_0_0(DataInput in, DeserializationContext context)
      throws IOException, ClassNotFoundException {
    fromDataPre_GEODE_1_9_0_0(in, context);
    if (version >= KnownVersion.GEODE_1_9_0.ordinal()) {
      isConcurrencyConflict = DataSerializer.readBoolean(in);
    }
  }

  public void fromDataPre_GEODE_1_9_0_0(DataInput in, DeserializationContext context)
      throws IOException, ClassNotFoundException {
    version = in.readShort();
    isInitialized = true;
    action = in.readInt();
    numberOfParts = in.readInt();
    id = context.getDeserializer().readObject(in);
    regionPath = DataSerializer.readString(in);
    valueIsObject = in.readByte();
    deserializeKey(in, context);
    value = DataSerializer.readByteArray(in);
    callbackArgument = context.getDeserializer().readObject(in);
    possibleDuplicate = in.readBoolean();
    creationTime = in.readLong();
    bucketId = in.readInt();
    shadowKey = in.readLong();
    versionTimeStamp = in.readLong();
  }

  protected void deserializeKey(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    key = context.getDeserializer().readObject(in);
  }

  @Override
  public String toString() {
    return "GatewaySenderEventImpl[" + "id=" + id + ";action="
        + action + ";operation=" + getOperation() + ";region="
        + regionPath + ";key=" + key + ";value="
        + getValueAsString(true) + ";valueIsObject=" + valueIsObject
        + ";numberOfParts=" + numberOfParts + ";callbackArgument="
        + callbackArgument + ";possibleDuplicate=" + possibleDuplicate
        + ";creationTime=" + creationTime + ";shadowKey="
        + shadowKey + ";timeStamp=" + versionTimeStamp
        + ";acked=" + isAcked + ";dispatched=" + isDispatched
        + ";bucketId=" + bucketId
        + ";isConcurrencyConflict=" + isConcurrencyConflict
        + ";transactionId=" + transactionId
        + ";isLastEventInTransaction=" + isLastEventInTransaction
        + "]";
  }

  public String toSmallString() {
    return "GatewaySenderEventImpl[" + "id=" + id + ";operation="
        + getOperation() + ";region=" + regionPath + ";key="
        + key + ";shadowKey=" + shadowKey + ";bucketId="
        + bucketId + "]";
  }

  public static boolean isSerializingValue() {
    return isSerializingValue.get();
  }

  // public static boolean isDeserializingValue() {
  // return ((Boolean)isDeserializingValue.get()).booleanValue();
  // }

  // / Conflatable interface methods ///

  /**
   * Determines whether or not to conflate this message. This method will answer true IFF the
   * message's operation is AFTER_UPDATE and its region has enabled are conflation. Otherwise, this
   * method will answer false. Messages whose operation is AFTER_CREATE, AFTER_DESTROY,
   * AFTER_INVALIDATE or AFTER_REGION_DESTROY are not conflated.
   *
   * @return Whether to conflate this message
   */
  @Override
  public boolean shouldBeConflated() {
    // If the message is an update, it may be conflatable. If it is a
    // create, destroy, invalidate or destroy-region, it is not conflatable.
    // Only updates are conflated.
    return isUpdate();
  }

  @Override
  public String getRegionToConflate() {
    return regionPath;
  }

  @Override
  public Object getKeyToConflate() {
    return key;
  }

  @Override
  public Object getValueToConflate() {
    // Since all the uses of this are for logging
    // changing it to return the string form of the value
    // instead of the actual value.
    return getValueAsString(true);
  }

  @Override
  public void setLatestValue(Object value) {
    // Currently this method is never used.
    // If someone does want to use it in the future
    // then the implementation needs to be updated
    // to correctly update value, valueObj, and valueIsObject
    throw new UnsupportedOperationException();
  }

  // / End Conflatable interface methods ///

  /**
   * Returns whether this <code>GatewayEvent</code> represents an update.
   *
   * @return whether this <code>GatewayEvent</code> represents an update
   */
  protected boolean isUpdate() {
    // This event can be in one of three states:
    // - in memory primary (initialized)
    // - in memory secondary (not initialized)
    // - evicted to disk, read back in (initialized)
    // In the first case, both the operation and action are set.
    // In the second case, only the operation is set.
    // In the third case, only the action is set.
    return operation == null ? action == UPDATE_ACTION
        : operation == EnumListenerEvent.AFTER_UPDATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a create.
   *
   * @return whether this <code>GatewayEvent</code> represents a create
   */
  protected boolean isCreate() {
    // See the comment in isUpdate() for additional details
    return operation == null ? action == CREATE_ACTION
        : operation == EnumListenerEvent.AFTER_CREATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a destroy.
   *
   * @return whether this <code>GatewayEvent</code> represents a destroy
   */
  protected boolean isDestroy() {
    // See the comment in isUpdate() for additional details
    return operation == null ? action == DESTROY_ACTION
        : operation == EnumListenerEvent.AFTER_DESTROY;
  }

  /**
   * Initialize the unique identifier for this event. This id is used by the receiving
   * <code>Gateway</code> to keep track of which events have been processed. Duplicates can be
   * dropped.
   */
  private void initializeId(EntryEventImpl event) {
    // CS43_HA
    id = event.getEventId();
    // TODO:ASIF :Once stabilized remove the check below
    if (id == null) {
      throw new IllegalStateException(
          "No event id is available for this gateway event.");
    }

  }

  /**
   * Initialize this instance. Get the useful parts of the input operation and event.
   */
  public void initialize() {
    if (isInitialized()) {
      return;
    }
    isInitialized = true;
  }


  // Initializes the value object. This function need a relook because the
  // serialization of the value looks unnecessary.
  @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  protected void initializeValue(EntryEventImpl event) {
    // Set the value to be a byte[] representation of either the value or
    // substituteValue (if set).
    if (substituteValue == null) {
      // If the value is already serialized, use it.
      valueIsObject = 0x01;
      /*
       * so ends up being stored in this.valueObj
       */
      @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      StoredObject so;
      {
        ReferenceCountHelper.setReferenceCountOwner(this);
        so = event.getOffHeapNewValue();
        ReferenceCountHelper.setReferenceCountOwner(null);
      }

      if (so != null) {
        // Since GatewaySenderEventImpl instances can live for a long time in the gateway region
        // queue we do not want the StoredObject to be one that keeps the heap form cached.
        so = so.getStoredObjectWithoutHeapForm();
        valueObj = so;
        if (!so.isSerialized()) {
          valueIsObject = 0x00;
        }
      } else if (event.getCachedSerializedNewValue() != null) {
        // We want this to have lower precedence than StoredObject so that the gateway
        // can share a reference to the off-heap value.
        value = event.getCachedSerializedNewValue();
      } else {
        final Object newValue = event.getRawNewValue();
        assert !(newValue instanceof StoredObject); // since we already called getOffHeapNewValue()
                                                    // and it returned null
        if (newValue instanceof CachedDeserializable) {
          value = ((CachedDeserializable) newValue).getSerializedValue();
        } else if (newValue instanceof byte[]) {
          // The value is byte[]. Set _valueIsObject flag to 0x00 (not an object)
          value = (byte[]) newValue;
          valueIsObject = 0x00;
        } else {
          // The value is an object. It will be serialized later when getSerializedValue is called.
          valueObj = newValue;
          // to prevent bug 48281 we need to serialize it now
          getSerializedValue();
          valueObj = null;
        }
      }
    } else {
      // The substituteValue is set. Use it.
      if (substituteValue instanceof byte[]) {
        // The substituteValue is byte[]. Set valueIsObject flag to 0x00 (not an object)
        value = (byte[]) substituteValue;
        valueIsObject = 0x00;
      } else if (substituteValue == TOKEN_NULL) {
        // The substituteValue represents null. Set the value and substituteValue to null.
        value = null;
        substituteValue = null;
        valueIsObject = 0x01;
      } else {
        // The substituteValue is an object. Leave it as is.
        valueIsObject = 0x01;
      }
    }
  }

  /**
   * Initialize this event's action and number of parts
   *
   * @param operation The operation from which to initialize this event's action and number of parts
   */
  protected void initializeAction(EnumListenerEvent operation) {
    if (operation == EnumListenerEvent.AFTER_CREATE) {
      // Initialize after create action
      action = CREATE_ACTION;

      // Initialize number of parts
      // part 1 = action
      // part 2 = posDup flag
      // part 3 = regionName
      // part 4 = eventId
      // part 5 = key
      // part 6 = value (create and update only)
      // part 7 = whether callbackArgument is non-null
      // part 8 = callbackArgument (if non-null)
      // part 9 = versionTimeStamp;
      numberOfParts = (callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_UPDATE) {
      // Initialize after update action
      action = UPDATE_ACTION;

      // Initialize number of parts
      numberOfParts = (callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_DESTROY) {
      // Initialize after destroy action
      action = DESTROY_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      numberOfParts = (callbackArgument == null) ? 7 : 8;
    } else if (operation == EnumListenerEvent.TIMESTAMP_UPDATE) {
      // Initialize after destroy action
      action = VERSION_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      numberOfParts = (callbackArgument == null) ? 7 : 8;
    } else if (operation == EnumListenerEvent.AFTER_INVALIDATE) {
      // Initialize after invalidate action
      action = INVALIDATE_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      numberOfParts = (callbackArgument == null) ? 7 : 8;
    }
  }

  private void initializeOperationDetail(Operation operation) {
    if (operation.isLocalLoad()) {
      operationDetail = OP_DETAIL_LOCAL_LOAD;
    } else if (operation.isNetLoad()) {
      operationDetail = OP_DETAIL_NET_LOAD;
    } else if (operation.isPutAll()) {
      operationDetail = OP_DETAIL_PUTALL;
    } else if (operation.isRemoveAll()) {
      operationDetail = OP_DETAIL_REMOVEALL;
    } else {
      operationDetail = OP_DETAIL_NONE;
    }
  }

  @Override
  public EventID getEventId() {
    return id;
  }

  /**
   * Return the EventSequenceID of the Event
   *
   */
  @Override
  public EventSequenceID getEventSequenceID() {
    return new EventSequenceID(id.getMembershipID(), id.getThreadID(), id.getSequenceID());
  }

  public long getVersionTimeStamp() {
    return versionTimeStamp;
  }

  @Override
  public int getSizeInBytes() {
    // Calculate the size of this event. This is used for overflow to disk.

    // The sizes of the following variables are calculated:
    //
    // - the value (byte[])
    // - the original callback argument (Object)
    // - primitive and object instance variable references
    //
    // The sizes of the following variables are not calculated:

    // - the key because it is a reference
    // - the region and regionName because they are references
    // - the operation because it is a reference
    // - the entry event because it is nulled prior to calling this method
    // - the transactionId because it is is a reference

    // The size of instances of the following internal datatypes were estimated
    // using a NullDataOutputStream and hardcoded into this method:

    // - the id (an instance of EventId)
    // - the callbackArgument (an instance of GatewayEventCallbackArgument)

    int size = 0;

    // Add this event overhead
    size += Sizeable.PER_OBJECT_OVERHEAD;

    // Add object references
    // _id reference = 4 bytes
    // _region reference = 4 bytes
    // _regionName reference = 4 bytes
    // _key reference = 4 bytes
    // _callbackArgument reference = 4 bytes
    // _operation reference = 4 bytes
    // _entryEvent reference = 4 bytes
    // _transactionId reference = 4 bytes
    size += 32;

    // Add primitive references
    // int _action = 4 bytes
    // int _numberOfParts = 4 bytes
    // byte _valueIsObject = 1 byte
    // boolean _possibleDuplicate = 1 byte
    // int bucketId = 4 bytes
    // long shadowKey = 8 bytes
    // long creationTime = 8 bytes
    // boolean _hasTransaction = 1 byte
    // boolean _isLastEventInTransaction = 1 byte
    size += 32;

    // Add the id (an instance of EventId)
    // The hardcoded value below was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 56;

    // The value (a byte[])
    size += getSerializedValueSize();

    // The callback argument (a GatewayEventCallbackArgument wrapping an Object
    // which is the original callback argument)
    // The hardcoded value below represents the GatewayEventCallbackArgument
    // and was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 194;
    // The sizeOf call gets the size of the input callback argument.
    size += Sizeable.PER_OBJECT_OVERHEAD + sizeOf(getCallbackArgument());

    // the version timestamp
    size += 8;

    return size;
  }

  private int sizeOf(Object obj) {
    int size = 0;
    if (obj == null) {
      return size;
    }
    if (obj instanceof String) {
      size = ObjectSizer.DEFAULT.sizeof(obj);
    } else if (obj instanceof Integer) {
      size = 4; // estimate
    } else if (obj instanceof Long) {
      size = 8; // estimate
    } else {
      size = CachedDeserializableFactory.calcMemSize(obj) - Sizeable.PER_OBJECT_OVERHEAD;
    }
    return size;
  }


  // Asif: If the GatewayEvent serializes to a node where the region itself may
  // not be present or the
  // region is not created yet , and if the gateway event queue is persistent,
  // then even if
  // we try to set the region in the fromData , we may still get null. Though
  // the product is
  // not using this method anywhere still not comfortable changing the Interface
  // so
  // modifying the implementation a bit.

  @Override
  public Region<?, ?> getRegion() {
    // The region will be null mostly for the other node where the gateway event
    // is serialized
    return region != null ? region
        : CacheFactory.getAnyInstance().getRegion(regionPath);
  }

  public int getBucketId() {
    return bucketId;
  }

  public boolean isConcurrencyConflict() {
    return isConcurrencyConflict;
  }

  /**
   * @param tailKey the tailKey to set
   */
  public void setShadowKey(Long tailKey) {
    shadowKey = tailKey;
  }

  /**
   * @return the tailKey
   */
  public Long getShadowKey() {
    return shadowKey;
  }

  public boolean isLastEventInTransaction() {
    return isLastEventInTransaction;
  }

  public TransactionId getTransactionId() {
    return transactionId;
  }

  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof GatewaySenderEventImpl)) {
      return false;
    }

    GatewaySenderEventImpl that = (GatewaySenderEventImpl) obj;

    return shadowKey.equals(that.shadowKey)
        && id.equals(that.id)
        && bucketId == that.bucketId
        && action == that.action
        && regionPath.equals(that.regionPath)
        && key.equals(that.key)
        && Arrays.equals(value, that.value);
  }

  public int hashCode() {
    int hashCode = 17;
    hashCode = 37 * hashCode + ObjectUtils.hashCode(shadowKey);
    hashCode = 37 * hashCode + ObjectUtils.hashCode(id);
    hashCode = 37 * hashCode + bucketId;
    hashCode = 37 * hashCode + action;
    hashCode = 37 * hashCode + ObjectUtils.hashCode(regionPath);
    hashCode = 37 * hashCode + ObjectUtils.hashCode(key);
    hashCode = 37 * hashCode + (value == null ? 0 : Arrays.hashCode(value));
    return hashCode;
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return new KnownVersion[] {KnownVersion.GEODE_1_9_0, KnownVersion.GEODE_1_14_0,
        KnownVersion.GEODE_1_15_0};
  }

  public int getSerializedValueSize() {
    int localSerializedValueSize = serializedValueSize;
    if (localSerializedValueSize != DEFAULT_SERIALIZED_VALUE_SIZE) {
      return localSerializedValueSize;
    }
    @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object vo = valueObj;
    if (vo instanceof StoredObject) {
      localSerializedValueSize = ((StoredObject) vo).getSizeInBytes();
    } else {
      if (substituteValue != null) {
        localSerializedValueSize = sizeOf(substituteValue);
      } else {
        localSerializedValueSize = CachedDeserializableFactory.calcMemSize(getSerializedValue());
      }
    }
    serializedValueSize = localSerializedValueSize;
    return localSerializedValueSize;
  }

  @Override
  @Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  public synchronized void release() {
    @Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object vo = valueObj;
    if (OffHeapHelper.releaseAndTrackOwner(vo, this)) {
      valueObj = null;
      valueObjReleased = true;
    }
  }

  public static void release(
      @Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE) Object o) {
    if (o instanceof GatewaySenderEventImpl) {
      ((GatewaySenderEventImpl) o).release();
    }
  }

  /**
   * Make a heap copy of this off-heap event and return it. A copy only needs to be made if the
   * event's value is stored off-heap. If it is already on the java heap then just return "this". If
   * it was stored off-heap and is no longer available (because it was released) then return null.
   */
  public GatewaySenderEventImpl makeHeapCopyIfOffHeap() {
    if (value != null || substituteValue != null) {
      // we have the value stored on the heap so return this
      return this;
    } else {
      Object v = valueObj;
      if (v == null) {
        if (valueObjReleased) {
          // this means that the original off heap value was freed
          return null;
        } else {
          return this;
        }
      }
      if (v instanceof StoredObject && ((StoredObject) v).hasRefCount()) {
        try {
          return makeCopy();
        } catch (IllegalStateException ex) {
          // this means that the original off heap value was freed
          return null;
        }
      } else {
        // the valueObj does not use refCounts so just return this.
        return this;
      }
    }
  }

  protected GatewaySenderEventImpl makeCopy() {
    return new GatewaySenderEventImpl(this);
  }

  public void setAcked(boolean acked) {
    isAcked = acked;
  }

  public enum TransactionMetadataDisposition {
    /**
     * Transaction metadata should be excluded from the event.
     */
    EXCLUDE,
    /**
     * Transaction metadata should be included in the event.
     */
    INCLUDE,
    /**
     * Transaction metadata should be included in the event and this is the last event in the
     * transaction.
     */
    INCLUDE_LAST_EVENT,
  }
}
