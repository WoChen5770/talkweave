package io.github.wochen5770.talkweave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AssistantApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(AssistantApplication.class);
        // The production entry point cannot re-enable the legacy file-based authorization path.
        application.setAdditionalProfiles("managed");
        application.run(args);
    }
}
