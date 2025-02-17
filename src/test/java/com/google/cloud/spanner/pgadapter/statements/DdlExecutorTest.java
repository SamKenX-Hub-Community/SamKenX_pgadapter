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

package com.google.cloud.spanner.pgadapter.statements;

import static com.google.cloud.spanner.pgadapter.statements.DdlExecutor.unquoteIdentifier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.AbstractStatementParser;
import com.google.cloud.spanner.pgadapter.statements.SimpleParser.TableOrIndexName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DdlExecutorTest {
  private static final AbstractStatementParser PARSER =
      AbstractStatementParser.getInstance(Dialect.POSTGRESQL);

  @Test
  public void testUnquoteIdentifier() {
    assertEquals("foo", unquoteIdentifier("foo"));
    assertEquals("foo", unquoteIdentifier("FOO"));
    assertEquals("foo", unquoteIdentifier("Foo"));

    assertEquals("foo", unquoteIdentifier("\"foo\""));
    assertEquals("FOO", unquoteIdentifier("\"FOO\""));
    assertEquals("Foo", unquoteIdentifier("\"Foo\""));

    assertEquals("", unquoteIdentifier(""));
    assertEquals("a", unquoteIdentifier("a"));
    assertEquals("", unquoteIdentifier("\"\""));
  }

  private Statement translate(String sql, DdlExecutor executor) {
    Statement statement = Statement.of(sql);
    return executor.translate(PARSER.parse(statement), statement);
  }

  @Test
  public void testTranslateCreateTableNotExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean tableExists(TableOrIndexName name) {
            return false;
          }
        };

    assertEquals(
        Statement.of("create table foo (id int)"),
        translate("create table foo (id int)", executor));
    assertEquals(
        Statement.of("create table foo(id int)"), translate("create table foo(id int)", executor));
    assertEquals(
        Statement.of("create table \"Foo\" (id int)"),
        translate("create table \"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table s.foo (id int)"),
        translate("create table s.foo (id int)", executor));
    assertEquals(
        Statement.of("create table s.\"Foo\" (id int)"),
        translate("create table s.\"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table \"s\".\"Foo\" (id int)"),
        translate("create table \"s\".\"Foo\" (id int)", executor));
    assertEquals(Statement.of("create table"), translate("create table", executor));
    assertEquals(Statement.of("create table "), translate("create table ", executor));

    assertEquals(
        Statement.of("create table foo (id int)"),
        translate("create table if not exists foo (id int)", executor));
    assertEquals(
        Statement.of("create table \"Foo\" (id int)"),
        translate("create table if not exists \"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table s.foo (id int)"),
        translate("create table if not exists s.foo (id int)", executor));
    assertEquals(
        Statement.of("create table s.\"Foo\" (id int)"),
        translate("create table if not exists s.\"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table \"s\".\"Foo\" (id int)"),
        translate("create table if not exists \"s\".\"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table if not exists"),
        translate("create table if not exists", executor));
  }

  @Test
  public void testTranslateCreateTableExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean tableExists(TableOrIndexName name) {
            return true;
          }
        };

    assertEquals(
        Statement.of("create table foo (id int)"),
        translate("create table foo (id int)", executor));
    assertEquals(
        Statement.of("create table \"Foo\" (id int)"),
        translate("create table \"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table s.foo (id int)"),
        translate("create table s.foo (id int)", executor));
    assertEquals(
        Statement.of("create table \"S\".\"Foo\" (id int)"),
        translate("create table \"S\".\"Foo\" (id int)", executor));
    assertEquals(Statement.of("create table"), translate("create table", executor));

    assertNull(translate("create table if not exists foo (id int)", executor));
    assertNull(translate("create table if not exists \"Foo\" (id int)", executor));
    assertNull(translate("create table if not exists s.foo (id int)", executor));
    assertNull(translate("create table if not exists \"S\".\"Foo\" (id int)", executor));
    assertEquals(
        Statement.of("create table if not exists"),
        translate("create table if not exists", executor));
  }

  @Test
  public void testTranslateCreateIndexNotExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return false;
          }
        };

    assertEquals(
        Statement.of("create index foo on bar(id)"),
        translate("create index foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"Foo\" on bar(id)"),
        translate("create index \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create index s.foo on bar(id)"),
        translate("create index s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"s\".\"Foo\" on bar(id)"),
        translate("create index \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(Statement.of("create index"), translate("create index", executor));
    assertEquals(Statement.of("create index "), translate("create index ", executor));

    assertEquals(
        Statement.of("create index foo on bar(id)"),
        translate("create index if not exists foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"Foo\" on bar(id)"),
        translate("create index if not exists \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create index s.foo on bar(id)"),
        translate("create index if not exists s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"s\".\"Foo\" on bar(id)"),
        translate("create index if not exists \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create index if not exists"),
        translate("create index if not exists", executor));
  }

  @Test
  public void testTranslateCreateIndexExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return true;
          }
        };

    assertEquals(
        Statement.of("create index foo on bar(id)"),
        translate("create index foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"Foo\" on bar(id)"),
        translate("create index \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create index s.foo on bar(id)"),
        translate("create index s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create index \"s\".\"Foo\" on bar(id)"),
        translate("create index \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(Statement.of("create index"), translate("create index", executor));

    assertNull(translate("create index if not exists foo on bar(id int)", executor));
    assertNull(translate("create index if not exists \"Foo\" on bar(id int)", executor));
    assertNull(translate("create index if not exists s.foo on bar(id int)", executor));
    assertNull(translate("create index if not exists \"s\".\"Foo\" on bar(id int)", executor));
    assertEquals(
        Statement.of("create index if not exists"),
        translate("create index if not exists", executor));
  }

  @Test
  public void testTranslateCreateUniqueIndexNotExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return false;
          }
        };

    assertEquals(
        Statement.of("create unique index foo on bar(id)"),
        translate("create unique index foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"Foo\" on bar(id)"),
        translate("create unique index \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index s.foo on bar(id)"),
        translate("create unique index s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"s\".\"Foo\" on bar(id)"),
        translate("create unique index \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(Statement.of("create unique index"), translate("create unique index", executor));
    assertEquals(Statement.of("create unique index "), translate("create unique index ", executor));

    assertEquals(
        Statement.of("create unique index foo on bar(id)"),
        translate("create unique index if not exists foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"Foo\" on bar(id)"),
        translate("create unique index if not exists \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index s.foo on bar(id)"),
        translate("create unique index if not exists s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"s\".\"Foo\" on bar(id)"),
        translate("create unique index if not exists \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index if not exists"),
        translate("create unique index if not exists", executor));
  }

  @Test
  public void testTranslateCreateUniqueIndexExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return true;
          }
        };

    assertEquals(
        Statement.of("create unique index foo on bar(id)"),
        translate("create unique index foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"Foo\" on bar(id)"),
        translate("create unique index \"Foo\" on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index s.foo on bar(id)"),
        translate("create unique index s.foo on bar(id)", executor));
    assertEquals(
        Statement.of("create unique index \"s\".\"Foo\" on bar(id)"),
        translate("create unique index \"s\".\"Foo\" on bar(id)", executor));
    assertEquals(Statement.of("create unique index"), translate("create unique index", executor));

    assertNull(translate("create unique index if not exists foo on bar(id int)", executor));
    assertNull(translate("create unique index if not exists \"Foo\" on bar(id int)", executor));
    assertNull(translate("create unique index if not exists s.foo on bar(id int)", executor));
    assertNull(
        translate("create unique index if not exists \"s\".\"Foo\" on bar(id int)", executor));
    assertEquals(
        Statement.of("create unique index if not exists"),
        translate("create unique index if not exists", executor));
  }

  @Test
  public void testTranslateDropTableNotExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean tableExists(TableOrIndexName name) {
            return false;
          }
        };

    assertEquals(Statement.of("drop table foo"), translate("drop table foo", executor));
    assertEquals(Statement.of("drop table \"Foo\""), translate("drop table \"Foo\"", executor));
    assertEquals(Statement.of("drop table s.foo"), translate("drop table s.foo", executor));
    assertEquals(
        Statement.of("drop table \"s\".\"Foo\""), translate("drop table \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop table"), translate("drop table", executor));
    assertEquals(Statement.of("drop table "), translate("drop table ", executor));

    assertNull(translate("drop table if exists foo", executor));
    assertNull(translate("drop table if exists \"Foo\"", executor));
    assertNull(translate("drop table if exists sfoo", executor));
    assertNull(translate("drop table if exists \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop table if exists"), translate("drop table if exists", executor));
  }

  @Test
  public void testTranslateDropTableExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean tableExists(TableOrIndexName name) {
            return true;
          }
        };

    assertEquals(Statement.of("drop table foo"), translate("drop table foo", executor));
    assertEquals(Statement.of("drop table \"Foo\""), translate("drop table \"Foo\"", executor));
    assertEquals(Statement.of("drop table s.foo"), translate("drop table s.foo", executor));
    assertEquals(
        Statement.of("drop table \"s\".\"Foo\""), translate("drop table \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop table"), translate("drop table", executor));

    assertEquals(Statement.of("drop table foo"), translate("drop table if exists foo", executor));
    assertEquals(
        Statement.of("drop table \"Foo\""), translate("drop table if exists \"Foo\"", executor));
    assertEquals(
        Statement.of("drop table s.foo"), translate("drop table if exists s.foo", executor));
    assertEquals(
        Statement.of("drop table \"s\".\"Foo\""),
        translate("drop table if exists \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop table if exists"), translate("drop table if exists", executor));
  }

  @Test
  public void testTranslateDropIndexNotExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return false;
          }
        };

    assertEquals(Statement.of("drop index foo"), translate("drop index foo", executor));
    assertEquals(Statement.of("drop index \"Foo\""), translate("drop index \"Foo\"", executor));
    assertEquals(Statement.of("drop index s.foo"), translate("drop index s.foo", executor));
    assertEquals(
        Statement.of("drop index \"s\".\"Foo\""), translate("drop index \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop index"), translate("drop index", executor));
    assertEquals(Statement.of("drop index "), translate("drop index ", executor));

    assertNull(translate("drop index if exists foo", executor));
    assertNull(translate("drop index if exists \"Foo\"", executor));
    assertNull(translate("drop index if exists s.foo", executor));
    assertNull(translate("drop index if exists \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop index if exists"), translate("drop index if exists", executor));
  }

  @Test
  public void testTranslateDropIndexExists() {
    DdlExecutor executor =
        new DdlExecutor(mock(BackendConnection.class)) {
          @Override
          boolean indexExists(TableOrIndexName name) {
            return true;
          }
        };

    assertEquals(Statement.of("drop index foo"), translate("drop index foo", executor));
    assertEquals(Statement.of("drop index \"Foo\""), translate("drop index \"Foo\"", executor));
    assertEquals(Statement.of("drop index s.foo"), translate("drop index s.foo", executor));
    assertEquals(
        Statement.of("drop index \"s\".\"Foo\""), translate("drop index \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop index"), translate("drop index", executor));

    assertEquals(Statement.of("drop index foo"), translate("drop index if exists foo", executor));
    assertEquals(
        Statement.of("drop index \"Foo\""), translate("drop index if exists \"Foo\"", executor));
    assertEquals(
        Statement.of("drop index s.foo"), translate("drop index if exists s.foo", executor));
    assertEquals(
        Statement.of("drop index \"s\".\"Foo\""),
        translate("drop index if exists \"s\".\"Foo\"", executor));
    assertEquals(Statement.of("drop index if exists"), translate("drop index if exists", executor));
  }

  @Test
  public void testMaybeRemovePrimaryKeyConstraintName() {
    DdlExecutor ddlExecutor = new DdlExecutor(mock(BackendConnection.class));

    assertEquals(
        Statement.of("create table foo (id bigint primary key, value text)"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of("create table foo (id bigint primary key, value text)")));
    assertEquals(
        Statement.of("create table foo (id bigint, value text, primary key (id))"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of("create table foo (id bigint, value text, primary key (id))")));
    assertEquals(
        Statement.of(
            "create table foo (value1 varchar, value2 text, primary key (value1, value2))"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table foo (value1 varchar, value2 text, primary key (value1, value2))")));
    assertEquals(
        Statement.of(
            "create table foo (id bigint primary key, value text, constraint chk_bar check (length(value) < 100))"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table foo (id bigint primary key, value text, constraint chk_bar check (length(value) < 100))")));

    // Only primary key constraints that are literally named 'pk_<table-name>' are replaced.
    assertEquals(
        Statement.of(
            "create table foo (id bigint, value text, constraint pk_a1b2 primary key (id) )"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table foo (id bigint, value text, constraint pk_a1b2 primary key (id) )")));

    assertEquals(
        Statement.of("create table foo (id bigint, value text,  primary key (id) )"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table foo (id bigint, value text, constraint pk_foo primary key (id) )")));
    assertEquals(
        Statement.of(
            "create table foo (id bigint, value text,  primary key (id), constraint fk_bar foreign key (value) references bar (id))"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table foo (id bigint, value text, constraint pk_foo primary key (id), constraint fk_bar foreign key (value) references bar (id))")));
    assertEquals(
        Statement.of("create table public.foo (id bigint, value text,  primary key (id) )"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "create table public.foo (id bigint, value text, constraint pk_foo primary key (id) )")));

    assertEquals(
        Statement.of(
            "CREATE TABLE \"user\" (\"id\" integer NOT NULL, \"firstName\" character varying NOT NULL, \"lastName\" character varying NOT NULL, \"age\" integer NOT NULL,  PRIMARY KEY (\"id\"))"),
        ddlExecutor.maybeRemovePrimaryKeyConstraintName(
            Statement.of(
                "CREATE TABLE \"user\" (\"id\" integer NOT NULL, \"firstName\" character varying NOT NULL, \"lastName\" character varying NOT NULL, \"age\" integer NOT NULL, CONSTRAINT \"PK_user\" PRIMARY KEY (\"id\"))")));
  }
}
