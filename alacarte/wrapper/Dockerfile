FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS supervisor-builder
WORKDIR /src
COPY supervisor/ .
ARG TARGETOS TARGETARCH
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -ldflags="-s -w" -o /supervisor .

# Sillon: the Android runtime files (rootfs/system, ~116 MB, unchanged) are not copied into the Sillon
# repository; they are fetched from à la carte's repository at a fixed commit.
FROM alpine:3.22 AS rootfs-fetch
ARG ALACARTE_ROOTFS_COMMIT=5c3f06bca33d0062cc31df331a176ec37a4ed345
RUN apk add --no-cache git \
  && git init -q /src && cd /src \
  && git remote add origin https://github.com/norifm/alacarte.git \
  && git config core.sparseCheckout true \
  && echo "wrapper/rootfs/" > .git/info/sparse-checkout \
  && git fetch -q --depth 1 origin "$ALACARTE_ROOTFS_COMMIT" \
  && git checkout -q FETCH_HEAD \
  && test -d wrapper/rootfs/system/lib64

FROM debian:stable-slim

RUN apt-get update \
  && apt-get install -y --no-install-recommends gcc libc6-dev \
  && rm -rf /var/lib/apt/lists/*

COPY --from=rootfs-fetch /src/wrapper/rootfs /app/rootfs
COPY ./rootfs /app/rootfs
COPY ./wrapper /app
COPY ./preload_host.c ./preload_libdl_stub.c ./wrapper_login_fix.c ./wrapper-entrypoint.sh /app/
COPY --from=supervisor-builder /supervisor /app/supervisor
WORKDIR /app

RUN gcc -shared -fPIC -O2 \
      -Wl,-soname,libwrapper_login_fix.so \
      -o /app/libwrapper_login_fix.so /app/preload_host.c \
  && mkdir -p /app/rootfs/app \
  && gcc -shared -fPIC -nostdlib -Wl,--hash-style=sysv \
      -Wl,-soname,libdl.so -o /tmp/libdl.so /app/preload_libdl_stub.c \
  && gcc -shared -fPIC -O2 -nostdlib -fno-stack-protector -fno-builtin \
      -fno-asynchronous-unwind-tables -Wl,--hash-style=sysv \
      -Wl,-soname,libwrapper_login_fix.so \
      -L/app/rootfs/system/lib64 -L/tmp \
      -o /app/rootfs/app/libwrapper_login_fix.so /app/wrapper_login_fix.c \
      -Wl,--no-as-needed -lc -ldl \
  && chmod +x /app/wrapper-entrypoint.sh /app/supervisor

ENTRYPOINT ["/app/wrapper-entrypoint.sh"]
CMD ["-H", "0.0.0.0"]

EXPOSE 10020 20020 30020 40020
