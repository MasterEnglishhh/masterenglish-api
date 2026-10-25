package com.masterenglish.api;

import org.springframework.boot.SpringApplication;

public class TestMasterenglishApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(MasterenglishApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
