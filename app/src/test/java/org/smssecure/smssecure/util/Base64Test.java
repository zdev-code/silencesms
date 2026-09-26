package org.smssecure.smssecure.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;

public class Base64Test {
    @Test
    public void decodesGzipBytesWithoutDecompressing() throws IOException {
        byte[] content = new byte[] {1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(content);
        }

        byte[] gzipBytes = output.toByteArray();
        assertArrayEquals(gzipBytes, Base64.decode(Base64.encodeBytes(gzipBytes)));
    }

    @Test
    public void roundTripsPaddedAndUnpaddedData() throws IOException {
        byte[] data = new byte[] {0, 1, 2, 3, 4};

        assertArrayEquals(data, Base64.decode(Base64.encodeBytes(data)));
        assertArrayEquals(data, Base64.decodeWithoutPadding(Base64.encodeBytesWithoutPadding(data)));
    }
}