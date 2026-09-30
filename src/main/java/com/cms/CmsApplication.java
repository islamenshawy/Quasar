package com.cms;

import com.cms.hsm.PayShieldClient;
import com.cms.hsm.PinService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmsApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    PayShieldClient payShieldClient(
            @Value("${cms.hsm.host}") String host,
            @Value("${cms.hsm.port}") int port,
            @Value("${cms.hsm.header-length}") int headerLength,
            @Value("${cms.hsm.pool-size}") int poolSize,
            @Value("${cms.hsm.connect-timeout-ms}") int connectTimeout,
            @Value("${cms.hsm.read-timeout-ms}") int readTimeout,
            @Value("${cms.hsm.borrow-timeout-ms}") int borrowTimeout) {
        return new PayShieldClient(new PayShieldClient.Config(
                host, port, headerLength, poolSize, connectTimeout, readTimeout, borrowTimeout));
    }

    @Bean
    PinService pinService(PayShieldClient hsm) {
        return new PinService(hsm);
    }
}
