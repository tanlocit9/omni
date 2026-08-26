from py_common.storage.metadata_sync import EodMetadataSynchronizer, MetadataSyncResult

from app.metadata.messages import SyncMetadataJobMessage


class MetadataSyncJobHandler:
    def __init__(self, synchronizer: EodMetadataSynchronizer) -> None:
        self._synchronizer = synchronizer

    async def handle(self, message: SyncMetadataJobMessage) -> MetadataSyncResult:
        return await self._synchronizer.sync(execution_id=message.execution_id)
