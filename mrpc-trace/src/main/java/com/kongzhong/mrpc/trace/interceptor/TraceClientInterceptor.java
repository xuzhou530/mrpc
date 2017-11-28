package com.kongzhong.mrpc.trace.interceptor;

import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.kongzhong.basic.zipkin.TraceContext;
import com.kongzhong.basic.zipkin.agent.AbstractAgent;
import com.kongzhong.basic.zipkin.agent.KafkaAgent;
import com.kongzhong.mrpc.Const;
import com.kongzhong.mrpc.client.invoke.ClientInvocation;
import com.kongzhong.mrpc.client.invoke.RpcInvoker;
import com.kongzhong.mrpc.interceptor.RpcClientInterceptor;
import com.kongzhong.mrpc.model.RpcContext;
import com.kongzhong.mrpc.model.RpcRequest;
import com.kongzhong.mrpc.trace.TraceConstants;
import com.kongzhong.mrpc.trace.config.TraceClientAutoConfigure;
import com.kongzhong.mrpc.utils.Ids;
import com.kongzhong.mrpc.utils.NetUtils;
import com.kongzhong.mrpc.utils.StringUtils;
import com.kongzhong.mrpc.utils.TimeUtils;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ClientTraceInterceptor
 */
@Slf4j
@Data
public class TraceClientInterceptor implements RpcClientInterceptor {

    private AbstractAgent agent;

    private TraceClientAutoConfigure traceClientAutoConfigure;

    public TraceClientInterceptor(TraceClientAutoConfigure traceClientAutoConfigure) {
        if (null == traceClientAutoConfigure) {
            this.traceClientAutoConfigure = new TraceClientAutoConfigure();
        } else {
            this.traceClientAutoConfigure = traceClientAutoConfigure;
            this.agent = new KafkaAgent(traceClientAutoConfigure.getUrl());
        }
    }

    @Override
    public Object execute(ClientInvocation invocation) throws Exception {

        if (!traceClientAutoConfigure.getEnable()) {
            // not need tracing
            return invocation.next();
        }

        RpcInvoker invoker = invocation.getRpcInvoker();
        RpcRequest request = invoker.getRequest();

        // start the watch
        Stopwatch watch = Stopwatch.createStarted();

        List<Span> rootSpans = TraceContext.getSpans();
        boolean    fromUrl   = (rootSpans != null && rootSpans.isEmpty() == false);

        Span consumeSpan = this.startTrace(request, fromUrl);

        log.debug("consumer invoke before: ");
        TraceContext.print();

        try {
            Object result = invoker.invoke();

            log.debug("consumer invoke after: ");
            TraceContext.print();

            log.debug("sr time: {}", RpcContext.getAttachments(TraceConstants.SR_TIME));
            log.debug("ss time: {}", RpcContext.getAttachments(TraceConstants.SS_TIME));

            this.endTrace(request, consumeSpan, watch, fromUrl, null);
            return result;
        } catch (Exception e) {
            this.endTrace(request, consumeSpan, watch, fromUrl, e);
            throw e;
        }
    }

    private Span startTrace(RpcRequest request, boolean fromUrl) {

        // start client span
        Span clientSpan = new Span();

        long id       = Ids.get();
        long traceId  = id;
        long parentId = id;

        // 判断是不是要创建新的span
        if (fromUrl) {
            // 来源于url,直接继承
            traceId = (TraceContext.getTraceId());
            clientSpan.setParent_id(parentId); // 这个使用不当,如果放在else分支,会导致zipkin ui js溢出
        } else {
            // 开始span
            TraceContext.start();
            TraceContext.setTraceId(id);
            TraceContext.setSpanId(id);
        }

        clientSpan.setId(id);
        clientSpan.setTrace_id(traceId);

        clientSpan.setName(request.getMethodName());

        long timestamp = TimeUtils.currentMicros();
        clientSpan.setTimestamp(timestamp);

        // cs annotation
        int providerHost = NetUtils.ip2Num(request.getContext().get(Const.SERVER_HOST));
        int providerPort = Integer.parseInt(request.getContext().get(Const.SERVER_PORT));

        clientSpan.addToAnnotations(
                Annotation.create(timestamp, TraceConstants.ANNO_CS,
                        Endpoint.create(request.getContext().getOrDefault(Const.SERVER_NAME, request.getClassName()), providerHost, providerPort)));

        String owners = request.getContext().get(Const.SERVER_OWNER);
        if (StringUtils.isNotEmpty(owners)) {
            // app owner
            clientSpan.addToBinary_annotations(BinaryAnnotation.create(
                    "负责人", owners, null
            ));
        }

        // attach trace data
        Map<String, String> attaches = request.getContext();
        attaches.put(TraceConstants.TRACE_ID, String.valueOf(clientSpan.getTrace_id()));
        attaches.put(TraceConstants.SPAN_ID, String.valueOf(clientSpan.getId()));

        return clientSpan;
    }

    private void endTrace(RpcRequest request, Span clientSpan, Stopwatch watch, boolean fromUrl, Exception e) {
        clientSpan.setDuration(watch.stop().elapsed(TimeUnit.MICROSECONDS));

        String host = RpcContext.getAttachments(Const.SERVER_HOST);
        int    port = Integer.parseInt(RpcContext.getAttachments(Const.SERVER_PORT));

        // cr annotation
        clientSpan.addToAnnotations(
                Annotation.create(TimeUtils.currentMicros(), TraceConstants.ANNO_CR,
                        Endpoint.create(request.getMethodName(), NetUtils.ip2Num(host), port)));

        if (null != e) {
            // attach exception
            clientSpan.addToBinary_annotations(BinaryAnnotation.create(
                    "Exception", Throwables.getStackTraceAsString(e), null));
        }

        // collect the span
        TraceContext.addSpan(clientSpan);

        // 来源于url的span,在本地发送
        if (!fromUrl) {
            // 将span发送出去
            agent.send(TraceContext.getSpans());
            TraceContext.clear();
        }

    }

}
