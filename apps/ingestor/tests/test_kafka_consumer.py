from app.kafka_consumer import consume_loop


def test_kafka_consumer_exports_consume_loop():
    assert callable(consume_loop)