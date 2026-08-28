package dev.flamelens.fusio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Java 8 stand-ins for String.repeat / InputStream.readAllBytes (tests run on the Java 8 baseline). */
public final class TestUtil {

    private TestUtil() {
    }

    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
