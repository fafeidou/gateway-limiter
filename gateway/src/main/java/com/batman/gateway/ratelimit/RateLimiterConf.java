package com.batman.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
@ConfigurationProperties(prefix = "ratelimiter-conf")
public class RateLimiterConf {
    //处理速度
    private static final String DEFAULT_REPLENISHRATE = "default.replenishRate";
    //容量
    private static final String DEFAULT_BURSTCAPACITY = "default.burstCapacity";

    private Map<String, Integer> rateLimitMap = new ConcurrentHashMap<String, Integer>() {
        {
            put(DEFAULT_REPLENISHRATE, 100);
            put(DEFAULT_BURSTCAPACITY, 1000);
        }
    };

    public Map<String, Integer> getRateLimitMap() {
        return rateLimitMap;
    }

    public void setRateLimitMap(Map<String, Integer> rateLimitMap) {
        this.rateLimitMap = rateLimitMap;
    }
}
