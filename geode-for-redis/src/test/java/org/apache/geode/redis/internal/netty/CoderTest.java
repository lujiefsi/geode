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
package org.apache.geode.redis.internal.netty;

import static org.apache.geode.redis.internal.netty.Coder.bytesToDouble;
import static org.apache.geode.redis.internal.netty.Coder.bytesToString;
import static org.apache.geode.redis.internal.netty.Coder.doubleToBytes;
import static org.apache.geode.redis.internal.netty.Coder.equalsIgnoreCaseBytes;
import static org.apache.geode.redis.internal.netty.Coder.isInfinity;
import static org.apache.geode.redis.internal.netty.Coder.isNaN;
import static org.apache.geode.redis.internal.netty.Coder.isNegativeInfinity;
import static org.apache.geode.redis.internal.netty.Coder.isPositiveInfinity;
import static org.apache.geode.redis.internal.netty.Coder.narrowLongToInt;
import static org.apache.geode.redis.internal.netty.Coder.stringToBytes;
import static org.apache.geode.redis.internal.netty.Coder.toUpperCaseBytes;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.bNaN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertArrayEquals;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.geode.test.junit.runners.GeodeParamsRunner;

@RunWith(GeodeParamsRunner.class)
public class CoderTest {
  @Test
  @Parameters(method = "stringPairs")
  public void equalsIgnoreCaseBytes_matchesStringEqualsIgnoreCase(String string1, String string2) {
    byte[] string1Bytes = stringToBytes(string1);
    byte[] string2Bytes = stringToBytes(string2);
    assertThat(equalsIgnoreCaseBytes(string1Bytes, string2Bytes))
        .as("Comparing equality of " + string1 + " and " + string2)
        .isEqualTo(string1.equalsIgnoreCase(string2));
  }

  @Test
  @Parameters({"abc", "AbC", "ABC", "%abc", "123abc!@#", "+inf", "-INF"})
  public void toUpperCaseBytes_matchesStringToUpperCase(String string) {
    byte[] uppercase = toUpperCaseBytes(stringToBytes(string));
    assertThat(uppercase)
        .withFailMessage("Comparing toUpperCase for " + string
            + ".\nExpected: " + bytesToString(uppercase)
            + "\nActual: " + string.toUpperCase())
        .isEqualTo(stringToBytes(string.toUpperCase()));
  }

  @Test
  @Parameters(method = "infinityStrings")
  public void isInfinity_returnsCorrectly(String string, boolean isPositiveInfinity,
      boolean isNegativeInfinity, boolean isNaN) {
    byte[] bytes = stringToBytes(string);
    assertThat(isInfinity(bytes)).isEqualTo(isPositiveInfinity || isNegativeInfinity);
    assertThat(isPositiveInfinity(bytes)).isEqualTo(isPositiveInfinity);
    assertThat(isNegativeInfinity(bytes)).isEqualTo(isNegativeInfinity);
    assertThat(isNaN(bytes)).isEqualTo(isNaN);
  }

  @Test
  @Parameters(method = "infinityReturnStrings")
  public void doubleToBytes_processesLikeRedis(String inputString, String expectedString) {
    byte[] bytes = stringToBytes(inputString);
    double d = bytesToDouble(bytes);
    byte[] convertedBytes = doubleToBytes(d);
    String convertedString = bytesToString(convertedBytes);
    assertThat(convertedString).isEqualTo(expectedString);
  }

  @Test
  public void bytesToDouble_errorsWhenGivenNaNAsBytes() {
    assertThatThrownBy(() -> Coder.bytesToDouble(bNaN)).isInstanceOf(NumberFormatException.class);
  }

  @Test
  public void narrowLongToInt_correctlyConvertsLongToIntMinValue() {
    for (long i = Integer.MIN_VALUE; i > Integer.MIN_VALUE - 10L; --i) {
      assertThat(narrowLongToInt(i)).isEqualTo(Integer.MIN_VALUE);
    }
    assertThat(narrowLongToInt(Long.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
  }

  @Test
  public void narrowLongToInt_correctlyConvertsLongToIntMaxValue() {
    for (long i = Integer.MAX_VALUE; i < Integer.MAX_VALUE + 10L; ++i) {
      assertThat(narrowLongToInt(i)).isEqualTo(Integer.MAX_VALUE);
    }
    assertThat(narrowLongToInt(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  public void narrowLongToInt_correctlyConvertsLongToInt() {
    for (long i = -10; i < 10; ++i) {
      assertThat(narrowLongToInt(i)).isEqualTo(Math.toIntExact(i));
    }
  }

  @Test
  @Parameters(method = "doubleBytes")
  public void doubleToBytes_processesTrailingZeroLikeRedis(Double inputDouble,
      byte[] expectedBytes) {
    double d = inputDouble;
    byte[] convertedBytes = doubleToBytes(d);
    assertArrayEquals(convertedBytes, expectedBytes);
  }

  @SuppressWarnings("unused")
  private Object[] stringPairs() {
    // string1, string2
    return new Object[] {
        new Object[] {"abc", "abc"},
        new Object[] {"AbC", "abc"},
        new Object[] {"ABC", "ABC"},
        new Object[] {"ABC", "abc"},
        new Object[] {"abc", "acb"},
        new Object[] {"abc", "xyz"},
        new Object[] {"abc", "abcd"},
        new Object[] {"%abc", "%abc"},
        new Object[] {"%abc", "abc%"},
        new Object[] {"+inf", "+INF"},
        new Object[] {"-INF", "+INF"},
        new Object[] {"123abc!@#", "123ABC!@#"},
        new Object[] {"123abc!@#", "#@!cba321"},
        new Object[] {"abc", null}
    };
  }

  @SuppressWarnings("unused")
  private Object[] infinityStrings() {
    // string, isPositiveInfinity, isNegativeInfinity, isNaN
    return new Object[] {
        new Object[] {"inf", true, false, false},
        new Object[] {"+inf", true, false, false},
        new Object[] {"Infinity", true, false, false},
        new Object[] {"+Infinity", true, false, false},
        new Object[] {"-inf", false, true, false},
        new Object[] {"-Infinity", false, true, false},
        new Object[] {"infinite", false, false, false},
        new Object[] {"+infinityty", false, false, false},
        new Object[] {"+-inf", false, false, false},
        new Object[] {"notEvenClose", false, false, false},
        new Object[] {"NaN", false, false, true},
        new Object[] {null, false, false, false}
    };
  }

  @SuppressWarnings("unused")
  private Object[] doubleBytes() {
    // input double, double bytes with stripped trailing zero
    return new Object[] {
        new Object[] {0.0, stringToBytes("0")},
        new Object[] {0.01, stringToBytes("0.01")},
        new Object[] {0d, stringToBytes("0")},
        new Object[] {1.0, stringToBytes("1")},
        new Object[] {-1.0, stringToBytes("-1")},
        new Object[] {6.0221409E23, stringToBytes("6.0221409E23")},
        new Object[] {6.62607E-34, stringToBytes("6.62607E-34")},
        new Object[] {Double.POSITIVE_INFINITY, stringToBytes("inf")},
        new Object[] {Double.NaN, stringToBytes("NaN")},
    };
  }

  @SuppressWarnings("unused")
  private Object[] infinityReturnStrings() {
    // string, expectedString
    return new Object[] {
        new Object[] {"inf", "inf"},
        new Object[] {"+inf", "inf"},
        new Object[] {"Infinity", "inf"},
        new Object[] {"+Infinity", "inf"},
        new Object[] {"-inf", "-inf"},
        new Object[] {"-Infinity", "-inf"},
    };
  }

  @Test
  public void verify_appendAsciiDigitsToByteBuf() {
    for (long i = Long.MAX_VALUE; i > Long.MAX_VALUE - 1000; i--) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Long.MIN_VALUE; i < Long.MIN_VALUE + 1000; i++) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Integer.MAX_VALUE + 1000; i > Integer.MAX_VALUE - 1000; i--) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Integer.MIN_VALUE + 1000; i > Integer.MIN_VALUE - 1000; i--) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Short.MAX_VALUE + 1000; i > Short.MAX_VALUE - 1000; i--) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Short.MIN_VALUE + 1000; i > Short.MIN_VALUE - 1000; i--) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
    for (long i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
      verify_appendAsciiDigitsToByteBuf(i);
    }
  }

  private void verify_appendAsciiDigitsToByteBuf(long value) {
    String expected = Long.toString(value);
    ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();

    Coder.appendAsciiDigitsToByteBuf(value, buf);

    assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo(expected);
  }

  @Test
  public void verify_longToBytes_bytesToLong_consistency() {
    for (long i = Long.MAX_VALUE; i > Long.MAX_VALUE - 1000; i--) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Long.MIN_VALUE; i < Long.MIN_VALUE + 1000; i++) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Integer.MAX_VALUE + 1000; i > Integer.MAX_VALUE - 1000; i--) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Integer.MIN_VALUE + 1000; i > Integer.MIN_VALUE - 1000; i--) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Short.MAX_VALUE + 1000; i > Short.MAX_VALUE - 1000; i--) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Short.MIN_VALUE + 1000; i > Short.MIN_VALUE - 1000; i--) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
    for (long i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
      verify_longToBytes_bytesToLong_consistency(i);
    }
  }

  private void verify_longToBytes_bytesToLong_consistency(long l) {
    byte[] lBytes = Coder.longToBytes(l);
    long l2 = Coder.bytesToLong(lBytes);
    assertThat(l2).isEqualTo(l);
  }

  @Test
  public void bytesToLong_fails_if_MINUS_ZERO_start() {
    assertThatThrownBy(() -> Coder.bytesToLong(Coder.stringToBytes("-01234")))
        .isInstanceOf(NumberFormatException.class);
    assertThatThrownBy(() -> Coder.bytesToLong(Coder.stringToBytes("-0")))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  public void bytesToLong_fails_if_PLUS_start() {
    assertThatThrownBy(() -> Coder.bytesToLong(Coder.stringToBytes("+")))
        .isInstanceOf(NumberFormatException.class);
    assertThatThrownBy(() -> Coder.bytesToLong(Coder.stringToBytes("+0")))
        .isInstanceOf(NumberFormatException.class);
    assertThatThrownBy(() -> Coder.bytesToLong(Coder.stringToBytes("+1")))
        .isInstanceOf(NumberFormatException.class);
  }

}
