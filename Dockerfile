# Stage 1: Build the thin Go whatsmeow bridge
FROM golang:1.26-bookworm AS go-builder
WORKDIR /build/bridge
COPY bridge/go.mod ./
COPY bridge/main.go ./
RUN go get github.com/mattn/go-sqlite3@latest && go get go.mau.fi/whatsmeow@latest && go get google.golang.org/protobuf@latest && go mod tidy
RUN CGO_ENABLED=1 GOOS=linux go build -ldflags="-s -w" -o /out/wa-bridge .

# Stage 2: Build the Rust Core (falkeai-core)
FROM rust:1.85-bookworm AS rust-builder
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY Cargo.toml ./
COPY src ./src
RUN cargo build --release && cp target/release/falkeai-core /out-falkeai-core

# Stage 3: Minimal runtime (~35 MB RAM at runtime, zero Node.js)
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl3 sqlite3 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=go-builder /out/wa-bridge /app/wa-bridge
COPY --from=rust-builder /out-falkeai-core /app/falkeai-core
ENV WA_BRIDGE_BIN=/app/wa-bridge
ENV PORT=8080
ENV WEBSITES_PORT=8080
EXPOSE 8080
CMD ["/app/falkeai-core"]
