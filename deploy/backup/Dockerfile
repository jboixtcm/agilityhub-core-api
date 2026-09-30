FROM mongo:7.0
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv age \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/backup \
    && /opt/backup/bin/pip install --no-cache-dir boto3==1.35.99 pymongo==4.10.1
COPY backup.py /opt/backup/backup.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["/opt/backup/bin/python", "/opt/backup/backup.py"]
