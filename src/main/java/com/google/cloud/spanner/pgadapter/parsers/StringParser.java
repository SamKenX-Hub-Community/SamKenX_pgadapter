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

package com.google.cloud.spanner.pgadapter.parsers;

import com.google.api.core.InternalApi;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;

/** Translate from wire protocol to string. */
@InternalApi
public class StringParser extends Parser<String> {

  StringParser(ResultSet item, int position) {
    this.item = item.getString(position);
  }

  StringParser(Object item) {
    this.item = (String) item;
  }

  StringParser(byte[] item, FormatCode formatCode) {
    if (item != null) {
      this.item = toString(item);
    }
  }

  /** Converts the binary data to an UTF8 string. */
  public static String toString(@Nonnull byte[] data) {
    return new String(data, UTF8);
  }

  @Override
  public String stringParse() {
    return this.item;
  }

  @Override
  protected byte[] binaryParse() {
    return this.item == null ? null : this.item.getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] convertToPG(ResultSet resultSet, int position) {
    return resultSet.getString(position).getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] binaryParse(ResultSet resultSet, int position) {
    return resultSet.isNull(position)
        ? null
        : resultSet.getString(position).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void bind(Statement.Builder statementBuilder, String name) {
    statementBuilder.bind(name).to(this.item);
  }
}
