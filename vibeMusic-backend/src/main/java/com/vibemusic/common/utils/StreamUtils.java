package com.vibemusic.common.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * IO 流工具类
 */
public class StreamUtils {

    private static final int BUFFER_SIZE = 65536; // 64KB，提升流拷贝吞吐量

    /**
     * 流拷贝（带缓冲），完成后自动 flush
     */
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
