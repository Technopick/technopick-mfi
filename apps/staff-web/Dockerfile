# Staff web app: static files served by nginx, /api proxied to core banking on the same origin.
# Build first: npm ci && npm run build   (config.json is mounted per environment)
FROM nginx:1.29-alpine
# The nginx image substitutes CORE_URL into the template at start.
ENV CORE_URL=http://core:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY dist/ /usr/share/nginx/html/
EXPOSE 8080
