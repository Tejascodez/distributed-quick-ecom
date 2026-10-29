package com.ims.darkstore;

import java.io.IOException;

import com.uber.h3core.H3Core;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class H3Config {

    @Bean
    H3Core h3Core() throws IOException {
        return H3Core.newInstance();
    }
}
