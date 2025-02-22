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
package org.apache.geode.redis.internal.executor.set;

import static java.util.Collections.emptySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.geode.redis.internal.RedisCommandType;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.data.RedisSet;
import org.apache.geode.redis.internal.data.RedisSet.MemberSet;
import org.apache.geode.redis.internal.executor.CommandExecutor;
import org.apache.geode.redis.internal.executor.RedisResponse;
import org.apache.geode.redis.internal.netty.Command;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public abstract class SetOpExecutor implements CommandExecutor {

  @Override
  public RedisResponse executeCommand(Command command, ExecutionHandlerContext context) {
    int setsStartIndex = 1;

    if (isStorage()) {
      setsStartIndex++;
    }

    List<RedisKey> commandElements = command.getProcessedCommandKeys();
    List<RedisKey> setKeys = commandElements.subList(setsStartIndex, commandElements.size());
    if (isStorage()) {
      RedisKey destination = command.getKey();
      int storeCount = doStoreSetOp(command.getCommandType(), context, destination, setKeys);
      return RedisResponse.integer(storeCount);
    } else {
      return doActualSetOperation(context, setKeys);
    }
  }

  private RedisResponse doActualSetOperation(ExecutionHandlerContext context,
      List<RedisKey> setKeys) {
    Set<byte[]> resultSet = null;

    for (RedisKey key : setKeys) {
      Set<byte[]> keySet = context.setLockedExecute(key, true,
          set -> new MemberSet(set.smembers()));
      if (resultSet == null) {
        resultSet = keySet;
      } else if (doSetOp(resultSet, keySet)) {
        break;
      }
    }

    return RedisResponse.array(resultSet, true);
  }


  protected int doStoreSetOp(RedisCommandType setOp, ExecutionHandlerContext context,
      RedisKey destination,
      List<RedisKey> setKeys) {
    List<MemberSet> nonDestinationSets = fetchSets(context, setKeys, destination);
    return context.lockedExecute(destination,
        () -> doStoreSetOpWhileLocked(setOp, context, destination, nonDestinationSets));
  }

  private int doStoreSetOpWhileLocked(RedisCommandType setOp, ExecutionHandlerContext context,
      RedisKey destination,
      List<MemberSet> nonDestinationSets) {
    Set<byte[]> result =
        computeStoreSetOp(setOp, nonDestinationSets, context, destination);
    if (result.isEmpty()) {
      context.getRegion().remove(destination);
      return 0;
    } else {
      context.getRegion().put(destination, new RedisSet(result));
      return result.size();
    }
  }

  private Set<byte[]> computeStoreSetOp(RedisCommandType setOp, List<MemberSet> nonDestinationSets,
      ExecutionHandlerContext context, RedisKey destination) {
    MemberSet result = null;
    if (nonDestinationSets.isEmpty()) {
      return emptySet();
    }
    for (MemberSet set : nonDestinationSets) {
      if (set == null) {
        RedisSet redisSet = context.getRedisSet(destination, false);
        set = new MemberSet(redisSet.smembers());
      }
      if (result == null) {
        result = set;
      } else {
        switch (setOp) {
          case SUNIONSTORE:
            result.addAll(set);
            break;
          case SINTERSTORE:
            result.retainAll(set);
            break;
          case SDIFFSTORE:
            result.removeAll(set);
            break;
          default:
            throw new IllegalStateException(
                "expected a set store command but found: " + setOp);
        }
      }
    }
    return result;
  }

  /**
   * Gets the set data for the given keys, excluding the destination if it was in setKeys.
   * The result will have an element for each corresponding key and a null element if
   * the corresponding key is the destination.
   * This is all done outside the striped executor to prevent a deadlock.
   */
  private List<MemberSet> fetchSets(ExecutionHandlerContext context, List<RedisKey> setKeys,
      RedisKey destination) {
    List<MemberSet> result = new ArrayList<>(setKeys.size());
    for (RedisKey key : setKeys) {
      if (key.equals(destination)) {
        result.add(null);
      } else {
        result.add(context.setLockedExecute(key, false,
            set -> new MemberSet(set.smembers())));
      }
    }
    return result;
  }

  /**
   * @return true if no further calls of doSetOp are needed
   */
  protected abstract boolean doSetOp(Set<byte[]> resultSet, Set<byte[]> nextSet);

  protected abstract boolean isStorage();

}
