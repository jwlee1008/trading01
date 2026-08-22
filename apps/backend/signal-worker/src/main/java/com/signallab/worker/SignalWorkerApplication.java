package com.signallab.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.worker.domain.marketdata.service.MarketDataImportService;
import com.signallab.worker.domain.order.service.PostgresPaperOrderProcessor;
import com.signallab.worker.domain.outbox.service.PostgresOutboxDispatcher;
import com.signallab.worker.domain.ranking.service.PostgresRankedBuyCycle;
import com.signallab.worker.domain.signal.service.PostgresDailySignalCycle;
import com.signallab.worker.domain.signal.service.PostgresSellSignalCycle;
import com.signallab.worker.global.config.WorkerProperties;
import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class SignalWorkerApplication {

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(SignalWorkerApplication.class, args);
    }

    private static void loadDotenv() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path normalized = directory.resolve(".env");
            if (!Files.isRegularFile(normalized)) continue;
            Dotenv dotenv = Dotenv.configure()
                .directory(normalized.getParent().toString())
                .filename(normalized.getFileName().toString())
                .ignoreIfMissing()
                .load();
            dotenv.entries().forEach(entry -> {
                if (!entry.getValue().isBlank()
                    && System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
            return;
        }
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    CommandLineRunner startup(WorkerProperties properties, PostgresOutboxDispatcher outboxDispatcher, PostgresDailySignalCycle dailySignalCycle, PostgresRankedBuyCycle rankedBuyCycle, PostgresSellSignalCycle sellSignalCycle, PostgresPaperOrderProcessor paperOrderProcessor, MarketDataImportService marketDataImportService) {
        return args -> {
            if (!"none".equals(properties.getMarketDataAction()) && !properties.getMarketDataAction().isBlank()) {
                System.out.println(marketDataImportService.run(properties));
                System.exit(0);
            }
            if (properties.isOnce()) {
                System.out.println(dailySignalCycle.run(properties));
                System.out.println(rankedBuyCycle.run(properties));
                System.out.println(sellSignalCycle.run(properties));
                System.out.println(paperOrderProcessor.process(properties));
                System.out.println(outboxDispatcher.dispatch(properties));
                System.exit(0);
            }
        };
    }
}
