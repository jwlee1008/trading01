package com.signallab.worker;

import com.signallab.worker.domain.order.service.PostgresPaperOrderProcessor;
import com.signallab.worker.domain.outbox.service.PostgresOutboxDispatcher;
import com.signallab.worker.domain.ranking.service.PostgresRankedBuyCycle;
import com.signallab.worker.domain.signal.service.PostgresDailySignalCycle;
import com.signallab.worker.domain.signal.service.PostgresSellSignalCycle;
import com.signallab.worker.domain.marketdata.service.MarketDataImportService;
import com.signallab.worker.global.config.WorkerProperties;
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

    WorkerScheduler(WorkerProperties properties, PostgresOutboxDispatcher outboxDispatcher, PostgresDailySignalCycle dailySignalCycle, PostgresRankedBuyCycle rankedBuyCycle, PostgresSellSignalCycle sellSignalCycle, PostgresPaperOrderProcessor paperOrderProcessor, MarketDataImportService marketDataImportService) {
        this.properties = properties;
        this.outboxDispatcher = outboxDispatcher;
        this.dailySignalCycle = dailySignalCycle;
        this.rankedBuyCycle = rankedBuyCycle;
        this.sellSignalCycle = sellSignalCycle;
        this.paperOrderProcessor = paperOrderProcessor;
        this.marketDataImportService = marketDataImportService;
    }

    @Scheduled(cron = "${signal.worker.outbox-cron:0 */1 * * * *}", zone = "Asia/Seoul")
    synchronized void dispatchOutbox() {
        if (properties.isEnabled()) {
            System.out.println(dailySignalCycle.run(properties));
            System.out.println(rankedBuyCycle.run(properties));
            System.out.println(sellSignalCycle.run(properties));
            System.out.println(paperOrderProcessor.process(properties));
            System.out.println(outboxDispatcher.dispatch(properties));
        }
    }

    @Scheduled(initialDelayString = "${signal.worker.market-data-auto-initial-delay-ms:5000}",
        fixedDelayString = "${signal.worker.market-data-auto-interval-ms:300000}")
    synchronized void refreshMarketData() {
        if (properties.isEnabled() && properties.isMarketDataAutoEnabled()) {
            try {
                System.out.println(marketDataImportService.automaticTop10Refresh(properties));
            } catch (RuntimeException error) {
                System.err.println("automatic-market-data-refresh failed: " + error.getMessage());
            }
        }
    }
}
