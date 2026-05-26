package com.omni.platform.application.dtos.stock;

/**
 * DTO representing a request to sync a stock.
 * This will be serialized to JSON and sent to the {@code stock-sync} Kafka topic.
 */
public class StockSyncRequest {

    private String symbol;
    private Integer limit;

    public StockSyncRequest() {
        // Default constructor for deserialization
    }

    public StockSyncRequest(String symbol, Integer limit) {
        this.symbol = symbol;
        this.limit = limit;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}