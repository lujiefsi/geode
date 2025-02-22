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
package org.apache.geode.internal.cache.tier.sockets.command;

import static org.apache.geode.util.internal.UncheckedUtils.uncheckedCast;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.apache.geode.CancelCriterion;
import org.apache.geode.cache.operations.RegisterInterestOperationContext;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.tier.CachedRegionHelper;
import org.apache.geode.internal.cache.tier.sockets.AcceptorImpl;
import org.apache.geode.internal.cache.tier.sockets.ChunkedMessage;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.cache.tier.sockets.Part;
import org.apache.geode.internal.cache.tier.sockets.ServerConnection;
import org.apache.geode.internal.security.AuthorizeRequest;
import org.apache.geode.internal.security.SecurityService;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.security.NotAuthorizedException;
import org.apache.geode.security.ResourcePermission.Operation;
import org.apache.geode.security.ResourcePermission.Resource;
import org.apache.geode.test.junit.categories.ClientServerTest;

@Category({ClientServerTest.class})
public class RegisterInterest61Test {

  private static final String REGION_NAME = "region1";
  private static final String KEY = "key1";
  private static final byte[] DURABLE = new byte[8];

  @Mock
  private SecurityService securityService;
  @Mock
  private Message message;
  @Mock
  private ServerConnection serverConnection;
  @Mock
  private AuthorizeRequest authzRequest;
  @Mock
  private InternalCache cache;
  @Mock
  private Part regionNamePart;
  @Mock
  private Part interestTypePart;
  @Mock
  private Part durablePart;
  @Mock
  private Part keyPart;
  @Mock
  private Part notifyPart;
  @Mock
  private RegisterInterestOperationContext registerInterestOperationContext;
  @Mock
  private ChunkedMessage chunkedResponseMessage;

  @InjectMocks
  private RegisterInterest61 registerInterest61;

  @Before
  public void setUp() throws Exception {
    registerInterest61 = new RegisterInterest61();
    MockitoAnnotations.openMocks(this);

    when(authzRequest.registerInterestAuthorize(eq(REGION_NAME), eq(KEY), anyInt(), any()))
        .thenReturn(registerInterestOperationContext);

    when(cache.getRegion(isA(String.class))).thenReturn(uncheckedCast(mock(LocalRegion.class)));
    when(cache.getCancelCriterion()).thenReturn(mock(CancelCriterion.class));

    when(durablePart.getObject()).thenReturn(DURABLE);

    when(interestTypePart.getInt()).thenReturn(0);

    when(keyPart.getStringOrObject()).thenReturn(KEY);

    when(message.getNumberOfParts()).thenReturn(6);
    when(message.getPart(eq(0))).thenReturn(regionNamePart);
    when(message.getPart(eq(1))).thenReturn(interestTypePart);
    when(message.getPart(eq(2))).thenReturn(mock(Part.class));
    when(message.getPart(eq(3))).thenReturn(durablePart);
    when(message.getPart(eq(4))).thenReturn(keyPart);
    when(message.getPart(eq(5))).thenReturn(notifyPart);

    when(notifyPart.getObject()).thenReturn(DURABLE);

    when(regionNamePart.getCachedString()).thenReturn(REGION_NAME);

    when(registerInterestOperationContext.getKey()).thenReturn(KEY);

    when(serverConnection.getCache()).thenReturn(cache);
    when(serverConnection.getAuthzRequest()).thenReturn(authzRequest);
    when(serverConnection.getCachedRegionHelper()).thenReturn(mock(CachedRegionHelper.class));
    when(serverConnection.getChunkedResponseMessage()).thenReturn(chunkedResponseMessage);
    when(serverConnection.getClientVersion()).thenReturn(KnownVersion.GFE_81);
    when(serverConnection.getAcceptor()).thenReturn(mock(AcceptorImpl.class));
  }

  @Test
  public void noSecurityShouldSucceed() throws Exception {
    when(securityService.isClientSecurityRequired()).thenReturn(false);

    registerInterest61.cmdExecute(message, serverConnection, securityService,
        0);

    verify(chunkedResponseMessage).sendChunk(serverConnection);
  }

  @Test
  public void integratedSecurityShouldSucceedIfAuthorized() throws Exception {
    when(securityService.isClientSecurityRequired()).thenReturn(true);
    when(securityService.isIntegratedSecurity()).thenReturn(true);

    registerInterest61.cmdExecute(message, serverConnection, securityService,
        0);

    verify(securityService).authorize(Resource.DATA, Operation.READ, REGION_NAME, KEY);
    verify(chunkedResponseMessage).sendChunk(serverConnection);
  }

  @Test
  public void integratedSecurityShouldThrowIfNotAuthorized() throws Exception {
    when(securityService.isClientSecurityRequired()).thenReturn(true);
    when(securityService.isIntegratedSecurity()).thenReturn(true);
    doThrow(new NotAuthorizedException("")).when(securityService).authorize(Resource.DATA,
        Operation.READ, REGION_NAME, KEY);

    registerInterest61.cmdExecute(message, serverConnection, securityService,
        0);

    verify(securityService).authorize(Resource.DATA, Operation.READ, REGION_NAME, KEY);
    verify(chunkedResponseMessage).sendChunk(serverConnection);
  }

  @Test
  public void oldSecurityShouldSucceedIfAuthorized() throws Exception {
    when(securityService.isClientSecurityRequired()).thenReturn(true);
    when(securityService.isIntegratedSecurity()).thenReturn(false);

    registerInterest61.cmdExecute(message, serverConnection, securityService,
        0);

    verify(authzRequest).registerInterestAuthorize(eq(REGION_NAME), eq(KEY), anyInt(), any());
    verify(chunkedResponseMessage).sendChunk(serverConnection);
  }

  @Test
  public void oldSecurityShouldFailIfNotAuthorized() throws Exception {
    when(securityService.isClientSecurityRequired()).thenReturn(true);
    when(securityService.isIntegratedSecurity()).thenReturn(false);

    doThrow(new NotAuthorizedException("")).when(authzRequest)
        .registerInterestAuthorize(eq(REGION_NAME), eq(KEY), anyInt(), any());

    registerInterest61.cmdExecute(message, serverConnection, securityService,
        0);

    verify(authzRequest).registerInterestAuthorize(eq(REGION_NAME), eq(KEY), anyInt(), any());

    ArgumentCaptor<NotAuthorizedException> argument =
        ArgumentCaptor.forClass(NotAuthorizedException.class);
    verify(chunkedResponseMessage).addObjPart(argument.capture());

    assertThat(argument.getValue()).isExactlyInstanceOf(NotAuthorizedException.class);
    verify(chunkedResponseMessage).sendChunk(serverConnection);
  }

}
