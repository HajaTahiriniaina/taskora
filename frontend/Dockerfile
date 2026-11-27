# ---- Taskora Web (Angular 22) ----
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de
# Gabarit rendu par envsubst au démarrage (amont d'identité commutable sans reconstruction).
# Idem pour les tranches de board-service (board-migration.md §5.2) : toutes vers Symfony par défaut.
ENV IDENTITY_UPSTREAM=api:80 \
    IDENTITY_WRITE_FREEZE=off \
    BOARD_T1_UPSTREAM=api:80 \
    BOARD_T2_UPSTREAM=api:80 \
    BOARD_T3_UPSTREAM=api:80 \
    BOARD_T4_UPSTREAM=api:80 \
    BOARD_T5_UPSTREAM=api:80
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
