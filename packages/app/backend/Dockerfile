# Multi-stage build. Image tags confirmed on Docker Hub at implementation time.
FROM golang:1.25-alpine AS builder

WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .

# Static binary so it runs on the scratch-based runtime image without libc.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/collector ./cmd/collector

FROM alpine:3.22

RUN apk add --no-cache ca-certificates \
    && addgroup -S collector \
    && adduser -S -G collector collector

COPY --from=builder /out/collector /usr/local/bin/collector
RUN mkdir -p /data/audio && chown -R collector:collector /data

VOLUME ["/data"]
EXPOSE 8080

USER collector
ENTRYPOINT ["/usr/local/bin/collector"]
