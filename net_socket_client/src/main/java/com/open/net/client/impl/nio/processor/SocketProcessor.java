package com.open.net.client.impl.nio.processor;

import com.open.net.client.impl.nio.INioConnectListener;
import com.open.net.client.impl.nio.NioClient;
import com.open.net.client.structures.BaseClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;

/**
 * author       :   long
 * created on   :   2017/11/30
 * description  :   连/读/写 处理器
 */

public final class SocketProcessor {

    private final String TAG = "SocketProcessor";

    private String  mIp ="192.168.1.1";
    private int     mPort =9999;

    private ConnectRunnable mConnectProcessor;
    private Thread mConnectThread =null;

    private long connect_token;
    private INioConnectListener mNioConnectListener;

    private BaseClient mClient;
    private boolean closed = false;

    public SocketProcessor(long connect_token,BaseClient mClient, String mIp, int mPort, INioConnectListener mNioConnectListener) {
        this.connect_token = connect_token;
        this.mClient = mClient;
        this.mIp = mIp;
        this.mPort = mPort;
        this.mNioConnectListener = mNioConnectListener;
    }

    public void start(){
        mConnectProcessor = new ConnectRunnable();
        mConnectThread = new Thread(mConnectProcessor);
        mConnectThread.start();
    }

    public synchronized void close(){
        closed = true;
        wakeUp();
    }

    public void wakeUp(){
        if(null !=mConnectProcessor){
            mConnectProcessor.wakeUp();
        }
    }

    public void onSocketExit(int exit_code){
        close();
        if(null != mNioConnectListener){
            mNioConnectListener.onConnectFailed(connect_token);
        }
    }

    private class ConnectRunnable implements Runnable {

        private SocketChannel mSocketChannel;
        private Selector mSelector;

        public void wakeUp(){
            if(null != mSelector){
                mSelector.wakeup();
            }
        }

        @Override
        public void run() {
            try {

                mSelector = SelectorProvider.provider().openSelector();
                mSocketChannel = SocketChannel.open();
                mSocketChannel.configureBlocking(false);

                InetSocketAddress address=new InetSocketAddress(mIp, mPort);
                mSocketChannel.connect(address);
                mSocketChannel.register(mSelector, SelectionKey.OP_CONNECT,mClient);

                //处理连接
                boolean isConnectSuccess = connect(10000);

                //开始读写
                if(isConnectSuccess){
                    boolean isExit = false;
                    while(!isExit) {

                        int readKeys = mSelector.select();
                        if(readKeys > 0){
                            Iterator<SelectionKey> selectedKeys = mSelector.selectedKeys().iterator();
                            while (selectedKeys.hasNext()) {
                                SelectionKey key =  selectedKeys.next();
                                selectedKeys.remove();

                                if (!key.isValid()) {
                                    continue;
                                }

                                if (key.isReadable()) {
                                    BaseClient mClient = (BaseClient) key.attachment();
                                    boolean ret = mClient.onRead();
                                    if(!ret){
                                        isExit = true;
                                        key.cancel();
                                        key.attach(null);
                                        key.channel().close();
                                        break;
                                    }

                                }else if (key.isWritable()) {
                                    BaseClient mClient = (BaseClient) key.attachment();
                                    boolean ret = mClient.onWrite();
                                    if(!ret){
                                        isExit = true;
                                        key.cancel();
                                        key.attach(null);
                                        key.channel().close();
                                        break;
                                    }
                                    key.interestOps(SelectionKey.OP_READ);
                                }
                            }
                        }

                        if(isExit || closed){
                            break;
                        }

                        if(!mClient.mWriteMessageQueen.mQueen.isEmpty()) {
                            SelectionKey key= mSocketChannel.keyFor(mSelector);
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            onSocketExit(1);
        }

        private boolean connect(int connect_timeout) throws IOException {
            boolean isConnectSuccess = false;
            //连接
            int connectReady = 0;
            if(connect_timeout == -1){
                connectReady = mSelector.select();
            }else{
                connectReady = mSelector.select(connect_timeout);
            }
            if(connectReady > 0){
                Iterator<SelectionKey> selectedKeys = mSelector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isConnectable()) {
                        boolean ret = finishConnection(key);
                        isConnectSuccess = ret;
                        if(!ret){
                            key.cancel();
                            key.attach(null);
                            key.channel().close();
                            break;
                        }
                    }
                }
            }else{
                isConnectSuccess = false;
                try{
                    Iterator<SelectionKey> selectedKeys = mSelector.keys().iterator();
                    while(selectedKeys.hasNext()){
                        SelectionKey key = selectedKeys.next();
                        key.cancel();
                        key.attach(null);
                        key.channel().close();
                    }
                }catch(Exception e2){
                    e2.printStackTrace();
                }
            }
            return isConnectSuccess;
        }

        private boolean finishConnection(SelectionKey key){
            boolean connectRet = true;
            try{
                boolean result;
                SocketChannel socketChannel = (SocketChannel) key.channel();
                result= socketChannel.finishConnect();//没有网络的时候也返回true;连不上的情况下会抛出java.net.ConnectException: Connection refused
                if(result) {
                    ((NioClient)mClient).init(mSocketChannel,mSelector);
                    key.interestOps(SelectionKey.OP_READ);
                    if(null != mNioConnectListener){
                        mNioConnectListener.onConnectSuccess(connect_token,mSocketChannel,mSelector);
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                connectRet = false;
            }
            return connectRet;
        }

    }
}