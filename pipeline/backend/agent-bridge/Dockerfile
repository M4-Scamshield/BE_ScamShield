# agent 다리. agent 코드는 팀 레포를 고치지 않고 빌드 컨텍스트 "agent" 로 받아 복사만 한다.
#   docker build --build-context agent=../../team/scam-agent .
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.13 /uv /usr/local/bin/uv
WORKDIR /agent
COPY --from=agent pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY --from=agent scamshield ./scamshield
COPY server.py /bridge/server.py
RUN useradd --system --uid 10001 agent
USER agent
ENV AGENT_DIR=/agent PATH=/agent/.venv/bin:$PATH PYTHONUNBUFFERED=1
EXPOSE 9000
# 0.0.0.0 은 컨테이너 안 주소다. compose 에서 ports: 를 두지 않아 내부망(app)만 닿는다
CMD ["python", "/bridge/server.py", "--host", "0.0.0.0", "--port", "9000"]
