package com.lonple.dap.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.lonple.dap.server.**.mapper")
public class DapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DapServerApplication.class, args);
    }
}
