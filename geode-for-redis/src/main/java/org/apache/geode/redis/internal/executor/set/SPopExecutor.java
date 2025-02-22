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


import static org.apache.geode.redis.internal.RedisConstants.ERROR_NOT_INTEGER;
import static org.apache.geode.redis.internal.netty.Coder.bytesToLong;
import static org.apache.geode.redis.internal.netty.Coder.narrowLongToInt;

import java.util.Collection;
import java.util.List;

import org.apache.geode.cache.Region;
import org.apache.geode.redis.internal.data.RedisData;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.executor.CommandExecutor;
import org.apache.geode.redis.internal.executor.RedisResponse;
import org.apache.geode.redis.internal.netty.Command;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public class SPopExecutor implements CommandExecutor {

  @Override
  public RedisResponse executeCommand(Command command, ExecutionHandlerContext context) {
    List<byte[]> commandElems = command.getProcessedCommand();
    boolean isCountPassed = false;
    int popCount;

    if (commandElems.size() == 3) {
      isCountPassed = true;
      try {
        popCount = narrowLongToInt(bytesToLong(commandElems.get(2)));
      } catch (NumberFormatException nex) {
        return RedisResponse.error(ERROR_NOT_INTEGER);
      }
    } else {
      popCount = 1;
    }

    Region<RedisKey, RedisData> region = context.getRegion();
    RedisKey key = command.getKey();
    Collection<byte[]> popped = context.setLockedExecute(key, false,
        set -> set.spop(region, key, popCount));

    if (popped.isEmpty() && !isCountPassed) {
      return RedisResponse.nil();
    }

    if (!isCountPassed) {
      return RedisResponse.bulkString(popped.iterator().next());
    }

    return RedisResponse.array(popped, true);
  }
}
