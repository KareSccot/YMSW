# ariba-srmp-ui 前端 SPA 镜像
# CI build-app 用 vite 编译代码产出 dist/，build-container 用本 Dockerfile 打包成镜像推到 TCR。

FROM nginx:1.29.4-alpine

# dist/ 是 build-app 的 CI artifact，直接 COPY 进 nginx html 目录
# 用 COPY 不用 ADD：cicd-template 传的是目录不是 zip，ADD 的自动解压不需要
COPY dist/ /usr/share/nginx/html/

# nginx 站点配置（SPA 路由回退 + 缓存策略，见 nginx.conf）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 80 端口：SSL 终止在 K8s Ingress 层，容器内只跑 HTTP
ARG SERVICE_PORT=80
EXPOSE ${SERVICE_PORT}

# nginx 前台运行（不加 daemon off 容器会退出）
CMD ["nginx", "-g", "daemon off;"]