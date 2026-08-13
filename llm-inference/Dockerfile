FROM ollama/ollama:0.32.6

LABEL org.opencontainers.image.title="Delivery Harness Ollama runtime" \
      org.opencontainers.image.description="Pinned local LLM runtime for Delivery Harness"

# Pre-pull a model during build (optional, can also pull at runtime)
# RUN ollama pull qwen2.5:7b

EXPOSE 11434

VOLUME /root/.ollama

ENTRYPOINT ["/bin/ollama"]
CMD ["serve"]
