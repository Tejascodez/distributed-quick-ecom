package com.ims.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UserAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAuthApplication.class, args);
    }
}
