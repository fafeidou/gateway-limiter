# 简介
* SpringCloud Gateway 集成了redis做限流。限流作为网关最基本的功能，Spring Cloud Gateway
官方就提供了RequestRateLimiterGatewayFilterFactory这个类，使用Redis和lua脚本实现了令牌桶方式的限流.
这个filterFactory依赖RateLimiter及KeyResolver,其中KeyResolver用于从request中提取限流的key,
而RateLimiter则是相应的针对key的限流规则.
# 背景
* 之前使用了sentinel做了动态限流，还需要加一个中间件，可能有点复杂。调研了原生地限流方式，结合以下现在的业务需求，
需要做一个对不同租户的访问接口的限流。分解下需求：
1. 需要可以针对租户限流
2. 不同租户访问同一个接口的限流配置也不一样
3. 就是要对不同接口不同租户做不同的配置
4. 这些配置需要放到配置中心中，防止硬编码以后需要网关重启

* 现在原生的网关的调研
1. 可以针对租户限流
2. 可以针对某个接口限流也可以，但是接口粒度太粗,只针对一个路由进行限流

* 不足之处，不能满足我现在的需求，多接口，多租户，进行配置限流，于是就先看了下gateway是怎么实现限流的

# 分析spring cloud gateway 是怎么做的

## 分析org.springframework.cloud.gateway.filter.factory#RequestRateLimiterGatewayFilterFactory
1. RequestRateLimiterGatewayFilterFactory#apply,这个方法会返回一个GatewayFilter，显然就是filter原理
做的。
2. KeyResolver需要用户去配置，意思就是根绝什么条件路由，这个返回结果，作为redis令牌的依据。KeyResolver返回的结果
为空，那么就判断denyEmpty是否为false（默认为true）,如果false，终止当前线程；否则，过滤通过。
3. limiter.isAllowed，这个limiter是org.springframework.cloud.gateway.config#redisRateLimiter，gateway的默认实现，
后面的代码，也是基于这个去实现动态限流的。

```
public GatewayFilter apply(Config config) {
		KeyResolver resolver = getOrDefault(config.keyResolver, defaultKeyResolver);
		RateLimiter<Object> limiter = getOrDefault(config.rateLimiter,
				defaultRateLimiter);
		boolean denyEmpty = getOrDefault(config.denyEmptyKey, this.denyEmptyKey);
		HttpStatusHolder emptyKeyStatus = HttpStatusHolder
				.parse(getOrDefault(config.emptyKeyStatus, this.emptyKeyStatusCode));

		return (exchange, chain) -> resolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY)
				.flatMap(key -> {
					if (EMPTY_KEY.equals(key)) {
						if (denyEmpty) {
							setResponseStatus(exchange, emptyKeyStatus);
							return exchange.getResponse().setComplete();
						}
						return chain.filter(exchange);
					}
					return limiter.isAllowed(config.getRouteId(), key)
							.flatMap(response -> {

								for (Map.Entry<String, String> header : response
										.getHeaders().entrySet()) {
									exchange.getResponse().getHeaders()
											.add(header.getKey(), header.getValue());
								}

								if (response.isAllowed()) {
									return chain.filter(exchange);
								}

								setResponseStatus(exchange, config.getStatusCode());
								return exchange.getResponse().setComplete();
							});
				});
	}
```

## 分析org.springframework.cloud.gateway.filter.factory#GatewayRedisAutoConfiguration
1. 配置了redisRequestRateLimiterScript,用于读取request_rate_limiter.lua脚本，这个限流是基于redis令牌桶实现的，
gateway结合了lua脚本实现。
2. stringReactiveRedisTemplate，初始化了reactive的redis客户端
3. redisRateLimiter，这个就是上面提到的限流策略，主要限流逻辑在这个里面。顺便提一嘴（@ConditionalOnMissingBean这个用法，
值得我们学习，在自己写一些starter的时候，一定要考虑，代码代码的侵入性）
```
class GatewayRedisAutoConfiguration {

	@Bean
	@SuppressWarnings("unchecked")
	public RedisScript redisRequestRateLimiterScript() {
		DefaultRedisScript redisScript = new DefaultRedisScript<>();
		redisScript.setScriptSource(new ResourceScriptSource(
				new ClassPathResource("META-INF/scripts/request_rate_limiter.lua")));
		redisScript.setResultType(List.class);
		return redisScript;
	}

	@Bean
	// TODO: replace with ReactiveStringRedisTemplate in future
	public ReactiveRedisTemplate<String, String> stringReactiveRedisTemplate(
			ReactiveRedisConnectionFactory reactiveRedisConnectionFactory) {
		RedisSerializer<String> serializer = new StringRedisSerializer();
		RedisSerializationContext<String, String> serializationContext = RedisSerializationContext
				.<String, String>newSerializationContext().key(serializer)
				.value(serializer).hashKey(serializer).hashValue(serializer).build();
		return new ReactiveRedisTemplate<>(reactiveRedisConnectionFactory,
				serializationContext);
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisRateLimiter redisRateLimiter(
			ReactiveRedisTemplate<String, String> redisTemplate,
			@Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> redisScript,
			Validator validator) {
		return new RedisRateLimiter(redisTemplate, redisScript, validator);
	}

}
```

## 分析org.springframework.cloud.gateway.filter.factory#RedisRateLimiter

1. RedisRateLimiter#isAllowed,这个方法入参，routeId为gateway定义的那个routeId，id为KeyResolver返回
的结果。可以看下这个getKeys方法，返回了一个tokenKey和一个时间戳的key放到一个List中。

```
public Mono<Response> isAllowed(String routeId, String id) {
		if (!this.initialized.get()) {
			throw new IllegalStateException("RedisRateLimiter is not initialized");
		}

		Config routeConfig = loadConfiguration(routeId);

		// How many requests per second do you want a user to be allowed to do?
		// 一秒钟的请求次数，即qps，就是往令牌桶放的速度
		int replenishRate = routeConfig.getReplenishRate();

		// How much bursting do you want to allow?
		// 令牌桶有多大
		int burstCapacity = routeConfig.getBurstCapacity();

		try {
			List<String> keys = getKeys(id);

			// The arguments to the LUA script. time() returns unixtime in seconds.
			//LUA 脚本的参数，注意的是取出的时候从1开始，lua好像是这样定义的
			List<String> scriptArgs = Arrays.asList(replenishRate + "",
					burstCapacity + "", Instant.now().getEpochSecond() + "", "1");
			// allowed, tokens_left = redis.eval(SCRIPT, keys, args)
			// eval和evalsha是redis2.6支持lua的执行，Redis使用Lua解释器解释Lua脚本过程中，会保证脚本是以原子性的方式执行
			Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys,
					scriptArgs);
			// .log("redisratelimiter", Level.FINER);
			return flux.onErrorResume(throwable -> Flux.just(Arrays.asList(1L, -1L)))
					.reduce(new ArrayList<Long>(), (longs, l) -> {
						longs.addAll(l);
						return longs;
					}).map(results -> {
					    //可以看到lua脚本返回多个返回值，一个是时候能够拿到令牌的标识，一个是剩余的令牌
						boolean allowed = results.get(0) == 1L;
						Long tokensLeft = results.get(1);

						Response response = new Response(allowed,
								getHeaders(routeConfig, tokensLeft));

						if (log.isDebugEnabled()) {
							log.debug("response: " + response);
						}
						return response;
					});
		}
		catch (Exception e) {
			/*
			 * We don't want a hard dependency on Redis to allow traffic. Make sure to set
			 * an alert so you know if this is happening too much. Stripe's observed
			 * failure rate is 0.01%.
			 */
			log.error("Error determining if user allowed from redis", e);
		}
		return Mono.just(new Response(true, getHeaders(routeConfig, -1L)));
	}
	
	static List<String> getKeys(String id) {
    		// use `{}` around keys to use Redis Key hash tags
    		// this allows for using redis cluster
    
    		// Make a unique key per user.
    		String prefix = "request_rate_limiter.{" + id;
    
    		// You need two Redis keys for Token Bucket.
    		String tokenKey = prefix + "}.tokens";
    		String timestampKey = prefix + "}.timestamp";
    		return Arrays.asList(tokenKey, timestampKey);
    }
```

## 分析这个lua脚本

```
-- token的key
local tokens_key = KEYS[1]
-- 时间戳的key
local timestamp_key = KEYS[2]
--redis.log(redis.LOG_WARNING, "tokens_key " .. tokens_key)

-- 往令牌桶里面放令牌的速率，一秒多少个
local rate = tonumber(ARGV[1])
-- 令牌桶的数量
local capacity = tonumber(ARGV[2])
-- 当前的时间戳
local now = tonumber(ARGV[3])
-- 请求令牌的数量
local requested = tonumber(ARGV[4])

-- 放满令牌桶是的时长
local fill_time = capacity/rate
-- redis 过期时间 这里为什么是放满令牌桶的两倍
-- 这个时间不能太长，加入太长10s，你第一秒把令牌拿完，后面9s，就会出现突刺现象
local ttl = math.floor(fill_time*2)

-- 获取令牌桶的数量，如果为空，将令牌桶容量赋值
local last_tokens = tonumber(redis.call("get", tokens_key))
if last_tokens == nil then
    last_tokens = capacity
end
--redis.log(redis.LOG_WARNING, "last_tokens " .. last_tokens)
-- 获取最后的更新时间戳，如果为空，设置为0
local last_refreshed = tonumber(redis.call("get", timestamp_key))
if last_refreshed == nil then
    last_refreshed = 0
end
--redis.log(redis.LOG_WARNING, "last_refreshed " .. last_refreshed)
-- 计算出时间间隔
local delta = math.max(0, now-last_refreshed)
-- 该往令牌桶放令牌的数量
local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
-- 看剩余的令牌是否能够获取到
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens
-- 零代表false
local allowed_num = 0
-- 如果允许获取得到，计算出剩余的令牌数量，并标记可以获取
if allowed then
    new_tokens = filled_tokens - requested
    allowed_num = 1
end

-- 存到redis
redis.call("setex", tokens_key, ttl, new_tokens)
redis.call("setex", timestamp_key, ttl, now)
--  lua可以返回多个字段，java获取时用List获取
return { allowed_num, new_tokens }

```


# 开始根据业务需求重构

##  创建RateLimiterConf，这个用于配置限流策略

```
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
```
## 创建SystemRedisRateLimiter,重写isAllowed方法。


```
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
```
## 覆盖默认配置
* 初始化KeyResolver，以及SystemRedisRateLimiter
```
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
```
## 最后就是配置了
```
server.port: 8081

spring:
  application:
    name: gateway
  redis:
    host: localhost
    port: 6379
  cloud:
    gateway:
      routes:
      - id: rateLimit_route
        uri: http://localhost:8088/hello
        order: 0
        predicates:
        - Path=/rateLimit/**
        filters:
        #filter名称必须是RequestRateLimiter
        - name: RequestRateLimiter
          args:
            rate-limiter: "#{@systemRedisRateLimiter}"
            key-resolver: "#{@sysKeyResolver}"
ratelimiter-conf:
  #配置限流参数与RateLimiterConf类映射
  rateLimitMap:
    #格式为：routeid(gateway配置固定).token+path.replenishRate(流速)/burstCapacity令牌桶大小
    rateLimit_route.t1.rateLimit-hello.replenishRate: 1
    rateLimit_route.t1.rateLimit-hello.burstCapacity: 5

logging:
  level:
    #org.springframework.cloud.gateway: debug
    com.batman.gateway: debug
```
* 频繁访问下面链接，注意查看加上header为t1和其他值的区别
```
  curl http://localhost:8081/rateLimit/hello
```

# 总结
1. 不足先做api限流，然后业务限流，可以参考 https://www.cnblogs.com/qianwei/p/10127700.html
2. 如果有更好实现请留言。