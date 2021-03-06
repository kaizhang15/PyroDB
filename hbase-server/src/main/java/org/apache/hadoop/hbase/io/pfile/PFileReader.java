package org.apache.hadoop.hbase.io.pfile;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;

import org.apache.hadoop.hbase.io.hfile.HFileReaderV2;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileBlock;
import org.apache.hadoop.hbase.io.hfile.HFile.Writer;
import org.apache.hadoop.hbase.io.hfile.FixedFileTrailer;
import org.apache.hadoop.hbase.io.FSDataInputStreamWrapper;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.util.Bytes;

/*
 * Any component above this layer sees only HFileBlock. Skiplists and 
 * multi-block-reading functionality are made transparent to higher level
 * components.
 *
 * It seems that I do not need to implement PFileBlockReader, as all seek
 * are doen in HFileReaderV2.blockSeek(Cell Key, boolean seekBefore).
 *
 * So, rewrite that should be suffice for get. 
 *
 * TODO: study how the reader works for scan, do I need to rewrite the scan
 * portion?
 */

public class PFileReader extends HFileReaderV2 {
  private static final Log LOG = LogFactory.getLog(PFileReader.class);

  private static boolean indexOutOfBound = false;

  public PFileReader(final Path path, final FixedFileTrailer trailer,
      final FSDataInputStreamWrapper fsdis, final long size,
      final CacheConfig cacheConf, final HFileSystem hfs,
      final Configuration conf) throws IOException {
    super(path, trailer, fsdis, size, cacheConf, hfs, conf);
    indexOutOfBound = false;
  }

  @Override
  public int getMajorVersion() {
    return 4;
  }

  @Override
  public HFileScanner getScanner(boolean cacheBlocks, final boolean pread,
                        final boolean isCompaction) {
    if (dataBlockEncoder.useEncodedScanner()) {
      throw new IllegalStateException("Shen Li: PFileScanner does "
          + " not support encoded scanner for now");
    }
    return new PFileScanner(this, cacheBlocks, pread, isCompaction);
  }

  public static class PFileScanner extends HFileReaderV2.ScannerV2 {
    private static final Log LOG = LogFactory.getLog(PFileScanner.class);

    public static final int KEY_LEN_SIZE = Bytes.SIZEOF_INT;
    public static final int MAX_INT = 2147483647;

    //TODO: take care of the following two variables
    private int currSkipListEntryLen = 0;
    private int currPNum = 0;
    private long tmpMemstoreTS = 0;
    private int tmpMemstoreTSLen = 0;

    protected PFileReader reader = null;

    public PFileScanner(PFileReader r, boolean cacheBlocks,
        final boolean pread, final boolean isCompaction) {
      super(r, cacheBlocks, pread, isCompaction);
      this.reader = r;
    }

    private int getKvPos(int pkvPos, int pNum) {
      return pkvPos + PKeyValue.POINTER_NUM_SIZE +
        (pNum + 1) * PKeyValue.POINTER_SIZE;
    }

    @Override
    public Cell getKeyValue() {
      //LOG.info("Shen Li: PFile getKeyValue");
      //String curTrace = "";
      //for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
      //  curTrace += (ste + "\n");
      //}
      //LOG.info(curTrace);
      if (!isSeeked())
        return null;
      
      KeyValue ret = new KeyValue(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen, getCellBufSize());
      
      if (this.reader.shouldIncludeMemstoreTS()) {
        ret.setMvccVersion(currMemstoreTS);
      }

      //LOG.info("Shen Li: key value read! " + ret.getKeyString());

      return ret;
    }

    @Override
    protected int getCellBufSize() {
      // should not include currSkipListEntryLen here as this is called
      // when reading a KeyValue rather than a PKeyValue
      return KEY_VALUE_LEN_SIZE
             + currKeyLen + currValueLen + currMemstoreTSLen;
    }

    @Override
    public ByteBuffer getKey() {
      assertSeeked();
      return ByteBuffer.wrap(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE, 
          currKeyLen).slice();
    }

    @Override
    public ByteBuffer getValue() {
      assertSeeked();
      return ByteBuffer.wrap(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE
          + currKeyLen, currValueLen).slice();
    }

    @Override
    protected void setNonSeekedState() {
      block = null;
      blockBuffer = null;
      currPNum = 0;
      currSkipListEntryLen = 0;
      currKeyLen = 0;
      currValueLen = 0;
      currMemstoreTS = 0;
      currMemstoreTSLen = 0;
      tmpMemstoreTS = 0;
      tmpMemstoreTSLen = 0;
    }

    @Override
      protected void readKeyValueLen() {
        blockBuffer.mark();
        //LOG.info("Shen Li: blockBuffer position = " + blockBuffer.position()
        //         + ", limit = " + blockBuffer.limit()
        //         + ", arrayOffset = " + blockBuffer.arrayOffset()
        //         + ", lastSkipListEntryLen = " + currSkipListEntryLen
        //         + ", lastKeyLen = " + currKeyLen
        //         + ", lastValueLen = " + currValueLen
        //         + ", remaining = " + blockBuffer.remaining());
        currPNum = blockBuffer.get();
        currSkipListEntryLen =
          PKeyValue.POINTER_NUM_SIZE + 
          (currPNum + 1) * PKeyValue.POINTER_SIZE;
        blockBuffer.position(
            blockBuffer.position() + currSkipListEntryLen
            - PKeyValue.POINTER_NUM_SIZE);
        currKeyLen = blockBuffer.getInt();
        currValueLen = blockBuffer.getInt();

        readMvccVersion();

        if (currPNum < 0 || currKeyLen < 0 || currValueLen < 0
            || currKeyLen > blockBuffer.limit() 
            || currValueLen > blockBuffer.limit()
            || currSkipListEntryLen > blockBuffer.limit()) {
          throw new IllegalStateException(
              "Shen Li: Invalid currKeyLen " + currKeyLen
              + " or currValueLen " + currValueLen 
              + " or currPNum " + currPNum
              + " or currSkipListEntryLen " + currSkipListEntryLen
              + ". Block offset: " + block.getOffset()
              + ", block length: " + blockBuffer.limit()
              + ", position: " + blockBuffer.position()
              + " (without header).");
        }
        blockBuffer.reset();
      }

    @Override
      protected int getNextCellStartPosition() {
        return blockBuffer.position() + currSkipListEntryLen
          + KEY_VALUE_LEN_SIZE + currKeyLen + currValueLen + currMemstoreTSLen;
      }

    @Override
      protected ByteBuffer getFirstKeyInBlock(HFileBlock curBlock) {
        ByteBuffer buffer = curBlock.getBufferWithoutHeader();
        buffer.rewind();
        byte pNum = buffer.get();
        //LOG.info("Shen Li: PFileScanner.getFirstKeyInBlock called, pNum is " + pNum);
        buffer.position(buffer.position() + 
            (pNum + 1) * PKeyValue.POINTER_SIZE);
        int klen = buffer.getInt();
        buffer.getInt();
        ByteBuffer keyBuff = buffer.slice();
        keyBuff.limit(klen);
        keyBuff.rewind();
        return keyBuff;
      }

    @Override
      public String getKeyString() {
        return Bytes.toStringBinary(
            blockBuffer.array(),
            blockBuffer.arrayOffset() + blockBuffer.position() +
            currSkipListEntryLen + KEY_VALUE_LEN_SIZE, currKeyLen);
      }

    @Override
    public String getValueString() {
      return Bytes.toString(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position() +
          currSkipListEntryLen + KEY_VALUE_LEN_SIZE + currKeyLen,
          currValueLen);
    }

    // TODO: declare and set currSkipListEntryLen
    @Override
    public int compareKey(KVComparator comparator, Cell key) {
      return comparator.compareOnlyKeyPortion(
          key,
          new KeyValue.KeyOnlyKeyValue(
            blockBuffer.array(), 
            blockBuffer.arrayOffset() + blockBuffer.position() +
            currSkipListEntryLen + KEY_VALUE_LEN_SIZE, 
            currKeyLen));
    }


    @Override
    public int compareKey(KVComparator comparator, byte[] key, 
                          int offset, int length) {
      return comparator.compareFlatKey(
          key, offset, length,
          blockBuffer.array(), 
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE,
          currKeyLen);
    }

    private void readMemstoreTS(int offset) {
      if (this.reader.shouldIncludeMemstoreTS()) {
        if (this.reader.decodeMemstoreTS) {
          try {
            tmpMemstoreTS = Bytes.readVLong(
                blockBuffer.array(), offset);
            tmpMemstoreTSLen = 
              WritableUtils.getVIntSize(tmpMemstoreTS);
          } catch (Exception e) {
            throw new RuntimeException(
                "Error reading memstore timestamp", e);
          }
        } else {
          tmpMemstoreTS = 0;
          tmpMemstoreTSLen = 1;
        }
      }
    }

    private void setCurrStates(byte pNum, int skipListEntryLen,
        int klen, int vlen, long memstoreTS, int memstoreTSLen) {
      currPNum = pNum;
      currSkipListEntryLen = skipListEntryLen;
      currKeyLen = klen;
      currValueLen = vlen;
      currMemstoreTS = memstoreTS;
      currMemstoreTSLen = memstoreTSLen;
    }

    /*
     * TODO: try the next pkv first, accelerate the sequential scan
     */
    @Override
      protected int blockSeek(Cell key, boolean seekBefore) {
        int klen, vlen, skipKLen;
        int kvPos, skipKvPos;
        byte pNum, tmpPNum, skipPNum;
        int ptr, skipPrevPtr;
        int lastKeyValueSize = -1;
        int curPos, skipPos, ptrPos, skipPrevPos;


        //for testing

        //int nKcmp = 0;

        //LOG.info("Shen Li: call trace");
        //String curTrace = "";
        //for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
        //      curTrace += (ste + "\n");
        //}
        //LOG.info(curTrace);


        tmpMemstoreTS = 0;
        tmpMemstoreTSLen = 0;

        KeyValue.KeyOnlyKeyValue keyOnlyKv = new KeyValue.KeyOnlyKeyValue();

        // If the target key is smaller than the first pkv in the
        // current block, return -2 (HConstants.INDEX_KEY_MAGIC)

        curPos = blockBuffer.position();
        pNum = blockBuffer.get(curPos);
        kvPos = this.getKvPos(curPos, pNum);
        // key length of that kv
        //LOG.info("Shen Li: klen set by first keey in seek");
        klen = blockBuffer.getInt(kvPos);
        keyOnlyKv.setKey(blockBuffer.array(), 
            blockBuffer.arrayOffset() + kvPos + KEY_VALUE_LEN_SIZE, klen);

        int comp = reader.getComparator().compareOnlyKeyPortion(key, 
            keyOnlyKv);
        //++nKcmp;

        //LOG.info("Shen Li: comp " + comp);

        if (comp < 0) {
          // target key smaller than the first key
          //LOG.info("Shen Li: blockSeek called on a larger block. "
          //         + "getMinorVersion() = " 
          //         + this.reader.trailer.getMinorVersion());
          readKeyValueLen();
          if (this.reader.trailer.getMinorVersion() 
              >= MINOR_VERSION_WITH_FAKED_KEY) {
            return HConstants.INDEX_KEY_MAGIC;
          }
          return 1;
        }

        // the target key is within the range of the pointers of the 
        // current entry
        boolean found;

        // helps search in the skiplist
        int maxPos = this.MAX_INT;

        // initialize variables in case the block contains a single
        // pkv, whose key is smaller than the target key. 
        skipPos = curPos;
        ptr = 0;
        //LOG.info("Shen Li: skipKLen set by klen");
        skipKLen = klen;
        skipKvPos = 0;

        /*
         * Invariant: the current key under while has to be smaller than the 
         * target key. The loop over the current skiplist entry will return if
         * found an exact match, otherwise set the current key to the largest key
         * that is smaller than the target key in its skiplist pointers.
         */
        while(true) {

          // offset to the largest pointer
          ptrPos = curPos + PKeyValue.POINTER_NUM_SIZE +
            (pNum - 1) * PKeyValue.POINTER_SIZE;

          found = false;
          // check pointers of the current entry
          while (ptrPos > curPos) {
            ptr = blockBuffer.getInt(ptrPos);
            // offset to the beginning of the pkv indicated by the pointer
            skipPos = curPos + ptr;
            if (skipPos >= maxPos) {
              ptrPos -= PKeyValue.POINTER_SIZE;
              continue;
            }
            // ptr num of that pkv
            skipPNum = blockBuffer.get(skipPos);
            // offset to the beginning of kv of that pkv
            skipKvPos = this.getKvPos(skipPos, skipPNum);

            //LOG.info("Shen Li: blockBuffer limit " + blockBuffer.limit()
            //         + ", arrayOffset " + blockBuffer.arrayOffset()
            //         + ", curPos " + curPos + ", ptr " + ptr
            //         + ", skipPos " + skipPos
            //         + ", skipPNum " + skipPNum
            //         + ", skipKvPos " + skipKvPos);
            // key length of that kv
            //LOG.info("Shen Li: skipKLen set by test skipList entry");
            skipKLen = blockBuffer.getInt(skipKvPos);
            keyOnlyKv.setKey(blockBuffer.array(), blockBuffer.arrayOffset() 
                + skipKvPos + KEY_VALUE_LEN_SIZE, skipKLen);

            comp = reader.getComparator().compareOnlyKeyPortion(key, 
                keyOnlyKv);
            //++nKcmp;
            //and writers.
            if (0 == comp) {
              //Found exact match
              //LOG.info("Shen Li: PFile nKcmp = " + nKcmp);
              return handleExactMatch(key, blockBuffer.position() + ptr, 
                  skipKvPos, seekBefore);
            } else if (comp < 0) {
              // larger than the target key, try the next smaller pointer
              ptrPos -= PKeyValue.POINTER_SIZE;    
              maxPos = skipPos;
            } else {
              // found the largest key that is smaller than the target key 
              // known by the current pkv, break
              found = true;
              break;
            }
          }

          //TODO: handle the last block

          if (!found) {
            // all pointers point to larger keys (or no pointer), 
            // and the curren tkey is smaller than the target key.

            //read vlen of the current key
            //LOG.info("Shen LI: is kvPos wrong? " 
            //    + " kvPos = " + kvPos + ", pNum = " + pNum 
            //    + ", curPos = " + curPos);
            vlen = blockBuffer.getInt(kvPos + KEY_LEN_SIZE);

            //read memstoreTS
            readMemstoreTS(blockBuffer.arrayOffset() + kvPos 
                           + KEY_VALUE_LEN_SIZE + klen + vlen);
            // check next pkv
            ptr = PKeyValue.POINTER_NUM_SIZE +
              (pNum + 1) * PKeyValue.POINTER_SIZE + 
              KEY_VALUE_LEN_SIZE + klen + vlen + tmpMemstoreTSLen;
            //LOG.info("Shen Li: position = " + blockBuffer.position()
            //         + ", curPos = " + curPos
            //         + ", pointer = " + ptr 
            //         + ", limit = " + blockBuffer.limit()
            //         + ", pNum = " + pNum
            //         + ", klen = " + klen + ", vlen = " + vlen
            //         + ", tmpMemstoreTSLen = " + tmpMemstoreTSLen);
            if (blockBuffer.position() + ptr >= blockBuffer.limit()) {
              // the current pkv is the last pkv in this block
              //LOG.info("Shen Li: got last pkv");
              setCurrStates(pNum, 
                  PKeyValue.POINTER_NUM_SIZE + (pNum + 1) * PKeyValue.POINTER_SIZE,
                  klen, vlen, tmpMemstoreTS, tmpMemstoreTSLen);
              return 1;
            }
            skipPos = curPos + ptr;
            skipPNum = blockBuffer.get(skipPos);
            skipKvPos = getKvPos(skipPos, skipPNum);
            //LOG.info("Shen Li: skipKLen set by test next kv");
            skipKLen = blockBuffer.getInt(skipKvPos);
            keyOnlyKv.setKey(blockBuffer.array(), blockBuffer.arrayOffset()
                + skipKvPos + KEY_VALUE_LEN_SIZE, skipKLen);
            comp = reader.getComparator().compareOnlyKeyPortion(key, 
                keyOnlyKv);
            if (0 == comp) {
              // next pkv matches target key
              //LOG.info("Shen Li: 1 PFile nKcmp = " + nKcmp);
              return handleExactMatch(key, blockBuffer.position() + ptr, 
                  skipKvPos, seekBefore);
            } else if (comp < 0) {
              // target key is larger than current but smaller than the next.
              // therefore, the current locaiton of blockBuffer is correct
              //LOG.info("Shen Li: 2 PFile nKcmp = " + nKcmp);
              setCurrStates(pNum, 
                  PKeyValue.POINTER_NUM_SIZE + (pNum + 1) * PKeyValue.POINTER_SIZE,
                  klen, vlen, tmpMemstoreTS, tmpMemstoreTSLen);
              return 1;
            } else {
              // target key is larger than next but smaller than the next of next
              // blockBuffer should be placed at the beginning of the next key
              //LOG.info("Shen Li: position = " + blockBuffer.position()
              //       + ", curPos = " + curPos
              //       + ", pointer = " + ptr 
              //       + ", limit = " + blockBuffer.limit());
              blockBuffer.position(blockBuffer.position() + ptr);
              readKeyValueLen();
              //LOG.info("Shen Li: 3 PFile nKcmp = " + nKcmp);
              return 1;
            }
          } else {
            // found a valid range, so update the current pkv info
            blockBuffer.position(blockBuffer.position() + ptr);
            curPos = skipPos;
            pNum = blockBuffer.get(curPos);
            //LOG.info("klen set by skipKLen");
            klen = skipKLen;
            kvPos = skipKvPos;
          }
        }
      }

    private int handleExactMatch(Cell key, int destPos, int kvPos, boolean seekBefore) {
      if (seekBefore) {
        int skipPrevPos = kvPos - PKeyValue.POINTER_SIZE;
        // note that this pointer is negtive value
        int skipPrevPtr = blockBuffer.getInt(skipPrevPos);

        if (skipPrevPtr >= 0) {
          KeyValue kv = KeyValueUtil.ensureKeyValue(key);
          //LOG.info("Shen Li: seekBefore exact match at the first key");
          throw new IllegalStateException("Shen Li: " +
              "blockSeek with seekBefore at the first key of the block: "
              + "key = " + Bytes.toStringBinary(kv.getKey(), 
                kv.getKeyOffset(), 
                kv.getKeyLength())
              + ", blockOffset = " + block.getOffset() + ", onDiskSize = "
              + block.getOnDiskSizeWithHeader());
        }

        // use the prev ptr to reset the position.
        blockBuffer.position(destPos + skipPrevPtr);
        readKeyValueLen();
        return 1;
      }

      blockBuffer.position(destPos);
      readKeyValueLen();
      return 0;
    }
  }
}
