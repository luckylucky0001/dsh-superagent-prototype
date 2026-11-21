package dev.fellowloop.server.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the daemon download at {@code /downloads/fellowloop-daemon.jar} when a downloads directory is set. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final String downloads;

  public WebConfig(@Value("${fellowloop.downloads-dir:}") String downloads) {
    this.downloads = downloads;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (downloads != null && !downloads.isBlank() && Files.isDirectory(Path.of(downloads))) {
      registry.addResourceHandler("/downloads/**").addResourceLocations(Path.of(downloads).toUri().toString());
    }
  }
}
