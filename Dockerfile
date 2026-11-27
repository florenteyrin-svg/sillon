# Sillon server image: one small Go binary + ffmpeg (conversion and probing of non-FLAC files).
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/sillon ./cmd/sillon

FROM alpine:3.22
RUN apk add --no-cache ffmpeg tzdata ca-certificates
COPY --from=build /out/sillon /usr/local/bin/sillon
ENV SILLON_MUSIC_DIR=/music \
    SILLON_DATA_DIR=/data \
    SILLON_LISTEN=:4747
EXPOSE 4747
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:4747/api/health >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/sillon"]
