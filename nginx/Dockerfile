FROM nginx:1.27-alpine 
COPY vibemusic-web/dist /usr/share/nginx/html 
COPY nginx/k8s-nginx.conf /etc/nginx/conf.d/default.conf 
EXPOSE 80 
CMD ["nginx", "-g", "daemon off;"] 
