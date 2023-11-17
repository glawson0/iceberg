/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.source.SerializableTableWithSize;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestTableSerialization {

  private static final HadoopTables TABLES = new HadoopTables();

  private static final Schema SCHEMA =
      new Schema(
          required(1, "id", Types.LongType.get()),
          optional(2, "data", Types.StringType.get()),
          required(3, "date", Types.StringType.get()),
          optional(4, "double", Types.DoubleType.get()));

  private static final PartitionSpec SPEC =
      PartitionSpec.builderFor(SCHEMA).identity("date").build();

  private static final SortOrder SORT_ORDER = SortOrder.builderFor(SCHEMA).asc("id").build();

  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private Table table;

  @Before
  public void initTable() throws IOException {
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");

    File tableLocation = temp.newFolder();
    Assert.assertTrue(tableLocation.delete());

    this.table = TABLES.create(SCHEMA, SPEC, SORT_ORDER, props, tableLocation.toString());
  }

  @Test
  public void testCloseSerializableTableKryoSerialization() throws Exception {
    for (Table tbl : tables()) {
      Table spyTable = spy(tbl);
      FileIO spyIO = spy(tbl.io());
      when(spyTable.io()).thenReturn(spyIO);

      Table serializableTable = SerializableTableWithSize.copyOf(spyTable);

      Table serializableTableCopy = spy(KryoHelpers.roundTripSerialize(serializableTable));
      FileIO spyFileIOCopy = spy(serializableTableCopy.io());
      when(serializableTableCopy.io()).thenReturn(spyFileIOCopy);

      ((AutoCloseable) serializableTable).close(); // mimics close on the driver
      ((AutoCloseable) serializableTableCopy).close(); // mimics close on executors

      verify(spyIO, never()).close();
      verify(spyFileIOCopy, times(1)).close();
    }
  }

  @Test
  public void testCloseSerializableTableJavaSerialization() throws Exception {
    for (Table tbl : tables()) {
      Table spyTable = spy(tbl);
      FileIO spyIO = spy(tbl.io());
      when(spyTable.io()).thenReturn(spyIO);

      Table serializableTable = SerializableTableWithSize.copyOf(spyTable);

      Table serializableTableCopy = spy(TestHelpers.roundTripSerialize(serializableTable));
      FileIO spyFileIOCopy = spy(serializableTableCopy.io());
      when(serializableTableCopy.io()).thenReturn(spyFileIOCopy);

      ((AutoCloseable) serializableTable).close(); // mimics close on the driver
      ((AutoCloseable) serializableTableCopy).close(); // mimics close on executors

      verify(spyIO, never()).close();
      verify(spyFileIOCopy, times(1)).close();
    }
  }

  @Test
  public void testSerializableTableKryoSerialization() throws IOException {
    Table serializableTable = SerializableTableWithSize.copyOf(table);
    TestHelpers.assertSerializedAndLoadedMetadata(
        table, KryoHelpers.roundTripSerialize(serializableTable));
  }

  @Test
  public void testSerializableMetadataTableKryoSerialization() throws IOException {
    for (MetadataTableType type : MetadataTableType.values()) {
      TableOperations ops = ((HasTableOperations) table).operations();
      Table metadataTable =
          MetadataTableUtils.createMetadataTableInstance(ops, table.name(), "meta", type);
      Table serializableMetadataTable = SerializableTableWithSize.copyOf(metadataTable);

      TestHelpers.assertSerializedAndLoadedMetadata(
          metadataTable, KryoHelpers.roundTripSerialize(serializableMetadataTable));
    }
  }

  @Test
  public void testSerializableTransactionTableKryoSerialization() throws IOException {
    Transaction txn = table.newTransaction();

    txn.updateProperties().set("k1", "v1").commit();

    Table txnTable = txn.table();
    Table serializableTxnTable = SerializableTableWithSize.copyOf(txnTable);

    TestHelpers.assertSerializedMetadata(
        txnTable, KryoHelpers.roundTripSerialize(serializableTxnTable));
  }

  private List<Table> tables() {
    List<Table> tables = Lists.newArrayList();

    tables.add(table);

    for (MetadataTableType type : MetadataTableType.values()) {
      Table metadataTable = MetadataTableUtils.createMetadataTableInstance(table, type);
      tables.add(metadataTable);
    }

    return tables;
  }
}
