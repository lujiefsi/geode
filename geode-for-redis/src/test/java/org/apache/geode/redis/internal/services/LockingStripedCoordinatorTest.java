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

package org.apache.geode.redis.internal.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.apache.geode.redis.internal.data.RedisKey;

public class LockingStripedCoordinatorTest {

  @Test
  public void handleMinNegativeHashCode() {
    Hashy hashy = new Hashy(Integer.MIN_VALUE);

    StripedCoordinator executor = new LockingStripedCoordinator();
    String result = executor.execute(hashy, () -> "OK");
    assertThat(result).isEqualTo("OK");
  }

  @Test
  public void handleMaxPositiveHashCode() {
    Hashy hashy = new Hashy(Integer.MAX_VALUE);

    StripedCoordinator executor = new LockingStripedCoordinator();
    String result = executor.execute(hashy, () -> "OK");
    assertThat(result).isEqualTo("OK");
  }

  @Test
  public void handleStripeCollisionsForSameExecution() {
    List<RedisKey> keys = new ArrayList<>();
    keys.add(new Hashy(1));
    keys.add(new Hashy(2));
    keys.add(new Hashy(3));

    StripedCoordinator coordinator = new LockingStripedCoordinator(1);

    assertThat(coordinator.execute(keys, () -> "OK"))
        .isEqualTo("OK");
  }

  private static class Hashy extends RedisKey {
    private final int hashcode;

    public Hashy(int hashcode) {
      this.hashcode = hashcode;
    }

    @Override
    public int hashCode() {
      return hashcode;
    }
  }
}
