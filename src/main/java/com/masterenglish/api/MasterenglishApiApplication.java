package com.masterenglish.api;

import com.masterenglish.api.infrastructure.runner.ContentImportCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MasterenglishApiApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--content-import")) {
            ContentImportCommand.run(
                    Arrays.stream(args)
                            .filter(argument -> !argument.equals("--content-import"))
                            .toArray(String[]::new));
            return;
        }
        SpringApplication.run(MasterenglishApiApplication.class, args);
    }
}
