"""Actual broker outage, truthful health and automatic same-group recovery."""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import time
import uuid

import pytest
from aiokafka import TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from fastapi.testclient import TestClient
from test_kafka_offsets import BROKER_CONTAINERS, publish_command
from test_kafka_offsets import broker as broker

from py_common.kafka.factory import KafkaClientFactory
from py_common.kafka.job_status_service import JobStatusKafkaService
from py_common.messaging import JobStatusMessage, utc_now
from py_common.runtime.worker import create_worker_app

pytestmark = pytest.mark.skipif(
    os.environ.get("OMNI_KAFKA_INTEGRATION") != "1",
    reason="Requires explicit py-common:test-integration and isolated Docker broker",
)


class Worker(JobStatusKafkaService):
    def __init__(self, settings, topic, status_topic, group, *, blocked):
        super().__init__(
            kafka_settings=settings,
            input_topic=topic,
            status_topic=status_topic,
            group_id=group,
            service_name="recovery-test",
        )
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.attempts = 0
        if not blocked:
            self.release.set()

    async def process_payload(self, payload):
        self.attempts += 1
        self.entered.set()
        await self.release.wait()
        status = JobStatusMessage(
            execution_id="00000000-0000-0000-0000-000000000001",
            work_type="GLOBAL",
            work_key="test",
            status="SUCCESS",
            started_at=utc_now(),
            finished_at=utc_now(),
        )
        # Only the test's timeout is shortened; publisher and broker are real.
        await asyncio.wait_for(self.publish_status(status), 1)
        return status


@pytest.mark.asyncio
async def test_broker_outage_automatic_recovery_and_truthful_health(
    broker,
    record_property,
):
    topic, status_topic, group = (
        prefix + uuid.uuid4().hex for prefix in ("jobs-", "status-", "group-")
    )
    sent = await publish_command(broker, topic)
    admin = AIOKafkaAdminClient(bootstrap_servers=broker.bootstrap_servers)
    await admin.start()
    try:
        await admin.create_topics([NewTopic(status_topic, 1, 1)])
    finally:
        await admin.close()
    worker = Worker(broker, topic, status_topic, group, blocked=True)
    await worker.start()
    await asyncio.wait_for(worker.entered.wait(), 20)
    container = BROKER_CONTAINERS[broker.bootstrap_servers]
    name = subprocess.check_output(
        ["docker", "inspect", "--format", "{{.Name}}", container], text=True, timeout=15
    ).strip()
    assert name.startswith("/omni-offset-test-")
    subprocess.run(
        ["docker", "pause", container],
        check=True,
        stdout=subprocess.DEVNULL,
        timeout=15,
    )
    try:
        worker.release.set()
        deadline = time.monotonic() + 10
        while worker.is_running:
            assert time.monotonic() < deadline
            await asyncio.sleep(0.05)
        assert not worker._task.done()

        async def waiting_worker():
            await asyncio.Future()

        app = create_worker_app(
            waiting_worker,
            title="Recovery observation",
            description="Disposable integration",
            version="test",
            readiness=lambda: worker.is_running,
        )
        with TestClient(app) as client:
            health = client.get("/health")
            record_property("failedWorkerHealthStatus", health.status_code)
            record_property("automaticRecovery", True)
            assert health.status_code == 503
    finally:
        subprocess.run(
            ["docker", "unpause", container],
            check=True,
            stdout=subprocess.DEVNULL,
            timeout=15,
        )
    tp = TopicPartition(topic, sent.partition)
    admin = AIOKafkaAdminClient(bootstrap_servers=broker.bootstrap_servers)
    await admin.start()
    try:
        offsets = await admin.list_consumer_group_offsets(group)
        assert tp not in offsets or offsets[tp].offset < sent.offset + 1
    finally:
        await admin.close()
    observer = KafkaClientFactory.create_consumer(
        broker, status_topic, "observer-" + uuid.uuid4().hex
    )
    await observer.start()
    try:
        deadline = time.monotonic() + 30
        while (
            not worker.is_running
            or worker._consumer is None
            or await worker._consumer.committed(tp) != sent.offset + 1
        ):
            assert not worker._task.done()
            assert time.monotonic() < deadline
            await asyncio.sleep(0.1)
        record = await asyncio.wait_for(observer.getone(), 15)
        assert (
            json.loads(record.value)["executionId"]
            == "00000000-0000-0000-0000-000000000001"
        )
        assert json.loads(record.value)["status"] == "SUCCESS"
        assert worker.attempts >= 2
        assert worker.is_running
        print(
            "RECOVERY_OBSERVATION: offset retained; automatic restart recovered; "
            f"failed-worker HTTP health observed {health.status_code}"
        )
    finally:
        await worker.stop()
        await observer.stop()
