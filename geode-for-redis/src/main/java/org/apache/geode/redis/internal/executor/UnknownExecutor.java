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
 *
 */
package org.apache.geode.redis.internal.executor;

import static org.apache.geode.redis.internal.RedisConstants.ERROR_UNKNOWN_COMMAND;
import static org.apache.geode.redis.internal.netty.Coder.bytesToString;

import java.util.List;

import org.apache.geode.redis.internal.netty.Command;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public class UnknownExecutor implements CommandExecutor {

  @Override
  public RedisResponse executeCommand(Command command,
      ExecutionHandlerContext context) {

    StringBuilder commandArguments = new StringBuilder();
    String commandText = null;
    List<byte[]> commandElems = command.getProcessedCommand();

    if (commandElems != null && !commandElems.isEmpty()) {
      commandText = bytesToString(commandElems.get(0));

      if (commandElems.size() > 1) {
        for (int i = 1; i < commandElems.size(); i++) {
          if (commandElems.get(i) == null) {
            continue;
          }
          commandArguments.append("`").append(bytesToString(commandElems.get(i)))
              .append("`, ");
        }
      }
    }

    return RedisResponse.error(String.format(ERROR_UNKNOWN_COMMAND, commandText, commandArguments));
  }
}
