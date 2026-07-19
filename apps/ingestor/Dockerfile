FROM python:3.14-slim

WORKDIR /app

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

# Copy dependency files
COPY apps/ingestor/pyproject.toml apps/ingestor/uv.lock* /app/

# Install dependencies
RUN uv pip install --system -r pyproject.toml

# Copy application code
COPY apps/ingestor /app

# Run the async event consumer
CMD ["python", "-m", "app.main"]