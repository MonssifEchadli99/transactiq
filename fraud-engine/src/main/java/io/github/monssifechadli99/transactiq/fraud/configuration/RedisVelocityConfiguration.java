package io.github.monssifechadli99.transactiq.fraud.configuration;

import io.github.monssifechadli99.transactiq.fraud.adapter.out.redis.RedisVelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityTrackingSettings;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration(proxyBeanMethods = false)
public class RedisVelocityConfiguration {

    @Bean
    RedisScript<List> recordVelocityAttemptScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/record_velocity_attempt.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    VelocityAttemptRecorder velocityAttemptRecorder(
            StringRedisTemplate redisTemplate,
            RedisScript<List> recordVelocityAttemptScript,
            VelocityTrackingSettings settings) {
        return new RedisVelocityAttemptRecorder(
                redisTemplate,
                recordVelocityAttemptScript,
                settings);
    }
}
