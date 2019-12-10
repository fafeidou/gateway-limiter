package com.batman.gateway.ratelimit;

import org.springframework.beans.BeansException;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.constraints.Min;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SystemRedisRateLimiter extends AbstractRateLimiter<SystemRedisRateLimiter.Config> implements ApplicationContextAware {

    public static final String REPLENISH_RATE_KEY = "replenishRate";

    public static final String BURST_CAPACITY_KEY = "burstCapacity";

    public static final String CONFIGURATION_PROPERTY_NAME = "sys-redis-rate-limiter";
    public static final String REDIS_SCRIPT_NAME = "redisRequestRateLimiterScript";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    public static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";

    //处理速度
    private static final String DEFAULT_REPLENISHRATE = "default.replenishRate";
    //容量
    private static final String DEFAULT_BURSTCAPACITY = "default.burstCapacity";

    private ReactiveRedisTemplate<String, String> redisTemplate;
    private RedisScript<List<Long>> script;
    private AtomicBoolean initialized = new AtomicBoolean(false);

    private String remainingHeader = REMAINING_HEADER;

    /**
     * The name of the header that returns the replenish rate configuration.
     */
    private String replenishRateHeader = REPLENISH_RATE_HEADER;

    /**
     * The name of the header that returns the burst capacity configuration.
     */
    private String burstCapacityHeader = BURST_CAPACITY_HEADER;

    private Config defaultConfig;

    public SystemRedisRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate,
                                  RedisScript<List<Long>> script, Validator validator) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, validator);
        this.redisTemplate = redisTemplate;
        this.script = script;
        initialized.compareAndSet(false, true);
    }

    public SystemRedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, null);
        defaultConfig = new Config()
                .setReplenishRate(defaultReplenishRate)
                .setBurstCapacity(defaultBurstCapacity);

    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        if (!this.initialized.get()) {
            throw new IllegalStateException("RedisRateLimiter is not initialized");
        }
        if (ObjectUtils.isEmpty(rateLimiterConf)) {
            throw new IllegalArgumentException("No Configuration found for route " + routeId);
        }
        Map<String, Integer> rateLimitMap = rateLimiterConf.getRateLimitMap();
        //缓存的key
        String replenishRateKey = routeId + "." + id + "." + REPLENISH_RATE_KEY;

        int replenishRate = ObjectUtils.isEmpty(rateLimitMap.get(replenishRateKey)) ? rateLimitMap.get(DEFAULT_REPLENISHRATE) : rateLimitMap.get(replenishRateKey);
        //容量key
        String burstCapacityKey = routeId + "." + id + "." + BURST_CAPACITY_KEY;

        int burstCapacity = ObjectUtils.isEmpty(rateLimitMap.get(burstCapacityKey)) ? rateLimitMap.get(DEFAULT_BURSTCAPACITY) : rateLimitMap.get(burstCapacityKey);

        try {
            List<String> keys = getKeys(id);

            List<String> scriptArgs = Arrays.asList(replenishRate + "", burstCapacity + "",
                    Instant.now().getEpochSecond() + "", "1");
            Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys, scriptArgs);

            return flux.onErrorResume(throwable -> Flux.just(Arrays.asList(1L, -1L)))
                    .reduce(new ArrayList<Long>(), (longs, l) -> {
                        longs.addAll(l);
                        return longs;
                    }).map(results -> {
                        boolean allowed = results.get(0) == 1L;
                        Long tokensLeft = results.get(1);

                        RateLimiter.Response response = new RateLimiter.Response(allowed, getHeaders(replenishRate, burstCapacity, tokensLeft));

                        return response;
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Mono.just(new RateLimiter.Response(true, getHeaders(replenishRate, burstCapacity, -1L)));
    }

    private RateLimiterConf rateLimiterConf;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.rateLimiterConf = applicationContext.getBean(RateLimiterConf.class);
    }

    public HashMap<String, String> getHeaders(Integer replenishRate, Integer burstCapacity, Long tokensLeft) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put(this.remainingHeader, tokensLeft.toString());
        headers.put(this.replenishRateHeader, String.valueOf(replenishRate));
        headers.put(this.burstCapacityHeader, String.valueOf(burstCapacity));
        return headers;
    }

    static List<String> getKeys(String id) {
        // use `{}` around keys to use Redis Key hash tags
        // this allows for using redis cluster

        // Make a unique key per user.
        String prefix = "request_sys_rate_limiter.{" + id;

        // You need two Redis keys for Token Bucket.
        String tokenKey = prefix + "}.tokens";
        String timestampKey = prefix + "}.timestamp";
        return Arrays.asList(tokenKey, timestampKey);
    }


    @Validated
    public static class Config {
        @Min(1)
        private int replenishRate;
        @Min(1)
        private int burstCapacity = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        @Override
        public String toString() {
            return "Config{" +
                    "replenishRate=" + replenishRate +
                    ", burstCapacity=" + burstCapacity +
                    '}';
        }
    }
}
