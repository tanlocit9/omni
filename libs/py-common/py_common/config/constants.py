"""Shared constants for Omni services."""

from enum import StrEnum


class Timeframe(StrEnum):
    """Supported timeframe intervals for indicator calculation.

    These timeframe values are used in S3 path construction and must match
    the pattern defined in configs/shared/s3-paths.yaml.

    Examples:
        >>> Timeframe.ONE_DAY
        '1d'
        >>> Timeframe.ONE_HOUR.value
        '1h'
    """

    # Intraday timeframes
    ONE_MINUTE = "1m"
    FIVE_MINUTES = "5m"
    FIFTEEN_MINUTES = "15m"
    THIRTY_MINUTES = "30m"
    ONE_HOUR = "1h"
    FOUR_HOURS = "4h"

    # Daily and above
    ONE_DAY = "1d"
    ONE_WEEK = "1w"
    ONE_MONTH = "1M"

    @classmethod
    def validate(cls, value: str) -> Timeframe:
        """Validate and convert string to Timeframe enum.

        Args:
            value: Timeframe string to validate

        Returns:
            Validated Timeframe enum

        Raises:
            ValueError: If value is not a valid timeframe

        Examples:
            >>> Timeframe.validate("1d")
            <Timeframe.ONE_DAY: '1d'>
            >>> Timeframe.validate("invalid")
            Traceback (most recent call last):
                ...
            ValueError: Invalid timeframe 'invalid'.
            Must be one of: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M
        """
        try:
            return cls(value)
        except ValueError:
            valid = ", ".join(t.value for t in cls)
            raise ValueError(
                f"Invalid timeframe '{value}'. Must be one of: {valid}"
            ) from None


ENABLED_INDICATOR_TIMEFRAMES: frozenset[Timeframe] = frozenset({Timeframe.ONE_DAY})


def validate_indicator_timeframe(value: Timeframe | str) -> Timeframe:
    """Validate a timeframe for v1 indicator calculation.

    The full Timeframe enum remains canonical for known interval validation, while
    this rule defines which known intervals are enabled for indicator jobs.
    """
    timeframe = Timeframe.validate(value) if isinstance(value, str) else value
    if not isinstance(timeframe, Timeframe):
        raise ValueError(f"Invalid timeframe '{value}'. Must be a Timeframe or string")
    if timeframe not in ENABLED_INDICATOR_TIMEFRAMES:
        enabled = ", ".join(t.value for t in ENABLED_INDICATOR_TIMEFRAMES)
        raise ValueError(
            f"Timeframe '{timeframe.value}' is not enabled for indicator calculation. "
            f"Enabled timeframes: {enabled}"
        )
    return timeframe


class ConsumerGroup(StrEnum):
    """Standard consumer group prefixes for Kafka consumers.

    These prefixes ensure consistent naming across services and prevent
    consumer group ID collisions.

    Examples:
        >>> ConsumerGroup.INGESTOR.for_topic("symbols-sync")
        'omni-ingestor-symbols-sync'
        >>> ConsumerGroup.ANALYZER.for_topic("calculate-indicators")
        'omni-analyzer-calculate-indicators'
    """

    INGESTOR = "omni-ingestor"
    ANALYZER = "omni-analyzer"
    PLATFORM = "omni-platform"

    def for_topic(self, topic: str) -> str:
        """Generate full consumer group ID for a topic.

        Combines the service prefix with the topic name to create a unique
        consumer group identifier.

        Args:
            topic: Kafka topic name

        Returns:
            Full consumer group ID: {prefix}-{topic}

        Examples:
            >>> ConsumerGroup.INGESTOR.for_topic("symbols-sync")
            'omni-ingestor-symbols-sync'
        """
        return f"{self.value}-{topic}"
