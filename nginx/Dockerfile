FROM node:22-alpine AS frontend-build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm install
COPY frontend/ .
RUN npm run build

FROM nginx:stable
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/backend-upstream.inc /etc/nginx/conf.d/backend-upstream.inc
COPY --from=frontend-build /app/dist /usr/share/nginx/client
