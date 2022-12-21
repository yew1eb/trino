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
package io.trino.parquet.reader.flat;

import io.trino.spi.block.Block;
import io.trino.spi.block.Int96ArrayBlock;

import java.util.Optional;

public class Int96ColumnAdapter
        implements ColumnAdapter<Int96ColumnAdapter.Int96Buffer>
{
    public static final Int96ColumnAdapter INT96_ADAPTER = new Int96ColumnAdapter();

    @Override
    public Int96Buffer createBuffer(int size)
    {
        return new Int96Buffer(size);
    }

    @Override
    public void copyValue(Int96Buffer source, int sourceIndex, Int96Buffer destination, int destinationIndex)
    {
        destination.longs[destinationIndex] = source.longs[sourceIndex];
        destination.ints[destinationIndex] = source.ints[sourceIndex];
    }

    @Override
    public Block createNullableBlock(boolean[] nulls, Int96Buffer values)
    {
        return new Int96ArrayBlock(values.size(), Optional.of(nulls), values.longs, values.ints);
    }

    @Override
    public Block createNonNullBlock(Int96Buffer values)
    {
        return new Int96ArrayBlock(values.size(), Optional.empty(), values.longs, values.ints);
    }

    @Override
    public void decodeDictionaryIds(Int96Buffer values, int offset, int length, int[] ids, Int96Buffer dictionary)
    {
        for (int i = 0; i < length; i++) {
            values.longs[offset + i] = dictionary.longs[ids[i]];
            values.ints[offset + i] = dictionary.ints[ids[i]];
        }
    }

    public static class Int96Buffer
    {
        public final long[] longs;
        public final int[] ints;

        public Int96Buffer(int size)
        {
            this.longs = new long[size];
            this.ints = new int[size];
        }

        public int size()
        {
            return longs.length;
        }
    }
}
