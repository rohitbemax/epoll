package com.wizzardo.epoll;

import com.wizzardo.epoll.readable.ReadableByteArray;
import com.wizzardo.epoll.readable.ReadableData;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author: wizzardo
 * Date: 1/6/14
 */
public class Connection implements Cloneable, Closeable {
    protected static final int EPOLLIN = 0x001;
    protected static final int EPOLLOUT = 0x004;

    protected final int fd;
    protected final int ip, port;
    protected volatile Queue<ReadableData> sending;
    protected volatile IOThread epoll;
    private volatile int mode = 1;
    private volatile boolean alive = true;
    private String ipString;
    private Long lastEvent;

    public Connection(int fd, int ip, int port) {
        this.fd = fd;
        this.ip = ip;
        this.port = port;
    }

    public String getIp() {
        if (ipString == null)
            ipString = getIp(ip);
        return ipString;
    }

    void setIpString(String ip) {
        ipString = ip;
    }

    private String getIp(int ip) {
        StringBuilder sb = new StringBuilder();
        sb.append((ip >> 24) + (ip < 0 ? 256 : 0)).append(".");
        sb.append((ip & 16777215) >> 16).append(".");
        sb.append((ip & 65535) >> 8).append(".");
        sb.append(ip & 255);
        return sb.toString();
    }

    public int getPort() {
        return port;
    }

    Long setLastEvent(Long lastEvent) {
        Long last = this.lastEvent;
        this.lastEvent = lastEvent;
        return last;
    }

    Long getLastEvent() {
        return lastEvent;
    }

    public boolean isAlive() {
        return alive;
    }

    synchronized void setIsAlive(boolean isAlive) {
        alive = isAlive;
    }

    public void write(String s, ByteBufferProvider bufferProvider) {
        try {
            write(s.getBytes("utf-8"), bufferProvider);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(byte[] bytes, ByteBufferProvider bufferProvider) {
        write(bytes, 0, bytes.length, bufferProvider);
    }

    public void write(byte[] bytes, int offset, int length, ByteBufferProvider bufferProvider) {
        write(new ReadableByteArray(bytes, offset, length), bufferProvider);
    }

    public void write(ReadableData readable, ByteBufferProvider bufferProvider) {
        if (sending == null)
            synchronized (this) {
                if (sending == null)
                    sending = new ConcurrentLinkedQueue<ReadableData>();
            }

        sending.add(readable);
        write(bufferProvider);
    }

    public void write(ByteBufferProvider bufferProvider) {
        if (sending == null)
            return;

        synchronized (this) {
            ReadableData readable;
            try {
                while ((readable = sending.peek()) != null) {
                    while (!readable.isComplete() && actualWrite(readable, bufferProvider)) {
                    }
                    if (!readable.isComplete())
                        return;

                    readable.close();
                    onWriteData(sending.poll(), !sending.isEmpty());
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    /*
    * @return true if connection ready to write data
    */
    protected boolean actualWrite(ReadableData readable, ByteBufferProvider bufferProvider) throws IOException {
        ByteBufferWrapper bb = readable.getByteBuffer(bufferProvider);
        bb.clear();
        int r = readable.read(bb.buffer());
        if (r > 0 && isAlive()) {
            int written = write(fd, bb.address, bb.offset(), r);
//            System.out.println("write: " + written + " (" + readable.complete() + "/" + readable.length() + ")" + " to " + this);
            if (written != r) {
                readable.unread(r - written);
                return false;
            }
            return true;
        }
        return false;
    }

    protected int write(int fd, long bbAddress, int offset, int length) throws IOException {
        return epoll.write(fd, bbAddress, offset, length);
    }

    public int read(byte[] b, int offset, int length, ByteBufferProvider bufferProvider) throws IOException {
        ByteBuffer bb = read(length, bufferProvider);
        int r = bb.limit();
        bb.get(b, offset, r);
        return r;
    }

    public ByteBuffer read(int length, ByteBufferProvider bufferProvider) throws IOException {
        ByteBufferWrapper bb = bufferProvider.getBuffer();
        bb.clear();
        int l = Math.min(length, bb.limit());
        int r = isAlive() ? read(fd, bb.address, 0, l) : -1;
        if (r > 0)
            bb.position(r);
        bb.flip();
        return bb.buffer();
    }

    protected int read(int fd, long bbAddress, int offset, int length) throws IOException {
        return epoll.read(fd, bbAddress, offset, length);
    }

    public void close() throws IOException {
        epoll.close(this);
        if (sending != null)
            for (ReadableData data : sending)
                data.close();
    }

    protected void enableOnWriteEvent() {
        if ((mode & EPOLLIN) != 0)
            epoll.mod(this, EPOLLIN | EPOLLOUT);
        else
            epoll.mod(this, EPOLLOUT);
    }

    protected void disableOnWriteEvent() {
        if ((mode & EPOLLIN) != 0)
            epoll.mod(this, EPOLLIN);
        else
            epoll.mod(this, 0);
    }

    protected void enableOnReadEvent() {
        if ((mode & EPOLLOUT) != 0)
            epoll.mod(this, EPOLLIN | EPOLLOUT);
        else
            epoll.mod(this, EPOLLIN);
    }

    protected void disableOnReadEvent() {
        if ((mode & EPOLLOUT) != 0)
            epoll.mod(this, EPOLLOUT);
        else
            epoll.mod(this, 0);
    }

    public void onWriteData(ReadableData readable, boolean hasMore) {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Connection that = (Connection) o;

        if (fd != that.fd) return false;
        if (ip != that.ip) return false;
        if (port != that.port) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = fd;
        result = 31 * result + ip;
        result = 31 * result + port;
        return result;
    }

    @Override
    public String toString() {
        return fd + " " + getIp() + ":" + port;
    }

    public int read(byte[] bytes, ByteBufferProvider bufferProvider) throws IOException {
        return read(bytes, 0, bytes.length, bufferProvider);
    }

    boolean isInvalid(Long now) {
        return lastEvent.compareTo(now) <= 0;
    }

    void setMode(int mode) {
        this.mode = mode;
    }

    int getMode() {
        return mode;
    }

    public void setIOThread(IOThread IOThread) {
        epoll = IOThread;
    }
}