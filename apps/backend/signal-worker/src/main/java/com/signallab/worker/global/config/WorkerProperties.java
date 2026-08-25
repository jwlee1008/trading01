package com.signallab.worker.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "signal.worker")
public class WorkerProperties {

    private boolean once;
    private boolean enabled = false;
    private String pushProvider = "disabled";
    private int outboxBatchSize = 50;
    private int outboxLeaseSeconds = 300;
    private boolean dailyCycleEnabled;
    private String expectedThrough = "";
    private int candleLookback = 500;
    private boolean sellCycleEnabled;
    private boolean marketDataAutoEnabled;
    private int marketDataAutoBatchSize = 25;
    private String marketDataAction = "none";
    private String marketDataFrom = "";
    private String marketDataThrough = "";
    private String marketDataSymbols = "";
    private String marketDataDatasetVersion = "kiwoom-d1-v1";
    private String marketCalendarVersion = "";
    private String marketCalendarHolidays = "";
    private String marketCalendarExtraSessions = "";
    private String kiwoomBaseUrl = "";
    private String kiwoomMode = "real";
    private String kiwoomAppKey = "";
    private String kiwoomAppSecret = "";
    private String kiwoomDemoAppKey = "";
    private String kiwoomDemoAppSecret = "";
    private int kiwoomMaxPages = 30;
    private int backfillChunkDays = 180;
    private int backfillMaxRetries = 3;
    private int backfillRequestDelayMs = 250;
    private int backfillMaxInstruments = 0;
    private boolean backfillDryRun;
    private String universeEffectiveFrom = "2026-08-01";
    private String universeSourceRevision = "";
    private String top100AsOf = "";
    private String backfillUniverseKind = "";
    private int taskMaxRetries = 3;
    private int taskRetryDelayMs = 500;

    public boolean isOnce() { return once; }
    public void setOnce(boolean once) { this.once = once; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPushProvider() { return pushProvider; }
    public void setPushProvider(String pushProvider) { this.pushProvider = pushProvider; }
    public int getOutboxBatchSize() { return outboxBatchSize; }
    public void setOutboxBatchSize(int outboxBatchSize) { this.outboxBatchSize = outboxBatchSize; }
    public int getOutboxLeaseSeconds() { return outboxLeaseSeconds; }
    public void setOutboxLeaseSeconds(int outboxLeaseSeconds) { this.outboxLeaseSeconds = outboxLeaseSeconds; }
    public boolean isDailyCycleEnabled() { return dailyCycleEnabled; }
    public void setDailyCycleEnabled(boolean dailyCycleEnabled) { this.dailyCycleEnabled = dailyCycleEnabled; }
    public String getExpectedThrough() { return expectedThrough; }
    public void setExpectedThrough(String expectedThrough) { this.expectedThrough = expectedThrough; }
    public int getCandleLookback() { return candleLookback; }
    public void setCandleLookback(int candleLookback) { this.candleLookback = candleLookback; }
    public boolean isSellCycleEnabled() { return sellCycleEnabled; }
    public void setSellCycleEnabled(boolean value) { this.sellCycleEnabled = value; }
    public boolean isMarketDataAutoEnabled() { return marketDataAutoEnabled; }
    public void setMarketDataAutoEnabled(boolean value) { this.marketDataAutoEnabled = value; }
    public int getMarketDataAutoBatchSize() { return marketDataAutoBatchSize; }
    public void setMarketDataAutoBatchSize(int value) { this.marketDataAutoBatchSize = value; }
    public String getMarketDataAction() { return marketDataAction; }
    public void setMarketDataAction(String value) { this.marketDataAction = value; }
    public String getMarketDataFrom() { return marketDataFrom; }
    public void setMarketDataFrom(String value) { this.marketDataFrom = value; }
    public String getMarketDataThrough() { return marketDataThrough; }
    public void setMarketDataThrough(String value) { this.marketDataThrough = value; }
    public String getMarketDataSymbols() { return marketDataSymbols; }
    public void setMarketDataSymbols(String value) { this.marketDataSymbols = value; }
    public String getMarketDataDatasetVersion() { return marketDataDatasetVersion; }
    public void setMarketDataDatasetVersion(String value) { this.marketDataDatasetVersion = value; }
    public String getMarketCalendarVersion() { return marketCalendarVersion; }
    public void setMarketCalendarVersion(String value) { this.marketCalendarVersion = value; }
    public String getMarketCalendarHolidays() { return marketCalendarHolidays; }
    public void setMarketCalendarHolidays(String value) { this.marketCalendarHolidays = value; }
    public String getMarketCalendarExtraSessions() { return marketCalendarExtraSessions; }
    public void setMarketCalendarExtraSessions(String value) { this.marketCalendarExtraSessions = value; }
    public String getKiwoomBaseUrl() { return kiwoomBaseUrl; }
    public void setKiwoomBaseUrl(String value) { this.kiwoomBaseUrl = value; }
    public String getKiwoomMode() { return kiwoomMode; }
    public void setKiwoomMode(String value) { this.kiwoomMode = value; }
    public String getKiwoomAppKey() { return kiwoomAppKey; }
    public void setKiwoomAppKey(String value) { this.kiwoomAppKey = value; }
    public String getKiwoomAppSecret() { return kiwoomAppSecret; }
    public void setKiwoomAppSecret(String value) { this.kiwoomAppSecret = value; }
    public String getKiwoomDemoAppKey() { return kiwoomDemoAppKey; }
    public void setKiwoomDemoAppKey(String value) { this.kiwoomDemoAppKey = value; }
    public String getKiwoomDemoAppSecret() { return kiwoomDemoAppSecret; }
    public void setKiwoomDemoAppSecret(String value) { this.kiwoomDemoAppSecret = value; }
    public int getKiwoomMaxPages() { return kiwoomMaxPages; }
    public void setKiwoomMaxPages(int value) { this.kiwoomMaxPages = value; }
    public int getBackfillChunkDays() { return backfillChunkDays; }
    public void setBackfillChunkDays(int value) { this.backfillChunkDays = value; }
    public int getBackfillMaxRetries() { return backfillMaxRetries; }
    public void setBackfillMaxRetries(int value) { this.backfillMaxRetries = value; }
    public int getBackfillRequestDelayMs() { return backfillRequestDelayMs; }
    public void setBackfillRequestDelayMs(int value) { this.backfillRequestDelayMs = value; }
    public int getBackfillMaxInstruments() { return backfillMaxInstruments; }
    public void setBackfillMaxInstruments(int value) { this.backfillMaxInstruments = value; }
    public boolean isBackfillDryRun() { return backfillDryRun; }
    public void setBackfillDryRun(boolean value) { this.backfillDryRun = value; }
    public String getUniverseEffectiveFrom() { return universeEffectiveFrom; }
    public void setUniverseEffectiveFrom(String value) { this.universeEffectiveFrom = value; }
    public String getUniverseSourceRevision() { return universeSourceRevision; }
    public void setUniverseSourceRevision(String value) { this.universeSourceRevision = value; }
    public String getTop100AsOf() { return top100AsOf; }
    public void setTop100AsOf(String value) { this.top100AsOf = value; }
    public String getBackfillUniverseKind() { return backfillUniverseKind; }
    public void setBackfillUniverseKind(String value) { this.backfillUniverseKind = value; }
    public int getTaskMaxRetries() { return taskMaxRetries; }
    public void setTaskMaxRetries(int value) { this.taskMaxRetries = value; }
    public int getTaskRetryDelayMs() { return taskRetryDelayMs; }
    public void setTaskRetryDelayMs(int value) { this.taskRetryDelayMs = value; }
}
