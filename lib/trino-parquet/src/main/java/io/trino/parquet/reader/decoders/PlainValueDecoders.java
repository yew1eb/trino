/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.parquet.reader.decoders;

import io.airlift.slice.Slices;
import io.trino.parquet.reader.SimpleSliceInputStream;
import io.trino.parquet.reader.flat.BitPackingUtils;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import org.apache.parquet.column.ColumnDescriptor;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.parquet.ParquetReaderUtils.toByteExact;
import static io.trino.parquet.ParquetReaderUtils.toShortExact;
import static io.trino.parquet.ParquetTypeUtils.checkBytesFitInShortDecimal;
import static io.trino.parquet.ParquetTypeUtils.getShortDecimalValue;
import static io.trino.parquet.reader.flat.BitPackingUtils.unpack;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

public final class PlainValueDecoders
{
    private PlainValueDecoders() {}

    public static final class LongPlainValueDecoder
            implements ValueDecoder<long[]>
    {
        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(long[] values, int offset, int length)
        {
            input.readBytes(Slices.wrappedLongArray(values), offset * Long.BYTES, length * Long.BYTES);
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * Long.BYTES);
        }
    }

    public static final class IntPlainValueDecoder
            implements ValueDecoder<int[]>
    {
        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(int[] values, int offset, int length)
        {
            input.readBytes(Slices.wrappedIntArray(values), offset * Integer.BYTES, length * Integer.BYTES);
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * Integer.BYTES);
        }
    }

    public static final class IntToLongPlainValueDecoder
            implements ValueDecoder<long[]>
    {
        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(long[] values, int offset, int length)
        {
            input.ensureBytesAvailable(Integer.BYTES * length);
            int endOffset = offset + length;
            for (int i = offset; i < endOffset; i++) {
                values[i] = input.readIntUnsafe();
            }
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * Integer.BYTES);
        }
    }

    public static final class IntToShortPlainValueDecoder
            implements ValueDecoder<short[]>
    {
        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(short[] values, int offset, int length)
        {
            input.ensureBytesAvailable(Integer.BYTES * length);
            int endOffset = offset + length;
            for (int i = offset; i < endOffset; i++) {
                values[i] = toShortExact(input.readIntUnsafe());
            }
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * Integer.BYTES);
        }
    }

    public static final class IntToBytePlainValueDecoder
            implements ValueDecoder<byte[]>
    {
        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(byte[] values, int offset, int length)
        {
            input.ensureBytesAvailable(Integer.BYTES * length);
            int endOffset = offset + length;
            for (int i = offset; i < endOffset; i++) {
                values[i] = toByteExact(input.readIntUnsafe());
            }
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * Integer.BYTES);
        }
    }

    public static final class BooleanPlainValueDecoder
            implements ValueDecoder<byte[]>
    {
        private SimpleSliceInputStream input;
        // Number of unread bits in the current byte
        private int alreadyReadBits;
        // Partly read byte
        private byte partiallyReadByte;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
            alreadyReadBits = 0;
        }

        @Override
        public void read(byte[] values, int offset, int length)
        {
            if (alreadyReadBits != 0) { // Use partially unpacked byte
                int bitsRemaining = Byte.SIZE - alreadyReadBits;
                int chunkSize = min(bitsRemaining, length);
                unpack(values, offset, partiallyReadByte, alreadyReadBits, alreadyReadBits + chunkSize);
                alreadyReadBits = (alreadyReadBits + chunkSize) % Byte.SIZE; // Set to 0 when full byte reached
                if (length == chunkSize) {
                    return;
                }
                offset += chunkSize;
                length -= chunkSize;
            }

            // Read full bytes
            int bytesToRead = length / Byte.SIZE;
            while (bytesToRead >= Long.BYTES) {
                long packedLong = input.readLong();
                BitPackingUtils.unpack64FromLong(values, offset, packedLong);
                bytesToRead -= Long.BYTES;
                offset += Long.SIZE;
            }
            while (bytesToRead >= Byte.BYTES) {
                byte packedByte = input.readByte();
                BitPackingUtils.unpack8FromByte(values, offset, packedByte);
                bytesToRead -= Byte.BYTES;
                offset += Byte.SIZE;
            }

            // Partially read the last byte
            alreadyReadBits = length % Byte.SIZE;
            if (alreadyReadBits != 0) {
                partiallyReadByte = input.readByte();
                unpack(values, offset, partiallyReadByte, 0, alreadyReadBits);
            }
        }

        @Override
        public void skip(int n)
        {
            if (alreadyReadBits != 0) { // Skip the partially read byte
                int chunkSize = min(Byte.SIZE - alreadyReadBits, n);
                n -= chunkSize;
                alreadyReadBits = (alreadyReadBits + chunkSize) % Byte.SIZE; // Set to 0 when full byte reached
            }

            // Skip full bytes
            input.skip(n / Byte.SIZE);

            if (n % Byte.SIZE != 0) { // Partially skip the last byte
                alreadyReadBits = n % Byte.SIZE;
                partiallyReadByte = input.readByte();
            }
        }
    }

    public static final class ShortDecimalFixedLengthByteArrayDecoder
            implements ValueDecoder<long[]>
    {
        private final int typeLength;
        private final DecimalType decimalType;
        private final ColumnDescriptor descriptor;
        private final ShortDecimalFixedWidthByteArrayBatchDecoder decimalValueDecoder;

        private SimpleSliceInputStream input;

        public ShortDecimalFixedLengthByteArrayDecoder(DecimalType decimalType, ColumnDescriptor descriptor)
        {
            checkArgument(decimalType.isShort(), "Decimal type %s is not a short decimal", decimalType);
            this.decimalType = decimalType;
            this.descriptor = requireNonNull(descriptor, "descriptor is null");
            this.typeLength = descriptor.getPrimitiveType().getTypeLength();
            checkArgument(typeLength > 0 && typeLength <= 16, "Expected column %s to have type length in range (1-16)", descriptor);
            this.decimalValueDecoder = new ShortDecimalFixedWidthByteArrayBatchDecoder(Math.min(typeLength, Long.BYTES));
        }

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(long[] values, int offset, int length)
        {
            input.ensureBytesAvailable(typeLength * length);
            if (typeLength <= Long.BYTES) {
                decimalValueDecoder.getShortDecimalValues(input, values, offset, length);
                return;
            }
            int extraBytesLength = typeLength - Long.BYTES;
            byte[] inputBytes = input.getByteArray();
            int inputBytesOffset = input.getByteArrayOffset();
            for (int i = offset; i < offset + length; i++) {
                checkBytesFitInShortDecimal(inputBytes, inputBytesOffset, extraBytesLength, decimalType, descriptor);
                values[i] = getShortDecimalValue(inputBytes, inputBytesOffset + extraBytesLength, Long.BYTES);
                inputBytesOffset += typeLength;
            }
            input.skip(length * typeLength);
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * typeLength);
        }
    }

    public static final class LongDecimalPlainValueDecoder
            implements ValueDecoder<long[]>
    {
        private final int typeLength;
        private final byte[] inputBytes;

        private SimpleSliceInputStream input;

        public LongDecimalPlainValueDecoder(int typeLength)
        {
            checkArgument(typeLength > 0 && typeLength <= 16, "typeLength %s should be in range (1-16) for a long decimal", typeLength);
            this.typeLength = typeLength;
            this.inputBytes = new byte[typeLength];
        }

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(long[] values, int offset, int length)
        {
            int endOffset = (offset + length) * 2;
            for (int currentOutputOffset = offset * 2; currentOutputOffset < endOffset; currentOutputOffset += 2) {
                input.readBytes(Slices.wrappedBuffer(inputBytes), 0, typeLength);
                Int128 value = Int128.fromBigEndian(inputBytes);
                values[currentOutputOffset] = value.getHigh();
                values[currentOutputOffset + 1] = value.getLow();
            }
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * typeLength);
        }
    }

    public static final class UuidPlainValueDecoder
            implements ValueDecoder<long[]>
    {
        private static final int UUID_SIZE = 16;

        private SimpleSliceInputStream input;

        @Override
        public void init(SimpleSliceInputStream input)
        {
            this.input = requireNonNull(input, "input is null");
        }

        @Override
        public void read(long[] values, int offset, int length)
        {
            int endOffset = (offset + length) * 2;
            for (int currentOutputOffset = offset * 2; currentOutputOffset < endOffset; currentOutputOffset += 2) {
                values[currentOutputOffset] = input.readLong();
                values[currentOutputOffset + 1] = input.readLong();
            }
        }

        @Override
        public void skip(int n)
        {
            input.skip(n * UUID_SIZE);
        }
    }
}
