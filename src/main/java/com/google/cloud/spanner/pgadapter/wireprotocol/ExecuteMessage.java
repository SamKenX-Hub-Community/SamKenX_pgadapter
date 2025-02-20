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

import com.google.api.core.InternalApi;
import com.google.cloud.spanner.pgadapter.ConnectionHandler;
import com.google.cloud.spanner.pgadapter.statements.BackendConnection;
import com.google.cloud.spanner.pgadapter.statements.CopyStatement;
import com.google.cloud.spanner.pgadapter.statements.IntermediatePreparedStatement;
import java.text.MessageFormat;

/** Executes a portal. */
@InternalApi
public class ExecuteMessage extends AbstractQueryProtocolMessage {
  protected static final char IDENTIFIER = 'E';

  private final String name;
  private final int maxRows;
  private final IntermediatePreparedStatement statement;

  public ExecuteMessage(ConnectionHandler connection) throws Exception {
    super(connection);
    this.name = this.readAll();
    this.maxRows = this.inputStream.readInt();
    this.statement = this.connection.getPortal(this.name);
  }

  /** Constructor for execute messages that are generated by the simple query protocol. */
  public ExecuteMessage(ConnectionHandler connection, ManuallyCreatedToken manuallyCreatedToken) {
    super(connection, 8, manuallyCreatedToken);
    this.name = "";
    this.maxRows = 0;
    this.statement = this.connection.getPortal(this.name);
  }

  @Override
  void buffer(BackendConnection backendConnection) {
    this.statement.executeAsync(backendConnection);
  }

  @Override
  public void flush() throws Exception {
    this.handleExecute();
  }

  @Override
  protected String getMessageName() {
    return "Execute";
  }

  @Override
  protected String getPayloadString() {
    return new MessageFormat("Length: {0}, " + "Name: {1}, " + "Max Rows: {2}")
        .format(new Object[] {this.length, this.name, this.maxRows});
  }

  @Override
  protected String getIdentifier() {
    return String.valueOf(IDENTIFIER);
  }

  public String getName() {
    return this.name;
  }

  public int getMaxRows() {
    return this.maxRows;
  }

  @Override
  public String getSql() {
    return this.statement.getSql();
  }

  @Override
  protected int getHeaderLength() {
    return 8;
  }

  /**
   * Called when an execute message is received.
   *
   * @throws Exception if sending the message back to the client causes an error.
   */
  private void handleExecute() throws Exception {
    // Copy response is handled directly in the COPY protocol.
    if (this.statement instanceof CopyStatement) {
      if (this.statement.hasException()) {
        throw this.statement.getException();
      }
    } else {
      if (this.statement.hasException()) {
        this.handleError(this.statement.getException());
      } else {
        try {
          this.sendSpannerResult(this.statement, this.queryMode, this.maxRows);
        } catch (Exception exception) {
          handleError(exception);
        }
      }
    }
    this.connection.cleanUp(this.statement);
  }
}
