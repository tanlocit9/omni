package com.omni.platform.modules.scheduler.notifications;

import java.util.List;

public record SignalDigestItem(
        String symbolKey,
        String previousSignal,
        String newSignal,
        String price,
        String signalDate,
        String strategy,
        String timeframe,
        String score,
        List<String> reasonCodes) {
}
