package com.rigour.shared.context;

/**
 * 当前线程的请求上下文。
 * 只保存经过请求入口解析的横切信息；异步任务必须显式复制上下文，不能依赖 ThreadLocal 自动传播。
 */
public final class RequestContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId, String acceptLanguage) {
        HOLDER.set(new State(requestId, acceptLanguage));
    }

    public static String getRequestId() {
        State state = HOLDER.get();
        return state == null ? null : state.requestId();
    }

    public static String getAcceptLanguage() {
        State state = HOLDER.get();
        return state == null ? null : state.acceptLanguage();
    }

    public static void clear() {
        HOLDER.remove();
    }

    private record State(String requestId, String acceptLanguage) {
    }
}
