package br.com.moviai.visualizador3d;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Leitor bufferizado de stream com suporte a little/big endian, linhas e tokens de texto. */
final class BinReader {
    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos = 0;
    private int lim = 0;
    boolean bigEndian = false;
    long bytesRead = 0;

    BinReader(InputStream in) {
        this.in = in;
    }

    /** Retorna o próximo byte (0..255) ou -1 no fim do arquivo. */
    int read() throws IOException {
        while (pos >= lim) {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim < 0) {
                lim = 0;
                return -1;
            }
        }
        bytesRead++;
        return buf[pos++] & 0xFF;
    }

    int u8() throws IOException {
        int b = read();
        if (b < 0) throw new EOFException("Fim inesperado do arquivo");
        return b;
    }

    int u16() throws IOException {
        int a = u8();
        int b = u8();
        return bigEndian ? ((a << 8) | b) : ((b << 8) | a);
    }

    int i32() throws IOException {
        int a = u8(), b = u8(), c = u8(), d = u8();
        return bigEndian
                ? ((a << 24) | (b << 16) | (c << 8) | d)
                : ((d << 24) | (c << 16) | (b << 8) | a);
    }

    long i64() throws IOException {
        long lo, hi;
        if (bigEndian) {
            hi = i32() & 0xFFFFFFFFL;
            lo = i32() & 0xFFFFFFFFL;
        } else {
            lo = i32() & 0xFFFFFFFFL;
            hi = i32() & 0xFFFFFFFFL;
        }
        return (hi << 32) | lo;
    }

    float f32() throws IOException {
        return Float.intBitsToFloat(i32());
    }

    double f64() throws IOException {
        return Double.longBitsToDouble(i64());
    }

    void skip(long n) throws IOException {
        for (long i = 0; i < n; i++) u8();
    }

    /** Lê uma linha de texto (sem \r\n). Retorna null no fim do arquivo. */
    String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c = read();
        if (c < 0) return null;
        while (c >= 0 && c != '\n') {
            if (c != '\r') sb.append((char) c);
            c = read();
        }
        return sb.toString();
    }

    /** Próximo token separado por espaço em branco. Retorna null no fim do arquivo. */
    String token() throws IOException {
        int c = read();
        while (c >= 0 && c <= ' ') c = read();
        if (c < 0) return null;
        StringBuilder sb = new StringBuilder(16);
        while (c > ' ') {
            sb.append((char) c);
            c = read();
        }
        return sb.toString();
    }

    double tokenDouble() throws IOException {
        String t = token();
        if (t == null) throw new EOFException("Fim inesperado do arquivo");
        return Double.parseDouble(t);
    }
}
