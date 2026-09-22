# 阶段一：编译
FROM golang:1.21-alpine AS builder

# 设置 Go 代理（国内加速可选）
ENV GOPROXY=https://goproxy.cn,direct

WORKDIR /app

# 先复制依赖文件，利用 Docker 缓存层
COPY go.mod go.sum ./
RUN go mod download

# 复制源码并编译
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/server .

# 阶段二：运行
FROM alpine:3.19

# 设置时区（可选）
RUN apk --no-cache add ca-certificates tzdata
ENV TZ=Asia/Shanghai

WORKDIR /app

# 从编译阶段复制二进制文件
COPY --from=builder /app/server .

EXPOSE 8080

CMD ["./server"]