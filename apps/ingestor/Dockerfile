FROM python:3.14-slim

WORKDIR /workspace

# Prevent Python from writing pyc files and enable unbuffered output
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_SYSTEM_PYTHON=1

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.cargo/bin:$PATH"

# Copy workspace dependency files
COPY pyproject.toml uv.lock* ./
COPY libs/py-common/pyproject.toml libs/py-common/uv.lock* libs/py-common/
COPY apps/ingestor/pyproject.toml apps/ingestor/uv.lock* apps/ingestor/

# Install ingestor and workspace dependencies
RUN uv pip install --system --editable apps/ingestor

# Copy application and shared library code
COPY libs/py-common libs/py-common
COPY apps/ingestor apps/ingestor
COPY configs configs

WORKDIR /workspace/apps/ingestor

# Run the async event consumer
CMD ["python", "-m", "app.main"]
