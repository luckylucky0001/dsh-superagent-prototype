package dev.fellowloop.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FellowloopServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(FellowloopServerApplication.class, args);
  }
}
