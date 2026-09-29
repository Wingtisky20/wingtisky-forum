package com.wingtisky.forum.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求分配一个 traceId，放进日志上下文与响应头。
 *
 * <p><b>解决的问题</b>：一个请求会经过过滤器、Controller、Service、Mapper，打出十几行日志。
 * 没有 traceId 时，多用户并发下这些日志是交织在一起的——用户说"我点保存报错了"，
 * 你很难从日志里捞出他那一次请求的完整轨迹。有了 traceId，一次请求的日志就能被串起来，
 * 用户把界面上的 traceId 报给你，直接精确定位。
 *
 * <p><b>M9 拆服务后这条会更重要</b>：跨进程后日志分散在多个服务里，traceId 是唯一的串联线索。
 * 现在把它做对，M9 不用返工。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名。前端出错时把它显示出来，用户截图即可。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 允许客户端传入的 traceId 格式。
     *
     * <p><b>为什么必须校验而不是直接采用</b>：这个值会被写进日志。若原样接受任意输入，
     * 攻击者可以塞入换行符伪造出整行日志（日志注入），把真实的入侵痕迹淹没在假记录里。
     * 限制字符集与长度后，它只能是安全的标识符。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        TraceIdHolder.set(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程是复用的，不清理会让下一个请求沿用上一个的 traceId，
            // 日志会串成一片。这是 MDC 最常见的误用。
            TraceIdHolder.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
