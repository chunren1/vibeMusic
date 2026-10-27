#!/usr/bin/env node
// 运行时契约守卫（2026-10-27 审计 P2）：三处 node 主版本必须一致 ——
//   1) musicapi/package.json 的 engines.node
//   2) musicapi/Dockerfile 的 FROM node:<major>（镜像 pin 了 digest，改主版本必然连 tag 一起改）
//   3) 当前运行时（CI 的 setup-node / 本机 node）
//
// 为什么需要机器断言：这三处任何一处被单独升级，都会出现「本地+CI 全绿、线上 alpine 起不来」
// 或反过来的假绿；此前只有人肉约定（README 说 24，engines 说 >=24，没人核对）。
//
// 口径是**主版本相等**，不是 semver 满足：engines 写 ">=24" 只表示最低要求，
// 真升到 26 时必须把 engines 一起改成 ">=26"，否则这条守卫会红 —— 这是有意的，
// 逼改动方明确表态，而不是让 ">=" 悄悄放行一个没在镜像里验证过的主版本。
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const engines = pkg.engines?.node ?? '';
const dockerfile = readFileSync(join(root, 'Dockerfile'), 'utf8');
const fromLine = dockerfile.split('\n').find((line) => /^FROM\s+node:/i.test(line)) ?? '';

const engineMajor = Number((engines.match(/>=\s*(\d+)/) ?? [])[1] ?? NaN);
const imageMajor = Number((fromLine.match(/^FROM\s+node:(\d+)/i) ?? [])[1] ?? NaN);
const runtimeMajor = Number(process.versions.node.split('.')[0]);

const problems = [];
if (!Number.isInteger(engineMajor)) problems.push(`package.json engines.node 解析不出主版本: "${engines}"`);
if (!Number.isInteger(imageMajor)) problems.push(`Dockerfile 里找不到 "FROM node:<major>": "${fromLine.trim()}"`);
if (problems.length === 0) {
    if (runtimeMajor !== engineMajor) {
        problems.push(`当前运行时 node ${process.versions.node} 主版本 ${runtimeMajor} ≠ engines 要求 ${engineMajor}`);
    }
    if (imageMajor !== engineMajor) {
        problems.push(`镜像 node:${imageMajor} ≠ engines 要求 ${engineMajor}`);
    }
}

if (problems.length > 0) {
    console.error('运行时契约不成立：');
    for (const p of problems) console.error(`  - ${p}`);
    console.error('  改一处要同时改三处：musicapi/package.json engines、musicapi/Dockerfile FROM、'
        + '.github/workflows/{ci,deploy}.yml 的 node-version');
    process.exit(1);
}

console.log(`运行时契约通过: engines=${engines} 镜像=node:${imageMajor} 当前=${process.versions.node}`);
