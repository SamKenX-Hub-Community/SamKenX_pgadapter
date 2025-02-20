// Copyright 2022 Google LLC
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

package com.google.cloud.spanner.pgadapter.utils;

import com.google.api.core.InternalApi;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.pgadapter.ConnectionHandler;
import com.google.cloud.spanner.pgadapter.error.PGException;
import com.google.cloud.spanner.pgadapter.error.SQLState;
import com.google.cloud.spanner.pgadapter.statements.PgCatalog.EmptyPgAttribute;
import com.google.cloud.spanner.pgadapter.statements.PgCatalog.PgCatalogTable;
import com.google.cloud.spanner.pgadapter.statements.local.DjangoGetTableNamesStatement;
import com.google.cloud.spanner.pgadapter.statements.local.ListDatabasesStatement;
import com.google.cloud.spanner.pgadapter.statements.local.LocalStatement;
import com.google.cloud.spanner.pgadapter.statements.local.SelectCurrentCatalogStatement;
import com.google.cloud.spanner.pgadapter.statements.local.SelectCurrentDatabaseStatement;
import com.google.cloud.spanner.pgadapter.statements.local.SelectCurrentSchemaStatement;
import com.google.cloud.spanner.pgadapter.statements.local.SelectVersionStatement;
import com.google.cloud.spanner.pgadapter.wireoutput.NoticeResponse;
import com.google.cloud.spanner.pgadapter.wireoutput.NoticeResponse.NoticeSeverity;
import com.google.cloud.spanner.pgadapter.wireprotocol.ParseMessage;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.postgresql.core.Oid;

/**
 * Utility class that tries to automatically detect well-known clients and drivers that are
 * connecting to PGAdapter.
 */
@InternalApi
public class ClientAutoDetector {
  public static final ImmutableList<LocalStatement> EMPTY_LOCAL_STATEMENTS = ImmutableList.of();
  public static final ImmutableList<LocalStatement> DEFAULT_LOCAL_STATEMENTS =
      ImmutableList.of(
          SelectCurrentSchemaStatement.INSTANCE,
          SelectCurrentDatabaseStatement.INSTANCE,
          SelectCurrentCatalogStatement.INSTANCE,
          SelectVersionStatement.INSTANCE,
          DjangoGetTableNamesStatement.INSTANCE);
  private static final ImmutableSet<String> DEFAULT_CHECK_PG_CATALOG_PREFIXES =
      ImmutableSet.of("pg_", "information_schema.");
  public static final String PGBENCH_USAGE_HINT =
      "See https://github.com/GoogleCloudPlatform/pgadapter/blob/-/docs/pgbench.md for how to use pgbench with PGAdapter";

  public enum WellKnownClient {
    PSQL {

      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // PSQL makes it easy for us, as it sends its own name in the application_name parameter.
        return parameters.containsKey("application_name")
            && parameters.get("application_name").equals("psql");
      }

      @Override
      public ImmutableList<LocalStatement> getLocalStatements(ConnectionHandler connectionHandler) {
        if (connectionHandler.getServer().getOptions().useDefaultLocalStatements()) {
          return ImmutableList.<LocalStatement>builder()
              .addAll(DEFAULT_LOCAL_STATEMENTS)
              .add(new ListDatabasesStatement(connectionHandler))
              .build();
        }
        return ImmutableList.of(new ListDatabasesStatement(connectionHandler));
      }
    },
    PGBENCH {
      final ImmutableList<String> errorHints = ImmutableList.of(PGBENCH_USAGE_HINT);
      volatile long lastHintTimestampMillis = 0L;

      @Override
      public void reset() {
        lastHintTimestampMillis = 0L;
      }

      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // PGBENCH makes it easy for us, as it sends its own name in the application_name parameter.
        return parameters.containsKey("application_name")
            && parameters.get("application_name").equals("pgbench");
      }

      @Override
      public ImmutableList<NoticeResponse> createStartupNoticeResponses(
          ConnectionHandler connection) {
        synchronized (PGBENCH) {
          // Only send the hint at most once every 30 seconds, to prevent benchmark runs that open
          // multiple connections from showing the hint every time.
          if (Duration.ofMillis(System.currentTimeMillis() - lastHintTimestampMillis).getSeconds()
              > 30L) {
            lastHintTimestampMillis = System.currentTimeMillis();
            return ImmutableList.of(
                new NoticeResponse(
                    connection.getConnectionMetadata().getOutputStream(),
                    SQLState.Success,
                    NoticeSeverity.INFO,
                    "Detected connection from pgbench",
                    PGBENCH_USAGE_HINT + "\n"));
          }
        }
        return super.createStartupNoticeResponses(connection);
      }

      @Override
      public ImmutableList<String> getErrorHints(PGException exception) {
        return errorHints;
      }
    },
    JDBC {
      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // JDBC always sends the following startup parameters as the first five parameters (and has
        // done so for more than 5 years):
        // paramList.add(new String[]{"user", user});
        // paramList.add(new String[]{"database", database});
        // paramList.add(new String[]{"client_encoding", "UTF8"});
        // paramList.add(new String[]{"DateStyle", "ISO"});
        // paramList.add(new String[]{"TimeZone", createPostgresTimeZone()});
        if (orderedParameterKeys.size() < 5) {
          return false;
        }
        if (!orderedParameterKeys.get(0).equals("user")) {
          return false;
        }
        if (!orderedParameterKeys.get(1).equals("database")) {
          return false;
        }
        if (!orderedParameterKeys.get(2).equals("client_encoding")) {
          return false;
        }
        if (!orderedParameterKeys.get(3).equals("DateStyle")) {
          return false;
        }
        if (!orderedParameterKeys.get(4).equals("TimeZone")) {
          return false;
        }
        if (!parameters.get("client_encoding").equals("UTF8")) {
          return false;
        }
        return parameters.get("DateStyle").equals("ISO");
      }

      @Override
      public ImmutableMap<String, String> getDefaultParameters() {
        return ImmutableMap.of(
            "spanner.guess_types", String.format("%d,%d", Oid.TIMESTAMPTZ, Oid.DATE));
      }
    },
    PGX {
      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // pgx does not send enough unique parameters for it to be auto-detected.
        return false;
      }

      @Override
      boolean isClient(List<ParseMessage> skippedParseMessages, ParseMessage parseMessage) {
        // pgx uses a relatively unique naming scheme for prepared statements (and uses prepared
        // statements for everything by default).
        return parseMessage.getName() != null && parseMessage.getName().startsWith("lrupsc_");
      }
    },
    NPGSQL {
      final ImmutableMap<String, String> tableReplacements =
          ImmutableMap.of(
              "pg_catalog.pg_attribute", "pg_attribute", "pg_attribute", "pg_attribute");
      final ImmutableMap<String, PgCatalogTable> pgCatalogTables =
          ImmutableMap.of("pg_attribute", new EmptyPgAttribute());

      final ImmutableMap<Pattern, Supplier<String>> functionReplacements =
          ImmutableMap.of(
              Pattern.compile("elemproc\\.oid = elemtyp\\.typreceive"),
              Suppliers.ofInstance("false"),
              Pattern.compile("proc\\.oid = typ\\.typreceive"),
              Suppliers.ofInstance("false"),
              Pattern.compile("WHEN proc\\.proname='array_recv' THEN typ\\.typelem"),
              Suppliers.ofInstance("WHEN substr(typ.typname, 1, 1)='_' THEN typ.typelem"),
              Pattern.compile(
                  "WHEN proc\\.proname='array_recv' THEN 'a' ELSE typ\\.typtype END AS typtype"),
              Suppliers.ofInstance(
                  "WHEN substr(typ.typname, 1, 1)='_' THEN 'a' ELSE typ.typtype END AS typtype"));

      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // npgsql does not send enough unique parameters for it to be auto-detected.
        return false;
      }

      @Override
      boolean isClient(List<ParseMessage> skippedParseMessages, List<Statement> statements) {
        // The npgsql client always starts with sending a query that contains multiple statements
        // and that starts with the following prefix.
        return statements.size() == 1
            && statements
                .get(0)
                .getSql()
                .startsWith(
                    "SELECT version();\n"
                        + "\n"
                        + "SELECT ns.nspname, t.oid, t.typname, t.typtype, t.typnotnull, t.elemtypoid\n");
      }

      @Override
      public ImmutableMap<String, String> getTableReplacements() {
        return tableReplacements;
      }

      @Override
      public ImmutableMap<String, PgCatalogTable> getPgCatalogTables() {
        return pgCatalogTables;
      }

      @Override
      public ImmutableMap<Pattern, Supplier<String>> getFunctionReplacements() {
        return functionReplacements;
      }
    },
    SQLALCHEMY2 {
      final ImmutableMap<Pattern, Supplier<String>> functionReplacements =
          ImmutableMap.of(
              Pattern.compile("oid::regtype::text AS regtype"),
              Suppliers.ofInstance("'' as regtype"),
              Pattern.compile("WHERE t\\.oid = to_regtype\\(\\$1\\)"),
              Suppliers.ofInstance("WHERE t.typname = \\$1"));

      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // SQLAlchemy 2.x does not send enough unique parameters for it to be auto-detected.
        return false;
      }

      @Override
      boolean isClient(List<ParseMessage> skippedParseMessages, List<Statement> statements) {
        // SQLAlchemy always starts with the following (relatively unique) combination of queries:
        // 1. 'BEGIN' using the extended query protocol.
        // 2. 'select pg_catalog.version()' using the simple query protocol.
        return skippedParseMessages.size() == 1
            && skippedParseMessages.get(0).getSql().equals("BEGIN")
            && statements.size() == 1
            && statements.get(0).getSql().equals("select pg_catalog.version()");
      }

      @Override
      public ImmutableMap<Pattern, Supplier<String>> getFunctionReplacements() {
        return functionReplacements;
      }
    },
    UNSPECIFIED {
      @Override
      boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters) {
        // Use UNSPECIFIED as default to prevent null checks everywhere and to ease the use of any
        // defaults defined in this enum.
        return true;
      }

      @Override
      boolean isClient(List<ParseMessage> skippedParseMessages, List<Statement> statements) {
        // Use UNSPECIFIED as default to prevent null checks everywhere and to ease the use of any
        // defaults defined in this enum.
        return true;
      }

      @Override
      boolean isClient(List<ParseMessage> skippedParseMessages, ParseMessage parseMessage) {
        // Use UNSPECIFIED as default to prevent null checks everywhere and to ease the use of any
        // defaults defined in this enum.
        return true;
      }
    };

    abstract boolean isClient(List<String> orderedParameterKeys, Map<String, String> parameters);

    /** Resets any cached or temporary settings for the client. */
    @VisibleForTesting
    public void reset() {}

    boolean isClient(List<ParseMessage> skippedParseMessages, List<Statement> statements) {
      return false;
    }

    boolean isClient(List<ParseMessage> skippedParseMessages, ParseMessage parseMessage) {
      return false;
    }

    public ImmutableList<LocalStatement> getLocalStatements(ConnectionHandler connectionHandler) {
      if (connectionHandler.getServer().getOptions().useDefaultLocalStatements()) {
        return DEFAULT_LOCAL_STATEMENTS;
      }
      return EMPTY_LOCAL_STATEMENTS;
    }

    public ImmutableSet<String> getPgCatalogCheckPrefixes() {
      return DEFAULT_CHECK_PG_CATALOG_PREFIXES;
    }

    public ImmutableMap<String, String> getTableReplacements() {
      return ImmutableMap.of();
    }

    public ImmutableMap<String, PgCatalogTable> getPgCatalogTables() {
      return ImmutableMap.of();
    }

    public ImmutableMap<Pattern, Supplier<String>> getFunctionReplacements() {
      return ImmutableMap.of();
    }

    /** Creates specific notice messages for a client after startup. */
    public ImmutableList<NoticeResponse> createStartupNoticeResponses(
        ConnectionHandler connection) {
      return ImmutableList.of();
    }

    /** Returns the client-specific hint(s) that should be included with the given exception. */
    public ImmutableList<String> getErrorHints(PGException exception) {
      return ImmutableList.of();
    }

    public ImmutableMap<String, String> getDefaultParameters() {
      return ImmutableMap.of();
    }
  }

  /**
   * Returns the {@link WellKnownClient} that the detector thinks is connecting to PGAdapter based
   * purely on the list of parameters. It will return UNSPECIFIED if no specific client could be
   * determined.
   */
  public static @Nonnull WellKnownClient detectClient(
      List<String> orderParameterKeys, Map<String, String> parameters) {
    for (WellKnownClient client : WellKnownClient.values()) {
      if (client.isClient(orderParameterKeys, parameters)) {
        return client;
      }
    }
    // The following line should never be reached.
    throw new IllegalStateException("UNSPECIFIED.isClient() should have returned true");
  }

  /**
   * Returns the {@link WellKnownClient} that the detector thinks is connected to PGAdapter based on
   * the given list of SQL statements that have been executed.
   */
  public static @Nonnull WellKnownClient detectClient(
      List<ParseMessage> skippedParseMessages, List<Statement> statements) {
    for (WellKnownClient client : WellKnownClient.values()) {
      if (client.isClient(skippedParseMessages, statements)) {
        return client;
      }
    }
    // The following line should never be reached.
    throw new IllegalStateException("UNSPECIFIED.isClient() should have returned true");
  }

  /**
   * Returns the {@link WellKnownClient} that the detector thinks is connected to PGAdapter based on
   * the Parse message that has been received.
   */
  public static @Nonnull WellKnownClient detectClient(
      List<ParseMessage> skippedParseMessages, ParseMessage parseMessage) {
    for (WellKnownClient client : WellKnownClient.values()) {
      if (client.isClient(skippedParseMessages, parseMessage)) {
        return client;
      }
    }
    // The following line should never be reached.
    throw new IllegalStateException("UNSPECIFIED.isClient() should have returned true");
  }
}
