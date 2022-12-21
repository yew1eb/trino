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

import io.trino.parquet.ParquetEncoding;
import io.trino.parquet.PrimitiveField;
import io.trino.parquet.reader.SimpleSliceInputStream;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import org.joda.time.DateTimeZone;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.parquet.reader.decoders.ValueDecoders.getInt96Decoder;
import static io.trino.parquet.reader.decoders.ValueDecoders.getLongDecoder;
import static io.trino.parquet.reader.flat.Int96ColumnAdapter.Int96Buffer;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.trino.spi.type.Timestamps.MILLISECONDS_PER_SECOND;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static io.trino.spi.type.Timestamps.round;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.util.Objects.requireNonNull;

/**
 * {@link io.trino.parquet.reader.decoders.ValueDecoder} implementations which build on top of implementations from {@link io.trino.parquet.reader.decoders.ValueDecoders}.
 * These decoders apply transformations to the output of an underlying primitive parquet type decoder to convert it into values
 * which can be used by {@link io.trino.parquet.reader.flat.ColumnAdapter} to create Trino blocks.
 */
public class TransformingValueDecoders
{
    private TransformingValueDecoders() {}

    public static ValueDecoder<long[]> getTimeMicrosDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        return new InlineTransformDecoder<>(
                getLongDecoder(encoding, field),
                (values, offset, length) -> {
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = values[i] * PICOSECONDS_PER_MICROSECOND;
                    }
                });
    }

    public static ValueDecoder<long[]> getInt96ToShortTimestampDecoder(ParquetEncoding encoding, PrimitiveField field, DateTimeZone timeZone)
    {
        checkArgument(
                field.getType() instanceof TimestampType timestampType && timestampType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampType) field.getType()).getPrecision();
        ValueDecoder<Int96Buffer> delegate = getInt96Decoder(encoding, field);
        return new ValueDecoder<>()
        {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(long[] values, int offset, int length)
            {
                Int96Buffer int96Buffer = new Int96Buffer(length);
                delegate.read(int96Buffer, 0, length);
                for (int i = 0; i < length; i++) {
                    long epochSeconds = int96Buffer.longs[i];
                    long epochMicros;
                    if (timeZone == DateTimeZone.UTC) {
                        epochMicros = epochSeconds * MICROSECONDS_PER_SECOND;
                    }
                    else {
                        epochMicros = timeZone.convertUTCToLocal(epochSeconds * MILLISECONDS_PER_SECOND) * MICROSECONDS_PER_MILLISECOND;
                    }
                    int nanosOfSecond = (int) round(int96Buffer.ints[i], 9 - precision);
                    values[offset + i] = epochMicros + nanosOfSecond / NANOSECONDS_PER_MICROSECOND;
                }
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    public static ValueDecoder<Int96Buffer> getInt96ToLongTimestampDecoder(ParquetEncoding encoding, PrimitiveField field, DateTimeZone timeZone)
    {
        checkArgument(
                field.getType() instanceof TimestampType timestampType && !timestampType.isShort(),
                "Trino type %s is not a long timestamp",
                field.getType());
        int precision = ((TimestampType) field.getType()).getPrecision();
        return new InlineTransformDecoder<>(
                getInt96Decoder(encoding, field),
                (values, offset, length) -> {
                    for (int i = offset; i < offset + length; i++) {
                        long epochSeconds = values.longs[i];
                        long nanosOfSecond = values.ints[i];
                        if (timeZone != DateTimeZone.UTC) {
                            epochSeconds = timeZone.convertUTCToLocal(epochSeconds * MILLISECONDS_PER_SECOND) / MILLISECONDS_PER_SECOND;
                        }
                        if (precision < 9) {
                            nanosOfSecond = (int) round(nanosOfSecond, 9 - precision);
                        }
                        // epochMicros
                        values.longs[i] = epochSeconds * MICROSECONDS_PER_SECOND + (nanosOfSecond / NANOSECONDS_PER_MICROSECOND);
                        // picosOfMicro
                        values.ints[i] = (int) ((nanosOfSecond * PICOSECONDS_PER_NANOSECOND) % PICOSECONDS_PER_MICROSECOND);
                    }
                });
    }

    public static ValueDecoder<long[]> getInt96ToShortTimestampWithTimeZoneDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampWithTimeZoneType timestampWithTimeZoneType && timestampWithTimeZoneType.isShort(),
                "Trino type %s is not a short timestamp with timezone",
                field.getType());
        ValueDecoder<Int96Buffer> delegate = getInt96Decoder(encoding, field);
        return new ValueDecoder<>() {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(long[] values, int offset, int length)
            {
                Int96Buffer int96Buffer = new Int96Buffer(length);
                delegate.read(int96Buffer, 0, length);
                for (int i = 0; i < length; i++) {
                    long epochSeconds = int96Buffer.longs[i];
                    int nanosOfSecond = int96Buffer.ints[i];
                    long utcMillis = epochSeconds * MILLISECONDS_PER_SECOND + (nanosOfSecond / NANOSECONDS_PER_MILLISECOND);
                    values[offset + i] = packDateTimeWithZone(utcMillis, UTC_KEY);
                }
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    public static ValueDecoder<long[]> getInt64TimestampMillsToShortTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampType timestampType && timestampType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampType) field.getType()).getPrecision();
        ValueDecoder<long[]> valueDecoder = getLongDecoder(encoding, field);
        if (precision < 3) {
            return new InlineTransformDecoder<>(
                    valueDecoder,
                    (values, offset, length) -> {
                        // decoded values are epochMillis, round to lower precision and convert to epochMicros
                        for (int i = offset; i < offset + length; i++) {
                            values[i] = round(values[i], 3 - precision) * MICROSECONDS_PER_MILLISECOND;
                        }
                    });
        }
        return new InlineTransformDecoder<>(
                valueDecoder,
                (values, offset, length) -> {
                    // decoded values are epochMillis, convert to epochMicros
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = values[i] * MICROSECONDS_PER_MILLISECOND;
                    }
                });
    }

    public static ValueDecoder<long[]> getInt64TimestampMillsToShortTimestampWithTimeZoneDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampWithTimeZoneType timestampWithTimeZoneType && timestampWithTimeZoneType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampWithTimeZoneType) field.getType()).getPrecision();
        ValueDecoder<long[]> valueDecoder = getLongDecoder(encoding, field);
        if (precision < 3) {
            return new InlineTransformDecoder<>(
                    valueDecoder,
                    (values, offset, length) -> {
                        // decoded values are epochMillis, round to lower precision and convert to packed millis utc value
                        for (int i = offset; i < offset + length; i++) {
                            values[i] = packDateTimeWithZone(round(values[i], 3 - precision), UTC_KEY);
                        }
                    });
        }
        return new InlineTransformDecoder<>(
                valueDecoder,
                (values, offset, length) -> {
                    // decoded values are epochMillis, convert to packed millis utc value
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = packDateTimeWithZone(values[i], UTC_KEY);
                    }
                });
    }

    public static ValueDecoder<long[]> getInt64TimestampMicrosToShortTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampType timestampType && timestampType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampType) field.getType()).getPrecision();
        ValueDecoder<long[]> valueDecoder = getLongDecoder(encoding, field);
        if (precision == 6) {
            return valueDecoder;
        }
        return new InlineTransformDecoder<>(
                valueDecoder,
                (values, offset, length) -> {
                    // decoded values are epochMicros, round to lower precision
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = round(values[i], 6 - precision);
                    }
                });
    }

    public static ValueDecoder<long[]> getInt64TimestampMicrosToShortTimestampWithTimeZoneDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampWithTimeZoneType timestampWithTimeZoneType && timestampWithTimeZoneType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampWithTimeZoneType) field.getType()).getPrecision();
        return new InlineTransformDecoder<>(
                getLongDecoder(encoding, field),
                (values, offset, length) -> {
                    // decoded values are epochMicros, round to lower precision and convert to packed millis utc value
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = packDateTimeWithZone(round(values[i], 6 - precision) / MICROSECONDS_PER_MILLISECOND, UTC_KEY);
                    }
                });
    }

    public static ValueDecoder<long[]> getInt64TimestampNanosToShortTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        checkArgument(
                field.getType() instanceof TimestampType timestampType && timestampType.isShort(),
                "Trino type %s is not a short timestamp",
                field.getType());
        int precision = ((TimestampType) field.getType()).getPrecision();
        return new InlineTransformDecoder<>(
                getLongDecoder(encoding, field),
                (values, offset, length) -> {
                    // decoded values are epochNanos, round to lower precision and convert to epochMicros
                    for (int i = offset; i < offset + length; i++) {
                        values[i] = round(values[i], 9 - precision) / NANOSECONDS_PER_MICROSECOND;
                    }
                });
    }

    public static ValueDecoder<Int96Buffer> getInt64TimestampMillisToLongTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        ValueDecoder<long[]> delegate = getLongDecoder(encoding, field);
        return new ValueDecoder<>()
        {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(Int96Buffer values, int offset, int length)
            {
                delegate.read(values.longs, offset, length);
                // decoded values are epochMillis, convert to epochMicros
                for (int i = offset; i < offset + length; i++) {
                    values.longs[i] = values.longs[i] * MICROSECONDS_PER_MILLISECOND;
                }
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    public static ValueDecoder<Int96Buffer> getInt64TimestampMicrosToLongTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        ValueDecoder<long[]> delegate = getLongDecoder(encoding, field);
        return new ValueDecoder<>()
        {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(Int96Buffer values, int offset, int length)
            {
                // decoded values are epochMicros
                delegate.read(values.longs, offset, length);
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    public static ValueDecoder<Int96Buffer> getInt64TimestampMicrosToLongTimestampWithTimeZoneDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        ValueDecoder<long[]> delegate = getLongDecoder(encoding, field);
        return new ValueDecoder<>()
        {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(Int96Buffer values, int offset, int length)
            {
                delegate.read(values.longs, offset, length);
                // decoded values are epochMicros, convert to (packed epochMillisUtc, picosOfMilli)
                for (int i = offset; i < offset + length; i++) {
                    long epochMicros = values.longs[i];
                    values.longs[i] = packDateTimeWithZone(floorDiv(epochMicros, MICROSECONDS_PER_MILLISECOND), UTC_KEY);
                    values.ints[i] = floorMod(epochMicros, MICROSECONDS_PER_MILLISECOND) * PICOSECONDS_PER_MICROSECOND;
                }
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    public static ValueDecoder<Int96Buffer> getInt64TimestampNanosToLongTimestampDecoder(ParquetEncoding encoding, PrimitiveField field)
    {
        ValueDecoder<long[]> delegate = getLongDecoder(encoding, field);
        return new ValueDecoder<>()
        {
            @Override
            public void init(SimpleSliceInputStream input)
            {
                delegate.init(input);
            }

            @Override
            public void read(Int96Buffer values, int offset, int length)
            {
                delegate.read(values.longs, offset, length);
                // decoded values are epochNanos, convert to (epochMicros, picosOfMicro)
                for (int i = offset; i < offset + length; i++) {
                    long epochNanos = values.longs[i];
                    values.longs[i] = floorDiv(epochNanos, NANOSECONDS_PER_MICROSECOND);
                    values.ints[i] = floorMod(epochNanos, NANOSECONDS_PER_MICROSECOND) * PICOSECONDS_PER_NANOSECOND;
                }
            }

            @Override
            public void skip(int n)
            {
                delegate.skip(n);
            }
        };
    }

    private static class InlineTransformDecoder<T>
            implements ValueDecoder<T>
    {
        private final ValueDecoder<T> valueDecoder;
        private final TypeTransform<T> typeTransform;

        private InlineTransformDecoder(ValueDecoder<T> valueDecoder, TypeTransform<T> typeTransform)
        {
            this.valueDecoder = requireNonNull(valueDecoder, "valueDecoder is null");
            this.typeTransform = requireNonNull(typeTransform, "typeTransform is null");
        }

        @Override
        public void init(SimpleSliceInputStream input)
        {
            valueDecoder.init(input);
        }

        @Override
        public void read(T values, int offset, int length)
        {
            valueDecoder.read(values, offset, length);
            typeTransform.process(values, offset, length);
        }

        @Override
        public void skip(int n)
        {
            valueDecoder.skip(n);
        }
    }

    private interface TypeTransform<T>
    {
        void process(T values, int offset, int length);
    }
}
