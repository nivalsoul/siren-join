package solutions.siren.join.action.terms.collector;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntScatterSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import solutions.siren.join.action.terms.TermsByQueryRequest;
import solutions.siren.join.index.query.FieldDataTermsQueryHelper;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

public class IntegerTermsSet extends TermsSet {

  private transient IntHashSet set;

  /**
   * The size of the header: four bytes for the terms encoding ordinal,
   * 1 byte for the {@link #isPruned} flag, and four bytes for the size.
   */
  private static final int HEADER_SIZE = 9;

  private static final ESLogger logger = Loggers.getLogger(IntegerTermsSet.class);

  /**
   * Default constructor
   */
  public IntegerTermsSet() {}

  public IntegerTermsSet(int expectedElements) {
    this.set = new IntHashSet(expectedElements);
  }

  /**
   * Constructor based on a byte array containing the encoded set of terms.
   * Used in {@link solutions.siren.join.index.query.FieldDataTermsQuery}.
   */
  public IntegerTermsSet(BytesRef bytes) {
    this.readFromBytes(bytes);
  }

  @Override
  public void add(long term) {
    this.set.add((int) term);
  }

  @Override
  public boolean contains(long term) {
    return this.set.contains((int) term);
  }

  @Override
  protected void addAll(TermsSet terms) {
    if (!(terms instanceof IntegerTermsSet)) {
      throw new UnsupportedOperationException("Invalid type: IntegerTermsSet expected.");
    }
    this.set.addAll(((IntegerTermsSet) terms).set);
  }

  @Override
  public int size() {
    return this.set.size();
  }

  @Override
  public void readFrom(StreamInput in) throws IOException {
    this.setIsPruned(in.readBoolean());
    int size = in.readInt();
    set = new IntHashSet(size);
    for (long i = 0; i < size; i++) {
      set.add(in.readVInt());
    }
  }

  /**
   * Serialize the list of terms to the {@link StreamOutput}.
   * <br>
   * Given the low performance of {@link org.elasticsearch.common.io.stream.BytesStreamOutput} when writing a large number
   * of longs (5 to 10 times slower than writing directly to a byte[]), we use a small buffer of 8kb
   * to optimise the throughput. 8kb seems to be the optimal buffer size, larger buffer size did not improve
   * the throughput.
   *
   * @param out the output
   */
  @Override
  public void writeTo(StreamOutput out) throws IOException {
    // Encode flag
    out.writeBoolean(this.isPruned());

    // Encode size of list
    out.writeInt(set.size());

    // Encode ints
    BytesRef buffer = new BytesRef(new byte[1024 * 8]);
    Iterator<IntCursor> it = set.iterator();
    while (it.hasNext()) {
      FieldDataTermsQueryHelper.writeVInt(buffer, it.next().value);
      if (buffer.offset > buffer.bytes.length - 5) {
        out.write(buffer.bytes, 0, buffer.offset);
        buffer.offset = 0;
      }
    }

    // flush the remaining bytes from the buffer
    out.write(buffer.bytes, 0, buffer.offset);
  }

  @Override
  public BytesRef writeToBytes() {
    long start = System.nanoTime();

    int size = set.size();
    BytesRef bytesRef = new BytesRef(new byte[HEADER_SIZE + size * 5]);

    // Encode encoding type
    FieldDataTermsQueryHelper.writeInt(bytesRef, this.getEncoding().ordinal());

    // Encode flag
    bytesRef.bytes[bytesRef.offset++] = (byte) (this.isPruned() ? 1 : 0);

    // Encode size of list
    FieldDataTermsQueryHelper.writeInt(bytesRef, size);

    // Encode ints
    for (IntCursor i : set) {
      FieldDataTermsQueryHelper.writeVInt(bytesRef, i.value);
    }

    logger.debug("Serialized {} terms - took {} ms", this.size(), (System.nanoTime() - start) / 1000000);

    bytesRef.length = bytesRef.offset;
    bytesRef.offset = 0;
    return bytesRef;
  }

  private void readFromBytes(BytesRef bytesRef) {
    // Read pruned flag
    this.setIsPruned(bytesRef.bytes[bytesRef.offset++] == 1 ? true : false);

    // Read size fo the set
    int size = FieldDataTermsQueryHelper.readInt(bytesRef);

    // Read terms

    // Scatter set is slightly more efficient than the hash set, but should be used only for lookups,
    // not for merging
    set = new IntScatterSet(size);
    for (int i = 0; i < size; i++) {
      set.add(FieldDataTermsQueryHelper.readVInt(bytesRef));
    }
  }

  @Override
  public TermsByQueryRequest.TermsEncoding getEncoding() {
    return TermsByQueryRequest.TermsEncoding.INTEGER;
  }

}