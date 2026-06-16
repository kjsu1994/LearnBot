package com.learnbot;

import com.learnbot.config.LearnBotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LearnBotProperties.class)
public class LearnBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(LearnBotApplication.class, args);
    }
}