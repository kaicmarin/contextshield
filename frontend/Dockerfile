FROM nginx:1.27-alpine
COPY nginx-cache-map.conf /etc/nginx/conf.d/00-cache-map.conf
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY index.html sistema.html /usr/share/nginx/html/
COPY loja /usr/share/nginx/html/loja
COPY banco /usr/share/nginx/html/banco
COPY ops /usr/share/nginx/html/ops
COPY assets /usr/share/nginx/html/assets
COPY public/ /usr/share/nginx/html/
# API_URL: endereço da core-api com esquema (no compose é o nome do serviço)
ENV API_URL=http://core-api:8080 PORT=80
EXPOSE 80
