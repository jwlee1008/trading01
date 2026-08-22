package com.signallab.worker;

import com.signallab.worker.domain.order.service.PostgresPaperOrderProcessor;
import com.signallab.worker.domain.outbox.service.PostgresOutboxDispatcher;
import com.signallab.worker.domain.ranking.service.PostgresRankedBuyCycle;
import com.signallab.worker.domain.signal.service.PostgresDailySignalCycle;
import com.signallab.worker.domain.signal.service.PostgresSellSignalCycle;
import com.signallab.worker.domain.marketdata.service.MarketDataImportService;
import com.signallab.worker.global.config.WorkerProperties;
import com.signallab.worker.global.runtime.WorkerTaskRunner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class WorkerScheduler {
    private final WorkerProperties properties;
    private final PostgresOutboxDispatcher outboxDispatcher;
    private final PostgresDailySignalCycle dailySignalCycle;
    private final PostgresRankedBuyCycle rankedBuyCycle;
    private final PostgresSellSignalCycle sellSignalCycle;
    private final PostgresPaperOrderProcessor paperOrderProcessor;
    private final MarketDataImportService marketDataImportService;
    private final WorkerTaskRunner taskRunner;

    WorkerScheduler(WorkerProperties properties, PostgresOutboxDispatcher outboxDispatcher, PostgresDailySignalCycle dailySignalCycle, PostgresRankedBuyCycle rankedBuyCycle, PostgresSellSignalCycle sellSignalCycle, PostgresPaperOrderProcessor paperOrderProcessor, MarketDataImportService marketDataImportService, WorkerTaskRunner taskRunner) {
        this.properties = properties;
        this.outboxDispatcher = outboxDispatcher;
        this.dailySignalCycle = dailySignalCycle;
        this.rankedBuyCycle = rankedBuyCycle;
        this.sellSignalCycle = sellSignalCycle;
        this.paperOrderProcessor = paperOrderProcessor;
        this.marketDataImportService = marketDataImportService;
        this.taskRunner = taskRunner;
    }

    @Scheduled(cron = "${signal.worker.outbox-cron:0 */1 * * * *}", zone = "Asia/Seoul")
    synchronized void dispatchOutbox() {
        if (properties.isEnabled()) {
            String runKey = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            taskRunner.run("signal", runKey, () -> dailySignalCycle.run(properties));
            taskRunner.run("ranking", runKey, () -> rankedBuyCycle.run(properties));
            taskRunner.run("sell-signal", runKey, () -> sellSignalCycle.run(properties));
            taskRunner.run("paper-fill", runKey, () -> paperOrderProcessor.process(properties));
            taskRunner.run("notification", runKey, () -> outboxDispatcher.dispatch(properties));
        }
    }

    @Scheduled(initialDelayString = "${signal.worker.market-data-auto-initial-delay-ms:5000}",
        fixedDelayString = "${signal.worker.market-data-auto-interval-ms:300000}")
    synchronized void refreshMarketData() {
        if (properties.isEnabled() && properties.isMarketDataAutoEnabled()) {
            String runKey = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            taskRunner.run("market-data", runKey, () -> marketDataImportService.automaticTop10Refresh(properties));
        }
    }
}
