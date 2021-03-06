/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.regionserver.compactions.Compactor;

// Shen Li
import org.apache.hadoop.hbase.util.Bytes;
/**
 * Store flusher interface. Turns a snapshot of memstore into a set of store files (usually one).
 * Custom implementation can be provided.
 */
@InterfaceAudience.Private
abstract class StoreFlusher {
  protected Configuration conf;
  protected Store store;

  // Shen Li: the 
  byte [] nextSplitRow;
  int splitKeyIndex;

  public StoreFlusher(Configuration conf, Store store) {
    this.conf = conf;
    this.store = store;
  }

  /**
   * Turns a snapshot of memstore into a set of store files.
   * @param snapshot Memstore snapshot.
   * @param cacheFlushSeqNum Log cache flush sequence number.
   * @param status Task that represents the flush operation and may be updated with status.
   * @return List of files written. Can be empty; must not be null.
   */
  public abstract List<Path> flushSnapshot(MemStoreSnapshot snapshot, long cacheFlushSeqNum,
      MonitoredTask status) throws IOException;

  protected void finalizeWriter(StoreFile.Writer writer, long cacheFlushSeqNum,
      MonitoredTask status) throws IOException {
    // Write out the log sequence number that corresponds to this output
    // hfile. Also write current time in metadata as minFlushTime.
    // The hfile is current up to and including cacheFlushSeqNum.
    status.setStatus("Flushing " + store + ": appending metadata");
    writer.appendMetadata(cacheFlushSeqNum, false);
    status.setStatus("Flushing " + store + ": closing flushed file");
    writer.close();
  }


  /**
   * Creates the scanner for flushing snapshot. Also calls coprocessors.
   * @param snapshotScanner
   * @param smallestReadPoint
   * @return The scanner; null if coprocessor is canceling the flush.
   */
  protected InternalScanner createScanner(KeyValueScanner snapshotScanner,
      long smallestReadPoint) throws IOException {
    InternalScanner scanner = null;
    if (store.getCoprocessorHost() != null) {
      scanner = store.getCoprocessorHost().preFlushScannerOpen(store, snapshotScanner);
    }
    if (scanner == null) {
      Scan scan = new Scan();
      scan.setMaxVersions(store.getScanInfo().getMaxVersions());
      scanner = new StoreScanner(store, store.getScanInfo(), scan,
          Collections.singletonList(snapshotScanner), ScanType.COMPACT_RETAIN_DELETES,
          smallestReadPoint, HConstants.OLDEST_TIMESTAMP);
    }
    assert scanner != null;
    if (store.getCoprocessorHost() != null) {
      try {
        return store.getCoprocessorHost().preFlush(store, scanner);
      } catch (IOException ioe) {
        scanner.close();
        throw ioe;
      }
    }
    return scanner;
  }

  /**
   * Shen Li
   */
  protected boolean shouldSeal(KeyValue kv) {
    // TODO: compare kv with HRegionInfo.splitKeys
    store.getRegionInfo();

    byte [] row = kv.getRow();
    if (null != nextSplitRow && 
        Bytes.compareTo(nextSplitRow, row) <= 0) {
      nextSplitRow = store.getRegionInfo().getSplitKey(++splitKeyIndex);
      return true;
    }
    return false;
  }

  /**
   * Shen Li
   */
  protected String getReplicaNamespace() {
    return store.getRegionInfo().getReplicaNamespace();
  }

  /**
   * Shen Li
   */
  protected String [] getReplicaGroups() {
    return store.getRegionInfo().getReplicaGroups(splitKeyIndex);
  }

  /**
   * Performs memstore flush, writing data from scanner into sink.
   * @param scanner Scanner to get data from.
   * @param sink Sink to write data to. Could be StoreFile.Writer.
   * @param smallestReadPoint Smallest read point used for the flush.
   */
  protected void performFlush(InternalScanner scanner,
      Compactor.CellSink sink, long smallestReadPoint) throws IOException {
    int compactionKVMax =
      conf.getInt(HConstants.COMPACTION_KV_MAX, HConstants.COMPACTION_KV_MAX_DEFAULT);
    List<Cell> kvs = new ArrayList<Cell>();

    // Shen Li: init nextSplitRow
    splitKeyIndex = 0;
    nextSplitRow = store.getRegionInfo().getSplitKey(splitKeyIndex);

    boolean hasMore;
    do {
      hasMore = scanner.next(kvs, compactionKVMax);
      if (!kvs.isEmpty()) {
        for (Cell c : kvs) {
          // If we know that this KV is going to be included always, then let us
          // set its memstoreTS to 0. This will help us save space when writing to
          // disk.
          KeyValue kv = KeyValueUtil.ensureKeyValue(c);
          if (kv.getMvccVersion() <= smallestReadPoint) {
            // let us not change the original KV. It could be in the memstore
            // changing its memstoreTS could affect other threads/scanners.
            kv = kv.shallowCopy();
            kv.setMvccVersion(0);
          }
          // Shen Li: TODO check split boundary. use Store, if exceed boundary,
          // call Store to seal block and reset replica group
          //
          // sink is a instance of StoreFile.Writer which has a
          // HFile.Writer as a member variable
          //
          // HFile.Writer has a FSDataOutputStream member variable
          // which can do seal, and set replica group operations.
          //
          if (shouldSeal(kv)) {
            // the sealCurBlock will flush buffer before seal block
            sink.sealCurBlock();
            sink.setReplicaGroups(getReplicaNamespace(), 
                                  getReplicaGroups());
          }
          sink.append(kv);
        }
        kvs.clear();
      }
    } while (hasMore);
  }
}
