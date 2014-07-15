
package com.milink.asio.tcp.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class TcpServer {

    private TcpServerListener mListener = null;
    private ServerSocketChannel mServerChannel = null;
    private int mListenPort = 0;
    private boolean mStarted = false;
    private SelectWorker mSelectWorker = null;
    private RecvWorker mRecvWorker = null;
    private SendWorker mSendWorker = null;

    public TcpServer(int port, TcpServerListener listener) {
        mListener = listener;

        try {
            mServerChannel = ServerSocketChannel.open();
            InetSocketAddress localAddress = new InetSocketAddress(port);
            mServerChannel.socket().bind(localAddress);
            mListenPort = mServerChannel.socket().getLocalPort();
            mServerChannel.configureBlocking(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        if (!mStarted) {
            mStarted = true;
            mSelectWorker = new SelectWorker();
            mRecvWorker = new RecvWorker();
            mSendWorker = new SendWorker();
        }
    }

    public void stop() {
        if (mStarted) {
            mStarted = false;
            mSelectWorker.close();
            mRecvWorker.close();
            mSendWorker.close();
        }
    }

    public int getListenPort() {
        return mListenPort;
    }

    public void closeConnection(TcpConn conn) {
        if (mStarted) {
            try {
                conn.getChannel().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class SelectWorker implements Runnable {

        private TcpConnPool mConnPool = new TcpConnPool();
        private Selector mSelector = null;
        private Thread mThread = null;

        public SelectWorker() {
            mThread = new Thread(this);
            mThread.start();
        }

        public void close() {
            try {
                mSelector.close();
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            boolean loop = true;

            try {
                mSelector = Selector.open();
            } catch (IOException e1) {
                e1.printStackTrace();
                return;
            }

            while (loop) {
                preSelect();
                
                try {
                    mSelector.select();
                } catch (IOException e) {
                    break;
                }

                Iterator<SelectionKey> it = mSelector.selectedKeys().iterator();
                if (!it.hasNext())
                    break;

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    postSelect(key);
                }
            }

        }

        private void preSelect() {
            try {
                mServerChannel.register(mSelector, SelectionKey.OP_READ);
                
                for (TcpConn conn : mConnPool.getConns()) {
                    conn.getChannel().register(mSelector, SelectionKey.OP_READ);
                }
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
        }

        private void postSelect(SelectionKey key) {
            // accept a new connection
            if (key.isValid() && key.isAcceptable()) {
                SocketChannel channel = (SocketChannel) key.channel();
                TcpConn conn = new TcpConn(channel);
                mRecvWorker.putNewConnection(conn);
                mConnPool.add(conn);
                return;
            }

            // read data
            if (key.isValid() && key.isReadable()) {
                SocketChannel channel = (SocketChannel) key.channel();

                ByteBuffer buf = ByteBuffer.allocateDirect(1024);
                int numBytesRead = 0;
                try {
                    numBytesRead = channel.read(buf);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (numBytesRead > 0) {
                    buf.flip();

                    byte[] data = new byte[numBytesRead];
                    buf.get(data, 0, numBytesRead);

                    TcpConn conn = mConnPool.getConn(channel);
                    mRecvWorker.putData(conn, data);

                    buf.clear();
                }
                else {
                    // close connection;
                    try {
                        channel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    TcpConn conn = mConnPool.getConn(channel);
                    mRecvWorker.putClosedConnection(conn);
                    
                    mConnPool.remove(conn);
                }
            }
        }
    }

    public class RecvWorker implements Runnable {

        private static final int MAX_RECV_QUEUE_LENGTH = 128;
        private BlockingQueue<TcpPacket> mQueue = null;
        private Thread mThread = null;

        public RecvWorker() {
            mQueue = new ArrayBlockingQueue<TcpPacket>(MAX_RECV_QUEUE_LENGTH);
            mThread = new Thread(this);
            mThread.start();
        }

        public void close() {
            TcpPacket packet = new TcpPacket();
            packet.type = TcpPacket.Type.Exit;

            synchronized (this) {
                mQueue.clear();
                try {
                    mQueue.put(packet);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void putNewConnection(TcpConn conn) {
            TcpPacket packet = new TcpPacket();
            packet.type = TcpPacket.Type.Accept;
            packet.conn = conn;

            synchronized (this) {
                try {
                    mQueue.put(packet);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void putClosedConnection(TcpConn conn) {
            TcpPacket packet = new TcpPacket();
            packet.type = TcpPacket.Type.Closed;
            packet.conn = conn;

            synchronized (this) {
                try {
                    mQueue.put(packet);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void putData(TcpConn conn, byte[] data) {
            TcpPacket packet = new TcpPacket();
            packet.type = TcpPacket.Type.Receive;
            packet.data = data.clone();
            packet.conn = conn;

            synchronized (this) {
                try {
                    mQueue.put(packet);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void run() {
            while (true) {
                TcpPacket packet;

                try {
                    packet = mQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }

                if (packet.type == TcpPacket.Type.Exit) {
                    break;
                }

                else if (packet.type == TcpPacket.Type.Accept) {
                    mListener.onAccept(TcpServer.this, packet.conn);
                }

                else if (packet.type == TcpPacket.Type.Closed) {
                    mListener.onConnectionClosed(TcpServer.this, packet.conn);
                }
                else if (packet.type == TcpPacket.Type.Receive) {
                    mListener.onReceive(TcpServer.this, packet.conn, packet.data);
                }
            }

            mQueue.clear();
        }
    }

    public class SendWorker implements Runnable {

        private static final int MAX_SEND_QUEUE_LENGTH = 128;
        private BlockingQueue<TcpPacket> mQueue = null;
        private Thread mThread = null;

        public SendWorker() {
            mQueue = new ArrayBlockingQueue<TcpPacket>(MAX_SEND_QUEUE_LENGTH);
            mThread = new Thread(this);
            mThread.start();
        }

        public void close() {
            synchronized (this) {
                TcpPacket packet = new TcpPacket();
                packet.type = TcpPacket.Type.Exit;

                synchronized (this) {
                    try {
                        mQueue.clear();
                        mQueue.put(packet);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void putData(TcpConn conn, byte[] data) {
            synchronized (this) {
                TcpPacket packet = new TcpPacket();
                packet.type = TcpPacket.Type.Send;
                packet.data = data.clone();

                synchronized (this) {
                    try {
                        mQueue.put(packet);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        @Override
        public void run() {
            while (true) {
                TcpPacket packet = null;

                try {
                    packet = mQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }

                if (packet.type == TcpPacket.Type.Exit) {
                    break;
                }
                else if (packet.type == TcpPacket.Type.Send) {
                    SocketChannel channel = packet.conn.getChannel();

                    ByteBuffer buffer = ByteBuffer.wrap(packet.data);
                    buffer.clear();

                    int writeSize = 0;
                    while (true) {
                        int size = 0;
                        try {
                            size = channel.write(buffer);
                        } catch (IOException e) {
                            break;
                        }

                        writeSize += size;
                        if (writeSize < packet.data.length) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                                break;
                            }
                        }
                        else {
                            break;
                        }
                    }
                }
            }

            mQueue.clear();
        }
    }
}
