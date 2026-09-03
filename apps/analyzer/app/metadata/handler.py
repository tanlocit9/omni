from py_common.storage.metadata_sync import (
    MetadataSynchronizer,
    MetadataSyncResult,
    MetadataSyncTarget,
)

from app.metadata.messages import SyncMetadataJobMessage


class MetadataSyncJobHandler:
    def __init__(self, synchronizer: MetadataSynchronizer) -> None:
        self._synchronizer = synchronizer

    async def handle(self, message: SyncMetadataJobMessage) -> MetadataSyncResult:
        target = (
            MetadataSyncTarget(
                dataset=message.target.dataset,
                partition=message.target.partition,
            )
            if message.target is not None
            else None
        )
        return await self._synchronizer.sync(
            target=target,
            execution_id=message.execution_id,
        )
