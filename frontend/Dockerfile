# ---- Taskora Web (Angular 22) ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.29-alpine
# Gabarit rendu par envsubst au démarrage (amont d'identité commutable sans reconstruction).
ENV IDENTITY_UPSTREAM=api:80 \
    IDENTITY_WRITE_FREEZE=off
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
