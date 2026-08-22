package com.signallab.api;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SignalApiApplication {

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(SignalApiApplication.class, args);
    }

    private static void loadDotenv() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve(".env");
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            Dotenv dotenv = Dotenv.configure()
                .directory(candidate.getParent().toString())
                .filename(candidate.getFileName().toString())
                .ignoreIfMissing()
                .load();
            dotenv.entries().forEach(entry -> {
                if (System.getProperty(entry.getKey()) == null
                    && System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
            loadProjectGeminiSettings(candidate);
            return;
        }
    }

    private static void loadProjectGeminiSettings(Path dotenvFile) {
        try {
            Files.readAllLines(dotenvFile).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("GEMINI_") && line.contains("="))
                .forEach(line -> {
                    int separator = line.indexOf('=');
                    String key = line.substring(0, separator).trim();
                    String value = line.substring(separator + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    System.setProperty(key, value);
                });
        } catch (IOException exception) {
            throw new IllegalStateException("프로젝트 Gemini 설정을 읽을 수 없습니다.", exception);
        }
    }
}
