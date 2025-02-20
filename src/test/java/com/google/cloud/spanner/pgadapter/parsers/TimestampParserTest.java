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

package com.google.cloud.spanner.pgadapter.parsers;

import static com.google.cloud.spanner.pgadapter.parsers.Parser.PG_EPOCH_SECONDS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.ResultSets;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.StructField;
import com.google.cloud.spanner.pgadapter.ProxyServer.DataFormat;
import com.google.cloud.spanner.pgadapter.error.PGException;
import com.google.cloud.spanner.pgadapter.parsers.Parser.FormatCode;
import com.google.cloud.spanner.pgadapter.session.SessionState;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.postgresql.util.ByteConverter;

@RunWith(JUnit4.class)
public class TimestampParserTest {

  @Test
  public void testToTimestamp() {
    long micros = new Random().nextLong();
    if (micros < -62135596800000L) {
      micros = -62135596800000L;
    } else if (micros > 253402300799000L) {
      micros = 253402300799000L;
    }
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, micros - PG_EPOCH_SECONDS * 1000_000L);
    assertEquals(Timestamp.ofTimeMicroseconds(micros), TimestampParser.toTimestamp(data));

    SpannerException spannerException =
        assertThrows(SpannerException.class, () -> TimestampParser.toTimestamp(new byte[4]));
    assertEquals(ErrorCode.INVALID_ARGUMENT, spannerException.getErrorCode());

    assertArrayEquals(
        data,
        new TimestampParser(TimestampParser.toTimestamp(data), mock(SessionState.class))
            .binaryParse());
    assertNull(new TimestampParser(null, mock(SessionState.class)).binaryParse());
  }

  @Test
  public void testSpannerParse() {
    assertEquals(
        "2022-07-08T07:22:59.123456789Z",
        new TimestampParser(
                "2022-07-08 07:22:59.123456789+00".getBytes(StandardCharsets.UTF_8),
                FormatCode.TEXT,
                mock(SessionState.class))
            .spannerParse());
    assertNull(new TimestampParser(null, mock(SessionState.class)).spannerParse());

    ResultSet resultSet =
        ResultSets.forRows(
            Type.struct(StructField.of("ts", Type.timestamp())),
            ImmutableList.of(
                Struct.newBuilder()
                    .set("ts")
                    .to(Timestamp.parseTimestamp("2022-07-08T07:22:59.123456789Z"))
                    .build()));
    resultSet.next();
    assertArrayEquals(
        "2022-07-08T07:22:59.123456789Z".getBytes(StandardCharsets.UTF_8),
        TimestampParser.convertToPG(resultSet, 0, DataFormat.SPANNER, ZoneId.of("UTC")));
  }

  @Test
  public void testStringParse() {
    SessionState sessionState = mock(SessionState.class);
    when(sessionState.getTimezone()).thenReturn(ZoneId.of("+00"));
    assertEquals(
        "2022-07-08 07:22:59.123456+00",
        new TimestampParser(
                Timestamp.parseTimestamp("2022-07-08T07:22:59.123456789Z"), sessionState)
            .stringParse());
    assertNull(new TimestampParser(null, sessionState).stringParse());
    assertThrows(
        PGException.class,
        () ->
            new TimestampParser(
                "foo".getBytes(StandardCharsets.UTF_8), FormatCode.TEXT, sessionState));
  }

  @Test
  public void testTextToTimestamp() {
    assertEquals(
        Timestamp.parseTimestamp("2022-10-09T19:09:18Z"),
        TimestampParser.toTimestamp("2022-10-09 10:09:18", ZoneId.of("-09:00")));
    assertEquals(
        Timestamp.parseTimestamp("2022-12-28T09:00Z"),
        TimestampParser.toTimestamp("2022-12-28 10:00", ZoneId.of("CET")));
    assertEquals(
        Timestamp.parseTimestamp("2022-08-28T08:00Z"),
        TimestampParser.toTimestamp("2022-08-28 10:00", ZoneId.of("CET")));
    assertEquals(
        Timestamp.parseTimestamp("2022-08-28T08:00Z"),
        TimestampParser.toTimestamp("2022-08-28 10:00", ZoneId.of("Europe/Amsterdam")));
    assertEquals(
        Timestamp.parseTimestamp("2022-12-27T14:00:00Z"),
        TimestampParser.toTimestamp("2022-12-28", ZoneId.of("+10:00")));
    assertEquals(
        Timestamp.parseTimestamp("2022-12-28T08:00Z"),
        TimestampParser.toTimestamp("2022-12-28 10:00+02:00", ZoneId.of("CET")));
    assertEquals(
        Timestamp.parseTimestamp("2022-12-28T07:30Z"),
        TimestampParser.toTimestamp("2022-12-28 10:00+02:30", ZoneId.of("CET")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("(\"2011-11-04 00:05:23.123456+00:00\")", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("('2011-11-04 00:05:23.123456+00:00')", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("'2011-11-04 00:05:23.123456+00:00'", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp("", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp("(", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp(")", ZoneId.of("UTC")));
    assertThrows(
        PGException.class,
        () -> TimestampParser.toTimestamp("'2011-11-04 00:05:23.123456+00:00')", ZoneId.of("UTC")));
    assertThrows(
        PGException.class,
        () -> TimestampParser.toTimestamp("('2011-11-04 00:05:23.123456+00:00'", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp("()", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp("''", ZoneId.of("UTC")));
    assertThrows(PGException.class, () -> TimestampParser.toTimestamp("'2000'", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2000-01-01T00:00:00Z"),
        TimestampParser.toTimestamp("'2000-01-01'", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp(" (\"2011-11-04 00:05:23.123456+00:00\")", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("(\"2011-11-04 00:05:23.123456+00:00\") ", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("( \"2011-11-04 00:05:23.123456+00:00\" )", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp("(\" 2011-11-04 00:05:23.123456+00:00\")", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp(
            "\n(  \"2011-11-04 00:05:23.123456+00:00  \" )", ZoneId.of("UTC")));
    assertEquals(
        Timestamp.parseTimestamp("2011-11-04T00:05:23.123456Z"),
        TimestampParser.toTimestamp(
            "\t\n( \"  2011-11-04 00:05:23.123456+00:00  \n\t\" )", ZoneId.of("UTC")));
  }
}
