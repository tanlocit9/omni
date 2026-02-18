"""Hello unit test module."""

from analytics.hello import hello


def test_hello():
    """Test the hello function."""
    assert hello() == "Hello analytics"
