# Zero-dependency service: the image is the runtime plus two files.
FROM node:22-alpine

WORKDIR /app
COPY redirector.js test-vectors.json ./

ENV NODE_ENV=production \
    PORT=8080
EXPOSE 8080
USER node

CMD ["node", "redirector.js"]
