package com.batman.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
//可以针对同一个服务中不同接口进行限流，但是不利于不同接口不用用户的配置限流
public class RemoteAddrKeyResolver implements KeyResolver {

    private String header = "t1";

    private String path = "/rateLimit/hello";

    public static final String BEAN_NAME = "remoteAddrKeyResolver";

    //path
        //apiPath
        //header
        //limit
    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        if (exchange.getRequest().getPath().toString().equals(path)) {
            return Mono.just(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        }
        return Mono.just("____EMPTY_KEY__");
    }

}

