ARG ALPINE_VERSION=3.20
FROM alpine:${ALPINE_VERSION}

RUN sed -i 's#https\?://dl-cdn.alpinelinux.org#https://mirrors.aliyun.com#g' /etc/apk/repositories \
    && apk add --no-cache openssh-client

CMD ["sh"]
