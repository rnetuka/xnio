/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
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

package org.xnio.conduits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.xnio.channels.FixedLengthOverflowException;
import org.xnio.channels.FixedLengthUnderflowException;
import org.xnio.channels.StreamSourceChannel;

import static java.lang.Math.min;

/**
 * A stream sink conduit with a limited length.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class FixedLengthStreamSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit> implements StreamSinkConduit {
    private long remaining;

    /**
     * Construct a new instance.
     *
     * @param next the delegate conduit to set
     */
    public FixedLengthStreamSinkConduit(final FixedLengthStreamSinkConduit next) {
        super(next);
    }

    public long transferFrom(final FileChannel src, final long position, final long count) throws IOException {
        if (count == 0L) return 0L;
        final long remaining = this.remaining;
        if (remaining == 0L) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            return res = next.transferFrom(src, position, min(count, remaining));
        } finally {
            this.remaining = remaining - res;
        }
    }

    public long transferFrom(final StreamSourceChannel source, final long count, final ByteBuffer throughBuffer) throws IOException {
        if (count == 0L) return 0L;
        final long remaining = this.remaining;
        if (remaining == 0L) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            return res = next.transferFrom(source, min(count, remaining), throughBuffer);
        } finally {
            this.remaining = remaining - res;
        }
    }

    public int write(final ByteBuffer src) throws IOException {
        if (! src.hasRemaining()) {
            return 0;
        }
        int res = 0;
        final long remaining = this.remaining;
        if (remaining == 0L) {
            throw new FixedLengthOverflowException();
        }
        try {
            final int lim = src.limit();
            final int pos = src.position();
            if (lim - pos > remaining) {
                src.limit((int) (remaining - (long) pos));
                try {
                    return res = next.write(src);
                } finally {
                    src.limit(lim);
                }
            } else {
                return res = next.write(src);
            }
        } finally {
            this.remaining = remaining - res;
        }
    }

    public long write(final ByteBuffer[] srcs, final int offs, final int len) throws IOException {
        if (len == 0) {
            return 0L;
        } else if (len == 1) {
            return write(srcs[offs]);
        }
        final long remaining = this.remaining;
        if (remaining == 0L) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            int lim;
            // The total amount of buffer space discovered so far.
            long t = 0L;
            for (int i = 0; i < len; i ++) {
                final ByteBuffer buffer = srcs[i + offs];
                // Grow the discovered buffer space by the remaining size of the current buffer.
                // We want to capture the limit so we calculate "remaining" ourselves.
                t += (lim = buffer.limit()) - buffer.position();
                if (t > remaining) {
                    // only read up to this point, and trim the last buffer by the number of extra bytes
                    buffer.limit(lim - (int) (t - (remaining)));
                    try {
                        return res = next.write(srcs, offs, i + 1);
                    } finally {
                        // restore the original limit
                        buffer.limit(lim);
                    }
                }
            }
            if (t == 0L) {
                return 0L;
            }
            // the total buffer space is less than the remaining count.
            return res = next.write(srcs, offs, len);
        } finally {
            this.remaining = remaining - res;
        }
    }

    public void terminateWrites() throws IOException {
        next.terminateWrites();
        if (remaining > 0L) {
            throw new FixedLengthUnderflowException();
        }
    }

    public void truncateWrites() throws IOException {
        next.terminateWrites();
        if (remaining > 0L) {
            throw new FixedLengthUnderflowException();
        }
    }

    /**
     * Get the number of remaining bytes available to read.
     *
     * @return the number of remaining bytes available to read
     */
    public long getRemaining() {
        return remaining;
    }
}
