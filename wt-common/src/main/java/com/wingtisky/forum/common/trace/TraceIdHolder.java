package com.wingtisky.forum.common.trace;

import org.slf4j.MDC;

/**
 * traceId 的读写入口。
 *
 * <p>底层用 SLF4J 的 {@link MDC}，它把 traceId 绑在**当前线程**上，日志的
 * {@code %X{traceId}} 占位符就能自动带上，不需要在每个 log 调用里手写。
 *
 * <p><b>为什么单独抽一个类</b>：业务代码只该知道"我要拿 traceId"，不该知道它是用
 * MDC 实现的。将来若要换成别的传播机制（比如跨服务的链路追踪），只改这里。
 */
public final class TraceIdHolder {

    /** 日志配置文件里的占位符名：{@code %X{traceId}}。 */
    public static final String MDC_KEY = "traceId";

    private TraceIdHolder() {
    }

    /** 当前线程的 traceId；不在请求上下文中时返回 {@code null}。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    static void clear() {
        MDC.remove(MDC_KEY);
    }
}
