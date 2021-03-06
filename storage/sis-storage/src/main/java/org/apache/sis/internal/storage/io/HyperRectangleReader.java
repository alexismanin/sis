/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.internal.storage.io;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.io.IOException;
import org.apache.sis.util.Numbers;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.storage.DataStoreContentException;


/**
 * Helper methods for reading a rectangular area, a cube or a hyper-cube from a channel.
 * The data can be stored in an existing array, or a new array can be created.
 * This class does not handle compression; it is rather designed for efficient reading of uncompressed data.
 *
 * @author  Johann Sorel (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.0
 * @since   0.7
 * @module
 */
public final class HyperRectangleReader {
    /**
     * The channel from which to read the values, together with a buffer for transferring data.
     */
    private final DataTransfer reader;

    /**
     * The {@code input} position of the first sample (ignoring sub-area and subsampling).
     * This is the {@code origin} argument given to the constructor, copied verbatim.
     * This field is public for callers wanting to order {@code HyperRectangleReader} instances
     * in increasing file offset order, for more sequential reading (less seek operations).
     */
    public final long origin;

    /**
     * Creates a new reader for the given input and source region.
     *
     * @param  dataType  the type of elements to read, as one of the constants defined in {@link Numbers}.
     * @param  input     the channel from which to read the values, together with a buffer for transferring data.
     * @param  origin    the position in the channel of the first sample value in the hyper-rectangle.
     * @throws DataStoreContentException if the given {@code dataType} is not one of the supported values.
     */
    public HyperRectangleReader(final byte dataType, final ChannelDataInput input, final long origin)
            throws DataStoreContentException
    {
        switch (dataType) {
            case Numbers.BYTE:      reader = input.new BytesReader  (           null); break;
            case Numbers.CHARACTER: reader = input.new CharsReader  ((char[])   null); break;
            case Numbers.SHORT:     reader = input.new ShortsReader ((short[])  null); break;
            case Numbers.INTEGER:   reader = input.new IntsReader   ((int[])    null); break;
            case Numbers.LONG:      reader = input.new LongsReader  ((long[])   null); break;
            case Numbers.FLOAT:     reader = input.new FloatsReader ((float[])  null); break;
            case Numbers.DOUBLE:    reader = input.new DoublesReader((double[]) null); break;
            default: throw new DataStoreContentException(Errors.format(Errors.Keys.UnknownType_1, dataType));
        }
        this.origin = origin;
        final ByteBuffer buffer = input.buffer;
        final int pos = buffer.position();
        final int lim = buffer.limit();
        try {
            buffer.position(0).limit(buffer.capacity());
            reader.createView();
        } finally {
            buffer.limit(lim).position(pos);
        }
    }

    /**
     * Creates a new reader for the data in an existing buffer.
     * The data will be read from the current buffer position to the buffer limit.
     *
     * @param  filename  a data source name, for error messages or debugging purpose.
     * @param  data      a buffer containing the data to read.
     * @throws IOException should never happen.
     */
    public HyperRectangleReader(final String filename, final Buffer data) throws IOException {
        reader = new MemoryDataTransfer(filename, data).reader();
        origin = 0;
    }

    /**
     * Returns a file identifier for error messages or debugging purpose.
     *
     * @return the file identifier.
     */
    public String filename() {
        return reader.filename();
    }

    /**
     * Returns the number of bytes in each value to be read.
     *
     * @return number of bytes per value.
     */
    public int dataSize() {
        return 1 << reader.dataSizeShift();
    }

    /**
     * Reads data in the given region. It is caller's responsibility to ensure that the {@code Region}
     * object has been created with a {@code size} argument equals to this hyper-rectangle size.
     *
     * @param  region  the sub-area to read and the subsampling to use.
     * @return the data in an array of primitive type.
     * @throws IOException if an error occurred while transferring data from the channel.
     */
    public Object read(final Region region) throws IOException {
        final int contiguousDataDimension = region.contiguousDataDimension();
        final int contiguousDataLength = region.targetLength(contiguousDataDimension);
        final long[] strides = new long[region.getDimension() - contiguousDataDimension];
        final int[]   cursor = new int[strides.length];
        final int  sizeShift = reader.dataSizeShift();
        long  streamPosition = origin + (region.startAt << sizeShift);
        int    arrayPosition = 0;
        for (int i=0; i<strides.length; i++) {
            strides[i] = (region.skips[i + contiguousDataDimension] + contiguousDataLength) << sizeShift;
            assert (strides[i] > 0) : i;
        }
        try {
            reader.createDataArray(region.targetLength(region.getDimension()));
            final Buffer view = reader.view();
loop:       do {
                reader.seek(streamPosition);
                assert reader.view() == view;
                reader.readFully(view, arrayPosition, contiguousDataLength);
                for (int i=0; i<cursor.length; i++) {
                    /*
                     * After we have read as much contiguous data as we can (may be a row, or a plane, or
                     * a cube, etc. depending if we have to skip values or not between rows/planes/cubes),
                     * search the highest dimension which is going to change (i.e. are we going to start a
                     * new row, or a new plane, or a new cube?). This determine how many bytes we have to
                     * skip.
                     */
                    if (++cursor[i] < region.targetSize[contiguousDataDimension + i]) {
                        streamPosition += strides[i];
                        arrayPosition  += contiguousDataLength;
                        continue loop;
                    }
                    cursor[i] = 0;
                }
                break;
            } while (true);
            return reader.dataArray();
        } finally {
            reader.setDest(null);
        }
    }
}
