# VCI Intraday Adapter vnstock 4.x Migration

## Context

The ingestor service's [`VCIIntradayQuoteAdapter`](../../apps/ingestor/app/stocks/clients/vci_intraday.py) was failing with `TypeError: Quote.intraday() got an unexpected keyword argument 'page'` when processing P9-I1 intraday EOD jobs.

## Problem

**Error Pattern**: `TypeError` during vnstock API call

**Stack Trace**:

```python
File "apps/ingestor/app/stocks/clients/vci_intraday.py", line 58, in _fetch_page
    frame = quote.intraday(**kwargs)
File ".venv/Lib/site-packages/vnai/beam/patching.py", line 296, in intraday_with_limit
    df = original_intraday(*args, **kwargs)
TypeError: Quote.intraday() got an unexpected keyword argument 'page'
```

**Root Cause**: vnstock 4.x removed pagination support from the `Quote.intraday()` API

The original adapter implementation attempted cursor-based pagination using:

- `page_size` parameter to control batch size
- `last_time` cursor for pagination
- Complex loop logic to detect cursor advancement

However, vnstock 4.x API no longer accepts these parameters.

**Impact**: All P9-I1 intraday EOD ingestion blocked for HOSE, HNX, and UPCOM exchanges.

## Solution

Simplified [`VCIIntradayQuoteAdapter`](../../apps/ingestor/app/stocks/clients/vci_intraday.py) to fetch complete trading day data in a single API call, matching vnstock 4.x capabilities.

### Implementation Changes

**Removed**:

- `page_size` constructor parameter
- Pagination loop logic
- Cursor tracking and advancement detection
- `_cursor_from()` helper function

**Simplified to**:

```python
def _fetch_session_sync(self, symbol: str, trading_date: date) -> pd.DataFrame:
    quote = self._quote_factory(symbol=symbol, source="VCI")
    kwargs: dict[str, Any] = {
        "start": trading_date.isoformat(),
        "end": trading_date.isoformat(),
    }
    frame = quote.intraday(**kwargs)
    return frame if isinstance(frame, pd.DataFrame) else pd.DataFrame(frame)
```

### Rationale

**Why single-call is acceptable**:

1. **Scope**: Fetching a single trading day's worth of intraday trades
2. **Volume**: Even active symbols rarely exceed 10,000 trades per day
3. **Memory**: Complete day's trades fit comfortably in memory (~1-5MB per symbol)
4. **Simplicity**: Eliminates complex pagination state management
5. **API Contract**: Matches vnstock 4.x design (provider removed pagination for a reason)

**Performance characteristics**:

- Single HTTP request vs. multiple paginated requests
- Reduced error surface area (no cursor advancement failures)
- Faster execution for typical trading days
- Async execution in thread pool preserves non-blocking behavior

### Testing Considerations

1. **Functional Correctness**:

   - Verify all trades for a complete trading day are returned
   - Confirm [`normalize_intraday_trades()`](../../apps/ingestor/app/handlers/intraday_eod.py:108) receives complete data
   - Check reconciliation against EOD data passes

2. **Memory Usage**:

   - Monitor memory consumption during high-volume trading days
   - Typical expectation: 1-10MB per symbol session

3. **Error Handling**:
   - vnstock API errors propagate correctly
   - Empty DataFrames handled (no trades for the day)
   - Network timeouts caught and reported

## Alternative Solutions Considered

### Option 1: Pin vnstock to 3.x (Rejected)

- **Pro**: Preserves pagination logic
- **Con**: Blocks security updates, bug fixes, and feature improvements
- **Con**: vnstock 3.x may be unmaintained

### Option 2: Implement client-side chunking (Rejected)

- **Pro**: Limits memory per batch
- **Con**: Complex logic for splitting date ranges
- **Con**: No API support for time-based pagination
- **Con**: Over-engineering for typical use case

### Option 3: Stream processing (Future Enhancement)

- If trading days with >100k trades become common, consider streaming
- Implement chunked processing of DataFrame rows
- Likely unnecessary for Vietnam market trading volumes

## Related Work

- [Intraday EOD Flow](../flows/005-intraday-eod.md)
- [P9 Intraday EOD Implementation Plan](../plans/013-intraday-eod.md)
- [`intraday_eod.py` handler](../../apps/ingestor/app/handlers/intraday_eod.py)

## Status

**Implemented**: 2026-09-15  
**Verification**: Requires testing with actual P9-I1 intraday EOD job execution
