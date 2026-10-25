package myexpressionfriend_api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${app.storage.base-path:uploads}")
    private String storageBasePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path storagePath = Paths.get(storageBasePath).toAbsolutePath().normalize();
        String location = storagePath.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
