package com.masterenglish.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MasterenglishApiApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--content-import")) {
            ContentImportCommand.run(
                    java.util.Arrays.stream(args)
                            .filter(argument -> !argument.equals("--content-import"))
                            .toArray(String[]::new));
            return;
        }
        SpringApplication.run(MasterenglishApiApplication.class, args);
    }
}
