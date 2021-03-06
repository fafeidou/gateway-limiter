package com.batman.gateway;

import com.batman.gateway.ratelimit.SystemRedisRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.Validator;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@SpringBootApplication
public class GatewayApplication {
    @Autowired
    private SystemRedisRateLimiter systemRedisRateLimiter;

    @Autowired
    private RequestRateLimiterGatewayFilterFactory requestRateLimiterGatewayFilterFactory;

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

//    @Bean
//    public RouteLocator myRoutes(RouteLocatorBuilder builder) {
//        return builder.routes()
//                .route("rateLimit_route", p -> p
//                        .order(0)
//                        .path("/rateLimit/**")
//                        .uri("http://localhost:8088/hello")
//                        .filter(requestRateLimiterGatewayFilterFactory.apply(config -> {
//                            config.setRateLimiter(systemRedisRateLimiter);
//                            config.setKeyResolver(this.sysKeyResolver());
//                            config.setRouteId("rateLimit_route");
//                        })))
//                .build();
//    }

    @Bean
    KeyResolver sysKeyResolver() {
        return exchange -> {
            List<String> openAPiToken = exchange.getRequest().getHeaders().get("X-Open-Api-Token");
            if (CollectionUtils.isEmpty(openAPiToken)) {
                return Mono.just("____EMPTY_KEY__");
            }
            Optional<String> pathOptional = Optional.of(exchange.getRequest().getPath().toString());
            String path="";
            if (pathOptional.isPresent()) {
                path = pathOptional.get().substring(1).replace("/","-");

            }
            return Mono.just(openAPiToken.get(0) + "." + path);
        };
    }

    @Bean
    @Primary
    SystemRedisRateLimiter systemRedisRateLimiter(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Qualifier(SystemRedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            Validator validator) {
        return new SystemRedisRateLimiter(redisTemplate, script, validator);
    }
}
