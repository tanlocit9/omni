package com.omni.platform.modules.notifications.events;

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
