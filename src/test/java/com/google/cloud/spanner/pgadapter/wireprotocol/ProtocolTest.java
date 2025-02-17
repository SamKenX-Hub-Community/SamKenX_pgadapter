// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.spanner.pgadapter.wireprotocol;

import static com.google.cloud.spanner.pgadapter.wireprotocol.ControlMessage.MAX_INVALID_MESSAGE_COUNT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.AbstractStatementParser;
import com.google.cloud.spanner.connection.AbstractStatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.AbstractStatementParser.StatementType;
import com.google.cloud.spanner.connection.Connection;
import com.google.cloud.spanner.pgadapter.ConnectionHandler;
import com.google.cloud.spanner.pgadapter.ConnectionHandler.ConnectionStatus;
import com.google.cloud.spanner.pgadapter.ConnectionHandler.QueryMode;
import com.google.cloud.spanner.pgadapter.ProxyServer;
import com.google.cloud.spanner.pgadapter.error.PGException;
import com.google.cloud.spanner.pgadapter.error.PGExceptionFactory;
import com.google.cloud.spanner.pgadapter.error.SQLState;
import com.google.cloud.spanner.pgadapter.metadata.ConnectionMetadata;
import com.google.cloud.spanner.pgadapter.metadata.OptionsMetadata;
import com.google.cloud.spanner.pgadapter.metadata.OptionsMetadata.SslMode;
import com.google.cloud.spanner.pgadapter.session.PGSetting;
import com.google.cloud.spanner.pgadapter.session.SessionState;
import com.google.cloud.spanner.pgadapter.statements.BackendConnection;
import com.google.cloud.spanner.pgadapter.statements.BackendConnection.ConnectionState;
import com.google.cloud.spanner.pgadapter.statements.BackendConnection.UpdateCount;
import com.google.cloud.spanner.pgadapter.statements.CopyStatement;
import com.google.cloud.spanner.pgadapter.statements.ExtendedQueryProtocolHandler;
import com.google.cloud.spanner.pgadapter.statements.IntermediatePortalStatement;
import com.google.cloud.spanner.pgadapter.statements.IntermediatePreparedStatement;
import com.google.cloud.spanner.pgadapter.utils.ClientAutoDetector.WellKnownClient;
import com.google.cloud.spanner.pgadapter.utils.MutationWriter;
import com.google.cloud.spanner.pgadapter.wireprotocol.ControlMessage.ManuallyCreatedToken;
import com.google.cloud.spanner.pgadapter.wireprotocol.ControlMessage.PreparedType;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.SettableFuture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.simple.parser.JSONParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.postgresql.util.ByteConverter;

@RunWith(JUnit4.class)
public class ProtocolTest {
  private static final AbstractStatementParser PARSER =
      AbstractStatementParser.getInstance(Dialect.POSTGRESQL);

  @Rule public MockitoRule rule = MockitoJUnit.rule();
  @Mock private ConnectionHandler connectionHandler;
  @Mock private Connection connection;
  @Mock private ExtendedQueryProtocolHandler extendedQueryProtocolHandler;
  @Mock private BackendConnection backendConnection;
  @Mock private ProxyServer server;
  @Mock private OptionsMetadata options;
  @Mock private IntermediatePreparedStatement intermediatePreparedStatement;
  @Mock private IntermediatePortalStatement intermediatePortalStatement;
  @Mock private ConnectionMetadata connectionMetadata;
  @Mock private DataOutputStream outputStream;
  @Mock private ResultSet resultSet;

  private byte[] intToBytes(int value) {
    byte[] parameters = new byte[4];
    ByteConverter.int4(parameters, 0, value);
    return parameters;
  }

  private DataInputStream inputStreamFromOutputStream(ByteArrayOutputStream output) {
    return new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
  }

  private String readUntil(DataInputStream input, int length) throws IOException {
    byte[] item = new byte[length];
    int readLength = input.read(item, 0, length);
    if (readLength != length) {
      throw new IOException(String.format("Only got %d bytes, expected %d", readLength, length));
    }
    return new String(item, StandardCharsets.UTF_8);
  }

  private void readUntilNullTerminator(DataInputStream input) throws Exception {
    byte c;
    do {
      c = input.readByte();
    } while (c != '\0');
  }

  private static ParsedStatement parse(String sql) {
    return PARSER.parse(Statement.of(sql));
  }

  @Test
  public void testQueryMessage() throws Exception {
    byte[] messageMetadata = {'Q', 0, 0, 0, 24};
    String payload = "SELECT * FROM users\0";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    DataOutputStream outputStream = new DataOutputStream(new ByteArrayOutputStream());

    String expectedSQL = "SELECT * FROM users";

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(options.requiresMatcher()).thenReturn(false);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);
    when(backendConnection.getConnectionState()).thenReturn(ConnectionState.IDLE);
    when(connectionHandler.getStatement("")).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getPortal("")).thenReturn(intermediatePortalStatement);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(QueryMessage.class, message.getClass());
    assertEquals(expectedSQL, ((QueryMessage) message).getStatement().getSql());

    QueryMessage messageSpy = (QueryMessage) spy(message);

    messageSpy.send();
  }

  @Test
  public void testQueryUsesPSQLStatementWhenPSQLModeSelectedMessage() throws Exception {
    JSONParser parser = new JSONParser();
    byte[] messageMetadata = {'Q', 0, 0, 0, 24};
    String payload = "SELECT * FROM users\0";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    String expectedSQL = "SELECT * FROM users";

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(QueryMessage.class, message.getClass());
    assertNotNull(((QueryMessage) message).getSimpleQueryStatement());
    assertEquals(expectedSQL, ((QueryMessage) message).getStatement().getSql());
  }

  @Test
  public void testQueryMessageFailsWhenNotNullTerminated() {
    byte[] messageMetadata = {'Q', 0, 0, 0, 23};
    String payload = "SELECT * FROM users";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    assertThrows(IOException.class, () -> ControlMessage.create(connectionHandler));
  }

  @Test
  public void testParseMessageException() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload = "SELECT * FROM users WHERE name = $1\0";

    byte[] parameterCount = {0, 1};
    byte[] parameters = intToBytes(1002);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    int[] expectedParameterDataTypes = new int[] {1002};
    String expectedSQL = "SELECT * FROM users WHERE name = $1";
    String expectedMessageName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ParseMessage.class, message.getClass());
    assertEquals(expectedMessageName, ((ParseMessage) message).getName());
    assertEquals(expectedSQL, ((ParseMessage) message).getStatement().getSql());
    assertArrayEquals(
        expectedParameterDataTypes,
        ((ParseMessage) message).getStatement().getGivenParameterDataTypes());

    when(connectionHandler.hasStatement(anyString())).thenReturn(false);
    message.send();
    ((ParseMessage) message).flush();
    verify(connectionHandler)
        .registerStatement(expectedMessageName, ((ParseMessage) message).getStatement());

    // ParseCompleteResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('1', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testParseMessage() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] parameterCount = {0, 1};
    byte[] parameters = intToBytes(1002);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    int[] expectedParameterDataTypes = new int[] {1002};
    String expectedSQL = "SELECT * FROM users WHERE name = $1";
    String expectedMessageName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ParseMessage.class, message.getClass());
    assertEquals(expectedMessageName, ((ParseMessage) message).getName());
    assertEquals(expectedSQL, ((ParseMessage) message).getStatement().getSql());
    assertArrayEquals(
        expectedParameterDataTypes,
        ((ParseMessage) message).getStatement().getGivenParameterDataTypes());

    when(connectionHandler.hasStatement(anyString())).thenReturn(false);
    message.send();
    ((ParseMessage) message).flush();
    verify(connectionHandler)
        .registerStatement(expectedMessageName, ((ParseMessage) message).getStatement());

    // ParseCompleteResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('1', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testParseMessageAcceptsUntypedParameter() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] parameterCount = {0, 1};
    // Unspecifed parameter type.
    byte[] parameters = intToBytes(0);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    int[] expectedParameterDataTypes = new int[] {0};
    String expectedSQL = "SELECT * FROM users WHERE name = $1";
    String expectedMessageName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ParseMessage.class, message.getClass());
    assertEquals(expectedMessageName, ((ParseMessage) message).getName());
    assertEquals(expectedSQL, ((ParseMessage) message).getStatement().getSql());
    assertArrayEquals(
        expectedParameterDataTypes,
        ((ParseMessage) message).getStatement().getGivenParameterDataTypes());

    when(connectionHandler.hasStatement(anyString())).thenReturn(false);
    message.send();
    ((ParseMessage) message).flush();
    verify(connectionHandler)
        .registerStatement(expectedMessageName, ((ParseMessage) message).getStatement());

    // ParseCompleteResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('1', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testParseMessageWithNonMatchingParameterTypeCount() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] length = intToBytes(4 + statementName.length() + payload.length() + 1);

    byte[] value =
        Bytes.concat(
            messageMetadata, length, statementName.getBytes(), payload.getBytes(), intToBytes(0));

    int[] expectedParameterDataTypes = new int[0];
    String expectedSQL = "SELECT * FROM users WHERE name = $1";
    String expectedMessageName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ParseMessage.class, message.getClass());
    assertEquals(expectedMessageName, ((ParseMessage) message).getName());
    assertEquals(expectedSQL, ((ParseMessage) message).getStatement().getSql());
    assertArrayEquals(
        expectedParameterDataTypes,
        ((ParseMessage) message).getStatement().getGivenParameterDataTypes());

    when(connectionHandler.hasStatement(anyString())).thenReturn(false);
    message.send();
    ((ParseMessage) message).flush();
    verify(connectionHandler)
        .registerStatement(expectedMessageName, ((ParseMessage) message).getStatement());

    // ParseCompleteResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('1', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testParseMessageExceptsIfNameIsInUse() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] parameterCount = {0, 1};
    byte[] parameters = intToBytes(1002);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);

    when(connectionHandler.hasStatement(anyString())).thenReturn(true);
    assertThrows(IllegalStateException.class, message::send);
  }

  @Test
  public void testParseMessageExceptsIfNameIsNull() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "some statement\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] parameterCount = {0, 1};
    byte[] parameters = intToBytes(1002);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    when(connectionHandler.hasStatement(anyString())).thenReturn(true);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);

    when(connectionHandler.hasStatement(anyString())).thenReturn(true);
    assertThrows(IllegalStateException.class, message::send);
  }

  @Test
  public void testParseMessageWorksIfNameIsEmpty() throws Exception {
    byte[] messageMetadata = {'P'};
    String statementName = "\0";
    String payload =
        "SELECT * FROM users WHERE name = $1 /*This is a comment*/ --this is another comment\0";

    byte[] parameterCount = {0, 1};
    byte[] parameters = intToBytes(1002);

    byte[] length =
        intToBytes(
            4
                + statementName.length()
                + payload.length()
                + parameterCount.length
                + parameters.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            statementName.getBytes(),
            payload.getBytes(),
            parameterCount,
            parameters);

    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);

    message.send();
  }

  @Test
  public void testBindMessage() throws Exception {
    byte[] messageMetadata = {'B'};
    String portalName = "some portal\0";
    String statementName = "some statement\0";

    byte[] parameterCodesCount = {0, 0}; // Denotes no codes

    byte[] parameterCount = {0, 1};
    byte[] parameter = "someUser\0".getBytes();
    byte[] parameterLength = intToBytes(parameter.length);

    byte[] resultCodesCount = {0, 0};

    byte[] length =
        intToBytes(
            4
                + portalName.length()
                + statementName.length()
                + parameterCodesCount.length
                + parameterCount.length
                + parameterLength.length
                + parameter.length
                + resultCodesCount.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            portalName.getBytes(),
            statementName.getBytes(),
            parameterCodesCount,
            parameterCount,
            parameterLength,
            parameter,
            resultCodesCount);

    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePreparedStatement);
    when(intermediatePreparedStatement.createPortal(anyString(), any(), any(), any()))
        .thenReturn(intermediatePortalStatement);
    when(intermediatePortalStatement.getSql()).thenReturn("select * from foo");
    when(intermediatePortalStatement.getPreparedStatement())
        .thenReturn(intermediatePreparedStatement);

    byte[][] expectedParameters = {parameter};
    List<Short> expectedFormatCodes = new ArrayList<>();
    String expectedPortalName = "some portal";
    String expectedStatementName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(BindMessage.class, message.getClass());
    assertEquals(expectedPortalName, ((BindMessage) message).getPortalName());
    assertEquals(expectedStatementName, ((BindMessage) message).getStatementName());
    assertArrayEquals(expectedParameters, ((BindMessage) message).getParameters());
    assertEquals(expectedFormatCodes, ((BindMessage) message).getFormatCodes());
    assertEquals(expectedFormatCodes, ((BindMessage) message).getResultFormatCodes());
    assertEquals("select * from foo", ((BindMessage) message).getSql());
    assertTrue(((BindMessage) message).hasParameterValues());

    message.send();
    ((BindMessage) message).flush();
    verify(connectionHandler).registerPortal(expectedPortalName, intermediatePortalStatement);

    // BindCompleteResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('2', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testBindMessageOneNonTextParam() throws Exception {
    byte[] messageMetadata = {'B'};
    String portalName = "some portal\0";
    String statementName = "some statement\0";

    byte[] parameterCodesCount = {0, 2};
    byte[] parameterCodes = {0, 0, 0, 1}; // First is text, second binary

    byte[] parameterCount = {0, 2};
    byte[] firstParameter = "someUser\0".getBytes();
    byte[] firstParameterLength = intToBytes(firstParameter.length);
    byte[] secondParameter = {0, 1, 0, 1};
    byte[] secondParameterLength = intToBytes(secondParameter.length);

    byte[] resultCodesCount = {0, 1};
    byte[] resultCodes = {0, 1}; // binary

    byte[] length =
        intToBytes(
            4
                + portalName.length()
                + statementName.length()
                + parameterCodesCount.length
                + parameterCodes.length
                + parameterCount.length
                + firstParameterLength.length
                + firstParameter.length
                + secondParameterLength.length
                + secondParameter.length
                + resultCodesCount.length
                + resultCodes.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            portalName.getBytes(),
            statementName.getBytes(),
            parameterCodesCount,
            parameterCodes,
            parameterCount,
            firstParameterLength,
            firstParameter,
            secondParameterLength,
            secondParameter,
            resultCodesCount,
            resultCodes);

    byte[][] expectedParameters = {firstParameter, secondParameter};
    List<Short> expectedFormatCodes = Arrays.asList((short) 0, (short) 1);
    List<Short> expectedResultFormatCodes = Collections.singletonList((short) 1);
    String expectedPortalName = "some portal";
    String expectedStatementName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePreparedStatement);
    when(intermediatePreparedStatement.createPortal(anyString(), any(), any(), any()))
        .thenReturn(intermediatePortalStatement);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(BindMessage.class, message.getClass());
    assertEquals(expectedPortalName, ((BindMessage) message).getPortalName());
    assertEquals(expectedStatementName, ((BindMessage) message).getStatementName());
    assertArrayEquals(expectedParameters, ((BindMessage) message).getParameters());
    assertEquals(expectedFormatCodes, ((BindMessage) message).getFormatCodes());
    assertEquals(expectedResultFormatCodes, ((BindMessage) message).getResultFormatCodes());
  }

  @Test
  public void testBindMessageAllNonTextParam() throws Exception {
    byte[] messageMetadata = {'B'};
    String portalName = "some portal\0";
    String statementName = "some statement\0";

    byte[] parameterCodesCount = {0, 1};
    byte[] parameterCodes = {0, 1}; // binary

    byte[] parameterCount = {0, 2};
    byte[] firstParameter = "someUser\0".getBytes();
    byte[] firstParameterLength = intToBytes(firstParameter.length);
    byte[] secondParameter = {0, 1, 0, 1};
    byte[] secondParameterLength = intToBytes(secondParameter.length);

    byte[] resultCodesCount = {0, 1};
    byte[] resultCodes = {0, 1}; // binary

    byte[] length =
        intToBytes(
            4
                + portalName.length()
                + statementName.length()
                + parameterCodesCount.length
                + parameterCodes.length
                + parameterCount.length
                + firstParameterLength.length
                + firstParameter.length
                + secondParameterLength.length
                + secondParameter.length
                + resultCodesCount.length
                + resultCodes.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            portalName.getBytes(),
            statementName.getBytes(),
            parameterCodesCount,
            parameterCodes,
            parameterCount,
            firstParameterLength,
            firstParameter,
            secondParameterLength,
            secondParameter,
            resultCodesCount,
            resultCodes);

    byte[][] expectedParameters = {firstParameter, secondParameter};
    List<Short> expectedFormatCodes = Collections.singletonList((short) 1);
    List<Short> expectedResultFormatCodes = Collections.singletonList((short) 1);
    String expectedPortalName = "some portal";
    String expectedStatementName = "some statement";

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePreparedStatement);
    when(intermediatePreparedStatement.createPortal(anyString(), any(), any(), any()))
        .thenReturn(intermediatePortalStatement);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(BindMessage.class, message.getClass());
    assertEquals(expectedPortalName, ((BindMessage) message).getPortalName());
    assertEquals(expectedStatementName, ((BindMessage) message).getStatementName());
    assertArrayEquals(expectedParameters, ((BindMessage) message).getParameters());
    assertEquals(expectedFormatCodes, ((BindMessage) message).getFormatCodes());
    assertEquals(expectedResultFormatCodes, ((BindMessage) message).getResultFormatCodes());
  }

  @Test
  public void testDescribePortalMessage() throws Exception {
    byte[] messageMetadata = {'D'};
    byte[] statementType = {'P'};
    String statementName = "some statement\0";

    byte[] length = intToBytes(4 + 1 + statementName.length());

    byte[] value = Bytes.concat(messageMetadata, length, statementType, statementName.getBytes());

    String expectedStatementName = "some statement";
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getPortal(anyString())).thenReturn(intermediatePortalStatement);
    when(intermediatePortalStatement.getSql()).thenReturn("select * from foo");
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(DescribeMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((DescribeMessage) message).getName());
    assertEquals("select * from foo", ((DescribeMessage) message).getSql());

    verify(connectionHandler).getPortal("some statement");

    DescribeMessage messageSpy = (DescribeMessage) spy(message);
    doNothing().when(messageSpy).handleDescribePortal();

    messageSpy.send();
    messageSpy.flush();

    verify(messageSpy).handleDescribePortal();
  }

  @Test
  public void testDescribeStatementMessage() throws Exception {
    byte[] messageMetadata = {'D'};
    byte[] statementType = {'S'};
    String statementName = "some statement\0";

    byte[] length = intToBytes(4 + 1 + statementName.length());

    byte[] value = Bytes.concat(messageMetadata, length, statementType, statementName.getBytes());

    String expectedStatementName = "some statement";
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePreparedStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(DescribeMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((DescribeMessage) message).getName());

    verify(connectionHandler).getStatement("some statement");

    DescribeMessage messageSpy = (DescribeMessage) spy(message);
    doNothing().when(messageSpy).handleDescribeStatement();

    messageSpy.send();
    messageSpy.flush();

    verify(messageSpy).handleDescribeStatement();
  }

  @Test
  public void testDescribeMessageWithException() throws Exception {
    byte[] messageMetadata = {'D'};
    byte[] statementType = {'S'};
    String statementName = "some statement\0";

    byte[] length = intToBytes(4 + 1 + statementName.length());

    byte[] value = Bytes.concat(messageMetadata, length, statementType, statementName.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePreparedStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(intermediatePreparedStatement.hasException()).thenReturn(true);
    when(intermediatePreparedStatement.getException())
        .thenReturn(PGExceptionFactory.newPGException("test error", SQLState.InternalError));

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(DescribeMessage.class, message.getClass());
    DescribeMessage describeMessage = (DescribeMessage) message;

    PGException exception =
        assertThrows(PGException.class, describeMessage::handleDescribeStatement);
    assertEquals("test error", exception.getMessage());
  }

  @Test
  public void testExecuteMessage() throws Exception {
    byte[] messageMetadata = {'E'};
    String statementName = "some portal\0";
    int totalRows = 99999;

    byte[] length = intToBytes(4 + statementName.length() + 4);

    byte[] value =
        Bytes.concat(messageMetadata, length, statementName.getBytes(), intToBytes(totalRows));

    String expectedStatementName = "some portal";
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getPortal(anyString())).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ExecuteMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((ExecuteMessage) message).getName());
    assertEquals(totalRows, ((ExecuteMessage) message).getMaxRows());

    verify(connectionHandler).getPortal("some portal");
    ExecuteMessage messageSpy = (ExecuteMessage) spy(message);

    doNothing()
        .when(messageSpy)
        .sendSpannerResult(any(IntermediatePortalStatement.class), any(QueryMode.class), anyLong());

    messageSpy.send();
    messageSpy.flush();

    verify(intermediatePortalStatement).executeAsync(backendConnection);
    verify(messageSpy)
        .sendSpannerResult(intermediatePortalStatement, QueryMode.EXTENDED, totalRows);
    verify(connectionHandler).cleanUp(intermediatePortalStatement);
  }

  @Test
  public void testExecuteMessageWithException() throws Exception {
    byte[] messageMetadata = {'E'};
    String statementName = "some portal\0";
    int totalRows = 99999;

    byte[] length = intToBytes(4 + statementName.length() + 4);

    byte[] value =
        Bytes.concat(messageMetadata, length, statementName.getBytes(), intToBytes(totalRows));

    String expectedStatementName = "some portal";
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    PGException testException =
        PGExceptionFactory.newPGException("test error", SQLState.SyntaxError);
    when(intermediatePortalStatement.hasException()).thenReturn(true);
    when(intermediatePortalStatement.getException()).thenReturn(testException);
    when(connectionHandler.getWellKnownClient()).thenReturn(WellKnownClient.UNSPECIFIED);
    when(connectionHandler.getPortal(anyString())).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(ExecuteMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((ExecuteMessage) message).getName());
    assertEquals(totalRows, ((ExecuteMessage) message).getMaxRows());

    verify(connectionHandler).getPortal("some portal");
    ExecuteMessage messageSpy = (ExecuteMessage) spy(message);

    messageSpy.send();
    messageSpy.flush();

    verify(intermediatePortalStatement).executeAsync(backendConnection);
    verify(messageSpy).handleError(testException);
    verify(connectionHandler).cleanUp(intermediatePortalStatement);
  }

  @Test
  public void testClosePortalMessage() throws Exception {
    byte[] messageMetadata = {'C'};
    byte[] statementType = {'P'};
    String statementName = "some portal\0";

    byte[] length = intToBytes(4 + statementType.length + statementName.length());

    byte[] value = Bytes.concat(messageMetadata, length, statementType, statementName.getBytes());

    String expectedStatementName = "some portal";
    PreparedType expectedType = PreparedType.Portal;
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getPortal(anyString())).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(CloseMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((CloseMessage) message).getName());
    assertEquals(expectedType, ((CloseMessage) message).getType());

    verify(connectionHandler).getPortal("some portal");

    message.send();
    verify(intermediatePortalStatement).close();
    verify(connectionHandler).closePortal(expectedStatementName);

    // CloseResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('3', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testCloseStatementMessage() throws Exception {
    byte[] messageMetadata = {'C'};
    byte[] statementType = {'S'};
    String statementName = "some statement\0";

    byte[] length = intToBytes(4 + statementType.length + statementName.length());

    byte[] value = Bytes.concat(messageMetadata, length, statementType, statementName.getBytes());

    String expectedStatementName = "some statement";
    PreparedType expectedType = PreparedType.Statement;
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getStatement(anyString())).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(CloseMessage.class, message.getClass());
    assertEquals(expectedStatementName, ((CloseMessage) message).getName());
    assertEquals(expectedType, ((CloseMessage) message).getType());

    verify(connectionHandler).getStatement("some statement");

    message.send();
    verify(connectionHandler).closeStatement(expectedStatementName);

    // CloseResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('3', outputResult.readByte());
    assertEquals(4, outputResult.readInt());
  }

  @Test
  public void testSyncMessage() throws Exception {
    byte[] messageMetadata = {'S'};

    byte[] length = intToBytes(4);

    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);
    when(backendConnection.getConnectionState()).thenReturn(ConnectionState.IDLE);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(message.getClass(), SyncMessage.class);

    message.send();

    // ReadyResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('Z', outputResult.readByte());
    assertEquals(5, outputResult.readInt());
    assertEquals('I', outputResult.readByte());
  }

  @Test
  public void testSyncMessageInTransaction() throws Exception {
    byte[] messageMetadata = {'S'};

    byte[] length = intToBytes(4);

    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);
    when(backendConnection.getConnectionState()).thenReturn(ConnectionState.TRANSACTION);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(SyncMessage.class, message.getClass());

    message.send();

    // ReadyResponse
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('Z', outputResult.readByte());
    assertEquals(5, outputResult.readInt());
    assertEquals('T', outputResult.readByte());
  }

  @Test
  public void testFlushMessage() throws Exception {
    byte[] messageMetadata = {'H'};

    byte[] length = intToBytes(4);

    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    DataOutputStream outputStream = mock(DataOutputStream.class);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(message.getClass(), FlushMessage.class);

    message.send();

    verify(extendedQueryProtocolHandler).flush();
    verify(extendedQueryProtocolHandler, never()).sync();
  }

  @Test
  public void testFlushMessageInTransaction() throws Exception {
    byte[] messageMetadata = {'H'};

    byte[] length = intToBytes(4);

    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    DataOutputStream outputStream = mock(DataOutputStream.class);

    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(FlushMessage.class, message.getClass());

    message.send();

    verify(extendedQueryProtocolHandler).flush();
    verify(extendedQueryProtocolHandler, never()).sync();
  }

  @Test
  public void testQueryMessageInTransaction() throws Exception {
    byte[] messageMetadata = {'Q', 0, 0, 0, 45};
    String payload = "INSERT INTO users (name) VALUES ('test')\0";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    String expectedSQL = "INSERT INTO users (name) VALUES ('test')";

    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getServer()).thenReturn(server);
    when(connectionHandler.getStatement("")).thenReturn(intermediatePortalStatement);
    when(connectionHandler.getPortal("")).thenReturn(intermediatePortalStatement);
    when(intermediatePortalStatement.getCommandTag()).thenReturn("INSERT");
    when(intermediatePortalStatement.getStatementType()).thenReturn(StatementType.UPDATE);
    when(intermediatePortalStatement.getStatementResult()).thenReturn(new UpdateCount(1L));
    when(intermediatePortalStatement.getUpdateCount()).thenReturn(1L);
    when(backendConnection.getConnectionState()).thenReturn(ConnectionState.TRANSACTION);
    OptionsMetadata options = mock(OptionsMetadata.class);
    when(server.getOptions()).thenReturn(options);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    ExtendedQueryProtocolHandler extendedQueryProtocolHandler =
        new ExtendedQueryProtocolHandler(connectionHandler, backendConnection);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(QueryMessage.class, message.getClass());
    assertEquals(expectedSQL, ((QueryMessage) message).getStatement().getSql());

    message.send();

    // NoData response (query does not return any results).
    DataInputStream outputResult = inputStreamFromOutputStream(result);
    assertEquals('C', outputResult.readByte()); // CommandComplete
    assertEquals('\0', outputResult.readByte());
    assertEquals('\0', outputResult.readByte());
    assertEquals('\0', outputResult.readByte());
    // 15 = 4 + "INSERT".length() + " 0 1".length() + 1 (header + command length + null terminator)
    assertEquals(15, outputResult.readByte());
    byte[] command = new byte[10];
    assertEquals(10, outputResult.read(command, 0, 10));
    assertEquals("INSERT 0 1", new String(command));
    assertEquals('\0', outputResult.readByte());
    // ReadyResponse in transaction ('T')
    assertEquals('Z', outputResult.readByte());
    assertEquals(5, outputResult.readInt());
    assertEquals('T', outputResult.readByte());
  }

  @Test
  public void testTerminateMessage() throws Exception {
    byte[] messageMetadata = {'X'};

    byte[] length = intToBytes(4);

    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(TerminateMessage.class, message.getClass());
  }

  @Test
  public void testUnknownMessageTypeCausesException() {
    byte[] messageMetadata = {'Y'};

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(messageMetadata));

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);

    assertThrows(IllegalStateException.class, () -> ControlMessage.create(connectionHandler));
  }

  @Test
  public void testCopyDataMessage() throws Exception {
    byte[] messageMetadata = {'d'};
    byte[] payload = "This is the payload".getBytes();
    byte[] length = intToBytes(4 + payload.length);
    byte[] value = Bytes.concat(messageMetadata, length, payload);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    CopyStatement copyStatement = mock(CopyStatement.class);
    when(connectionHandler.getActiveCopyStatement()).thenReturn(copyStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    MutationWriter mw = mock(MutationWriter.class);
    when(copyStatement.getMutationWriter()).thenReturn(mw);

    WireMessage message = ControlMessage.create(connectionHandler);
    assertEquals(CopyDataMessage.class, message.getClass());
    assertArrayEquals(payload, ((CopyDataMessage) message).getPayload());

    CopyDataMessage messageSpy = (CopyDataMessage) spy(message);
    messageSpy.send();

    verify(mw).addCopyData(payload);
  }

  @Test
  public void testCopyDataMessageWithNoCopyStatement() throws Exception {
    byte[] messageMetadata = {'d'};
    byte[] payload = "This is the payload".getBytes();
    byte[] length = intToBytes(4 + payload.length);
    byte[] value = Bytes.concat(messageMetadata, length, payload);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getActiveCopyStatement()).thenReturn(null);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);
    // This should be a no-op.
    message.sendPayload();
  }

  @Test
  public void testMultipleCopyDataMessages() throws Exception {
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);

    byte[] messageMetadata = {'d'};
    byte[] payload1 = "1\t'one'\n2\t".getBytes();
    byte[] payload2 = "'two'\n3\t'th".getBytes();
    byte[] payload3 = "ree'\n4\t'four'\n".getBytes();
    byte[] length1 = intToBytes(4 + payload1.length);
    byte[] length2 = intToBytes(4 + payload2.length);
    byte[] length3 = intToBytes(4 + payload3.length);
    byte[] value1 = Bytes.concat(messageMetadata, length1, payload1);
    byte[] value2 = Bytes.concat(messageMetadata, length2, payload2);
    byte[] value3 = Bytes.concat(messageMetadata, length3, payload3);

    DataInputStream inputStream1 = new DataInputStream(new ByteArrayInputStream(value1));
    DataInputStream inputStream2 = new DataInputStream(new ByteArrayInputStream(value2));
    DataInputStream inputStream3 = new DataInputStream(new ByteArrayInputStream(value3));

    String sql = "COPY keyvalue FROM STDIN;";
    CopyStatement copyStatement =
        (CopyStatement)
            CopyStatement.create(connectionHandler, options, "", parse(sql), Statement.of(sql));
    copyStatement.executeAsync(mock(BackendConnection.class));

    when(connectionHandler.getActiveCopyStatement()).thenReturn(copyStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    {
      when(connectionMetadata.getInputStream()).thenReturn(inputStream1);
      WireMessage message = ControlMessage.create(connectionHandler);
      assertEquals(CopyDataMessage.class, message.getClass());
      assertArrayEquals(payload1, ((CopyDataMessage) message).getPayload());
      CopyDataMessage copyDataMessage = (CopyDataMessage) message;
      copyDataMessage.send();
    }
    {
      when(connectionMetadata.getInputStream()).thenReturn(inputStream2);
      WireMessage message = ControlMessage.create(connectionHandler);
      assertEquals(CopyDataMessage.class, message.getClass());
      assertArrayEquals(payload2, ((CopyDataMessage) message).getPayload());
      CopyDataMessage copyDataMessage = (CopyDataMessage) message;
      copyDataMessage.send();
    }
    {
      when(connectionMetadata.getInputStream()).thenReturn(inputStream3);
      WireMessage message = ControlMessage.create(connectionHandler);
      assertEquals(CopyDataMessage.class, message.getClass());
      assertArrayEquals(payload3, ((CopyDataMessage) message).getPayload());
      CopyDataMessage copyDataMessage = (CopyDataMessage) message;
      copyDataMessage.send();
    }
  }

  @Test
  public void testCopyDoneMessage() throws Exception {
    byte[] messageMetadata = {'c'};
    byte[] length = intToBytes(4);
    byte[] value = Bytes.concat(messageMetadata, length);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    CopyStatement copyStatement = mock(CopyStatement.class);
    MutationWriter mutationWriter = mock(MutationWriter.class);
    when(copyStatement.getMutationWriter()).thenReturn(mutationWriter);
    when(connectionHandler.getActiveCopyStatement()).thenReturn(copyStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);

    assertEquals(CopyDoneMessage.class, message.getClass());
    CopyDoneMessage messageSpy = (CopyDoneMessage) spy(message);

    messageSpy.send();
    verify(messageSpy).sendPayload();
    verify(mutationWriter).commit();
  }

  @Test
  public void testCopyFailMessage() throws Exception {
    byte[] messageMetadata = {'f'};
    byte[] errorMessage = "Error Message\0".getBytes();
    byte[] length = intToBytes(4 + errorMessage.length);
    byte[] value = Bytes.concat(messageMetadata, length, errorMessage);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    String expectedErrorMessage = "Error Message";

    CopyStatement copyStatement = mock(CopyStatement.class);
    MutationWriter mutationWriter = mock(MutationWriter.class);
    when(copyStatement.getMutationWriter()).thenReturn(mutationWriter);
    when(connectionHandler.getActiveCopyStatement()).thenReturn(copyStatement);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);

    assertEquals(CopyFailMessage.class, message.getClass());
    assertEquals(expectedErrorMessage, ((CopyFailMessage) message).getErrorMessage());
    message.send();

    verify(mutationWriter).rollback();
  }

  @Test
  public void testFunctionCallMessageThrowsException() throws Exception {
    byte[] messageMetadata = {'F'};
    byte[] functionId = intToBytes(1);
    byte[] argumentCodesCount = {0, 2};
    byte[] argumentCodes = {0, 0, 0, 1}; // First is text, second binary
    byte[] parameterCount = {0, 2};
    byte[] firstParameter = "first parameter\0".getBytes();
    byte[] secondParameter = intToBytes(10);
    byte[] firstParameterLength = intToBytes(firstParameter.length);
    byte[] secondParameterLength = intToBytes(secondParameter.length);
    byte[] resultCode = {0, 0};

    byte[] length =
        intToBytes(
            4
                + functionId.length
                + argumentCodesCount.length
                + argumentCodes.length
                + parameterCount.length
                + firstParameterLength.length
                + firstParameter.length
                + secondParameterLength.length
                + secondParameter.length
                + resultCode.length);

    byte[] value =
        Bytes.concat(
            messageMetadata,
            length,
            functionId,
            argumentCodesCount,
            argumentCodes,
            parameterCount,
            firstParameterLength,
            firstParameter,
            secondParameterLength,
            secondParameter,
            resultCode);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = ControlMessage.create(connectionHandler);

    assertEquals(FunctionCallMessage.class, message.getClass());
    assertThrows(IllegalStateException.class, message::send);
  }

  @Test
  public void testStartUpMessage() throws Exception {
    byte[] protocol = intToBytes(196608);
    byte[] payload =
        ("database\0"
                + "databasename\0"
                + "application_name\0"
                + "psql\0"
                + "client_encoding\0"
                + "UTF8\0"
                + "server_version\0"
                + "13.4\0"
                + "user\0"
                + "me\0")
            .getBytes();
    byte[] length = intToBytes(8 + payload.length);

    byte[] value = Bytes.concat(length, protocol, payload);

    Map<String, String> expectedParameters = new HashMap<>();
    expectedParameters.put("database", "databasename");
    expectedParameters.put("application_name", "psql");
    expectedParameters.put("client_encoding", "UTF8");
    expectedParameters.put("server_version", "13.4");
    expectedParameters.put("user", "me");

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getServer()).thenReturn(server);
    when(connectionHandler.getConnectionId()).thenReturn(1);
    when(connectionHandler.getExtendedQueryProtocolHandler())
        .thenReturn(extendedQueryProtocolHandler);
    when(connectionHandler.getWellKnownClient()).thenReturn(WellKnownClient.UNSPECIFIED);
    when(extendedQueryProtocolHandler.getBackendConnection()).thenReturn(backendConnection);
    SessionState sessionState = mock(SessionState.class);
    PGSetting serverVersionSetting = mock(PGSetting.class);
    when(serverVersionSetting.getSetting()).thenReturn("13.4");
    when(sessionState.get(null, "server_version")).thenReturn(serverVersionSetting);
    when(backendConnection.getSessionState()).thenReturn(sessionState);
    when(server.getOptions()).thenReturn(options);
    when(options.shouldAuthenticate()).thenReturn(false);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = BootstrapMessage.create(connectionHandler);
    assertEquals(StartupMessage.class, message.getClass());

    assertEquals(expectedParameters, ((StartupMessage) message).getParameters());

    message.send();

    DataInputStream outputResult = inputStreamFromOutputStream(result);
    verify(connectionHandler).connectToSpanner("databasename", null);

    // AuthenticationOkResponse
    assertEquals('R', outputResult.readByte());
    assertEquals(8, outputResult.readInt());
    assertEquals(0, outputResult.readInt());

    // KeyDataResponse
    assertEquals('K', outputResult.readByte());
    assertEquals(12, outputResult.readInt());
    assertEquals(1, outputResult.readInt());
    assertEquals(0, outputResult.readInt());

    // ParameterStatusResponse (x11)
    assertEquals('S', outputResult.readByte());
    assertEquals(24, outputResult.readInt());
    assertEquals("server_version\0", readUntil(outputResult, "server_version\0".length()));
    assertEquals("13.4\0", readUntil(outputResult, "13.4\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(31, outputResult.readInt());
    assertEquals("application_name\0", readUntil(outputResult, "application_name\0".length()));
    assertEquals("PGAdapter\0", readUntil(outputResult, "PGAdapter\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(23, outputResult.readInt());
    assertEquals("is_superuser\0", readUntil(outputResult, "is_superuser\0".length()));
    assertEquals("false\0", readUntil(outputResult, "false\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(36, outputResult.readInt());
    assertEquals(
        "session_authorization\0", readUntil(outputResult, "session_authorization\0".length()));
    assertEquals("PGAdapter\0", readUntil(outputResult, "PGAdapter\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(25, outputResult.readInt());
    assertEquals("integer_datetimes\0", readUntil(outputResult, "integer_datetimes\0".length()));
    assertEquals("on\0", readUntil(outputResult, "on\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(25, outputResult.readInt());
    assertEquals("server_encoding\0", readUntil(outputResult, "server_encoding\0".length()));
    assertEquals("UTF8\0", readUntil(outputResult, "UTF8\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(25, outputResult.readInt());
    assertEquals("client_encoding\0", readUntil(outputResult, "client_encoding\0".length()));
    assertEquals("UTF8\0", readUntil(outputResult, "UTF8\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(22, outputResult.readInt());
    assertEquals("DateStyle\0", readUntil(outputResult, "DateStyle\0".length()));
    assertEquals("ISO,YMD\0", readUntil(outputResult, "ISO,YMD\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(27, outputResult.readInt());
    assertEquals("IntervalStyle\0", readUntil(outputResult, "IntervalStyle\0".length()));
    assertEquals("iso_8601\0", readUntil(outputResult, "iso_8601\0".length()));
    assertEquals('S', outputResult.readByte());
    assertEquals(35, outputResult.readInt());
    assertEquals(
        "standard_conforming_strings\0",
        readUntil(outputResult, "standard_conforming_strings\0".length()));
    assertEquals("on\0", readUntil(outputResult, "on\0".length()));
    assertEquals('S', outputResult.readByte());

    // Timezone will vary depending on the default location of the JVM that is running.
    String timezoneIdentifier = ZoneId.systemDefault().getId();
    int expectedLength = timezoneIdentifier.getBytes(StandardCharsets.UTF_8).length + 10 + 4;
    assertEquals(expectedLength, outputResult.readInt());
    assertEquals("TimeZone\0", readUntil(outputResult, "TimeZone\0".length()));
    readUntilNullTerminator(outputResult);

    // ReadyResponse
    assertEquals('Z', outputResult.readByte());
    assertEquals(5, outputResult.readInt());
    assertEquals('I', outputResult.readByte());
  }

  @Test
  public void testCancelMessage() throws Exception {
    byte[] length = intToBytes(16);
    byte[] protocol = intToBytes(80877102);
    byte[] connectionId = intToBytes(1);
    byte[] secret = intToBytes(1);

    byte[] value = Bytes.concat(length, protocol, connectionId, secret);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = BootstrapMessage.create(connectionHandler);
    assertEquals(CancelMessage.class, message.getClass());

    assertEquals(1, ((CancelMessage) message).getConnectionId());
    assertEquals(1, ((CancelMessage) message).getSecret());

    message.send();

    verify(connectionHandler).cancelActiveStatement(1, 1);
    verify(connectionHandler).handleTerminate();
  }

  @Test
  public void testSSLMessage() throws Exception {
    byte[] length = intToBytes(8);
    byte[] protocol = intToBytes(80877103);

    byte[] value = Bytes.concat(length, protocol);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(options.getSslMode()).thenReturn(SslMode.Enable);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = BootstrapMessage.create(connectionHandler);
    assertEquals(SSLMessage.class, message.getClass());
    assertEquals("SSLMessage, Length: 8", message.getPayloadString());

    message.send();

    DataInputStream outputResult = inputStreamFromOutputStream(result);

    // AcceptSSLResponse
    assertEquals('S', outputResult.readByte());
  }

  @Test
  public void testSSLMessageFailsWhenCalledTwice() throws Exception {
    byte[] length = intToBytes(8);
    byte[] protocol = intToBytes(80877103);

    byte[] value = Bytes.concat(length, protocol);

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getServer()).thenReturn(server);
    when(server.getOptions()).thenReturn(options);
    when(options.getSslMode()).thenReturn(SslMode.Disable);
    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);

    WireMessage message = BootstrapMessage.create(connectionHandler);
    assertEquals(SSLMessage.class, message.getClass());

    message.send();

    DataInputStream outputResult = inputStreamFromOutputStream(result);

    // DeclineSSLResponse
    assertEquals('N', outputResult.readByte());

    assertThrows(IOException.class, message::send);
  }

  @Test
  public void testGetPortalMetadataBeforeFlushFails() {
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getPortal(anyString())).thenReturn(intermediatePortalStatement);
    when(intermediatePortalStatement.containsResultSet()).thenReturn(true);
    when(intermediatePortalStatement.describeAsync(backendConnection))
        .thenReturn(SettableFuture.create());

    DescribeMessage describeMessage =
        new DescribeMessage(connectionHandler, ManuallyCreatedToken.MANUALLY_CREATED_TOKEN);
    describeMessage.buffer(backendConnection);

    assertThrows(IllegalStateException.class, describeMessage::getPortalMetadata);
  }

  @Test
  public void testSkipMessage() throws Exception {
    byte[] messageMetadata = {0, 0, 0, 45};
    String payload = "INSERT INTO users (name) VALUES ('test')\0";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);

    SkipMessage message = SkipMessage.createForValidStream(connectionHandler);
    message.send();

    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
    assertEquals("Skip", message.getMessageName());
    assertEquals("", message.getIdentifier());
    assertEquals("Length: 45", message.getPayloadString());
  }

  @Test
  public void testFlushSkippedInCopyMode() throws Exception {
    byte[] messageMetadata = {FlushMessage.IDENTIFIER, 0, 0, 0, 4};

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(messageMetadata));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);

    ControlMessage message = ControlMessage.create(connectionHandler);

    assertEquals(SkipMessage.class, message.getClass());
    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
  }

  @Test
  public void testSyncSkippedInCopyMode() throws Exception {
    byte[] messageMetadata = {SyncMessage.IDENTIFIER, 0, 0, 0, 4};

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(messageMetadata));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.COPY_IN);

    ControlMessage message = ControlMessage.create(connectionHandler);

    assertEquals(SkipMessage.class, message.getClass());
    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
  }

  @Test
  public void testCopyDataSkippedInNormalMode() throws Exception {
    byte[] messageMetadata = {CopyDataMessage.IDENTIFIER, 0, 0, 0, 4};
    String payload = "1\t'One'\n2\t'Two'\n";
    byte[] value = Bytes.concat(messageMetadata, payload.getBytes());

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);

    ControlMessage message = ControlMessage.create(connectionHandler);

    assertEquals(SkipMessage.class, message.getClass());
    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
  }

  @Test
  public void testCopyDoneSkippedInNormalMode() throws Exception {
    byte[] messageMetadata = {CopyDoneMessage.IDENTIFIER, 0, 0, 0, 4};

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(messageMetadata));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);

    ControlMessage message = ControlMessage.create(connectionHandler);

    assertEquals(SkipMessage.class, message.getClass());
    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
  }

  @Test
  public void testCopyFailSkippedInNormalMode() throws Exception {
    byte[] messageMetadata = {CopyFailMessage.IDENTIFIER, 0, 0, 0, 4};

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(messageMetadata));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);

    ControlMessage message = ControlMessage.create(connectionHandler);

    assertEquals(SkipMessage.class, message.getClass());
    // Verify that nothing was written to the output.
    assertEquals(0, result.size());
  }

  @Test
  public void testRepeatedCopyDataInNormalMode_TerminatesConnectionAndReturnsError()
      throws Exception {
    String payload = "1\t'One'\n2\t'Two'\n";
    byte[] messageMetadata = {
      CopyDataMessage.IDENTIFIER,
      0,
      0,
      0,
      (byte) (4 + payload.getBytes(StandardCharsets.UTF_8).length)
    };
    byte[] value = new byte[0];
    for (int i = 0; i <= MAX_INVALID_MESSAGE_COUNT; i++) {
      value = Bytes.concat(value, messageMetadata, payload.getBytes());
    }

    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(value));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream outputStream = new DataOutputStream(result);

    when(connectionMetadata.getInputStream()).thenReturn(inputStream);
    when(connectionMetadata.getOutputStream()).thenReturn(outputStream);
    when(connectionHandler.getWellKnownClient()).thenReturn(WellKnownClient.UNSPECIFIED);
    when(connectionHandler.getConnectionMetadata()).thenReturn(connectionMetadata);
    when(connectionHandler.getStatus()).thenReturn(ConnectionStatus.AUTHENTICATED);
    doCallRealMethod().when(connectionHandler).increaseInvalidMessageCount();
    when(connectionHandler.getInvalidMessageCount()).thenCallRealMethod();

    for (int i = 0; i < MAX_INVALID_MESSAGE_COUNT; i++) {
      ControlMessage message = ControlMessage.create(connectionHandler);
      assertEquals(SkipMessage.class, message.getClass());
      // Verify that nothing was written to the output.
      assertEquals(0, result.size());
      verify(connectionHandler, never()).setStatus(ConnectionStatus.TERMINATED);
    }

    ControlMessage.create(connectionHandler);
    verify(connectionHandler).setStatus(ConnectionStatus.TERMINATED);
    byte[] resultBytes = result.toByteArray();
    assertEquals('E', resultBytes[0]);
  }

  private void setupQueryInformationSchemaResults() {
    DatabaseClient databaseClient = mock(DatabaseClient.class);
    ReadContext singleUseReadContext = mock(ReadContext.class);
    when(databaseClient.singleUse()).thenReturn(singleUseReadContext);
    when(connectionHandler.getSpannerConnection()).thenReturn(connection);
    when(connection.getDatabaseClient()).thenReturn(databaseClient);
    ResultSet spannerType = mock(ResultSet.class);
    when(spannerType.getString("column_name")).thenReturn("key", "value");
    when(spannerType.getString("data_type")).thenReturn("bigint", "character varying");
    when(spannerType.next()).thenReturn(true, true, false);
    when(singleUseReadContext.executeQuery(
            ArgumentMatchers.argThat(
                statement ->
                    statement != null && statement.getSql().startsWith("SELECT column_name"))))
        .thenReturn(spannerType);

    ResultSet countResult = mock(ResultSet.class);
    when(countResult.getLong(0)).thenReturn(2L);
    when(countResult.next()).thenReturn(true, false);
    when(singleUseReadContext.executeQuery(
            ArgumentMatchers.argThat(
                statement ->
                    statement != null && statement.getSql().startsWith("SELECT COUNT(*)"))))
        .thenReturn(countResult);
  }
}
