const fs = require('fs');
const cp = require('child_process');
let ok = 0, total = 0;
const check = (label, pass) => { pass ? ok++ : console.log('  ✗ ' + label); total++; };

console.log('=== 1. 凭据安全 ===');
const env = fs.readFileSync('d:/vibeMusic/.env', 'utf8');
['MYSQL_ROOT_PASSWORD', 'ES_PASSWORD', 'JWT_SECRET', 'REDIS_PASSWORD'].forEach(k => {
  const v = env.match(new RegExp('^' + k + '=(.+)$', 'm'));
  const good = v && v[1].length > 8 && v[1] !== '123456' && !v[1].startsWith('ChangeMe') && !v[1].startsWith('<your');
  check(k, good);
});

['MUSIC_NETEASE_COOKIE', 'MUSIC_QQ_COOKIE'].forEach(k => {
  const v = env.match(new RegExp('^' + k + '=(.+)$', 'm'));
  check(k, v && v[1].length > 30 && !v[1].startsWith('<'));
});

const sp = fs.readFileSync('d:/vibeMusic/vibemusic-web/.env.production', 'utf8');
check('Sentry DSN', sp.includes('ingest.sentry.io') && !sp.includes('YOUR_SENTRY'));

console.log('\n=== 2. 关键文件 ===');
['musicapi/.dockerignore', 'vibeMusic-backend/.dockerignore', '.github/workflows/backend-ci.yml', '.github/workflows/frontend-ci.yml', 'nginx/certs/fullchain.pem', 'nginx/certs/privkey.pem', 'docs/DEVELOPMENT_PLAN.md'].forEach(f => check(f, fs.existsSync('d:/vibeMusic/' + f)));

console.log('\n=== 3. 配置一致性 ===');
const dc = fs.readFileSync('d:/vibeMusic/docker-compose.yml', 'utf8');
check('资源限制', dc.includes('deploy:'));
check('ES xpack安全', dc.includes('xpack.security.enabled=true'));
check('Redis密码', dc.includes('requirepass'));
check('CORS', dc.includes('CORS_ORIGINS'));

console.log('\n=== 4. 代码质量 ===');
let r = cp.spawnSync('findstr', ['/s', '/m', 'Page', 'd:/vibeMusic/vibeMusic-backend/src/main/java/com/vibemusic/service/FavoriteService.java']);
check('FavoriteService用Page分页', r.stdout.toString().trim().length > 0);

r = cp.spawnSync('findstr', ['/s', '/m', 'rollbackFor', 'd:/vibeMusic/vibeMusic-backend/src/main/java/com/vibemusic/service/UserService.java']);
check('UserService有rollbackFor', r.stdout.toString().trim().length > 0);

check('StreamController存在', fs.existsSync('d:/vibeMusic/vibeMusic-backend/src/main/java/com/vibemusic/controller/StreamController.java'));
check('PlayHistoryController存在', fs.existsSync('d:/vibeMusic/vibeMusic-backend/src/main/java/com/vibemusic/controller/PlayHistoryController.java'));
check('JsonCacheService存在', fs.existsSync('d:/vibeMusic/vibeMusic-backend/src/main/java/com/vibemusic/service/JsonCacheService.java'));
check('Caffeine引入', fs.readFileSync('d:/vibeMusic/vibeMusic-backend/pom.xml', 'utf8').includes('caffeine'));

console.log('\n=== 5. Nginx安全 ===');
const ng = fs.readFileSync('d:/vibeMusic/nginx/nginx.conf', 'utf8');
check('CSP头', ng.includes('Content-Security-Policy'));
check('Actuator拦截', ng.includes('location /actuator/') && ng.includes('return 403'));

console.log('\n=== 6. Docker状态 ===');
r = cp.spawnSync('docker', ['ps', '--format', '{{.Names}}']);
const containers = r.stdout.toString().trim().split('\n');
check('MySQL运行中', containers.some(c => c.includes('mysql')));
check('Redis运行中', containers.some(c => c.includes('redis')));

console.log('\n' + '='.repeat(30) + '\n结果: ' + ok + '/' + total + ' 通过\n' + '='.repeat(30));
