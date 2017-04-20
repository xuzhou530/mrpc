package com.kongzhong.mrpc.client;

import com.google.common.util.concurrent.*;
import com.kongzhong.mrpc.common.thread.RpcThreadPool;
import com.kongzhong.mrpc.registry.ServiceDiscovery;
import com.kongzhong.mrpc.serialize.ProtostuffSerialize;
import com.kongzhong.mrpc.transfer.RequestCallback;
import com.kongzhong.mrpc.transfer.RpcClientHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 服务配置加载
 */
public class RpcServerLoader {

    public static final Logger log = LoggerFactory.getLogger(RpcServerLoader.class);

    /**
     * 单例获取loader
     */
    private volatile static RpcServerLoader rpcServerLoader;

    /**
     * 并行处理器个数
     */
    private final static int parallel = Runtime.getRuntime().availableProcessors() * 2;

    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(parallel);

    /**
     * 客户端 消息处理线程池
     */
    private static ListeningExecutorService TPE = MoreExecutors.listeningDecorator((ThreadPoolExecutor) RpcThreadPool.getExecutor(16, -1));

    private RpcClientHandler clientHandler = null;

    /**
     * 细粒度的可重入锁
     */
    private Lock lock = new ReentrantLock();
    private Condition connectStatus = lock.newCondition();
    private Condition handlerStatus = lock.newCondition();

    private RpcServerLoader() {
    }

    private static final class RpcServerLoaderHolder {
        private static final RpcServerLoader $ = new RpcServerLoader();
    }

    public static RpcServerLoader me() {
        return RpcServerLoaderHolder.$;
    }

    /**
     * @param serverAddress     服务器端地址
     * @param serializeProtocol 序列化协议
     */
    public void load(String serverAddress) {
        String[] ipAddr = serverAddress.split(":");
        if (ipAddr.length == 2) {
            //获取IP
            String host = ipAddr[0];
            //获取端口号
            int port = Integer.parseInt(ipAddr[1]);
            //获取socket的完整地址
            final InetSocketAddress remoteAddr = new InetSocketAddress(host, port);

            //与服务器建立连接
            ListenableFuture<Boolean> listenableFuture = TPE.submit(
                    new RequestCallback(eventLoopGroup, remoteAddr, new ProtostuffSerialize()));

            // 给listenableFuture 添加回调函数,当MessageSendInitializeTask执行完毕之后调用
            Futures.addCallback(listenableFuture, new FutureCallback<Boolean>() {
                /***
                 * 与服务器建立连接后,等待本地ClientHandler获取成功
                 * @param result
                 */
                public void onSuccess(Boolean result) {
                    try {
                        lock.lock();
                        if (clientHandler == null) {
                            handlerStatus.await();
                        }
                        if (result == Boolean.TRUE && clientHandler != null) {
                            connectStatus.signalAll();
                        }
                    } catch (InterruptedException ex) {
                        log.error(RpcServerLoader.class.getName(), ex);
                    } finally {
                        lock.unlock();
                    }
                }

                public void onFailure(Throwable t) {
                    log.error("client connect fail", t);
                }
            }, TPE);// end of Futures.addCallback()

        }
    }

    public void load(ServiceDiscovery serviceDiscovery) {
        String serverAddr = serviceDiscovery.discover();
        this.load(serverAddr);
    }

    public void setMessageSendHandler(RpcClientHandler clientHandler) {
        try {
            lock.lock();
            this.clientHandler = clientHandler;
            handlerStatus.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 独占的获取 messageSendHandler
     *
     * @return
     * @throws InterruptedException
     */
    public RpcClientHandler getMessageSendHandler() throws InterruptedException {
        try {
            lock.lock();
            //netty服务端链路没有建立完毕之前，先挂起等待
            if (clientHandler == null) {
                connectStatus.await();// 阻塞
            }
            return clientHandler;
        } finally {
            lock.unlock();
        }
    }


    public void unLoad() {
        clientHandler.close();
        TPE.shutdown();
        eventLoopGroup.shutdownGracefully();
    }

}